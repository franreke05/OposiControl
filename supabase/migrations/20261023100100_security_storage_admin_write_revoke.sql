-- =============================================================================
-- RB-16 / SEC-V1-01c -- an admin JWT can no longer write or delete Storage objects directly (Ktor is the only writer)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (owner GO required). NEW forward-only migration: no earlier file is edited.
-- Run AFTER 20261023100000 (independent of it, but same intervention family). Idempotent, one transaction.
--
-- EVIDENCE (MAIN catalog read 2026-10-19, GO-1)
--   storage.objects carries 12 permissive WRITE policies whose expressions call es_admin(): INSERT/UPDATE/DELETE on the
--   buckets exam-resources, news-assets (public bucket), question-resources and recursos_pregunta. An admin JWT (or a stolen
--   one) + the public anon key uploads, overwrites or deletes objects through the Storage API, bypassing Ktor's MIME sniffing,
--   re-encoding, path rules and audit. 11 of the 12 were created outside the repository migrations (their exact text is not
--   tracked), so this migration does NOT hard-code their bodies: it copies each one into a private backup table first and the
--   ROLLBACK recreates them from that copy.
--   No required flow: Ktor reads/writes Storage with the service_role key (bypasses RLS); the admin client and OposiBot never
--   call /storage/v1/object for writes (scripts/verify-no-direct-supabase.sh, PhaseBAccessGatePinTest).
--
-- WHAT THIS MIGRATION DOES
--   1. private.sec_v1_storage_admin_policy_backup: exact copy (name, cmd, permissive, roles, USING, WITH CHECK) of the 12.
--   2. DROP POLICY for the 12. SELECT policies (exam_resources_storage_admin_select, question_resources_storage_admin_select,
--      recursos_pregunta_storage_select_premium) and every policy not in the list are untouched. No grant is changed
--      (storage.objects grants belong to the Storage service).
--   3. POSTCHECK: no permissive INSERT/UPDATE/DELETE/ALL policy on storage.objects calls es_admin(); 12 backup rows.
--
-- PREFLIGHT (fail closed, P0001 STORAGE_ADMIN_WRITE_PREFLIGHT, nothing changed)
--   storage.objects exists with RLS on; the executing role owns it or is a member of its owner (otherwise DROP POLICY would
--   fail: NOT_VERIFIED on MAIN which role runs migrations) and can CREATE in schema private (backup table, created only after
--   the preflight passes); state is EITHER all 12 present (each a permissive write policy
--   calling es_admin()) with an empty backup, OR none present with exactly their 12 backup rows (rerun = no-op); no OTHER
--   permissive write policy on storage.objects calls es_admin() (an unknown one stops the run for review).
--
-- LOCKING
--   DROP POLICY takes ACCESS EXCLUSIVE on storage.objects until COMMIT (catalog only, milliseconds): every Storage request
--   waits for it. lock_timeout 5s: under load the migration aborts instead of queueing; retry in a quiet window.
--
-- DATA LOSS: none (no object, bucket or row of storage is touched).   INDEXES: none.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- do $$
-- declare
--   b record;
-- begin
--   if to_regclass('private.sec_v1_storage_admin_policy_backup') is null then
--     return;
--   end if;
--   for b in select * from private.sec_v1_storage_admin_policy_backup order by policyname loop
--     if not exists (select 1 from pg_policy where polrelid = 'storage.objects'::regclass and polname = b.policyname) then
--       execute format('create policy %I on storage.objects as %s for %s to %s%s%s', b.policyname, b.permissive, b.cmd,
--                      (select string_agg(case when r = 'public' then 'public' else quote_ident(r) end, ', ') from unnest(b.roles) r),
--                      case when b.qual is not null then ' using (' || b.qual || ')' else '' end,
--                      case when b.with_check is not null then ' with check (' || b.with_check || ')' else '' end);
--     end if;
--   end loop;
--   drop table private.sec_v1_storage_admin_policy_backup;
-- end $$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only)
-- VERIFY-BEGIN
-- select 'storage_admin_write_policy' as check_name, count(*)::bigint as failures, string_agg(policyname, ',') as info
-- from pg_policies
-- where schemaname = 'storage' and tablename = 'objects' and permissive = 'PERMISSIVE'
--   and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
--   and (coalesce(qual, '') ~ 'es_admin\(' or coalesce(with_check, '') ~ 'es_admin\(')
-- union all
-- select 'storage_admin_write_backup_rows',
--        (case when to_regclass('private.sec_v1_storage_admin_policy_backup') is null then 1
--              when (xpath('/row/c/text()', query_to_xml('select count(*) as c from private.sec_v1_storage_admin_policy_backup', false, true, '')))[1]::text::int = 12
--              then 0 else 1 end)::bigint, null;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
declare
  c_names constant name[] := array[
    'exam_resources_storage_admin_delete', 'exam_resources_storage_admin_insert', 'exam_resources_storage_admin_update',
    'news_assets_storage_admin_delete', 'news_assets_storage_admin_insert', 'news_assets_storage_admin_update',
    'question_resources_storage_admin_delete', 'question_resources_storage_admin_insert', 'question_resources_storage_admin_update',
    'recursos_pregunta_storage_delete_admin', 'recursos_pregunta_storage_insert_admin', 'recursos_pregunta_storage_update_admin']::name[];
  v_rel oid := to_regclass('storage.objects');
  v_owner oid;
  v_present int;
  v_good int;
  v_backup int;
  v_unknown text;
