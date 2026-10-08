-- =============================================================================
-- SLICE 3 -- consumer exam runtime: atomic command RPCs (D2-03, D2-09, D2-10, D2-11)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only: FIVE NEW functions; no table, column,
-- index, policy, grant or existing function is changed, and public.record_answer / the trigger functions
-- preparar_intento_examen and validar_y_calcular_respuesta_intento are NOT replaced (they keep firing as on MAIN).
-- Requires 20261008100000 (columns + ux_intentos_examen_abierto). Idempotent (create or replace), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   Ktor (service_role key) owns identity, the entitlement policy (EntitlementPolicy, D7-25) and the wire; the
--   database owns atomicity. Every command is ONE transaction, serialised on the attempt row, and returns a jsonb
--   `{outcome: ...}` instead of raising for business outcomes (Ktor maps the outcome to the canonical envelope):
--
--   private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid) returns integer
--     Freezes the question set of an attempt: the exam's links (preguntas_examen) that are NOT annulled and whose
--     question is active (preguntas.activa), in exam order, renumbered 1..n. No-op (returns the existing count) if
--     the attempt already has a frozen set. Internal helper (schema private; no client can reach it).
--
--   public.app_start_exam_attempt(p_usuario_id uuid, p_examen_id uuid) returns jsonb            -- D2-03
--     outcome CREATED | RESUMED (+ intento_id) | EXAM_NOT_FOUND (missing, draft or archived) | EXAM_EMPTY |
--     EXAM_TOO_LARGE (> 200 servable questions: the frozen wire bounds an attempt to 200).
--     INSERT ... ON CONFLICT (usuario_id, examen_id) WHERE estado = 'en_progreso' DO NOTHING on
--     ux_intentos_examen_abierto: N concurrent starts create ONE attempt and the rest return it (RESUMED). The set
--     is frozen in the same transaction and especialidad_snapshot_id is fixed from the exam (DEC-002).
--     A finalizado / abandonado attempt does not block a new one (retake).
--
--   public.app_record_attempt_answer(p_usuario_id uuid, p_intento_id uuid, p_pregunta_id uuid, p_opcion_id uuid,
--       p_tiempo_respuesta_ms integer, p_base_revision integer, p_nivel_confianza integer) returns jsonb -- D2-09
--     outcome OK (estado ANSWERED | SKIPPED | ANNULLED, revision, es_primera_respuesta, respondidas) |
--     ATTEMPT_NOT_FOUND (unknown or not the owner) | ATTEMPT_NOT_IN_PROGRESS | QUESTION_NOT_IN_ATTEMPT |
--     OPTION_NOT_IN_QUESTION | ANSWER_LOCKED | REVISION_CONFLICT (+ current_revision).
--     Why not public.record_answer: it cannot skip (NULL option), has no revision / base revision, no time, checks
--     membership against the live exam instead of the frozen set and raises instead of returning typed outcomes.
--     It is left untouched for the legacy POST /intentos/{id}/responder. Both take the SAME advisory lock
--     (usuario:pregunta) and then the attempt row, so they serialise and cannot deadlock each other.
--     Rules (technical defaults while DEC-015 is open): same option again = no-op; a skipped question may be
--     answered later (revision + 1, needs the current base revision); a non-null answer can NOT be changed
--     (ANSWER_LOCKED); correctness is stored but never returned. A skip never consumes the user's first response
--     (ux_respuestas_first_response). An annulled question stores nothing.
--
--   public.app_finish_exam_attempt(p_usuario_id uuid, p_intento_id uuid) returns jsonb          -- D2-10
--     outcome OK (already: true when it was finalizado: nothing is recomputed) | ATTEMPT_NOT_FOUND |
--     ATTEMPT_ABANDONED. Under FOR UPDATE: freezes preguntas_intento.estado_final, the totals (annulled questions
--     are not evaluable: they are in no total), resumen_intento_etiqueta, motivo_fin = 'USER', finalizado_en,
--     duracion_segundos. The legacy columns keep their legacy meaning (porcentaje_acierto = correctas / respondidas,
--     puntuacion = correctas). The nota (DEC-013) is NOT stored: Ktor derives it from the frozen totals.
--
--   public.app_abandon_exam_attempt(p_usuario_id uuid, p_intento_id uuid, p_posicion integer, p_motivo text)
--       returns jsonb                                                                           -- D2-11
--     outcome OK (already: true when it was abandonado: no second abandonos_examen row) | ATTEMPT_NOT_FOUND |
--     ATTEMPT_ALREADY_FINISHED | INVALID_POSITION | INVALID_REASON. Elapsed time is computed here (now() -
--     iniciado_en), never taken from the client.
--
-- FORWARD PLAN
--   Apply right after 20261008100000 and before the Slice 3 Ktor build. Old Ktor does not call these functions.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops the five functions (run it BEFORE 20261008100000's ROLLBACK). The Slice 3 Ktor
--   routes then answer 5xx (missing RPC); legacy routes are unaffected. No data is touched.
--
-- LOCKING
--   CREATE FUNCTION only (catalog). At run time each command locks ONE intentos_examen row FOR UPDATE.
--
-- DATA LOSS
--   None.
--
-- RLS AND GRANTS
--   The four public functions are SECURITY DEFINER with `search_path = ''` and EXECUTE for service_role ONLY
--   (revoked from public, anon, authenticated). The private helper is SECURITY INVOKER, revoked from clients, in a
--   schema clients cannot use. Table RLS and grants are unchanged.
--
-- CONTENT / AUTHORING FLOWS
--   Read-only on examenes, preguntas, preguntas_examen, opciones_pregunta, soluciones_pregunta, etiquetas_pregunta
--   (plain SELECT, no row lock on content). Nothing of the authoring / import flow is touched.
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate and dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_abandon_exam_attempt(uuid, uuid, integer, text);
-- drop function if exists public.app_finish_exam_attempt(uuid, uuid);
-- drop function if exists public.app_record_attempt_answer(uuid, uuid, uuid, uuid, integer, integer, integer);
-- drop function if exists public.app_start_exam_attempt(uuid, uuid);
-- drop function if exists private.app_congelar_preguntas_intento(uuid, uuid);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- with fns(sig) as (values
--   ('public.app_start_exam_attempt(uuid,uuid)'),
--   ('public.app_record_attempt_answer(uuid,uuid,uuid,uuid,integer,integer,integer)'),
--   ('public.app_finish_exam_attempt(uuid,uuid)'),
--   ('public.app_abandon_exam_attempt(uuid,uuid,integer,text)'))
-- select 'slice3_rpc_missing' as check_name,
--        count(*) filter (where to_regprocedure(sig) is null)::bigint as failures,
--        null::text as info
-- from fns
-- union all
-- select 'slice3_rpc_executable_by_clients', count(*)::bigint, null
-- from fns join pg_proc p on p.oid = to_regprocedure(fns.sig)
-- where has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute')
-- union all
-- select 'slice3_rpc_not_executable_by_service_role', count(*)::bigint, null
-- from fns join pg_proc p on p.oid = to_regprocedure(fns.sig)
-- where not has_function_privilege('service_role', p.oid, 'execute')
-- union all
-- select 'slice3_rpc_not_definer_with_pinned_search_path', count(*)::bigint, null
-- from fns join pg_proc p on p.oid = to_regprocedure(fns.sig)
-- where not (p.prosecdef and p.proconfig @> array['search_path=""'])
-- union all
-- select 'slice3_freeze_helper_missing_or_client_executable',
--        (case when to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)') is null then 1
--              when has_function_privilege('anon', to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)'), 'execute')
--                or has_function_privilege('authenticated', to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)'), 'execute') then 1
--              else 0 end)::bigint, null;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT: the schema migration and the MAIN objects the functions read and write.
do $$
declare
  v_missing text;
