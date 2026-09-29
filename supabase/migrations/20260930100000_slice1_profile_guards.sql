-- =============================================================================
-- SLICE 1 / WP B1 -- Profile write guards, specialty backstop, username uniqueness,
--                    handle_new_auth_user account-state guard, onboarding backfill fix
-- =============================================================================
-- Local/branch only. NOT applied to MAIN (separate gate). Depends on Slice 0
-- 20260928100000 (estado_cuenta, private schema). Idempotent, one transaction.
--
-- WHAT THIS MIGRATION DOES
--   1. private.guard_perfiles_server_owned() + trigger trg_perfiles_usuario_server_owned_guard
--      (BEFORE INSERT OR UPDATE): for anon/authenticated (direct PostgREST) the columns
--      onboarding_completado, es_invitado, especialidad_principal_id, comunidad_principal_id,
--      username and perfil_intro_visto_en are server-owned: INSERT with a non-default value or an
--      UPDATE that changes one raises 42501 PROFILE_FIELD_NOT_CLIENT_WRITABLE (R2:RB-1-03). MAIN
--      grants table-level UPDATE on perfiles_usuario to authenticated, so a column-level REVOKE
--      would have to revoke and re-grant the whole table; the trigger extends the Slice 0
--      estado_cuenta guard instead and needs no privilege change. Ktor (service_role) and the
--      SECURITY DEFINER RPCs (owner postgres) are unaffected: ALL profile writes go through them.
--      Roles are NOT profile columns: they live in roles_usuario, already admin-only by RLS
--      (roles_usuario_admin_all) and protected by role_sync_guard; nothing changes there.
--   2. private.validate_perfiles_refs() + trigger trg_perfiles_usuario_validar_referencias
--      (BEFORE INSERT OR UPDATE OF especialidad_principal_id, comunidad_principal_id): DB backstop for
--      DEC-002 for ANY writer. When the value is set or changes it must reference an ACTIVE
--      especialidad (22023 UNKNOWN_SPECIALTY) / an existing comunidad (22023 UNKNOWN_COMMUNITY).
--      NULLs (also produced by the FK ON DELETE SET NULL) and unchanged values are not checked, so a
--      later deactivation of a specialty never breaks profile updates. Changing the specialty never
--      touches attempts/results (only this one column is written).
--      KNOWN EDGE: the Slice 0 backfill DO block re-executed AFTER this migration aborts (whole
--      transaction) with UNKNOWN_SPECIALTY if a legacy row resolves to an INACTIVE specialty. MAIN
--      has 0 legacy rows (preflight count(*) of public.usuario must be 0, else STOP).
--   3. Unique index perfiles_usuario_username_lower_uq on lower(username) where username is not
--      null. Created only when there are no case-collisions; otherwise a NOTICE reports the number
--      of colliding groups and the index is NOT created (never fails the migration; the RPCs still
--      enforce case-insensitive uniqueness and re-applying the file creates it once the collisions
--      are resolved). The existing case-sensitive perfiles_usuario_username_key stays.
--   4. handle_new_auth_user(): same body as MAIN plus `where estado_cuenta = 'activa'` on the
--      ON CONFLICT DO UPDATE, so an auth.users email/metadata update no longer rewrites (nor
--      re-populates after anonymization) a profile that is bloqueada/eliminacion_pendiente (R2:RB-1-07).
--   5. Onboarding backfill correction (R2:RA-1-01). The Slice 0 block set onboarding_completado
--      from perfil_completo alone. Correct rule: only when username, nombre AND apellidos are all
--      non-null. This file re-applies the rule as a delta (fills the eligible rows that were
--      skipped, un-sets the rows the old rule wrongly completed that still have a non-deleted
--      legacy perfil_completo row AND incomplete fields). No-op when public.usuario is empty (MAIN).
--
-- FORWARD PLAN
--   Apply after Slice 0 on local/branch; MAIN only at the dedicated apply gate. Ktor Slice 1 writes
--   profiles exclusively through the RPCs of 20260930100200. The Slice 0 VERIFY `onboarding_diff` check
--   applies the OLD rule; use the VERIFY block below after this migration.
--
-- ROLLBACK / MITIGATION
--   ROLLBACK block below (drops both triggers/functions/index, restores the previous
--   handle_new_auth_user). Data touched by step 5 is NOT restored (only legacy-derived
--   onboarding flags; 0 rows on MAIN). Nothing else changes data.
--
-- COMPATIBILITY WITH OLD KTOR
--   Old Ktor does not write these columns for clients; legacy /v1/auth/register/profile writes
--   public.usuario through oposibots_upsert_own_profile (untouched). service_role writes keep
--   working; only writes of an inactive/unknown specialty or community id are rejected.
--
-- LOCKING
--   Triggers/function replacement take a brief ACCESS EXCLUSIVE lock on perfiles_usuario (CREATE
--   TRIGGER takes SHARE ROW EXCLUSIVE); CREATE UNIQUE INDEX takes SHARE (blocks writes) for the build.
--   lock_timeout 5s: fails fast, retry. perfiles_usuario is small on MAIN.
--
-- DATA LOSS
--   None (see step 5 for the legacy-derived onboarding flag).
--
-- INDEXES
--   + perfiles_usuario_username_lower_uq (unique, partial). No other index change.
--
-- RLS AND GRANTS
--   No policy or grant change. New functions: SECURITY INVOKER, EXECUTE revoked from public/anon/
--   authenticated (trigger functions need no EXECUTE grant). handle_new_auth_user keeps its
--   SECURITY DEFINER / search_path public / ACL (create or replace).
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_perfiles_usuario_validar_referencias on public.perfiles_usuario;
-- drop trigger if exists trg_perfiles_usuario_server_owned_guard on public.perfiles_usuario;
-- drop function if exists private.validate_perfiles_refs();
-- drop function if exists private.guard_perfiles_server_owned();
-- drop index if exists public.perfiles_usuario_username_lower_uq;
-- create or replace function public.handle_new_auth_user()
--  returns trigger
--  language plpgsql
--  security definer
--  set search_path to 'public'
-- as $function$
-- begin
--   insert into public.perfiles_usuario (
--     usuario_id,
--     nombre_mostrar,
--     username,
--     avatar_url,
--     actualizado_en
--   )
--   values (
--     new.id,
--     coalesce(
--       new.raw_user_meta_data ->> 'nombre',
--       new.raw_user_meta_data ->> 'name',
--       split_part(coalesce(new.email, 'usuario'), '@', 1)
--     ),
--     nullif(new.raw_user_meta_data ->> 'username', ''),
--     nullif(new.raw_user_meta_data ->> 'avatar_url', ''),
--     now()
--   )
--   on conflict (usuario_id) do update
--   set
--     nombre_mostrar = coalesce(public.perfiles_usuario.nombre_mostrar, excluded.nombre_mostrar),
--     username = coalesce(public.perfiles_usuario.username, excluded.username),
--     avatar_url = coalesce(public.perfiles_usuario.avatar_url, excluded.avatar_url),
--     actualizado_en = now();
--
--   return new;
-- end;
-- $function$;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read-only SQL for an operator running as the migration role; every check must
-- report failures = 0. Executed verbatim by dbtest.)
-- VERIFY-BEGIN
-- select 'server_owned_guard_missing' as check_name,
--        (case when exists (select 1 from pg_trigger t where t.tgname = 'trg_perfiles_usuario_server_owned_guard'
--                             and t.tgrelid = 'public.perfiles_usuario'::regclass and not t.tgisinternal) then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'refs_trigger_missing',
--        (case when exists (select 1 from pg_trigger t where t.tgname = 'trg_perfiles_usuario_validar_referencias'
--                             and t.tgrelid = 'public.perfiles_usuario'::regclass and not t.tgisinternal) then 0 else 1 end)::bigint, null
-- union all
-- select 'username_lower_collisions', count(*), null
-- from (select lower(username) from public.perfiles_usuario where username is not null
--       group by 1 having count(*) > 1) c
-- union all
-- select 'username_lower_index_missing',
--        (case when exists (select 1 from pg_indexes where schemaname = 'public'
--                             and indexname = 'perfiles_usuario_username_lower_uq') then 0 else 1 end)::bigint, null
-- union all
-- select 'handle_new_auth_user_unguarded',
--        (case when exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--                            where n.nspname = 'public' and p.proname = 'handle_new_auth_user'
--                              and p.prosrc like '%estado_cuenta = ''activa''%') then 0 else 1 end)::bigint, null
-- union all
-- select 'onboarding_incomplete_legacy', count(*), null
-- from public.perfiles_usuario p
-- where p.onboarding_completado
--   and (p.username is null or p.nombre is null or p.apellidos is null)
--   and exists (select 1 from public.usuario u
--                where u.auth_user_id = p.usuario_id and u.deleted_at is null and u.perfil_completo)
-- union all
-- select 'onboarding_complete_not_set', count(*), null
-- from public.perfiles_usuario p
-- where not p.onboarding_completado
--   and p.username is not null and p.nombre is not null and p.apellidos is not null
--   and exists (select 1 from public.usuario u
--                where u.auth_user_id = p.usuario_id and u.deleted_at is null and u.perfil_completo);
-- VERIFY-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

