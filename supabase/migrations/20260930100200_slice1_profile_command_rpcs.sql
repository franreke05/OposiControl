-- =============================================================================
-- SLICE 1 / WP B3 -- Profile command RPCs (onboarding, profile patch, legal acceptances)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN. Idempotent (create or replace), one transaction.
-- Depends on 20260930100000 (guards) and 20260930100100 (aceptaciones_legales, atribucion,
-- detalle, comunidades objetivo).
--
-- WHAT THIS MIGRATION DOES
--   Three SECURITY DEFINER commands, the ONLY writers of profile/legal/attribution data for the app:
--     public.app_completar_onboarding(p_usuario_id, p_username, p_nombre, p_apellidos, p_especialidad_id,
--                                     p_documentos uuid[], p_plataforma, p_attribution jsonb, p_referral_code) returns jsonb
--     public.app_actualizar_perfil(p_usuario_id, p_patch jsonb, p_if_match timestamptz) returns jsonb
--     public.app_aceptar_documentos_legales(p_usuario_id, p_documentos uuid[], p_plataforma) returns jsonb
--   plus four private helpers (SECURITY INVOKER, callable only from the definer functions):
--   private.slice1_lock_profile / slice1_profile_json / slice1_uuid / slice1_text / slice1_int.
--   Each command locks the perfiles_usuario row FOR UPDATE, requires estado_cuenta = 'activa'
--   (deny-by-default: missing profile OC404 PROFILE_MISSING; bloqueada 42501 ACCOUNT_BLOCKED;
--   eliminacion_pendiente 42501 ACCOUNT_DELETION_PENDING; anything else 42501 FORBIDDEN) and uses
--   server-side now() for every timestamp. Stable error keys (message): USERNAME_TAKEN (23505),
--   UNKNOWN_SPECIALTY / UNKNOWN_COMMUNITY / DOCUMENT_NOT_ACTIVE / INVALID_ARGUMENT / UNKNOWN_FIELD
--   (22023, field in hint), ALREADY_ONBOARDED_CONFLICT (OC409), PRECONDITION_FAILED (OC412).
--   Signatures, argument semantics and JSON shapes: SLICE_1_DB_PLAN.md.
--   DEC-002: especialidadId only rewrites perfiles_usuario.especialidad_principal_id; nothing else
--   (attempts/results) is read or written.
--   No auth.users access at all (no SELECT grant needed or given).
--
-- FORWARD PLAN
--   Ktor Slice 1 (CanonicalProfileService) calls these via PostgREST with the service_role key.
--
-- ROLLBACK / MITIGATION
--   Drop the functions (block below). No data is touched by the migration itself; data written by
--   the RPCs stays valid (plain profile/legal rows).
--
-- COMPATIBILITY WITH OLD KTOR
--   New functions only; old Ktor never calls them.
--
-- LOCKING
--   Function DDL only (catalog locks). Runtime: one row lock per call on the caller's profile.
--
-- DATA LOSS
--   None.
--
-- INDEXES
--   None (lookups by primary key / unique keys created earlier).
--
-- RLS AND GRANTS
--   SECURITY DEFINER, owner = migration role (postgres), search_path '' (every reference is
--   schema-qualified), EXECUTE revoked from public/anon/authenticated and granted to service_role
--   only (also overrides the default function privileges of schema public). Helpers: EXECUTE
--   revoked from everyone but the owner. RLS is bypassed by the owner on purpose; the guard
--   triggers of 20260930100000 do not fire for the owner role.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_aceptar_documentos_legales(uuid, uuid[], text);
-- drop function if exists public.app_actualizar_perfil(uuid, jsonb, timestamptz);
-- drop function if exists public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text);
-- drop function if exists private.slice1_int(jsonb, text, integer, integer);
-- drop function if exists private.slice1_text(jsonb, text, integer);
-- drop function if exists private.slice1_uuid(text, text);
-- drop function if exists private.slice1_profile_json(uuid);
-- drop function if exists private.slice1_lock_profile(uuid, boolean);
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read-only; every check must report failures = 0. Executed verbatim by dbtest.)
-- VERIFY-BEGIN
-- select 'rpc_missing' as check_name,
--        (3 - count(*))::bigint as failures, null::text as info
-- from pg_proc p join pg_namespace n on n.oid = p.pronamespace
-- where n.nspname = 'public' and p.proname in ('app_completar_onboarding', 'app_actualizar_perfil', 'app_aceptar_documentos_legales')
-- union all
-- select 'rpc_not_definer_or_search_path', count(*), null
-- from pg_proc p join pg_namespace n on n.oid = p.pronamespace
-- where n.nspname = 'public' and p.proname in ('app_completar_onboarding', 'app_actualizar_perfil', 'app_aceptar_documentos_legales')
--   and (not p.prosecdef or p.proconfig is null or not ('search_path=""' = any (p.proconfig)))
-- union all
-- select 'rpc_execute_outside_service_role', count(*), null
-- from pg_proc p join pg_namespace n on n.oid = p.pronamespace
-- cross join (values ('anon'), ('authenticated'), ('public')) r(rol)
-- where n.nspname = 'public' and p.proname in ('app_completar_onboarding', 'app_actualizar_perfil', 'app_aceptar_documentos_legales')
--   and has_function_privilege(r.rol, p.oid, 'execute')
-- union all
-- select 'rpc_service_role_cannot_execute', count(*), null
-- from pg_proc p join pg_namespace n on n.oid = p.pronamespace
-- where n.nspname = 'public' and p.proname in ('app_completar_onboarding', 'app_actualizar_perfil', 'app_aceptar_documentos_legales')
--   and not has_function_privilege('service_role', p.oid, 'execute');
-- VERIFY-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

