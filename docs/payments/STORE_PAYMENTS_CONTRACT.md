# Store payments contract (Google Play + App Store), V1

Status: CONTRACT (E1, 2026-10-19). Implementation follows on this branch, OFF by default (`PAYMENTS_STORE_GOOGLE_PLAY_ENABLED` / `PAYMENTS_STORE_APP_STORE_ENABLED`).
Owner: OposiBot (E1). OposiControl audits it (checklist: Control's `docs/audit-v1/STORE_PAYMENTS_AUDIT_REQUIREMENTS.md`, PAY-R1..R6, D1..D10) and
owns the admin side (`admin_payment_order_resolve`, orders/refunds UI, prices). Client side: OposiBot `docs/payments/STORE_BILLING_CLIENT.md` (E4).
Sources opened 2026-10-19 (listed at the end). Migration: `supabase/migrations/20261022102600_v1_store_payments.sql`.

## 1. Principles

- The server is the only authority. The client never sends a price, an OT quantity, a user id or a free SKU: it sends the store proof and the store
  product id; the server maps the product id through config (`PAYMENTS_STORE_PRODUCTS`) to a `productos_comerciales.codigo`, and the database
  freezes quantity/price from the catalogue.
- A store purchase becomes an existing **order** (`compras_usuario`, provider `google_play` / `app_store`, `identificador_transaccion` = store key) and
  is credited by the existing `app_credit_payment_order` (one ledger row per order, event dedup in `eventos_pago`). Refunds go through the existing
  `app_refund_payment_order` (policy P2 and the SEC-V1-05 hold, unchanged). Subscriptions go to `suscripciones`; the existing projection
  (`sincronizar_estado_premium_usuario`) keeps `estado_premium_usuario` and therefore D7-02 / plan access.
- Purchase key: Google = `purchaseToken` (never `orderId`: some purchases have none); Apple = `transactionId` (consumables) and
  `originalTransactionId` (subscriptions). UNIQUE (provider, key) in the database binds a purchase to ONE user forever.
- Every store notification is persisted in an idempotent inbox (`tienda_bandeja`) **before** the 2xx (ECO-02). Processing failures (store API down,
  refunds switched off, unknown account) leave it `pendiente`; the reconciliation job retries. Nothing is answered 2xx without being stored.

## 2. Account binding

`GET /api/v1/app/store/account-token` returns `{accountToken}`: a random UUID v4 stored in `tienda_cuentas` (one per user, stable, no PII, not derived
from the user id). The client passes it as Play `obfuscatedAccountId` (<= 64 chars) and Apple `appAccountToken` (UUID). The server checks, in the
database, that the token echoed by the store maps to the JWT user. Difference from the brief ("hash of usuario_id"): a stored random UUID cannot be
recomputed from a leaked user id and matches E4's contract ("opaque UUID issued by the server").

## 3. Client endpoints (JWT; `Cache-Control: no-store`; per-user rate limit `ORDER_WRITE` / `ORDER_READ`)

| Id | Route | Notes |
|---|---|---|
| S-01 | `GET /api/v1/app/store/account-token` | `{accountToken}` |
| S-02 | `GET /api/v1/app/store/products` | `{items:[{storeProductId, platform, kind, productCode}]}` from config; only platforms that are enabled |
| S-03 | `POST /api/v1/app/store/purchases/verify` | `Idempotency-Key` required. Body `{platform, storeProductId, kind, token}` (`token` = Play `purchaseToken` or StoreKit 2 `jwsRepresentation`, max 32 KiB, PAY-R2). `200 {result: GRANTED|PENDING, kind, productCode, balance, premium}` |
| S-04 | `POST /api/v1/app/store/purchases/restore` | Body `{platform, purchases:[{storeProductId, kind, token}]}` (1..20). `200 {items:[{storeProductId, result, code?}]}`; each item = S-03 without its own key |
| S-05 | `GET /api/v1/app/store/entitlements` | `{balance, premium:{active, state, productCode?, provider?, until?}}` |

`platform` = `GOOGLE_PLAY` | `APP_STORE`; `kind` = `CONSUMABLE` | `SUBSCRIPTION`. S-03 is idempotent twice: by `Idempotency-Key` (same body = same
answer) and by store key in the database (a second verify of a credited token answers GRANTED with the same balance, never a second credit).

Difference from E4: E4 expects `REJECTED {code}` in a 200. The canonical API answers definitive rejections as 4xx with the canonical envelope; the
client maps 409/422 to `REJECTED{code}`, 401 to "session lost", 5xx/transport to "retry". `PENDING` (Play pending / not yet PURCHASED) is a 200: no
grant, no finish. Server-side finish: the server consumes (packs) / acknowledges (subscriptions) on Play after the credit commits; the client's later
consume then answers `ITEM_NOT_OWNED` (settled), as E4 already handles.

### Error codes (all with zero rows written)

| Code | HTTP | When |
|---|---|---|
| `PAYMENT_PROVIDER_UNAVAILABLE` | 422 | platform disabled / not configured |
| `STORE_PRODUCT_UNKNOWN` | 422 | product id not in `PAYMENTS_STORE_PRODUCTS`, or kind does not match, or the store product differs from the one sent |
| `STORE_PURCHASE_INVALID` | 422 | bad JWS / foreign x5c chain, wrong package/bundle, quantity > 1, cancelled, refunded/revoked, malformed |
| `STORE_ACCOUNT_MISMATCH` | 422 | store account token absent, unknown, or not the caller's |
| `STORE_PURCHASE_BOUND_TO_OTHER` | 409 | the purchase / subscription is already bound to another user (D5 default) |
| `STORE_SANDBOX_REJECTED` | 422 | sandbox/test purchase where `PAYMENTS_STORE_SANDBOX` does not allow it (D4) |
| `UPSTREAM_UNAVAILABLE` | 503 | store API unreachable: retry with the same key |
| `WEBHOOK_SIGNATURE_INVALID` | 401 | webhook JWS / OIDC invalid (wrong `aud`, `iss`, `email`, unverified email, expired) |

## 4. Webhooks (no JWT; per-IP limit `PAYMENT_WEBHOOK`; body cap 256 KiB; order: limit, size, auth, inbox, process)

- **W-02 `POST /api/v1/webhooks/store/google-play`** (RTDN via Pub/Sub push). `Authorization: Bearer <OIDC JWT>` verified RS256 against Google JWKS
  (`https://www.googleapis.com/oauth2/v3/certs`), `iss` in {`accounts.google.com`, `https://accounts.google.com`}, `aud` = `PAYMENTS_GOOGLE_PUBSUB_AUDIENCE`
  (explicit, required), `email` = `PAYMENTS_GOOGLE_PUBSUB_SERVICE_ACCOUNT`, `email_verified` = true, `exp` valid. Then `message.data` (base64)
  `packageName` must equal `PAYMENTS_GOOGLE_PACKAGE_NAME`. Dedup key: Pub/Sub `messageId`. RTDN carries no state: the server re-reads the purchase
  (`purchases.productsv2.getproductpurchasev2`, `purchases.subscriptionsv2.get`). `voidedPurchaseNotification` = refund (by `purchaseToken`).
  `testNotification` = recorded and ignored.
- **W-03 `POST /api/v1/webhooks/store/app-store`** (App Store Server Notifications V2). Body `{signedPayload}`; JWS verified with Apple's App Store
  Server Library (`SignedDataVerifier`: x5c chain to the Apple root CAs from `PAYMENTS_APPLE_ROOT_CA_PATHS`, Apple OIDs, `bundleId`, `appAppleId`,
  `environment`), then `signedTransactionInfo` / `signedRenewalInfo` verified the same way. Dedup key: `notificationUUID`. State is re-derived from
  the signed transaction (revocationDate, expiresDate), never from the notification type alone.

Answers: `200 {result}` once the event is in the inbox (also when processing is deferred: `result = DEFERRED`); 401 bad auth (nothing written); 503 only
if the inbox write itself failed (nothing stored, the store retries). Apple retries V2 5 times (1, 12, 24, 48, 72 h) and only in production; missed
notifications are recovered with Get Notification History (pending: needs the App Store API key, D9).

## 5. State machine mapping

### Packs (consumables) -> `compras_usuario` + ledger

| Store fact | Action | DB outcome |
|---|---|---|
| Verified PURCHASED, quantity 1, account token = user | `app_store_credit_purchase` creates (or finds) the order `pendiente` keyed by store key, then `app_credit_payment_order(event 'compra:'+sha256(key))` | `CREDITED` once; replays `REPLAY` |
| PENDING (Play) | nothing | `PENDING` (200) |
| Same key, other user | refused | `STORE_PURCHASE_BOUND_TO_OTHER` |
| Refund / void / Apple `REFUND` (revocationDate) | `app_store_refund_purchase` -> `app_refund_payment_order` | `REFUNDED` / `REFUNDED_P2`; order not paid -> `REFUND_HELD` (SEC-V1-05) |
| Refund before the purchase exists (out of order) | inbox row marked `REFUND_ORPHAN_HELD` | a later verify creates the order, holds the refund, credits nothing (`EN_REVISION`) |
| Refund with `PAYMENTS_REFUNDS_ENABLED=false` | inbox `pendiente` (`REFUNDS_DISABLED`), 200 | applied by the job when the switch is on (ECO-02) |
| Apple `REFUND_REVERSED` | `app_store_refund_reversed`: order `reembolsada -> en_revision`, audit `reembolso_revertido` | Control decides (**D3**); no automatic re-credit |
| Apple `CONSUMPTION_REQUEST` | recorded only | no consumption data is sent without consent (**D7**) |
| Account deleted, then void | order gone with the account (FK cascade) -> refund is an orphan, held | **D6** (legal): ledger retention on deletion |

Credit is **by SKU** (PAY-R1, proposal pending **D1**): the store price is not compared (Google `purchases` returns none; Apple says not to use `price`
for reconciliation). The DB passes the frozen catalogue price to `app_credit_payment_order`, and the store-reported price/currency (Apple milliunits,
when present) is kept in `compras_usuario.payload_validacion` for audit only. The daily OT cap and the 5-open-orders limit do NOT apply to store
purchases (the store already charged). Alternative for D1: tolerance per storefront, which only works on Apple.

### Subscriptions -> `suscripciones` (key = Google `purchaseToken` / Apple `originalTransactionId`)

| Google `subscriptionState` | Apple status (from signed tx + renewal info) | `estado` / `fin_motivo` | Access |
|---|---|---|---|
| ACTIVE | active (`expiresDate` future, no revocation) | `activa`, `fin_en` = expiry | yes |
| IN_GRACE_PERIOD | `DID_FAIL_TO_RENEW/GRACE_PERIOD` (`gracePeriodExpiresDate`) | `activa`, `fin_en` = grace end | yes |
| ON_HOLD / PAUSED | billing retry without grace | `pausada` | no |
| CANCELED (auto-renew off, paid time left) | `autoRenewStatus = 0` | `cancelada`, `fin_en` = expiry | until `fin_en` |
| EXPIRED | `EXPIRED` / `GRACE_PERIOD_EXPIRED` | `caducada`, `expiro` | no |
| revoked / voided | `REFUND` / `REVOKE` (revocationDate) | `caducada`, `reembolso` / `revocada` | no, at once |
| PENDING | - | not written | no |

Out of order: each row stores `version_evento` (Google `eventTimeMillis` / API read time; Apple `signedDate`); an older event never overwrites a newer
one (`STALE`). A Google `linkedPurchaseToken` closes the previous row of the same user. Recovery from hold = the next ACTIVE read. Bound to another
user -> 409 (**D5**). Subscriptions in V1 at all: **D8**.

### Play finish and reconciliation (job, `PAYMENTS_STORE_JOB_SECONDS`, default 300)

- Acknowledge/consume only after the DB credit committed; never a PENDING purchase. On failure the row keeps `tienda_finalizada_en = null` and
  `app_store_pending_finalize` returns it to the job (Play refunds after 3 days without acknowledgement).
- Retries `pendiente` inbox rows (exponential: attempts are counted; Control sees `intentos` and `ultimo_error`).
- Daily Voided Purchases poll (`type=1`, 30-day window, quota 6000/day): each void is put in the inbox as event `void:<orderId or token hash>`
  and processed like an RTDN void.

## 6. Environments and sandbox (D4)

`PAYMENTS_STORE_SANDBOX` = `reject` (default) | `allowlist` | `accept`. `accept` is refused at startup unless `APP_ENV` is `local`/`test`.
`allowlist` accepts sandbox purchases only from the user ids in `PAYMENTS_STORE_SANDBOX_USERS` (App Review / testers). Sandbox = Apple
`environment = Sandbox`, Google `testPurchaseContext` / `testPurchase` / `purchaseType = 0`. Every order/subscription stores `entorno`
(`produccion` | `sandbox`); revenue views must filter `entorno = 'produccion'`.

## 7. Startup and secrets (D9)

If a platform is enabled and any of its settings is missing or unreadable, the process exits with code 78 before binding the port. Settings (names
only, `ops/deploy/backend.bot.env.example`): Google `PAYMENTS_GOOGLE_PACKAGE_NAME`, `PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE`,
`PAYMENTS_GOOGLE_PUBSUB_AUDIENCE`, `PAYMENTS_GOOGLE_PUBSUB_SERVICE_ACCOUNT`; Apple `PAYMENTS_APPLE_BUNDLE_ID`, `PAYMENTS_APPLE_APP_APPLE_ID`,
`PAYMENTS_APPLE_ROOT_CA_PATHS`; common `PAYMENTS_STORE_PRODUCTS`, `PAYMENTS_STORE_SANDBOX`, `PAYMENTS_STORE_SANDBOX_USERS`. Files live in
`/etc/oposibots/production`, never in the repo. No token, JWS or account token is logged.

## 8. What Control sees and audits

- Orders: `compras_usuario` with `proveedor in ('google_play','app_store')`, `entorno`, `identificador_transaccion`, `tienda_finalizada_en`; the
  review queue (`en_revision`) is unchanged and `admin_payment_order_resolve` keeps its signature.
- Inbox `tienda_bandeja` (service_role SELECT): every notification with `huella` (sha256 of the raw body), `estado`, `intentos`, `resultado`,
  `ultimo_error`; `REFUND_ORPHAN_HELD` rows are refunds waiting for their purchase.
- Audit rows inside the RPCs: `pago_acreditado`, `pago_reembolsado`, `reembolso_retenido`, `pago_tras_reembolso`, `evento_de_otro_pedido` (ECO-04),
  `reembolso_revertido`, `suscripcion_tienda`.
- ECO-04: an event id already bound to another order sends the order to review (`evento_de_otro_pedido`) instead of a silent REPLAY.
- ECO-11: `service_role` loses INSERT/UPDATE/DELETE/TRUNCATE on `compras_usuario` and `suscripciones`; only SECURITY DEFINER RPCs write them.
- ECO-12 / D6: account deletion still cascades orders and subscriptions (FK of MAIN); a later void is held as an orphan. Retention is a legal decision.

## 9. Owner decisions

D1 credit by SKU (proposed) vs tolerance · D2 refunds on in production · D3 REFUND_REVERSED re-credit · D4 sandbox in production · D5 subscription
transfer between accounts (default 409) · D6 ledger on account deletion · D7 Apple consumption data consent · D8 subscriptions in V1 · D9 secret
custody · D10 OT/EUR equivalence and store fees. Inputs: Play Console service account (androidpublisher scope) + RTDN topic/push subscription with
OIDC audience and service account; App Store Connect bundle id, Apple ID, ASSN V2 URL (production and sandbox), In-App Purchase key (for Notification
History / consumption, later); product ids, base plans and prices per storefront.

## Sources (opened 2026-10-19)

- RTDN reference: https://developer.android.com/google/play/billing/rtdn-reference
- purchases.products (get/acknowledge/consume, ProductPurchase): https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products
- consume URL: https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products/consume
- purchases.productsv2 (ProductPurchaseV2, testPurchaseContext): https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2 and .../purchases.productsv2/getproductpurchasev2
- purchases.subscriptionsv2 (SubscriptionPurchaseV2): https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2
- subscriptions acknowledge: https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptions/acknowledge
- Voided Purchases API: https://developers.google.com/android-publisher/voided-purchases
- Integrate (3 days, never acknowledge PENDING, obfuscatedAccountId, backend consume): https://developer.android.com/google/play/billing/integrate
- Pub/Sub push authentication (OIDC claims): https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions
- Google OpenID configuration (issuer, jwks_uri): https://accounts.google.com/.well-known/openid-configuration
- ASSN V2 notificationType: https://developer.apple.com/documentation/appstoreservernotifications/notificationtype
- Responding to ASSN (200-206, 5 retries, production only, Notification History): https://developer.apple.com/documentation/appstoreservernotifications/responding-to-app-store-server-notifications
- JWSTransactionDecodedPayload (price in milliunits, not for reconciliation; appAccountToken; environment): https://developer.apple.com/documentation/appstoreserverapi/jwstransactiondecodedpayload
- App Store Server Library for Java 5.2.0 (SignedDataVerifier): https://github.com/apple/app-store-server-library-java ; POM https://repo1.maven.org/maven2/com/apple/itunes/storekit/app-store-server-library/5.2.0/app-store-server-library-5.2.0.pom

Still NV (not confirmed in an opened page): exact Apple Transaction History / Subscription Status / Refund History paths; whether `appAccountToken` is
kept on renewals (the server falls back to the subscription row owner); Google access semantics during account hold (we deny); whether
`purchases.products` v1 will be deprecated (productsv2 is used for reads, v1 only for `consume`); separate ASSN URLs per environment.
