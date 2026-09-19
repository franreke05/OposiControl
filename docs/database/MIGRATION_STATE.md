# Migration State

## Database Redesign V1 closure — 2026-08-21

Three controlled MAIN migrations were applied after read-only preflight:
`20260821110000_database_redesign_v1_012_drop_redundant_attempt_index`,
`20260821110100_database_redesign_v1_013_least_privilege_grants` and
`20260821110200_database_redesign_v1_014_revoke_view_mutation_grants`.
The first removed only the proven unused duplicate `intentos_examen` index;
the latter two removed anonymous mutation grants globally and mutation grants
without an authenticated policy, including public derived views. No table,
row, bucket, object, policy, RPC or legacy adapter was removed.

## Current operational state — 2026-08-21

The authorized MAIN project `ndiooyyqtaeysnedywer` has Product Metrics migrations `002`–`010` applied through the tracked migration mechanism. The current verified contract includes canonical `record_answer`, `record_event` and `admin_grant_tickets`; the latter is atomic, idempotent, concurrency-safe and executable only by `service_role`. The candidate V2 and `POST_OPOSIBOT_LEGACY_CLEANUP` packages remain separate documentation for their own gates and were not used for this closure.

The broader legacy/V1 migration history remains mixed and is not declared globally canonical by this document.

All sections below this current operational state are historical reconciliation or candidate-package evidence. They do not override the current Product Metrics/wallet closure above.

## Content verticals closure — 2026-09-17/18

Seven migrations were applied to MAIN (`ndiooyyqtaeysnedywer`) for the INICIO/CONTENIDO closure (Dashboard, Exámenes, Preguntas, Importación), each through a preflight → independent review → apply → live-verify → test cycle, one at a time:

| Version | Name | What it does |
|---|---|---|
| `20260918095440` | `exams_questions_many_to_many` (M2) | Drops the old `preguntas_examen_pregunta_unica` constraint; a question can now belong to more than one exam |
| `20260918095539` | `exams_questions_parent_guards` (M3) | Blocks changing an exam's or question's type/specialty while it has active links (the specialty part was SUPERSEDED by M8/M9 below: specialty no longer takes part; only the type stays locked) |
| `20260918095646` | `exams_questions_order_and_link_rpcs` (M4) | Adds `admin_link_question_to_exam`, `admin_unlink_question_from_exam`, `admin_reorder_exam_questions` — the sole write path for the M:N relationship; makes `preguntas_examen`'s position uniqueness `DEFERRABLE INITIALLY IMMEDIATE` |
| `20260918110809` | `exams_codigo_examen_not_blank` (M5) | Repairs the one exam with a blank code, adds a CHECK forbidding blank/whitespace-only codes |
| `20260918111232` | `draft_content_rls` (M7) | Closes EXAM-009: the six public content-table SELECT policies now require `publicado_en IS NOT NULL` (or `es_admin()`) — draft exam content is no longer readable by non-admins. `storage.objects`' matching policy could not be altered by this session (owner is `supabase_storage_admin`); live-proven not currently exploitable (a non-premium/anon request still can't reach it), but that's inherited protection from another table's RLS, not a direct guarantee — see DEBT list |
| `20260918112140` | `import_staging_states` (IM1) | Adds `aprobada`/`omitida` to `preguntas_parseadas_staging.estado_parseo` for the human-review step, alongside the parser's own `ok`/`advertencia`/`error` and the publish-only `importada` |
| `20260918113100` | `import_lote_rpc` (IM3) | `admin_import_lote_preguntas` — atomic, idempotent, all-or-nothing publish of an import batch in one Postgres transaction; claims the lote row (`for update`), takes a per-(especialidad,tipo) advisory lock before creating questions, re-checks duplicates under that lock, reuses M4's link RPC and the existing question-creation RPC |

All seven are byte-identical between MAIN's `supabase_migrations.schema_migrations` and the corresponding file in `supabase/migrations/` (md5-verified). M2/M3/M4's functional behavior was proven live against real data (not just schema presence) with results matching pre-documented expected output exactly. IM3's atomicity was proven with real PDF imports: a forced failure mid-publish wrote zero rows, calling publish twice on an already-published lote was a true no-op, and two genuinely overlapping publish calls on the same batch (one held open across a 3-second delay specifically to force the race) produced exactly one result with no duplication.

### M8 — questions have no specialty (2026-09-19)

