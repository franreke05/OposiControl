-- =============================================================================
-- V1 -- onboarding stores the display name as the username (owner decision 2026-11-08).
-- =============================================================================
-- LOCAL / PG17 GATE ONLY. NOT applied to MAIN. Forward-only; 20260930100500 is NOT edited. Idempotent (create or replace), one transaction.
-- Requires 20260930100500 (latest app_completar_onboarding).
--
-- WHAT IT DOES
--   perfiles_usuario.nombre_mostrar was only ever set by the auth trigger (handle_new_auth_user: OAuth full_name/name or the e-mail prefix), so the
--   name others saw was derived from the e-mail. public.app_completar_onboarding now also writes nombre_mostrar = p_username when the onboarding is
--   applied (not on an identical replay, which changes nothing). Same body as 20260930100500 otherwise (same validation, locks, errors, JSON).
--   The username pattern (^[A-Za-z0-9._-]{3,30}$) fits the nombre_mostrar bound (<= 120).
--   NOT done here: no backfill of already-onboarded users (owner decision pending), and a later username change through app_actualizar_perfil does
--   not touch nombre_mostrar.
--
-- ROLLBACK / MITIGATION
--   The 20260930100500 body comes back verbatim. nombre_mostrar values written meanwhile stay (plain profile data).
--
-- LOCKING: function DDL only.   DATA LOSS: none.   INDEXES: none.
-- RLS AND GRANTS: SECURITY DEFINER, search_path '', EXECUTE revoked from public/anon/authenticated and granted to service_role only (re-asserted).
--
-- ROLLBACK (manual; executed verbatim by the PG17 gate and dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function public.app_completar_onboarding(
--     p_usuario_id uuid,
--     p_username text,
--     p_nombre text,
--     p_apellidos text,
--     p_especialidad_id uuid,
--     p_documentos uuid[],
--     p_plataforma text,
--     p_attribution jsonb,
--     p_referral_code text
-- )
-- returns jsonb
-- language plpgsql
-- security definer
-- set search_path = ''
-- as $$
-- declare
--     v_p public.perfiles_usuario;
--     v_nombre text := private.slice1_trim(p_nombre);
--     v_apellidos text := private.slice1_trim(p_apellidos);
--     v_docs uuid[];
--     v_attr jsonb := coalesce(p_attribution, '{}'::jsonb);
--     v_ref text := private.slice1_trim(p_referral_code);
--     v_fuente text;
--     v_canal text;
--     v_campania text;
--     v_plat text;
--     v_install text;
--     v_n integer;
--     v_replay boolean := false;
-- begin
--     v_p := private.slice1_lock_profile(p_usuario_id);
--
--     if p_username is null or p_username !~ '^[A-Za-z0-9._-]{3,30}$' then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
--     end if;
--     if v_nombre is null or char_length(v_nombre) not between 1 and 100 then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
--     end if;
--     if v_apellidos is null or char_length(v_apellidos) not between 1 and 100 then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
--     end if;
--
--     -- Idempotent on usuario_id: identical replay = profile untouched but the body's legal documents and
--     -- write-once attribution are still applied; different identity = conflict (nothing validated or written).
--     if v_p.onboarding_completado then
--         if coalesce(v_p.username = p_username and v_p.nombre = v_nombre and v_p.apellidos = v_apellidos
--                     and (p_especialidad_id is null or v_p.especialidad_principal_id = p_especialidad_id), false) then
--             v_replay := true;
--         else
--             raise exception using errcode = 'OC409', message = 'ALREADY_ONBOARDED_CONFLICT';
--         end if;
--     end if;
--
--     if jsonb_typeof(v_attr) is distinct from 'object' then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution';
--     end if;
--     v_fuente := private.slice1_text(v_attr, 'fuente', 500);
--     v_canal := private.slice1_text(v_attr, 'canal', 500);
--     v_campania := private.slice1_text(v_attr, 'campania', 500);
--     v_plat := private.slice1_text(v_attr, 'plataforma', 20);
--     v_install := private.slice1_text(v_attr, 'installReferrer', 500);
--     if v_plat is not null and v_plat not in ('android', 'ios', 'web') then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution.plataforma';
--     end if;
--     if v_ref is not null and char_length(v_ref) > 64 then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'referralCode';
--     end if;
--
--     if not v_replay then
--         -- Serialise concurrent claims of the same case-insensitive username (the unique index stays the backstop).
--         perform pg_advisory_xact_lock(hashtext('slice1.username'), hashtext(lower(p_username)));
--         if exists (select 1 from public.perfiles_usuario x
--                    where lower(x.username) = lower(p_username) and x.usuario_id <> p_usuario_id) then
--             raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
--         end if;
--
--         if p_especialidad_id is not null
--            and not exists (select 1 from public.especialidades e where e.id = p_especialidad_id and e.activa) then
--             raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
--         end if;
--     end if;
--
--     select coalesce(array_agg(distinct d), '{}'::uuid[]) into v_docs from unnest(p_documentos) d where d is not null;
--     if cardinality(v_docs) > 20 then
--         raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'documentos';
--     end if;
--     if cardinality(v_docs) > 0 then
--         if p_plataforma is null or p_plataforma not in ('android', 'ios') then
--             raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'plataforma';
--         end if;
--         select count(*) into v_n from public.documentos_legales d where d.id = any (v_docs) and d.activo;
--         if v_n <> cardinality(v_docs) then
--             raise exception using errcode = '22023', message = 'DOCUMENT_NOT_ACTIVE';
--         end if;
--         insert into public.aceptaciones_legales (usuario_id, documento_id, tipo_documento, version, plataforma)
--         select p_usuario_id, d.id, d.tipo_documento, d.version, p_plataforma
--         from public.documentos_legales d
--         where d.id = any (v_docs)
--         on conflict (usuario_id, documento_id) do nothing;
--     end if;
--
--     if not v_replay then
--         begin
--             update public.perfiles_usuario
--             set username = p_username,
--                 nombre = v_nombre,
--                 apellidos = v_apellidos,
--                 especialidad_principal_id = coalesce(p_especialidad_id, especialidad_principal_id),
--                 onboarding_completado = true
--             where usuario_id = p_usuario_id;
--         exception when unique_violation then
--             raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
--         end;
--     end if;
--
--     if v_fuente is not null or v_canal is not null or v_campania is not null or v_plat is not null
--        or v_install is not null or v_ref is not null then
--         insert into public.atribucion_usuario (usuario_id, fuente, canal, campania, plataforma, install_referrer, codigo_referido)
--         values (p_usuario_id, v_fuente, v_canal, v_campania, v_plat, v_install, v_ref)
--         on conflict (usuario_id) do nothing;
--     end if;
--
--     if v_replay then
--         return private.slice1_profile_json(p_usuario_id) || jsonb_build_object('replay', true);
--     end if;
--     return private.slice1_profile_json(p_usuario_id);
-- end;
-- $$;
-- revoke all on function public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text) from public, anon, authenticated;
-- grant execute on function public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text) to service_role;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'onboarding_rpc_missing_or_not_service_role_only' as check_name,
--        (case when to_regprocedure('public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)') is null then 1
--              when has_function_privilege('anon', 'public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)', 'execute')
--                or has_function_privilege('authenticated', 'public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)', 'execute')
--                or not has_function_privilege('service_role', 'public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)', 'execute') then 1 else 0 end)::bigint as failures
-- union all
-- select 'onboarding_rpc_not_definer_or_search_path',
--        (case when p.prosecdef and coalesce(p.proconfig, '{}'::text[]) @> array['search_path=""'] then 0 else 1 end)::bigint
--   from pg_proc p where p.oid = to_regprocedure('public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)')
-- union all
-- select 'onboarding_does_not_write_display_name',
--        (case when p.prosrc ~ 'nombre_mostrar = p_username' then 0 else 1 end)::bigint
--   from pg_proc p where p.oid = to_regprocedure('public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)');
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)') is null or to_regprocedure('private.slice1_trim(text)') is null then
    raise exception using errcode = 'P0001', message = 'V1_ONBOARDING_DISPLAY_NAME_PREFLIGHT', hint = 'apply 20260930100500 first';
  end if;