create schema if not exists private;

-- Helpers ---------------------------------------------------------------------------------
create or replace function private.slice1_lock_profile(p_usuario_id uuid, p_allow_pending boolean default false)
returns public.perfiles_usuario
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_p public.perfiles_usuario;
begin
    if p_usuario_id is null then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'usuarioId';
    end if;
    select * into v_p from public.perfiles_usuario pu where pu.usuario_id = p_usuario_id for update;
    if not found then
        raise exception using errcode = 'OC404', message = 'PROFILE_MISSING';
    end if;
    if v_p.estado_cuenta = 'activa' then
        return v_p;
    end if;
    if p_allow_pending and v_p.estado_cuenta = 'eliminacion_pendiente' then
        return v_p;
    end if;
    if v_p.estado_cuenta = 'bloqueada' then
        raise exception using errcode = '42501', message = 'ACCOUNT_BLOCKED';
    elsif v_p.estado_cuenta = 'eliminacion_pendiente' then
        raise exception using errcode = '42501', message = 'ACCOUNT_DELETION_PENDING';
    end if;
    raise exception using errcode = '42501', message = 'FORBIDDEN';
end;
$$;

create or replace function private.slice1_profile_json(p_usuario_id uuid)
returns jsonb
language sql
security invoker
set search_path = ''
as $$
    select jsonb_build_object(
        'usuarioId', p.usuario_id,
        'username', p.username,
        'nombreMostrar', p.nombre_mostrar,
        'nombre', p.nombre,
        'apellidos', p.apellidos,
        'especialidadPrincipalId', p.especialidad_principal_id,
        'comunidadPrincipalId', p.comunidad_principal_id,
        'avatarUrl', p.avatar_url,
        'onboardingCompleted', p.onboarding_completado,
        'estadoCuenta', p.estado_cuenta,
        'introSeenAt', to_char(p.perfil_intro_visto_en at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'creadoEn', to_char(p.creado_en at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'actualizadoEn', to_char(p.actualizado_en at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
    )
    from public.perfiles_usuario p
    where p.usuario_id = p_usuario_id
$$;

create or replace function private.slice1_uuid(p_text text, p_field text)
returns uuid
language plpgsql
security invoker
set search_path = ''
as $$
begin
    return p_text::uuid;
exception when invalid_text_representation then
    raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_field;
end;
$$;

-- Text member of a JSON object: JSON null / blank -> NULL; not a string or too long -> INVALID_ARGUMENT.
create or replace function private.slice1_text(p_json jsonb, p_key text, p_max integer)
returns text
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_t text;
begin
    if p_json -> p_key is null or jsonb_typeof(p_json -> p_key) = 'null' then
        return null;
    end if;
    if jsonb_typeof(p_json -> p_key) is distinct from 'string' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    v_t := nullif(btrim(p_json ->> p_key), '');
    if v_t is not null and char_length(v_t) > p_max then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    return v_t;
end;
$$;

-- Integer member of a JSON object: JSON null -> NULL; not an integer or out of range -> INVALID_ARGUMENT.
create or replace function private.slice1_int(p_json jsonb, p_key text, p_min integer, p_max integer)
returns integer
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_n numeric;
begin
    if p_json -> p_key is null or jsonb_typeof(p_json -> p_key) = 'null' then
        return null;
    end if;
    if jsonb_typeof(p_json -> p_key) is distinct from 'number' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    v_n := (p_json ->> p_key)::numeric;
    if v_n <> trunc(v_n) or v_n < p_min or v_n > p_max then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = p_key;
    end if;
    return v_n::integer;
end;
$$;

revoke all on function private.slice1_lock_profile(uuid, boolean) from public, anon, authenticated, service_role;
revoke all on function private.slice1_profile_json(uuid) from public, anon, authenticated, service_role;
revoke all on function private.slice1_uuid(text, text) from public, anon, authenticated, service_role;
revoke all on function private.slice1_text(jsonb, text, integer) from public, anon, authenticated, service_role;
revoke all on function private.slice1_int(jsonb, text, integer, integer) from public, anon, authenticated, service_role;

-- app_completar_onboarding ------------------------------------------------------------------
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
    v_nombre text := btrim(p_nombre);
    v_apellidos text := btrim(p_apellidos);
    v_docs uuid[];
    v_attr jsonb := coalesce(p_attribution, '{}'::jsonb);
    v_ref text := nullif(btrim(p_referral_code), '');
    v_fuente text;
    v_canal text;
    v_campania text;
    v_plat text;
    v_install text;
    v_n integer;
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

    -- Idempotent on usuario_id: identical replay = no-op, different identity = conflict.
    if v_p.onboarding_completado then
        if v_p.username = p_username and v_p.nombre = v_nombre and v_p.apellidos = v_apellidos
           and (p_especialidad_id is null or v_p.especialidad_principal_id = p_especialidad_id) then
            return private.slice1_profile_json(p_usuario_id) || jsonb_build_object('replay', true);
        end if;
        raise exception using errcode = 'OC409', message = 'ALREADY_ONBOARDED_CONFLICT';
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

    if exists (select 1 from public.perfiles_usuario x
               where lower(x.username) = lower(p_username) and x.usuario_id <> p_usuario_id) then
        raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
    end if;

    if p_especialidad_id is not null
       and not exists (select 1 from public.especialidades e where e.id = p_especialidad_id and e.activa) then
        raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
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

    begin
        update public.perfiles_usuario
        set username = p_username,
            nombre = v_nombre,
            apellidos = v_apellidos,
            especialidad_principal_id = coalesce(p_especialidad_id, especialidad_principal_id),
            onboarding_completado = true
        where usuario_id = p_usuario_id;
    exception when unique_violation then
        raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
    end;

    if v_fuente is not null or v_canal is not null or v_campania is not null or v_plat is not null
       or v_install is not null or v_ref is not null then
        insert into public.atribucion_usuario (usuario_id, fuente, canal, campania, plataforma, install_referrer, codigo_referido)
        values (p_usuario_id, v_fuente, v_canal, v_campania, v_plat, v_install, v_ref)
        on conflict (usuario_id) do nothing;
    end if;

    return private.slice1_profile_json(p_usuario_id);
end;
$$;

-- app_actualizar_perfil ---------------------------------------------------------------------
create or replace function public.app_actualizar_perfil(
    p_usuario_id uuid,
    p_patch jsonb,
    p_if_match timestamptz
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_p public.perfiles_usuario;
    v_allowed constant text[] := array['username', 'nombre', 'apellidos', 'especialidadId',
                                        'comunidadPrincipalId', 'detail', 'targetCommunityIds', 'introSeen'];
    v_detail_allowed constant text[] := array['edad', 'ciudad', 'estadoLaboral', 'centroTrabajo',
                                               'tiempoEstudioMin', 'franja', 'fechaExamenObjetivo'];
    k text;
    v_username text;
    v_nombre text;
    v_apellidos text;
    v_esp uuid;
    v_com uuid;
    v_intro timestamptz;
    v_touch boolean := false;
    v_detail jsonb;
    v_d public.perfiles_usuario_detalle;
    v_edad integer;
    v_ciudad text;
    v_laboral text;
    v_centro text;
    v_tiempo integer;
    v_franja text;
    v_fecha date;
    v_raw uuid[];
    v_targets uuid[];
    v_current uuid[];
    v_i integer;
begin
    v_p := private.slice1_lock_profile(p_usuario_id);

    if p_patch is null or jsonb_typeof(p_patch) is distinct from 'object' then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'patch';
    end if;
    for k in select jsonb_object_keys(p_patch) loop
        if not (k = any (v_allowed)) then
            raise exception using errcode = '22023', message = 'UNKNOWN_FIELD', hint = k;
        end if;
    end loop;

    if p_if_match is not null and p_if_match is distinct from v_p.actualizado_en then
        raise exception using errcode = 'OC412', message = 'PRECONDITION_FAILED';
    end if;

    v_username := v_p.username;
    v_nombre := v_p.nombre;
    v_apellidos := v_p.apellidos;
    v_esp := v_p.especialidad_principal_id;
    v_com := v_p.comunidad_principal_id;
    v_intro := v_p.perfil_intro_visto_en;

    if p_patch ? 'username' then
        if jsonb_typeof(p_patch -> 'username') is distinct from 'string'
           or (p_patch ->> 'username') !~ '^[A-Za-z0-9._-]{3,30}$' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'username';
        end if;
        v_username := p_patch ->> 'username';
        if exists (select 1 from public.perfiles_usuario x
                   where lower(x.username) = lower(v_username) and x.usuario_id <> p_usuario_id) then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end if;
    end if;
    if p_patch ? 'nombre' then
        v_nombre := private.slice1_text(p_patch, 'nombre', 100);
        if v_nombre is null then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'nombre';
        end if;
    end if;
    if p_patch ? 'apellidos' then
        v_apellidos := private.slice1_text(p_patch, 'apellidos', 100);
        if v_apellidos is null then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'apellidos';
        end if;
    end if;
    if p_patch ? 'especialidadId' then
        if jsonb_typeof(p_patch -> 'especialidadId') is distinct from 'string' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'especialidadId';
        end if;
        v_esp := private.slice1_uuid(p_patch ->> 'especialidadId', 'especialidadId');
        if not exists (select 1 from public.especialidades e where e.id = v_esp and e.activa) then
            raise exception using errcode = '22023', message = 'UNKNOWN_SPECIALTY';
        end if;
    end if;
    if p_patch ? 'comunidadPrincipalId' then
        if jsonb_typeof(p_patch -> 'comunidadPrincipalId') is distinct from 'string' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'comunidadPrincipalId';
        end if;
        v_com := private.slice1_uuid(p_patch ->> 'comunidadPrincipalId', 'comunidadPrincipalId');
        if not exists (select 1 from public.comunidades_autonomas c where c.id = v_com) then
            raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
        end if;
    end if;
    if p_patch ? 'introSeen' then
        if jsonb_typeof(p_patch -> 'introSeen') is distinct from 'boolean' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'introSeen';
        end if;
        if (p_patch ->> 'introSeen')::boolean and v_intro is null then
            v_intro := now();
        end if;
    end if;

    -- detail (perfiles_usuario_detalle): only present keys change; JSON null clears the field.
    if p_patch ? 'detail' then
        v_detail := p_patch -> 'detail';
        if jsonb_typeof(v_detail) is distinct from 'object' then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'detail';
        end if;
        for k in select jsonb_object_keys(v_detail) loop
            if not (k = any (v_detail_allowed)) then
                raise exception using errcode = '22023', message = 'UNKNOWN_FIELD', hint = 'detail.' || k;
            end if;
        end loop;
        select * into v_d from public.perfiles_usuario_detalle d where d.usuario_id = p_usuario_id for update;
        v_edad := v_d.edad;
        v_ciudad := v_d.ciudad;
        v_laboral := v_d.estado_laboral;
        v_centro := v_d.centro_trabajo;
        v_tiempo := v_d.tiempo_disponible_estudio_minutos;
        v_franja := v_d.franja_preferente_estudio;
        v_fecha := v_d.fecha_examen_objetivo;
        if v_detail ? 'edad' then v_edad := private.slice1_int(v_detail, 'edad', 14, 120); end if;
        if v_detail ? 'ciudad' then v_ciudad := private.slice1_text(v_detail, 'ciudad', 100); end if;
        if v_detail ? 'estadoLaboral' then v_laboral := private.slice1_text(v_detail, 'estadoLaboral', 100); end if;
        if v_detail ? 'centroTrabajo' then v_centro := private.slice1_text(v_detail, 'centroTrabajo', 100); end if;
        if v_detail ? 'tiempoEstudioMin' then v_tiempo := private.slice1_int(v_detail, 'tiempoEstudioMin', 0, 1440); end if;
        if v_detail ? 'franja' then v_franja := private.slice1_text(v_detail, 'franja', 100); end if;
        if v_detail ? 'fechaExamenObjetivo' then
            if jsonb_typeof(v_detail -> 'fechaExamenObjetivo') = 'null' then
                v_fecha := null;
            elsif jsonb_typeof(v_detail -> 'fechaExamenObjetivo') = 'string'
                  and (v_detail ->> 'fechaExamenObjetivo') ~ '^\d{4}-\d{2}-\d{2}$' then
                begin
                    v_fecha := (v_detail ->> 'fechaExamenObjetivo')::date;
                exception when others then
                    raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'fechaExamenObjetivo';
                end;
            else
                raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'fechaExamenObjetivo';
            end if;
        end if;

        if v_d.usuario_id is null then
            if (v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha) is distinct from
               (null::integer, null::text, null::text, null::text, null::integer, null::text, null::date) then
                insert into public.perfiles_usuario_detalle (usuario_id, edad, ciudad, estado_laboral, centro_trabajo,
                    tiempo_disponible_estudio_minutos, franja_preferente_estudio, fecha_examen_objetivo, actualizado_en)
                values (p_usuario_id, v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha, now());
                v_touch := true;
            end if;
        elsif (v_edad, v_ciudad, v_laboral, v_centro, v_tiempo, v_franja, v_fecha) is distinct from
              (v_d.edad, v_d.ciudad, v_d.estado_laboral, v_d.centro_trabajo,
               v_d.tiempo_disponible_estudio_minutos, v_d.franja_preferente_estudio, v_d.fecha_examen_objetivo) then
            update public.perfiles_usuario_detalle
            set edad = v_edad, ciudad = v_ciudad, estado_laboral = v_laboral, centro_trabajo = v_centro,
                tiempo_disponible_estudio_minutos = v_tiempo, franja_preferente_estudio = v_franja,
                fecha_examen_objetivo = v_fecha, actualizado_en = now()
            where usuario_id = p_usuario_id;
            v_touch := true;
        end if;
    end if;

    -- target communities: the array REPLACES the set; prioridad = position (1..n).
    if p_patch ? 'targetCommunityIds' then
        if jsonb_typeof(p_patch -> 'targetCommunityIds') is distinct from 'array'
           or jsonb_array_length(p_patch -> 'targetCommunityIds') > 20 then
            raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'targetCommunityIds';
        end if;
        v_raw := '{}'::uuid[];
        for v_i in 0 .. jsonb_array_length(p_patch -> 'targetCommunityIds') - 1 loop
            if jsonb_typeof(p_patch -> 'targetCommunityIds' -> v_i) is distinct from 'string' then
                raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'targetCommunityIds';
            end if;
            v_raw := v_raw || private.slice1_uuid(p_patch -> 'targetCommunityIds' ->> v_i, 'targetCommunityIds');
        end loop;
        select coalesce(array_agg(s.id order by s.first_ord), '{}'::uuid[]) into v_targets
        from (select t.id, min(t.ord) as first_ord from unnest(v_raw) with ordinality as t(id, ord) group by t.id) s;
        if (select count(*) from public.comunidades_autonomas c where c.id = any (v_targets)) <> cardinality(v_targets) then
            raise exception using errcode = '22023', message = 'UNKNOWN_COMMUNITY';
        end if;
        select coalesce(array_agg(o.comunidad_id order by o.prioridad, o.comunidad_id), '{}'::uuid[]) into v_current
        from public.comunidades_objetivo_usuario o where o.usuario_id = p_usuario_id;
        if v_current is distinct from v_targets then
            delete from public.comunidades_objetivo_usuario where usuario_id = p_usuario_id;
            insert into public.comunidades_objetivo_usuario (usuario_id, comunidad_id, prioridad)
            select p_usuario_id, t.id, t.ord::integer from unnest(v_targets) with ordinality as t(id, ord);
            v_touch := true;
        end if;
    end if;

    if v_touch
       or (v_username, v_nombre, v_apellidos, v_esp, v_com, v_intro) is distinct from
          (v_p.username, v_p.nombre, v_p.apellidos, v_p.especialidad_principal_id,
           v_p.comunidad_principal_id, v_p.perfil_intro_visto_en) then
        begin
            update public.perfiles_usuario
            set username = v_username, nombre = v_nombre, apellidos = v_apellidos,
                especialidad_principal_id = v_esp, comunidad_principal_id = v_com,
                perfil_intro_visto_en = v_intro
            where usuario_id = p_usuario_id;
        exception when unique_violation then
            raise exception using errcode = '23505', message = 'USERNAME_TAKEN';
        end;
    end if;

    return private.slice1_profile_json(p_usuario_id);
end;
$$;

-- app_aceptar_documentos_legales --------------------------------------------------------------
create or replace function public.app_aceptar_documentos_legales(
    p_usuario_id uuid,
    p_documentos uuid[],
    p_plataforma text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_docs uuid[];
    v_n integer;
    v_pending jsonb;
begin
    perform private.slice1_lock_profile(p_usuario_id);

    select coalesce(array_agg(distinct d), '{}'::uuid[]) into v_docs from unnest(p_documentos) d where d is not null;
    if cardinality(v_docs) not between 1 and 5 then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'documentos';
    end if;
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

    -- Only the OpenAPI LegalDocumentType values (terminos|privacidad) are ever returned.
    select coalesce(jsonb_agg(jsonb_build_object('id', q.id, 'tipo', q.tipo_documento, 'version', q.version)
                              order by q.tipo_documento, q.publicado_en, q.id), '[]'::jsonb)
    into v_pending
    from (
        select d.id, d.tipo_documento, d.version, d.publicado_en
        from public.documentos_legales d
        where d.activo and d.tipo_documento in ('terminos', 'privacidad')
          and not exists (select 1 from public.aceptaciones_legales a
                          where a.usuario_id = p_usuario_id and a.documento_id = d.id)
        order by d.tipo_documento, d.publicado_en, d.id
        limit 20
    ) q;

    return jsonb_build_object('pendingLegalDocuments', v_pending);
end;
$$;

-- Privileges: service_role only ----------------------------------------------------------------
revoke all on function public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text)
    from public, anon, authenticated;
revoke all on function public.app_actualizar_perfil(uuid, jsonb, timestamptz) from public, anon, authenticated;
revoke all on function public.app_aceptar_documentos_legales(uuid, uuid[], text) from public, anon, authenticated;
grant execute on function public.app_completar_onboarding(uuid, text, text, text, uuid, uuid[], text, jsonb, text) to service_role;
grant execute on function public.app_actualizar_perfil(uuid, jsonb, timestamptz) to service_role;
grant execute on function public.app_aceptar_documentos_legales(uuid, uuid[], text) to service_role;

commit;
