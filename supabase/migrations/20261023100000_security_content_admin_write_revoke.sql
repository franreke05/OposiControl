-- =============================================================================
-- SEC-V1-01 -- clients can no longer write content tables through the Data API; a blocked admin is no longer admin in RLS
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (owner GO required). NEW forward-only migration: no earlier file is edited.
-- Same shape as 20261008100400 (contenido_desbloqueado_usuario, applied to MAIN 2026-10-08), extended to the content
-- tables. Idempotent, one transaction.
--
-- EVIDENCE (MAIN catalog read 2026-10-19, privileges and policy names only)
--   - authenticated holds INSERT, UPDATE, DELETE on examenes, preguntas, opciones_pregunta, soluciones_pregunta,
--     preguntas_examen, recursos_pregunta, especialidades, comunidades_autonomas, etiquetas, noticia, tipo_noticia, each
--     with a permissive FOR ALL policy `*_admin_all` (es_admin()). The baseline capture shows the same shape for
--     etiquetas_pregunta and lotes_importacion_preguntas (not re-read on MAIN: PREFLIGHT fails closed if they differ).
--   - es_admin() does not look at perfiles_usuario.estado_cuenta: an admin whose account is bloqueada /
--     eliminacion_pendiente is still admin for RLS, while Ktor (hasActiveAdminRole) already refuses it.
--   - perfiles_usuario_update_propio is `own row OR es_admin()`: any admin can rewrite another user's profile row.
--   - So an admin JWT (or a stolen one) + the public anon key changes prices, access type, questions, solutions, news
--     and other users' profiles through PostgREST, bypassing Ktor validation, auditoria_log and the admin rate limit.
--   - No required flow: Ktor writes every one of these tables with the service_role key (user JWTs only reach /auth/v1,
--     AdminAccessRequests.kt:1629); the admin client is guarded by scripts/verify-no-direct-supabase.sh and the OposiBot
--     client by PhaseBAccessGatePinTest (no /rest/v1 for business data).
--
-- WHAT THIS MIGRATION DOES
--   1. For each of the 13 tables: DROP POLICY <table>_admin_all; REVOKE INSERT, UPDATE, DELETE FROM anon, authenticated.
--      SELECT policies and SELECT grants are untouched (soluciones_pregunta has no SELECT policy: clients read none).
--   2. es_admin() additionally requires perfiles_usuario.estado_cuenta = 'activa' (same rule as Ktor).
--   3. perfiles_usuario_update_propio becomes own-row only (the `OR es_admin()` branch is removed).
--   4. POSTCHECK: no client write privilege, no permissive write policy on the 13 tables, es_admin() has the new body,
--      perfiles_usuario_update_propio has no es_admin(); otherwise P0001 CONTENT_ADMIN_WRITE_POSTCHECK and rollback.
--   UNCHANGED: every service_role privilege, every SELECT policy, es_moderador_o_admin(), sesiones_estudio and
--   abandonos_examen policies (residual, see SECURITY_AUDIT SEC-V1-01b), rows of any table.
--
-- PREFLIGHT (fail closed, P0001 CONTENT_ADMIN_WRITE_PREFLIGHT, nothing dropped or revoked)
--   Roles, tables and perfiles_usuario.estado_cuenta exist; each table is EITHER in the MAIN state (authenticated holds
--   exactly INSERT+UPDATE+DELETE granted by the owner without grant option, and *_admin_all is exactly permissive ALL to
--   authenticated with es_admin()) OR in the applied state (no write grant, no *_admin_all); anon/PUBLIC hold no write
--   ACL; es_admin() body is the MAIN body or the new body; perfiles_usuario_update_propio is the MAIN or the new form.
--
-- FORWARD PLAN
--   Order-independent from Ktor and from the V1 migrations (no build writes these tables with a user JWT). MAIN only
--   with the owner's GO, after the read-only VERIFY below has been run and reviewed.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below re-grants INSERT, UPDATE, DELETE to authenticated on the 13 tables, recreates the 13 policies,
--   restores the MAIN es_admin() body and the MAIN perfiles_usuario_update_propio. Idempotent. Re-opens the path.
--
-- LOCKING
--   DROP/ALTER POLICY take ACCESS EXCLUSIVE on each table until COMMIT (catalog only, milliseconds). lock_timeout 5s:
--   under traffic the migration aborts instead of queueing reads; retry in a quiet window.
--
-- DATA LOSS: none.   INDEXES: none.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- do $$
-- declare
--   v record;
-- begin
--   for v in select * from (values
--       ('examenes', 'examenes_admin_all'), ('preguntas', 'preguntas_admin_all'),
--       ('opciones_pregunta', 'opciones_pregunta_admin_all'), ('soluciones_pregunta', 'soluciones_pregunta_admin_all'),
--       ('preguntas_examen', 'preguntas_examen_admin_all'), ('recursos_pregunta', 'recursos_pregunta_admin_all'),
--       ('especialidades', 'especialidades_admin_all'), ('comunidades_autonomas', 'comunidades_admin_all'),
--       ('etiquetas', 'etiquetas_admin_all'), ('etiquetas_pregunta', 'etiquetas_pregunta_admin_all'),
--       ('lotes_importacion_preguntas', 'lotes_importacion_admin_all'), ('noticia', 'noticia_admin_all'),
--       ('tipo_noticia', 'tipo_noticia_admin_all')) as t(tabla, politica)
--   loop
--     execute format('grant insert, update, delete on table public.%I to authenticated', v.tabla);
--     if not exists (select 1 from pg_policy p where p.polrelid = format('public.%I', v.tabla)::regclass and p.polname = v.politica) then
--       execute format('create policy %I on public.%I as permissive for all to authenticated using (public.es_admin()) with check (public.es_admin())',
--                      v.politica, v.tabla);
--     end if;
--   end loop;
-- end $$;
-- create or replace function public.es_admin() returns boolean
-- language sql stable security definer set search_path to ''
-- as $f$
--   select exists (
--     select 1
--     from public.roles_usuario ru
--     join public.roles_app ra on ra.id = ru.rol_id
--     where ru.usuario_id = auth.uid()
--       and ra.codigo in ('superadmin', 'admin')
--   );
-- $f$;
-- alter policy perfiles_usuario_update_propio on public.perfiles_usuario
--   using (((usuario_id = (select auth.uid())) or public.es_admin()))
--   with check (((usuario_id = (select auth.uid())) or public.es_admin()));
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- with t(tabla) as (values ('examenes'), ('preguntas'), ('opciones_pregunta'), ('soluciones_pregunta'), ('preguntas_examen'),
--   ('recursos_pregunta'), ('especialidades'), ('comunidades_autonomas'), ('etiquetas'), ('etiquetas_pregunta'),
--   ('lotes_importacion_preguntas'), ('noticia'), ('tipo_noticia'))
-- select 'content_client_write_privilege' as check_name, count(*)::bigint as failures,
--        string_agg(r.rol || ' ' || p.priv || ' ' || t.tabla, ', ') as info
-- from t cross join unnest(array['anon', 'authenticated']) as r(rol) cross join unnest(array['INSERT', 'UPDATE', 'DELETE']) as p(priv)
-- where has_table_privilege(r.rol::name, format('public.%I', t.tabla)::regclass, p.priv)
-- union all
-- select 'content_client_write_policy', count(*)::bigint, string_agg(c.relname || '.' || p.polname, ',')
-- from t join pg_class c on c.relnamespace = 'public'::regnamespace and c.relname = t.tabla
-- join pg_policy p on p.polrelid = c.oid
-- where p.polpermissive and p.polcmd in ('a', 'w', 'd', '*')
-- union all
-- select 'content_service_role_write_missing', count(*)::bigint, string_agg(p.priv || ' ' || t.tabla, ',')
-- from t cross join unnest(array['SELECT', 'INSERT', 'UPDATE', 'DELETE']) as p(priv)
-- where not has_table_privilege('service_role', format('public.%I', t.tabla)::regclass, p.priv)
-- union all
-- select 'es_admin_ignores_account_state',
--        (case when pg_get_functiondef('public.es_admin()'::regprocedure) ~ 'estado_cuenta' then 0 else 1 end)::bigint, null
-- union all
-- select 'perfiles_update_admin_branch', count(*)::bigint, string_agg(p.polname, ',')
-- from pg_policy p
-- where p.polrelid = 'public.perfiles_usuario'::regclass and p.polcmd = 'w'
--   and (coalesce(pg_get_expr(p.polqual, p.polrelid), '') ~ 'es_admin' or coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') ~ 'es_admin');
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create temporary table sec_v1_01_tablas (tabla text primary key, politica text not null) on commit drop;
insert into sec_v1_01_tablas values
  ('examenes', 'examenes_admin_all'), ('preguntas', 'preguntas_admin_all'),
  ('opciones_pregunta', 'opciones_pregunta_admin_all'), ('soluciones_pregunta', 'soluciones_pregunta_admin_all'),
  ('preguntas_examen', 'preguntas_examen_admin_all'), ('recursos_pregunta', 'recursos_pregunta_admin_all'),
  ('especialidades', 'especialidades_admin_all'), ('comunidades_autonomas', 'comunidades_admin_all'),
  ('etiquetas', 'etiquetas_admin_all'), ('etiquetas_pregunta', 'etiquetas_pregunta_admin_all'),
  ('lotes_importacion_preguntas', 'lotes_importacion_admin_all'), ('noticia', 'noticia_admin_all'),
  ('tipo_noticia', 'tipo_noticia_admin_all');

do $$
declare
  v record;
  v_rel oid;
  v_owner oid;
  v_admin_all boolean;
  v_auth text[];
  v_bad text;
  v_body text;
  v_qual text;
  v_check text;
  c_old_body constant text := 'select exists ( select 1 from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id where ru.usuario_id = auth.uid() and ra.codigo in (''superadmin'', ''admin'') );';
  c_new_body constant text := 'select exists ( select 1 from public.roles_usuario ru join public.roles_app ra on ra.id = ru.rol_id join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id where ru.usuario_id = auth.uid() and ra.codigo in (''superadmin'', ''admin'') and pu.estado_cuenta = ''activa'' );';
begin
  if to_regrole('anon') is null or to_regrole('authenticated') is null or to_regrole('service_role') is null then
    raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT' using detail = 'roles anon/authenticated/service_role missing';
  end if;
  if not exists (select 1 from pg_attribute a where a.attrelid = to_regclass('public.perfiles_usuario')
                 and a.attname = 'estado_cuenta' and not a.attisdropped) then
    raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT' using detail = 'public.perfiles_usuario.estado_cuenta is required';
  end if;

  for v in select * from sec_v1_01_tablas order by tabla loop
    v_rel := to_regclass(format('public.%I', v.tabla));
    if v_rel is null then
      raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT' using detail = 'table public.' || v.tabla || ' missing';
    end if;
    select c.relowner into v_owner from pg_class c where c.oid = v_rel;

    v_admin_all := exists (select 1 from pg_policy p where p.polrelid = v_rel and p.polname = v.politica);
    if v_admin_all and not exists (
         select 1 from pg_policy p
         where p.polrelid = v_rel and p.polname = v.politica and p.polpermissive and p.polcmd = '*'
           and p.polroles = array['authenticated'::regrole::oid]
           and pg_get_expr(p.polqual, p.polrelid) ~ '^(public\.)?es_admin\(\)$'
           and pg_get_expr(p.polwithcheck, p.polrelid) ~ '^(public\.)?es_admin\(\)$') then
      raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT'
        using detail = v.politica || ' differs from MAIN (permissive ALL to authenticated, es_admin())';
    end if;

    select string_agg(coalesce(ro.rolname::text, 'PUBLIC') || ' ' || a.privilege_type, ', ')
      into v_bad
    from pg_class c
    cross join lateral aclexplode(c.relacl) a
    left join pg_roles ro on ro.oid = a.grantee
    where c.oid = v_rel and a.privilege_type in ('INSERT', 'UPDATE', 'DELETE')
      and (a.grantee = 0 or ro.rolname = 'anon');
    if v_bad is not null then
      raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT' using detail = v.tabla || ': write privilege held by ' || v_bad;
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
        raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT'
          using detail = v.tabla || ': not granted by the owner, with grant option or owner not usable: ' || v_bad;
      end if;
    elsif not (cardinality(v_auth) = 0 and not v_admin_all) then
      raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT'
        using detail = format('%s: partial state, authenticated holds {%s}, %s present = %s',
                              v.tabla, array_to_string(v_auth, ','), v.politica, v_admin_all);
    end if;
  end loop;

  select btrim(regexp_replace(p.prosrc, '\s+', ' ', 'g')) into v_body
  from pg_proc p where p.oid = to_regprocedure('public.es_admin()');
  if v_body is null or v_body not in (c_old_body, c_new_body) then
    raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT' using detail = 'es_admin() body differs from MAIN: ' || coalesce(v_body, '<missing>');
  end if;

  select pg_get_expr(p.polqual, p.polrelid), pg_get_expr(p.polwithcheck, p.polrelid) into v_qual, v_check
  from pg_policy p
  where p.polrelid = 'public.perfiles_usuario'::regclass and p.polname = 'perfiles_usuario_update_propio'
    and p.polpermissive and p.polcmd = 'w' and p.polroles = array['authenticated'::regrole::oid];
  if v_qual is null
     or not ((v_qual = '((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())'
              and v_check = '((usuario_id = ( SELECT auth.uid() AS uid)) OR es_admin())')
          or (v_qual = '(usuario_id = ( SELECT auth.uid() AS uid))'
              and v_check = '(usuario_id = ( SELECT auth.uid() AS uid))')) then
    raise exception 'CONTENT_ADMIN_WRITE_PREFLIGHT'
      using detail = format('perfiles_usuario_update_propio differs from MAIN: using %s check %s', v_qual, v_check);
  end if;
end;
$$;

do $$
declare
  v record;
begin
  for v in select * from sec_v1_01_tablas order by tabla loop
    execute format('drop policy if exists %I on public.%I', v.politica, v.tabla);
    execute format('revoke insert, update, delete on table public.%I from anon, authenticated', v.tabla);
  end loop;
end;
$$;

create or replace function public.es_admin() returns boolean
language sql stable security definer set search_path to ''
as $f$
  select exists (
    select 1
    from public.roles_usuario ru
    join public.roles_app ra on ra.id = ru.rol_id
    join public.perfiles_usuario pu on pu.usuario_id = ru.usuario_id
    where ru.usuario_id = auth.uid()
      and ra.codigo in ('superadmin', 'admin')
      and pu.estado_cuenta = 'activa'
  );
$f$;

alter policy perfiles_usuario_update_propio on public.perfiles_usuario
  using ((usuario_id = (select auth.uid())))
  with check ((usuario_id = (select auth.uid())));

do $$
declare
  v_bad text;
begin
  select string_agg(r.rol || ' ' || p.priv || ' ' || t.tabla, ', ')
    into v_bad
  from sec_v1_01_tablas t
  cross join unnest(array['anon', 'authenticated']) as r(rol)
  cross join unnest(array['INSERT', 'UPDATE', 'DELETE']) as p(priv)
  where has_table_privilege(r.rol::name, format('public.%I', t.tabla)::regclass, p.priv);
  if v_bad is null then
    select string_agg(c.relname || '.' || p.polname, ', ')
      into v_bad
    from sec_v1_01_tablas t
    join pg_class c on c.relnamespace = 'public'::regnamespace and c.relname = t.tabla
    join pg_policy p on p.polrelid = c.oid
    where p.polpermissive and p.polcmd in ('a', 'w', 'd', '*');
  end if;
  if v_bad is null and pg_get_functiondef('public.es_admin()'::regprocedure) !~ 'estado_cuenta' then
    v_bad := 'es_admin() still ignores estado_cuenta';
  end if;
  if v_bad is null and exists (
       select 1 from pg_policy p
       where p.polrelid = 'public.perfiles_usuario'::regclass and p.polcmd = 'w'
         and (coalesce(pg_get_expr(p.polqual, p.polrelid), '') ~ 'es_admin'
              or coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') ~ 'es_admin')) then
    v_bad := 'perfiles_usuario update policy still has an es_admin() branch';
  end if;
  if v_bad is not null then
    raise exception 'CONTENT_ADMIN_WRITE_POSTCHECK' using detail = 'still writable by clients: ' || v_bad;
  end if;
end;
$$;

commit;
