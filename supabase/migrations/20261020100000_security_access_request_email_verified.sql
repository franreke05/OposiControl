-- Admin access requests: proof of mailbox ownership (owner plan 2026-10-14, security review 2026-10-14). Forward-only;
-- adds TWO columns and ONE check constraint to public.solicitudes_acceso_admin (20261013100000). Touches no existing value.
--
-- Until now approving a request CONFIRMED the account's e-mail and granted `admin`, so whoever asked for access with
-- somebody else's address became an administrator of a mailbox they never proved they own. Now the applicant receives a
-- 6-digit code at that address and proves it at `POST /v1/auth/admin-access-requests/verify`; the backend then stores the
-- instant here. Approval requires this column to be set (and Supabase Auth to report the e-mail confirmed); approval no
-- longer confirms anything.
--
-- `correo_verificado_en`:
--   null      = the applicant has not proved the mailbox. Such a request does not reserve the address and EXPIRES one hour
--               after `creado_en` (computed by the backend, there is no scheduler: see DEBT-116).
--   not null  = proved at that instant (backend clock).
--   There is deliberately NO ordering check against `resuelto_en` (security review L4): both instants come from
--   application clocks of possibly different backend instances, so a skew would make a legitimate approval fail. The
--   backend only verifies `pendiente` rows (conditional PATCH), which is the real guarantee.
--
-- `intentos_verificacion` (security review M2): wrong codes entered for this request. The backend increments it on each
-- failed verify of a live row (optimistic, conditional on the value it read) and, at 5, releases the request (same rule as
-- an expired one): the applicant must ask again and a new code is mailed. Never negative.
--
-- Every row that exists before this migration gets null / 0: a request created before the change has to be asked again.
--
-- The columns inherit the table's protection: RLS enabled with NO policies and every privilege revoked from `public`,
-- `anon` and `authenticated`; only `service_role` (the backend) reads or writes them. No grant is touched here.
--
-- Security review #3 L1 adds ONE function, public.admin_purge_user_sessions(uuid) (security definer, empty search_path,
-- executable by service_role only); see its comment below.
--
-- Rollback (manual, loses the stored verification instants and counters):
--   drop function public.admin_purge_user_sessions(uuid);
--   alter table public.solicitudes_acceso_admin drop constraint solicitudes_acceso_admin_intentos_verificacion_chk;
--   alter table public.solicitudes_acceso_admin drop column intentos_verificacion;
--   alter table public.solicitudes_acceso_admin drop column correo_verificado_en;

begin;

alter table public.solicitudes_acceso_admin
    add column correo_verificado_en timestamptz;

alter table public.solicitudes_acceso_admin
    add column intentos_verificacion integer not null default 0;

alter table public.solicitudes_acceso_admin
    add constraint solicitudes_acceso_admin_intentos_verificacion_chk
        check (intentos_verificacion >= 0);

comment on column public.solicitudes_acceso_admin.correo_verificado_en is
    'Instante en que el solicitante demostro el buzon con el codigo de 6 cifras. Nulo = sin verificar (caduca 1 h despues de creado_en). Aprobar lo exige.';

comment on column public.solicitudes_acceso_admin.intentos_verificacion is
    'Codigos erroneos introducidos para esta solicitud. Al quinto el backend la libera y hay que volver a pedir el acceso.';

