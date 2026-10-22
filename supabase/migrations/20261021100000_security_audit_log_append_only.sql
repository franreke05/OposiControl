-- Audit log append-only (E2 security pass, 2026-10-15). Forward-only; adds ONE function and TWO triggers to
-- public.auditoria_log. Touches no existing row, column, index, policy, grant or foreign key.
--
-- Until now `auditoria_log` was append-only by convention only: `service_role` (the backend, and anyone holding that key)
-- keeps UPDATE, DELETE and TRUNCATE on the table (supabase/baseline/main_shape_subset.sql), so a leaked key or a bug could
-- rewrite or erase who did what. Nothing in the backend or in any migration updates or deletes audit rows (grep of
-- backend/ and supabase/migrations/ on 2026-10-15: every writer is an INSERT, including the RPCs of 20260930100300 and the
-- partial unique index of 20260930101730, which only rejects a duplicate INSERT).
--
-- Rule enforced here, for EVERY role (service_role, the table owner, the RPCs):
--   INSERT    allowed, unchanged.
--   UPDATE    rejected (P0001 AUDIT_LOG_APPEND_ONLY) with exactly ONE exception: the action of the existing foreign key
--             `auditoria_log_usuario_id_fkey ... references auth.users(id) on delete set null`. When an Auth user is
--             deleted (account deletion), Postgres rewrites `usuario_id` to null on that user's rows. That transition is
--             allowed only if (a) the old actor no longer exists in auth.users, (b) the new actor is null, and (c) every
--             other column is unchanged. A manual `set usuario_id = null` while the user still exists is rejected: it
--             would erase the actor of a row without the account being gone.
--   DELETE    rejected (row-level BEFORE trigger).
--   TRUNCATE  rejected (statement-level BEFORE trigger; row triggers do not fire on TRUNCATE).
--
-- The foreign key is kept AS IS on purpose (the task of this pass is append-only, not the retention policy): deleting an
-- Auth user still anonymises its audit rows instead of failing. Changing that is a product/legal decision (GDPR erasure
-- vs. accountability) and is registered in docs/registry/RISKS.md.
--
-- Limits (documented, not fixable from SQL): a superuser can `alter table ... disable trigger` or run with
-- `session_replication_role = replica`; Supabase does not give that to service_role. Retention/purging of old audit rows
-- now needs a deliberate migration that drops or replaces these triggers.
--
-- The function is SECURITY DEFINER (owner) with an empty search_path because it reads auth.users, on which service_role
-- has no privilege in MAIN; it is not callable as a function by clients (trigger functions cannot be called directly) and
-- EXECUTE is revoked from public anyway.
--
-- Idempotent: `create or replace` and `drop trigger if exists` + `create trigger`.
--
-- Rollback (manual; restores the previous, mutable behaviour):
--   drop trigger if exists auditoria_log_append_only_row on public.auditoria_log;
--   drop trigger if exists auditoria_log_append_only_truncate on public.auditoria_log;
--   drop function if exists public.auditoria_log_append_only();

begin;

set local lock_timeout = '5s';

do $$
begin
    if to_regclass('public.auditoria_log') is null then
        raise exception 'AUDIT_LOG_APPEND_ONLY_PREFLIGHT: public.auditoria_log does not exist';
    end if;
end
$$;

create or replace function public.auditoria_log_append_only()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if tg_op = 'UPDATE'
        and old.usuario_id is not null
        and new.usuario_id is null
        and (to_jsonb(new) - 'usuario_id') = (to_jsonb(old) - 'usuario_id')
        and not exists (select 1 from auth.users u where u.id = old.usuario_id)
    then
        -- ON DELETE SET NULL of auditoria_log_usuario_id_fkey: the Auth user is gone, the row stays.
        return new;
    end if;
    raise exception 'AUDIT_LOG_APPEND_ONLY: % on public.auditoria_log is not allowed', tg_op
        using errcode = 'P0001';
end
$$;

revoke all on function public.auditoria_log_append_only() from public;
-- E7 (integration): Supabase default privileges grant EXECUTE to anon/authenticated on new public functions; the gate matrix requires none on SECURITY DEFINER.
revoke all on function public.auditoria_log_append_only() from anon, authenticated;

comment on function public.auditoria_log_append_only() is
    'Hace public.auditoria_log de solo anadir: rechaza UPDATE, DELETE y TRUNCATE. Unica excepcion: la FK del actor (ON DELETE SET NULL) al borrar el usuario de Auth.';

drop trigger if exists auditoria_log_append_only_row on public.auditoria_log;
create trigger auditoria_log_append_only_row
    before update or delete on public.auditoria_log
    for each row execute function public.auditoria_log_append_only();

drop trigger if exists auditoria_log_append_only_truncate on public.auditoria_log;
create trigger auditoria_log_append_only_truncate
    before truncate on public.auditoria_log
    for each statement execute function public.auditoria_log_append_only();

commit;

-- E7 (integration): the prose "Rollback (manual)" above, in the block form the PG17 gate executes. COMMENT-ONLY addition, no SQL change.
-- ROLLBACK-BEGIN
-- drop trigger if exists auditoria_log_append_only_row on public.auditoria_log;
-- drop trigger if exists auditoria_log_append_only_truncate on public.auditoria_log;
-- drop function if exists public.auditoria_log_append_only();
-- ROLLBACK-END

-- VERIFY-BEGIN
-- select 'row_trigger_missing_or_disabled', (count(*) <> 1)::int from pg_trigger t where t.tgrelid = 'public.auditoria_log'::regclass and t.tgname = 'auditoria_log_append_only_row' and not t.tgisinternal and t.tgenabled = 'O' and t.tgfoid = 'public.auditoria_log_append_only()'::regprocedure
-- union all select 'truncate_trigger_missing_or_disabled', (count(*) <> 1)::int from pg_trigger t where t.tgrelid = 'public.auditoria_log'::regclass and t.tgname = 'auditoria_log_append_only_truncate' and not t.tgisinternal and t.tgenabled = 'O' and t.tgfoid = 'public.auditoria_log_append_only()'::regprocedure
-- union all select 'function_not_security_definer_with_empty_search_path', (count(*) <> 1)::int from pg_proc p where p.oid = 'public.auditoria_log_append_only()'::regprocedure and p.prosecdef and p.proconfig = array['search_path=""']
-- union all select 'actor_fk_changed', (count(*) <> 1)::int from pg_constraint c where c.conrelid = 'public.auditoria_log'::regclass and c.conname = 'auditoria_log_usuario_id_fkey' and c.confdeltype = 'n';
-- VERIFY-END
