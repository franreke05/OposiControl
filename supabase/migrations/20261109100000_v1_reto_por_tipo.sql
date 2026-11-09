-- =============================================================================
-- V1 -- daily challenge (reto diario / racha): one question PER EXAM TYPE from the question bank of the published exams (F20).
-- Corrective; 20261022101200, 20261022101300 and 20261022103500 are NOT edited. Forward-only.
-- =============================================================================
-- OWNER DECISION 2026-11-09 (F20): the daily challenge draws its questions from the published exams of the user's specialty: ONE question of the
-- inmersivo exams, ONE of the razonado exams, ONE of the rafaga exams. A type without any eligible question is SKIPPED (a challenge of 1 to 3
-- questions); the other slots fill on their own as soon as exams of that type are published. Contract: FA46 (text only, engagement.yaml).
--
-- BEFORE (20261022101200 + 101300 + 103500): private.reto_pool filtered by the QUESTION type and, for the immersive slot, by Control's EXPLICIT
-- demo flags (preguntas_examen.es_demo); private.ensure_challenge_row created NO row when any of the three slots had no pool. In production only
-- rafaga exams exist, so no row was ever created and GET /retos/hoy answered NO_DISPONIBLE to everyone (the streak never advanced).
--
-- WHAT IT DOES
--   * private.reto_pool(esp, com, slot): the active questions of the ACTIVE, PUBLISHED (publicado_en <= now) exams of the specialty whose
--     tipo_examen is the slot's exam type (inmersiva <- inmersivo, razonada <- razonado, rafaga <- rafaga), main (not reserve) and not annulled
--     in that exam. The demo flag no longer matters. The regional rule (etiquetas_comunidad) is unchanged. The question type must still match the
--     slot (MAIN's triggers already force exam type == question type; this only keeps the served tipoSlot honest).
--   * private.ensure_challenge_row: per slot (fixed order inmersiva, razonada, rafaga) ONE question chosen DETERMINISTICALLY for the day and segment
--     (smallest md5(fecha|especialidad|comunidad|slot|pregunta)): every creator of the same segment and day picks the same questions; empty slots
--     are skipped; positions are compact 1..n in that order; no slot at all = no row (NO_DISPONIBLE, as before). The stored tipo_slot keeps MAIN's
--     vocabulary ('inmersiva_demo' = the immersive slot, served as INMERSIVA; the CHECK of MAIN is not touched).
--   * Pass rule (the row carries it, the answer function already reads it): objetivo_preguntas = n and aciertos_necesarios = ceil(2n/3), i.e.
--     3 questions -> 2 correct (unchanged), 2 -> 2, 1 -> 1. The challenge closes on the n-th answer; streak and reward rules are unchanged
--     (a passed challenge counts one streak day and earns the plan amounts, whatever n is).
--   * Unchanged: one row per day and segment (ux_retos_diarios_segmento), the frozen row per user per Madrid day, the window to the next local
--     midnight (DST), the first open without a global lock, answers final and checked on the server, no correction before answering, the claim
--     and the earn cap, the media chain, attempts and ranking (nothing here reads or writes them). Rows already created keep their questions.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the bodies of 20261022101300 (reto_pool) and 20261022103500 (ensure_challenge_row) come
-- back verbatim. Rows created while this migration was live are data and stay as they are (their own objetivo/aciertos keep them consistent).
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function private.reto_pool(p_esp uuid, p_com uuid, p_slot text)
-- returns setof uuid
-- language sql
-- stable
-- security definer
-- set search_path = ''
-- as $$
--   select distinct p.id
--     from public.preguntas p
--     join public.preguntas_examen pe on pe.pregunta_id = p.id and pe.anulada is not true and not pe.es_reserva and (p_slot <> 'inmersiva' or pe.es_demo)
--     join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= clock_timestamp() and e.especialidad_id = p_esp
--    where p.activa and p.tipo_pregunta = p_slot
--      and (not exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id where ep.pregunta_id = p.id)
--           or (p_com is not null and exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
--                                              where ep.pregunta_id = p.id and ec.comunidad_id = p_com)))
-- $$;
-- revoke all on function private.reto_pool(uuid, uuid, text) from public, anon, authenticated;
-- create or replace function private.ensure_challenge_row(p_fecha date, p_esp uuid, p_com uuid)
-- returns boolean
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_reto uuid;
--   v_inm uuid;
--   v_raz uuid;
--   v_raf uuid;
--   v_inicio timestamptz;
--   v_hay_regional boolean;
-- begin
--   if exists (select 1 from public.retos_diarios r where r.fecha = p_fecha and r.especialidad_id = p_esp and r.comunidad_id is not distinct from p_com) then
--     return false;
--   end if;
--   -- a regional row only exists when some regional-tagged question can enter it (otherwise it would equal the national one)
--   if p_com is not null then
--     select exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
--                     where ec.comunidad_id = p_com
--                       and ep.pregunta_id in (select private.reto_pool(p_esp, p_com, 'rafaga') union select private.reto_pool(p_esp, p_com, 'razonada') union select private.reto_pool(p_esp, p_com, 'inmersiva')))
--       into v_hay_regional;
--     if not v_hay_regional then
--       return false;
--     end if;
--   end if;
--   select q into v_inm from private.reto_pool(p_esp, p_com, 'inmersiva') q order by random() limit 1;
--   select q into v_raz from private.reto_pool(p_esp, p_com, 'razonada') q order by random() limit 1;
--   select q into v_raf from private.reto_pool(p_esp, p_com, 'rafaga') q order by random() limit 1;
--   if v_inm is null or v_raz is null or v_raf is null then
--     return false;  -- a slot without pool: no challenge for this segment today (never relax the regional rule)
--   end if;
--   -- the window ends at the NEXT LOCAL MIDNIGHT of the configured zone (25 h on the October DST day, 23 h in March), never inicio + 24 h
--   v_inicio := (p_fecha::timestamp + make_interval(hours => private.config_int('reto_hora_desbloqueo', 0))) at time zone private.reto_tz();
--   insert into public.retos_diarios (fecha, titulo, descripcion, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario)
--   values (p_fecha, 'Reto diario', null, 3, 2, 0, true, v_inicio, private.reto_day_end(p_fecha), p_esp, p_com, 'todos')
--   on conflict do nothing
--   returning id into v_reto;
--   if v_reto is null then
--     return false;
--   end if;
--   insert into public.preguntas_reto_diario (reto_id, pregunta_id, posicion, tipo_slot) values
--     (v_reto, v_inm, 1, 'inmersiva_demo'), (v_reto, v_raz, 2, 'razonada'), (v_reto, v_raf, 3, 'rafaga');
--   return true;
-- end;
-- $$;
-- revoke all on function private.ensure_challenge_row(date, uuid, uuid) from public, anon, authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'private_helper_client_executable:' || f.n as check_name,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint as failures
--   from (values ('private.reto_pool(uuid,uuid,text)'), ('private.ensure_challenge_row(date,uuid,uuid)')) f(n)
-- union all
-- select 'reto_pool_not_by_published_exam_type',
--        (case when (select p.prosrc from pg_proc p where p.oid = 'private.reto_pool(uuid,uuid,text)'::regprocedure) like '%e.tipo_examen = case p_slot%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.reto_pool(uuid,uuid,text)'::regprocedure) not like '%es_demo%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.reto_pool(uuid,uuid,text)'::regprocedure) like '%not pe.es_reserva%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.reto_pool(uuid,uuid,text)'::regprocedure) like '%pe.anulada is not true%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.reto_pool(uuid,uuid,text)'::regprocedure) like '%e.publicado_en <= clock_timestamp()%' then 0 else 1 end)::bigint
-- union all
-- select 'challenge_row_requires_every_slot',
--        (case when (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) like '%array_length(v_ids, 1)%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) like '%reto_day_end(p_fecha)%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) not like '%order by random()%' then 0 else 1 end)::bigint
-- union all
-- select 'open_rows_whose_target_differs_from_their_questions',
--        (select count(*) from public.retos_diarios d
--          where d.fecha >= private.reto_today(clock_timestamp())
--            and d.objetivo_preguntas <> (select count(*) from public.preguntas_reto_diario pr where pr.reto_id = d.id))::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed)
do $$
begin
  if to_regprocedure('private.reto_pool(uuid,uuid,text)') is null or to_regprocedure('private.ensure_challenge_row(date,uuid,uuid)') is null
     or to_regprocedure('private.reto_day_end(date)') is null or to_regprocedure('private.config_int(text,integer)') is null
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'examenes' and column_name = 'tipo_examen')
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'preguntas_examen' and column_name = 'es_reserva') then
    raise exception using errcode = 'P0001', message = 'V1_RETO_POR_TIPO_PREFLIGHT', hint = 'apply 20261022101200, 20261022101300 and 20261022103500 first';
  end if;
