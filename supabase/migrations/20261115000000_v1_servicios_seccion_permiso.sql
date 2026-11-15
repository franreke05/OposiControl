-- =============================================================================
-- V1 -- HOTFIX HF1: Control can save Market services (and V1 config / rich reasoning) again: invoker triggers that call revoked private helpers
-- become SECURITY DEFINER.
-- =============================================================================
-- Forward-only. Requires 20261022101500_v1_config_bounds, 20261022102100_v1_razonado_markup and 20261114400000_v1_servicios_catalogo (all in MAIN).
--
-- PRODUCTION BUG (2026-11-15, server 1.3 live): Control > Catalogo > Servicios fails on every create/update whose seccion is empty. Ktor log:
--   Supabase POST /rest/v1/servicios -> 403: {"code":"42501","message":"permission denied for function servicio_seccion_por_tipo"}
-- ROOT CAUSE: 20261114400000 added the BEFORE INSERT OR UPDATE trigger trg_servicios_seccion whose function private.tg_servicios_seccion() was SECURITY
-- INVOKER and calls private.servicio_seccion_por_tipo(text) when NEW.seccion is NULL. The helper was closed with `revoke all ... from public, anon,
-- authenticated`; service_role (the role of the Ktor admin gateway: the service key through PostgREST) never had an explicit EXECUTE, only the PUBLIC
-- one the revoke removed. Control's upsert (INSERT ... ON CONFLICT DO UPDATE, seccion sent as an explicit null when empty) ran the helper as
-- service_role -> 42501. The PG17 gate and backend/dbtest wrote public.servicios as the superuser, so neither saw it.
--
-- SAME DEFECT FOUND BY THE SWEEP (catalog query of the acta, run on the MAIN shape of today; already in MAIN since the V1 window of 2026-10-22):
--   private.tg_configuracion_v1_bounds()  (20261022101500)  trg_configuracion_v1_bounds BEFORE INSERT OR UPDATE on public.configuracion_v1 calls
--       private.config_v1_range(text) on EVERY write -> Control > Configuracion (AdminControlConfig PATCH /rest/v1/configuracion_v1) gets 42501.
--   private.tg_soluciones_markup()        (20261022102100)  trg_soluciones_markup on public.soluciones_pregunta and
--   private.tg_staging_markup()           (20261022102100)  trg_staging_markup on public.preguntas_parseadas_staging call private.markup_valid(jsonb) /
--       private.markup_plain(jsonb) when razonamiento_rico is not null -> a solution or an import row with rich reasoning written by Ktor gets 42501.
-- The 11 migrations of the 1.3 window (20261109100100 .. 20261114500000) have no other case: tg_demo_set_fijo_* (20261111100000) and
-- tg_reto_answer_counts (20261114300000) are already definer; validar_y_calcular_respuesta_intento (20261109100100) and preparar_intento_examen
-- (20261114300000) are invoker but call no function.
--
-- WHAT IT DOES
--   * The four trigger functions become SECURITY DEFINER (owner unchanged: the migration owner, like every definer function of the windows), keep
--     `set search_path = ''` and the SAME body (every name already schema-qualified). A trigger function needs no EXECUTE from the writing role, so they
--     stay revoked from public, anon and authenticated.
--   * The helpers (servicio_seccion_por_tipo, config_v1_range, markup_valid, markup_plain) are NOT opened: still not executable by anon/authenticated
--     (VERIFY) and not granted to service_role.
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate): the four functions of 20261022101500 / 20261022102100 / 20261114400000, verbatim (SECURITY
-- INVOKER). It brings the bugs back.
-- ROLLBACK-BEGIN
-- begin;
-- create or replace function private.tg_servicios_seccion()
-- returns trigger
-- language plpgsql
-- set search_path = ''
-- as $$
-- begin
--   if new.seccion is null then
--     new.seccion := private.servicio_seccion_por_tipo(new.tipo_servicio);
--   end if;
--   return new;
-- end;
-- $$;
-- revoke all on function private.tg_servicios_seccion() from public, anon, authenticated;
-- create or replace function private.tg_configuracion_v1_bounds()
-- returns trigger
-- language plpgsql
-- set search_path = ''
-- as $$
-- declare
--   r integer[] := private.config_v1_range(new.clave);
-- begin
--   if r is null then
--     raise exception using errcode = '23514', message = 'CONFIG_KEY_UNKNOWN', hint = new.clave;
--   end if;
--   if new.clave = 'zona_horaria' then
--     if new.valor_text is null or not exists (select 1 from pg_timezone_names z where z.name = new.valor_text) then
--       raise exception using errcode = '23514', message = 'CONFIG_VALUE_OUT_OF_BOUNDS', hint = new.clave;
--     end if;
--   elsif new.valor_integer is null or new.valor_integer < r[1] or new.valor_integer > r[2] then
--     raise exception using errcode = '23514', message = 'CONFIG_VALUE_OUT_OF_BOUNDS', hint = new.clave || ' ' || r[1] || '..' || r[2];
--   end if;
--   return new;
-- end;
-- $$;
-- revoke all on function private.tg_configuracion_v1_bounds() from public, anon, authenticated;
-- create or replace function private.tg_soluciones_markup()
-- returns trigger
-- language plpgsql
-- set search_path = ''
-- as $$
-- begin
--   if tg_op = 'UPDATE' and new.razonamiento is distinct from old.razonamiento
--      and new.razonamiento_rico is not distinct from old.razonamiento_rico and new.razonamiento_markup is not distinct from old.razonamiento_markup then
--     new.razonamiento_rico := null;
--     new.razonamiento_markup := null;
--   end if;
--   if new.razonamiento_rico is null then
--     new.razonamiento_markup := null;
--   else
--     if not private.markup_valid(new.razonamiento_rico) then raise exception 'MARKUP_INVALID'; end if;
--     if new.razonamiento is distinct from private.markup_plain(new.razonamiento_rico) then raise exception 'MARKUP_PLAIN_MISMATCH'; end if;
--     if new.razonamiento_markup is null then raise exception 'MARKUP_SOURCE_REQUIRED'; end if;
--   end if;
--   return new;
-- end;
-- $$;
-- revoke all on function private.tg_soluciones_markup() from public, anon, authenticated;
-- create or replace function private.tg_staging_markup()
-- returns trigger
-- language plpgsql
-- set search_path = ''
-- as $$
-- begin
--   if new.razonamiento_rico is null then
--     new.razonamiento_markup := null;
--   else
--     if not private.markup_valid(new.razonamiento_rico) then raise exception 'MARKUP_INVALID'; end if;
--     if new.razonamiento is distinct from private.markup_plain(new.razonamiento_rico) then raise exception 'MARKUP_PLAIN_MISMATCH'; end if;
--     if new.razonamiento_markup is null then raise exception 'MARKUP_SOURCE_REQUIRED'; end if;
--   end if;
--   return new;
-- end;
-- $$;
-- revoke all on function private.tg_staging_markup() from public, anon, authenticated;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- with f(fn) as (values ('private.tg_servicios_seccion()'::regprocedure), ('private.tg_configuracion_v1_bounds()'::regprocedure),
--                       ('private.tg_soluciones_markup()'::regprocedure), ('private.tg_staging_markup()'::regprocedure)),
--      h(fn) as (values ('private.servicio_seccion_por_tipo(text)'::regprocedure), ('private.config_v1_range(text)'::regprocedure),
--                       ('private.markup_valid(jsonb)'::regprocedure), ('private.markup_plain(jsonb)'::regprocedure))
-- select 'hf1_trigger_function_not_security_definer', (select count(*) from f join pg_proc p on p.oid = f.fn where not p.prosecdef)::bigint
-- union all
-- select 'hf1_trigger_function_search_path_not_empty',
--        (select count(*) from f join pg_proc p on p.oid = f.fn where p.proconfig is distinct from array['search_path=""'])::bigint
-- union all
-- select 'hf1_trigger_function_owner_cannot_run_helper',
--        (select count(*) from f join pg_proc p on p.oid = f.fn cross join h where not has_function_privilege(p.proowner, h.fn, 'execute'))::bigint
-- union all
-- select 'hf1_trigger_function_executable_by_clients',
--        (select count(*) from f where has_function_privilege('anon', f.fn, 'execute') or has_function_privilege('authenticated', f.fn, 'execute'))::bigint
-- union all
-- select 'hf1_helper_executable_by_clients',
--        (select count(*) from h where has_function_privilege('anon', h.fn, 'execute') or has_function_privilege('authenticated', h.fn, 'execute'))::bigint
-- union all
-- select 'hf1_trigger_missing_or_disabled',
--        (4 - (select count(*) from pg_trigger t where not t.tgisinternal and t.tgenabled <> 'D' and (t.tgrelid, t.tgname, t.tgfoid) in (
--               ('public.servicios'::regclass::oid, 'trg_servicios_seccion'::name, 'private.tg_servicios_seccion()'::regprocedure::oid),
--               ('public.configuracion_v1'::regclass::oid, 'trg_configuracion_v1_bounds'::name, 'private.tg_configuracion_v1_bounds()'::regprocedure::oid),
--               ('public.soluciones_pregunta'::regclass::oid, 'trg_soluciones_markup'::name, 'private.tg_soluciones_markup()'::regprocedure::oid),
--               ('public.preguntas_parseadas_staging'::regclass::oid, 'trg_staging_markup'::name, 'private.tg_staging_markup()'::regprocedure::oid))))::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

