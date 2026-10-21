# Store payments: owner setup checklist

One list for everything the owner has to do outside the code before store payments can go live. Server contract:
[`STORE_PAYMENTS_CONTRACT.md`](STORE_PAYMENTS_CONTRACT.md). Open decisions and their conservative defaults: [`DECISIONS_PENDING.md`](DECISIONS_PENDING.md).
Client side (OposiBot repo, branch `feature/v1-p3-store-billing-client`): `docs/payments/STORE_BILLING_CLIENT.md`, sections "Owner inputs needed" and
"Store rules checklist".

Secrets are listed by **name only**. Their values go in files with mode 0600 under `/etc/oposibots/production` (decision D9). They never go in the
repo, in a ticket or in a chat. The server never logs them. Variable names are in `ops/deploy/backend.bot.env.example`.

## 0. Before anything: decisions

- [ ] Take or confirm D1..D11, G1, N3-6 and N3-7 in `DECISIONS_PENDING.md`. Until G1 is turned on, production purchases are verified and stored but
      **not credited**, and subscriptions (D8) are off.

## 1. Google Play Console

- [ ] Payments profile and merchant account active; tax information complete.
- [ ] One-time products (OT packs) created as **consumables**, with their product ids. Prices per country, VAT included.
- [ ] Subscriptions (only if D8 = yes): subscription product, base plans (`productId/basePlanId`), offers, grace period and account hold settings.
- [ ] License testers (Settings > License testing) for the sandbox evidence. Test purchases come back marked as test and are treated as sandbox (D4).
- [ ] Google Cloud project linked to Play Console. A **service account** with access to the app in Play Console (financial data / order management
      permission for the Developer API, scope `androidpublisher`). Download its JSON key.
- [ ] Real-time developer notifications: a Pub/Sub **topic**, Play Console > Monetization setup > topic name, "Send test notification" OK.
- [ ] A Pub/Sub **push subscription** to `https://<bot host>/api/v1/webhooks/store/google-play` with **authentication on**: a push service account
      and an explicit **audience**.
- [ ] Edge routing: `/api/v1/webhooks/store/*` must reach the bot pool (see "Pending outside this branch" in the contract). Without it W-02 is
      unreachable.

Server secrets and settings (names only):

| Name | What |
|---|---|
| `PAYMENTS_STORE_GOOGLE_PLAY_ENABLED` | `true` to turn Google Play on |
| `PAYMENTS_GOOGLE_PACKAGE_NAME` | application id |
| `PAYMENTS_GOOGLE_SERVICE_ACCOUNT_FILE` | path to the service-account JSON (secret file) |
| `PAYMENTS_GOOGLE_PUBSUB_AUDIENCE` | the audience set on the push subscription |
| `PAYMENTS_GOOGLE_PUBSUB_SERVICE_ACCOUNT` | the email of the push service account |
| `PAYMENTS_GOOGLE_PUBSUB_SUBSCRIPTION` | full name `projects/<p>/subscriptions/<s>` |

## 2. App Store Connect

- [ ] Paid Apps Agreement signed; banking and tax forms complete.
- [ ] In-App Purchases: OT packs as **Consumable**; subscriptions (only if D8 = yes) as **Auto-Renewable** in one subscription group, with billing
      grace period on or off (decision of the owner; the server follows Apple's status either way). Each IAP with its review screenshot.
- [ ] Sandbox testers (Users and Access > Sandbox) for the sandbox evidence.
- [ ] App Store Server Notifications **V2**: production URL and sandbox URL = `https://<bot host>/api/v1/webhooks/store/app-store`. Send a test
      notification (it is recorded as `PRUEBA`).
- [ ] **In-App Purchase key** (Users and Access > Integrations > In-App Purchase): download the `.p8` once; note its key id and the issuer id.
      Needed for Get Transaction Info, Get All Subscription Statuses and Notification History (gap recovery). Without it the server uses the signed
      transactions and notifications only.
- [ ] Apple Root CA - G3 certificate from https://www.apple.com/certificateauthority/ (public; its SHA-256 is pinned in the code and checked at
      startup).

Server secrets and settings (names only):

| Name | What |
|---|---|
| `PAYMENTS_STORE_APP_STORE_ENABLED` | `true` to turn the App Store on |
| `PAYMENTS_APPLE_BUNDLE_ID` | bundle id |
| `PAYMENTS_APPLE_APP_APPLE_ID` | numeric Apple ID of the app |
| `PAYMENTS_APPLE_ROOT_CA_PATHS` | path(s) to Apple Root CA - G3 (DER or PEM) |
| `PAYMENTS_APPLE_ONLINE_CHECKS` | leave empty (`true`): OCSP of the chain |
| `PAYMENTS_APPLE_API_KEY_FILE` | path to the `.p8` In-App Purchase key (secret file); optional, all three or none |
| `PAYMENTS_APPLE_API_KEY_ID` | key id of that key |
| `PAYMENTS_APPLE_API_ISSUER_ID` | issuer id of the team |

## 3. Server (both stores)

| Name | What |
|---|---|
| `PAYMENTS_STORE_PRODUCTS` | catalogue `platform:storeProductId=productCode:KIND`, one entry per store product; `productCode` = `productos_comerciales.codigo` |
| `PAYMENTS_STORE_SANDBOX` / `PAYMENTS_STORE_SANDBOX_USERS` | D4: `allowlist` + tester user ids for the sandbox evidence and App Review |
| `PAYMENTS_STORE_JOB_SECONDS` | reconciliation job; 300 by default, never 0 in production (alert `StoreInboxMetricsAbsent`) |
| `PAYMENTS_REFUNDS_ENABLED` | D2 |
| `PAYMENTS_STORE_LIVE_CREDIT_ENABLED` / `PAYMENTS_STORE_SANDBOX_EVIDENCE_REF` | G1: last step, see 4 |
| `PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED`, `PAYMENTS_STORE_SUBSCRIPTION_REVOCATION_IMMEDIATE` | D8, D11 |
| `PAYMENTS_STORE_DEAD_LETTER_ATTEMPTS`, `PAYMENTS_STORE_INBOX_RETENTION_DAYS` | N3-6, D6 |

- [ ] Products of `productos_comerciales` exist with the same codes (OT and price frozen per order, D1/D10).
- [ ] Migrations `20261022102600`, `20261022102800`, `20261022102900` and `20261022103000` applied (today: local / PG17 gate only, not MAIN).
- [ ] With a store enabled and anything missing, the process exits with code 78 before binding the port: check the start log after each change.

## 4. Sandbox evidence, then go-live

- [ ] With `PAYMENTS_STORE_SANDBOX=allowlist` and the tester ids: one pack and (if D8) one subscription per store, through the real client.
      Each must show: S-03 `GRANTED`, the order `pagada` with `entorno = sandbox`, the ledger row, Play consume / acknowledge done
      (`tienda_finalizada_en` set), the notification in `tienda_bandeja` processed, and a refund test going through the D2 path.
- [ ] Record the evidence (ledger entry or ticket) and put its reference in `PAYMENTS_STORE_SANDBOX_EVIDENCE_REF`.
- [ ] Turn on `PAYMENTS_STORE_LIVE_CREDIT_ENABLED=true` (G1). The process refuses it without the reference or without an enabled store.
- [ ] Watch the `store-payments` alerts for the first days (pending > 1 h, retained refunds, dead letters, orphans, metrics absent).