end
$$;

-- ---- 1. pool of a slot = the questions of the published exams of that TYPE of the specialty
create or replace function private.reto_pool(p_esp uuid, p_com uuid, p_slot text)
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
  -- F20: slot inmersiva <- inmersivo exams, razonada <- razonado, rafaga <- rafaga; main (non-reserve), non-annulled, active questions; no demo flag
  select distinct p.id
    from public.preguntas p
    join public.preguntas_examen pe on pe.pregunta_id = p.id and pe.anulada is not true and not pe.es_reserva
    join public.examenes e on e.id = pe.examen_id and e.activo and e.publicado_en is not null and e.publicado_en <= clock_timestamp() and e.especialidad_id = p_esp
                          and e.tipo_examen = case p_slot when 'inmersiva' then 'inmersivo' when 'razonada' then 'razonado' when 'rafaga' then 'rafaga' end
   where p.activa and p.tipo_pregunta = p_slot
     and (not exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id where ep.pregunta_id = p.id)
          or (p_com is not null and exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
                                             where ep.pregunta_id = p.id and ec.comunidad_id = p_com)))
$$;
revoke all on function private.reto_pool(uuid, uuid, text) from public, anon, authenticated;

-- ---- 2. one row for a segment and a day: one deterministic question per slot that has a pool; empty slots skipped
create or replace function private.ensure_challenge_row(p_fecha date, p_esp uuid, p_com uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_reto uuid;
  v_q uuid;
  v_ids uuid[] := '{}';
  v_slots text[] := '{}';
  v_n integer;
  v_inicio timestamptz;
  v_hay_regional boolean;
  s record;
begin
  if exists (select 1 from public.retos_diarios r where r.fecha = p_fecha and r.especialidad_id = p_esp and r.comunidad_id is not distinct from p_com) then
    return false;
  end if;
  -- a regional row only exists when some regional-tagged question can enter it (otherwise it would equal the national one)
  if p_com is not null then
    select exists (select 1 from public.etiquetas_pregunta ep join public.etiquetas_comunidad ec on ec.etiqueta_id = ep.etiqueta_id
                    where ec.comunidad_id = p_com
                      and ep.pregunta_id in (select private.reto_pool(p_esp, p_com, 'rafaga') union select private.reto_pool(p_esp, p_com, 'razonada') union select private.reto_pool(p_esp, p_com, 'inmersiva')))
      into v_hay_regional;
    if not v_hay_regional then
      return false;
    end if;
  end if;
  -- fixed slot order; the pick is the same for every creator of this day and segment (no random(): concurrent first opens agree)
  for s in select x.slot, x.tipo_slot from (values (1, 'inmersiva', 'inmersiva_demo'), (2, 'razonada', 'razonada'), (3, 'rafaga', 'rafaga')) x(orden, slot, tipo_slot) order by x.orden loop
    v_q := null;
    select q into v_q from private.reto_pool(p_esp, p_com, s.slot) q
     order by md5(p_fecha::text || '|' || p_esp::text || '|' || coalesce(p_com::text, '-') || '|' || s.slot || '|' || q::text), q
     limit 1;
    if v_q is not null then
      v_ids := v_ids || v_q;
      v_slots := v_slots || s.tipo_slot::text;
    end if;
  end loop;
  v_n := coalesce(array_length(v_ids, 1), 0);
  if v_n = 0 then
    return false;  -- no slot has a pool: no challenge for this segment today (never relax the regional rule)
  end if;
  -- the window ends at the NEXT LOCAL MIDNIGHT of the configured zone (25 h on the October DST day, 23 h in March), never inicio + 24 h
  v_inicio := (p_fecha::timestamp + make_interval(hours => private.config_int('reto_hora_desbloqueo', 0))) at time zone private.reto_tz();
  -- pass rule: ceil(2n/3) correct of n (3 -> 2, 2 -> 2, 1 -> 1)
  insert into public.retos_diarios (fecha, titulo, descripcion, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario)
  values (p_fecha, 'Reto diario', null, v_n, (2 * v_n + 2) / 3, 0, true, v_inicio, private.reto_day_end(p_fecha), p_esp, p_com, 'todos')
  on conflict do nothing
  returning id into v_reto;
  if v_reto is null then
    return false;
  end if;
  insert into public.preguntas_reto_diario (reto_id, pregunta_id, posicion, tipo_slot)
  select v_reto, v_ids[g.i], g.i, v_slots[g.i] from generate_subscripts(v_ids, 1) as g(i);
  return true;
end;
$$;
revoke all on function private.ensure_challenge_row(date, uuid, uuid) from public, anon, authenticated;

commit;
