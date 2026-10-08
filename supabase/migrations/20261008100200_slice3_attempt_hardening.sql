-- =============================================================================
-- SLICE 3 -- consumer exam runtime: attempt hardening (gate review F-01, F-02, F-05, F-06)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only and additive: ONE new nullable column
-- (catalog-only, no rewrite, no backfill) with its CHECK and its immutability trigger, TWO new partial indexes, and
-- the four Slice 3 command functions REDEFINED (create or replace: same signature, same owner, same grants).
-- 20261008100000 and 20261008100100 are published and are NOT edited: this file supersedes the function bodies of
-- 20261008100100. No row is written or changed. Idempotent, one transaction.
-- Requires 20261008100000 (columns, ux_intentos_examen_abierto) and 20261008100100 (the functions being redefined).
--
-- WHY
--   F-01 (HIGH): the legacy POST /api/v1/app/examenes/{id}/iniciar inserted intentos_examen checking only
--   activo / publicado_en, never the entitlement policy (EntitlementPolicy, D7-25), and 20261008100100 then froze
--   the question set of such an attempt on its first command ("late freeze"). A user without any grant could start
--   a LOCKED exam, finish it and read every question and correct answer through the review. Ktor retires the two
--   legacy routes (410); this migration is the defence in depth in the database: an attempt that
--   public.app_start_exam_attempt did not create does not exist for any command.
--
-- WHAT THIS MIGRATION DOES
--   1. public.intentos_examen
--      + creado_por_comando text NULL, CHECK (creado_por_comando is null or = 'app_start_exam_attempt'): the marker
--        "this attempt was created by the canonical start command" (which Ktor only calls AFTER the entitlement
--        policy allowed the exam). Set by app_start_exam_attempt on INSERT and by nothing else. NOT backfilled.
--        Why a new column and not an existing fact: especialidad_snapshot_id is documented as backfillable for
--        historical attempts (20261008100000) and depends on examenes.especialidad_id staying NOT NULL; a frozen
--        preguntas_intento set is exactly what the late freeze manufactured. Neither is a proof of origin.
--      + trigger trg_intentos_examen_creado_por_comando_inmutable (BEFORE UPDATE OF creado_por_comando): the marker
--        can never be set, changed or cleared after the INSERT (P0001 INTENTO_CREADO_POR_COMANDO_INMUTABLE), so an
--        attempt cannot be "promoted" later. Clients have no write privilege on the table (SELECT own rows only).
--      + index idx_intentos_examen_especialidad_snapshot_id (especialidad_snapshot_id) WHERE ... IS NOT NULL (F-05:
--        the FK of 20261008100000 had no index; deleting a specialty scanned intentos_examen).
--   2. public.respuestas_intento
--      + index idx_respuestas_intento_primera_opcion_id (primera_opcion_id) WHERE ... IS NOT NULL (F-05: the FK
--        ON DELETE SET NULL of 20261008100000 had no index, so every delete of an opciones_pregunta row -- the
--        question-edit flow does it -- scanned respuestas_intento). Partial: the RI lookup is `col = $1`, which
--        implies `col IS NOT NULL`, and skipped answers (NULL) stay out of the index.
--   3. Functions (bodies copied verbatim from 20261008100100; ONLY these changes):
--      public.app_start_exam_attempt        sets creado_por_comando = 'app_start_exam_attempt' on INSERT. RESUMED
--        only for an open attempt that carries the marker. An open attempt WITHOUT it (legacy start) is never
--        resumed and never frozen: once the exam is known to be startable it is closed (estado = 'abandonado',
--        metadatos.cerradoPor / motivo) and a new, marked attempt is created. Reason: it is unreachable through
--        every route and would otherwise hold ux_intentos_examen_abierto for ever (the user could never start that
--        exam again). No answer, frozen set or abandonos_examen row is written for it.
--      public.app_record_attempt_answer, public.app_finish_exam_attempt, public.app_abandon_exam_attempt
--        the owner lookup now also requires origen_intento = 'examen' (F-06: Ktor reads already filtered it, the
--        commands did not) AND creado_por_comando IS NOT NULL: anything else is ATTEMPT_NOT_FOUND, exactly as an
--        unknown or foreign attempt. record / finish no longer call private.app_congelar_preguntas_intento: there
--        is NO late freeze (a marked attempt always has the set frozen at start, in the same transaction).
--      private.app_congelar_preguntas_intento is unchanged (still used by the start).
--
-- ASSUMPTION (stated, not enforced)
--   MAIN has 0 rows in public.intentos_examen (checked by the owner before this gate), so there is no legacy
--   attempt to preserve: no existing attempt loses access. If that is no longer true at apply time, every existing
--   attempt (none has the marker) becomes invisible to the Slice 3 routes: finished ones can no longer be reviewed
--   through D2-12 / D2-65 and open ones are closed on the next start of the same exam. The data itself is kept.
--
-- PREFLIGHT
--   Fail closed (nothing applied), SLICE3_HARDENING_PREFLIGHT: the columns of 20261008100000, the five functions of
--   20261008100100 and schema private must exist.
--   REPORT only (NOTICE SLICE3_HARDENING_PREFLIGHT_REPORT, never an error): how many open (en_progreso) exam
--   attempts lack the marker, i.e. will be closed by the next start of the same (user, exam). Expected on MAIN: 0.
--   The same number is the `info` of the VERIFY row open_exam_attempts_without_command_marker_info.
--
-- FORWARD PLAN
--   Apply right after 20261008100100 and BEFORE deploying the Slice 3 Ktor build: that build filters every attempt
--   read by creado_por_comando (PostgREST answers 400 for an unknown column, so the reads fail closed without it).
--   A Ktor build older than Slice 3 never calls these functions and never names the column.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below: drops the two indexes, the trigger, its function and the column (the marker values are
--   lost; no other data is touched). It does NOT restore the function bodies: `create or replace` keeps no history.
--   After it, RE-APPLY 20261008100100 verbatim (idempotent) to get the previous bodies back; until then the four
--   commands fail (they name the dropped column), which is fail-closed. In a full rollback (this block, then
--   20261008100100's, then 20261008100000's) the functions are dropped anyway. Rolling back re-opens F-01's late
--   freeze: do it only together with the Ktor build that has the legacy routes retired.
--
-- LOCKING
--   ALTER TABLE ... ADD COLUMN / ADD CONSTRAINT: ACCESS EXCLUSIVE on intentos_examen for a catalog-only change (the
--   CHECK is validated by one scan; every row is NULL). CREATE INDEX (plain, not CONCURRENTLY: one transaction)
--   takes SHARE on intentos_examen and on respuestas_intento for one scan each, blocking writes meanwhile: both
--   tables are empty on MAIN (0 attempts, and answers cascade from attempts), so the build is instantaneous.
--   CREATE FUNCTION / TRIGGER: catalog only. `lock_timeout = 5s` bounds every lock wait.
--
-- DATA LOSS
--   None.
--
-- RLS AND GRANTS
--   Unchanged. The four public functions stay SECURITY DEFINER with `search_path = ''` and EXECUTE for service_role
--   ONLY (re-stated below: idempotent). The new trigger function is in schema private and revoked from clients. The
--   tables keep RLS enabled and MAIN's policies / grants (clients: SELECT own rows only; they can read the new
--   column of their own attempts, which reveals nothing).
--
-- CONTENT / AUTHORING FLOWS
--   No content table, trigger, policy or function is changed. The only effect on the authoring flow is that
--   deleting an opciones_pregunta row (or a specialty) no longer scans the attempt tables.
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate and dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop index if exists public.idx_respuestas_intento_primera_opcion_id;
-- drop index if exists public.idx_intentos_examen_especialidad_snapshot_id;
-- drop trigger if exists trg_intentos_examen_creado_por_comando_inmutable on public.intentos_examen;
-- drop function if exists private.tg_intentos_examen_creado_por_comando_inmutable();
-- alter table public.intentos_examen drop column if exists creado_por_comando;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- with cmds(sig, guarded, may_freeze) as (values
--   ('public.app_start_exam_attempt(uuid,uuid)', false, true),
--   ('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)', true, false),
--   ('public.app_finish_exam_attempt(uuid,uuid)', true, false),
--   ('public.app_abandon_exam_attempt(uuid,uuid,integer,text)', true, false))
-- select 'hardening_marker_column_missing' as check_name,
--        (1 - count(*))::bigint as failures,
--        null::text as info
-- from pg_attribute a
-- where a.attrelid = to_regclass('public.intentos_examen') and a.attname = 'creado_por_comando'
--   and not a.attisdropped and a.atttypid = 'text'::regtype and not a.attnotnull
-- union all
-- select 'hardening_marker_check_missing', (1 - count(*))::bigint, null
-- from pg_constraint c
-- where c.conrelid = to_regclass('public.intentos_examen') and c.conname = 'intentos_examen_creado_por_comando_check'
--   and c.contype = 'c' and c.convalidated
-- union all
-- select 'hardening_marker_immutable_trigger_missing',
--        (case when exists (select 1 from pg_trigger t
--                           where t.tgrelid = to_regclass('public.intentos_examen')
--                             and t.tgname = 'trg_intentos_examen_creado_por_comando_inmutable'
--                             and not t.tgisinternal and t.tgenabled = 'O'
--                             and t.tgfoid = to_regprocedure('private.tg_intentos_examen_creado_por_comando_inmutable()'))
--              then 0 else 1 end)::bigint, null
-- union all
-- select 'hardening_trigger_function_executable_by_clients', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('private.tg_intentos_examen_creado_por_comando_inmutable()')
--   and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute'))
-- union all
-- select 'hardening_fk_indexes_missing_or_wrong', (2 - count(*))::bigint, null
-- from pg_index i
-- where i.indisvalid and i.indisready
--   and (i.indexrelid, pg_get_indexdef(i.indexrelid)) in (
--     (to_regclass('public.idx_respuestas_intento_primera_opcion_id'),
--      'CREATE INDEX idx_respuestas_intento_primera_opcion_id ON public.respuestas_intento USING btree (primera_opcion_id) WHERE (primera_opcion_id IS NOT NULL)'),
--     (to_regclass('public.idx_intentos_examen_especialidad_snapshot_id'),
--      'CREATE INDEX idx_intentos_examen_especialidad_snapshot_id ON public.intentos_examen USING btree (especialidad_snapshot_id) WHERE (especialidad_snapshot_id IS NOT NULL)'))
-- union all
-- select 'hardening_rpc_missing', count(*) filter (where to_regprocedure(sig) is null)::bigint, null
-- from cmds
-- union all
-- select 'hardening_rpc_wrong_security_or_grants', count(*)::bigint, null
-- from cmds join pg_proc p on p.oid = to_regprocedure(cmds.sig)
-- where not (p.prosecdef and p.proconfig @> array['search_path=""'])
--    or has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute')
--    or not has_function_privilege('service_role', p.oid, 'execute')
-- union all
-- select 'hardening_command_without_marker_or_origin_guard', count(*)::bigint, null
-- from cmds join pg_proc p on p.oid = to_regprocedure(cmds.sig)
-- where cmds.guarded
--   and not (p.prosrc like '%and ie.origen_intento = ''examen'' and ie.creado_por_comando is not null%')
-- union all
-- select 'hardening_command_with_late_freeze', count(*)::bigint, null
-- from cmds join pg_proc p on p.oid = to_regprocedure(cmds.sig)
-- where not cmds.may_freeze and p.prosrc like '%app_congelar_preguntas_intento%'
-- union all
-- select 'hardening_start_does_not_set_marker', count(*)::bigint, null
-- from cmds join pg_proc p on p.oid = to_regprocedure(cmds.sig)
-- where cmds.may_freeze
--   and not (p.prosrc like '%''examen'', ''app_start_exam_attempt'')%' and p.prosrc like '%if found and v_marcador is not null then%')
-- union all
-- select 'open_exam_attempts_without_command_marker_info', 0::bigint, count(*)::text
-- from public.intentos_examen ie
-- where ie.estado = 'en_progreso' and ie.origen_intento = 'examen' and ie.creado_por_comando is null;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed): the objects of 20261008100000 / 20261008100100 this migration builds on.
do $$
declare
  v_missing text;