begin
  select string_agg(need.t || '.' || need.c, ', ' order by need.t, need.c) into v_missing
  from (values
    ('intentos_examen', 'motivo_fin'), ('intentos_examen', 'especialidad_snapshot_id'),
    ('intentos_examen', 'ultima_pregunta_posicion'), ('intentos_examen', 'abandonado_en_posicion'),
    ('intentos_examen', 'total_sin_responder'), ('intentos_examen', 'calculado_en'),
    ('respuestas_intento', 'primera_opcion_id'), ('respuestas_intento', 'primera_es_correcta'),
    ('respuestas_intento', 'revision'), ('respuestas_intento', 'actualizada_en'),
    ('respuestas_intento', 'tiempo_respuesta_ms'), ('respuestas_intento', 'es_primer_intento_pregunta'),
    ('preguntas_intento', 'estado_final'),
    ('preguntas_examen', 'anulada'), ('preguntas', 'activa'), ('examenes', 'publicado_en'),
    ('soluciones_pregunta', 'opcion_correcta_id'), ('opciones_pregunta', 'pregunta_id'),
    ('etiquetas_pregunta', 'etiqueta_id'), ('resumen_intento_etiqueta', 'tiempo_total_segundos'),
    ('abandonos_examen', 'motivo_detectado')
  ) as need(t, c)
  where not exists (
    select 1 from pg_attribute a
    where a.attrelid = to_regclass('public.' || need.t) and a.attname = need.c and not a.attisdropped
  );
  if v_missing is not null then
    raise exception 'SLICE3_RPC_PREFLIGHT' using detail = 'Missing on this database: ' || v_missing;
  end if;
  if to_regclass('public.ux_intentos_examen_abierto') is null then
    raise exception 'SLICE3_RPC_PREFLIGHT' using detail = 'public.ux_intentos_examen_abierto is missing (apply 20261008100000 first).';
  end if;