-- 1. Server-owned column guard ------------------------------------------------------
create or replace function private.guard_perfiles_server_owned()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    if current_user in ('anon', 'authenticated') then
        if tg_op = 'INSERT' then
            if new.onboarding_completado
               or new.es_invitado
               or new.especialidad_principal_id is not null
               or new.comunidad_principal_id is not null
               or new.username is not null
               or new.perfil_intro_visto_en is not null then
                raise exception using errcode = '42501', message = 'PROFILE_FIELD_NOT_CLIENT_WRITABLE';
            end if;
        elsif (new.onboarding_completado, new.es_invitado, new.especialidad_principal_id,
               new.comunidad_principal_id, new.username, new.perfil_intro_visto_en)
              is distinct from
              (old.onboarding_completado, old.es_invitado, old.especialidad_principal_id,
               old.comunidad_principal_id, old.username, old.perfil_intro_visto_en) then
            raise exception using errcode = '42501', message = 'PROFILE_FIELD_NOT_CLIENT_WRITABLE';
        end if;
    end if;
    return new;
end;
$$;

revoke all on function private.guard_perfiles_server_owned() from public, anon, authenticated;

drop trigger if exists trg_perfiles_usuario_server_owned_guard on public.perfiles_usuario;
create trigger trg_perfiles_usuario_server_owned_guard
before insert or update on public.perfiles_usuario
for each row execute function private.guard_perfiles_server_owned();

