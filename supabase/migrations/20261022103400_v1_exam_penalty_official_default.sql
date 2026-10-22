-- V1 EXAM PENALTY: the default ON of an OFFICIAL exam moves into the database (Control spec E1-V1-PENALTY-CONTRACT). Certification audit
-- CERT-EXAMS-2026-10-21 B-03 (b): the default was a best-effort Ktor PATCH after the create (a failure was only logged and the exam stayed official
-- WITHOUT penalty) and nothing happened when an exam was later switched to official. Corrective; 20261022101300 is NOT edited.
-- Owner decision 2026-10-22 (relayed by OposiControl): the penalty applies to official AND non-official exams. It stays a per-exam datum, frozen per
-- attempt at start by private.app_congelar_preguntas_intento (20261022101300, only on the first freeze); this migration does not restrict it.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101300 (official rules) and 20261022102300 (control RPCs).
--
-- WHAT IT DOES (for every writer: the Ktor create, a PATCH of es_oficial, SQL by hand)
--   1. Two BEFORE triggers on public.examenes, fired per row in name order (e < o):
--      * trg_examenes_penalty_explicit_mark: BEFORE UPDATE OF penalizacion_racional, penalizacion_num, penalizacion_den. PostgreSQL fires a column
--        trigger when the column is in the statement's SET list, EVEN with the same value: it marks the row (transaction-local setting
--        app.penalty_explicit = exam id) as "the penalty was set explicitly by this statement".
--      * trg_examenes_penalty_official_default (private.tg_examenes_penalty_official_default): BEFORE INSERT OR UPDATE (every update, so the mark of
--        this row is always read and cleared and never leaks into a later statement of the same transaction):
--          - an exam CREATED official with no penalty gets penalizacion_racional = true (same rule as the old Ktor PATCH);
--          - an exam SWITCHED to official gets it too, unless the statement named a penalty column in its SET list (any value, also an explicit
--            SET to false / 0 / 1 equal to the current one) or the exam already has a penalty: an explicit setting always wins;
--          - leaving official changes nothing (the penalty is not restricted to official exams). Nothing is ever refused.
--   2. trg_examenes_audit_grading_rules loses its column list (same function, same WHEN): the implicit change of (1) on an UPDATE is audited too.
-- LIMITATION (documented, by design): on INSERT there is no SET list. An explicit `penalizacion_racional = false` in an INSERT cannot be told from the
-- column default, so an exam INSERTED official without a penalty always gets the rational penalty; to create an official exam without penalty, insert
-- it and then turn the penalty off (Reglas de puntuacion / admin_set_exam_grading_rules), exactly as with the old Ktor default. Writers that run with
-- session_replication_role = replica (fixtures, repair scripts) skip triggers and therefore get no default.
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
-- PREFLIGHT (fail closed, nothing changed): stops only if the objects of 20261022101300 / 20261022102300 are missing. It changes no data and on
-- success RAISEs a NOTICE with aggregate counts only: official exams, official with the rational penalty, official without any penalty (backfill
-- candidates) and non-official exams with a penalty (informative).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate). It removes the two triggers and the function and gives the audit trigger back its column list.
-- DATA IS NOT REVERTED, by decision: a rational penalty the trigger switched on stays on after a rollback. Reason: an INSERT default leaves no audit row
-- and an implicit UPDATE leaves an audit row that cannot be told apart from an admin's own change with the same before/after, so reverting "from the
-- audit" would be a guess that could silently remove a penalty an admin wanted (and change the grade of every attempt started afterwards). Attempts
-- already started keep their frozen rule either way. If a rollback needs the values back, decide per exam from this read-only list:
--   select e.id, e.es_oficial, e.penalizacion_racional, e.creado_en,
--          (select count(*) from public.auditoria_log l where l.tabla_afectada = 'examenes' and l.registro_id = e.id
--             and l.accion = 'examen_reglas_puntuacion_modificadas' and (l.despues ->> 'penalizacion_racional')::boolean
--             and not coalesce((l.antes ->> 'penalizacion_racional')::boolean, false)) as encendidos_auditados
--     from public.examenes e where e.es_oficial and e.penalizacion_racional and e.creado_en >= '<fecha del despliegue>' order by e.creado_en;
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_examenes_penalty_official_default on public.examenes;
-- drop trigger if exists trg_examenes_penalty_explicit_mark on public.examenes;
-- drop function if exists private.tg_examenes_penalty_official_default();
-- drop function if exists private.tg_examenes_penalty_explicit_mark();
-- drop trigger if exists trg_examenes_audit_grading_rules on public.examenes;
-- create trigger trg_examenes_audit_grading_rules after update of regla_anuladas, penalizacion_num, penalizacion_den, penalizacion_racional on public.examenes
--   for each row when (old.regla_anuladas is distinct from new.regla_anuladas or old.penalizacion_num is distinct from new.penalizacion_num
--                      or old.penalizacion_den is distinct from new.penalizacion_den or old.penalizacion_racional is distinct from new.penalizacion_racional)
--   execute function private.tg_examenes_audit_grading_rules();
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'official_default_trigger_shape' as check_name,
--        (case when exists (select 1 from pg_trigger t where t.tgrelid = 'public.examenes'::regclass and not t.tgisinternal
--                             and t.tgname = 'trg_examenes_penalty_official_default'
--                             and t.tgfoid = to_regprocedure('private.tg_examenes_penalty_official_default()')
--                             and t.tgtype & 2 = 2 and t.tgtype & 1 = 1 and t.tgtype & 4 = 4 and t.tgtype & 16 = 16 and t.tgattr::text = '')
--              then 0 else 1 end)::bigint as failures
-- union all
-- select 'explicit_mark_trigger_shape',
--        (case when exists (select 1 from pg_trigger t where t.tgrelid = 'public.examenes'::regclass and not t.tgisinternal
--                             and t.tgname = 'trg_examenes_penalty_explicit_mark'
--                             and t.tgfoid = to_regprocedure('private.tg_examenes_penalty_explicit_mark()')
--                             and t.tgtype & 2 = 2 and t.tgtype & 1 = 1 and t.tgtype & 16 = 16 and t.tgtype & 4 = 0
--                             and (select array_agg(a.attname::text order by a.attname) from pg_attribute a
--                                   where a.attrelid = t.tgrelid and a.attnum = any (t.tgattr::int2[]))
--                                 = array['penalizacion_den', 'penalizacion_num', 'penalizacion_racional'])
--              then 0 else 1 end)::bigint
-- union all
-- select 'official_default_function_body',
--        (case when (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.tg_examenes_penalty_official_default()'))
--                   like '%app.penalty_explicit%' and
--                   (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.tg_examenes_penalty_official_default()'))
--                   like '%new.penalizacion_racional := true%' and
--                   (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.tg_examenes_penalty_official_default()'))
--                   not like '%raise%' and
--                   (select p.prosrc from pg_proc p where p.oid = to_regprocedure('private.tg_examenes_penalty_explicit_mark()'))
--                   like '%app.penalty_explicit%'
--              then 0 else 1 end)::bigint
-- union all
-- select 'penalty_functions_not_private:' || f.n,
--        (case when to_regprocedure(f.n) is null then 1 when has_function_privilege('anon', f.n, 'execute') or has_function_privilege('authenticated', f.n, 'execute') then 1 else 0 end)::bigint
--   from (values ('private.tg_examenes_penalty_official_default()'), ('private.tg_examenes_penalty_explicit_mark()')) f(n)
-- union all
-- select 'audit_trigger_still_column_limited',
--        (case when (select t.tgattr::text from pg_trigger t where t.tgname = 'trg_examenes_audit_grading_rules' and not t.tgisinternal) = '' then 0 else 1 end)::bigint
-- union all
-- select 'no_check_ties_penalty_to_es_oficial',
--        (select count(*) from pg_constraint c where c.conrelid = 'public.examenes'::regclass and c.contype = 'c'
--           and pg_get_constraintdef(c.oid) like '%es_oficial%')::bigint
-- union all
-- select 'no_code_refuses_penalty_on_non_official',
--        (select count(*) from pg_proc p where p.pronamespace in ('public'::regnamespace, 'private'::regnamespace)
--           and p.prosrc like '%GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM%')::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT (fail closed: an exception aborts the whole transaction, nothing is changed)
do $$
declare
  v_oficiales bigint;
  v_oficiales_rac bigint;
  v_candidatos bigint;
  v_no_oficiales_pen bigint;
begin
  if to_regprocedure('private.tg_examenes_audit_grading_rules()') is null
     or to_regprocedure('public.admin_set_exam_grading_rules(uuid,uuid,text,integer,integer,boolean,text)') is null
     or not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'examenes' and column_name = 'penalizacion_racional') then
    raise exception using errcode = 'P0001', message = 'V1_EXAM_PENALTY_OFFICIAL_DEFAULT_PREFLIGHT', hint = 'apply 20261022101300 and 20261022102300 first';
  end if;
  select count(*) filter (where es_oficial), count(*) filter (where es_oficial and penalizacion_racional),
         count(*) filter (where es_oficial and not penalizacion_racional and penalizacion_num = 0),
         count(*) filter (where not es_oficial and (penalizacion_racional or penalizacion_num > 0))
    into v_oficiales, v_oficiales_rac, v_candidatos, v_no_oficiales_pen
    from public.examenes;
  raise notice 'V1_EXAM_PENALTY_OFFICIAL_DEFAULT: % official exam(s), % with the rational penalty, % official without any penalty (backfill candidates, NOT changed: owner decision), % non-official with a penalty',
    v_oficiales, v_oficiales_rac, v_candidatos, v_no_oficiales_pen;
end
$$;

-- ---- 1a. mark a row whose penalty columns are in the statement's SET list (fires even when the value does not change)
create or replace function private.tg_examenes_penalty_explicit_mark()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  perform set_config('app.penalty_explicit', new.id::text, true);
  return new;
end;
$$;
revoke all on function private.tg_examenes_penalty_explicit_mark() from public, anon, authenticated;
drop trigger if exists trg_examenes_penalty_explicit_mark on public.examenes;
create trigger trg_examenes_penalty_explicit_mark before update of penalizacion_racional, penalizacion_num, penalizacion_den on public.examenes
  for each row execute function private.tg_examenes_penalty_explicit_mark();

-- ---- 1b. default ON when an exam is created official or becomes official; never over an explicit setting, never a refusal.
-- Fires on EVERY update (no column list) so the mark of this row is always read and cleared in the same row event.
create or replace function private.tg_examenes_penalty_official_default()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_explicit boolean;
begin
  if tg_op = 'INSERT' then
    if new.es_oficial and not new.penalizacion_racional and new.penalizacion_num = 0 then
      new.penalizacion_racional := true;
    end if;
    return new;
  end if;
  v_explicit := coalesce(current_setting('app.penalty_explicit', true), '') = new.id::text;
  if v_explicit then
    perform set_config('app.penalty_explicit', '', true);
  end if;
  if new.es_oficial and not old.es_oficial and not v_explicit
     and not old.penalizacion_racional and old.penalizacion_num = 0 then
    new.penalizacion_racional := true;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_examenes_penalty_official_default() from public, anon, authenticated;
drop trigger if exists trg_examenes_penalty_official_default on public.examenes;
create trigger trg_examenes_penalty_official_default before insert or update on public.examenes
  for each row execute function private.tg_examenes_penalty_official_default();

-- ---- 2. the grading-rules audit also sees the change made by (1): same function, same WHEN, no column list
drop trigger if exists trg_examenes_audit_grading_rules on public.examenes;
create trigger trg_examenes_audit_grading_rules after update on public.examenes
  for each row when (old.regla_anuladas is distinct from new.regla_anuladas or old.penalizacion_num is distinct from new.penalizacion_num
                     or old.penalizacion_den is distinct from new.penalizacion_den or old.penalizacion_racional is distinct from new.penalizacion_racional)
  execute function private.tg_examenes_audit_grading_rules();

commit;