begin
  select string_agg(need.t || '.' || need.c, ', ' order by need.t, need.c) into v_missing
  from (values
    ('intentos_examen', 'id'), ('intentos_examen', 'usuario_id'), ('intentos_examen', 'examen_id'),
    ('intentos_examen', 'estado'), ('intentos_examen', 'origen_intento'), ('intentos_examen', 'metadatos'),
    ('intentos_examen', 'especialidad_snapshot_id'),
    ('respuestas_intento', 'primera_opcion_id'),
    ('preguntas_intento', 'estado_final')
  ) as need(t, c)
  where not exists (
    select 1 from pg_attribute a
    where a.attrelid = to_regclass('public.' || need.t) and a.attname = need.c and not a.attisdropped
  );
  if v_missing is not null then
    raise exception 'SLICE3_HARDENING_PREFLIGHT'
      using detail = 'Missing on this database: ' || v_missing, hint = 'Apply 20261008100000 first.';
  end if;

  select string_agg(need.sig, ', ' order by need.sig) into v_missing
  from (values
    ('public.app_start_exam_attempt(uuid,uuid)'),
    ('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'),
    ('public.app_finish_exam_attempt(uuid,uuid)'),
    ('public.app_abandon_exam_attempt(uuid,uuid,integer,text)'),
    ('private.app_congelar_preguntas_intento(uuid,uuid)')
  ) as need(sig)
  where to_regprocedure(need.sig) is null;
  if v_missing is not null then
    raise exception 'SLICE3_HARDENING_PREFLIGHT'
      using detail = 'Missing function(s): ' || v_missing, hint = 'Apply 20261008100100 first.';
  end if;

  if to_regnamespace('private') is null or to_regclass('public.ux_intentos_examen_abierto') is null then
    raise exception 'SLICE3_HARDENING_PREFLIGHT'
      using detail = 'Schema private or public.ux_intentos_examen_abierto is missing (apply 20261008100000 first).';
  end if;
