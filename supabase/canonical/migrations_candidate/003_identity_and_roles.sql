-- OPOSICONTROL CANONICAL V1 — UUID IDENTITY AND EXPLICIT ROLES
-- No integer-to-UUID dual write is introduced here. public.usuario is retained
-- until the separately gated final cleanup migration.

begin;

insert into public.roles_app (id, codigo, nombre, descripcion)
select gen_random_uuid(), role_code, role_name, role_description
from (
  values
    ('superadmin', 'Superadministrador', 'Control total del sistema'),
    ('admin', 'Administrador', 'Administracion operativa'),
    ('moderador', 'Moderador', 'Moderacion de contenido y comunidad'),
    ('usuario', 'Usuario', 'Usuario autenticado estandar')
) as required_roles(role_code, role_name, role_description)
where not exists (
  select 1 from public.roles_app existing
  where existing.codigo = required_roles.role_code
);

create or replace function public.is_superadmin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.roles_usuario ru
    join public.roles_app ra on ra.id = ru.rol_id
    where ru.usuario_id = auth.uid()
      and ra.codigo = 'superadmin'
  );
$$;

create or replace function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.is_superadmin()
      or exists (
        select 1
        from public.roles_usuario ru
        join public.roles_app ra on ra.id = ru.rol_id
        where ru.usuario_id = auth.uid()
          and ra.codigo = 'admin'
      );
$$;

create or replace function public.is_moderator_or_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.is_admin()
      or exists (
        select 1
        from public.roles_usuario ru
        join public.roles_app ra on ra.id = ru.rol_id
        where ru.usuario_id = auth.uid()
          and ra.codigo = 'moderador'
      );
$$;

revoke all on function public.is_superadmin() from public, anon, authenticated;
revoke all on function public.is_admin() from public, anon, authenticated;
revoke all on function public.is_moderator_or_admin() from public, anon, authenticated;
grant execute on function public.is_superadmin() to authenticated, service_role;
grant execute on function public.is_admin() to authenticated, service_role;
grant execute on function public.is_moderator_or_admin() to authenticated, service_role;

commit;
