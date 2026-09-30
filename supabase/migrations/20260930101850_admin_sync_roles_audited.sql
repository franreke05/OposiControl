-- =============================================================================
-- AUDIT LOOP 4 -- admin_sync_roles reports the role sets it saw UNDER THE LOCK (MASTER4-OBS-001)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only, additive: neither
-- public.admin_sync_roles(uuid, uuid[]) (20260921071916) nor public.admin_sync_roles(uuid, uuid[], uuid)
-- (20260930101600) is edited or replaced. Idempotent (create or replace), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   D1-26 audit (Ktor AdminV1Gateway.syncRoles) wrote the `antes` role ids of its rows from a read done BEFORE the
--   RPC took the role-sync advisory lock: two concurrent syncs could both record the same stale `antes`, so the
--   privileged audit trail could not say which role set each sync actually replaced. Authorization was never
--   affected (the 3-argument RPC re-checks SUPERADMIN under the lock).
--   New function public.admin_sync_roles_audited(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid)
--   returns jsonb {"antes": [uuid...], "despues": [uuid...]}:
--     1. takes the SAME transaction advisory lock (pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0)))
--        BEFORE its first read, so it is serialised with every role sync through any overload;
--     2. reads the target's current role ids (sorted, distinct) under the lock -> `antes`;
--     3. delegates guard + write to public.admin_sync_roles(uuid, uuid[], uuid) in the same transaction
--        (advisory xact locks are re-entrant; every error, e.g. SUPERADMIN_REQUIRED / ROLE_SYNC_*, propagates
--        unchanged, so Ktor's error mapping is identical);
--     4. reads the resulting role ids (sorted, distinct), still under the lock -> `despues`.
--   Ktor keeps the INTENT row (actor, requested ids, the pre-lock snapshot it authorised against) and writes the
--   OUTCOME row with the locked `antes`/`despues` returned here.
--
-- FORWARD PLAN
--   Apply BEFORE deploying the Ktor build of audit loop 4 (it calls admin_sync_roles_audited). MAIN only at the
--   dedicated apply gate. Both older overloads stay for old Ktor and for the rollback path.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops only the new function. Roll Ktor back FIRST: the loop 4 Ktor build calls
--   admin_sync_roles_audited and would get PGRST202 without it. No data is touched.
--
-- COMPATIBILITY WITH OLD KTOR
--   Old Ktor calls admin_sync_roles(p_usuario_id, p_role_ids[, p_actor_id]): unchanged functions.
--
-- LOCKING
--   Function DDL only (catalog locks). Runtime: the existing global transaction advisory lock of role sync (no new
--   lock, no new wait). `set local lock_timeout = '5s'` applies to the DDL of THIS migration only.
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None.
--
-- RLS AND GRANTS
--   No policy change. SECURITY DEFINER with search_path '' and row_security off (same fail-closed rationale as the
--   guard overloads). EXECUTE revoked from public/anon/authenticated and granted to service_role only. It calls the
--   3-argument overload as its owner (the migration role): the VERIFY check `nested_execute_missing` fails if that
--   owner cannot execute it.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_sync_roles_audited(uuid, uuid[], uuid);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'audited_function_missing' as check_name,
--        (case when to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)') is not null then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'actor_overload_missing',
--        (case when to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)') is not null then 0 else 1 end)::bigint, null
-- union all
-- select 'audited_function_executable_by_clients', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)')
--   and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute'))
-- union all
-- select 'nested_execute_missing',
--        (case when exists (select 1 from pg_proc p
--                           where p.oid = to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)')
--                             and to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)') is not null
--                             and has_function_privilege(p.proowner, to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)'), 'execute'))
--              then 0 else 1 end)::bigint, null;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create or replace function public.admin_sync_roles_audited(
  p_usuario_id uuid,
  p_role_ids uuid[],
  p_actor_id uuid
)
returns jsonb
language plpgsql
security definer
volatile
set search_path = ''
set row_security = off
as $$
declare
  v_antes jsonb;
  v_despues jsonb;
begin
  if p_usuario_id is null then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  -- Same global lock as both guard overloads, BEFORE the first read: `antes` is exactly the set this sync replaces.
  perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));

  select coalesce(jsonb_agg(to_jsonb(x.rol_id::text) order by x.rol_id::text), '[]'::jsonb)
  into v_antes
  from (select distinct ru.rol_id from public.roles_usuario ru where ru.usuario_id = p_usuario_id) x;

  -- Guard (SUPERADMIN under the lock) + write + invariants A/B. Errors propagate unchanged.
  perform public.admin_sync_roles(p_usuario_id, p_role_ids, p_actor_id);

  select coalesce(jsonb_agg(to_jsonb(x.rol_id::text) order by x.rol_id::text), '[]'::jsonb)
  into v_despues
  from (select distinct ru.rol_id from public.roles_usuario ru where ru.usuario_id = p_usuario_id) x;

  return jsonb_build_object('antes', v_antes, 'despues', v_despues);
end;
$$;

revoke all on function public.admin_sync_roles_audited(uuid, uuid[], uuid) from public, anon, authenticated;
grant execute on function public.admin_sync_roles_audited(uuid, uuid[], uuid) to service_role;

do $$
begin
  if to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)') is null
    or to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)') is null then
    raise exception 'ROLE_SYNC_AUDITED_NOT_INSTALLED';
  end if;
end;
$$;

commit;