end;
$$;

-- PREFLIGHT (report only, never fails): open exam attempts that lack the marker. On the first apply the column does
-- not exist yet, so every open exam attempt lacks it.
do $$
declare
  v_abiertos bigint;
  v_total bigint;
begin
  if exists (select 1 from pg_attribute a
             where a.attrelid = to_regclass('public.intentos_examen') and a.attname = 'creado_por_comando'
               and not a.attisdropped) then
    execute $q$select count(*) filter (where ie.estado = 'en_progreso'), count(*)
               from public.intentos_examen ie
               where ie.origen_intento = 'examen' and ie.creado_por_comando is null$q$
      into v_abiertos, v_total;
  else
    select count(*) filter (where ie.estado = 'en_progreso'), count(*) into v_abiertos, v_total
    from public.intentos_examen ie
    where ie.origen_intento = 'examen';
  end if;
  raise notice 'SLICE3_HARDENING_PREFLIGHT_REPORT: % open exam attempt(s) without the command marker (closed by the next start of the same exam); % exam attempt(s) without it in total (invisible to the Slice 3 routes). Expected on MAIN: 0 and 0.',
    v_abiertos, v_total;
end;
$$;

-- 1. intentos_examen: the command marker
alter table public.intentos_examen add column if not exists creado_por_comando text;

