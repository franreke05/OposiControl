# Migration State

## Slice 0 + Slice 1 expand set applied to MAIN — 2026-10-08 (alternative A)

Seven forward-only migrations were applied to MAIN (`ndiooyyqtaeysnedywer`), one at a time, each with its VERIFY block at 0,
its repo version recorded in `supabase_migrations.schema_migrations`, and the protected-content catalog fingerprint
(functions, triggers, policies, columns, constraints, grants of the question/exam/specialty/import objects) compared after
every step: unchanged.

| Version | Name |
|---|---|
| `20260923120000` | `news_published_at` |
| `20260928100000` | `identity_canonical_profile_expand` |
| `20260928100100` | `idempotency_keys` |
| `20260930100000` | `slice1_profile_guards` |
| `20260930100100` | `slice1_legal_attribution_detail` |
| `20260930100200` | `slice1_profile_command_rpcs` |
| `20260930100300` | `slice1_account_deletion_requests` |

- History: 54 versions, last `20260930100300`. Applied with the Supabase CLI (`db query --linked -f <file>` + `migration repair
  --status applied <version>`), so the recorded version equals the repo file version (no new divergence like `news_canvas_schema`
  `20260923090948` vs `20260923103000`, which stays history-only).
- Preconditions met: logical backup taken and restore-verified before the first write (outside Git; evidence file
  `BACKUP_EVIDENCE.txt` + `SHA256SUMS` next to the dump); owner ratified FA7 and FA9 and chose alternative A (2026-10-08);
  prechecks on MAIN: `public.usuario` = 0 rows (backfill is a no-op), 0 profiles with onboarding completed, no username
  duplicates, 3 specialties, every object owned by `postgres`.
- Data after apply: 4 users, 4 profiles (`estado_cuenta` = `activa`), 2 exams, 203 questions, 812 options, 218 audit rows:
  identical to before. New tables `idempotency_keys`, `aceptaciones_legales`, `solicitudes_eliminacion_cuenta`: RLS enabled,
  0 rows, no client grants. No public table without RLS; no SECURITY DEFINER command executable by `anon`/`authenticated`.
- 2026-10-08 (later): `20261008100400_slice2_content_grant_client_write_revoke` applied to MAIN on its own (order-independent, narrowing only: policy `contenido_desbloqueado_admin_all` dropped, INSERT/UPDATE/DELETE on `contenido_desbloqueado_usuario` revoked from `anon`/`authenticated`). VERIFY 4 checks at 0, history 55 versions, protected-content fingerprint unchanged, 0 grant rows before and after. Proven by `Slice2ContentGrantClientWriteMigrationTest` (user and plain admin denied, service_role allowed).
- STILL PENDING on MAIN (apply at the Ktor cutover, runbook step 0): `20260930100500` .. `20260930102300` (12, starting with the
  two contract migrations 5 and 6), `20261008100000` .. `20261008100200` (Slice 3) and any later file. `PENDING_MIGRATIONS.txt`
  and `main_shape_subset.sql` still describe MAIN as of 2026-09-29 for the PG17 gate; regenerating the baseline from the
  current MAIN is a follow-up.

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

### M10 — pregunta anulada por el tribunal en un examen (2026-09-19)

| Version | Name | What it does |
|---|---|---|
| `20260919122139` | `annulled_questions_in_exams` (M10) | **La anulación pertenece a la RELACIÓN `preguntas_examen`, nunca a `preguntas`**: `anulada boolean not null default false` y `motivo_anulacion text` (sólo con `anulada`, no vacío, ≤ 500; CHECK `preguntas_examen_anulada_motivo_chk`). Las mismas dos columnas (y CHECK) en `preguntas_parseadas_staging`. La misma pregunta puede estar anulada en un examen y normal en otro. Reescribe: `admin_link_question_to_exam` (firma nueva de 5 args: `+p_anulada`, `+p_motivo_anulacion`), `admin_create_question` (14 args), `admin_import_lote_preguntas` (sigue siendo UNA transacción; devuelve `anuladas`; exige confirmación si cambia una relación de un examen publicado; el tope de 200 cuenta sólo relaciones nuevas), `record_answer` (una anulada NO puntúa: no guarda respuesta, devuelve `es_correcta` NULL y la opción correcta) y el trigger de staging inmutable (`tg_staging_inmutable_tras_importar` incluye `anulada`/`motivo_anulacion`). Las firmas antiguas se eliminan (sin sobrecargas). EXECUTE sólo `service_role`. |

