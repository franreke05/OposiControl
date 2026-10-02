-- =============================================================================
-- OWNER DECISION 4 (2026-10-02) -- revoke TRUNCATE, TRIGGER and REFERENCES on public relations from anon/authenticated
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). NEW forward-only hardening migration (deliberately NOT folded
-- into 20260930102000 or any earlier file; nothing already written is edited). Privileges only: no column, policy,
-- function, index or row of any business table changes. Idempotent, one transaction.
--
-- EVIDENCE (baseline_main.sql, the READ-ONLY catalog capture of MAIN, section 10 "REVOKE/GRANT state")
--   Effective privileges of the client roles (Postgres 17.6; RLS does not apply to TRUNCATE, TRIGGER or REFERENCES):
--   - authenticated holds DELETE, INSERT, MAINTAIN, REFERENCES, SELECT, TRIGGER, TRUNCATE, UPDATE on exactly 42 public
--     tables (all RLS-enabled base tables, no views): abandonos_examen, acciones_moderacion (line 3820),
--     cola_repaso_usuario, comentarios_oposired, comunidades_autonomas, configuraciones_usuario,
--     contenido_desbloqueado_usuario, conversaciones_soporte, errores_app, especialidades, etiquetas, etiquetas_opositeca,
--     etiquetas_pregunta, eventos_conversion_premium, examenes, feedback_examen, feedback_pregunta, invitaciones_referido,
--     lotes_actualizacion_ranking, lotes_importacion_preguntas, mensajes_soporte, metricas_rendimiento_app, noticia
--     (3986), opciones_pregunta, opositeca_items, perfiles_usuario, preguntas, preguntas_examen,
--     preguntas_favoritas_usuario, preguntas_parseadas_staging, publicaciones_oposired, reacciones_oposired,
--     recomendaciones_usuario, recursos_pregunta, registros_reto_diario, reportes_contenido (4076), roles_usuario (4098),
--     sesiones_estudio, soluciones_pregunta, tipo_noticia (4119), tokens_dispositivo, uso_recursos_pregunta.
--   - anon holds the same eight privileges on noticia (3985) and tipo_noticia (4118); on the other 40 tables anon holds
--     MAINTAIN and SELECT only. No other public relation grants TRUNCATE/TRIGGER/REFERENCES to anon or authenticated
--     (the rest grant them to service_role only), PUBLIC holds nothing on any table, and every grant comes after a
--     `REVOKE ALL ... FROM PUBLIC, anon, authenticated, service_role` (so it is the whole ACL of the client role).
--   - What each one allows a session running AS authenticated (or anon on the two news tables), which dbtest proves with
--     SET ROLE before this migration: TRUNCATE empties the table and BYPASSES RLS (every row of every user); TRIGGER
--     lets it CREATE TRIGGER on the table with any trigger function it may EXECUTE (functions default to EXECUTE for
--     PUBLIC); REFERENCES lets a table it owns declare a FOREIGN KEY to the table (latent in MAIN: the client roles have
--     USAGE but no CREATE on public, line 4277, and private is closed, 4278). None of the three is a row operation,
--     so no RLS policy limits them.
--   - Reachability: the client roles are NOLOGIN; clients reach the database only through the Data API (PostgREST),
--     which never issues TRUNCATE, CREATE TRIGGER or a FOREIGN KEY. A SECURITY INVOKER function called by a client
--     would run them with the client's privileges: the only TRUNCATE in MAIN functions is in the statistics
--     recalculation functions, on tables (estadisticas_globales_*) where only service_role holds TRUNCATE.
--   - No required flow (searched OposiControl Ktor, the admin client and the OposiBot client): Ktor uses the
--     service_role key for every /rest/v1 call (user tokens only reach /auth/v1), and the clients never call the Data
--     API for business data (scripts/verify-no-direct-supabase.sh). FOREIGN KEY checks and triggers run with the owner's
--     rights, so revoking REFERENCES/TRIGGER from the client roles changes no INSERT/UPDATE/DELETE they perform.
--
-- WHAT THIS MIGRATION DOES
--   1. Creates private.slice1_client_ddl_grants_revoked (relation, grantee, privilege, revoked_en): the exact record of
--      what this migration revoked, so the ROLLBACK restores exactly that and nothing else. RLS on, no grant to any
--      API role (private is closed to clients anyway).
--   2. For every relation of schema public (tables, partitioned tables, views, materialized views, foreign tables) and
--      every ACL entry that grants TRUNCATE, TRIGGER or REFERENCES to anon or authenticated: records it and REVOKEs it.
--      On MAIN this is the 42 x 3 authenticated entries and 2 x 3 anon entries listed above (132 rows). The selection
--      is by catalog, not by a hard-coded list, so a table missing from one environment is never an error and a table
--      with the same defect is never skipped.
--   3. POSTCHECK: has_table_privilege() must be false for anon and authenticated for TRUNCATE, TRIGGER and REFERENCES on
--      every relation of public (effective privilege: includes PUBLIC and role membership). Otherwise P0001
--      CLIENT_DDL_PRIVILEGES_POSTCHECK and the whole transaction rolls back.
--   UNCHANGED: SELECT/INSERT/UPDATE/DELETE of the client roles (still behind RLS), MAINTAIN (PG17: VACUUM/ANALYZE/
--   REINDEX/LOCK TABLE/REFRESH; not part of owner decision 4, also unreachable through PostgREST; declared residual),
--   every service_role privilege, every policy, schema USAGE and the default privileges (see RESIDUALS).
--
-- PREFLIGHT (fail closed, P0001 CLIENT_DDL_PRIVILEGES_PREFLIGHT, nothing created or revoked)
--   - roles anon and authenticated and schema private exist;
--   - an existing private.slice1_client_ddl_grants_revoked (rerun) has exactly the columns created here;
--   - every client ACL entry to revoke was granted by the relation OWNER, WITHOUT grant option, and the migration role
--     acts as that owner (pg_has_role USAGE). Otherwise the REVOKE would be a silent no-op (it only removes grants made
--     by the owner) or could not be restored exactly: e.g. a table owned by supabase_admin, or a grant made by another
--     grantor, aborts the apply and the detail names relation, role and privilege;
--   - PUBLIC holds none of the three privileges on a public relation (revoking from the client roles would not remove
--     it; MAIN revokes everything from PUBLIC).
--
-- HISTORICAL DATA
--   None. No row is read, written or deleted; only ACLs change.
--
-- FORWARD PLAN
--   Order-independent from Ktor: no Ktor build, OposiBot or admin client uses these privileges, so it can be applied
--   with 1..13 or on its own; it does not change the Ktor x DB compatibility matrix. MAIN only at the dedicated apply
--   gate. No PostgREST schema reload is needed (PostgREST does not expose these privileges).
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below: GRANTs back exactly the rows recorded in private.slice1_client_ddl_grants_revoked (same
--   relation, grantee and privilege; the grantor is again the owner, as before, so each ACL text is byte-identical:
--   the entry is modified in place), skips relations dropped since, then drops the record table. Idempotent (no record
--   table = nothing to do). Rolling back re-opens TRUNCATE (RLS bypass) and CREATE TRIGGER for the client roles.
--
-- LOCKING
--   GRANT/REVOKE on a relation is a catalog-only update of its pg_class row (relacl): no table rewrite, no scan, no
--   ACCESS EXCLUSIVE lock on the business table (dbtest `elRevokeNoTomaCerrojoSobreLasTablasDeNegocio` measures
--   pg_locks inside the open transaction: no lock on any revoked table). Concurrent sessions keep reading and writing;
--   a concurrent GRANT/REVOKE/ALTER of the same table waits on the catalog row until COMMIT. `set local lock_timeout`
--   bounds every wait (the CREATE TABLE of the record table and the catalog rows): the migration fails instead of
--   queueing (retry it).
--
-- DATA LOSS
--   None. ROLLBACK drops only the record table created here (after restoring from it).
--
-- INDEXES
--   Only the primary key of the new record table.
--
-- RLS AND GRANTS
--   Narrowing only: three privileges revoked from anon/authenticated on public relations; no grant added or widened, no
--   policy touched. Record table: RLS enabled, REVOKE ALL from PUBLIC, anon, authenticated and service_role (owner only).
--
-- RESIDUALS (declared; SLICE_1_PRODUCTION_GATE section 8 checklist)
--   - Default privileges: MAIN has `ALTER DEFAULT PRIVILEGES FOR ROLE postgres` and `FOR ROLE supabase_admin IN SCHEMA
--     public GRANT ALL ON TABLES TO ... anon, authenticated` (baseline_main.sql 4280 and 4283). A table created LATER
--     by either role gets TRUNCATE/TRIGGER/REFERENCES (and everything else) for the client roles again. The migration
--     role cannot alter the supabase_admin defaults, and changing the postgres defaults is outside owner decision 4.
--     Mitigation: every new table migration revokes explicitly (repo convention, e.g. 20260930100300), the VERIFY row
--     client_ddl_privilege_effective flags any such table at the next gate run, and rerunning this migration revokes
--     and records it.
--   - MAINTAIN stays granted to anon and authenticated (see UNCHANGED).
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- do $$
-- declare
--   r record;
-- begin
--   if to_regclass('private.slice1_client_ddl_grants_revoked') is null then
--     return;
--   end if;
--   for r in execute 'select relation_name, grantee, privilege from private.slice1_client_ddl_grants_revoked order by 1, 2, 3' loop
--     if to_regclass(r.relation_name) is not null then
--       execute format('grant %s on table %s to %I', r.privilege, r.relation_name, r.grantee);
--     end if;
--   end loop;
-- end $$;
-- drop table if exists private.slice1_client_ddl_grants_revoked;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'client_ddl_privilege_effective' as check_name, count(*)::bigint as failures,
--        string_agg(format('%I.%I %s %s', n.nspname, c.relname, r.rol, p.priv), ', ') as info
-- from pg_class c
-- join pg_namespace n on n.oid = c.relnamespace
-- cross join unnest(array['anon', 'authenticated']) as r(rol)
-- cross join unnest(array['TRUNCATE', 'TRIGGER', 'REFERENCES']) as p(priv)
-- where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'f')
--   and has_table_privilege(r.rol::name, c.oid, p.priv)
-- union all
-- select 'client_ddl_revocation_record_missing',
--        (case when to_regclass('private.slice1_client_ddl_grants_revoked') is not null then 0 else 1 end)::bigint, null
-- union all
-- select 'client_ddl_revocation_record_exposed', count(*)::bigint, null
-- from pg_class c
-- where c.oid = to_regclass('private.slice1_client_ddl_grants_revoked')
--   and (not c.relrowsecurity
--        or exists (select 1 from unnest(array['anon', 'authenticated', 'service_role']) as r(rol)
--                   where has_table_privilege(r.rol::name, c.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')));
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  v_bad text;
begin
  if to_regrole('anon') is null or to_regrole('authenticated') is null or to_regnamespace('private') is null then
    raise exception 'CLIENT_DDL_PRIVILEGES_PREFLIGHT'
      using detail = 'roles anon/authenticated or schema private missing';
  end if;

  -- Rerun: the record table must be exactly ours.
  if to_regclass('private.slice1_client_ddl_grants_revoked') is not null
     and (select string_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod) || ':' || a.attnotnull::text, ',' order by a.attnum)
          from pg_attribute a
          where a.attrelid = to_regclass('private.slice1_client_ddl_grants_revoked') and a.attnum > 0 and not a.attisdropped)
        is distinct from 'relation_name:text:true,grantee:text:true,privilege:text:true,revoked_en:timestamp with time zone:true' then
    raise exception 'CLIENT_DDL_PRIVILEGES_PREFLIGHT'
      using detail = 'private.slice1_client_ddl_grants_revoked exists with another shape';
  end if;

  -- Every entry to revoke must be revocable by this role and restorable exactly.
  select string_agg(format('%I.%I %s %s', n.nspname, c.relname, ro.rolname, a.privilege_type), ', ' order by c.relname)
    into v_bad
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  cross join lateral aclexplode(c.relacl) a
  join pg_roles ro on ro.oid = a.grantee
  where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'f')
    and ro.rolname in ('anon', 'authenticated')
    and a.privilege_type in ('TRUNCATE', 'TRIGGER', 'REFERENCES')
    and (a.grantor <> c.relowner or a.is_grantable or not pg_has_role(current_user, c.relowner, 'USAGE'));
  if v_bad is not null then
    raise exception 'CLIENT_DDL_PRIVILEGES_PREFLIGHT'
      using detail = 'not granted by the owner, with grant option or owner not usable: ' || v_bad;
  end if;

  select string_agg(format('%I.%I %s', n.nspname, c.relname, a.privilege_type), ', ' order by c.relname)
    into v_bad
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  cross join lateral aclexplode(c.relacl) a
  where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'f')
    and a.grantee = 0 and a.privilege_type in ('TRUNCATE', 'TRIGGER', 'REFERENCES');
  if v_bad is not null then
    raise exception 'CLIENT_DDL_PRIVILEGES_PREFLIGHT'
      using detail = 'granted to PUBLIC: ' || v_bad;
  end if;
