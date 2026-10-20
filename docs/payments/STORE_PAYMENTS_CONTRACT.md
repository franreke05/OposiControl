# Store payments contract (Google Play + App Store), V1

Status: IMPLEMENTED (E1, 2026-10-19), OFF by default (`PAYMENTS_STORE_GOOGLE_PLAY_ENABLED` / `PAYMENTS_STORE_APP_STORE_ENABLED`). Code: `StorePayments.kt`, `StoreGooglePlay.kt`, `StoreAppStore.kt`, `StorePaymentRoutes.kt`.
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
| S-02 | `GET /api/v1/app/store/products` | `{items:[{storeProductId, platform, kind, productCode, basePlanId?}]}` from config; only platforms that are enabled |
| S-03 | `POST /api/v1/app/store/purchases/verify` | `Idempotency-Key` required. Body `{platform, storeProductId, kind, token, transactionId?, accountToken?}` (`token` = Play `purchaseToken` or StoreKit 2 `jwsRepresentation`, max 32 KiB, PAY-R2). `200 {result: GRANTED|PENDING, kind, productCode, balance, premium, premiumUntil?}` |
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
notifications are recovered with Get Notification History when the In-App Purchase key is configured (section "Round 3", below).

## 5. State machine mapping

### Packs (consumables) -> `compras_usuario` + ledger

| Store fact | Action | DB outcome |
|---|---|---|
| Verified PURCHASED, quantity 1, account token = user | `app_store_credit_purchase` creates (or finds) the order `pendiente` keyed by store key, then `app_credit_payment_order(event 'compra:'+sha256(key))` | `CREDITED` once; replays `REPLAY` |
| PENDING (Play) | nothing | `PENDING` (200) |
| Same key, other user | refused | `STORE_PURCHASE_BOUND_TO_OTHER` |
| Refund / void / Apple `REFUND` (revocationDate) | `app_store_refund_purchase` -> `app_refund_payment_order` | `REFUNDED` / `REFUNDED_P2`; order not paid -> `REFUND_HELD` (SEC-V1-05) |
| Refund before the purchase exists (out of order) | inbox row marked `REFUND_ORPHAN_HELD` | a later verify creates the order, holds the refund, credits nothing (`EN_REVISION`) |
| Refund with `PAYMENTS_REFUNDS_ENABLED=false` | inbox `retenido` (`REFUNDS_DISABLED`, not an attempt, never dropped), 200 | listed again and applied by the job when the switch is on (ECO-02) |
| Apple `REFUND_REVERSED` | `app_store_refund_reversed`: refunded order -> `en_revision`; not refunded yet -> recorded (`REVERSAL_EARLY`), a later refund goes to review; unknown key -> held on the inbox and attached to the order on purchase | Control decides (**D3**); never a NOOP, never an automatic re-credit |
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
- Daily Voided Purchases poll (`type=0`, one-time products only; last 3 days of the 30-day window; quota 6000/day): each void goes to the inbox as event `void:<orderId or token hash>`; subscription voids come from RTDN (`SUBSCRIPTION_REVOKED` + `voidedPurchaseNotification`)
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

## Alignment with E4 (`STORE_BILLING_CLIENT.md`, OposiBot e221c6e)

Same: opaque server-issued UUID account token (S-01); catalogue with `storeProductId`, `kind`, server `code`, `basePlanId` (S-02); one idempotent
verify with `Idempotency-Key` and `{platform, storeProductId, kind, token, transactionId?, accountToken?}`; each store token credited at most once;
`PENDING` = no grant, no finish; 5xx = retry, 401 = session lost; the server may consume/acknowledge first (client sees `ITEM_NOT_OWNED`).
Differences: (1) rejections are canonical 4xx envelopes (`STORE_*` codes, table above), not `REJECTED{code}` in a 200: the client maps 409/422 to
REJECTED; (2) `GRANTED` returns `balance` always and `premiumUntil` when premium is active, plus `premium` and `productCode`; (3) `transactionId` is
informative (the key comes from the verified proof) and `accountToken`, when sent, must equal the one the store signed; (4) restore is also available as
one batch call (S-04, <= 20 items), optional for the client; (5) a purchase held for review answers 409 `STORE_PURCHASE_IN_REVIEW` (client: never finish).