- **Aplicada en MAIN** `ndiooyyqtaeysnedywer` (versión de MAIN `20260919122139`; el archivo de `supabase/migrations/` lleva la misma versión).
- **Respaldo**: `private.m10_previous_definitions_20260919` (definiciones exactas previas de las 5 funciones/trigger; RLS activado, sin acceso para `public`/`anon`/`authenticated`). Borrable cuando el rollback ya no pueda hacer falta (junto con las de M8, DEBT-050).
- **Rollback / preflight**: `supabase/canonical/annulled_questions_m10/ROLLBACK.sql` (una transacción; **aborta** si existe alguna relación o fila de staging anulada, porque quitar las columnas devolvería esa pregunta al cómputo de la nota; restaura las definiciones guardadas y elimina columnas y CHECK; ejecutar con `execute_sql`/`psql`, no con `apply_migration`) y `PREFLIGHT.sql` (sólo SELECT; todas las filas deben decir OK).
- **Orden de despliegue: M10 primero, Ktor después** (RISKS.md IMP-003, DEBT-056): un Ktor con `anulada` contra una BD sin M10 falla al escribir.
- **Deuda asociada**: estadísticas agregadas no excluyen anulaciones posteriores (DEBT-060); `validar_y_calcular_respuesta_intento` no rechaza escrituras directas (DEBT-061); sin test SQL reproducible (DEBT-062).
- **Verificación**: E2E LIVE contra MAIN vía Ktor real, 74/74 checks (véase `docs/product/domains/PDF_IMPORT.md`); los datos de prueba se borraron y MAIN volvió a 3 preguntas / 2 vínculos / 3 exámenes / 0 lotes.

### Drift de historial de migraciones (informe 2026-09-19)

MAIN tiene 18 versiones en `supabase_migrations.schema_migrations` sin archivo correspondiente en `supabase/migrations/` (sus fuentes viven en `supabase/canonical/`), y M10 lleva en MAIN la versión `20260919122139`. Cifra tomada del informe de cierre de la sesión, no recontada aquí. Registrado como DEBT-063 (P3). La equivalencia byte a byte entre el archivo de M10 y `schema_migrations.statements` no se recontrastó en esta pasada (M8 sí se aplicó sin comentarios, véase arriba).

**Governance note:** M5, M7, IM1 and IM3 were applied by a process outside this session's own review pipeline (IM3 was not in the owner's original authorization). Discovered mid-session, disclosed to the owner immediately, and resolved: an independent retroactive audit certified all four as technically sound, the owner ratified IM3, and this session then independently re-verified all seven live before continuing. See `docs/brain/CURRENT_STATE.md` → "INICIO + CONTENIDO closure" for the full narrative.

Known residual gap, not closed by M7: `storage.objects`' policy for premium immersive-exam resources still lacks the `publicado_en` check that the six public-schema tables got (ownership prevented the ALTER). Tracked informally; not yet in `TECH_DEBT.md` as its own line since it's currently inert (inherits protection transitively) — worth a dedicated entry if `storage.objects` ownership is ever fixed to allow the ALTER.

## Guarda de roles instalada en MAIN — 2026-09-21

| Version | Name | What it does |
|---|---|---|
| `20260921071916` | `role_sync_guard` | Instala `public.admin_sync_roles(uuid, uuid[])` y su helper `public.admin_role_code_canonical(text)`. Archivo: `supabase/migrations/20260921071916_role_sync_guard.sql`; origen: `supabase/canonical/role_sync_guard_candidate_v1/001` (sin los `begin`/`commit` propios: `apply_migration` ya es transaccional). |

