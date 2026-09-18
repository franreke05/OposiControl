-- ROLE SYNC GUARD V1 — REVERSIÓN.
--
-- STATUS: CANDIDATE / LOCAL ONLY. NOT APPLIED TO MAIN. APPLY_PENDING_APPROVAL.
--
-- Devuelve `public.admin_sync_roles` EXACTAMENTE al cuerpo documentado en
-- `docs/supabase/20260624_admin_atomic_operations.sql:3-19`, incluido su
-- `security invoker` y su `search_path = public`.
--
-- LEE ESTO ANTES DE EJECUTARLO. Revertir **reabre SEC-006**: a partir de ese
-- momento un array vacío vuelve a borrar todos los roles del usuario y nada
-- impide que desaparezca el último superadministrador. No es una reversión
-- inocua: es volver a un estado del que se sabe que es inseguro. Sólo tiene
-- sentido si la guarda causara una caída peor —por ejemplo, si el propietario de
-- la función no tuviera `SELECT` sobre `auth.users` y toda edición de roles
-- empezara a fallar— y hubiera que recuperar servicio mientras se corrige.
--
-- Antes de ejecutarlo:
--   1. Anotar por qué se revierte, en el registro de incidencias.
--   2. Restaurar el ACL que se hubiera dumpeado en la comprobación previa 5 del
--      README: `create or replace` conserva el ACL, así que este fichero NO lo
--      toca.
--   3. Avisar de que, mientras esté revertido, la única protección vuelve a ser
--      el aviso de la interfaz.
--
-- El helper `admin_role_code_canonical` NO se elimina: es inocuo, no lo usa
-- nadie más y borrarlo obligaría a recrearlo al reaplicar. Si se quiere limpieza
-- total, hay un `drop` comentado al final.

begin;

create or replace function public.admin_sync_roles(
  p_usuario_id uuid,
  p_role_ids uuid[]
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
  delete from public.roles_usuario where usuario_id = p_usuario_id;

  insert into public.roles_usuario (usuario_id, rol_id)
  select p_usuario_id, role_id
  from (select distinct unnest(coalesce(p_role_ids, array[]::uuid[])) as role_id) roles;
end;
$$;

revoke execute on function public.admin_sync_roles(uuid, uuid[])
  from public, anon, authenticated;
grant execute on function public.admin_sync_roles(uuid, uuid[])
  to service_role;

-- Limpieza opcional del helper. Comentada a propósito: no estorba y borrarlo
-- obliga a recrearlo al reaplicar la guarda.
-- drop function if exists public.admin_role_code_canonical(text);

do $$
begin
  if to_regprocedure('public.admin_sync_roles(uuid,uuid[])') is null then
    raise exception 'ROLE_SYNC_ROLLBACK_NOT_INSTALLED';
  end if;
end;
$$;

commit;