## Pending outside this branch

- Edge routing: `ops/caddy` does not yet send `/api/v1/webhooks/store/*` to the bot pool (deny by default), so W-02/W-03 are unreachable in
  production until infra adds that path (not changed here).
- OpenAPI amendment for S-01..S-05 / W-02 / W-03 (contract-first files under `openapi/`). Consumption API calls stay off (D7).

## Audit fixes (OposiControl audit of 8859ee4, 2026-10-19; migration `20261022102800`)

- F2/F12: a `REFUND_REVERSED` is never a silent NOOP (see the table above); `app_refund_payment_order` sends a paid order with an early reversal to
  review (`reembolso_tras_reversion`) instead of debiting.
- F4: inbox state `retenido` for refunds deferred by the switch (no attempt counted, never dropped, listed only when refunds are on); real failures
  back off 1 min .. 6 h with no attempt cap; claims use `FOR UPDATE SKIP LOCKED` + 5-minute lease, ordered by next attempt (no starvation).
  Metric `store_inbox_backlog{estado}` / `store_inbox_oldest_seconds{estado}` and an `ALERT store inbox` WARN log (alert rules in ops: pending).
- F6: Apple `RETRYABLE_VERIFICATION_FAILURE` (OCSP undetermined) -> 503 `UPSTREAM_UNAVAILABLE` (store / client retry), not 422 / 401.
- F7: `PAYMENTS_GOOGLE_PUBSUB_SUBSCRIPTION` is required with Google enabled and always checked.
- F8: configured Apple roots must match the pinned SHA-256 of Apple Root CA - G3
  (`63343abf...653e9179`, Apple's root list https://support.apple.com/en-am/121672), or startup fails.
- F10: the DB refuses a JWT user without the store account token (`STORE_ACCOUNT_MISMATCH`), for purchases and subscriptions.
- F11 (OWNER DECISION D11, not implemented): today a subscription refund/revocation waits behind `PAYMENTS_REFUNDS_ENABLED` like an OT refund, so
  Premium stays active while the switch is off. Proposal: treat subscription revocation as an ACCESS fact from the store (apply it always; it touches
  no ledger) and keep the switch for the OT ledger policy (P2) only. It changes who loses access and when, so it is not policy-neutral.
- Regression tests from Control's audit: `StoreAuditRegressionTest` (A-K1..A-K8; A-K3 and A-K6 inverted) and the `20261022102800` block of
  `supabase/baseline/assertions.sql` (S1, S5, S7, S8 inverted; S2 kept as current D3 behaviour; S3 left to Control's F3 migration).

## Re-audit fixes (OposiControl re-audit of 94a867c + 9e4ecd0, 2026-10-19; migration `20261022102900`)

- NEW-1 / NEW-2: one advisory lock per (provider, store key) in `app_store_credit_purchase`, `app_store_refund_purchase` and
  `app_store_refund_reversed`, taken before the wallet lock (key -> wallet everywhere): a refund or reversal that arrives while its purchase is
  being credited waits and is applied to the order. Safety net: the job runs `app_store_reconcile_orphans` (refund orphans only with refunds on).
  Proven with two real connections: `ops/local-exam/store-concurrency-probe.mjs` (R1..R4 red before, green after).
- NEW-3: `app_store_inbox_put` claims the row (token + 5-minute lease) like the job; a redelivery while another worker holds it answers
  `IN_PROGRESS`; the job renews each row's claim before processing it; `app_store_inbox_mark` applies only for the holder and never changes a processed
  row (its result is never overwritten).
- NEW-4: dead-letter state `muerto` after `PAYMENTS_STORE_DEAD_LETTER_ATTEMPTS` (default never), `app_store_inbox_requeue`; stats and gauges for
  `muerto`, `huerfano_reembolso`, `huerfano_reversion`; retention purge only when `PAYMENTS_STORE_INBOX_RETENTION_DAYS` (>= 30) is set (owner /
  legal input); orphans and held rows are never purged.
- NEW-5: alert rules `store-payments` in `ops/monitoring/prometheus/alert_rules.yml` (pending > 1 h, retained refunds, dead letters, orphans > 1 day).
- NEW-6: the `REVERSAL_EARLY` marker is consumed (`REVERSAL_CONSUMED`) by the one refund it reverses; a later genuine refund is applied (P2).
- NEW-7: Apple roots are read as X.509 (DER or PEM) and the pin is over the certificate DER; the duplicated check is merged.
- Regression: `StoreReauditRegressionTest` (R-K1..R-K5, R-K3 inverted) and the `20261022102900` block of `supabase/baseline/assertions.sql`
  (Control's n1_orders.sql scenarios A..E as assertions).

## Round 3 (OposiControl re-audit of 1cabc65 + ad88bc0, 2026-10-19; migration `20261022103000`) and server-side verification

- N3-3: the per-key lock is proven by a REAL race in the PG17 gate (`scripts/db-pg17-gate.sh` step 6, two/three psql connections, transaction held
  open): R1..R4 (credit vs refund / reversal in both orders), S1 (same purchase verified twice at once: one credit), S2 (same refund event
  delivered twice at once: one debit), S3 (first delivery of a subscription twice at once: one row), D1 (reconcile vs a credit retry with wallet
  contention: no 40P01). `scripts/db-pg17-gate.test.sh` (g)/(g2)/(g3)/(h) move the lock below the lookup (text kept, VERIFY green) or restore the
  row-first reconcile, and the race must fail.
- N3-1: `app_store_reconcile_orphans` takes the store-key lock first (try-lock: a key held by a live call is skipped for the next run), then the
  row `FOR UPDATE SKIP LOCKED`, one subtransaction per row.
- N3-2 / N3-4: `app_store_inbox_mark` refuses a null claim and never replaces a `REFUND_HELD` / `REVERSAL_HELD` set by the database.
- N3-5: partial index `ix_tienda_bandeja_huerfano`. N3-6: owner decision (DECISIONS_PENDING.md), gauge `store_payments_enabled` and alert
  `StoreInboxMetricsAbsent`. N3-7: the purge never deletes any `*_HELD` row (purging them after review is an owner / legal decision, not taken).
- Subscriptions: same per-key lock as packs; an exact replay (same version and state) answers `REPLAY` and writes nothing.
- App Store Server API (optional, `PAYMENTS_APPLE_API_KEY_FILE` / `_KEY_ID` / `_ISSUER_ID`, all or none), with Apple's App Store Server Library
  client (`BaseAppStoreServerAPIClient`, transport = the server's `StoreHttp`): verify / restore re-read the purchase from Apple (Get Transaction
  Info for a pack, Get All Subscription Statuses for a subscription) and Apple's signed answer wins (a refund since the client JWS = revoked); a
  subscription notification is re-read (current status, read time as version), so delivery order does not matter; Get Notification History every
  6 h (production) recovers missed notifications into the inbox. Without the key nothing changes: signed JWS only.
- Google Play: the job re-reads due subscriptions with `subscriptionsv2` (`app_store_subscriptions_due`: still giving access and at / past their
  end, not refreshed for an hour); Voided Purchases daily with a persisted window.
- Poll cursors live in `tienda_sondeo` (`app_store_poll_get` / `app_store_poll_advance`, forward only): they survive restarts and are shared by the
  replicas; a window overlaps the previous one (1 day Google, 1 h Apple), the inbox deduplicates by event id, and the cursor moves only after
  everything read is in the inbox.
- Business decisions: `StoreDecisions.kt` + [`DECISIONS_PENDING.md`](DECISIONS_PENDING.md). Production crediting is OFF by default (G1).
  Owner checklist: [`STORE_OWNER_SETUP.md`](STORE_OWNER_SETUP.md).
- Apple endpoint paths used (`/inApps/v1/transactions/{id}`, `/inApps/v1/subscriptions/{id}`, `/inApps/v1/notifications/history`) are the ones
  the official library 5.2.0 builds (asserted in `StoreServerSyncTest`); no Apple page was opened for them in this round.