end;
$$;

-- ---------------------------------------------------------------------------------------------------------------
-- Freeze helper
-- ---------------------------------------------------------------------------------------------------------------
create or replace function private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid)
returns integer
language plpgsql
set search_path = ''
as $$
declare
  v_n integer;
begin
  select count(*) into v_n from public.preguntas_intento pi where pi.intento_id = p_intento_id;
  if v_n > 0 then
    return v_n;
  end if;
  insert into public.preguntas_intento (intento_id, pregunta_id, posicion)
  select p_intento_id, q.pregunta_id, (row_number() over (order by q.posicion, q.pregunta_id))::integer
  from (
    select pe.pregunta_id, pe.posicion
    from public.preguntas_examen pe
    join public.preguntas p on p.id = pe.pregunta_id
    where pe.examen_id = p_examen_id and pe.anulada is not true and p.activa
  ) q;
  get diagnostics v_n = row_count;
  return v_n;
end;
$$;

revoke all on function private.app_congelar_preguntas_intento(uuid, uuid) from public, anon, authenticated;

-- ---------------------------------------------------------------------------------------------------------------
-- D2-03 start or resume
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
    select ie.id into v_intento_id
    from public.intentos_examen ie
    where ie.usuario_id = p_usuario_id and ie.examen_id = p_examen_id and ie.estado = 'en_progreso'
    limit 1;
    if found then
      return jsonb_build_object('outcome', 'RESUMED', 'intento_id', v_intento_id);
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

    v_intento_id := null;
    insert into public.intentos_examen as nuevo
      (usuario_id, examen_id, estado, total_preguntas, especialidad_snapshot_id, origen_intento)
    values (p_usuario_id, p_examen_id, 'en_progreso', v_total, v_especialidad_id, 'examen')
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
-- D2-09 record / skip an answer
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
  for update;
  if not found then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_FOUND');
  end if;
  if v_estado <> 'en_progreso' then
    return jsonb_build_object('outcome', 'ATTEMPT_NOT_IN_PROGRESS', 'estado', v_estado);
  end if;

  -- An attempt opened by the legacy start has no frozen set yet: freeze it now (same rule as the start).
  if v_examen_id is not null then
    perform private.app_congelar_preguntas_intento(p_intento_id, v_examen_id);
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
-- D2-10 finish
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

  -- An attempt opened by the legacy start has no frozen set yet: freeze it now (same rule as the start).
  if v_examen_id is not null then
    perform private.app_congelar_preguntas_intento(p_intento_id, v_examen_id);
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
-- D2-11 abandon
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

commit;
