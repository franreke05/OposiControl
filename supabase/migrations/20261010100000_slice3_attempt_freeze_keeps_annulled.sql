-- =============================================================================
-- SLICE 3 -- an attempt shows every active question of the exam, annulled ones included (flagged, never scored)
-- =============================================================================
-- NEW forward-only migration: no earlier file is edited. One function body changes; no table, column, constraint,
-- policy, grant or row changes.
--
-- OWNER DECISION (2026-10-10)
--   "Tiene que mostrarse todas las preguntas en el examen y avisar de que son anuladas."
--   Observed in production: an official exam with 100 linked questions, 5 of them annulled by the tribunal, started an
--   attempt with 95 questions while the catalogue announces 100.
--
-- WHAT THIS MIGRATION DOES
--   private.app_congelar_preguntas_intento(uuid, uuid): the frozen set of a NEW attempt is every ACTIVE question linked
--   to the exam, annulled or not, numbered 1..n by (posicion, pregunta_id). Before: annulled questions were left out.
--   The function still RETURNS the number of EVALUABLE questions (active and not annulled), so the caller
--   (public.app_start_exam_attempt) keeps storing that number in intentos_examen.total_preguntas and its
--   "0 or more than 200" guard keeps its meaning. An annulled question stays out of every total, as before.
--   UNCHANGED: everything else of the runtime already treats an annulled question inside an attempt (one annulled after
--   the start): the question is served with `anulada = true`, answering it stores nothing and returns ANNULLED, it is
--   not counted as answered, it is not evaluable at finish and its final state is ANNULLED. Archived (inactive)
--   questions are still left out. public.app_start_exam_attempt is not redefined (EXAM_EMPTY and EXAM_TOO_LARGE still
--   count evaluable questions: an exam with only annulled questions cannot be started).
--
-- PREFLIGHT (fail closed, P0001 ATTEMPT_FREEZE_ANNULLED_PREFLIGHT, nothing changed)
--   The function exists with this signature and the columns it reads exist.
--
-- HISTORICAL DATA
--   None read, written or deleted. Attempts already started keep the set they were frozen with.
--
-- FORWARD PLAN
--   Order-independent from Ktor: the deployed backend already serves and scores annulled questions inside an attempt.
--
-- RLS AND GRANTS
--   None changed. The function stays without EXECUTE for public, anon and authenticated.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid)
-- returns integer
-- language plpgsql
-- set search_path = ''
-- as $fn$
-- declare
--   v_n integer;
-- begin
--   select count(*) into v_n from public.preguntas_intento pi where pi.intento_id = p_intento_id;
--   if v_n > 0 then
--     return v_n;
--   end if;
--   insert into public.preguntas_intento (intento_id, pregunta_id, posicion)
--   select p_intento_id, q.pregunta_id, (row_number() over (order by q.posicion, q.pregunta_id))::integer
--   from (
--     select pe.pregunta_id, pe.posicion
--     from public.preguntas_examen pe
--     join public.preguntas p on p.id = pe.pregunta_id
--     where pe.examen_id = p_examen_id and pe.anulada is not true and p.activa
--   ) q;
--   get diagnostics v_n = row_count;
--   return v_n;
-- end;
-- $fn$;
-- revoke all on function private.app_congelar_preguntas_intento(uuid, uuid) from public, anon, authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'attempt_freeze_function_missing' as check_name,
--        (case when to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)') is null then 1 else 0 end)::bigint as failures,
--        null as info
-- union all
-- select 'attempt_freeze_still_drops_annulled',
--        (select count(*) from pg_proc p
--          where p.oid = to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)')
--            and p.prosrc !~ 'ATTEMPT_FREEZE_KEEPS_ANNULLED')::bigint, null
-- union all
-- select 'attempt_freeze_executable_by_clients',
--        (case when has_function_privilege('anon', to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)'), 'EXECUTE')
--                or has_function_privilege('authenticated', to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)'), 'EXECUTE')
--              then 1 else 0 end)::bigint, null;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)') is null then
    raise exception 'ATTEMPT_FREEZE_ANNULLED_PREFLIGHT' using detail = 'private.app_congelar_preguntas_intento(uuid,uuid) missing';
  end if;
  if (select count(*) from information_schema.columns
       where table_schema = 'public'
         and (table_name, column_name) in (('preguntas_examen', 'anulada'), ('preguntas_examen', 'posicion'),
                                           ('preguntas', 'activa'), ('preguntas_intento', 'posicion'))) <> 4 then
    raise exception 'ATTEMPT_FREEZE_ANNULLED_PREFLIGHT' using detail = 'a column read by the freeze helper is missing';
  end if;
end;
$$;

create or replace function private.app_congelar_preguntas_intento(p_intento_id uuid, p_examen_id uuid)
returns integer
language plpgsql
set search_path = ''
as $$
declare
  v_n integer;
begin
  -- ATTEMPT_FREEZE_KEEPS_ANNULLED: every active question is frozen; the result is the number of EVALUABLE ones.
  if not exists (select 1 from public.preguntas_intento pi where pi.intento_id = p_intento_id) then
    insert into public.preguntas_intento (intento_id, pregunta_id, posicion)
    select p_intento_id, q.pregunta_id, (row_number() over (order by q.posicion, q.pregunta_id))::integer
    from (
      select pe.pregunta_id, pe.posicion
      from public.preguntas_examen pe
      join public.preguntas p on p.id = pe.pregunta_id
      where pe.examen_id = p_examen_id and p.activa
    ) q;
  end if;
  select count(*) into v_n
  from public.preguntas_intento pi
  where pi.intento_id = p_intento_id
    and not exists (select 1 from public.preguntas_examen pe
                    where pe.examen_id = p_examen_id and pe.pregunta_id = pi.pregunta_id and pe.anulada);
  return v_n;
end;
$$;

revoke all on function private.app_congelar_preguntas_intento(uuid, uuid) from public, anon, authenticated;

do $$
begin
  if (select p.prosrc from pg_proc p
       where p.oid = to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid)')) !~ 'ATTEMPT_FREEZE_KEEPS_ANNULLED' then
    raise exception 'ATTEMPT_FREEZE_ANNULLED_POSTCHECK' using detail = 'the freeze helper was not replaced';
  end if;
end;
$$;

commit;
