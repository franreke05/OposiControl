-- =============================================================================
-- SLICE 1 / WP B4 -- Account deletion REQUEST (persistent minimal command + safe status)
-- =============================================================================
-- Local/branch only. NOT applied to MAIN. Idempotent, one transaction.
-- Depends on Slice 0 (estado_cuenta) and 20260930100000/100200 (private helper
-- private.slice1_lock_profile).
--
-- WHAT THIS MIGRATION DOES
--   1. public.solicitudes_eliminacion_cuenta: one row per user (usuario_id primary key = at most one
--      open request, idempotent), usuario_id -> auth.users ON DELETE NO ACTION (NOT cascade: the
--      retained-class tables of DEC-008 must never lose their owner through a cascade; hard delete of
--      auth.users stays impossible for these users), solicitada_en default now(), estado
--      solicitada|en_ejecucion|completada|fallida (default solicitada), revocacion_sesiones
--      pendiente|hecha|fallida (default pendiente), paso_ejecucion smallint default 1, reintentos
--      int default 0, ultimo_error_codigo, actualizado_en. RLS enabled with NO policies; all
--      privileges revoked from public/anon/authenticated/service_role, then select/insert/update
--      granted to service_role only.
--   2. public.app_solicitar_eliminacion_cuenta(p_usuario_id) returns table(estado_cuenta, solicitada_en,
--      ya_pendiente): locks the profile FOR UPDATE; 'activa' -> estado_cuenta = 'eliminacion_pendiente',
--      request row inserted (or reset if a stale row exists after an operator restored the account),
--      auditoria_log row (accion 'cuenta_eliminacion_solicitada', tabla_afectada 'perfiles_usuario'), all in
--      one transaction; 'eliminacion_pendiente' -> returns the ORIGINAL row, nothing written (a missing
--      row is self-healed); 'bloqueada' -> 42501 ACCOUNT_BLOCKED; no profile -> OC404 PROFILE_MISSING;
--      any other value -> 42501 FORBIDDEN.
--   3. public.app_registrar_revocacion_sesiones(p_usuario_id, p_resultado hecha|fallida) returns jsonb:
--      records the outcome of the best-effort inline logout done by Ktor after commit. Never changes
--      estado. No request row -> OC404 NO_DELETION_REQUEST.
--
-- DEFERRED_EXECUTION (by scope, NOT faked): no hard delete of auth.users, no anonymization SQL, no
--   retention periods, no cron/pg_cron/worker. The request row stays estado = 'solicitada' and is only
--   exposed as accountStatus 'eliminacion_pendiente'. The lockout comes from the account gate (Ktor)
--   and the Slice 0 perfiles_usuario DB guard (a non-active profile is read-only for clients).
--   Steps 3-9 (ban+scrub Auth identity, anonymize PII, retain classes) need a later executor slice.
--
-- FORWARD PLAN
--   Ktor AccountDeletionService (D1-19) calls the RPCs with service_role after re-authentication.
--   A later slice adds the executor that moves estado solicitada -> en_ejecucion -> completada.
--
-- ROLLBACK / MITIGATION
--   Rollback drops the table and functions. EXPORT solicitudes_eliminacion_cuenta AND every profile with
--   estado_cuenta = 'eliminacion_pendiente' first: dropping the table loses the request timestamps and
--   accounts would stay 'eliminacion_pendiente' (locked out) with no request row (the RPC self-heals a
--   missing row on the next call).
--
-- COMPATIBILITY WITH OLD KTOR
--   New objects only. Old Ktor has no deletion route.
--
-- LOCKING
--   New table/functions: no lock on existing objects. Runtime: one profile row lock per call.
--
-- DATA LOSS
--   None on apply.
--
-- INDEXES
--   Primary key (usuario_id). No other index needed (executor scans by estado later).
--
-- RLS AND GRANTS
--   Table: RLS on, no policies, service_role select/insert/update only. RPCs: SECURITY DEFINER,
--   search_path '', EXECUTE service_role only. auditoria_log is written by the RPC (owner), no new grant.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop function if exists public.app_registrar_revocacion_sesiones(uuid, text);
-- drop function if exists public.app_solicitar_eliminacion_cuenta(uuid);
-- drop table if exists public.solicitudes_eliminacion_cuenta;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read-only; every check must report failures = 0. Executed verbatim by dbtest.)
-- VERIFY-BEGIN
-- select 'solicitudes_no_rls' as check_name,
--        (case when (select relrowsecurity from pg_class where oid = 'public.solicitudes_eliminacion_cuenta'::regclass) then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'solicitudes_policies', count(*), null from pg_policies
-- where schemaname = 'public' and tablename = 'solicitudes_eliminacion_cuenta'
-- union all
-- select 'solicitudes_client_privileges', count(*), null
-- from information_schema.role_table_grants
-- where table_schema = 'public' and table_name = 'solicitudes_eliminacion_cuenta' and grantee in ('anon', 'authenticated', 'PUBLIC')
-- union all
-- select 'solicitudes_service_role_privileges_unexpected', count(*), null
-- from information_schema.role_table_grants
-- where table_schema = 'public' and table_name = 'solicitudes_eliminacion_cuenta' and grantee = 'service_role'
--   and privilege_type not in ('SELECT', 'INSERT', 'UPDATE')
-- union all
-- select 'solicitudes_usuario_fk_not_no_action', count(*), null
-- from pg_constraint where conrelid = 'public.solicitudes_eliminacion_cuenta'::regclass and contype = 'f'
--   and confrelid = 'auth.users'::regclass and confdeltype <> 'a'
-- union all
-- select 'pending_profile_without_request', count(*), null
-- from public.perfiles_usuario p
-- where p.estado_cuenta = 'eliminacion_pendiente'
--   and not exists (select 1 from public.solicitudes_eliminacion_cuenta s where s.usuario_id = p.usuario_id)
-- union all
-- select 'rpc_execute_outside_service_role', count(*), null
-- from pg_proc p join pg_namespace n on n.oid = p.pronamespace
-- cross join (values ('anon'), ('authenticated'), ('public')) r(rol)
-- where n.nspname = 'public' and p.proname in ('app_solicitar_eliminacion_cuenta', 'app_registrar_revocacion_sesiones')
--   and has_function_privilege(r.rol, p.oid, 'execute');
-- VERIFY-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

create table if not exists public.solicitudes_eliminacion_cuenta (
    usuario_id uuid primary key references auth.users (id) on delete no action,
    solicitada_en timestamptz not null default now(),
    estado text not null default 'solicitada'
        constraint solicitudes_eliminacion_cuenta_estado_chk
        check (estado in ('solicitada', 'en_ejecucion', 'completada', 'fallida')),
    revocacion_sesiones text not null default 'pendiente'
        constraint solicitudes_eliminacion_cuenta_revocacion_chk
        check (revocacion_sesiones in ('pendiente', 'hecha', 'fallida')),
    paso_ejecucion smallint not null default 1,
    reintentos integer not null default 0,
    ultimo_error_codigo text,
    actualizado_en timestamptz not null default now()
);

alter table public.solicitudes_eliminacion_cuenta enable row level security;
revoke all on table public.solicitudes_eliminacion_cuenta from public, anon, authenticated, service_role;
grant select, insert, update on table public.solicitudes_eliminacion_cuenta to service_role;

create or replace function public.app_solicitar_eliminacion_cuenta(p_usuario_id uuid)
returns table (estado_cuenta text, solicitada_en timestamptz, ya_pendiente boolean)
language plpgsql
security definer
set search_path = ''
as $$
#variable_conflict use_column
declare
    v_p public.perfiles_usuario;
    v_req public.solicitudes_eliminacion_cuenta;
begin
    v_p := private.slice1_lock_profile(p_usuario_id, true);

    if v_p.estado_cuenta = 'activa' then
        update public.perfiles_usuario set estado_cuenta = 'eliminacion_pendiente' where usuario_id = p_usuario_id;
        insert into public.solicitudes_eliminacion_cuenta as s (usuario_id, solicitada_en, actualizado_en)
        values (p_usuario_id, now(), now())
        on conflict (usuario_id) do update
        set solicitada_en = now(), estado = 'solicitada', revocacion_sesiones = 'pendiente',
            paso_ejecucion = 1, reintentos = 0, ultimo_error_codigo = null, actualizado_en = now()
        returning s.* into v_req;
        insert into public.auditoria_log (usuario_id, accion, tabla_afectada, registro_id, antes, despues)
        values (p_usuario_id, 'cuenta_eliminacion_solicitada', 'perfiles_usuario', p_usuario_id,
                jsonb_build_object('estado_cuenta', 'activa'),
                jsonb_build_object('estado_cuenta', 'eliminacion_pendiente'));
        return query select 'eliminacion_pendiente'::text, v_req.solicitada_en, false;
        return;
    end if;

    -- eliminacion_pendiente (the helper rejected every other state): original row, no writes.
    select * into v_req from public.solicitudes_eliminacion_cuenta s where s.usuario_id = p_usuario_id;
    if not found then
        insert into public.solicitudes_eliminacion_cuenta as s (usuario_id, solicitada_en, actualizado_en)
        values (p_usuario_id, now(), now())
        returning s.* into v_req;
    end if;
    return query select 'eliminacion_pendiente'::text, v_req.solicitada_en, true;
end;
$$;

create or replace function public.app_registrar_revocacion_sesiones(p_usuario_id uuid, p_resultado text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_n integer;
begin
    if p_usuario_id is null or p_resultado is null or p_resultado not in ('hecha', 'fallida') then
        raise exception using errcode = '22023', message = 'INVALID_ARGUMENT', hint = 'resultado';
    end if;
    update public.solicitudes_eliminacion_cuenta s
    set revocacion_sesiones = p_resultado,
        actualizado_en = case when s.revocacion_sesiones = p_resultado then s.actualizado_en else now() end
    where s.usuario_id = p_usuario_id;
    get diagnostics v_n = row_count;
    if v_n = 0 then
        raise exception using errcode = 'OC404', message = 'NO_DELETION_REQUEST';
    end if;
    return jsonb_build_object('revocacionSesiones', p_resultado);
end;
$$;

revoke all on function public.app_solicitar_eliminacion_cuenta(uuid) from public, anon, authenticated;
revoke all on function public.app_registrar_revocacion_sesiones(uuid, text) from public, anon, authenticated;
grant execute on function public.app_solicitar_eliminacion_cuenta(uuid) to service_role;
grant execute on function public.app_registrar_revocacion_sesiones(uuid, text) to service_role;

commit;
