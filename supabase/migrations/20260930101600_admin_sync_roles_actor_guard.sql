-- =============================================================================
-- AUDIT LOOP 2 -- admin_sync_roles knows the ACTOR (MASTER2-AUTH-008)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only, additive: the MAIN function
-- public.admin_sync_roles(uuid, uuid[]) from 20260921071916_role_sync_guard.sql is NOT edited or replaced.
-- Idempotent (create or replace), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   D1-26 (profiles.yaml): granting or removing 'admin'/'superadmin' requires a SUPERADMIN actor. Ktor
--   (AdminV1Gateway.syncRoles) checked it with a separate read BEFORE calling the RPC, and the RPC did not
--   know the actor: a superadmin grant/removal committed between that read and the RPC was invisible to the
--   check (TOCTOU), so a plain admin's replace-all could silently undo a concurrent superadmin decision.
--   New overload public.admin_sync_roles(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid):
--     1. takes the SAME transaction advisory lock as the 2-argument guard
--        (pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0))) BEFORE any read, so it is serialised
--        with every role sync that goes through either overload;
--     2. under that lock, computes whether the change touches administrative roles: symmetric difference of
--        the target's current role codes and the requested ones, canonicalised with
--        public.admin_role_code_canonical, restricted to ('superadmin', 'admin') -- mirror of
--        RoleSyncInvariants.changesAdministrativeRoles in Ktor;
--     3. if it does, requires p_actor_id to hold 'superadmin' (read under the lock), else raises
--        SUPERADMIN_REQUIRED (Ktor maps it to 403 SUPERADMIN_REQUIRED); a NULL actor never passes;
--     4. delegates the write and invariants A/B to public.admin_sync_roles(uuid, uuid[]) (same transaction;
--        advisory xact locks are re-entrant).
--   Maintaining an administrative role the target already has, or changing only moderador/usuario, does not
--   need a superadmin (unchanged rule).
--
-- FORWARD PLAN
--   Apply BEFORE deploying the Ktor build of audit loop 2 (it calls the 3-argument overload, always sending
--   p_actor_id, null included). MAIN only at the dedicated apply gate. The 2-argument overload stays for old
--   Ktor and for the rollback path.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops only the 3-argument overload. Roll Ktor back FIRST: the loop 2 Ktor build calls
--   the 3-argument overload and would get PGRST202 without it. No data is touched.
--
-- COMPATIBILITY WITH OLD KTOR
--   Old Ktor calls admin_sync_roles(p_usuario_id, p_role_ids): PostgREST resolves by argument names, so it
--   keeps hitting the unchanged 2-argument function.
--
-- LOCKING
--   Function DDL only (catalog locks). Runtime: the existing global transaction advisory lock of role sync.
--   The `set local lock_timeout = '5s'` below applies to the DDL of THIS migration only (it ends at commit and the
--   functions carry no setting): the runtime advisory lock waits up to statement_timeout, not 5s (MASTER3-DB-003).
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None.
--
-- RLS AND GRANTS
--   No policy change. The new overload is SECURITY DEFINER with search_path '' and row_security off (same
--   fail-closed rationale as the 2-argument guard). EXECUTE revoked from public/anon/authenticated and granted
--   to service_role only. It calls the 2-argument function as its owner (the migration role): the VERIFY
--   check `nested_execute_missing` fails if that owner cannot execute it.
--   Scope (unchanged from the guard): a direct write on public.roles_usuario with the service_role key does
--   not go through either overload.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_sync_roles(uuid, uuid[], uuid);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'actor_overload_missing' as check_name,
--        (case when to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)') is not null then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'guard_overload_missing',
--        (case when to_regprocedure('public.admin_sync_roles(uuid,uuid[])') is not null then 0 else 1 end)::bigint, null
-- union all
-- select 'actor_overload_executable_by_clients', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)')
--   and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute'))
-- union all
-- select 'nested_execute_missing',
--        (case when exists (select 1 from pg_proc p
--                           where p.oid = to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)')
--                             and to_regprocedure('public.admin_sync_roles(uuid,uuid[])') is not null
--                             and has_function_privilege(p.proowner, to_regprocedure('public.admin_sync_roles(uuid,uuid[])'), 'execute'))
--              then 0 else 1 end)::bigint, null;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create or replace function public.admin_sync_roles(
  p_usuario_id uuid,
  p_role_ids uuid[],
  p_actor_id uuid
)
returns void
language plpgsql
security definer
volatile
set search_path = ''
set row_security = off
as $$
declare
  v_role_ids uuid[] := coalesce(p_role_ids, array[]::uuid[]);
  v_cambia_administrativos boolean;
begin
  if p_usuario_id is null then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  -- Same global lock as the 2-argument guard, BEFORE the first read: the actor's roles and the target's
  -- current roles cannot change (through the RPC) between this check and the write below.
  perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));

  with actuales as (
    select distinct public.admin_role_code_canonical(r.codigo) as codigo
    from public.roles_usuario ru
    join public.roles_app r on r.id = ru.rol_id
    where ru.usuario_id = p_usuario_id
  ),
  pedidos as (
    select distinct public.admin_role_code_canonical(r.codigo) as codigo
    from unnest(v_role_ids) as solicitado(rol_id)
    join public.roles_app r on r.id = solicitado.rol_id
  ),
  diferencia as (
    (select codigo from actuales except select codigo from pedidos)
    union all
    (select codigo from pedidos except select codigo from actuales)
  )
  select exists (select 1 from diferencia d where d.codigo in ('superadmin', 'admin'))
  into v_cambia_administrativos;

  if v_cambia_administrativos and (
    p_actor_id is null
    or not exists (
      select 1
      from public.roles_usuario ru
      join public.roles_app r on r.id = ru.rol_id
      where ru.usuario_id = p_actor_id
        and public.admin_role_code_canonical(r.codigo) = 'superadmin'
    )
  ) then
    raise exception 'SUPERADMIN_REQUIRED';
  end if;

  -- Write + invariants A/B (user exists, known roles, no empty admin, never zero superadmins).
  perform public.admin_sync_roles(p_usuario_id, p_role_ids);
end;
$$;

revoke all on function public.admin_sync_roles(uuid, uuid[], uuid) from public, anon, authenticated;
grant execute on function public.admin_sync_roles(uuid, uuid[], uuid) to service_role;

do $$
begin
  if to_regprocedure('public.admin_sync_roles(uuid,uuid[],uuid)') is null
    or to_regprocedure('public.admin_sync_roles(uuid,uuid[])') is null then
    raise exception 'ROLE_SYNC_ACTOR_GUARD_NOT_INSTALLED';
  end if;
end;
$$;

commit;
