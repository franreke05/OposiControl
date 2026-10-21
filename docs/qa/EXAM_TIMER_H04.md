# H-04: exam timer (what the server and the app do today)

Date: 2026-10-20 · Owner: E1 (backend + client contracts) · Scope: read-only investigation plus characterization tests. No behaviour changed.
Source of H-04: the Figma closure report. Figma shows a "Tiempo 00:00:00" card (`card-estado de examen-pregutnas-on`, 61976:42358) and "Ilimitado tiempo" on the cover (61144:41639). No frame says what happens when time runs out.

## 1. Facts: nothing in V1 has a time limit

| # | Fact | Evidence |
|---|------|----------|
| F1 | `examenes.duracion_minutos integer NULL` (CHECK > 0) exists. It is **metadata only**. | `supabase/baseline/main_shape_subset.sql:30,132` |
| F2 | Control edits it: admin PATCH merge-patch, so it can be cleared. The app reads it as `duracionMinutos` in D2-01/D2-02. | `AdminV1Routes.kt:1912,1968,1977`; `CatalogService.kt:33,65,318,372`; `CatalogGateway.kt:91,484` |
| F3 | No RPC reads `duracion_minutos`. `app_start_exam_attempt`, `app_record_attempt_answer` and `app_finish_exam_attempt` have no deadline, grace or expiry branch. | grep of `supabase/migrations` (only the column and its comments); current bodies: finish `20261022101300_v1_official_rules.sql:1174-1310`, answer `20261022101600_v1_stats.sql` |
| F4 | `intentos_examen.motivo_fin` allows `USER`/`TIMEOUT`, but the only writer is finish, and it always writes `'USER'`. No worker or cron closes attempts. Nothing auto-abandons them either: `inactividad` is rejected as a client reason, and no server path sends it. | `20261008100000_slice3_attempt_runtime_schema.sql:205-211`; `..._v1_official_rules.sql:1295`; `AttemptService.kt:942` |
| F5 | The Ktor service says so explicitly: "DEC-014 sin pausa. Sin limite de tiempo: `deadline` = null y `motivoFin` siempre USER (TIMEOUT / ATTEMPT_EXPIRED no se emiten…)". `deadline = null` is hard-coded in D2-07. | `AttemptService.kt:29-30,814` |
| F6 | `ATTEMPT_EXPIRED` (409) is in the error catalog and the OpenAPI, but **no DB outcome produces it** and `AttemptService` does not map it. An RPC that returned it today would become 502 `UPSTREAM_CONTRACT_ERROR`. | `BackendErrors.kt:1027`; `AttemptService.kt:531-540,544-549`; new test `h04TheRpcsNeverEmit…` |
| F7 | `duracion_segundos` = server `now() - iniciado_en` at finish. It is not capped at `duracion_minutos`. The per-answer time from the client is clamped to the server gap. | `..._v1_official_rules.sql:1297`; `_slice3_attempt_command_rpcs.sql:388` |
| F8 | Admin help copy disagrees about what the field means: "Sin duración" vs "Sin límite" vs "Minutos de los que dispone el usuario". | `docs/registry/TECH_DEBT.md` DEBT-034 |
| F9 | Official exam rules (`admin_set_exam_grading_rules`, 20261022101300) cover annulled questions, reserves and penalty only. They have **no** duration field. | `20261022101300_v1_official_rules.sql` |
| F10 | Quick quiz D2-04 (Figma shows a "tiempo" input, 59276:13287) is not built: it is blocked on DEC-021. The daily challenge is limited to the server calendar day, not by a timer. Answering after that day gives `NOT_FOUND`. | `openapi/root.yaml` description; `20261022101200_v1_daily_challenge.sql` (`reto_today`) |

### Design contract (not implemented)