-- 2. Specialty / community backstop (DEC-002) ---------------------------------------
create or replace function private.validate_perfiles_refs()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    if new.especialidad_principal_id is not null
       and (tg_op = 'INSERT' or new.especialidad_principal_id is distinct from old.especialidad_principal_id) then
        if not exists (
            select 1 from public.especialidades e
            where e.id = new.especialidad_principal_id and e.activa
        ) then
            raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
        end if;
    end if;
    if new.comunidad_principal_id is not null
       and (tg_op = 'INSERT' or new.comunidad_principal_id is distinct from old.comunidad_principal_id) then
        if not exists (
            select 1 from public.comunidades_autonomas c
            where c.id = new.comunidad_principal_id
        ) then
            raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
        end if;
    end if;
    return new;
end;
$$;

revoke all on function private.validate_perfiles_refs() from public, anon, authenticated;

drop trigger if exists trg_perfiles_usuario_validar_referencias on public.perfiles_usuario;
create trigger trg_perfiles_usuario_validar_referencias
before insert or update of especialidad_principal_id, comunidad_principal_id on public.perfiles_usuario
for each row execute function private.validate_perfiles_refs();

-- 3. Case-insensitive username uniqueness (report collisions, never fail) --------------
do $idx$
declare
    v_groups integer;
