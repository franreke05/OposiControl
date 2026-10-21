-- V1 EXAM PENALTY SCOPE (owner decision 2026-10-17, FA36): the penalty applies ONLY to OFFICIAL exams, is a per-exam datum and is frozen per attempt.
-- Certification audit CERT-EXAMS-2026-10-21 B-03: 20261022101300 / 20261022102300 accepted the penalty on ANY exam, and the default ON for an official
-- exam was a best-effort Ktor PATCH after the create (never on a later switch to official). Corrective; 20261022101300 and 20261022102300 are NOT edited.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101300 (official rules) and 20261022102300 (control RPCs).
--
-- WHAT IT DOES (for every writer: the admin RPC, a direct PostgREST PATCH -- the DEBT-159 fallback of AdminControlGrading --, the Ktor create, SQL by hand)
--   1. CHECK examenes_penalizacion_oficial_chk: es_oficial OR (NOT penalizacion_racional AND penalizacion_num = 0). "Penalty" = the rational penalty
--      OR a fixed fraction num/den > 0: neither may exist on a non-official exam. A CHECK is not skipped by session_replication_role = replica.
--   2. BEFORE INSERT OR UPDATE trigger trg_examenes_penalty_scope (private.tg_examenes_penalty_scope):
--      * an exam CREATED official, or SWITCHED to official, gets penalizacion_racional = true (the default ON of the Control spec), unless the same
--        statement sets a penalty itself (a fixed fraction, or any change of the three penalty columns, is respected as the explicit decision);
--      * an exam switched official -> NON-official loses its penalty (rational off, fraction 0/1), unless the same statement sets a penalty
--        explicitly, which is then refused. The audit trigger records the change (see 3). Attempts already started keep their FROZEN rule (see 4);
--      * a penalty on a non-official exam is refused with GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM (SQLSTATE 23514), before the CHECK fires.
--   3. trg_examenes_audit_grading_rules loses its column list (same function, same WHEN): an implicit change made by (2) is audited as well.
--   4. BEFORE INSERT OR UPDATE OF penalizacion_* trigger on intentos_examen: the rule FROZEN at start is `penalty AND exam.es_oficial` (a
--      non-official exam freezes no penalty, whoever writes the snapshot). Only the FIRST freeze is touched; a frozen attempt never changes later.
--   5. admin_set_exam_grading_rules: GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM (P0001) when a penalty is asked for a non-official exam.
--
-- NOT DONE HERE (OWNER DECISION): existing official exams are NOT backfilled. On MAIN every exam has penalizacion_racional = false after
-- 20261022101300 (column default). Whether MAIN's existing official exams get the rational penalty is the owner's call (Arturo's warning: changing
-- the rule after launch makes grades non-comparable). If the owner says yes, run AFTER this migration, counts first (read-only):
--   select count(*) filter (where es_oficial) as oficiales,
--          count(*) filter (where es_oficial and not penalizacion_racional and penalizacion_num = 0) as candidatos,
--          count(*) filter (where es_oficial and not penalizacion_racional and penalizacion_num = 0 and publicado_en is not null) as candidatos_publicados,
--          count(*) filter (where es_oficial and penalizacion_num > 0) as con_fraccion_fija
--     from public.examenes;
-- and then, in ONE transaction with the owner's admin id as the audit actor (published exams are re-validated by trg_examenes_official_rules at commit):
--   begin;
--   select set_config('app.audit_actor', '<owner admin uuid>', true), set_config('app.audit_reason', 'backfill penalizacion racional (decision propietario <fecha>)', true);
--   update public.examenes set penalizacion_racional = true where es_oficial and not penalizacion_racional and penalizacion_num = 0;
--   commit;
-- Attempts already started keep the rule they froze; only attempts started afterwards are penalised.
--
-- PREFLIGHT (fail closed, nothing changed): stops if any NON-official exam carries a penalty (it would violate the new CHECK); the error DETAIL gives
-- only aggregate counts (total / rational / fixed fraction), never rows. The same read-only count, to run on MAIN before the deploy:
--   select count(*) as no_oficiales_con_penalizacion, count(*) filter (where penalizacion_racional) as racional, count(*) filter (where penalizacion_num > 0) as fraccion
--     from public.examenes where not es_oficial and (penalizacion_racional or penalizacion_num > 0);
-- (Today MAIN has no such columns: they arrive with 20261022101300 in the same deploy, default false, so the expected count is 0.)
-- On success it RAISEs a NOTICE with: official exams, official with the rational penalty, official without any penalty (backfill candidates).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_intentos_examen_penalty_official on public.intentos_examen;
-- drop function if exists private.tg_intentos_examen_penalty_official();
-- drop trigger if exists trg_examenes_penalty_scope on public.examenes;
-- drop function if exists private.tg_examenes_penalty_scope();
-- alter table public.examenes drop constraint if exists examenes_penalizacion_oficial_chk;
-- drop trigger if exists trg_examenes_audit_grading_rules on public.examenes;
-- create trigger trg_examenes_audit_grading_rules after update of regla_anuladas, penalizacion_num, penalizacion_den, penalizacion_racional on public.examenes
--   for each row when (old.regla_anuladas is distinct from new.regla_anuladas or old.penalizacion_num is distinct from new.penalizacion_num
--                      or old.penalizacion_den is distinct from new.penalizacion_den or old.penalizacion_racional is distinct from new.penalizacion_racional)
--   execute function private.tg_examenes_audit_grading_rules();
-- create or replace function public.admin_set_exam_grading_rules(
--   p_actor uuid, p_examen uuid, p_regla_anuladas text, p_penalizacion_num integer, p_penalizacion_den integer, p_penalizacion_racional boolean, p_motivo text default null)
-- returns jsonb
-- language plpgsql
-- volatile
-- security definer
-- set search_path = ''
-- as $$
-- declare
--   e public.examenes%rowtype;
--   v_motivo text;
--   v_changed boolean;
-- begin
--   perform private.require_admin_actor(p_actor);
--   if p_examen is null or p_penalizacion_num is null or p_penalizacion_den is null or p_penalizacion_racional is null then
--     raise exception using errcode = 'P0001', message = 'GRADING_INPUT_INVALID';
--   end if;
--   if p_regla_anuladas is not null and p_regla_anuladas not in ('REEMPLAZO_POR_RESERVA', 'NO_PUNTUA') then
--     raise exception using errcode = 'P0001', message = 'GRADING_RULE_INVALID';
--   end if;
--   if p_penalizacion_racional and p_penalizacion_num <> 0 then
--     raise exception using errcode = 'P0001', message = 'GRADING_PENALTY_CONFLICT';
--   end if;
--   if p_penalizacion_den < 1 or p_penalizacion_den > 100 or p_penalizacion_num < 0 or p_penalizacion_num > p_penalizacion_den then
--     raise exception using errcode = 'P0001', message = 'GRADING_PENALTY_INVALID';
--   end if;
--   select * into e from public.examenes x where x.id = p_examen for no key update;
--   if not found then
--     raise exception using errcode = 'P0001', message = 'EXAM_NOT_FOUND';
--   end if;
--   -- a published exam changes rules only with a reason (it affects the attempts started afterwards)
--   v_motivo := private.admin_reason(p_motivo, case when e.publicado_en is not null then 3 else 0 end, 300);
--   v_changed := e.regla_anuladas is distinct from p_regla_anuladas or e.penalizacion_num <> p_penalizacion_num or e.penalizacion_den <> p_penalizacion_den or e.penalizacion_racional <> p_penalizacion_racional;
--   if v_changed then
--     perform set_config('app.audit_actor', p_actor::text, true);
--     perform set_config('app.audit_reason', coalesce(v_motivo, ''), true);
--     update public.examenes x
--        set regla_anuladas = p_regla_anuladas, penalizacion_num = p_penalizacion_num::smallint, penalizacion_den = p_penalizacion_den::smallint, penalizacion_racional = p_penalizacion_racional
--      where x.id = p_examen;
--     perform set_config('app.audit_actor', '', true);
--     perform set_config('app.audit_reason', '', true);
--   end if;
--   return jsonb_build_object('changed', v_changed, 'reglaEfectiva', private.official_rule_effective(p_examen), 'problema', private.official_rules_problem(p_examen),
--                             'avisos', to_jsonb(private.official_rules_warnings(p_examen)));
-- end;
-- $$;
-- revoke all on function public.admin_set_exam_grading_rules(uuid, uuid, text, integer, integer, boolean, text) from public, anon, authenticated;
-- grant execute on function public.admin_set_exam_grading_rules(uuid, uuid, text, integer, integer, boolean, text) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'penalty_official_check_missing' as check_name,
--        (case when exists (select 1 from pg_constraint where conname = 'examenes_penalizacion_oficial_chk' and conrelid = 'public.examenes'::regclass and convalidated) then 0 else 1 end)::bigint as failures
-- union all
-- select 'non_official_exams_with_penalty', (select count(*) from public.examenes where not es_oficial and (penalizacion_racional or penalizacion_num > 0))::bigint
-- union all
-- select 'penalty_scope_triggers_missing',
--        (2 - (select count(*) from pg_trigger t where not t.tgisinternal and t.tgname in ('trg_examenes_penalty_scope', 'trg_intentos_examen_penalty_official')))::bigint
-- union all
-- select 'audit_trigger_still_column_limited',
--        (case when (select t.tgattr::text from pg_trigger t where t.tgname = 'trg_examenes_audit_grading_rules' and not t.tgisinternal) = '' then 0 else 1 end)::bigint
-- union all
-- select 'penalty_scope_function_not_private:' || f.n,
--        (case when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint
--   from (values ('private.tg_examenes_penalty_scope()'), ('private.tg_intentos_examen_penalty_official()')) f(n)
-- union all
-- select 'grading_rpc_not_service_role_only',
--        (case when has_function_privilege('anon', 'public.admin_set_exam_grading_rules(uuid,uuid,text,integer,integer,boolean,text)', 'execute')
--                or has_function_privilege('authenticated', 'public.admin_set_exam_grading_rules(uuid,uuid,text,integer,integer,boolean,text)', 'execute')
--                or not has_function_privilege('service_role', 'public.admin_set_exam_grading_rules(uuid,uuid,text,integer,integer,boolean,text)', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'grading_rpc_without_official_scope',
--        (case when (select p.prosrc from pg_proc p where p.oid = 'public.admin_set_exam_grading_rules(uuid,uuid,text,integer,integer,boolean,text)'::regprocedure)
--                   like '%GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM%' then 0 else 1 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed: an exception aborts the whole transaction, nothing is changed)
do $$
declare
  v_bad bigint;
  v_bad_rac bigint;
  v_bad_frac bigint;
  v_oficiales bigint;
  v_oficiales_rac bigint;
  v_candidatos bigint;
begin
  if to_regprocedure('public.admin_set_exam_grading_rules(uuid,uuid,text,integer,integer,boolean,text)') is null
     or to_regprocedure('private.tg_examenes_audit_grading_rules()') is null
     or to_regprocedure('private.app_congelar_preguntas_intento(uuid,uuid,boolean)') is null
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'examenes' and column_name = 'penalizacion_racional')
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'intentos_examen' and column_name = 'penalizacion_racional') then
    raise exception using errcode = 'P0001', message = 'V1_EXAM_PENALTY_SCOPE_PREFLIGHT', hint = 'apply 20261022101300 and 20261022102300 first';
  end if;
  select count(*), count(*) filter (where penalizacion_racional), count(*) filter (where penalizacion_num > 0)
    into v_bad, v_bad_rac, v_bad_frac
    from public.examenes where not es_oficial and (penalizacion_racional or penalizacion_num > 0);
  if v_bad > 0 then
    raise exception using errcode = 'P0001', message = 'V1_EXAM_PENALTY_SCOPE_PREFLIGHT',
      detail = format('%s non-official exam(s) carry a penalty (%s rational, %s fixed fraction)', v_bad, v_bad_rac, v_bad_frac),
      hint = 'nothing was changed: the owner decides whether those exams become official or lose the penalty, then apply again';
  end if;
  select count(*) filter (where es_oficial), count(*) filter (where es_oficial and penalizacion_racional),
         count(*) filter (where es_oficial and not penalizacion_racional and penalizacion_num = 0)
    into v_oficiales, v_oficiales_rac, v_candidatos
    from public.examenes;
  raise notice 'V1_EXAM_PENALTY_SCOPE: % official exam(s), % with the rational penalty, % official without any penalty (backfill candidates, NOT changed: owner decision)',
    v_oficiales, v_oficiales_rac, v_candidatos;
end
$$;

-- ---- 1. the rule itself, for every writer
alter table public.examenes drop constraint if exists examenes_penalizacion_oficial_chk;
alter table public.examenes add constraint examenes_penalizacion_oficial_chk
  check (es_oficial or (not penalizacion_racional and penalizacion_num = 0));

-- ---- 2. default ON when an exam becomes official, cleared when it stops being official, clear error code for a penalty on a non-official exam
create or replace function private.tg_examenes_penalty_scope()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_same_penalty boolean;
begin
  if tg_op = 'INSERT' then
    if new.es_oficial and not new.penalizacion_racional and new.penalizacion_num = 0 then
      new.penalizacion_racional := true;
    end if;
  else
    -- the statement did not touch the penalty: the change of officiality decides it
    v_same_penalty := new.penalizacion_racional = old.penalizacion_racional and new.penalizacion_num = old.penalizacion_num
                      and new.penalizacion_den = old.penalizacion_den;
    if new.es_oficial and not old.es_oficial and v_same_penalty and not new.penalizacion_racional and new.penalizacion_num = 0 then
      new.penalizacion_racional := true;
    elsif old.es_oficial and not new.es_oficial and v_same_penalty then
      new.penalizacion_racional := false;
      new.penalizacion_num := 0;
      new.penalizacion_den := 1;
    end if;
  end if;
  if not new.es_oficial and (new.penalizacion_racional or new.penalizacion_num > 0) then
    raise exception using errcode = '23514', message = 'GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM',
      hint = 'the penalty (rational or a fixed fraction) applies only to official exams';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_examenes_penalty_scope() from public, anon, authenticated;
drop trigger if exists trg_examenes_penalty_scope on public.examenes;
create trigger trg_examenes_penalty_scope before insert or update on public.examenes
  for each row execute function private.tg_examenes_penalty_scope();

-- ---- 3. the grading-rules audit also sees a change made by (2): same function, same WHEN, no column list
drop trigger if exists trg_examenes_audit_grading_rules on public.examenes;
create trigger trg_examenes_audit_grading_rules after update on public.examenes
  for each row when (old.regla_anuladas is distinct from new.regla_anuladas or old.penalizacion_num is distinct from new.penalizacion_num
                     or old.penalizacion_den is distinct from new.penalizacion_den or old.penalizacion_racional is distinct from new.penalizacion_racional)
  execute function private.tg_examenes_audit_grading_rules();

-- ---- 4. the FROZEN rule of an attempt is `penalty AND es_oficial`, at the first freeze only (an attempt already frozen never changes)
create or replace function private.tg_intentos_examen_penalty_official()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (tg_op = 'INSERT' or (old.penalizacion_racional is null and old.penalizacion_num is null))
     and (coalesce(new.penalizacion_racional, false) or coalesce(new.penalizacion_num, 0) > 0)
     and not coalesce((select e.es_oficial from public.examenes e where e.id = new.examen_id), false) then
    new.penalizacion_racional := case when new.penalizacion_racional is not null then false end;
    new.penalizacion_num := case when new.penalizacion_num is not null then 0 end;
    new.penalizacion_den := case when new.penalizacion_den is not null then 1 end;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_intentos_examen_penalty_official() from public, anon, authenticated;
drop trigger if exists trg_intentos_examen_penalty_official on public.intentos_examen;
create trigger trg_intentos_examen_penalty_official before insert or update of penalizacion_racional, penalizacion_num, penalizacion_den on public.intentos_examen
  for each row execute function private.tg_intentos_examen_penalty_official();

-- ---- 5. the admin RPC refuses a penalty on a non-official exam with its own code (body of 20261022102300 + the check)
create or replace function public.admin_set_exam_grading_rules(
  p_actor uuid, p_examen uuid, p_regla_anuladas text, p_penalizacion_num integer, p_penalizacion_den integer, p_penalizacion_racional boolean, p_motivo text default null)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  e public.examenes%rowtype;
  v_motivo text;
  v_changed boolean;
begin
  perform private.require_admin_actor(p_actor);
  if p_examen is null or p_penalizacion_num is null or p_penalizacion_den is null or p_penalizacion_racional is null then
    raise exception using errcode = 'P0001', message = 'GRADING_INPUT_INVALID';
  end if;
  if p_regla_anuladas is not null and p_regla_anuladas not in ('REEMPLAZO_POR_RESERVA', 'NO_PUNTUA') then
    raise exception using errcode = 'P0001', message = 'GRADING_RULE_INVALID';
  end if;
  if p_penalizacion_racional and p_penalizacion_num <> 0 then
    raise exception using errcode = 'P0001', message = 'GRADING_PENALTY_CONFLICT';
  end if;
  if p_penalizacion_den < 1 or p_penalizacion_den > 100 or p_penalizacion_num < 0 or p_penalizacion_num > p_penalizacion_den then
    raise exception using errcode = 'P0001', message = 'GRADING_PENALTY_INVALID';
  end if;
  select * into e from public.examenes x where x.id = p_examen for no key update;
  if not found then
    raise exception using errcode = 'P0001', message = 'EXAM_NOT_FOUND';
  end if;
  -- PENALTY_OFFICIAL_SCOPE (owner 2026-10-17): a penalty (rational or a fixed fraction) exists only on an OFFICIAL exam. Clear error code here;
  -- the CHECK examenes_penalizacion_oficial_chk and the trigger trg_examenes_penalty_scope enforce the same rule for every other writer.
  if (p_penalizacion_racional or p_penalizacion_num > 0) and not e.es_oficial then
    raise exception using errcode = 'P0001', message = 'GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM';
  end if;
  -- a published exam changes rules only with a reason (it affects the attempts started afterwards)
  v_motivo := private.admin_reason(p_motivo, case when e.publicado_en is not null then 3 else 0 end, 300);
  v_changed := e.regla_anuladas is distinct from p_regla_anuladas or e.penalizacion_num <> p_penalizacion_num or e.penalizacion_den <> p_penalizacion_den or e.penalizacion_racional <> p_penalizacion_racional;
  if v_changed then
    perform set_config('app.audit_actor', p_actor::text, true);
    perform set_config('app.audit_reason', coalesce(v_motivo, ''), true);
    update public.examenes x
       set regla_anuladas = p_regla_anuladas, penalizacion_num = p_penalizacion_num::smallint, penalizacion_den = p_penalizacion_den::smallint, penalizacion_racional = p_penalizacion_racional
     where x.id = p_examen;
    perform set_config('app.audit_actor', '', true);
    perform set_config('app.audit_reason', '', true);
  end if;
  return jsonb_build_object('changed', v_changed, 'reglaEfectiva', private.official_rule_effective(p_examen), 'problema', private.official_rules_problem(p_examen),
                            'avisos', to_jsonb(private.official_rules_warnings(p_examen)));
end;
$$;
revoke all on function public.admin_set_exam_grading_rules(uuid, uuid, text, integer, integer, boolean, text) from public, anon, authenticated;
grant execute on function public.admin_set_exam_grading_rules(uuid, uuid, text, integer, integer, boolean, text) to service_role;

commit;
