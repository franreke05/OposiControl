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
--      and SECURITY DEFINER RPCs are unaffected.
--   3. private.identity_backfill_report (usuario_id, legacy_usuario_id, field,
--      outcome, detail) -- holds the LATEST run only.
--   4. private.identity_backfill_run(): idempotent, re-runnable backfill. It is
--      CREATED here but NOT executed: run it on local or a branch; on MAIN only
--      with explicit later authorization.
--   5. private.identity_backfill_verify(): read-only VERIFY; every row must
--      report failures = 0.
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
--
-- FORWARD PLAN
--   SLICE 0: this EXPAND + backfill function (+ verify) on local/branch.
--   SLICE 1 (one deploy): final delta re-run of identity_backfill_run() just
--   before the deploy; EP-218/EP-124 become thin adapters over the canonical
--   profile service (D1-12/D1-11) reading/writing perfiles_usuario only;
--   oposibots_upsert_own_profile is no longer called. NO DUAL-WRITE: a single
--   legacy writer switched atomically in one deploy; the re-runnable backfill
--   closes the window.
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
--   trigger, Ktor, OC) are unaffected. No FK or ON DELETE change (DEC-008 open).
--
-- LOCKING RISK
--   ADD COLUMN with a constant default is metadata-only (PG11+) but takes an
--   ACCESS EXCLUSIVE lock on perfiles_usuario for the duration of the
--   transaction, plus one validation scan for the two CHECKs on estado_cuenta
--   and name lengths (all rows are 'activa'/null, so the scan cannot fail).
--   lock_timeout = 5s makes the migration fail fast instead of queueing behind
--   long transactions; simply retry. The backfill function takes row locks only
--   (FOR UPDATE per profile) and no table locks.
--
-- DATA-LOSS RISK
--   Migration: none (additive). Backfill: none -- it only fills NULL targets and
--   never deletes or downgrades; public.usuario is read-only for it.
--   Rollback: drops the four new columns; nombre/apellidos copied from legacy can
--   be re-derived by re-running the backfill while public.usuario exists, but
--   values written natively after cutover and estado_cuenta states WOULD be lost.
--   Do not roll back after the SLICE 1 cutover without exporting them first.
--
-- INDEXES
--   None added. Backfill/verify scan public.usuario (expected 0 rows on MAIN,
--   candidate preflight 001) and look up perfiles_usuario by PK. Existing
--   perfiles_usuario_username_key (unique) and indexes are untouched.
--
-- RLS AND GRANTS
--   perfiles_usuario RLS/policies/grants unchanged; the new columns inherit the
--   existing table-level grants, hence the estado_cuenta guard trigger.
--   private.identity_backfill_report: RLS enabled with no policies; revoked from
--   public/anon/authenticated; service_role only. All new functions: execute
--   revoked from public/anon/authenticated, granted to service_role; they live in
--   schema private (usage = service_role only), so PostgREST never exposes them.
--
-- IDEMPOTENCY OF THIS FILE
--   Re-applying is safe: add column if not exists (the CHECK is part of the
--   column clause), create table if not exists, create or replace function,
--   drop trigger if exists + create trigger.
--
-- ROLLBACK (manual, local/branch; executed verbatim by the dbtest suite)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_perfiles_usuario_estado_cuenta_guard on public.perfiles_usuario;
-- drop function if exists private.guard_perfiles_estado_cuenta();
-- drop function if exists private.identity_backfill_verify();
-- drop function if exists private.identity_backfill_run();
-- drop function if exists private.identity_norm_text(text);
-- drop table if exists private.identity_backfill_report;
-- alter table public.perfiles_usuario
--     drop column if exists perfil_intro_visto_en,
--     drop column if exists estado_cuenta,
--     drop column if exists apellidos,
--     drop column if exists nombre;
-- commit;
-- ROLLBACK-END
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
revoke all on table private.identity_backfill_report from public, anon, authenticated;
grant select, insert, delete on table private.identity_backfill_report to service_role;

-- 4. Helpers + backfill -----------------------------------------------------------
-- Trim, fold Spanish accents (upper and lower, so it works under C collation),
-- then lower-case.
create or replace function private.identity_norm_text(p text)
returns text
language sql
immutable
set search_path = ''
as $$
    select lower(translate(
        btrim(p),
        'ÁÀÄÂÃÉÈËÊÍÌÏÎÓÒÖÔÕÚÙÜÛÑÇáàäâãéèëêíìïîóòöôõúùüûñç',
        'AAAAAEEEEIIIIOOOOOUUUUNCaaaaaeeeeiiiiooooouuuunc'
    ))
$$;

create or replace function private.identity_backfill_run()
returns table (outcome text, total bigint)
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_run uuid := gen_random_uuid();
    r record;
    p public.perfiles_usuario%rowtype;
    v_val text;
    v_ids uuid[];
    v_n integer;