- **Instalación LIMPIA, no reemplazo.** Antes de esta fecha `public.admin_sync_roles` NO existía en MAIN (sólo `has_role`/`has_any_role`). `AdminV1Gateway.syncRoles` (`AdminV1Routes.kt`, `POST /api/v1/admin/usuarios/{id}/roles`) llamaba a una RPC inexistente, así que «guardar roles» de Usuarios fallaba con PGRST202. La función de `docs/supabase/20260624_admin_atomic_operations.sql` nunca estuvo desplegada en MAIN. Por eso lo que dice la sección de abajo («reemplaza el cuerpo», «`APPLY_PENDING_APPROVAL`») y las afirmaciones de otros documentos de que «tiene éxito hoy en MAIN» son falsas o están SUPERADAS.
- **Estado verificado (VERIFIED_LIVE 2026-09-21).** `admin_sync_roles(uuid,uuid[])`: `SECURITY DEFINER`, `search_path=""`, `row_security=off`, `volatile`, ACL `{postgres, service_role}` (anon/authenticated sin EXECUTE, comprobado). `admin_role_code_canonical(text)`: inmutable y ejecutable por PUBLIC a propósito (el propietario de la guarda necesita EXECUTE sobre él; no toca tablas).
- **Invariantes** (códigos levantados con `raise exception`): `ROLE_SYNC_INVALID_USER`, `ROLE_SYNC_UNKNOWN_ROLE`, `ROLE_SYNC_EMPTY_ROLES` (un administrativo no puede quedarse sin roles) y `ROLE_SYNC_LAST_SUPERADMIN` (sólo bloquea la TRANSICIÓN antes>0 → después=0; MAIN hoy tiene 0 superadmins y 1 fila en `roles_usuario` con rol `admin`, así que no bloquea nada heredado).
- **Rollback EXACTO de la instalación limpia:** `drop function public.admin_sync_roles(uuid, uuid[]); drop function public.admin_role_code_canonical(text);`. El fichero `002_rollback_admin_sync_roles.sql` del paquete candidato NO sirve para esto: restaura la versión sin guarda de `docs/supabase/20260624`, que no es lo que había en MAIN. Revertir reabre SEC-006 (y deja «guardar roles» otra vez sin RPC).
- **Evidencia.** `:backend:dbtest` (RoleSync*, PostgreSQL 16 embebido) 45/45 tras `cleanTest` (9 suites). SMOKE LIVE contra Ktor real con usuarios E2E desechables (`E2E_TEST_SMOKE_*`, borrados; MAIN volvió a 1 fila en `roles_usuario`, 0 superadmins, 3 preguntas): asignar rol OK; array vacío a un administrativo → 422 `role_sync_empty_roles` con roles intactos; rol inexistente → 422 `role_sync_unknown_role`; usuario inexistente → 422 `role_sync_invalid_user`; dos degradaciones SIMULTÁNEAS de los dos únicos superadmins → 1×200 y 1×409 `role_sync_last_superadmin` y queda 1 superadmin; retirar el último superadmin → 409; con otro superadmin vivo degradar SÍ se permite; la RPC directa desde anon (401) y desde un usuario autenticado (403) está denegada.
- **Alcance de la protección** (heredado de la cabecera del SQL): protege a quien llama a la RPC; un `DELETE`/`INSERT` directo sobre `roles_usuario` con la clave de `service_role` no toma el cerrojo ni pasa por las comprobaciones (una invariante de BD completa exigiría un constraint trigger diferido; fuera de alcance). Correccion (ronda 8, RC-02 / MASTERF1-DB-001): ademas de `service_role`, en MAIN un admin NORMAL con su JWT podia escribir `roles_usuario` por la Data API (politica `roles_usuario_admin_all` FOR ALL TO authenticated con `es_admin()` + grant INSERT/UPDATE/DELETE a `authenticated`) y darse `superadmin`; la frase de la cabecera de `20260930100000` ("admin-only by RLS and protected by role_sync_guard") no lo cubria. La migracion NUEVA `20260930102200_slice1_roles_usuario_client_write_revoke` borra esa politica y revoca INSERT/UPDATE/DELETE a `anon`/`authenticated`; queda solo el residual de `service_role` descrito arriba.
- **RPC legada aún en MAIN, sin uso:** `rpc_grant_exam_access(integer,integer,...)` con ids enteros; Ktor no la llama (Accesos escribe directamente en `contenido_desbloqueado_usuario`).
- **RPC que Ktor invoca y NO existen en MAIN (2026-09-21):** `admin_replace_search_index` y `admin_upsert_search_entry` (`TagSearchGateway.kt:144,188`; definidas sólo en `docs/supabase/20260624_admin_atomic_operations.sql`). Ver DEBT-068 y RISKS LEG-003.

## Role sync guard candidate — 2026-09-12 (HISTÓRICO: SUPERADO 2026-09-21 por la instalación de arriba; el texto que sigue describe el candidato, no el estado de MAIN)

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

Clasificación (SUPERADA 2026-09-21: aplicada como `20260921071916`): `APPLY_PENDING_APPROVAL`. `MAIN_WRITES: NONE`. No se ejecutó
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