`10_EXAM_ENGINE_CONTRACT.md` in the OposiBot repo describes a *possible* design: `deadline = iniciado_en + duracion_minutos`, checked lazily (no worker), `motivo_fin=TIMEOUT`, late answers rejected with 409 `ATTEMPT_EXPIRED`. It also lists that design as an **open question**: "Timed exams: hard stop at duracion_minutos, or overtime only recorded? Late-answer grace acceptable?"

DEC-014 (pause/resume/timer) is still open in `18_PRODUCT_DECISIONS_REQUIRED.md`. The frozen OpenAPI `openapi/attempts/attempts.yaml` still describes that design in prose:
- D2-07: "May lazily finalize (TIMEOUT) first".
- D2-65: "the lazy TIMEOUT runs first".
- D2-09: lists `ATTEMPT_EXPIRED`.

## 2. When time "runs out" today

Nothing happens. In detail:
- An attempt that is hours past `duracion_minutos` still behaves as a normal open attempt:
  - Start or resume returns `RESUMED`.
  - `GET` returns `estado=en_progreso` with `deadline=null`.
  - Answers are accepted (200).
- Finish returns `motivo_fin=USER`, and the duration is the real elapsed time.
- No automatic finish, no error. An attempt left open stays `en_progreso` indefinitely, and the next start resumes it.

## 3. Concurrency (pinned with real two-connection races)

Finish and answer both lock the attempt row with `SELECT … FOR UPDATE`. Answer also takes an advisory lock on (user, question) before that. So the two commands are serialized:

| Case | Current behaviour | Safe? |
|------|-------------------|-------|
| Two finishes at once, no key, the same key, or different keys | One really finishes (`already=false`). The other waits, then gets `OK already=true`, and both return the same result. Same key: the Ktor idempotency plugin replays (`Idempotent-Replayed: true`), and a parallel duplicate gets 409 `IDEMPOTENCY_KEY_IN_PROGRESS`. Different keys: both reach the RPC and the state makes it idempotent. | yes |
| Answer arrives while finish is in progress | The answer waits for the commit, then gets `ATTEMPT_NOT_IN_PROGRESS` (409). Nothing is written and the grade is unchanged. | yes |
| Finish arrives while an answer is in progress | Finish waits, then counts that answer. | yes |
| Late answer after finish, or replay of an answered question | 409 `ATTEMPT_NOT_IN_PROGRESS`. The row fingerprint (grade, totals, `finalizado_en`, duration) is unchanged. | yes |
| Abandon racing finish | `ATTEMPT_ALREADY_FINISHED`; finish wins. | yes |
| Client retry after a finish timeout | The app keeps the same `finishKey` across retryable failures. A replay returns the stored result. If the key expired, the state-idempotent RPC returns `already=true` with the same result. | yes |

**No unsafe behaviour found:** no double finish, no answer accepted after finish, no grade change.

## 4. Client (OposiBot `P4-gestures`, read only)

- **No countdown, no auto-submit, no "time is running out" warning.** The only per-question timer measures `responseTimeMs` and is sent to the server (`ExamRuntimeStateHolder.kt:280`).
- Cover "tiempo" datum: `timeLimitLabel = if (attempt.deadline == null) "Ilimitado" else base.timeLimitLabel` (`ExamRuntimeMapping.kt:107`). With a deadline it would show the base placeholder; nothing formats a deadline.
- The cover also shows `durationLabel = "$durationMinutes min"` (`ExamRuntimeMapping.kt:58`), so an exam with `duracion_minutos=60` reads "60 min" next to "Ilimitado".
- The "Tiempo 00:00:00" card is filled only from the finished result's `duracionSegundos` (`ExamRuntimeMapping.kt:84`, `ExamStatusContent.kt:286`). During an attempt it is empty or not shown.
- `ATTEMPT_EXPIRED` is handled as a closed attempt: `ClosedAttemptCodes` (`ExamRuntimeStateHolder.kt:541`) re-reads the server state, and only `FINISHED` counts as handed in. The `EndReason.TIMEOUT` wire value is parsed (`AttemptApi.kt:19`, tested in `RealAttemptApiTest`). So the client tolerates a future timeout, but it has no UI for it.