alter table public.intentos_examen drop constraint if exists intentos_examen_creado_por_comando_check;
alter table public.intentos_examen
  add constraint intentos_examen_creado_por_comando_check
  check (creado_por_comando is null or creado_por_comando = 'app_start_exam_attempt'::text);

create or replace function private.tg_intentos_examen_creado_por_comando_inmutable()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.creado_por_comando is distinct from old.creado_por_comando then
    raise exception 'INTENTO_CREADO_POR_COMANDO_INMUTABLE'
      using detail = 'intentos_examen.creado_por_comando is written only by app_start_exam_attempt, on INSERT.';
  end if;
  return new;
end;
$$;

revoke all on function private.tg_intentos_examen_creado_por_comando_inmutable() from public, anon, authenticated;

drop trigger if exists trg_intentos_examen_creado_por_comando_inmutable on public.intentos_examen;
create trigger trg_intentos_examen_creado_por_comando_inmutable
  before update of creado_por_comando on public.intentos_examen
  for each row execute function private.tg_intentos_examen_creado_por_comando_inmutable();

-- 2. F-05: the two FKs of 20261008100000 get their index (partial: NULL rows are never looked up by the RI checks)
create index if not exists idx_respuestas_intento_primera_opcion_id
  on public.respuestas_intento (primera_opcion_id)
  where primera_opcion_id is not null;

create index if not exists idx_intentos_examen_especialidad_snapshot_id
  on public.intentos_examen (especialidad_snapshot_id)
  where especialidad_snapshot_id is not null;

