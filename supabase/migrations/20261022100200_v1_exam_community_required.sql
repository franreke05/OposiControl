-- V1 (owner decision 2026-10-15): every exam MUST have a community. Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
--
-- LEGACY AUDIT (owner-authorized narrow count on MAIN, 2026-10-15): LEGACY_EXAMS_WITH_NULL_COMMUNITY = 0.
--   Nothing here reads, invents or deletes legacy rows: NO default community, NO backfill, NO delete. The migration refuses to run (PREFLIGHT,
--   below) if any exam without community exists at apply time, and only then adds NOT NULL. Aggregate-only audit script:
--   `scripts/db-exam-community-audit.sql` (re-run it, with the owner's approval, right before deploying).
--
-- WHAT IS ENFORCED
--   0. COLUMN NOT NULL (database-level, last line of defence).
--   1. INSERT without community                         -> 23514 EXAM_COMMUNITY_REQUIRED
--   2. UPDATE that sets the community to NULL            -> 23514 EXAM_COMMUNITY_REQUIRED
--   3. UPDATE that PUBLISHES (publicado_en NULL -> value) a row without community -> 23514 EXAM_COMMUNITY_REQUIRED
--   The trigger remains in front of NOT NULL so the caller gets the readable domain error EXAM_COMMUNITY_REQUIRED instead of a bare 23502.
--   The FK `examenes_comunidad_id_fkey` was ON DELETE SET NULL: deleting a community silently produced NULL communities. It becomes
--   ON DELETE RESTRICT (a community in use cannot be deleted).
--
-- LOCKING: ALTER TABLE ... ADD/DROP CONSTRAINT takes ACCESS EXCLUSIVE on `examenes` for a catalog change; the new FK is added NOT VALID
-- then validated (SHARE UPDATE EXCLUSIVE scan). lock_timeout 5s. DATA LOSS: none. RLS / GRANTS: unchanged.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate):
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_examenes_community_required on public.examenes;
-- drop function if exists private.tg_examenes_community_required();
-- alter table public.examenes alter column comunidad_id drop not null;
-- alter table public.examenes drop constraint if exists examenes_comunidad_id_fkey;
-- alter table public.examenes add constraint examenes_comunidad_id_fkey foreign key (comunidad_id) references public.comunidades_autonomas(id) on delete set null;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'trigger_missing' as check_name,
--        (case when exists (select 1 from pg_trigger where tgname = 'trg_examenes_community_required' and tgrelid = 'public.examenes'::regclass and not tgisinternal)
--              then 0 else 1 end)::bigint as failures
-- union all
-- select 'comunidad_id_nullable',
--        (case when exists (select 1 from pg_attribute where attrelid = 'public.examenes'::regclass and attname = 'comunidad_id' and attnotnull) then 0 else 1 end)::bigint
-- union all
-- select 'fk_not_restrict',
--        (case when exists (select 1 from pg_constraint where conname = 'examenes_comunidad_id_fkey' and confdeltype = 'r') then 0 else 1 end)::bigint
-- union all
-- select 'trigger_function_executable_by_clients',
--        (case when has_function_privilege('anon', 'private.tg_examenes_community_required()', 'execute')
--                or has_function_privilege('authenticated', 'private.tg_examenes_community_required()', 'execute') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

-- PREFLIGHT: no exam may exist without a community (nothing is invented or deleted to make this pass).
do $$
begin
  if exists (select 1 from public.examenes where comunidad_id is null) then
    raise exception using errcode = '23514', message = 'V1_COMMUNITY_PREFLIGHT',
      hint = 'exams without community exist; run scripts/db-exam-community-audit.sql and decide with the owner; nothing was changed';
  end if;
end
$$;

create or replace function private.tg_examenes_community_required()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.comunidad_id is null and (
       tg_op = 'INSERT'
    or old.comunidad_id is not null
    or (new.publicado_en is not null and old.publicado_en is null)
  ) then
    raise exception using errcode = '23514', message = 'EXAM_COMMUNITY_REQUIRED';
  end if;
  return new;
end;
$$;

revoke all on function private.tg_examenes_community_required() from public, anon, authenticated;

drop trigger if exists trg_examenes_community_required on public.examenes;
create trigger trg_examenes_community_required
  before insert or update on public.examenes
  for each row execute function private.tg_examenes_community_required();

alter table public.examenes drop constraint if exists examenes_comunidad_id_fkey;
alter table public.examenes
  add constraint examenes_comunidad_id_fkey foreign key (comunidad_id) references public.comunidades_autonomas(id) on delete restrict not valid;
alter table public.examenes validate constraint examenes_comunidad_id_fkey;
alter table public.examenes alter column comunidad_id set not null;

commit;
