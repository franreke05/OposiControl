-- =============================================================================
-- SLICE 0 / S0-DB -- Canonical identity EXPAND + legacy public.usuario BACKFILL
-- =============================================================================
-- Canonical identity: auth.users.id (uuid) = perfiles_usuario.usuario_id = every
-- domain FK usuario_id. Legacy public.usuario (int PK, auth_user_id without FK)
-- is written only by EP-218 (rpc oposibots_upsert_own_profile) and read by
-- EP-124 and admin EP-070/071. DEC-001 ADOPTED.
--
-- WHAT THIS MIGRATION DOES (additive only)
--   1. perfiles_usuario gains nombre, apellidos (nullable, <=100 = legacy
--      varchar(100)), estado_cuenta (not null default 'activa', check
--      activa|bloqueada|eliminacion_pendiente), perfil_intro_visto_en (nullable).
--   2. Guard trigger: anon/authenticated (direct PostgREST) cannot set or change
--      estado_cuenta. It is ADMIN_DERIVED/SERVER_DERIVED; MAIN grants table-level
--      UPDATE on perfiles_usuario to authenticated and the update_propio RLS policy
--      would otherwise let a blocked user unblock themselves. Ktor (service_role)
--      and SECURITY DEFINER RPCs are unaffected. The same guard also makes a profile
--      whose estado_cuenta is not 'activa' (eliminacion_pendiente, bloqueada) READ-ONLY
--      for anon/authenticated (UPDATE -> 42501 ESTADO_CUENTA_NOT_ACTIVE, PF3-3-02), so a
--      still-valid access token cannot rewrite it through PostgREST. SCOPE: only
--      perfiles_usuario. Legacy /api/app/v1 routes and other own-data tables are not
--      covered until Slice 1 (see A1/SLICE_0 docs).
--   3. private.identity_backfill_report (usuario_id, legacy_usuario_id, field,
--      outcome, detail) -- holds the LATEST run only. Owner-only: no grant to
--      service_role/anon/authenticated (operators read it as the migration role).
--   4. The identity BACKFILL, executed INSIDE this migration as an anonymous DO
--      block under the migration's privileged role (postgres). It is idempotent
--      and atomic with the migration transaction. It is NOT a function: there is
--      NO runtime backfill routine (Gate A' D1 / FIX-D1). Rationale: it reads
--      auth.users, which service_role cannot SELECT (MAIN included), and nothing
--      at runtime needs it (no Ktor/OpenAPI/OposiBot caller; only this file, the
--      dbtest suite and docs mention it). Making it a service_role-callable
--      routine would need either SELECT on auth.users for service_role (rejected)
--      or a SECURITY DEFINER surface that is unnecessary at runtime. On MAIN
--      public.usuario is expected to hold 0 rows, so the block is a no-op there;
--      it does not touch auth.users at all when public.usuario is empty.
--   5. VERIFY is a plain, documented, read-only SQL snippet (VERIFY-BEGIN/END
--      below), run by an operator as the migration role; every row must report
--      failures = 0. It is not a granted function. The dbtest suite executes it
--      verbatim.
--   Legacy draft objects private.identity_backfill_run()/identity_backfill_verify()
--   /identity_norm_text(text) (never applied to MAIN) are dropped if present, so
--   a local/branch database that received the draft ends up in the same state.
--
-- BACKFILL RULES
--   Eligible = public.usuario rows with deleted_at null AND auth_user_id present
--   in auth.users. For each: ensure a perfiles_usuario row exists; fill nombre,
--   apellidos, username, especialidad_principal_id ONLY where the target IS NULL
--   (never overwrite canonical data; a differing non-null target is reported as
--   conflict_kept_existing). especialidad_registro -> especialidades by exact
--   trim + case + accent-insensitive match on nombre or slug; 0 matches ->
--   specialty_unmatched, >1 -> specialty_ambiguous (left null, reported).
--   perfil_completo=true sets onboarding_completado=true (never downgraded).
--   Username held (case-insensitively) by another profile -> field skipped and
--   reported (skipped_username_collision). Orphans (auth_user_id null, unknown
--   in auth.users, or deleted_at set) are report-only.
--   Accent folding uses translate(), not the unaccent extension (not installed
--   on MAIN; adding an extension is out of scope for SLICE 0).
--   The block serializes with pg_advisory_xact_lock and takes a row lock per
--   profile (FOR UPDATE); it emits one NOTICE per outcome with its count.
--
-- FORWARD PLAN
--   SLICE 0: this EXPAND + in-migration backfill (+ VERIFY snippet) on local/branch.
--   SLICE 1 (one deploy): final delta re-run just before the deploy = re-execute
--   this same idempotent DO block as the migration role (a follow-up migration
--   or operator script that repeats the block verbatim; re-applying this file
--   is also safe). Still NO runtime routine and NO grant for it. EP-218/EP-124
--   become thin adapters over the canonical profile service (D1-12/D1-11)
--   reading/writing perfiles_usuario only; oposibots_upsert_own_profile is no
--   longer called. NO DUAL-WRITE: a single legacy writer switched atomically in
--   one deploy; the re-runnable backfill closes the window.
--   CONTRACT (later, gated): once the OB release using D1-11/D1-12 is the
--   minimum supported version, legacy_route_requests_total = 0 for EP-124/218
--   and VERIFY is green -> 008_legacy_cleanup_final_gate drops
--   current_usuario_id, oposibots_upsert_own_profile and public.usuario
--   (human gate + empty-table assertion).
--
-- COMPATIBILITY PERIOD
--   public.usuario, its RLS, grants and oposibots_upsert_own_profile are NOT
--   touched; legacy reads/writes keep working until the SLICE 1 cutover. New
--   columns are nullable or defaulted, so existing inserts (handle_new_auth_user
--   trigger, Ktor, OC) are unaffected. No FK or ON DELETE change.
--   DEC-008 decided: the deletion executor (Slice 1) must DISABLE the Auth identity
--   (ban / revoke sessions) and SCRUB it while banned (email to a non-deliverable
--   tombstone, phone and user_metadata cleared, identities unlinked; auth class
--   ANONYMIZE), and never DELETE FROM auth.users while retained-class
--   tables still have ON DELETE CASCADE to auth.users. Retained tables are
--   perfiles_usuario, intentos_examen and answers, compras_usuario, carteras_usuario
--   and the wallet ledger. A contract migration must change those FKs before any
--   hard delete.
--
-- LOCKING RISK
--   ADD COLUMN with a constant default is metadata-only (PG11+) but takes an
--   ACCESS EXCLUSIVE lock on perfiles_usuario for the duration of the
--   transaction, plus one validation scan for the two CHECKs on estado_cuenta
--   and name lengths (all rows are 'activa'/null, so the scan cannot fail).
--   lock_timeout = 5s makes the migration fail fast instead of queueing behind
--   long transactions; simply retry. The backfill block takes row locks only
--   (FOR UPDATE per profile, plus one transaction-scoped advisory lock) and no
--   table locks beyond the ACCESS EXCLUSIVE above; it runs in the same
--   transaction, so on MAIN (0 legacy rows) it adds no lock time.
--
-- DATA-LOSS RISK
--   Migration: none (additive). Backfill: none -- it only fills NULL targets and
--   never deletes or downgrades; public.usuario is read-only for it. The whole
--   file is one transaction: any error rolls back columns, trigger and backfill.
--   Rollback: drops the four new columns; nombre/apellidos copied from legacy can
--   be re-derived by re-applying this file while public.usuario exists, but
--   values written natively after cutover and estado_cuenta states WOULD be lost.
--   Do not roll back after the SLICE 1 cutover without exporting them first.
--   EXPORT estado_cuenta BEFORE ANY ROLLBACK (Gate A' D2): dropping the column and
--   re-applying this file resets every account to 'activa', so bloqueada and
--   eliminacion_pendiente would silently become active again. Export
--   (usuario_id, estado_cuenta) where estado_cuenta <> 'activa' first and restore
--   it after re-applying (as service_role/postgres; clients cannot write it).
--
-- LEGACY SOFT-DELETED ROWS (Gate A' D3)
--   public.usuario rows with deleted_at set are REPORT-ONLY (orphan_deleted): the
--   backfill does not copy their data and does NOT map them to
--   estado_cuenta = 'eliminacion_pendiente'. Whether a legacy soft-delete means a
--   pending DEC-008 deletion (anonymize/retain classes, DELETE /api/v1/app/me,
--   D1-19, 202 async) is a SLICE 1 decision; nothing here pre-empts it.
--
-- INDEXES
--   None added. Backfill/verify scan public.usuario (expected 0 rows on MAIN,
--   candidate preflight 001) and look up perfiles_usuario by PK. Existing
--   perfiles_usuario_username_key (unique) and indexes are untouched.
--
-- RLS AND GRANTS
--   perfiles_usuario RLS/policies/grants unchanged; the new columns inherit the
--   existing table-level grants, hence the estado_cuenta guard trigger.
--   private.identity_backfill_report: RLS enabled with no policies; ALL privileges
--   revoked from public/anon/authenticated/service_role (owner-only; nobody at
--   runtime reads or writes it). The only new function is the estado_cuenta guard
--   trigger function (SECURITY INVOKER, execute revoked from public/anon/
--   authenticated; trigger functions need no EXECUTE grant to fire).
--   GRANTS IMPACT (Gate A' D1 / FIX-D1): NONE on auth.users. service_role has no
--   SELECT on auth.users in MAIN and this migration neither grants it nor creates
--   any SECURITY DEFINER routine (SERVICE_ROLE_AUTH_USERS_BROAD_SELECT = DENIED;
--   asserted by dbtest). There is no backfill EXECUTE grant for anyone: the
--   backfill ran as the migration role. Requirement: the migration role must be
--   able to SELECT auth.users (postgres in Supabase); the block checks it and
--   aborts the migration with a clear error if public.usuario has rows and the
--   privilege is missing.
--   If a RUNTIME need for the backfill ever appears, it must be a narrow SECURITY
--   DEFINER (owner postgres, search_path '', PUBLIC execute revoked, execute only
--   for the one actor, no parameters, no broad auth.users SELECT) with privilege-
--   boundary tests -- not a grant of SELECT on auth.users.
--
-- IDEMPOTENCY OF THIS FILE
--   Re-applying is safe: add column if not exists (the CHECK is part of the
--   column clause), create table if not exists, create or replace function,
--   drop trigger if exists + create trigger, drop function if exists for the
--   legacy draft routines, and the backfill DO block is idempotent (fills only
--   NULL targets; a second run reports the same conflicts and changes nothing).
--
-- ROLLBACK (manual, local/branch; executed verbatim by the dbtest suite)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_perfiles_usuario_estado_cuenta_guard on public.perfiles_usuario;
-- drop function if exists private.guard_perfiles_estado_cuenta();
-- drop table if exists private.identity_backfill_report;
-- alter table public.perfiles_usuario
--     drop column if exists perfil_intro_visto_en,
--     drop column if exists estado_cuenta,
--     drop column if exists apellidos,
--     drop column if exists nombre;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read-only SQL for an operator running as the migration role; NOT a
-- function, NO grant; run after the migration and again before the Slice 1
-- cutover. Every check must report failures = 0. Executed verbatim by dbtest.)
-- VERIFY-BEGIN
-- with nrm as (
--     select 'ÁÀÄÂÃÉÈËÊÍÌÏÎÓÒÖÔÕÚÙÜÛÑÇáàäâãéèëêíìïîóòöôõúùüûñç'::text as f,
--            'AAAAAEEEEIIIIOOOOOUUUUNCaaaaaeeeeiiiiooooouuuunc'::text as t
-- ),
-- eligible as (
--     select u.id as legacy_id, u.auth_user_id as uid,
--            nullif(btrim(u.nombre), '') as l_nombre,
--            nullif(btrim(u.apellidos), '') as l_apellidos,
--            nullif(btrim(u.username), '') as l_username,
--            nullif(btrim(u.especialidad_registro), '') as l_especialidad,
--            u.perfil_completo as l_completo
--     from public.usuario u
--     join auth.users a on a.id = u.auth_user_id
--     where u.deleted_at is null
-- ),
-- joined as (
--     select e.*, p.usuario_id as p_uid, p.nombre as p_nombre, p.apellidos as p_apellidos,
--            p.username as p_username, p.especialidad_principal_id as p_esp,
--            p.onboarding_completado as p_onb,
--            (select array_agg(s.id) from public.especialidades s, nrm
--              where lower(translate(btrim(s.nombre), nrm.f, nrm.t)) = lower(translate(btrim(e.l_especialidad), nrm.f, nrm.t))
--                 or lower(translate(btrim(s.slug), nrm.f, nrm.t)) = lower(translate(btrim(e.l_especialidad), nrm.f, nrm.t))) as esp_ids
--     from eligible e
--     left join public.perfiles_usuario p on p.usuario_id = e.uid
-- ),
-- reported as (
--     select b.legacy_usuario_id, b.field, b.outcome from private.identity_backfill_report b
-- )
-- select 'eligible_without_profile' as check_name, count(*) filter (where p_uid is null) as failures, null::text as info from joined
-- union all
-- select 'nombre_diff', count(*) filter (
--     where l_nombre is not null and p_nombre is distinct from l_nombre
--       and not exists (select 1 from reported x where x.legacy_usuario_id = legacy_id
--                        and x.field = 'nombre' and x.outcome = 'conflict_kept_existing')), null from joined
-- union all
-- select 'apellidos_diff', count(*) filter (
--     where l_apellidos is not null and p_apellidos is distinct from l_apellidos
--       and not exists (select 1 from reported x where x.legacy_usuario_id = legacy_id
--                        and x.field = 'apellidos' and x.outcome = 'conflict_kept_existing')), null from joined
-- union all
-- select 'username_diff', count(*) filter (
--     where l_username is not null and p_username is distinct from l_username
--       and not exists (select 1 from reported x where x.legacy_usuario_id = legacy_id
--                        and x.field = 'username'
--                        and x.outcome in ('conflict_kept_existing', 'skipped_username_collision'))), null from joined
-- union all
-- select 'especialidad_diff', count(*) filter (
--     where l_especialidad is not null
--       and (coalesce(cardinality(esp_ids), 0) <> 1 or p_esp is distinct from esp_ids[1])
--       and not exists (select 1 from reported x where x.legacy_usuario_id = legacy_id
--                        and x.field = 'especialidad_principal_id'
--                        and x.outcome in ('conflict_kept_existing', 'specialty_unmatched', 'specialty_ambiguous'))), null from joined
-- union all
-- select 'onboarding_diff', count(*) filter (where l_completo and not coalesce(p_onb, false)), null from joined
-- union all
-- select 'orphans_unreported', count(*), null
-- from public.usuario u
-- left join auth.users a on a.id = u.auth_user_id
-- where (u.deleted_at is not null or a.id is null)
--   and not exists (select 1 from reported x where x.legacy_usuario_id = u.id and x.field = 'row')
-- union all
-- -- Cutover evidence: compare this watermark before/after the SLICE 1 deploy;
-- -- it must stay identical (0 new public.usuario writes).
-- select 'legacy_write_watermark', 0::bigint,
--        'rows=' || count(*) || ';max_created_at=' || coalesce(max(u.created_at)::text, '-')
--            || ';max_updated_at=' || coalesce(max(u.updated_at)::text, '-')
-- from public.usuario u;
-- VERIFY-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

-- 1. EXPAND -------------------------------------------------------------------
alter table public.perfiles_usuario
    add column if not exists nombre text
        constraint perfiles_usuario_nombre_len_chk
        check (nombre is null or char_length(nombre) <= 100),
    add column if not exists apellidos text
        constraint perfiles_usuario_apellidos_len_chk
        check (apellidos is null or char_length(apellidos) <= 100),
    add column if not exists estado_cuenta text not null default 'activa'
        constraint perfiles_usuario_estado_cuenta_chk
        check (estado_cuenta in ('activa', 'bloqueada', 'eliminacion_pendiente')),
    add column if not exists perfil_intro_visto_en timestamptz;

-- 2. estado_cuenta guard ----------------------------------------------------------
create or replace function private.guard_perfiles_estado_cuenta()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    if current_user in ('anon', 'authenticated') then
        if tg_op = 'INSERT' and new.estado_cuenta is distinct from 'activa' then
            raise exception using errcode = '42501', message = 'ESTADO_CUENTA_NOT_CLIENT_WRITABLE';
        end if;
        -- PF3-3-02: a held access token keeps working against PostgREST for up to ~1h, so a
        -- profile that is not 'activa' (eliminacion_pendiente = DEC-008 anonymization in
        -- progress, bloqueada) is read-only for clients: the user cannot rewrite it (undoing
        -- the anonymization or editing a blocked profile). Server roles are unaffected.
        if tg_op = 'UPDATE' and old.estado_cuenta <> 'activa' then
            raise exception using errcode = '42501', message = 'ESTADO_CUENTA_NOT_ACTIVE';
        end if;
        if tg_op = 'UPDATE' and new.estado_cuenta is distinct from old.estado_cuenta then
            raise exception using errcode = '42501', message = 'ESTADO_CUENTA_NOT_CLIENT_WRITABLE';
        end if;
    end if;
    return new;
end;
$$;

revoke all on function private.guard_perfiles_estado_cuenta() from public, anon, authenticated;

drop trigger if exists trg_perfiles_usuario_estado_cuenta_guard on public.perfiles_usuario;
create trigger trg_perfiles_usuario_estado_cuenta_guard
before insert or update on public.perfiles_usuario
for each row execute function private.guard_perfiles_estado_cuenta();

-- 3. Backfill report ------------------------------------------------------------
-- Legacy draft routines (never applied to MAIN): the backfill is no longer a
-- runtime function (FIX-D1). Dropped if a local/branch database received them.
drop function if exists private.identity_backfill_verify();
drop function if exists private.identity_backfill_run();
drop function if exists private.identity_norm_text(text);

create table if not exists private.identity_backfill_report (
    id bigint generated always as identity primary key,
    run_id uuid not null,
    usuario_id uuid,
    legacy_usuario_id integer not null,
    field text not null check (field in (
        'row', 'profile', 'nombre', 'apellidos', 'username',
        'especialidad_principal_id', 'onboarding_completado'
    )),
    outcome text not null check (outcome in (
        'orphan_no_auth_user', 'orphan_unknown_auth_user', 'orphan_deleted',
        'profile_created', 'filled', 'conflict_kept_existing',
        'skipped_username_collision', 'specialty_unmatched', 'specialty_ambiguous'
    )),
    detail text,
    reported_at timestamptz not null default now()
);

alter table private.identity_backfill_report enable row level security;
-- Owner-only: no runtime actor (service_role included) reads or writes the report.
revoke all on table private.identity_backfill_report from public, anon, authenticated, service_role;

-- 4. Backfill (runs NOW, as the migration role; idempotent; no function) ------------
-- Trim, fold Spanish accents (upper and lower, so it works under C collation),
-- then lower-case: lower(translate(btrim(x), c_from, c_to)).
do $backfill$
declare
    c_from constant text := 'ÁÀÄÂÃÉÈËÊÍÌÏÎÓÒÖÔÕÚÙÜÛÑÇáàäâãéèëêíìïîóòöôõúùüûñç';
    c_to constant text := 'AAAAAEEEEIIIIOOOOOUUUUNCaaaaaeeeeiiiiooooouuuunc';
    v_run uuid := gen_random_uuid();
    r record;
    o record;
    p public.perfiles_usuario%rowtype;
    v_val text;
    v_ids uuid[];
    v_n integer;
    v_legacy bigint;
begin
    perform pg_advisory_xact_lock(hashtext('private.identity_backfill'));

    -- The report holds the LATEST run only.
    delete from private.identity_backfill_report;

    select count(*) into v_legacy from public.usuario;
    if v_legacy = 0 then
        raise notice 'identity backfill: public.usuario is empty, nothing to do';
        return;
    end if;
    -- auth.users is read under the migration role only; service_role has no SELECT on it.
    if not has_table_privilege(current_user, 'auth.users', 'select') then
        raise exception using errcode = '42501',
            message = 'IDENTITY_BACKFILL_NEEDS_AUTH_USERS_SELECT_FOR_MIGRATION_ROLE';
    end if;

    -- Orphans: report only.
    insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
    select v_run,
           case when a.id is not null then u.auth_user_id end,
           u.id,
           'row',
           case
               when u.deleted_at is not null then 'orphan_deleted'
               when u.auth_user_id is null then 'orphan_no_auth_user'
               else 'orphan_unknown_auth_user'
           end
    from public.usuario u
    left join auth.users a on a.id = u.auth_user_id
    where u.deleted_at is not null or a.id is null;

    for r in
        select u.id as legacy_id, u.auth_user_id as uid,
               nullif(btrim(u.nombre), '') as l_nombre,
               nullif(btrim(u.apellidos), '') as l_apellidos,
               nullif(btrim(u.username), '') as l_username,
               nullif(btrim(u.especialidad_registro), '') as l_especialidad,
               u.perfil_completo as l_completo
        from public.usuario u
        join auth.users a on a.id = u.auth_user_id
        where u.deleted_at is null
        order by u.id
    loop
        insert into public.perfiles_usuario (usuario_id) values (r.uid)
        on conflict (usuario_id) do nothing;
        get diagnostics v_n = row_count;
        if v_n > 0 then
            insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
            values (v_run, r.uid, r.legacy_id, 'profile', 'profile_created');
        end if;

        select * into p from public.perfiles_usuario pu where pu.usuario_id = r.uid for update;

        -- nombre
        if r.l_nombre is not null then
            if p.nombre is null then
                update public.perfiles_usuario set nombre = r.l_nombre where usuario_id = r.uid;
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                values (v_run, r.uid, r.legacy_id, 'nombre', 'filled');
            elsif p.nombre <> r.l_nombre then
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                values (v_run, r.uid, r.legacy_id, 'nombre', 'conflict_kept_existing');
            end if;
        end if;

        -- apellidos
        if r.l_apellidos is not null then
            if p.apellidos is null then
                update public.perfiles_usuario set apellidos = r.l_apellidos where usuario_id = r.uid;
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                values (v_run, r.uid, r.legacy_id, 'apellidos', 'filled');
            elsif p.apellidos <> r.l_apellidos then
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                values (v_run, r.uid, r.legacy_id, 'apellidos', 'conflict_kept_existing');
            end if;
        end if;

        -- username
        if r.l_username is not null then
            if p.username is null then
                if exists (
                    select 1 from public.perfiles_usuario x
                    where x.usuario_id <> r.uid and lower(x.username) = lower(r.l_username)
                ) then
                    insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                    values (v_run, r.uid, r.legacy_id, 'username', 'skipped_username_collision');
                else
                    begin
                        update public.perfiles_usuario set username = r.l_username where usuario_id = r.uid;
                        insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                        values (v_run, r.uid, r.legacy_id, 'username', 'filled');
                    exception when unique_violation then
                        insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                        values (v_run, r.uid, r.legacy_id, 'username', 'skipped_username_collision');
                    end;
                end if;
            elsif p.username <> r.l_username then
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                values (v_run, r.uid, r.legacy_id, 'username', 'conflict_kept_existing');
            end if;
        end if;

        -- especialidad_registro -> especialidad_principal_id
        if r.l_especialidad is not null then
            v_val := lower(translate(btrim(r.l_especialidad), c_from, c_to));
            select coalesce(array_agg(e.id order by e.id), array[]::uuid[]) into v_ids
            from public.especialidades e
            where lower(translate(btrim(e.nombre), c_from, c_to)) = v_val
               or lower(translate(btrim(e.slug), c_from, c_to)) = v_val;

            if cardinality(v_ids) = 0 then
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome, detail)
                values (v_run, r.uid, r.legacy_id, 'especialidad_principal_id', 'specialty_unmatched', left(r.l_especialidad, 100));
            elsif cardinality(v_ids) > 1 then
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome, detail)
                values (v_run, r.uid, r.legacy_id, 'especialidad_principal_id', 'specialty_ambiguous', left(r.l_especialidad, 100));
            elsif p.especialidad_principal_id is null then
                update public.perfiles_usuario set especialidad_principal_id = v_ids[1] where usuario_id = r.uid;
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                values (v_run, r.uid, r.legacy_id, 'especialidad_principal_id', 'filled');
            elsif p.especialidad_principal_id <> v_ids[1] then
                insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
                values (v_run, r.uid, r.legacy_id, 'especialidad_principal_id', 'conflict_kept_existing');
            end if;
        end if;

        -- perfil_completo -> onboarding_completado (never downgrade)
        if r.l_completo and not p.onboarding_completado then
            update public.perfiles_usuario set onboarding_completado = true where usuario_id = r.uid;
            insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
            values (v_run, r.uid, r.legacy_id, 'onboarding_completado', 'filled');
        end if;
    end loop;

    for o in
        select b.outcome as outcome, count(*) as total
        from private.identity_backfill_report b
        group by b.outcome
        order by b.outcome
    loop
        raise notice 'identity backfill: % = %', o.outcome, o.total;
    end loop;
end
$backfill$;

commit;
