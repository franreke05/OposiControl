-- =============================================================================
-- RB-17 (SEC-V1-01b, Control part) -- an admin JWT can no longer write the remaining admin tables through the Data API
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (owner GO required). NEW forward-only migration: no earlier file is edited.
-- Same shape as 20261023100000 (SEC-V1-01), for the admin tables that one did not cover. Idempotent, one transaction.
--
-- EVIDENCE (MAIN catalog read 2026-10-19, docs/audit-v1/evidence/SEC-V1-01/MAIN-checkpoint-2026-10-19.md §RB-17)
--   - authenticated holds INSERT, UPDATE, DELETE on opositeca_items, etiquetas_opositeca, invitaciones_referido and
--     preguntas_parseadas_staging, each with a permissive FOR ALL policy `*_admin_all` (es_admin()).
--   - So an admin JWT (or a stolen one) + the public anon key rewrites Opositeca content, referral invitations and
--     their rewards, and the import staging rows before publication, through PostgREST: no Ktor validation, no
--     auditoria_log, no admin rate limit.
--   - Ktor writes these tables with the service_role key; no client writes them with a user JWT, EXCEPT the user's own
--     referral invitation (`invitaciones_referido_insert_propias`: own row, pending, no reward), which stays.
--   - The rest of RB-17 is covered elsewhere: the user tables by OposiBot's 20261022102700, registros_reto_diario and
--     lotes_actualizacion_ranking by 20261022101200 (+ 101700). Order-independent from those and from 20261023100000.
--
-- WHAT THIS MIGRATION DOES
--   1. opositeca_items, etiquetas_opositeca, preguntas_parseadas_staging: DROP POLICY <table>_admin_all; REVOKE INSERT,
--      UPDATE, DELETE FROM anon, authenticated.
--   2. invitaciones_referido: DROP POLICY invitaciones_referido_admin_all; REVOKE UPDATE, DELETE FROM anon, authenticated;
--      REVOKE INSERT FROM anon. authenticated KEEPS INSERT for invitaciones_referido_insert_propias (own pending row).
--   3. POSTCHECK: no client write privilege beyond that INSERT, no `*_admin_all` left, no permissive write policy except
--      invitaciones_referido_insert_propias, which is still there; otherwise P0001 ADMIN_TABLES_WRITE_POSTCHECK and rollback.
--   UNCHANGED: every service_role privilege, every SELECT policy (including `invitaciones_referido_select_propias ... OR
--   es_admin()`, read only), es_admin() (20261023100000 owns it), rows of any table.
--
-- PREFLIGHT (fail closed, P0001 ADMIN_TABLES_WRITE_PREFLIGHT, nothing dropped or revoked)
--   Roles and the four tables exist; anon/PUBLIC hold no write ACL; each `*_admin_all` is absent or EXACTLY permissive
--   ALL to authenticated with es_admin(); no other permissive write policy on these tables except
--   invitaciones_referido_insert_propias (INSERT only); each table is EITHER in the MAIN state (authenticated holds exactly
--   INSERT+UPDATE+DELETE granted by the owner without grant option, and *_admin_all present) OR in the applied state
--   (authenticated holds nothing, or only INSERT on invitaciones_referido, and no *_admin_all). Any other state is unknown
--   to this migration and aborts it before any change.
--
-- FORWARD PLAN
--   MAIN only with the owner's GO, after the read-only VERIFY below has been run on MAIN and reviewed (expected before:
--   client write privileges = 11, admin policies = 4; after: all 0 and invitaciones_user_insert_missing = 0).
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below re-grants what was revoked from authenticated and recreates the four `*_admin_all` policies.
--   Idempotent. Re-opens the path (documented). anon never held write privileges on MAIN: nothing to give back.
--
-- LOCKING
--   DROP POLICY takes ACCESS EXCLUSIVE on each table until COMMIT (catalog only, milliseconds). lock_timeout 5s.
--
-- DATA LOSS: none.   INDEXES: none.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- grant insert, update, delete on table public.opositeca_items, public.etiquetas_opositeca, public.preguntas_parseadas_staging to authenticated;
-- grant insert, update, delete on table public.invitaciones_referido to authenticated;
-- do $$
-- declare
--   v record;
-- begin
--   for v in select * from (values
--       ('opositeca_items', 'opositeca_items_admin_all'), ('etiquetas_opositeca', 'etiquetas_opositeca_admin_all'),
--       ('invitaciones_referido', 'invitaciones_referido_admin_all'),
--       ('preguntas_parseadas_staging', 'preguntas_parseadas_admin_all')) as t(tabla, politica)
--   loop
--     if not exists (select 1 from pg_policy p where p.polrelid = format('public.%I', v.tabla)::regclass and p.polname = v.politica) then
--       execute format('create policy %I on public.%I as permissive for all to authenticated using (public.es_admin()) with check (public.es_admin())',
--                      v.politica, v.tabla);
--     end if;
--   end loop;
-- end $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- with t(tabla, politica, keeps_insert) as (values ('opositeca_items', 'opositeca_items_admin_all', false),
--   ('etiquetas_opositeca', 'etiquetas_opositeca_admin_all', false), ('invitaciones_referido', 'invitaciones_referido_admin_all', true),
--   ('preguntas_parseadas_staging', 'preguntas_parseadas_admin_all', false))
-- select 'admin_tables_client_write_privilege' as check_name, count(*)::bigint as failures,
--        string_agg(r.rol || ' ' || p.priv || ' ' || t.tabla, ', ') as info
-- from t cross join unnest(array['anon', 'authenticated']) as r(rol) cross join unnest(array['INSERT', 'UPDATE', 'DELETE']) as p(priv)
-- where has_table_privilege(r.rol::name, format('public.%I', t.tabla)::regclass, p.priv)
--   and not (t.keeps_insert and r.rol = 'authenticated' and p.priv = 'INSERT')
-- union all
-- select 'admin_tables_admin_policy', count(*)::bigint, string_agg(c.relname || '.' || p.polname, ',')
-- from t join pg_class c on c.relnamespace = 'public'::regnamespace and c.relname = t.tabla
-- join pg_policy p on p.polrelid = c.oid and p.polname = t.politica
-- union all
-- select 'admin_tables_unexpected_write_policy', count(*)::bigint, string_agg(c.relname || '.' || p.polname, ',')
-- from t join pg_class c on c.relnamespace = 'public'::regnamespace and c.relname = t.tabla
-- join pg_policy p on p.polrelid = c.oid
-- where p.polpermissive and p.polcmd in ('a', 'w', 'd', '*') and p.polname <> t.politica
--   and not (t.tabla = 'invitaciones_referido' and p.polname = 'invitaciones_referido_insert_propias' and p.polcmd = 'a')
-- union all
-- select 'admin_tables_service_role_write_missing', count(*)::bigint, string_agg(p.priv || ' ' || t.tabla, ',')
-- from t cross join unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE']) as p(priv)
-- where not has_table_privilege('service_role', format('public.%I', t.tabla)::regclass, p.priv)
-- union all
-- select 'invitaciones_user_insert_missing',
--        (case when has_table_privilege('authenticated', 'public.invitaciones_referido', 'INSERT')
--                   and exists (select 1 from pg_policy p where p.polrelid = 'public.invitaciones_referido'::regclass
--                               and p.polname = 'invitaciones_referido_insert_propias' and p.polcmd = 'a')
--              then 0 else 1 end)::bigint, null;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create temporary table rb17_tablas (tabla text primary key, politica text not null, keeps_insert boolean not null) on commit drop;
insert into rb17_tablas values
  ('opositeca_items', 'opositeca_items_admin_all', false),
  ('etiquetas_opositeca', 'etiquetas_opositeca_admin_all', false),
  ('invitaciones_referido', 'invitaciones_referido_admin_all', true),
  ('preguntas_parseadas_staging', 'preguntas_parseadas_admin_all', false);

