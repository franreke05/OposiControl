-- =============================================================================
-- SLICE 3 -- consumer exam runtime: schema (D2-03, D2-07, D2-09, D2-10, D2-12, D2-65)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate; it comes AFTER the 18 pending Slice 0/1 migrations).
-- Forward-only and additive: NEW nullable columns (catalog-only, no rewrite, no backfill), one NEW NOT NULL column
-- with a constant default (catalog-only), NEW constraints on those new columns only, one NEW partial unique index,
-- one NEW trigger function + trigger. No existing column, constraint, policy, grant, function or earlier migration is
-- changed. No row is written. Idempotent (if not exists / drop-if-exists + create), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   The frozen wire of Slice 3 (openapi/attempts/attempts.yaml) needs facts MAIN does not store:
--   1. public.intentos_examen
--      + motivo_fin text NULL, CHECK (motivo_fin in ('USER','TIMEOUT')): why a finalizado attempt ended
--        (Attempt.motivoFin, ReviewAttempt.motivoFin). NULL for open/abandoned attempts and for attempts closed by
--        the legacy finalizar (read as USER by Ktor: the legacy close was always a user action).
--      + especialidad_snapshot_id uuid NULL REFERENCES public.especialidades(id): the specialty of the EXAM at start
--        (DEC-002 / CONTRACT_FREEZE FA2; precedent: tipo_examen_snapshot). Set only by app_start_exam_attempt.
--        NOT backfilled (no data rewrite in this migration; historical attempts read especialidadId = null).
--      + trigger trg_intentos_examen_especialidad_snapshot_inmutable (BEFORE UPDATE OF especialidad_snapshot_id):
--        once set, the snapshot cannot change (P0001 INTENTO_ESPECIALIDAD_SNAPSHOT_INMUTABLE). NULL -> value stays
--        allowed (a later owner-approved backfill).
--      + partial UNIQUE index ux_intentos_examen_abierto (usuario_id, examen_id) WHERE estado = 'en_progreso':
--        at most ONE open attempt per (user, exam). It is the arbiter of app_start_exam_attempt's
--        INSERT ... ON CONFLICT DO NOTHING (CF-01: concurrent starts converge on one attempt).
--   2. public.respuestas_intento
--      + primera_opcion_id uuid NULL REFERENCES public.opciones_pregunta(id) ON DELETE SET NULL (same rule as the
--        existing opcion_seleccionada_id FK), primera_es_correcta boolean NULL: the FIRST submission, immutable by
--        construction (only written on insert by app_record_attempt_answer).
--      + revision integer NOT NULL DEFAULT 1, CHECK (revision >= 1): optimistic revision of the answer row
--        (AnswerRequest.baseRevision; 0 on the wire = no row yet). Existing rows read 1.
--      + actualizada_en timestamptz NULL: server time of the last change. NULL = row written before Slice 3
--        (legacy record_answer): Ktor then reads first answer = final answer.
--      + tiempo_respuesta_ms integer NULL, CHECK (>= 0): client-measured, server-clamped time.
--   3. public.preguntas_intento
--      + estado_final text NULL, CHECK in ('CORRECT','WRONG','SKIPPED','UNANSWERED','ANNULLED'): the per-question
--        status FROZEN at finish (D2-65: a later annulment or solution edit never rewrites an old review).
--
-- PREFLIGHT (fail closed, nothing applied)
--   SLICE3_SCHEMA_PREFLIGHT: the four tables and the columns the index / FKs are built on must exist.
--   SLICE3_OPEN_ATTEMPT_DUPLICATES_GATE: MAIN has NO unique index on open attempts and the legacy start
--   (POST /examenes/{id}/iniciar) never checked for one, so duplicates may exist. If any (usuario_id, examen_id) has
--   more than one en_progreso row the migration RAISES and applies nothing. Attempts are user data and are never
--   closed or deleted by a migration: the owner decides (see VERIFY `open_attempt_duplicates`).
--
-- FORWARD PLAN
--   Apply together with 20261008100100 (the RPCs) BEFORE deploying the Slice 3 Ktor build. Old Ktor keeps working:
--   it never names the new columns (PostgREST `select=*` returns them, kotlinx `ignoreUnknownKeys`), `revision`
--   has a default, and the only behaviour change is the unique index: the legacy start now gets 23505 instead of a
--   SECOND open attempt for the same (user, exam).
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below (run 20261008100100's ROLLBACK first). It drops only what this migration added; the data
--   in the new columns is lost (first-answer snapshot, revisions, frozen statuses, specialty snapshot).
--
-- LOCKING
--   ALTER TABLE ... ADD COLUMN / ADD CONSTRAINT take ACCESS EXCLUSIVE on intentos_examen, respuestas_intento and
--   preguntas_intento for a catalog-only change (no rewrite: nullable or constant default). The CHECKs are validated
--   by a scan (new columns: every row passes). The two FKs take SHARE ROW EXCLUSIVE on especialidades and
--   opciones_pregunta and validate trivially (all NULL). CREATE UNIQUE INDEX (not CONCURRENTLY: one transaction)
--   takes SHARE on intentos_examen for one scan. `lock_timeout = 5s` bounds every lock wait.
--
-- DATA LOSS
--   None.
--
-- RLS AND GRANTS
--   Unchanged. The three tables keep RLS enabled and MAIN's policies/grants (clients: SELECT own rows only).
--
-- CONTENT / AUTHORING FLOWS
--   No content table definition, trigger, policy or function is changed. Two FKs REFERENCE content-side tables
--   (especialidades NO ACTION, opciones_pregunta ON DELETE SET NULL): deleting a specialty that historical attempts
--   snapshot is now rejected (23503), and deleting an option nulls primera_opcion_id exactly as it already nulls
--   opcion_seleccionada_id.
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate and dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop index if exists public.ux_intentos_examen_abierto;
-- drop trigger if exists trg_intentos_examen_especialidad_snapshot_inmutable on public.intentos_examen;
-- drop function if exists private.tg_intentos_examen_especialidad_snapshot_inmutable();
-- alter table public.preguntas_intento drop column if exists estado_final;
-- alter table public.respuestas_intento drop column if exists tiempo_respuesta_ms;
-- alter table public.respuestas_intento drop column if exists actualizada_en;
-- alter table public.respuestas_intento drop column if exists revision;
-- alter table public.respuestas_intento drop column if exists primera_es_correcta;
-- alter table public.respuestas_intento drop column if exists primera_opcion_id;
-- alter table public.intentos_examen drop column if exists especialidad_snapshot_id;
-- alter table public.intentos_examen drop column if exists motivo_fin;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'slice3_columns_missing' as check_name,
--        (9 - count(*))::bigint as failures,
--        null::text as info
-- from pg_attribute a
-- where not a.attisdropped
--   and (a.attrelid, a.attname, a.atttypid) in (
--     (to_regclass('public.intentos_examen'), 'motivo_fin', 'text'::regtype),
--     (to_regclass('public.intentos_examen'), 'especialidad_snapshot_id', 'uuid'::regtype),
--     (to_regclass('public.respuestas_intento'), 'primera_opcion_id', 'uuid'::regtype),
--     (to_regclass('public.respuestas_intento'), 'primera_es_correcta', 'boolean'::regtype),
--     (to_regclass('public.respuestas_intento'), 'revision', 'integer'::regtype),
--     (to_regclass('public.respuestas_intento'), 'actualizada_en', 'timestamptz'::regtype),
--     (to_regclass('public.respuestas_intento'), 'tiempo_respuesta_ms', 'integer'::regtype),
--     (to_regclass('public.preguntas_intento'), 'estado_final', 'text'::regtype),
--     (to_regclass('public.intentos_examen'), 'estado', 'text'::regtype))
-- union all
-- select 'slice3_constraints_missing', (6 - count(*))::bigint, null
-- from pg_constraint c
-- where (c.conrelid, c.conname) in (
--     (to_regclass('public.intentos_examen'), 'intentos_examen_motivo_fin_check'),
--     (to_regclass('public.intentos_examen'), 'intentos_examen_especialidad_snapshot_id_fkey'),
--     (to_regclass('public.respuestas_intento'), 'respuestas_intento_primera_opcion_id_fkey'),
--     (to_regclass('public.respuestas_intento'), 'respuestas_intento_revision_check'),
--     (to_regclass('public.respuestas_intento'), 'respuestas_intento_tiempo_respuesta_ms_check'),
--     (to_regclass('public.preguntas_intento'), 'preguntas_intento_estado_final_check'))
--   and c.convalidated
-- union all
-- select 'open_attempt_index_missing',
--        (case when exists (select 1 from pg_index i
--                           where i.indexrelid = to_regclass('public.ux_intentos_examen_abierto')
--                             and i.indrelid = to_regclass('public.intentos_examen')
--                             and i.indisunique and i.indisvalid and i.indisready
--                             and pg_get_indexdef(i.indexrelid) =
--                                 'CREATE UNIQUE INDEX ux_intentos_examen_abierto ON public.intentos_examen USING btree (usuario_id, examen_id) WHERE (estado = ''en_progreso''::text)')
--              then 0 else 1 end)::bigint, null
-- union all
-- select 'open_attempt_duplicates', count(*)::bigint, null
-- from (select 1 from public.intentos_examen ie where ie.estado = 'en_progreso' and ie.examen_id is not null
--       group by ie.usuario_id, ie.examen_id having count(*) > 1) d
-- union all
-- select 'snapshot_immutable_trigger_missing',
--        (case when exists (select 1 from pg_trigger t
--                           where t.tgrelid = to_regclass('public.intentos_examen')
--                             and t.tgname = 'trg_intentos_examen_especialidad_snapshot_inmutable'
--                             and not t.tgisinternal and t.tgenabled = 'O'
--                             and t.tgfoid = to_regprocedure('private.tg_intentos_examen_especialidad_snapshot_inmutable()'))
--              then 0 else 1 end)::bigint, null
-- union all
-- select 'slice3_tables_without_rls', count(*)::bigint, null
-- from pg_class c
-- where c.oid in (to_regclass('public.intentos_examen'), to_regclass('public.respuestas_intento'),
--                 to_regclass('public.preguntas_intento'), to_regclass('public.abandonos_examen'))
--   and not c.relrowsecurity
-- union all
-- select 'snapshot_trigger_function_executable_by_clients', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('private.tg_intentos_examen_especialidad_snapshot_inmutable()')
--   and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute'));
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT: the MAIN objects this migration builds on.
do $$
declare
  v_missing text;
begin
  select string_agg(need.t || '.' || need.c, ', ' order by need.t, need.c) into v_missing
  from (values
    ('intentos_examen', 'id'), ('intentos_examen', 'usuario_id'), ('intentos_examen', 'examen_id'),
    ('intentos_examen', 'estado'),
    ('respuestas_intento', 'id'), ('respuestas_intento', 'opcion_seleccionada_id'),
    ('preguntas_intento', 'id'), ('preguntas_intento', 'posicion'),
    ('abandonos_examen', 'id'),
    ('especialidades', 'id'), ('opciones_pregunta', 'id')
  ) as need(t, c)
  where not exists (
    select 1 from pg_attribute a
    where a.attrelid = to_regclass('public.' || need.t) and a.attname = need.c and not a.attisdropped
  );
  if v_missing is not null then
    raise exception 'SLICE3_SCHEMA_PREFLIGHT' using detail = 'Missing on this database: ' || v_missing;
  end if;
  if to_regnamespace('private') is null then
    raise exception 'SLICE3_SCHEMA_PREFLIGHT' using detail = 'Schema private does not exist.';
  end if;
end;
$$;

-- GATE: the unique index cannot be built over existing duplicates, and attempts are never closed by a migration.
do $$
declare
  v_duplicados bigint;
begin
  select count(*) into v_duplicados
  from (
    select 1 from public.intentos_examen ie
    where ie.estado = 'en_progreso' and ie.examen_id is not null
    group by ie.usuario_id, ie.examen_id
    having count(*) > 1
  ) d;
  if v_duplicados > 0 then
    raise exception 'SLICE3_OPEN_ATTEMPT_DUPLICATES_GATE'
      using detail = format('%s (usuario, examen) pair(s) already have more than one en_progreso attempt.', v_duplicados),
            hint = 'Attempts are user data and are not closed by this migration. Decide which attempt stays open (the others become abandonado) and re-apply.';
  end if;
end;
$$;

-- 1. intentos_examen
alter table public.intentos_examen add column if not exists motivo_fin text;
alter table public.intentos_examen add column if not exists especialidad_snapshot_id uuid;

alter table public.intentos_examen drop constraint if exists intentos_examen_motivo_fin_check;
alter table public.intentos_examen
  add constraint intentos_examen_motivo_fin_check
  check (motivo_fin is null or motivo_fin = any (array['USER'::text, 'TIMEOUT'::text]));

alter table public.intentos_examen drop constraint if exists intentos_examen_especialidad_snapshot_id_fkey;
alter table public.intentos_examen
  add constraint intentos_examen_especialidad_snapshot_id_fkey
  foreign key (especialidad_snapshot_id) references public.especialidades(id);

create or replace function private.tg_intentos_examen_especialidad_snapshot_inmutable()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.especialidad_snapshot_id is not null
     and new.especialidad_snapshot_id is distinct from old.especialidad_snapshot_id then
    raise exception 'INTENTO_ESPECIALIDAD_SNAPSHOT_INMUTABLE'
      using detail = 'intentos_examen.especialidad_snapshot_id is fixed at start (DEC-002).';
  end if;
  return new;
end;
$$;

revoke all on function private.tg_intentos_examen_especialidad_snapshot_inmutable() from public, anon, authenticated;

drop trigger if exists trg_intentos_examen_especialidad_snapshot_inmutable on public.intentos_examen;
create trigger trg_intentos_examen_especialidad_snapshot_inmutable
  before update of especialidad_snapshot_id on public.intentos_examen
  for each row execute function private.tg_intentos_examen_especialidad_snapshot_inmutable();

create unique index if not exists ux_intentos_examen_abierto
  on public.intentos_examen (usuario_id, examen_id)
  where estado = 'en_progreso';

-- 2. respuestas_intento
alter table public.respuestas_intento add column if not exists primera_opcion_id uuid;
alter table public.respuestas_intento add column if not exists primera_es_correcta boolean;
alter table public.respuestas_intento add column if not exists revision integer not null default 1;
alter table public.respuestas_intento add column if not exists actualizada_en timestamp with time zone;
alter table public.respuestas_intento add column if not exists tiempo_respuesta_ms integer;

alter table public.respuestas_intento drop constraint if exists respuestas_intento_primera_opcion_id_fkey;
alter table public.respuestas_intento
  add constraint respuestas_intento_primera_opcion_id_fkey
  foreign key (primera_opcion_id) references public.opciones_pregunta(id) on delete set null;

alter table public.respuestas_intento drop constraint if exists respuestas_intento_revision_check;
alter table public.respuestas_intento
  add constraint respuestas_intento_revision_check check (revision >= 1);

alter table public.respuestas_intento drop constraint if exists respuestas_intento_tiempo_respuesta_ms_check;
alter table public.respuestas_intento
  add constraint respuestas_intento_tiempo_respuesta_ms_check
  check (tiempo_respuesta_ms is null or tiempo_respuesta_ms >= 0);

-- 3. preguntas_intento
alter table public.preguntas_intento add column if not exists estado_final text;

alter table public.preguntas_intento drop constraint if exists preguntas_intento_estado_final_check;
alter table public.preguntas_intento
  add constraint preguntas_intento_estado_final_check
  check (estado_final is null or estado_final = any (array['CORRECT'::text, 'WRONG'::text, 'SKIPPED'::text, 'UNANSWERED'::text, 'ANNULLED'::text]));

-- POST-CHECK: `if not exists` skips a pre-existing same-name index, so assert the exact definition.
do $$
declare
  v_def text;
  v_ok boolean;
begin
  select pg_get_indexdef(i.indexrelid),
         i.indrelid = to_regclass('public.intentos_examen') and i.indisunique and i.indisvalid and i.indisready
    into v_def, v_ok
  from pg_index i
  where i.indexrelid = to_regclass('public.ux_intentos_examen_abierto');
  if v_def is distinct from
       'CREATE UNIQUE INDEX ux_intentos_examen_abierto ON public.intentos_examen USING btree (usuario_id, examen_id) WHERE (estado = ''en_progreso''::text)'
     or v_ok is not true then
    raise exception 'SLICE3_OPEN_ATTEMPT_INDEX_WRONG_DEFINITION'
      using detail = format('public.ux_intentos_examen_abierto is %s (valid/ready/unique on intentos_examen: %s).',
                            coalesce(v_def, 'missing'), coalesce(v_ok::text, 'n/a')),
            hint = 'A same-name relation with another definition already exists. The migration does not drop it: review, then re-apply.';
  end if;
end;
$$;

commit;