| Version | Name | What it does |
|---|---|---|
| `20260919085404` | `questions_without_specialty` (M8) | **Product decision: a question does NOT belong to a specialty; the specialty is an attribute of the EXAM, and question↔exam compatibility depends on TYPE only.** Drops `preguntas.especialidad_id` (its only dependents were 1 trigger, 1 FK and 1 index; no view, matview or policy). `trg_preguntas_bloquear_cambio_tipo` now fires on `tipo_pregunta` only. `validar_pregunta_examen()` and `admin_link_question_to_exam()` (M4) drop the specialty comparison and keep type, cerrojos, cap 200, `QUESTION_ARCHIVED`. `admin_create_question` loses `p_especialidad_id` (new 12-arg signature; the 13-arg one is dropped; EXECUTE `service_role` only). `admin_import_lote_duplicados` now dedupes by TYPE across the whole bank and `admin_import_lote_preguntas` serialises per TYPE instead of `specialty:type`. |
| `20260919090808` | `exams_specialty_change_unlocked` (M9) | `trg_examenes_bloquear_cambio_tipo` fires on `tipo_examen` only: the specialty of an exam with linked questions can now be corrected (M3 blocked it because specialty used to be part of the link rule). Type stays locked. Rollback: `supabase/canonical/questions_without_specialty_m8/ROLLBACK_M9.sql`. |

Backup and rollback, in MAIN and in the repo: `private.preguntas_especialidad_backup_20260919` (the 3 `pregunta_id → especialidad_id` pairs at apply time), `private.m8_previous_definitions_20260919` (the exact previous definition of the 5 functions and the trigger) and `supabase/canonical/questions_without_specialty_m8/ROLLBACK.sql`. Both `private.*` tables are safe to drop once the rollback can no longer be wanted (tracked as DEBT-050). Applied through `apply_migration` with the file's SQL **minus comments**, so the file in `supabase/migrations/` is not byte-identical to `schema_migrations.statements` (functionally identical; the comments carry the rationale). Not proven by the embedded-Postgres suite: `dry_run_fixture/002_content_live_shape.sql` is the pre-M2 shape and does not replay M2–M8; M8 was proven live (see `docs/brain/CURRENT_STATE.md`).

**Governance note:** M5, M7, IM1 and IM3 were applied by a process outside this session's own review pipeline (IM3 was not in the owner's original authorization). Discovered mid-session, disclosed to the owner immediately, and resolved: an independent retroactive audit certified all four as technically sound, the owner ratified IM3, and this session then independently re-verified all seven live before continuing. See `docs/brain/CURRENT_STATE.md` → "INICIO + CONTENIDO closure" for the full narrative.

Known residual gap, not closed by M7: `storage.objects`' policy for premium immersive-exam resources still lacks the `publicado_en` check that the six public-schema tables got (ownership prevented the ALTER). Tracked informally; not yet in `TECH_DEBT.md` as its own line since it's currently inert (inherits protection transitively) — worth a dedicated entry if `storage.objects` ownership is ever fixed to allow the ALTER.

## Role sync guard candidate — 2026-09-12

`supabase/canonical/role_sync_guard_candidate_v1/` reemplaza el cuerpo de
`public.admin_sync_roles(uuid, uuid[])` por una versión con guarda. La función
documentada en `docs/supabase/20260624_admin_atomic_operations.sql:3-19` no
comprueba nada: un array vacío o nulo borra todos los roles del usuario, y nada
impide que desaparezca el último superadministrador del sistema.

El candidate añade dos invariantes **dentro** de la operación —un usuario
administrativo no puede quedarse sin roles; ninguna operación puede provocar la
transición de ≥1 a 0 superadministradores— serializadas con
`pg_advisory_xact_lock` tomado antes de la primera lectura, y comprobadas
después de escribir, de modo que el `raise exception` deshace la operación
entera. Estilo calcado de `product_metrics_candidate_v1/010_admin_grant_tickets.sql`.

Clasificación: `APPLY_PENDING_APPROVAL`. `MAIN_WRITES: NONE`. No se ejecutó
contra ninguna base de datos. El esquema se ancla en
`supabase/canonical/dry_run_fixture/001_live_shape.sql:14-25` y en los DTO Kotlin
que ya leen esas tablas, no en un volcado real: antes de aplicarlo hay que
confirmar los códigos de `roles_app` contra el esquema vivo. La firma no cambia,
así que aplicarlo no exige ningún cambio de código simultáneo.