begin
    delete from private.identity_backfill_report;

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
                    select 1 from public.perfiles_usuario o
                    where o.usuario_id <> r.uid and lower(o.username) = lower(r.l_username)
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
            v_val := private.identity_norm_text(r.l_especialidad);
            select coalesce(array_agg(e.id order by e.id), array[]::uuid[]) into v_ids
            from public.especialidades e
            where private.identity_norm_text(e.nombre) = v_val
               or private.identity_norm_text(e.slug) = v_val;

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

    return query
        select b.outcome, count(*)::bigint
        from private.identity_backfill_report b
        group by b.outcome
        order by b.outcome;
end;
$$;

-- 5. VERIFY (read-only) -------------------------------------------------------------
create or replace function private.identity_backfill_verify()
returns table (check_name text, failures bigint, info text)
language sql
stable
security invoker
set search_path = ''
as $$
    with eligible as (
        select u.id as legacy_id, u.auth_user_id as uid,
               nullif(btrim(u.nombre), '') as l_nombre,
               nullif(btrim(u.apellidos), '') as l_apellidos,
               nullif(btrim(u.username), '') as l_username,
               nullif(btrim(u.especialidad_registro), '') as l_especialidad,
               u.perfil_completo as l_completo
        from public.usuario u
        join auth.users a on a.id = u.auth_user_id
        where u.deleted_at is null
    ),
    joined as (
        select e.*, p.usuario_id as p_uid, p.nombre as p_nombre, p.apellidos as p_apellidos,
               p.username as p_username, p.especialidad_principal_id as p_esp,
               p.onboarding_completado as p_onb,
               (select array_agg(s.id) from public.especialidades s
                 where private.identity_norm_text(s.nombre) = private.identity_norm_text(e.l_especialidad)
                    or private.identity_norm_text(s.slug) = private.identity_norm_text(e.l_especialidad)) as esp_ids
        from eligible e
        left join public.perfiles_usuario p on p.usuario_id = e.uid
    ),
    reported as (
        select b.legacy_usuario_id, b.field, b.outcome from private.identity_backfill_report b
    )
    select 'eligible_without_profile', count(*) filter (where p_uid is null), null::text from joined
    union all
    select 'nombre_diff', count(*) filter (
        where l_nombre is not null and p_nombre is distinct from l_nombre
          and not exists (select 1 from reported x where x.legacy_usuario_id = legacy_id
                           and x.field = 'nombre' and x.outcome = 'conflict_kept_existing')), null from joined
    union all
    select 'apellidos_diff', count(*) filter (
        where l_apellidos is not null and p_apellidos is distinct from l_apellidos
          and not exists (select 1 from reported x where x.legacy_usuario_id = legacy_id
                           and x.field = 'apellidos' and x.outcome = 'conflict_kept_existing')), null from joined
    union all
    select 'username_diff', count(*) filter (
        where l_username is not null and p_username is distinct from l_username
          and not exists (select 1 from reported x where x.legacy_usuario_id = legacy_id
                           and x.field = 'username'
                           and x.outcome in ('conflict_kept_existing', 'skipped_username_collision'))), null from joined
    union all
    select 'especialidad_diff', count(*) filter (
        where l_especialidad is not null
          and (coalesce(cardinality(esp_ids), 0) <> 1 or p_esp is distinct from esp_ids[1])
          and not exists (select 1 from reported x where x.legacy_usuario_id = legacy_id
                           and x.field = 'especialidad_principal_id'
                           and x.outcome in ('conflict_kept_existing', 'specialty_unmatched', 'specialty_ambiguous'))), null from joined
    union all
    select 'onboarding_diff', count(*) filter (where l_completo and not coalesce(p_onb, false)), null from joined
    union all
    select 'orphans_unreported', count(*), null
    from public.usuario u
    left join auth.users a on a.id = u.auth_user_id
    where (u.deleted_at is not null or a.id is null)
      and not exists (select 1 from reported x where x.legacy_usuario_id = u.id and x.field = 'row')
    union all
    -- Cutover evidence: compare this watermark before/after the SLICE 1 deploy;
    -- it must stay identical (0 new public.usuario writes).
    select 'legacy_write_watermark', 0::bigint,
           'rows=' || count(*) || ';max_created_at=' || coalesce(max(u.created_at)::text, '-')
               || ';max_updated_at=' || coalesce(max(u.updated_at)::text, '-')
    from public.usuario u
$$;

revoke all on function private.identity_norm_text(text) from public, anon, authenticated;
revoke all on function private.identity_backfill_run() from public, anon, authenticated;
revoke all on function private.identity_backfill_verify() from public, anon, authenticated;
grant execute on function private.identity_norm_text(text) to service_role;
grant execute on function private.identity_backfill_run() to service_role;
grant execute on function private.identity_backfill_verify() to service_role;

commit;