-- ---------------------------------------------------------------------------------------------------------------
-- D2-03 start or resume (redefined: sets the command marker; never resumes an attempt it did not create)
-- ---------------------------------------------------------------------------------------------------------------
create or replace function public.app_start_exam_attempt(p_usuario_id uuid, p_examen_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_especialidad_id uuid;
  v_intento_id uuid;
  v_total integer;
  v_frozen integer;
  v_try integer;
  v_marcador text;
  v_sin_marcador uuid;
begin
  if p_usuario_id is null or p_examen_id is null then
    return jsonb_build_object('outcome', 'EXAM_NOT_FOUND');
  end if;

  select e.especialidad_id into v_especialidad_id
  from public.examenes e
  where e.id = p_examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= now();
  if not found then
    return jsonb_build_object('outcome', 'EXAM_NOT_FOUND');
  end if;

  for v_try in 1..3 loop
    v_sin_marcador := null;
    select ie.id, ie.creado_por_comando into v_intento_id, v_marcador
    from public.intentos_examen ie
    where ie.usuario_id = p_usuario_id and ie.examen_id = p_examen_id and ie.estado = 'en_progreso'
    limit 1;
    if found and v_marcador is not null then
      return jsonb_build_object('outcome', 'RESUMED', 'intento_id', v_intento_id);
    end if;
    if found then
      -- Open attempt that this command did not create (legacy start: no entitlement check, no frozen set). It is
      -- never resumed and never frozen late. It is closed below, only once the exam is known to be startable.
      v_sin_marcador := v_intento_id;
    end if;

    select count(*) into v_total
    from public.preguntas_examen pe
    join public.preguntas p on p.id = pe.pregunta_id
    where pe.examen_id = p_examen_id and pe.anulada is not true and p.activa;
    if v_total = 0 then
      return jsonb_build_object('outcome', 'EXAM_EMPTY');
    end if;
    if v_total > 200 then
      return jsonb_build_object('outcome', 'EXAM_TOO_LARGE', 'total', v_total);
    end if;

    if v_sin_marcador is not null then
      -- The caller passed the entitlement policy for THIS start; the unmarked attempt is unreachable through every
      -- route (commands and reads report it as not found) and would block ux_intentos_examen_abierto for ever.
      update public.intentos_examen
         set estado = 'abandonado',
             metadatos = metadatos || jsonb_build_object(
               'cerradoPor', 'app_start_exam_attempt', 'motivo', 'intento_sin_marcador_de_comando')
       where id = v_sin_marcador and estado = 'en_progreso' and creado_por_comando is null;
    end if;

    v_intento_id := null;
    insert into public.intentos_examen as nuevo
      (usuario_id, examen_id, estado, total_preguntas, especialidad_snapshot_id, origen_intento, creado_por_comando)
    values (p_usuario_id, p_examen_id, 'en_progreso', v_total, v_especialidad_id, 'examen', 'app_start_exam_attempt')
    on conflict (usuario_id, examen_id) where estado = 'en_progreso' do nothing
    returning nuevo.id into v_intento_id;

    if v_intento_id is not null then
      v_frozen := private.app_congelar_preguntas_intento(v_intento_id, p_examen_id);
      if v_frozen = 0 or v_frozen > 200 then
        -- The exam changed between the count and the freeze: roll the whole command back; the caller retries.
        raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001';
      end if;
      if v_frozen <> v_total then
        update public.intentos_examen set total_preguntas = v_frozen where id = v_intento_id;
      end if;
      return jsonb_build_object('outcome', 'CREATED', 'intento_id', v_intento_id);
    end if;
    -- Another transaction holds the open attempt: it is committed by now, the next round returns it.
  end loop;

  raise exception 'APP_START_ATTEMPT_RETRY' using errcode = '40001';
end;
$$;

revoke all on function public.app_start_exam_attempt(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_start_exam_attempt(uuid, uuid) to service_role;

-- ---------------------------------------------------------------------------------------------------------------
-- D2-09 record / skip an answer (redefined: marker + origen required; no late freeze)
-- ---------------------------------------------------------------------------------------------------------------
create or replace function public.app_record_attempt_answer(
  p_usuario_id uuid,
  p_intento_id uuid,
  p_pregunta_id uuid,
  p_opcion_id uuid,
  p_tiempo_respuesta_ms integer,
  p_base_revision integer,
  p_nivel_confianza integer
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_estado text;
  v_examen_id uuid;
  v_ranking boolean;
  v_iniciado timestamp with time zone;
  v_posicion integer;
  v_correcta_id uuid;
  v_row public.respuestas_intento%rowtype;
  v_exists boolean;
  v_first boolean;
  v_last_event timestamp with time zone;
  v_gap_ms bigint;
  v_ms integer;
  v_clamped boolean;
  v_meta jsonb;
  v_revision integer;
  v_respondidas integer;
  v_constraint text;
begin
  if p_usuario_id is null or p_intento_id is null or p_pregunta_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  -- Same key and same order as public.record_answer: (usuario, pregunta) first, then the attempt row.
  perform pg_advisory_xact_lock(hashtextextended(p_usuario_id::text || ':' || p_pregunta_id::text, 0));

  select ie.estado, ie.examen_id, ie.cuenta_para_ranking, ie.iniciado_en
    into v_estado, v_examen_id, v_ranking, v_iniciado
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_IN_PROGRESS', 'estado', v_estado);
  end if;

  select pi.posicion into v_posicion
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id and pi.pregunta_id = p_pregunta_id;
  if not found then
    return jsonb_build_object('outcome', 'QUESTION_NOT_IN_ATTEMPT');
  end if;

  if p_opcion_id is not null and not exists (
       select 1 from public.opciones_pregunta op where op.id = p_opcion_id and op.pregunta_id = p_pregunta_id) then
    return jsonb_build_object('outcome', 'OPTION_NOT_IN_QUESTION');
  end if;

  select ri.* into v_row
  from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.pregunta_id = p_pregunta_id
  limit 1;
  v_exists := found;
  v_revision := case when v_exists then v_row.revision else 0 end;

  -- Annulled between start and now: nothing is stored, nothing scores.
  if exists (select 1 from public.preguntas_examen pe
             where pe.examen_id = v_examen_id and pe.pregunta_id = p_pregunta_id and pe.anulada) then
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
    return jsonb_build_object('outcome', 'OK', 'estado', 'ANNULLED', 'revision', v_revision,
                              'es_primera_respuesta', false, 'respondidas', v_respondidas, 'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not distinct from p_opcion_id then
    -- Same option (or skip again): no-op whatever the base revision.
    select count(*) into v_respondidas
    from public.respuestas_intento ri
    where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
      and not exists (select 1 from public.preguntas_examen pe
                      where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);
    return jsonb_build_object(
      'outcome', 'OK',
      'estado', case when v_row.opcion_seleccionada_id is null then 'SKIPPED' else 'ANSWERED' end,
      'revision', v_revision,
      'es_primera_respuesta', v_row.es_primer_intento_pregunta,
      'respondidas', v_respondidas,
      'noop', true);
  end if;

  if v_exists and v_row.opcion_seleccionada_id is not null then
    -- DEC-015 open: a recorded answer is not changed (nor cleared) until the product rule exists.
    return jsonb_build_object('outcome', 'ANSWER_LOCKED', 'current_revision', v_revision);
  end if;

  if coalesce(p_base_revision, -1) <> v_revision then
    return jsonb_build_object('outcome', 'REVISION_CONFLICT', 'current_revision', v_revision);
  end if;

  if p_opcion_id is not null then
    select sp.opcion_correcta_id into v_correcta_id
    from public.soluciones_pregunta sp where sp.pregunta_id = p_pregunta_id limit 1;
    if v_correcta_id is null then
      raise exception 'APP_RECORD_ANSWER_MISSING_SOLUTION';
    end if;
  end if;

  -- Client time is advisory: clamp to [0, server gap since the previous event of this attempt].
  select greatest(v_iniciado, coalesce(max(greatest(ri.respondida_en, ri.actualizada_en, ri.creada_en)), v_iniciado))
    into v_last_event
  from public.respuestas_intento ri where ri.intento_id = p_intento_id;
  v_gap_ms := greatest(0, floor(extract(epoch from (clock_timestamp() - v_last_event)) * 1000))::bigint;
  v_ms := least(greatest(coalesce(p_tiempo_respuesta_ms, 0), 0)::bigint, v_gap_ms, 2147483647)::integer;
  v_clamped := coalesce(p_tiempo_respuesta_ms, 0) <> v_ms;
  v_meta := jsonb_strip_nulls(jsonb_build_object(
    'nivelConfianza', p_nivel_confianza,
    'tiempoRecortado', case when v_clamped then true end));

  -- First response of the user to the question (PDF 2.1-05): a skip never consumes it.
  v_first := p_opcion_id is not null and not exists (
    select 1 from public.respuestas_intento prev
    where prev.usuario_id = p_usuario_id and prev.pregunta_id = p_pregunta_id
      and prev.opcion_seleccionada_id is not null
      and (not v_exists or prev.id <> v_row.id));

  if not v_exists then
    begin
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), coalesce(v_ranking, false) and v_first, v_first,
        p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
        (v_ms / 1000), v_meta
      )
      returning r.revision, r.es_primer_intento_pregunta into v_revision, v_first;
    exception when unique_violation then
      get stacked diagnostics v_constraint = constraint_name;
      if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
        raise;
      end if;
      insert into public.respuestas_intento as r (
        intento_id, pregunta_id, opcion_seleccionada_id, es_correcta, usuario_id, examen_id, opcion_correcta_id,
        posicion_pregunta, respondida_en, cuenta_para_ranking, es_primer_intento_pregunta,
        primera_opcion_id, primera_es_correcta, revision, actualizada_en, tiempo_respuesta_ms,
        tiempo_respuesta_segundos, metadatos
      ) values (
        p_intento_id, p_pregunta_id, p_opcion_id, p_opcion_id = v_correcta_id, p_usuario_id, v_examen_id, v_correcta_id,
        v_posicion, now(), false, false,
        p_opcion_id, p_opcion_id = v_correcta_id, 1, now(), v_ms,
        (v_ms / 1000), v_meta
      )
      returning r.revision, r.es_primer_intento_pregunta into v_revision, v_first;
    end;
  else
    -- The row was a skip and is answered now. primera_* (the skip) is never touched.
    begin
      update public.respuestas_intento r
         set opcion_seleccionada_id = p_opcion_id,
             es_correcta = (p_opcion_id = v_correcta_id),
             opcion_correcta_id = v_correcta_id,
             revision = r.revision + 1,
             actualizada_en = now(),
             cambio_respuesta = true,
             es_primer_intento_pregunta = v_first,
             cuenta_para_ranking = coalesce(v_ranking, false) and v_first,
             tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
             tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
             metadatos = r.metadatos || v_meta
       where r.id = v_row.id
      returning r.revision, r.es_primer_intento_pregunta into v_revision, v_first;
    exception when unique_violation then
      get stacked diagnostics v_constraint = constraint_name;
      if v_constraint is distinct from 'ux_respuestas_first_response' or v_first is not true then
        raise;
      end if;
      update public.respuestas_intento r
         set opcion_seleccionada_id = p_opcion_id,
             es_correcta = (p_opcion_id = v_correcta_id),
             opcion_correcta_id = v_correcta_id,
             revision = r.revision + 1,
             actualizada_en = now(),
             cambio_respuesta = true,
             es_primer_intento_pregunta = false,
             cuenta_para_ranking = false,
             tiempo_respuesta_ms = least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647)::integer,
             tiempo_respuesta_segundos = (least(coalesce(r.tiempo_respuesta_ms, 0)::bigint + v_ms, 2147483647) / 1000)::integer,
             metadatos = r.metadatos || v_meta
       where r.id = v_row.id
      returning r.revision, r.es_primer_intento_pregunta into v_revision, v_first;
    end;
  end if;

  select count(*) into v_respondidas
  from public.respuestas_intento ri
  where ri.intento_id = p_intento_id and ri.opcion_seleccionada_id is not null
    and not exists (select 1 from public.preguntas_examen pe
                    where pe.examen_id = v_examen_id and pe.pregunta_id = ri.pregunta_id and pe.anulada);

  update public.intentos_examen
     set total_respondidas = v_respondidas,
         ultima_pregunta_posicion = v_posicion
   where id = p_intento_id;

  return jsonb_build_object(
    'outcome', 'OK',
    'estado', case when p_opcion_id is null then 'SKIPPED' else 'ANSWERED' end,
    'revision', v_revision,
    'es_primera_respuesta', coalesce(v_first, false),
    'respondidas', v_respondidas,
    'noop', false);
end;
$$;

revoke all on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) from public, anon, authenticated;
grant execute on function public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer) to service_role;

