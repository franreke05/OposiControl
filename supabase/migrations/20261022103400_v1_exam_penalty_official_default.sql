-- V1 EXAM PENALTY: the default ON of an OFFICIAL exam moves into the database (Control spec E1-V1-PENALTY-CONTRACT). Certification audit
-- CERT-EXAMS-2026-10-21 B-03 (b): the default was a best-effort Ktor PATCH after the create (a failure was only logged and the exam stayed official
-- WITHOUT penalty) and nothing happened when an exam was later switched to official. Corrective; 20261022101300 is NOT edited.
-- Owner decision 2026-10-22 (relayed by OposiControl): the penalty applies to official AND non-official exams. It stays a per-exam datum, frozen per
-- attempt at start by private.app_congelar_preguntas_intento (20261022101300, only on the first freeze); this migration does not restrict it.
-- Forward-only; local stack and PG17 gate only, NOT applied to MAIN. Requires 20261022101300 (official rules) and 20261022102300 (control RPCs).
--
-- WHAT IT DOES (for every writer: the Ktor create, a PATCH of es_oficial, SQL by hand)
--   1. BEFORE INSERT OR UPDATE OF es_oficial trigger trg_examenes_penalty_official_default (private.tg_examenes_penalty_official_default):
--      * an exam CREATED official with no penalty gets penalizacion_racional = true (same rule as the old Ktor PATCH: only over the database default);
--      * an exam SWITCHED to official gets it too, only if the statement does not touch the three penalty columns and the exam has no penalty yet:
--        an explicit setting in the same statement (a fraction, or an explicit change to 0/1) always wins;
--      * leaving official changes nothing (the penalty is not restricted to official exams). Nothing is ever refused.
--   2. trg_examenes_audit_grading_rules loses its column list (same function, same WHEN): the implicit change of (1) on an UPDATE is audited too.
-- Limitation (documented, not a bug): on INSERT an explicit `penalizacion_racional = false` cannot be told from the column default, so an official
-- exam is always created with the rational penalty; the admin turns it off afterwards in Reglas de puntuacion (as with the old Ktor default).
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
-- ROLLBACK (manual, executed verbatim by the PG17 gate)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_examenes_penalty_official_default on public.examenes;
-- drop function if exists private.tg_examenes_penalty_official_default();
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
-- select 'penalty_official_default_trigger_missing' as check_name,
--        (1 - (select count(*) from pg_trigger t where not t.tgisinternal and t.tgname = 'trg_examenes_penalty_official_default'))::bigint as failures
-- union all
-- select 'audit_trigger_still_column_limited',
--        (case when (select t.tgattr::text from pg_trigger t where t.tgname = 'trg_examenes_audit_grading_rules' and not t.tgisinternal) = '' then 0 else 1 end)::bigint
-- union all
-- select 'penalty_official_default_function_not_private',
--        (case when has_function_privilege('anon', 'private.tg_examenes_penalty_official_default()', 'execute')
--                or has_function_privilege('authenticated', 'private.tg_examenes_penalty_official_default()', 'execute') then 1 else 0 end)::bigint
-- union all
-- select 'no_official_scope_restriction_left',
--        (select count(*) from pg_constraint where conname = 'examenes_penalizacion_oficial_chk')::bigint;
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

-- ---- 1. default ON when an exam is created official or becomes official; never over an explicit setting, never a refusal
create or replace function private.tg_examenes_penalty_official_default()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    if new.es_oficial and not new.penalizacion_racional and new.penalizacion_num = 0 then
      new.penalizacion_racional := true;
    end if;
  elsif new.es_oficial and not old.es_oficial
        -- the statement did not touch the penalty and the exam has none: the switch to official decides it
        and new.penalizacion_racional = old.penalizacion_racional and new.penalizacion_num = old.penalizacion_num and new.penalizacion_den = old.penalizacion_den
        and not new.penalizacion_racional and new.penalizacion_num = 0 then
    new.penalizacion_racional := true;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_examenes_penalty_official_default() from public, anon, authenticated;
drop trigger if exists trg_examenes_penalty_official_default on public.examenes;
create trigger trg_examenes_penalty_official_default before insert or update of es_oficial on public.examenes
  for each row execute function private.tg_examenes_penalty_official_default();

-- ---- 2. the grading-rules audit also sees the change made by (1): same function, same WHEN, no column list
drop trigger if exists trg_examenes_audit_grading_rules on public.examenes;
create trigger trg_examenes_audit_grading_rules after update on public.examenes
  for each row when (old.regla_anuladas is distinct from new.regla_anuladas or old.penalizacion_num is distinct from new.penalizacion_num
                     or old.penalizacion_den is distinct from new.penalizacion_den or old.penalizacion_racional is distinct from new.penalizacion_racional)
  execute function private.tg_examenes_audit_grading_rules();

commit;