do $$
begin
  if to_regprocedure('private.tg_servicios_seccion()') is null or to_regprocedure('private.servicio_seccion_por_tipo(text)') is null
     or to_regprocedure('private.tg_configuracion_v1_bounds()') is null or to_regprocedure('private.config_v1_range(text)') is null
     or to_regprocedure('private.tg_soluciones_markup()') is null or to_regprocedure('private.tg_staging_markup()') is null
     or to_regprocedure('private.markup_valid(jsonb)') is null or to_regprocedure('private.markup_plain(jsonb)') is null then
    raise exception using errcode = 'P0001', message = 'HF1_TRIGGER_DEFINER_PREFLIGHT', hint = 'apply 20261022101500, 20261022102100 and 20261114400000 first';
  end if;
end
$$;

-- Same bodies as their migrations; only SECURITY DEFINER is new (the owner, not the writing role, runs the private helpers).

-- 20261114400000
create or replace function private.tg_servicios_seccion()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.seccion is null then
    new.seccion := private.servicio_seccion_por_tipo(new.tipo_servicio);
  end if;
  return new;
end;
$$;
revoke all on function private.tg_servicios_seccion() from public, anon, authenticated;

-- 20261022101500
create or replace function private.tg_configuracion_v1_bounds()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  r integer[] := private.config_v1_range(new.clave);
begin
  if r is null then
    raise exception using errcode = '23514', message = 'CONFIG_KEY_UNKNOWN', hint = new.clave;
  end if;
  if new.clave = 'zona_horaria' then
    if new.valor_text is null or not exists (select 1 from pg_timezone_names z where z.name = new.valor_text) then
      raise exception using errcode = '23514', message = 'CONFIG_VALUE_OUT_OF_BOUNDS', hint = new.clave;
    end if;
  elsif new.valor_integer is null or new.valor_integer < r[1] or new.valor_integer > r[2] then
    raise exception using errcode = '23514', message = 'CONFIG_VALUE_OUT_OF_BOUNDS', hint = new.clave || ' ' || r[1] || '..' || r[2];
  end if;
  return new;
