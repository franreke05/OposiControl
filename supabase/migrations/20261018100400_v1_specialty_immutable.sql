-- V1 (owner decision 2026-10-16): the specialty of a user is SET ONCE and cannot be changed from the app. Forward-only; local stack and PG17 gate only, NOT applied to MAIN.
--
-- WHAT IS ENFORCED (database level, so no route can bypass it: onboarding, PATCH /me/profile, register, admin edits, raw SQL by an app role)
--   perfiles_usuario.especialidad_principal_id may go NULL -> value (first choice) and value -> the SAME value (no-op). Any other change
--   (value -> another value, value -> NULL) is refused with SQLSTATE OC409, message SPECIALTY_ALREADY_SET. Ktor maps it to 409 SPECIALTY_ALREADY_SET.
--   Side effect to know: `perfiles_usuario_especialidad_fk` is ON DELETE SET NULL, so deleting a specialty that users hold is refused too (a specialty in use
--   is retired by setting activa = false, never deleted).
--
-- ROLLBACK (manual, executed verbatim by the PG17 gate):
-- ROLLBACK-BEGIN
-- begin;
-- drop trigger if exists trg_perfiles_usuario_especialidad_inmutable on public.perfiles_usuario;
-- drop function if exists private.tg_perfiles_usuario_especialidad_inmutable();
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read only; every row must have failures = 0)
-- VERIFY-BEGIN
-- select 'trigger_missing' as check_name,
--        (case when exists (select 1 from pg_trigger where tgname = 'trg_perfiles_usuario_especialidad_inmutable' and tgrelid = 'public.perfiles_usuario'::regclass and not tgisinternal)
--              then 0 else 1 end)::bigint as failures
-- union all
-- select 'trigger_function_executable_by_clients',
--        (case when has_function_privilege('anon', 'private.tg_perfiles_usuario_especialidad_inmutable()', 'execute')
--                or has_function_privilege('authenticated', 'private.tg_perfiles_usuario_especialidad_inmutable()', 'execute') then 1 else 0 end)::bigint;
-- VERIFY-END

begin;

set local lock_timeout = '5s';

create or replace function private.tg_perfiles_usuario_especialidad_inmutable()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if old.especialidad_principal_id is not null and new.especialidad_principal_id is distinct from old.especialidad_principal_id then
    raise exception using errcode = 'OC409', message = 'SPECIALTY_ALREADY_SET';
  end if;
  return new;
end;
$$;
revoke all on function private.tg_perfiles_usuario_especialidad_inmutable() from public, anon, authenticated;

drop trigger if exists trg_perfiles_usuario_especialidad_inmutable on public.perfiles_usuario;
create trigger trg_perfiles_usuario_especialidad_inmutable
  before update of especialidad_principal_id on public.perfiles_usuario
  for each row execute function private.tg_perfiles_usuario_especialidad_inmutable();

commit;
