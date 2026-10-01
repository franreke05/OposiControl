-- =============================================================================
-- FULL REMEDIATION -- admin_sync_roles_audited compares the expected role set UNDER THE LOCK (MASTER6-IDEM-003)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Forward-only, additive: no earlier migration is edited;
-- public.admin_sync_roles(uuid, uuid[]) (20260921071916), public.admin_sync_roles(uuid, uuid[], uuid)
-- (20260930101600) and public.admin_sync_roles_audited(uuid, uuid[], uuid) (20260930101850) are NOT replaced.
-- Idempotent (create or replace), one transaction.
--
-- WHAT THIS MIGRATION DOES
--   D1-26 (Ktor AdminV1Gateway.syncRoles) accepts an optional `expectedRoleIds` (FA8): the role set the editor saw.
--   Ktor compared it with a read done BEFORE the RPC took the role-sync advisory lock, so a concurrent grant that
--   committed in that window was still removed by the stale full replace (only flagged in the outcome audit row).
--   New overload public.admin_sync_roles_audited(p_usuario_id uuid, p_role_ids uuid[], p_actor_id uuid,
--   p_expected_role_ids uuid[]) returns jsonb {"antes": [...], "despues": [...]}:
--     1. takes the SAME transaction advisory lock (pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0)))
--        BEFORE its first read;
--     2. if p_expected_role_ids is NOT NULL: reads the target's current role ids (sorted, distinct) under the lock;
--     3. if the sorted distinct expected set differs from it (a NULL element never matches: deny by default), an
--        unknown target still raises 'ROLE_SYNC_INVALID_USER' (never a version conflict, whatever the expected set):
--        the function delegates the existence check to the guard of public.admin_sync_roles(uuid, uuid[]) by calling
--        it with the target's CURRENT role set, so the error, sqlstate (P0001), detail and hint are that guard's own.
--        This function does NOT read auth.users itself (gate rule: no new SECURITY DEFINER read of auth.users). For a
--        known target that call rewrites the same set (no guard can fire) and the function then raises
--        'ROLE_SYNC_VERSION_CONFLICT', which aborts the caller's (sub)transaction and undoes that rewrite: a conflict
--        never leaves a write;
--     4. otherwise delegates to public.admin_sync_roles_audited(uuid, uuid[], uuid) in the same transaction (advisory
--        xact locks are re-entrant; its `antes` is read under the same lock, so it equals the compared set; guard
--        errors propagate unchanged). A NULL p_expected_role_ids behaves exactly like the 3-argument function.
--   The 4th parameter has NO default on purpose: with a default, a 3-argument call would match both overloads
--   (ambiguous function call / PGRST203) and break old Ktor and the rollback path.
--   Ktor always sends `p_expected_role_ids` (the array, or JSON null) so PostgREST resolves this overload, and maps
--   ROLE_SYNC_VERSION_CONFLICT to the canonical 409 VERSION_CONFLICT.
--
-- FORWARD PLAN
--   Apply BEFORE deploying the Ktor build that sends p_expected_role_ids (it gets PGRST202 without this function).
--   MAIN only at the dedicated apply gate. The 3-argument overloads stay for old Ktor and for the rollback path.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below drops only the 4-argument overload. Roll Ktor back FIRST (the new Ktor calls it). No data
--   is touched. Without it, the pre-lock check in Ktor still rejects most stale edits and the durable outcome flag
--   `precondicionViolada` records the rest (previous behaviour).
--
-- COMPATIBILITY WITH OLD KTOR
--   Old Ktor calls admin_sync_roles_audited(p_usuario_id, p_role_ids, p_actor_id): unchanged function, still
--   resolved unambiguously (different arity, no defaults).
--
-- LOCKING
--   Function DDL only (catalog locks). Runtime: the existing global transaction advisory lock of role sync (no new
--   lock, no new wait). On a version conflict the delegated existence check also takes the row locks of the target's
--   own roles_usuario rows (same-set rewrite) until the conflict aborts the transaction, under the advisory lock that
--   every role sync already holds. `set local lock_timeout = '5s'` applies to the DDL of THIS migration only.
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None.
--
-- RLS AND GRANTS
--   No policy change. SECURITY DEFINER with search_path '' and row_security off (same fail-closed rationale as the
--   other role-sync overloads). EXECUTE revoked from public/anon/authenticated and granted to service_role only. It
--   calls the 3-argument admin_sync_roles_audited as its owner (the migration role): the VERIFY check
--   `nested_execute_missing` fails if that owner cannot execute it. On a version conflict it calls the existence guard
--   public.admin_sync_roles(uuid, uuid[]) as its owner: VERIFY `guard_execute_missing` fails if that owner cannot
--   execute it. It never reads auth.users itself (VERIFY `expected_overload_reads_auth_users`).
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.admin_sync_roles_audited(uuid, uuid[], uuid, uuid[]);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'expected_overload_missing' as check_name,
--        (case when to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])') is not null then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'audited_overload_missing',
--        (case when to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)') is not null then 0 else 1 end)::bigint, null
-- union all
-- select 'expected_overload_executable_by_clients', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])')
--   and (has_function_privilege('anon', p.oid, 'execute') or has_function_privilege('authenticated', p.oid, 'execute'))
-- union all
-- select 'expected_overload_has_defaults', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])')
--   and p.pronargdefaults > 0
-- union all
-- select 'nested_execute_missing',
--        (case when exists (select 1 from pg_proc p
--                           where p.oid = to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])')
--                             and to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)') is not null
--                             and has_function_privilege(p.proowner, to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)'), 'execute'))
--              then 0 else 1 end)::bigint, null
-- union all
-- select 'guard_execute_missing',
--        (case when exists (select 1 from pg_proc p
--                           where p.oid = to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])')
--                             and to_regprocedure('public.admin_sync_roles(uuid,uuid[])') is not null
--                             and has_function_privilege(p.proowner, to_regprocedure('public.admin_sync_roles(uuid,uuid[])'), 'execute'))
--              then 0 else 1 end)::bigint, null
-- union all
-- select 'expected_overload_reads_auth_users', count(*)::bigint, null
-- from pg_proc p
-- where p.oid = to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])')
--   and pg_get_functiondef(p.oid) ~* 'auth\.users';
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create or replace function public.admin_sync_roles_audited(
  p_usuario_id uuid,
  p_role_ids uuid[],
  p_actor_id uuid,
  p_expected_role_ids uuid[]
)
returns jsonb
language plpgsql
security definer
volatile
set search_path = ''
set row_security = off
as $$
declare
  v_actuales uuid[];
  v_antes jsonb;
  v_esperados jsonb;
