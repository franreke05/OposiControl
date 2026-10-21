-- =============================================================================
-- SEC-BOT-P3 -- clients hold no write privilege on the OposiBot plan/store tables (defense in depth)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (owner GO required, manual, like 20261023100000..100500). NEW forward-only
-- migration: no earlier file is edited. Idempotent, one transaction. OposiBot-owned, audited by OposiControl.
--
-- TABLES: public.suscripciones, public.beneficios_plan, public.capacidades_especialidad.
--
-- EVIDENCE
--   - MAIN catalog capture 2026-10-14 (read only; baseline_main.sql, the source of supabase/baseline/main_shape_subset.sql):
--       beneficios_plan          REVOKE ALL FROM PUBLIC, anon, authenticated, service_role; GRANT ALL TO service_role;
--                                GRANT SELECT TO anon, authenticated; policy canonical_v2_benefits_public_read (SELECT, anon+authenticated)
--       capacidades_especialidad same ACL as beneficios_plan; policy canonical_v2_capabilities_public_read (SELECT, anon+authenticated)
--       suscripciones            REVOKE ALL FROM PUBLIC, anon, authenticated, service_role; GRANT ALL TO service_role;
--                                GRANT SELECT TO authenticated; policy suscripciones_select_propio (SELECT, own row OR es_admin())
--     So on the captured MAIN no client holds INSERT/UPDATE/DELETE/TRUNCATE here and RLS has no write policy: this migration is
--     a NO-OP on that state and pins it (P3 hardening agreed with OposiControl).
--   - Drift it closes: every table created by `postgres` in `public` inherits `ALTER DEFAULT PRIVILEGES ... GRANT ALL ON TABLES TO
--     anon, authenticated` (main_shape_subset.sql, Supabase default). A table re-created that way (local stack / gate bootstrap:
--     main_shape_subset_extra*.sql create these three with `create table if not exists` + `grant select`), or a dashboard grant,
--     leaves clients with INSERT/UPDATE/DELETE and TRUNCATE. RLS stops I/U/D (no write policy) but NOT TRUNCATE: TRUNCATE ignores
--     RLS, so `authenticated` could empty the plan catalog or every subscription through a SQL path. TRUNCATE is therefore revoked
--     too (it is a write; the brief named I/U/D).
--   - No required client flow: Ktor reads and writes these tables with the service_role key only (SupabaseAuthScope.User is
--     never constructed; AppV1Routes, AppReadsGateway, CatalogGateway, AdminV1Routes); store writes go through SECURITY DEFINER
--     RPCs (20261022102600). The OposiBot KMP client never calls /rest/v1 for business data.
--
-- WHAT THIS MIGRATION DOES
--   REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON the 3 tables FROM anon, authenticated. Then POSTCHECK (P0001
--   PLANS_STORE_WRITE_POSTCHECK and rollback) if any client still has one of them (e.g. through role membership) or lost
--   one of the MAIN SELECT grants listed below. Same structure as 20261023100500 (RB-17), without policies to drop.
--   UNCHANGED: every SELECT grant (MAIN: anon+authenticated on beneficios_plan and capacidades_especialidad, authenticated on
--   suscripciones), every policy, every service_role privilege (incl. ECO-11 from 20261022102600: service_role keeps only
--   REFERENCES, SELECT, TRIGGER on suscripciones there), REFERENCES/TRIGGER/MAINTAIN of any role, rows of any table.
--
-- PREFLIGHT (fail closed, P0001 PLANS_STORE_WRITE_PREFLIGHT, nothing revoked)
--   Roles and tables exist; RLS is enabled on each table; PUBLIC holds no write privilege; every client write privilege (if any)
--   is granted by the owner without grant option and the migrating role can act as the owner (so REVOKE really removes it);
--   no policy lets anon/authenticated/PUBLIC write (INSERT/UPDATE/DELETE/ALL); the MAIN SELECT grants listed above are present.
--
-- FORWARD PLAN
--   Order-independent from Ktor and from the V1 migrations (it touches no service_role privilege, so it is consistent with
--   20261022102600 before or after it). MAIN only with the owner's GO: first run the read-only VERIFY below on MAIN and keep its
--   output (its `info` column lists any client write privilege present BEFORE applying), then apply, then VERIFY = 0.
--
-- ROLLBACK / MITIGATION
--   The ROLLBACK block restores the client ACL of the MAIN capture: SELECT only (re-granting those SELECTs is a no-op). It does
--   NOT re-grant INSERT/UPDATE/DELETE/TRUNCATE, because MAIN never had them; on a drifted database they were never intended and
--   are not re-opened (if ever needed, re-grant exactly what the pre-apply VERIFY listed). Idempotent.
--
-- LOCKING
--   REVOKE only rewrites pg_class.relacl (catalog, milliseconds). lock_timeout 5s: under traffic it aborts instead of queueing.
--
-- DATA LOSS: none.   INDEXES: none.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- grant select on table public.beneficios_plan, public.capacidades_especialidad to anon, authenticated;
-- grant select on table public.suscripciones to authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- with t(tabla) as (values ('suscripciones'), ('beneficios_plan'), ('capacidades_especialidad'))
-- select 'plans_store_client_write_privilege' as check_name, count(*)::bigint as failures,
--        string_agg(r.rol || ' ' || p.priv || ' ' || t.tabla, ', ') as info
-- from t cross join unnest(array['anon', 'authenticated']) as r(rol) cross join unnest(array['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) as p(priv)
-- where has_table_privilege(r.rol::name, format('public.%I', t.tabla)::regclass, p.priv)
-- union all
-- select 'plans_store_client_write_policy', count(*)::bigint, string_agg(c.relname || '.' || p.polname, ', ')
-- from t join pg_class c on c.relnamespace = 'public'::regnamespace and c.relname = t.tabla
-- join pg_policy p on p.polrelid = c.oid
-- where p.polcmd in ('a', 'w', 'd', '*')
--   and (p.polroles && array[0::oid, 'anon'::regrole::oid, 'authenticated'::regrole::oid])
-- union all
-- select 'plans_store_rls_disabled', count(*)::bigint, string_agg(c.relname, ', ')
-- from t join pg_class c on c.relnamespace = 'public'::regnamespace and c.relname = t.tabla
-- where not c.relrowsecurity
-- union all
-- select 'plans_store_client_select_missing', count(*)::bigint, string_agg(s.rol || ' ' || s.tabla, ', ')
-- from (values ('anon', 'beneficios_plan'), ('authenticated', 'beneficios_plan'), ('anon', 'capacidades_especialidad'),
--              ('authenticated', 'capacidades_especialidad'), ('authenticated', 'suscripciones')) as s(rol, tabla)
-- where not has_table_privilege(s.rol::name, format('public.%I', s.tabla)::regclass, 'SELECT')
-- union all
-- select 'plans_store_service_role_select_missing', count(*)::bigint, string_agg(t.tabla, ', ')
-- from t
-- where not has_table_privilege('service_role', format('public.%I', t.tabla)::regclass, 'SELECT');
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create temporary table plans_store_tablas (tabla text primary key) on commit drop;
insert into plans_store_tablas values ('suscripciones'), ('beneficios_plan'), ('capacidades_especialidad');

do $$
declare
  v record;
  v_rel oid;
  v_owner oid;
  v_bad text;
begin
  if to_regrole('anon') is null or to_regrole('authenticated') is null or to_regrole('service_role') is null then
    raise exception 'PLANS_STORE_WRITE_PREFLIGHT' using detail = 'roles anon/authenticated/service_role missing';
  end if;

  for v in select * from plans_store_tablas order by tabla loop
    v_rel := to_regclass(format('public.%I', v.tabla));
    if v_rel is null then
      raise exception 'PLANS_STORE_WRITE_PREFLIGHT' using detail = 'table public.' || v.tabla || ' missing';
    end if;
    select c.relowner into v_owner from pg_class c where c.oid = v_rel;

    if not (select c.relrowsecurity from pg_class c where c.oid = v_rel) then
      raise exception 'PLANS_STORE_WRITE_PREFLIGHT' using detail = v.tabla || ': row level security is disabled (MAIN has it enabled)';
    end if;

    -- Any policy that lets a client write is a MAIN state this migration does not know: stop before changing anything.
    select string_agg(p.polname || ' (' || p.polcmd::text || ')', ', ')
      into v_bad
    from pg_policy p
    where p.polrelid = v_rel and p.polcmd in ('a', 'w', 'd', '*')
      and (p.polroles && array[0::oid, 'anon'::regrole::oid, 'authenticated'::regrole::oid]);
    if v_bad is not null then
      raise exception 'PLANS_STORE_WRITE_PREFLIGHT' using detail = v.tabla || ': unexpected client write policy ' || v_bad;
    end if;

    select string_agg('PUBLIC ' || a.privilege_type, ', ')
      into v_bad
    from pg_class c
    cross join lateral aclexplode(c.relacl) a
    where c.oid = v_rel and a.grantee = 0 and a.privilege_type in ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE');
    if v_bad is not null then
      raise exception 'PLANS_STORE_WRITE_PREFLIGHT' using detail = v.tabla || ': write privilege held by ' || v_bad;
    end if;

    -- MAIN (2026-10-14) holds no client write privilege here. A drifted database may hold any subset (Supabase default
    -- privileges); each one must be removable by this REVOKE: granted by the owner, without grant option.
    select string_agg(ro.rolname || ' ' || a.privilege_type, ', ')
      into v_bad
    from pg_class c
    cross join lateral aclexplode(c.relacl) a
    join pg_roles ro on ro.oid = a.grantee
    where c.oid = v_rel and ro.rolname in ('anon', 'authenticated') and a.privilege_type in ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE')
      and (a.grantor <> v_owner or a.is_grantable or not pg_has_role(current_user, v_owner, 'USAGE'));
    if v_bad is not null then
      raise exception 'PLANS_STORE_WRITE_PREFLIGHT'
        using detail = v.tabla || ': not granted by the owner, with grant option or owner not usable: ' || v_bad;
    end if;
  end loop;

  select string_agg(s.rol || ' ' || s.tabla, ', ')
    into v_bad
  from (values ('anon', 'beneficios_plan'), ('authenticated', 'beneficios_plan'), ('anon', 'capacidades_especialidad'),
               ('authenticated', 'capacidades_especialidad'), ('authenticated', 'suscripciones')) as s(rol, tabla)
  where not has_table_privilege(s.rol::name, format('public.%I', s.tabla)::regclass, 'SELECT');
  if v_bad is not null then
    raise exception 'PLANS_STORE_WRITE_PREFLIGHT' using detail = 'MAIN SELECT grant missing: ' || v_bad;
  end if;
end;
$$;

do $$
declare
  v record;
begin
  for v in select * from plans_store_tablas order by tabla loop
    execute format('revoke insert, update, delete, truncate on table public.%I from anon, authenticated', v.tabla);
  end loop;
end;
$$;

do $$
declare
  v_bad text;
begin
  select string_agg(r.rol || ' ' || p.priv || ' ' || t.tabla, ', ')
    into v_bad
  from plans_store_tablas t
  cross join unnest(array['anon', 'authenticated']) as r(rol)
  cross join unnest(array['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) as p(priv)
  where has_table_privilege(r.rol::name, format('public.%I', t.tabla)::regclass, p.priv);
  if v_bad is not null then
    raise exception 'PLANS_STORE_WRITE_POSTCHECK' using detail = 'still writable by clients: ' || v_bad;
  end if;
  select string_agg(s.rol || ' ' || s.tabla, ', ')
    into v_bad
  from (values ('anon', 'beneficios_plan'), ('authenticated', 'beneficios_plan'), ('anon', 'capacidades_especialidad'),
               ('authenticated', 'capacidades_especialidad'), ('authenticated', 'suscripciones')) as s(rol, tabla)
  where not has_table_privilege(s.rol::name, format('public.%I', s.tabla)::regclass, 'SELECT');
  if v_bad is not null then
    raise exception 'PLANS_STORE_WRITE_POSTCHECK' using detail = 'client SELECT lost: ' || v_bad;
  end if;
end;
$$;

commit;