El paquete incluye `002_rollback_admin_sync_roles.sql`, que devuelve la función a
su cuerpo original y declara en cabecera que **revertir reabre SEC-006**. La
lista pre-apply tiene seis puntos; los dos que más importan son volcar el
`proacl` **antes** de reemplazar —`create or replace` conserva el ACL, así que un
grantee antiguo de la función `security invoker` pasaría a ejecutarla como
`definer`— y verificar que el propietario tenga `SELECT` sobre `auth.users`: si le
falta, el síntoma es `permission denied` (42501) y un 500 genérico, no
`ROLE_SYNC_INVALID_USER`.

**Fallo cerrado frente a RLS (2026-09-13).** La función declara además
`set row_security = off`. `public.roles_usuario` tiene RLS activa, y al pasar a
`security definer` la RPC corre como su propietario, que `create or replace`
conserva y que la migración no elige. Medido en PostgreSQL 16.2 real: con un
propietario sin `BYPASSRLS` al que la política filtra la lectura, los dos
recuentos leían **0**, el `delete` tampoco veía filas, el `insert` entraba, y
retirar `superadmin` dejaba al usuario en `[admin, superadmin]` **devolviendo
éxito** — las dos invariantes mudas y en silencio. `row_security = off` no
concede acceso: hace que PostgreSQL levante «la consulta sería afectada por la
política» en lugar de devolver un conjunto parcial. Un propietario exento no nota
la diferencia.

### Arquitectura de verificación — reset 2026-09-13

Cinco revisiones independientes tumbaron el método anterior. Se verificaba
leyendo el **texto** del SQL, y cada ronda encontró otra forma sintáctica de
dejar la guarda muerta con todo en verde: `raise notice` por `raise exception`,
un `case when false` de señuelo, un `exception when others then null` que se
tragaba el aborto dejando confirmada la escritura, una condición imposible en el
`if`, una segunda definición en MAYÚSCULAS que pisaba a la guardada, y un
abre-bloque de comentario dentro de un `--` que hacía que el propio limpiador se
comiera el neutralizador. No faltaba una expresión regular: la autoridad estaba
en el sitio equivocado.

| Pieza | Autoridad | Qué demuestra |
|---|---|---|
| `tests/verify_candidate.ps1` | paquete | integridad, forma transaccional, presencia de preflight y postcondición, rollback, estado declarado, ninguna migración colada en `supabase/migrations`, SQL ejecutable ASCII. Imprime su propio `DOES_NOT_PROVE:` |
| `RoleSyncInvariantsTest` (`:backend:ktor`) | espejo y deriva | que la especificación en Kotlin dice lo que creemos; avisa si el SQL revisado cambia. **No es la garantía** |
| **`:backend:dbtest`** | **comportamiento** | **las invariantes** |

`:backend:dbtest` levanta un PostgreSQL **real y desechable** (binarios propios,
sin Docker ni daemon; base nueva por escenario), instala el contrato mínimo de
Supabase, aplica **este mismo fichero** leído del repositorio —nunca una copia en
un literal de test— y comprueba el estado de las tablas: último
superadministrador, cero roles, canonicalización (matriz Kotlin↔SQL, 20 espacios
Unicode recortables y 4 no recortables), RLS y propiedad, ACL hostil, privilegios
sobre `auth.users` y sobre el helper, concurrencia con dos conexiones reales, y
rollback. Cada mutación se aplica a una base desechable y debe hacer que el
escenario funcional **deje pasar** la operación prohibida; el fichero del
repositorio no se toca.

## Candidate V2 review — 2026-08-20

`supabase/canonical/migrations_candidate_v2/` is the corrected pre-OposiBot package. It reuses `es_admin()`/`es_moderador_o_admin()`, denies direct authenticated business writes, preserves `public.usuario` and `/v1/auth/*`, keeps `can_access_conversation(uuid)`, omits `current_auth_user_id()`, applies the guarded `permite_guiados -> permite_razonados` rename in `007_plan_entitlements.sql`, and defines the confirmed plan entitlement model there. `POST_OPOSIBOT_LEGACY_CLEANUP` is separate and contains no capability rename.

Historical disposable evidence `OPC-20260820-012` recorded that files `001` through `007` passed twice on disposable project `vsdvqhjgvttjdldlkgim`; classification is `READY_FOR_FINAL_LIVE_READ_ONLY_GATE`, not a live migration approval. Live project `ndiooyyqtaeysnedywer` received no writes.

## Executable candidate package — 2026-08-20