end
$$;

create or replace function public.app_completar_onboarding(
    p_usuario_id uuid,
    p_username text,
    p_nombre text,
    p_apellidos text,
    p_especialidad_id uuid,
    p_documentos uuid[],
    p_plataforma text,
    p_attribution jsonb,
    p_referral_code text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_p public.perfiles_usuario;
    v_nombre text := private.slice1_trim(p_nombre);
    v_apellidos text := private.slice1_trim(p_apellidos);
    v_docs uuid[];
    v_attr jsonb := coalesce(p_attribution, '{}'::jsonb);
    v_ref text := private.slice1_trim(p_referral_code);
    v_fuente text;
    v_canal text;
    v_campania text;
    v_plat text;
    v_install text;
    v_n integer;
    v_replay boolean := false;
begin
    v_p := private.slice1_lock_profile(p_usuario_id);

    if p_username is null or p_username !~ '^[A-Za-z0-9._-]{3,30}$' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
    end if;
    if v_nombre is null or char_length(v_nombre) not between 1 and 100 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
    end if;
    if v_apellidos is null or char_length(v_apellidos) not between 1 and 100 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
    end if;

    -- Idempotent on usuario_id: identical replay = profile untouched but the body's legal documents and
    -- write-once attribution are still applied; different identity = conflict (nothing validated or written).
    if v_p.onboarding_completado then
        if coalesce(v_p.username = p_username and v_p.nombre = v_nombre and v_p.apellidos = v_apellidos
                    and (p_especialidad_id is null or v_p.especialidad_principal_id = p_especialidad_id), false) then
            v_replay := true;
        else
            raise exception using errcode = 'OC409', message = 'ALREADY_ONBOARDED_CONFLICT';
        end if;
    end if;

    if jsonb_typeof(v_attr) is distinct from 'object' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution';
    end if;
    v_fuente := private.slice1_text(v_attr, 'fuente', 500);
    v_canal := private.slice1_text(v_attr, 'canal', 500);
    v_campania := private.slice1_text(v_attr, 'campania', 500);
    v_plat := private.slice1_text(v_attr, 'plataforma', 20);
    v_install := private.slice1_text(v_attr, 'installReferrer', 500);
    if v_plat is not null and v_plat not in ('android', 'ios', 'web') then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'attribution.plataforma';
    end if;
    if v_ref is not null and char_length(v_ref) > 64 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'referralCode';
    end if;

    if not v_replay then
        -- Serialise concurrent claims of the same case-insensitive username (the unique index stays the backstop).
        perform pg_advisory_xact_lock(hashtext('slice1.username'), hashtext(lower(p_username)));
        if exists (select 1 from public.perfiles_usuario x
                   where lower(x.username) = lower(p_username) and x.usuario_id <> p_usuario_id) then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end if;

        if p_especialidad_id is not null
           and not exists (select 1 from public.especialidades e where e.id = p_especialidad_id and e.activa) then
            raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
        end if;
    end if;

    select coalesce(array_agg(distinct d), '{}'::uuid[]) into v_docs from unnest(p_documentos) d where d is not null;
    if cardinality(v_docs) > 20 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'documentos';
    end if;
    if cardinality(v_docs) > 0 then
        if p_plataforma is null or p_plataforma not in ('android', 'ios') then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'plataforma';
        end if;
        select count(*) into v_n from public.documentos_legales d where d.id = any (v_docs) and d.activo;
        if v_n <> cardinality(v_docs) then
            raise exception using errcode = '22023', message = 'DOCUMENT_NOT_ACTIVE';
        end if;
        insert into public.aceptaciones_legales (usuario_id, documento_id, tipo_documento, version, plataforma)
        select p_usuario_id, d.id, d.tipo_documento, d.version, p_plataforma
        from public.documentos_legales d
        where d.id = any (v_docs)
        on conflict (usuario_id, documento_id) do nothing;
    end if;

    if not v_replay then
        begin
            update public.perfiles_usuario
            set username = p_username,
                nombre_mostrar = p_username,   -- owner 2026-11-08: the display name IS the username chosen at signup
                nombre = v_nombre,
                apellidos = v_apellidos,
                especialidad_principal_id = coalesce(p_especialidad_id, especialidad_principal_id),
                onboarding_completado = true
            where usuario_id = p_usuario_id;
        exception when unique_violation then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end;
    end if;

    if v_fuente is not null or v_canal is not null or v_campania is not null or v_plat is not null
       or v_install is not null or v_ref is not null then
        insert into public.atribucion_usuario (usuario_id, fuente, canal, campania, plataforma, install_referrer, codigo_referido)
        values (p_usuario_id, v_fuente, v_canal, v_campania, v_plat, v_install, v_ref)
        on conflict (usuario_id) do nothing;
    end if;

    if v_replay then
        return private.slice1_profile_json(p_usuario_id) || jsonb_build_object('replay', true);
    end if;
    return private.slice1_profile_json(p_usuario_id);
end;
$$;

revoke all on function public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text) from public, anon, authenticated;
grant execute on function public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text) to service_role;

commit;
