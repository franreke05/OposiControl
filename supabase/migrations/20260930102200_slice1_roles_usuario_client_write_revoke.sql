-- =============================================================================
-- FULL REMEDIATION R8 -- clients can no longer write public.roles_usuario (MASTERF1-DB-001 / RC-02)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). NEW forward-only migration: no earlier file is edited
-- (20260930100000..102100 are pushed and immutable). One policy dropped, three privileges revoked; no column,
-- function, index or row changes. Idempotent, one transaction.
--
-- EVIDENCE (supabase/baseline/main_shape_subset.sql, the MAIN shape)
--   - :354 policy roles_usuario_admin_all AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK
--     (es_admin()); es_admin() (:194-207) is true for any holder of 'superadmin' OR 'admin'.
--   - :400 GRANT DELETE, INSERT, ..., UPDATE ON roles_usuario TO authenticated (102100 revoked only TRUNCATE, TRIGGER,
--     REFERENCES). anon holds MAINTAIN, SELECT only (:399).
--   - So a PLAIN admin with a user JWT can, through the Data API (PostgREST), INSERT (own uid, superadmin) or DELETE
--     the last superadmin row: both bypass admin_sync_roles (SUPERADMIN_REQUIRED of 20260930101600, D1-26, and the
--     last-superadmin invariant of 20260921071916). dbtest RolesUsuarioClientWriteRevokeMigrationTest proves the
--     self-grant under SET ROLE authenticated + claim sub BEFORE this migration (negative control).
--   - No required flow: Ktor writes roles only through rpc/admin_sync_roles_audited with the service_role key; the
--     admin client and OposiBot never call the Data API for business data (scripts/verify-no-direct-supabase.sh).
--     The SECURITY DEFINER role-sync functions run as their owner (the migration role), not as the caller.
--
-- WHAT THIS MIGRATION DOES
--   1. DROP POLICY roles_usuario_admin_all. Reads stay covered by roles_usuario_select_propio (own row OR es_admin()),
--      which this migration requires (PREFLIGHT) and keeps.
--   2. REVOKE INSERT, UPDATE, DELETE ON public.roles_usuario FROM anon, authenticated.
--   3. POSTCHECK: has_table_privilege() is false for anon and authenticated on INSERT/UPDATE/DELETE (effective:
--      includes PUBLIC and membership) and no permissive INSERT/UPDATE/DELETE/ALL policy is left on roles_usuario;
--      otherwise P0001 ROLES_USUARIO_CLIENT_WRITE_POSTCHECK and the whole transaction rolls back.
--   UNCHANGED: SELECT/MAINTAIN of anon and authenticated, every service_role privilege, roles_usuario_select_propio,
--   es_admin(), every role-sync function and its grants.
--
-- PREFLIGHT (fail closed, P0001 ROLES_USUARIO_CLIENT_WRITE_PREFLIGHT, nothing dropped or revoked)
--   - roles anon/authenticated and table public.roles_usuario exist;
--   - roles_usuario_select_propio exists as a PERMISSIVE SELECT policy (admins and owners keep reading);
--   - roles_usuario_admin_all, if present, is exactly the MAIN policy (PERMISSIVE, ALL, TO authenticated, USING and
--     WITH CHECK es_admin()), so the ROLLBACK recreates it exactly;
--   - the state is EITHER the MAIN state (authenticated holds INSERT, UPDATE and DELETE granted by the owner without
--     grant option, the migration role can act as the owner, and roles_usuario_admin_all exists) OR the applied state
--     (authenticated holds none of the three and roles_usuario_admin_all is absent: rerun = no-op). A partial state
--     could not be rolled back exactly and aborts;
--   - anon and PUBLIC hold none of INSERT/UPDATE/DELETE as an ACL entry (MAIN: never; the ROLLBACK re-grants to
--     authenticated only, so any other holder would be silently lost).
--
-- HISTORICAL DATA
--   None. No row is read, written or deleted. Rows a plain admin may already have written through the Data API are
--   not detectable from the table (asignado_por is client-writable); the operator reviews the administrative holders
--   before the apply (read only): `select ru.usuario_id, ra.codigo, ru.asignado_por, ru.creado_en from
--   public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id where ra.codigo in ('superadmin', 'admin')`.
--
-- FORWARD PLAN
--   Order-independent from Ktor (no Ktor build writes roles_usuario with a user JWT). Apply after 102100. MAIN only
--   at the dedicated apply gate. No PostgREST schema reload is needed (privileges and policies are evaluated by
--   PostgreSQL per request).
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below re-grants INSERT, UPDATE, DELETE to authenticated (the grantor is again the owner, so the
--   ACL entry is modified in place and its text is byte-identical to MAIN) and recreates roles_usuario_admin_all with
--   the MAIN definition. Idempotent (grant is idempotent; the policy is created only when absent). Rolling back
--   re-opens the self-grant path. No data is touched.
--
-- LOCKING
--   DROP POLICY takes an ACCESS EXCLUSIVE lock on roles_usuario until COMMIT (catalog-only, no scan or rewrite);
--   es_admin()/es_moderador_o_admin() read roles_usuario inside RLS checks of other tables, so those requests wait
--   for that instant. REVOKE is a catalog update of the pg_class row. `set local lock_timeout = '5s'` makes the
--   migration fail instead of queueing behind long readers (retry it).
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None.
--
-- RLS AND GRANTS
--   Narrowing only: one permissive ALL policy dropped; INSERT, UPDATE, DELETE revoked from anon/authenticated on
--   roles_usuario. No grant added or widened, no other policy touched. service_role (BYPASSRLS) keeps its privileges:
--   a direct write with the service_role key stays the declared residual of 20260930101600 (scope note).
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- grant insert, update, delete on table public.roles_usuario to authenticated;
-- do $$
-- begin
--   if not exists (select 1 from pg_policy p
--                  where p.polrelid = 'public.roles_usuario'::regclass and p.polname = 'roles_usuario_admin_all') then
--     create policy roles_usuario_admin_all on public.roles_usuario as permissive for all to authenticated
--       using (public.es_admin()) with check (public.es_admin());
--   end if;
-- end $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'roles_usuario_client_write_privilege' as check_name, count(*)::bigint as failures,
--        string_agg(r.rol || ' ' || p.priv, ', ') as info
-- from unnest(array['anon', 'authenticated']) as r(rol)
-- cross join unnest(array['INSERT', 'UPDATE', 'DELETE']) as p(priv)
-- where has_table_privilege(r.rol::name, 'public.roles_usuario'::regclass, p.priv)
-- union all
-- select 'roles_usuario_client_write_policy', count(*)::bigint, string_agg(p.polname, ',')
-- from pg_policy p
-- where p.polrelid = 'public.roles_usuario'::regclass and p.polpermissive and p.polcmd in ('a', 'w', 'd', '*')
-- union all
-- select 'roles_usuario_select_policy_missing',
--        (case when exists (select 1 from pg_policy p
--                           where p.polrelid = 'public.roles_usuario'::regclass and p.polname = 'roles_usuario_select_propio'
--                             and p.polpermissive and p.polcmd = 'r')
--              then 0 else 1 end)::bigint, null
-- union all
-- select 'roles_usuario_service_role_write_missing', count(*)::bigint, string_agg(p.priv, ',')
-- from unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE']) as p(priv)
-- where not has_table_privilege('service_role', 'public.roles_usuario'::regclass, p.priv);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  v_rel oid := to_regclass('public.roles_usuario');
  v_owner oid;
  v_admin_all boolean;
  v_auth text[];
  v_bad text;