end;
$$;
revoke all on function private.tg_configuracion_v1_bounds() from public, anon, authenticated;

-- 20261022102100
create or replace function private.tg_soluciones_markup()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' and new.razonamiento is distinct from old.razonamiento
     and new.razonamiento_rico is not distinct from old.razonamiento_rico and new.razonamiento_markup is not distinct from old.razonamiento_markup then
    new.razonamiento_rico := null;
    new.razonamiento_markup := null;
  end if;
  if new.razonamiento_rico is null then
    new.razonamiento_markup := null;
  else
    if not private.markup_valid(new.razonamiento_rico) then raise exception 'MARKUP_INVALID'; end if;
    if new.razonamiento is distinct from private.markup_plain(new.razonamiento_rico) then raise exception 'MARKUP_PLAIN_MISMATCH'; end if;
    if new.razonamiento_markup is null then raise exception 'MARKUP_SOURCE_REQUIRED'; end if;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_soluciones_markup() from public, anon, authenticated;

-- 20261022102100
create or replace function private.tg_staging_markup()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.razonamiento_rico is null then
    new.razonamiento_markup := null;
  else
    if not private.markup_valid(new.razonamiento_rico) then raise exception 'MARKUP_INVALID'; end if;
    if new.razonamiento is distinct from private.markup_plain(new.razonamiento_rico) then raise exception 'MARKUP_PLAIN_MISMATCH'; end if;
    if new.razonamiento_markup is null then raise exception 'MARKUP_SOURCE_REQUIRED'; end if;
  end if;
  return new;
end;
$$;
revoke all on function private.tg_staging_markup() from public, anon, authenticated;

commit;