-- ---------------------------------------------------------------------------------------------------------------
-- D2-10 finish (redefined: marker + origen required; no late freeze)
-- ---------------------------------------------------------------------------------------------------------------
create or replace function public.app_finish_exam_attempt(p_usuario_id uuid, p_intento_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_estado text;
  v_examen_id uuid;
  v_iniciado timestamp with time zone;
  v_correctas integer;
  v_falladas integer;
  v_sin_responder integer;
begin
  if p_usuario_id is null or p_intento_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  select ie.estado, ie.examen_id, ie.iniciado_en into v_estado, v_examen_id, v_iniciado
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado = 'finalizado' then
    return jsonb_build_object('outcome', 'OK', 'already', true);
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_ABANDONED');
  end if;

  update public.preguntas_intento pi
     set estado_final = s.estado_final
  from (
    select q.id,
           case
             when exists (select 1 from public.preguntas_examen pe
                          where pe.examen_id = v_examen_id and pe.pregunta_id = q.pregunta_id and pe.anulada) then 'ANNULLED'
             when ri.id is null then 'UNANSWERED'
             when ri.opcion_seleccionada_id is null then 'SKIPPED'
             when ri.es_correcta is true then 'CORRECT'
             else 'WRONG'
           end as estado_final
    from public.preguntas_intento q
    left join public.respuestas_intento ri on ri.intento_id = q.intento_id and ri.pregunta_id = q.pregunta_id
    where q.intento_id = p_intento_id
  ) s
  where pi.id = s.id;

  select count(*) filter (where pi.estado_final = 'CORRECT'),
         count(*) filter (where pi.estado_final = 'WRONG'),
         count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED'))
    into v_correctas, v_falladas, v_sin_responder
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id;

  insert into public.resumen_intento_etiqueta
    (intento_id, etiqueta_id, total_preguntas, correctas, falladas, sin_responder,
     tiempo_total_segundos, tiempo_medio_segundos, porcentaje_acierto)
  select p_intento_id,
         t.etiqueta_id,
         count(*)::integer,
         (count(*) filter (where pi.estado_final = 'CORRECT'))::integer,
         (count(*) filter (where pi.estado_final = 'WRONG'))::integer,
         (count(*) filter (where pi.estado_final in ('SKIPPED', 'UNANSWERED')))::integer,
         (coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000)::integer,
         round(coalesce(sum(ri.tiempo_respuesta_ms), 0) / 1000.0 / count(*), 2),
         case when count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')) > 0
              then round(count(*) filter (where pi.estado_final = 'CORRECT') * 100.0
                         / count(*) filter (where pi.estado_final in ('CORRECT', 'WRONG')), 2)
              else 0 end
  from public.preguntas_intento pi
  join lateral (
    select ep.etiqueta_id from public.etiquetas_pregunta ep
    where ep.pregunta_id = pi.pregunta_id
    order by ep.creado_en asc, ep.etiqueta_id asc
    limit 1
  ) t on true
  left join public.respuestas_intento ri on ri.intento_id = pi.intento_id and ri.pregunta_id = pi.pregunta_id
  where pi.intento_id = p_intento_id and pi.estado_final <> 'ANNULLED'
  group by t.etiqueta_id
  on conflict (intento_id, etiqueta_id) do nothing;

  update public.intentos_examen
     set estado = 'finalizado',
         motivo_fin = 'USER',
         finalizado_en = now(),
         duracion_segundos = greatest(0, floor(extract(epoch from (now() - v_iniciado))))::integer,
         total_respondidas = v_correctas + v_falladas,
         total_correctas = v_correctas,
         total_falladas = v_falladas,
         total_sin_responder = v_sin_responder,
         porcentaje_acierto = case when v_correctas + v_falladas > 0
                                   then round(v_correctas * 100.0 / (v_correctas + v_falladas), 2) else 0 end,
         puntuacion = v_correctas,
         calculado_en = now()
   where id = p_intento_id;

  return jsonb_build_object('outcome', 'OK', 'already', false);
end;
$$;

revoke all on function public.app_finish_exam_attempt(uuid, uuid) from public, anon, authenticated;
grant execute on function public.app_finish_exam_attempt(uuid, uuid) to service_role;

-- ---------------------------------------------------------------------------------------------------------------
-- D2-11 abandon (redefined: marker + origen required)
-- ---------------------------------------------------------------------------------------------------------------
create or replace function public.app_abandon_exam_attempt(
  p_usuario_id uuid,
  p_intento_id uuid,
  p_posicion integer,
  p_motivo text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_estado text;
  v_examen_id uuid;
  v_iniciado timestamp with time zone;
  v_total integer;
  v_pregunta_id uuid;
  v_motivo_detectado text;
begin
  if p_usuario_id is null or p_intento_id is null then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;

  select ie.estado, ie.examen_id, ie.iniciado_en, ie.total_preguntas
    into v_estado, v_examen_id, v_iniciado, v_total
  from public.intentos_examen ie
  where ie.id = p_intento_id and ie.usuario_id = p_usuario_id
    and ie.origen_intento = 'examen' and ie.creado_por_comando is not null
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado = 'abandonado' then
    return jsonb_build_object('outcome', 'OK', 'already', true);
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_ALREADY_FINISHED');
  end if;

  -- The wire value is kept verbatim in metadatos; motivo_detectado uses MAIN's vocabulary (CHECK).
  v_motivo_detectado := case p_motivo when 'salida_usuario' then 'cerrar_examen' when 'otro' then 'desconocido' end;
  if v_motivo_detectado is null then
    return jsonb_build_object('outcome', 'INVALID_REASON');
  end if;
  if p_posicion is null or p_posicion < 1 or (v_total > 0 and p_posicion > v_total) then
    return jsonb_build_object('outcome', 'INVALID_POSITION', 'total', v_total);
  end if;

  select pi.pregunta_id into v_pregunta_id
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id and pi.posicion = p_posicion;

  update public.intentos_examen
     set estado = 'abandonado',
         abandonado_en_posicion = p_posicion,
         abandonado_en_pregunta_id = v_pregunta_id,
         ultima_pregunta_posicion = p_posicion
   where id = p_intento_id;

  insert into public.abandonos_examen
    (usuario_id, intento_id, examen_id, pregunta_id, posicion_pregunta, tiempo_transcurrido_segundos,
     total_preguntas, motivo_detectado, metadatos)
  values
    (p_usuario_id, p_intento_id, v_examen_id, v_pregunta_id, p_posicion,
     greatest(0, floor(extract(epoch from (now() - v_iniciado))))::integer,
     v_total, v_motivo_detectado, jsonb_build_object('motivo', p_motivo, 'origen', 'D2-11'));

  return jsonb_build_object('outcome', 'OK', 'already', false);
end;
$$;

revoke all on function public.app_abandon_exam_attempt(uuid, uuid, integer, text) from public, anon, authenticated;
grant execute on function public.app_abandon_exam_attempt(uuid, uuid, integer, text) to service_role;

-- POST-CHECK: `if not exists` skips a pre-existing same-name index, so assert the exact definitions.
do $$
declare
  v_bad text;
begin
  select string_agg(want.name || ' is ' || coalesce(pg_get_indexdef(i.indexrelid), 'missing'), '; ' order by want.name) into v_bad
  from (values
    ('idx_respuestas_intento_primera_opcion_id',
     'CREATE INDEX idx_respuestas_intento_primera_opcion_id ON public.respuestas_intento USING btree (primera_opcion_id) WHERE (primera_opcion_id IS NOT NULL)'),
    ('idx_intentos_examen_especialidad_snapshot_id',
     'CREATE INDEX idx_intentos_examen_especialidad_snapshot_id ON public.intentos_examen USING btree (especialidad_snapshot_id) WHERE (especialidad_snapshot_id IS NOT NULL)')
  ) as want(name, def)
  left join pg_index i on i.indexrelid = to_regclass('public.' || want.name)
  where i.indexrelid is null or not i.indisvalid or not i.indisready
     or pg_get_indexdef(i.indexrelid) is distinct from want.def;
  if v_bad is not null then
    raise exception 'SLICE3_HARDENING_INDEX_WRONG_DEFINITION'
      using detail = v_bad,
            hint = 'A same-name relation with another definition already exists. The migration does not drop it: review, then re-apply.';
  end if;
end;
$$;

commit;