do $$
declare
  v record;
  v_rel oid;
  v_owner oid;
  v_admin_all boolean;
  v_auth text[];
  v_bad text;
begin
  if to_regrole('anon') is null or to_regrole('authenticated') is null or to_regrole('service_role') is null then
    raise exception 'ADMIN_TABLES_WRITE_PREFLIGHT' using detail = 'roles anon/authenticated/service_role missing';
  end if;

  for v in select * from rb17_tablas order by tabla loop
    v_rel := to_regclass(format('public.%I', v.tabla));
    if v_rel is null then
      raise exception 'ADMIN_TABLES_WRITE_PREFLIGHT' using detail = 'table public.' || v.tabla || ' missing';
    end if;
    select c.relowner into v_owner from pg_class c where c.oid = v_rel;

    v_admin_all := exists (select 1 from pg_policy p where p.polrelid = v_rel and p.polname = v.politica);
    if v_admin_all and not exists (
         select 1 from pg_policy p
         where p.polrelid = v_rel and p.polname = v.politica and p.polpermissive and p.polcmd = '*'
           and p.polroles = array['authenticated'::regrole::oid]
           and pg_get_expr(p.polqual, p.polrelid) ~ '^(public\.)?es_admin\(\)$'
           and pg_get_expr(p.polwithcheck, p.polrelid) ~ '^(public\.)?es_admin\(\)$') then
      raise exception 'ADMIN_TABLES_WRITE_PREFLIGHT'
        using detail = v.politica || ' differs from MAIN (permissive ALL to authenticated, es_admin())';
    end if;

    -- Any other permissive write policy is a MAIN state this migration does not know: stop before changing anything.
    select string_agg(p.polname || ' (' || p.polcmd::text || ')', ', ')
      into v_bad
    from pg_policy p
    where p.polrelid = v_rel and p.polpermissive and p.polcmd in ('a', 'w', 'd', '*') and p.polname <> v.politica
      and not (v.tabla = 'invitaciones_referido' and p.polname = 'invitaciones_referido_insert_propias' and p.polcmd = 'a');
    if v_bad is not null then
      raise exception 'ADMIN_TABLES_WRITE_PREFLIGHT' using detail = v.tabla || ': unexpected write policy ' || v_bad;
    end if;

    select string_agg(coalesce(ro.rolname::text, 'PUBLIC') || ' ' || a.privilege_type, ', ')
      into v_bad
    from pg_class c
    cross join lateral aclexplode(c.relacl) a
    left join pg_roles ro on ro.oid = a.grantee
    where c.oid = v_rel and a.privilege_type in ('INSERT', 'UPDATE', 'DELETE')
      and (a.grantee = 0 or ro.rolname = 'anon');
    if v_bad is not null then
      raise exception 'ADMIN_TABLES_WRITE_PREFLIGHT' using detail = v.tabla || ': write privilege held by ' || v_bad;
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
        raise exception 'ADMIN_TABLES_WRITE_PREFLIGHT'
          using detail = v.tabla || ': not granted by the owner, with grant option or owner not usable: ' || v_bad;
      end if;
    elsif not (not v_admin_all
               and (cardinality(v_auth) = 0 or (v.keeps_insert and v_auth = array['INSERT']))) then
      raise exception 'ADMIN_TABLES_WRITE_PREFLIGHT'
        using detail = format('%s: partial state, authenticated holds {%s}, %s present = %s',
                              v.tabla, array_to_string(v_auth, ','), v.politica, v_admin_all);
    end if;
  end loop;
