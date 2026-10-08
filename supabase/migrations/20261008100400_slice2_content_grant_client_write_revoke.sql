-- =============================================================================
-- SLICE 2 HARDENING -- clients can no longer write public.contenido_desbloqueado_usuario (content grants)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). NEW forward-only migration: no earlier file is edited.
-- One policy dropped, three privileges revoked; no column, function, index or row changes. Idempotent, one transaction.
-- Same shape as 20260930102200 (roles_usuario), applied to the entitlement table.
--
-- EVIDENCE (MAIN, read on 2026-10-08)
--   - policy contenido_desbloqueado_admin_all AS PERMISSIVE FOR ALL TO authenticated USING (es_admin()) WITH CHECK
--     (es_admin()); authenticated holds INSERT, UPDATE, DELETE on the table; anon and PUBLIC hold none.
--   - So any admin with a user JWT can, through the Data API (PostgREST), INSERT a content grant for any user
--     (including itself) or DELETE/extend one, bypassing Ktor (EntitlementPolicy reads this table as the source of
--     access), its admin authorization and its audit trail. A plain user cannot (only the SELECT-own policy applies).
--   - No required flow: Ktor reads and writes grants with the service_role key (admin "accesos" routes, catalog,
--     attempts); the admin client and OposiBot never call the Data API for business data
--     (scripts/verify-no-direct-supabase.sh).
--
-- WHAT THIS MIGRATION DOES
--   1. DROP POLICY contenido_desbloqueado_admin_all. Reads stay covered by contenido_desbloqueado_select_propio
--      (own row OR es_admin()), which this migration requires (PREFLIGHT) and keeps.
--   2. REVOKE INSERT, UPDATE, DELETE ON public.contenido_desbloqueado_usuario FROM anon, authenticated.
--   3. POSTCHECK: has_table_privilege() is false for anon and authenticated on INSERT/UPDATE/DELETE and no permissive
--      write policy is left; otherwise P0001 CONTENT_GRANT_CLIENT_WRITE_POSTCHECK and the transaction rolls back.
--   UNCHANGED: SELECT of authenticated, every service_role privilege, contenido_desbloqueado_select_propio, es_admin(),
--   every content-authoring object (examenes, preguntas, ... are not touched).
--
-- PREFLIGHT (fail closed, P0001 CONTENT_GRANT_CLIENT_WRITE_PREFLIGHT, nothing dropped or revoked)
--   Roles and table exist; the SELECT policy exists; the admin policy, if present, is exactly the MAIN policy; the state
--   is EITHER the MAIN state OR the applied state (rerun = no-op), never a partial one; anon/PUBLIC hold no write ACL.
--
-- HISTORICAL DATA
--   None read, written or deleted. Grants an admin may already have written through the Data API are not
--   distinguishable in the table; the operator can review them before the apply (read only):
--   `select motivo, count(*) from public.contenido_desbloqueado_usuario group by 1`.
--
-- FORWARD PLAN
--   Order-independent from Ktor (no Ktor build writes this table with a user JWT). MAIN only at its apply gate.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below re-grants INSERT, UPDATE, DELETE to authenticated and recreates the policy with the MAIN
--   definition. Idempotent. Rolling back re-opens the direct-write path. No data is touched.
--
-- LOCKING
--   DROP POLICY takes an ACCESS EXCLUSIVE lock on the table until COMMIT (catalog-only). lock_timeout 5s.
--
-- DATA LOSS: none.   INDEXES: none.
--
-- RLS AND GRANTS
--   Narrowing only: one permissive ALL policy dropped; INSERT, UPDATE, DELETE revoked from anon/authenticated.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- grant insert, update, delete on table public.contenido_desbloqueado_usuario to authenticated;
-- do $$
-- begin
--   if not exists (select 1 from pg_policy p
--                  where p.polrelid = 'public.contenido_desbloqueado_usuario'::regclass and p.polname = 'contenido_desbloqueado_admin_all') then
--     create policy contenido_desbloqueado_admin_all on public.contenido_desbloqueado_usuario as permissive for all to authenticated
--       using (public.es_admin()) with check (public.es_admin());
--   end if;
-- end $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'content_grant_client_write_privilege' as check_name, count(*)::bigint as failures,
--        string_agg(r.rol || ' ' || p.priv, ', ') as info
-- from unnest(array['anon', 'authenticated']) as r(rol)
-- cross join unnest(array['INSERT', 'UPDATE', 'DELETE']) as p(priv)
-- where has_table_privilege(r.rol::name, 'public.contenido_desbloqueado_usuario'::regclass, p.priv)
-- union all
-- select 'content_grant_client_write_policy', count(*)::bigint, string_agg(p.polname, ',')
-- from pg_policy p
-- where p.polrelid = 'public.contenido_desbloqueado_usuario'::regclass and p.polpermissive and p.polcmd in ('a', 'w', 'd', '*')
-- union all
-- select 'content_grant_select_policy_missing',
--        (case when exists (select 1 from pg_policy p
--                           where p.polrelid = 'public.contenido_desbloqueado_usuario'::regclass and p.polname = 'contenido_desbloqueado_select_propio'
--                             and p.polpermissive and p.polcmd = 'r')
--              then 0 else 1 end)::bigint, null
-- union all
-- select 'content_grant_service_role_write_missing', count(*)::bigint, string_agg(p.priv, ',')
-- from unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE']) as p(priv)
-- where not has_table_privilege('service_role', 'public.contenido_desbloqueado_usuario'::regclass, p.priv);
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  v_rel oid := to_regclass('public.contenido_desbloqueado_usuario');
  v_owner oid;
  v_admin_all boolean;
  v_auth text[];
  v_bad text;
