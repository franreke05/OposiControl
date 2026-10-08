# OposiBots load tests (k6)

Reproducible k6 scenarios for the **bot profile** consumer API. Plan, metrics and decision rule: `docs/engineering/PERFORMANCE_TEST_PLAN.md`. Static findings: `docs/engineering/PERFORMANCE_AUDIT.md`.

**No capacity number is claimed by this folder.** It only provides the instrument.

## Safety rules (enforced in code, `lib/guard.js`)

1. Local or staging targets only. Every script runs the guard at k6 init time and aborts **before any request** when `BASE_URL` is empty/relative or its host matches `*.supabase.co|in|net`, `api.oposibots.es`, `*.oposibots.es`, `*.oposicontrol.(es|com|app)`.
2. Read scenarios (`read_heavy`, `auth`, `mixed` with `MIXED_WRITE_RATIO=0`) can pass the guard on such a host only with `I_UNDERSTAND_THIS_IS_NOT_PRODUCTION=1` - meant for a real staging stack whose hostname happens to match. Do not use it to hit production.
3. Write scenarios (`write_heavy`, `mixed` with writes) additionally need `LOADTEST_ALLOW_WRITES=1` and are **never** allowed against a blocked host, override or not. They create attempts, answers and `idempotency_keys` rows: run them only against a **disposable test project or branch database with seeded fixtures**. Never against MAIN data.
4. No scenario touches admin, content/authoring, import or multipart routes (routes come from `ops/inventory/routes.bot.txt`: `/v1/auth/*`, `/api/v1/app/*`).
5. The rate limiter answers 429 (and 503 if Valkey fails closed) by design. Scenarios count these (`rate_limited`, `status_503`) and do not evade them (no IP spoofing). 429/503 are not `app_errors`.

## Layout

```
lib/guard.js      production-host guard
lib/common.js     BASE_URL, request helper (route-template tags only), metrics, summary export
lib/profiles.js   ramp/hold/ramp-down stages for 100/500/1000/2000/3000/5000 VUs, threshold parameters
lib/flows.js      consumer flows (catalog, my exams, entitlement, attempt state/questions/result, full attempt)
scenarios/        read_heavy.js  mixed.js  write_heavy.js  auth.js
fixtures/         fixtures.example.json (copy to fixtures.json, git-ignored), fixtures.smoke.json (dummy, stub only)
stub/nginx.conf   throwaway stub for the smoke
docker-compose.loadtest.yaml
results/          JSON summaries (git-ignored)
```

## Fixtures

Copy `fixtures/fixtures.example.json` to `fixtures/fixtures.json` and fill with TEST data of the disposable project:

- `users[]`: `token` = access JWT minted out-of-band (login is limited per IP 30/5 min and per account, so a load generator cannot mint thousands of tokens through the API), optional `email/password/refreshToken` for `auth`, `openAttemptId` / `finishedAttemptId` owned by that user for state/questions/result reads.
- `examIds` (published exams), `writableExamIds` (exams the user may start).
- VU i uses user `(i-1) % users.length`. With more VUs than users the per-user limiter will 429: seed at least as many users as VUs you want un-throttled.
- Tokens expire (GoTrue default 1 h): keep holds shorter than the token lifetime or re-mint.

## Run (no local k6 needed)

From `ops/loadtest/`:

```bash
# real run against a local/staging stack (backend on the host at :8080)
BASE_URL=http://host.docker.internal:8080 PROFILE=100 RUN_ID=r1 \
  docker compose -f docker-compose.loadtest.yaml --profile run run --rm k6 run scenarios/read_heavy.js

# writes (disposable DB only)
LOADTEST_ALLOW_WRITES=1 BASE_URL=http://host.docker.internal:8080 PROFILE=100 \
  docker compose -f docker-compose.loadtest.yaml --profile run run --rm k6 run scenarios/write_heavy.js
```

On Git Bash for Windows prefix with `MSYS_NO_PATHCONV=1`. Equivalent without compose: `docker run --rm -i -v "$PWD:/loadtest" -w /loadtest -e BASE_URL=... -e FIXTURES_FILE=/loadtest/fixtures/fixtures.json grafana/k6:0.55.0 run scenarios/read_heavy.js`.

Parameters (env): `PROFILE` (100|500|1000|2000|3000|5000), `RAMP_UP` (2m), `HOLD` (5m), `RAMP_DOWN` (1m), `THRESHOLD_ERROR_RATE` (0.01), `THRESHOLD_P95_MS`/`THRESHOLD_P99_MS` (reads), `THRESHOLD_WRITE_P95_MS`/`THRESHOLD_WRITE_P99_MS`, `NO_THRESHOLDS=1`, `ANSWERS_PER_ATTEMPT`, `MIXED_WRITE_RATIO`, `RUN_ID`, `REQUEST_TIMEOUT`. Threshold defaults are a starting hypothesis, not a promise: set them from the SLO you decide.

Summary: one line on stdout plus full JSON in `results/<scenario>-<profile>-<RUN_ID>.json`. Metric tags are only `name` (route template), `kind` (read|write|auth), `status`, `method`, `scenario`: no user ids, uuids or URLs.

Do not generate 3000-5000 VUs from a single laptop: the generator becomes the bottleneck. Check the k6 container CPU (`docker stats`) and `dropped_iterations`.

## Smoke (proves scripts parse and the guard works; no real load)

```bash
export MSYS_NO_PATHCONV=1 FIXTURES_FILE=/loadtest/fixtures/fixtures.smoke.json \
       PROFILE=1 RAMP_UP=1s HOLD=4s RAMP_DOWN=1s ALLOW_CUSTOM_PROFILE=1 NO_THRESHOLDS=1
docker compose -f docker-compose.loadtest.yaml --profile smoke up -d stub
docker compose -f docker-compose.loadtest.yaml --profile run run --rm k6 run scenarios/read_heavy.js
# guard must refuse:
BASE_URL=https://api.oposibots.es docker compose -f docker-compose.loadtest.yaml --profile run run --rm k6 run scenarios/read_heavy.js
docker compose -f docker-compose.loadtest.yaml --profile smoke down
```
