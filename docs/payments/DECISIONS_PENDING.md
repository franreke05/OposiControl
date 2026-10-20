# Store payments: pending business decisions

Owner: OposiBot (E1). Code: `backend/ktor/src/main/kotlin/com/oposicontrol/backend/StoreDecisions.kt` (`STORE_DECISIONS`,
`StorePaymentDecisions`). `StoreDecisionsTest` fails if an id or a config key in the code is missing here. Contract:
[`STORE_PAYMENTS_CONTRACT.md`](STORE_PAYMENTS_CONTRACT.md). Setup checklist: [`STORE_OWNER_SETUP.md`](STORE_OWNER_SETUP.md).

Rule for every default: **never credit more than the store charged, and never grant access the store has not confirmed**. A decision that is not
taken keeps the conservative default. A config value that asks for something not implemented stops the process at startup (exit 78).

| Id | Question | Current conservative default | Config key | Who decides |
|---|---|---|---|---|
| G1 | Credit real production purchases (OT and Premium)? | `false`: production purchases are verified and stored, not credited. Verify answers 503 (the client does not finish; Play refunds an unacknowledged purchase after 3 days); notifications stay `pendiente` (`LIVE_CREDIT_DISABLED`) and are applied when the switch is turned on. `true` requires an enabled store with full credentials and `PAYMENTS_STORE_SANDBOX_EVIDENCE_REF` (a reference to the sandbox evidence, e.g. the ledger entry) | `PAYMENTS_STORE_LIVE_CREDIT_ENABLED` (+ `PAYMENTS_STORE_SANDBOX_EVIDENCE_REF`) | Owner, after credentials and sandbox evidence |
| D1 | Credit by SKU, or compare the store price with a tolerance? | `sku`: OT and price come from the catalogue; the store price is kept for audit only. `tolerance` is not implemented and does not start | `PAYMENTS_STORE_CREDIT_POLICY` | Owner + Control |
| D2 | Refunds on in production? | `false` outside `APP_ENV=local/test`: a refund is stored as `retenido` (never dropped) and applied when the switch is on | `PAYMENTS_REFUNDS_ENABLED` | Owner + Control |
| D3 | Re-credit after `REFUND_REVERSED`? | Never automatic: the order goes to Control's review queue (`en_revision`) | none (database) | Control |
| D4 | Sandbox purchases in production? | `reject`; `allowlist` only for the user ids in `PAYMENTS_STORE_SANDBOX_USERS` (App Review, testers); `accept` only with `APP_ENV=local/test` | `PAYMENTS_STORE_SANDBOX` | Owner |
| D5 | Move a purchase / subscription between accounts? | 409 `STORE_PURCHASE_BOUND_TO_OTHER`, no silent transfer | none (database) | Owner |
| D6 | Ledger and inbox on account deletion; inbox retention | Nothing is purged (empty key); a void after an account deletion is held as an orphan | `PAYMENTS_STORE_INBOX_RETENTION_DAYS` (>= 30) | Legal |
| D7 | Send consumption data to Apple on `CONSUMPTION_REQUEST`? | `false`: recorded only. `true` is not implemented and does not start | `PAYMENTS_APPLE_CONSUMPTION_RESPONSES` | Owner + Legal |
| D8 | Store subscriptions in V1? | `false`: subscription products are not listed, a subscription verify answers 503 and a granting notification stays `pendiente` (`SUBSCRIPTIONS_DISABLED`). Facts that only remove access (expired, revoked) are always applied | `PAYMENTS_STORE_SUBSCRIPTIONS_ENABLED` | Owner |
| D9 | Custody of store secrets | Files with mode 0600 under `/etc/oposibots/production`; never in the repo, never logged | none (deployment) | Owner + infra |
| D10 | OT/EUR equivalence and store fees | Price and OT of the catalogue (`productos_comerciales`); the store fee never changes the credit | none (database catalogue) | Owner |
| D11 | Should a subscription refund / revocation remove access at once even with refunds off? | `false`: it waits behind D2 like an OT refund (today's behaviour). `true` is a PROPOSAL: access follows the store's signed state (it touches no ledger) | `PAYMENTS_STORE_SUBSCRIPTION_REVOCATION_IMMEDIATE` | Owner + Control |
| N3-6 | Attempts before a notification becomes a dead letter in production | `0` = never (nothing is dropped). Recommended for production: 20 (backoff 1 min .. 6 h, about 4 days). Alert `StoreInboxMetricsAbsent` fires when store payments are on and the job publishes no inbox metric | `PAYMENTS_STORE_DEAD_LETTER_ATTEMPTS` | Owner |
| N3-7 | Purge `*_HELD` inbox rows once their order leaves review? | Never purged (they still explain money); the retention purge skips every held / orphan row | none (database) | Owner + Legal |

## Related switches that are not decisions

- `PAYMENTS_STORE_GOOGLE_PLAY_ENABLED`, `PAYMENTS_STORE_APP_STORE_ENABLED`: turn a store on; with missing credentials the process does not start.
- `PAYMENTS_APPLE_API_KEY_FILE` / `PAYMENTS_APPLE_API_KEY_ID` / `PAYMENTS_APPLE_API_ISSUER_ID`: optional App Store Server API key; without it the
  server uses the signed notifications only (no Get All Subscription Statuses, no Notification History gap recovery).
- `PAYMENTS_STORE_JOB_SECONDS`: reconciliation job (0 = off).
