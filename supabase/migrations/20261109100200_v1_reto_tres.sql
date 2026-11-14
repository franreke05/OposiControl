-- =============================================================================
-- V1 -- daily challenge: ALWAYS 3 questions when the specialty has them (F20b).
-- Corrective; 20261109100000 is NOT edited. Forward-only. Requires 20261109100000 (one question per exam type).
-- =============================================================================
-- OWNER DECISION 2026-11-11 (F20b, plan approved): the daily reto always has 3 questions. 20261109100000 gave one per exam type with
-- published questions and SKIPPED the empty types, so with only rafaga exams in production the reto had 1 question.
--
-- WHAT IT DOES (private.ensure_challenge_row only; reto_pool and every other function unchanged)
--   * First the per-type loop of 20261109100000 (fixed order inmersiva, razonada, rafaga; one deterministic pick per type that has a pool).
--   * Then, while n < 3, it FILLS from the UNION of the three pools of the segment (same eligibility: published exams of the specialty,
--     main, non-annulled, active questions, regional rule), never repeating a question already in the reto, in a deterministic order per
--     day and segment with its own salt (md5(fecha|especialidad|comunidad|fill|pregunta)). The stored tipo_slot is the source pool type
--     (inmersiva_demo / razonada / rafaga), so a slot type can repeat (only rafaga today: 3 x RAFAGA). Positions stay compact 1..n.
--     BY DESIGN there is NO uniqueness of tipo_slot per reto (no UNIQUE, no check): the owner decision is "always 3", and with only one type
--     available (rafaga today) the repeat is the intended result. What never repeats is the QUESTION (the existing (reto, pregunta) key and the
--     fill skipping questions already chosen). Review 2026-11-14: documented instead of enforced.
--   * A segment with fewer than 3 eligible questions in total gets what exists (1 or 2); none = no row (NO_DISPONIBLE), as before.
--   * Pass rule unchanged: aciertos_necesarios = ceil(2n/3) (3 -> 2, 2 -> 2, 1 -> 1). Streak, reward, window (DST), one row per day and
--     segment, frozen row per user, first open without a global lock, answers, claim, media chain, attempts and ranking: unchanged.
--   * Rows already created keep their questions (a row of today with 1 question stays as the users opened it; the next day is filled).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the 20261109100000 body of ensure_challenge_row comes back verbatim. Rows created
-- meanwhile are data and stay (their own objetivo/aciertos keep them consistent).
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function private.ensure_challenge_row(p_fecha date, p_esp uuid, p_com uuid)
-- returns boolean
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_reto uuid;
--   v_q uuid;
--   v_ids uuid[] := '{}';
--   v_slots text[] := '{}';
--   v_n integer;
--   v_inicio timestamptz;
--   v_hay_regional boolean;
--   s record;
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
--   -- fixed slot order; the pick is the same for every creator of this day and segment (no random(): concurrent first opens agree)
--   for s in select x.slot, x.tipo_slot from (values (1, 'inmersiva', 'inmersiva_demo'), (2, 'razonada', 'razonada'), (3, 'rafaga', 'rafaga')) x(orden, slot, tipo_slot) order by x.orden loop
--     v_q := null;
--     select q into v_q from private.reto_pool(p_esp, p_com, s.slot) q
--      order by md5(p_fecha::text || '|' || p_esp::text || '|' || coalesce(p_com::text, '-') || '|' || s.slot || '|' || q::text), q
--      limit 1;
--     if v_q is not null then
--       v_ids := v_ids || v_q;
--       v_slots := v_slots || s.tipo_slot::text;
--     end if;
--   end loop;
--   v_n := coalesce(array_length(v_ids, 1), 0);
--   if v_n = 0 then
--     return false;  -- no slot has a pool: no challenge for this segment today (never relax the regional rule)
--   end if;
--   -- the window ends at the NEXT LOCAL MIDNIGHT of the configured zone (25 h on the October DST day, 23 h in March), never inicio + 24 h
--   v_inicio := (p_fecha::timestamp + make_interval(hours => private.config_int('reto_hora_desbloqueo', 0))) at time zone private.reto_tz();
--   -- pass rule: ceil(2n/3) correct of n (3 -> 2, 2 -> 2, 1 -> 1)
--   insert into public.retos_diarios (fecha, titulo, descripcion, objetivo_preguntas, aciertos_necesarios, recompensa_puntos, activo, empieza_en, termina_en, especialidad_id, comunidad_id, segmento_usuario)
--   values (p_fecha, 'Reto diario', null, v_n, (2 * v_n + 2) / 3, 0, true, v_inicio, private.reto_day_end(p_fecha), p_esp, p_com, 'todos')
--   on conflict do nothing
--   returning id into v_reto;
--   if v_reto is null then
--     return false;
--   end if;
--   insert into public.preguntas_reto_diario (reto_id, pregunta_id, posicion, tipo_slot)
--   select v_reto, v_ids[g.i], g.i, v_slots[g.i] from generate_subscripts(v_ids, 1) as g(i);
--   return true;
-- end;
-- $$;
-- revoke all on function private.ensure_challenge_row(date, uuid, uuid) from public, anon, authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'private_helper_client_executable:private.ensure_challenge_row(date,uuid,uuid)' as check_name,
--        (case when has_function_privilege('anon', 'private.ensure_challenge_row(date,uuid,uuid)', 'execute')
--                or has_function_privilege('authenticated', 'private.ensure_challenge_row(date,uuid,uuid)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'challenge_row_not_filled_to_three',
--        (case when (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) like '%|fill|%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) like '%limit 3 - v_n%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) like '%reto_day_end(p_fecha)%'
--                and (select p.prosrc from pg_proc p where p.oid = 'private.ensure_challenge_row(date,uuid,uuid)'::regprocedure) not like '%order by random()%' then 0 else 1 end)::bigint
-- union all
-- select 'reto_pool_not_by_published_exam_type',
--        (case when (select p.prosrc from pg_proc p where p.oid = 'private.reto_pool(uuid,uuid,text)'::regprocedure) like '%e.tipo_examen = case p_slot%' then 0 else 1 end)::bigint
-- union all
-- select 'open_rows_whose_target_differs_from_their_questions',
--        (select count(*) from public.retos_diarios d
--          where d.fecha >= private.reto_today(clock_timestamp())
--            and d.objetivo_preguntas <> (select count(*) from public.preguntas_reto_diario pr where pr.reto_id = d.id))::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed): 20261109100000 must be there (the per-type pool)
do $$
begin
  if to_regprocedure('private.ensure_challenge_row(date,uuid,uuid)') is null or to_regprocedure('private.reto_day_end(date)') is null
     or to_regprocedure('private.config_int(text,integer)') is null
     or (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.reto_pool(uuid,uuid,text)')) not like '%e.tipo_examen = case p_slot%' then
    raise exception using errcode = 'P0001', message = 'V1_RETO_TRES_PREFLIGHT', hint = 'apply 20261109100000 first';
  end if;
end
$$;

-- one row for a segment and a day: one deterministic question per type that has a pool, then filled to 3 from the union of the pools
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
  f record;
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
  -- 1. one per type (fixed slot order; the pick is the same for every creator of this day and segment: no random())
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
  -- 2. F20b (owner 2026-11-11): always 3 when the segment has them; fill from the union of the pools, no repeated question, own salt
  if v_n < 3 then
    for f in select c.q, c.tipo_slot
               from (select pq.q, x.tipo_slot
                       from (values ('inmersiva', 'inmersiva_demo'), ('razonada', 'razonada'), ('rafaga', 'rafaga')) x(slot, tipo_slot)
                       cross join lateral private.reto_pool(p_esp, p_com, x.slot) as pq(q)) c
              where not (c.q = any (v_ids))
              order by md5(p_fecha::text || '|' || p_esp::text || '|' || coalesce(p_com::text, '-') || '|fill|' || c.q::text), c.q
              limit 3 - v_n loop
      v_ids := v_ids || f.q;
      v_slots := v_slots || f.tipo_slot::text;
    end loop;
    v_n := coalesce(array_length(v_ids, 1), 0);
  end if;
  if v_n = 0 then
    return false;  -- no eligible question at all: no challenge for this segment today (never relax the regional rule)
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