begin
  if to_regrole('anon') is null or to_regrole('authenticated') is null or v_rel is null then
    raise exception 'ROLES_USUARIO_CLIENT_WRITE_PREFLIGHT'
      using detail = 'roles anon/authenticated or table public.roles_usuario missing';
  end if;
  select c.relowner into v_owner from pg_class c where c.oid = v_rel;

  if not exists (select 1 from pg_policy p
                 where p.polrelid = v_rel and p.polname = 'roles_usuario_select_propio' and p.polpermissive and p.polcmd = 'r') then
    raise exception 'ROLES_USUARIO_CLIENT_WRITE_PREFLIGHT'
      using detail = 'roles_usuario_select_propio (permissive SELECT) is required';
  end if;

  v_admin_all := exists (select 1 from pg_policy p where p.polrelid = v_rel and p.polname = 'roles_usuario_admin_all');
  if v_admin_all and not exists (
       select 1 from pg_policy p
       where p.polrelid = v_rel and p.polname = 'roles_usuario_admin_all' and p.polpermissive and p.polcmd = '*'
         and p.polroles = array['authenticated'::regrole::oid]
         and pg_get_expr(p.polqual, p.polrelid) ~ '^(public\.)?es_admin\(\)$'
         and pg_get_expr(p.polwithcheck, p.polrelid) ~ '^(public\.)?es_admin\(\)$') then
    raise exception 'ROLES_USUARIO_CLIENT_WRITE_PREFLIGHT'
      using detail = 'roles_usuario_admin_all differs from MAIN (permissive ALL to authenticated, es_admin())';
  end if;

  -- anon / PUBLIC must not hold any write privilege as an ACL entry (the ROLLBACK only re-grants authenticated).
  select string_agg(coalesce(ro.rolname::text, 'PUBLIC') || ' ' || a.privilege_type, ', ')
    into v_bad
  from pg_class c
  cross join lateral aclexplode(c.relacl) a
  left join pg_roles ro on ro.oid = a.grantee
  where c.oid = v_rel and a.privilege_type in ('INSERT', 'UPDATE', 'DELETE')
    and (a.grantee = 0 or ro.rolname = 'anon');
  if v_bad is not null then
    raise exception 'ROLES_USUARIO_CLIENT_WRITE_PREFLIGHT'
      using detail = 'write privilege held by: ' || v_bad;
  end if;

  select coalesce(array_agg(a.privilege_type::text order by a.privilege_type), array[]::text[])
    into v_auth
  from pg_class c
  cross join lateral aclexplode(c.relacl) a
  where c.oid = v_rel and a.grantee = 'authenticated'::regrole and a.privilege_type in ('INSERT', 'UPDATE', 'DELETE');

  if v_auth = array['DELETE', 'INSERT', 'UPDATE'] and v_admin_all then
    select string_agg(a.privilege_type, ', ')
      into v_bad
    from pg_class c
    cross join lateral aclexplode(c.relacl) a
    where c.oid = v_rel and a.grantee = 'authenticated'::regrole and a.privilege_type in ('INSERT', 'UPDATE', 'DELETE')
      and (a.grantor <> v_owner or a.is_grantable or not pg_has_role(current_user, v_owner, 'USAGE'));
    if v_bad is not null then
      raise exception 'ROLES_USUARIO_CLIENT_WRITE_PREFLIGHT'
        using detail = 'not granted by the owner, with grant option or owner not usable: authenticated ' || v_bad;
    end if;
  elsif not (cardinality(v_auth) = 0 and not v_admin_all) then
    raise exception 'ROLES_USUARIO_CLIENT_WRITE_PREFLIGHT'
      using detail = format('partial state: authenticated holds {%s}, roles_usuario_admin_all present = %s',
                            array_to_string(v_auth, ','), v_admin_all);
  end if;
end;
$$;

drop policy if exists roles_usuario_admin_all on public.roles_usuario;
revoke insert, update, delete on table public.roles_usuario from anon, authenticated;

do $$
declare
  v_bad text;
begin
  select string_agg(r.rol || ' ' || p.priv, ', ')
    into v_bad
  from unnest(array['anon', 'authenticated']) as r(rol)
  cross join unnest(array['INSERT', 'UPDATE', 'DELETE']) as p(priv)
  where has_table_privilege(r.rol::name, 'public.roles_usuario'::regclass, p.priv);
  if v_bad is null then
    select string_agg(p.polname, ', ')
      into v_bad
    from pg_policy p
    where p.polrelid = 'public.roles_usuario'::regclass and p.polpermissive and p.polcmd in ('a', 'w', 'd', '*');
  end if;
  if v_bad is not null then
    raise exception 'ROLES_USUARIO_CLIENT_WRITE_POSTCHECK'
      using detail = 'still writable by clients: ' || v_bad;
  end if;
end;
$$;

commit;
