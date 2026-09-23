# Content Import Atomicity Candidate V1

Tracked candidate migration source for two confirmed P1 gaps in Screen 09
(mobile content upload) and its underlying `POST
/api/v1/admin/importaciones/preguntas/{loteId}/importar` endpoint:

1. **Publication is not atomic.** `AdminV1Gateway.importarLote` (Kotlin)
   loops over staging rows with a separate REST call per row and a
   try/catch that only marks that one row `error` — no single transaction.
   A crash/restart mid-loop, or any partial failure, can leave the lote
   `parcial` with some real questions created and others not.
2. **No duplicate-candidate detection.** Nothing today checks whether an
   imported question already exists before creating a new one.

## Status: `APPLY_PENDING_APPROVAL`

**Not applied anywhere — local candidate only.** This migration was
designed and written without live Supabase schema access (no DB
credentials available in the session that authored it). Column/table names
are grounded in the real Kotlin DTOs that already read/write these tables
(`AdminV1Routes.kt`: `LoteRow`, `StagingRow`, `AdminPreguntaRow`,
`AdminCreateQuestionRpc`), not in a schema dump. Before this can be applied
anywhere, including a non-MAIN branch:

1. Confirm the exact parameter types of the existing, live
   `admin_create_question(p_question, p_options, p_solution, p_exam_id,
   p_position)` RPC — Kotlin only calls it over PostgREST
   (`AdminV1Gateway.createPregunta`), so its real Postgres signature isn't
   visible from this repo. `001_admin_import_lote_preguntas.sql` marks the
   exact call as a `TODO(apply-review)` for this reason — it is not wired
   in yet.
2. Confirm `preguntas.titulo`/`especialidad_id`/`activa` and
   `preguntas_parseadas_staging`'s real columns match what's assumed.
3. Confirm whether `preguntas_parseadas_staging.estado_parseo` has a CHECK
   constraint that needs a companion migration to accept the new
   `'duplicada_candidata'` value.

## Design

`public.admin_import_lote_preguntas(p_lote_id uuid)` — one Postgres
function call = one transaction. Any unexpected error rolls back
everything (true all-or-nothing), unlike the current per-row REST loop.
Duplicate candidates are a deliberate exception to "nothing happens": they
are flagged (`estado_parseo = 'duplicada_candidata'`) for human review,
never auto-skipped and never auto-replacing the existing question — the
UX contract is `duplicate candidate → revisar → saltar / conservar
nueva`, never `auto replace`. The lote's final `estado` only ever reports
`importado` when nothing (imports or duplicates) is left pending — the UI
can never surface success on a partial publish.

## Files

- `001_admin_import_lote_preguntas.sql`: the candidate RPC (see status
  above — the actual question-creation call is a documented TODO, not
  live SQL, pending signature confirmation).
- `tests/verify_candidate.ps1`: local static contract check (filenames,
  required SQL markers). Does not connect to Supabase.

## Safety boundary

Supabase production writes: `NONE`. This candidate has not been executed
against any database, local or remote. `MAIN_WRITES: NONE`.

## Not part of this candidate

Live wiring of a new Kotlin gateway method calling this RPC, and swapping
the active `POST .../{loteId}/importar` route to use it, are intentionally
**not** part of this candidate — that follow-up is a small, one-line
change once this migration is reviewed and applied. Until then, the
active route keeps today's documented behavior (`importarLote`, which can
report `parcial`).