The first concrete package is at `supabase/canonical/migrations_candidate/` and remains outside `supabase/migrations/`. Its exact order and rollback classes are documented in `CANONICAL_MIGRATION_EXECUTION_PLAN.md`. Static checks passed; no PostgreSQL clone dry-run or live apply was performed because the local `psql`/Supabase CLI are unavailable and the requested scope forbids live mutation. Classification remains `CANDIDATE_ONLY / HUMAN_REVIEW_REQUIRED`.

## Live reconciliation — 2026-08-20

Read-only Supabase inspection verified five applied migrations. The live statements for `20260803092231_oposibots_usuario_base_table` and `20260803092306_oposibots_auth_profile_rls` were recovered exactly into `supabase/migrations/`. Normalized content comparison shows `20260730120000_oposibots_auth_profile_rls.sql` and `20260803092306_oposibots_auth_profile_rls.sql` are equal; the former is `DUPLICATE_HISTORY` and is retained to avoid rewriting historical evidence. Migration reproducibility is restored for these entries; Canonical V1 application remains blocked.

## OPC-20260819-004 evidence

The repository contains 14 SQL files across `docs/supabase` and `supabase/migrations`, with multiple table/function/policy families and backend REST/RPC references. This is inventory evidence only; it does not prove application order, live compatibility, or deployment history. The Supabase connector returned `USER_NOT_LOGGED_IN` and the local CLI is not installed, so no project ref, table catalog, effective RLS state, RPC list, Storage bucket or migration history could be read.

## Recovery sequence

1. Connect the authorized Supabase project and capture a read-only inventory.
2. Compare live tables, columns, keys, policies, functions and buckets against code and local migration files.
3. Select one canonical migration owner/source with Architecture + Database + Security approval.
4. Produce additive, reversible migrations and compatibility tests; do not rebuild or drop families blindly.
5. Re-run the backend contract matrix and request the human gate.

## CURRENT CLASSIFICATION

`SEVERE_DRIFT`

This is a governance/source-alignment classification, not a claim about a verified production database. The exact live state remains:

```text
LIVE SUPABASE SCHEMA:
READ_ONLY_VERIFIED / RECONCILIATION_PARTIAL
NO CANONICAL MIGRATION SOURCE SELECTED YET
```

## Evidence status

| Evidence area | Classification | Meaning |
|---|---|---|
| Active Kotlin/code expectations | `CODE_EXPECTED` | What routes/gateways/repositories expect |
| `docs/supabase/*.sql` and plans | `DOCUMENTED_ONLY` | Historical/documentary DDL, policies and plans |
| `supabase/migrations` | `DOCUMENTED_ONLY` | Versioned subset, not proven complete applied history |
| Supabase runtime schema | `LIVE_VERIFIED` | Read-only catalog and migration inspection completed 2026-08-20 |
| Migration history in production | `LIVE_VERIFIED` and recovered | Five applied migrations; statements are now present locally |

## Coexisting families

The repository documents and/or expects all of the following at the same time:

- legacy singular/English schema family with integer primary-key expectations;
- plural/Spanish/UUID V1 schema family used by newer routes and code;
- app-specific tables for attempts, feedback, events and user-facing flows;
- SQL documentation under `docs/supabase` that is not a complete migration chain;
- dated migration files that cover only a subset of RLS/profile/index/function hardening;
- application contracts and gateways that still reference legacy and V1 families together.

No family is declared canonical by this document.

## Drift dimensions

| Dimension | Classification | Evidence |
|---|---|---|
| Table naming drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | code and SQL use legacy singular plus plural/V1 families |
| PK type drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | int/UUID differences recorded by audit |
| Column drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | code/DDL expectations differ in names and nullability |
| Migration history drift | `LIVE_VERIFIED` | duplicate historical filename remains; live statements are locally recoverable |
| SQL documentation drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | DDL/policies distributed across docs rather than one chain |
| Legacy/V1 coexistence | `VERIFIED_FROM_SOURCE` | routes, repositories and screen mappings use both |
| Application contract drift | `VERIFIED_FROM_SOURCE` / `DOCUMENTED_ONLY` | endpoint families and data repositories span generations |
| Policies/RLS | `LIVE_VERIFIED` counts; intent pending | 86 tables, 66 with policies and 20 without policies |
| RPC/functions/triggers | `LIVE_VERIFIED` grants; body review pending | deployed `SECURITY DEFINER` grants were inspected read-only |

## Guardrails

No SQL, migration, RPC, RLS, storage or Supabase write was performed. HL-21 remains active: future durable DB changes require a canonical migration decision, review, ledger, tests and human gate.