begin
    select count(*) into v_groups
    from (select lower(username) from public.perfiles_usuario
          where username is not null group by 1 having count(*) > 1) c;
    if v_groups > 0 then
        raise notice 'SLICE1 PREFLIGHT: % case-insensitive username collision group(s) in perfiles_usuario; unique index perfiles_usuario_username_lower_uq NOT created (resolve and re-apply)', v_groups;
    else
        create unique index if not exists perfiles_usuario_username_lower_uq
            on public.perfiles_usuario (lower(username)) where username is not null;
    end if;
end
$idx$;

-- 4. handle_new_auth_user: never touch a non-active profile (R2:RB-1-07) ---------------
create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path to 'public'
as $function$
begin
  insert into public.perfiles_usuario (
    usuario_id,
    nombre_mostrar,
    username,
    avatar_url,
    actualizado_en
  )
  values (
    new.id,
    coalesce(
      new.raw_user_meta_data ->> 'nombre',
      new.raw_user_meta_data ->> 'name',
      split_part(coalesce(new.email, 'usuario'), '@', 1)
    ),
    nullif(new.raw_user_meta_data ->> 'username', ''),
    nullif(new.raw_user_meta_data ->> 'avatar_url', ''),
    now()
  )
  on conflict (usuario_id) do update
  set
    nombre_mostrar = coalesce(public.perfiles_usuario.nombre_mostrar, excluded.nombre_mostrar),
    username = coalesce(public.perfiles_usuario.username, excluded.username),
    avatar_url = coalesce(public.perfiles_usuario.avatar_url, excluded.avatar_url),
    actualizado_en = now()
  where public.perfiles_usuario.estado_cuenta = 'activa';

  return new;
end;
$function$;

-- 5. Onboarding backfill correction (delta; no-op when public.usuario is empty) ---------
do $fix$
declare
    r record;
    v_down integer := 0;
    v_up integer := 0;
begin
    if to_regclass('public.usuario') is null or not exists (select 1 from public.usuario) then
        raise notice 'slice1 onboarding backfill fix: public.usuario empty or absent, nothing to do';
        return;
    end if;
    perform pg_advisory_xact_lock(hashtext('private.identity_backfill'));

    -- Un-set the rows the old rule wrongly completed.
    for r in
        select p.usuario_id
        from public.perfiles_usuario p
        where p.onboarding_completado
          and (p.username is null or p.nombre is null or p.apellidos is null)
          and exists (select 1 from public.usuario u
                      where u.auth_user_id = p.usuario_id and u.deleted_at is null and u.perfil_completo)
        for update of p
    loop
        update public.perfiles_usuario set onboarding_completado = false where usuario_id = r.usuario_id;
        delete from private.identity_backfill_report b
        where b.usuario_id = r.usuario_id and b.field = 'onboarding_completado' and b.outcome = 'filled';
        v_down := v_down + 1;
    end loop;

    -- Complete the eligible rows (all three identity fields present).
    for r in
        select p.usuario_id, min(u.id) as legacy_id
        from public.perfiles_usuario p
        join public.usuario u on u.auth_user_id = p.usuario_id and u.deleted_at is null and u.perfil_completo
        where not p.onboarding_completado
          and p.username is not null and p.nombre is not null and p.apellidos is not null
        group by p.usuario_id
    loop
        update public.perfiles_usuario set onboarding_completado = true where usuario_id = r.usuario_id;
        insert into private.identity_backfill_report (run_id, usuario_id, legacy_usuario_id, field, outcome)
        values (gen_random_uuid(), r.usuario_id, r.legacy_id, 'onboarding_completado', 'filled');
        v_up := v_up + 1;
    end loop;

    raise notice 'slice1 onboarding backfill fix: unset=% set=%', v_down, v_up;
end
$fix$;

commit;