end;
$$;

do $$
declare
  v record;
begin
  for v in select * from rb17_tablas order by tabla loop
    execute format('drop policy if exists %I on public.%I', v.politica, v.tabla);
    if v.keeps_insert then
      execute format('revoke update, delete on table public.%I from anon, authenticated', v.tabla);
      execute format('revoke insert on table public.%I from anon', v.tabla);
    else
      execute format('revoke insert, update, delete on table public.%I from anon, authenticated', v.tabla);
    end if;
  end loop;
end;
$$;

do $$
declare
  v_bad text;
begin
  select string_agg(r.rol || ' ' || p.priv || ' ' || t.tabla, ', ')
    into v_bad
  from rb17_tablas t
  cross join unnest(array['anon', 'authenticated']) as r(rol)
  cross join unnest(array['INSERT', 'UPDATE', 'DELETE']) as p(priv)
  where has_table_privilege(r.rol::name, format('public.%I', t.tabla)::regclass, p.priv)
    and not (t.keeps_insert and r.rol = 'authenticated' and p.priv = 'INSERT');
  if v_bad is null then
    select string_agg(c.relname || '.' || p.polname, ', ')
      into v_bad
    from rb17_tablas t
    join pg_class c on c.relnamespace = 'public'::regnamespace and c.relname = t.tabla
    join pg_policy p on p.polrelid = c.oid
    where p.polpermissive and p.polcmd in ('a', 'w', 'd', '*')
      and not (t.tabla = 'invitaciones_referido' and p.polname = 'invitaciones_referido_insert_propias' and p.polcmd = 'a');
  end if;
  if v_bad is not null then
    raise exception 'ADMIN_TABLES_WRITE_POSTCHECK' using detail = 'still writable by clients: ' || v_bad;
  end if;
  if not (has_table_privilege('authenticated', 'public.invitaciones_referido', 'INSERT')
          and exists (select 1 from pg_policy p where p.polrelid = 'public.invitaciones_referido'::regclass
                      and p.polname = 'invitaciones_referido_insert_propias' and p.polcmd = 'a')) then
    raise exception 'ADMIN_TABLES_WRITE_POSTCHECK' using detail = 'the user own-invitation insert path was lost';
  end if;
end;
$$;

commit;
