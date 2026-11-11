-- V1 RANDOM FIXED DEMO SET (owner decision 2026-11-11; workstream F14b). Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261108100300 (F14 automatic demo).
--
-- Decision: the AUTOMATIC demo set of an exam is N (config 'demo_preguntas', default 5) RANDOM-LOOKING questions, FIXED per exam: the same for every user and every call,
-- instead of the first N by posicion. Only private.demo_preguntas_efectivas changes; everything that selects the automatic set already goes through it
-- (public.demo_listo, private.exam_start_verdict, public.app_start_exam_attempt, private.app_congelar_preguntas_intento(..., true)), so their bodies stay as F14 left them.
-- 1. The explicit set of Control still wins when it is complete (private.demo_set_ok), unchanged.
-- 2. Automatic: the eligible questions are the same as in F14 (main = not a reserve, not annulled, question active); they are ranked by
--    md5(examen_id::text || pregunta_id::text) (tie: pregunta_id) and the first N are taken. No randomness at run time: the rank depends only on the two ids,
--    so the set is identical for every user, every call and every replica. Fewer than N eligible questions = no demo (NULL, fail closed), unchanged.
-- 3. STABILITY (documented): the set only changes when the ELIGIBLE set changes. A member that becomes ineligible (annulled, deactivated, turned into a reserve,
--    unlinked) drops out and the next eligible question in the md5 rank enters; when it becomes eligible again it takes its place back. A NEW question linked to
--    the exam enters the set only if its md5 rank is better than the current N-th member. Attempts already started never move (the set is frozen at start).
-- 4. The array is returned in exam order (posicion), like the explicit set, so a demo attempt shows its questions in the order of the exam.
-- No Ktor change, no contract shape change (FA44 text already says "automatically ... N"; FA48 text-only amendment in attempts.yaml).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): restores the F14 body of private.demo_preguntas_efectivas byte for byte (first N by posicion).
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function private.demo_preguntas_efectivas(p_examen uuid)
-- returns uuid[]
-- language plpgsql
-- stable
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   v_n integer := private.config_int('demo_preguntas', 5);
--   v uuid[];
-- begin
--   if p_examen is null or v_n is null or v_n < 1 then
--     return null;
--   end if;
--   -- the explicit set of Control wins when it is complete (same predicate as the original demo mode)
--   if private.demo_set_ok(p_examen) then
--     select array_agg(pe.pregunta_id order by pe.posicion, pe.pregunta_id) into v
--       from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
--      where pe.examen_id = p_examen and pe.es_demo and pe.anulada is not true and p.activa;
--     return v;
--   end if;
--   -- automatic: the first N main questions that can be served
--   select array_agg(x.pregunta_id order by x.posicion, x.pregunta_id) into v
--     from (select pe.pregunta_id, pe.posicion
--             from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
--            where pe.examen_id = p_examen and not pe.es_reserva and pe.anulada is not true and p.activa
--            order by pe.posicion, pe.pregunta_id
--            limit v_n) x;
--   if coalesce(cardinality(v), 0) < v_n then
--     return null;  -- fewer than N servable questions: no demo (fail closed)
--   end if;
--   return v;
-- end;
-- $$;
-- revoke all on function private.demo_preguntas_efectivas(uuid) from public, anon, authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'demo_aleatoria_function_not_private_only' as check_name,
--        (case when to_regprocedure('private.demo_preguntas_efectivas(uuid)') is null
--                or has_function_privilege('anon', 'private.demo_preguntas_efectivas(uuid)', 'execute')
--                or has_function_privilege('authenticated', 'private.demo_preguntas_efectivas(uuid)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'demo_aleatoria_not_md5_ranked',
--        (case when pg_get_functiondef(to_regprocedure('private.demo_preguntas_efectivas(uuid)')) like '%md5(p_examen::text || pe.pregunta_id::text)%' then 0 else 1 end)::bigint
-- union all
-- select 'demo_aleatoria_callers_missing:' || f.n,
--        (case when to_regprocedure(f.n) is not null and pg_get_functiondef(to_regprocedure(f.n)) like '%demo_preguntas_efectivas%' then 0 else 1 end)::bigint
--   from (values ('public.demo_listo(public.examenes)'), ('private.exam_start_verdict(uuid,uuid,text,timestamptz)'),
--                ('private.app_congelar_preguntas_intento(uuid,uuid,boolean)'), ('public.app_start_exam_attempt(uuid,uuid,text)')) f(n);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.demo_preguntas_efectivas(uuid)') is null or to_regprocedure('public.demo_listo(public.examenes)') is null
     or to_regprocedure('private.demo_set_ok(uuid)') is null then
    raise exception using errcode = 'P0001', message = 'V1_DEMO_ALEATORIA_PREFLIGHT', hint = 'apply 20261108100300 first';
  end if;
end
$$;

create or replace function private.demo_preguntas_efectivas(p_examen uuid)
returns uuid[]
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_n integer := private.config_int('demo_preguntas', 5);
  v uuid[];
begin
  if p_examen is null or v_n is null or v_n < 1 then
    return null;
  end if;
  -- the explicit set of Control wins when it is complete (same predicate as the original demo mode)
  if private.demo_set_ok(p_examen) then
    select array_agg(pe.pregunta_id order by pe.posicion, pe.pregunta_id) into v
      from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
     where pe.examen_id = p_examen and pe.es_demo and pe.anulada is not true and p.activa;
    return v;
  end if;
  -- automatic (F14b): N random-looking questions FIXED per exam = the first N eligible main questions by md5(exam id || question id), returned in exam order
  select array_agg(x.pregunta_id order by x.posicion, x.pregunta_id) into v
    from (select pe.pregunta_id, pe.posicion
            from public.preguntas_examen pe join public.preguntas p on p.id = pe.pregunta_id
           where pe.examen_id = p_examen and not pe.es_reserva and pe.anulada is not true and p.activa
           order by md5(p_examen::text || pe.pregunta_id::text), pe.pregunta_id
           limit v_n) x;
  if coalesce(cardinality(v), 0) < v_n then
    return null;  -- fewer than N servable questions: no demo (fail closed)
  end if;
  return v;
end;
$$;
revoke all on function private.demo_preguntas_efectivas(uuid) from public, anon, authenticated;

commit;