-- Security review #3 L1: a GoTrue ban (`ban_duration`) does NOT delete `auth.sessions`; a session minted before the ban
-- survives it and works again as soon as the account is unbanned at approval. The backend calls this function (service
-- role only) right after banning at verification and right before unbanning at approval / resumed approval, and fails
-- closed if it errors.
--
-- Refresh tokens: GoTrue's `auth.refresh_tokens.session_id` references `auth.sessions(id) on delete cascade` (GoTrue
-- migration 20220811173540), so deleting the sessions removes their tokens. Tokens minted before that column existed have
-- `session_id` null and do not cascade, so they are deleted explicitly by user (`refresh_tokens.user_id` is varchar).
-- This repository does not vendor the `auth` schema: the cascade is checked live by the preflight query of
-- DEPLOYMENT_PLAN 10.6 step 8b, and the explicit delete makes the function correct either way.
-- plpgsql resolves the `auth` tables at call time, so the migration itself does not depend on them.
create or replace function public.admin_purge_user_sessions(p_user_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_sessions integer;
begin
    if p_user_id is null then
        raise exception 'admin_purge_user_sessions: p_user_id is required' using errcode = '22004';
    end if;
    delete from auth.refresh_tokens where user_id = p_user_id::text;
    delete from auth.sessions where user_id = p_user_id;
    get diagnostics v_sessions = row_count;
    return v_sessions;
end;
$$;

revoke all on function public.admin_purge_user_sessions(uuid) from public, anon, authenticated;
grant execute on function public.admin_purge_user_sessions(uuid) to service_role;

comment on function public.admin_purge_user_sessions(uuid) is
    'Borra las sesiones de Auth (y sus refresh tokens) de una cuenta. Solo service_role (el backend): verificacion y aprobacion de solicitudes de acceso.';

commit;

-- VERIFY-BEGIN
-- select 'column_exists_nullable_timestamptz', (count(*) <> 1)::int from information_schema.columns where table_schema = 'public' and table_name = 'solicitudes_acceso_admin' and column_name = 'correo_verificado_en' and data_type = 'timestamp with time zone' and is_nullable = 'YES'
-- union all select 'attempts_column_integer_not_null_default_0', (count(*) <> 1)::int from information_schema.columns where table_schema = 'public' and table_name = 'solicitudes_acceso_admin' and column_name = 'intentos_verificacion' and data_type = 'integer' and is_nullable = 'NO' and column_default = '0'
-- union all select 'attempts_check_present_and_validated', (count(*) <> 1)::int from pg_constraint where conrelid = 'public.solicitudes_acceso_admin'::regclass and conname = 'solicitudes_acceso_admin_intentos_verificacion_chk' and contype = 'c' and convalidated
-- union all select 'no_clock_ordering_check', count(*)::int from pg_constraint where conrelid = 'public.solicitudes_acceso_admin'::regclass and conname = 'solicitudes_acceso_admin_correo_verificado_chk'
-- union all select 'rows_with_negative_attempts', count(*)::int from public.solicitudes_acceso_admin where intentos_verificacion < 0
-- union all select 'rls_still_enabled', (not coalesce(bool_and(c.relrowsecurity), false))::int from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'solicitudes_acceso_admin'
-- union all select 'still_no_policies', count(*)::int from pg_policy p join pg_class c on c.oid = p.polrelid join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'solicitudes_acceso_admin'
-- union all select 'clients_cannot_read_or_write_the_columns', (has_column_privilege('anon', 'public.solicitudes_acceso_admin', 'correo_verificado_en', 'select') or has_column_privilege('anon', 'public.solicitudes_acceso_admin', 'correo_verificado_en', 'update') or has_column_privilege('authenticated', 'public.solicitudes_acceso_admin', 'correo_verificado_en', 'select') or has_column_privilege('authenticated', 'public.solicitudes_acceso_admin', 'correo_verificado_en', 'update') or has_column_privilege('anon', 'public.solicitudes_acceso_admin', 'intentos_verificacion', 'select') or has_column_privilege('anon', 'public.solicitudes_acceso_admin', 'intentos_verificacion', 'update') or has_column_privilege('authenticated', 'public.solicitudes_acceso_admin', 'intentos_verificacion', 'select') or has_column_privilege('authenticated', 'public.solicitudes_acceso_admin', 'intentos_verificacion', 'update'))::int
-- union all select 'service_role_can_write_the_columns', (not (has_column_privilege('service_role', 'public.solicitudes_acceso_admin', 'correo_verificado_en', 'select') and has_column_privilege('service_role', 'public.solicitudes_acceso_admin', 'correo_verificado_en', 'update') and has_column_privilege('service_role', 'public.solicitudes_acceso_admin', 'intentos_verificacion', 'select') and has_column_privilege('service_role', 'public.solicitudes_acceso_admin', 'intentos_verificacion', 'update')))::int
-- union all select 'purge_fn_present_security_definer_empty_search_path', (count(*) <> 1)::int from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public' and p.proname = 'admin_purge_user_sessions' and pg_get_function_identity_arguments(p.oid) = 'p_user_id uuid' and p.prorettype = 'integer'::regtype and p.prosecdef and 'search_path=""' = any(coalesce(p.proconfig, array[]::text[]))
-- union all select 'purge_fn_executable_by_clients', (coalesce(has_function_privilege('anon', to_regprocedure('public.admin_purge_user_sessions(uuid)'), 'execute'), false) or coalesce(has_function_privilege('authenticated', to_regprocedure('public.admin_purge_user_sessions(uuid)'), 'execute'), false) or exists (select 1 from pg_proc p, aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a where p.oid = to_regprocedure('public.admin_purge_user_sessions(uuid)') and a.grantee = 0 and a.privilege_type = 'EXECUTE'))::int
-- union all select 'purge_fn_not_executable_by_service_role', (not coalesce(has_function_privilege('service_role', to_regprocedure('public.admin_purge_user_sessions(uuid)'), 'execute'), false))::int;
-- VERIFY-END