begin
  if p_usuario_id is null then
    raise exception 'ROLE_SYNC_INVALID_USER';
  end if;

  -- Same global lock as every role-sync overload, BEFORE the first read: no other sync can commit between this
  -- comparison and the write below.
  perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));

  if p_expected_role_ids is not null then
    v_actuales := array(select distinct ru.rol_id from public.roles_usuario ru where ru.usuario_id = p_usuario_id);

    select coalesce(jsonb_agg(to_jsonb(x.rol_id::text) order by x.rol_id::text), '[]'::jsonb)
    into v_antes
    from unnest(v_actuales) as x(rol_id);

    -- Sorted distinct expected set. A NULL element becomes JSON null and never equals a stored role id.
    select coalesce(jsonb_agg(to_jsonb(x.rol_id::text) order by x.rol_id::text), '[]'::jsonb)
    into v_esperados
    from (select distinct e.rol_id from unnest(p_expected_role_ids) as e(rol_id)) x;

    if v_esperados is distinct from v_antes then
      -- Unknown target: delegated to the existence guard of public.admin_sync_roles(uuid, uuid[]) (its own check, its
      -- own ROLE_SYNC_INVALID_USER, propagated unchanged), so this function never reads the auth schema itself. For a
      -- known target that call rewrites the SAME role set (no guard can fire: same roles, same superadmin count) and
      -- the raise below aborts the caller's (sub)transaction, which undoes it: a conflict never writes.
      perform public.admin_sync_roles(p_usuario_id, v_actuales);
      raise exception 'ROLE_SYNC_VERSION_CONFLICT';
    end if;
  end if;

  -- Re-entrant lock: `antes`/`despues` are read under the same lock; guard errors propagate unchanged.
  return public.admin_sync_roles_audited(p_usuario_id, p_role_ids, p_actor_id);
end;
$$;

revoke all on function public.admin_sync_roles_audited(uuid, uuid[], uuid, uuid[]) from public, anon, authenticated;
grant execute on function public.admin_sync_roles_audited(uuid, uuid[], uuid, uuid[]) to service_role;

do $$
begin
  if to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid,uuid[])') is null
    or to_regprocedure('public.admin_sync_roles_audited(uuid,uuid[],uuid)') is null
    or to_regprocedure('public.admin_sync_roles(uuid,uuid[])') is null then
    raise exception 'ROLE_SYNC_AUDITED_EXPECTED_NOT_INSTALLED';
  end if;
end;
$$;

commit;