## 5. Findings

| ID | Severity | Finding |
|----|----------|---------|
| H04-F1 | Medium (product/contract) | The frozen OpenAPI and the design contract describe a lazy TIMEOUT and `ATTEMPT_EXPIRED` that the server never produces (D2-07/D2-09/D2-65 descriptions). A reader of the contract will expect timed exams. Either mark it as TARGET/blocked on DEC-014, or implement it after the decision. |
| H04-F2 | Medium (latent) | If a DB migration ever returns `ATTEMPT_EXPIRED` before `AttemptService` maps it, answers and finishes become 502 `UPSTREAM_CONTRACT_ERROR`, and the client shows SERVER instead of closing the attempt. This is pinned by a test, so whoever adds the rule must update both. |
| H04-F3 | Low (UX copy) | The cover shows "N min" (from `duracion_minutos`) and "Ilimitado" for time at the same time. Admin help copy uses three different meanings for the field (DEBT-034). |
| H04-F4 | Info | An abandoned-in-practice attempt never closes by itself. It stays `en_progreso` and blocks a fresh start (resume). This is consistent with DEC-014 "sin pausa", but the idle rule is undefined. |
| — | none | Concurrency: no double finish, no answer after finish, grade immutable (section 3). |

## 6. Open product decisions (questions for the owner)

1. Does any V1 exam have a time limit, or is `duracion_minutos` informative only ("official exam lasts 120 min")? If informative, should the cover say "Duración 120 min · sin límite" instead of "N min" next to "Ilimitado"?
2. If an exam is timed: when time runs out, should it **hand in automatically** (with what is answered) or only record overtime? Is there a grace period (seconds) for answers sent just before the limit?
3. Does the limit apply to every type (ráfaga / razonado / inmersivo), only to official exams, or to demo attempts too?
4. Does the timer keep running while the app is closed (server clock), or does it stop (pause, DEC-014)?
5. During the exam, is the "Tiempo 00:00:00" card elapsed time or time remaining? Is there a "queda poco" warning (`pop up/informativos{warning}`), and at what threshold?
6. A timed-out attempt: does it count for ranking, streak, Nota media and the first answer like a handed-in one?
7. Should an attempt left open for days ever close by itself (inactivity), and if so, as abandoned or as handed in?

## 7. Tests added (characterization only, they pin today's behaviour)

These tests are expected to change when the owner decides a time rule.

- `supabase/baseline/assertions.sql`, last block "H-04". Exam with `duracion_minutos=1` and attempt backdated 2 h:
  - resume gives `RESUMED`, no lazy TIMEOUT;
  - late answers OK;
  - finish gives `USER` with `duracion_segundos >= 7200`;
  - no `TIMEOUT` written;
  - second finish gives `already=true`;
  - answer and replay after finish give `ATTEMPT_NOT_IN_PROGRESS`;
  - row fingerprint unchanged, no answer row written, abandon after finish gives `ATTEMPT_ALREADY_FINISHED`.
- `scripts/db-pg17-gate.sh` step 7 `exam_race`, real two-connection races, x2:
  - F1 finish‖finish;
  - F2 finish(held)‖answer;
  - F3 answer(held)‖finish;
  - F4 finish(held)‖abandon;
  - F5 post-finish fingerprint unchanged;
  - plus a global "no TIMEOUT" check.
- `backend/ktor/src/test/kotlin/com/oposicontrol/backend/AttemptRoutesTest.kt`:
  - `h04AnOpenAttemptLongPastTheExamDurationHasNoDeadlineAndIsStillOpen`;
  - `h04TheRpcsNeverEmitAttemptExpiredSoKtorTreatsItAsAnUpstreamContractError`;
  - `h04TwoFinishesWithDifferentKeysBothReachTheRpcAndReturnTheSameFrozenResult`.