begin
  if to_regrole('anon') is null or to_regrole('authenticated') is null or v_rel is null then
    raise exception 'CONTENT_GRANT_CLIENT_WRITE_PREFLIGHT'
      using detail = 'roles anon/authenticated or table public.contenido_desbloqueado_usuario missing';
  end if;
  select c.relowner into v_owner from pg_class c where c.oid = v_rel;

  if not exists (select 1 from pg_policy p
                 where p.polrelid = v_rel and p.polname = 'contenido_desbloqueado_select_propio' and p.polpermissive and p.polcmd = 'r') then
    raise exception 'CONTENT_GRANT_CLIENT_WRITE_PREFLIGHT'
      using detail = 'contenido_desbloqueado_select_propio (permissive SELECT) is required';
  end if;

  v_admin_all := exists (select 1 from pg_policy p where p.polrelid = v_rel and p.polname = 'contenido_desbloqueado_admin_all');
  if v_admin_all and not exists (
       select 1 from pg_policy p
       where p.polrelid = v_rel and p.polname = 'contenido_desbloqueado_admin_all' and p.polpermissive and p.polcmd = '*'
         and p.polroles = array['authenticated'::regrole::oid]
         and pg_get_expr(p.polqual, p.polrelid) ~ '^(public\.)?es_admin\(\)$'
         and pg_get_expr(p.polwithcheck, p.polrelid) ~ '^(public\.)?es_admin\(\)$') then
    raise exception 'CONTENT_GRANT_CLIENT_WRITE_PREFLIGHT'
      using detail = 'contenido_desbloqueado_admin_all differs from MAIN (permissive ALL to authenticated, es_admin())';
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
    raise exception 'CONTENT_GRANT_CLIENT_WRITE_PREFLIGHT'
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
      raise exception 'CONTENT_GRANT_CLIENT_WRITE_PREFLIGHT'
        using detail = 'not granted by the owner, with grant option or owner not usable: authenticated ' || v_bad;
    end if;
  elsif not (cardinality(v_auth) = 0 and not v_admin_all) then
    raise exception 'CONTENT_GRANT_CLIENT_WRITE_PREFLIGHT'
      using detail = format('partial state: authenticated holds {%s}, contenido_desbloqueado_admin_all present = %s',
                            array_to_string(v_auth, ','), v_admin_all);
  end if;
end;
$$;

drop policy if exists contenido_desbloqueado_admin_all on public.contenido_desbloqueado_usuario;
revoke insert, update, delete on table public.contenido_desbloqueado_usuario from anon, authenticated;

do $$
declare
  v_bad text;
begin
  select string_agg(r.rol || ' ' || p.priv, ', ')
    into v_bad
  from unnest(array['anon', 'authenticated']) as r(rol)
  cross join unnest(array['INSERT', 'UPDATE', 'DELETE']) as p(priv)
  where has_table_privilege(r.rol::name, 'public.contenido_desbloqueado_usuario'::regclass, p.priv);
  if v_bad is null then
    select string_agg(p.polname, ', ')
      into v_bad
    from pg_policy p
    where p.polrelid = 'public.contenido_desbloqueado_usuario'::regclass and p.polpermissive and p.polcmd in ('a', 'w', 'd', '*');
  end if;
  if v_bad is not null then
    raise exception 'CONTENT_GRANT_CLIENT_WRITE_POSTCHECK'
      using detail = 'still writable by clients: ' || v_bad;
  end if;
end;
$$;

commit;