begin
  if v_rel is null or not (select relrowsecurity from pg_class where oid = v_rel) then
    raise exception 'STORAGE_ADMIN_WRITE_PREFLIGHT' using detail = 'storage.objects missing or without RLS';
  end if;
  select relowner into v_owner from pg_class where oid = v_rel;
  if not pg_has_role(current_user, v_owner, 'USAGE') then
    raise exception 'STORAGE_ADMIN_WRITE_PREFLIGHT'
      using detail = format('%I is not the owner of storage.objects nor a member of %s: DROP POLICY would fail', current_user, v_owner::regrole);
  end if;
  if to_regnamespace('private') is null or not has_schema_privilege(current_user, 'private', 'CREATE') then
    raise exception 'STORAGE_ADMIN_WRITE_PREFLIGHT' using detail = format('%I cannot create the backup table in schema private', current_user);
  end if;

  select count(*) into v_present from pg_policies
   where schemaname = 'storage' and tablename = 'objects' and policyname = any(c_names);
  select count(*) into v_good from pg_policies
   where schemaname = 'storage' and tablename = 'objects' and policyname = any(c_names) and permissive = 'PERMISSIVE'
     and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
     and (coalesce(qual, '') ~ 'es_admin\(' or coalesce(with_check, '') ~ 'es_admin\(');
  v_backup := 0;
  if to_regclass('private.sec_v1_storage_admin_policy_backup') is not null then
    execute 'select count(*) from private.sec_v1_storage_admin_policy_backup where policyname = any($1)' into v_backup using c_names;
  end if;

  if not ((v_present = 12 and v_good = 12 and v_backup = 0) or (v_present = 0 and v_backup = 12)) then
    raise exception 'STORAGE_ADMIN_WRITE_PREFLIGHT'
      using detail = format('partial or unexpected state: present=%s matching=%s backup=%s (expected 12/12/0 or 0/-/12)', v_present, v_good, v_backup);
  end if;

  select string_agg(policyname, ', ') into v_unknown from pg_policies
   where schemaname = 'storage' and tablename = 'objects' and permissive = 'PERMISSIVE' and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
     and (coalesce(qual, '') ~ 'es_admin\(' or coalesce(with_check, '') ~ 'es_admin\(')
     and not (policyname = any(c_names));
  if v_unknown is not null then
    raise exception 'STORAGE_ADMIN_WRITE_PREFLIGHT' using detail = 'unexpected admin write policies on storage.objects: ' || v_unknown;
  end if;

  if v_present = 12 then
    execute $sql$
      create table if not exists private.sec_v1_storage_admin_policy_backup (
        policyname name primary key,
        permissive text not null,
        cmd text not null,
        roles name[] not null,
        qual text,
        with_check text,
        backed_up_at timestamptz not null default now()
      )$sql$;
    execute 'revoke all on table private.sec_v1_storage_admin_policy_backup from public, anon, authenticated, service_role';
    execute $sql$
      insert into private.sec_v1_storage_admin_policy_backup (policyname, permissive, cmd, roles, qual, with_check)
      select policyname, permissive, cmd, roles, qual, with_check
        from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = any($1)$sql$ using c_names;
    for i in 1 .. array_length(c_names, 1) loop
      execute format('drop policy %I on storage.objects', c_names[i]);
    end loop;
  end if;
end;
$$;

do $$
declare
  v_bad text;
begin
  select string_agg(policyname, ', ') into v_bad from pg_policies
   where schemaname = 'storage' and tablename = 'objects' and permissive = 'PERMISSIVE' and cmd in ('INSERT', 'UPDATE', 'DELETE', 'ALL')
     and (coalesce(qual, '') ~ 'es_admin\(' or coalesce(with_check, '') ~ 'es_admin\(');
  if v_bad is null and (select count(*) from private.sec_v1_storage_admin_policy_backup) <> 12 then
    v_bad := 'backup does not hold the 12 policies';
  end if;
  if v_bad is not null then
    raise exception 'STORAGE_ADMIN_WRITE_POSTCHECK' using detail = v_bad;
  end if;
end;
$$;

commit;