end;
$$;

create table if not exists private.slice1_client_ddl_grants_revoked (
  relation_name text not null,
  grantee text not null,
  privilege text not null,
  revoked_en timestamptz not null default now(),
  primary key (relation_name, grantee, privilege)
);
alter table private.slice1_client_ddl_grants_revoked enable row level security;
revoke all on table private.slice1_client_ddl_grants_revoked from public, anon, authenticated, service_role;

do $$
declare
  r record;
  v_bad text;
begin
  for r in
    select format('%I.%I', n.nspname, c.relname) as relation_name, ro.rolname::text as grantee, a.privilege_type as privilege
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    cross join lateral aclexplode(c.relacl) a
    join pg_roles ro on ro.oid = a.grantee
    where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'f')
      and ro.rolname in ('anon', 'authenticated')
      and a.privilege_type in ('TRUNCATE', 'TRIGGER', 'REFERENCES')
    order by 1, 2, 3
  loop
    insert into private.slice1_client_ddl_grants_revoked (relation_name, grantee, privilege)
    values (r.relation_name, r.grantee, r.privilege)
    on conflict (relation_name, grantee, privilege) do nothing;
    execute format('revoke %s on table %s from %I', r.privilege, r.relation_name, r.grantee);
  end loop;

  -- POSTCHECK: effective privilege (PUBLIC and membership included) is gone everywhere in public.
  select string_agg(format('%I.%I %s %s', n.nspname, c.relname, ro.rol, p.priv), ', ' order by c.relname)
    into v_bad
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
  cross join unnest(array['anon', 'authenticated']) as ro(rol)
  cross join unnest(array['TRUNCATE', 'TRIGGER', 'REFERENCES']) as p(priv)
  where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'f')
    and has_table_privilege(ro.rol::name, c.oid, p.priv);
  if v_bad is not null then
    raise exception 'CLIENT_DDL_PRIVILEGES_POSTCHECK'
      using detail = 'still effective: ' || v_bad;
  end if;
end;
$$;

commit;
