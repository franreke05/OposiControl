-- OPOSICONTROL CANONICAL V1 V2 — SINGLE AUTHORIZATION SOURCE
-- Reuses the live public.es_admin() and public.es_moderador_o_admin() names.
-- No is_admin/is_superadmin/is_moderator_or_admin family is created.

begin;

create or replace function public.es_admin()
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
      and ra.codigo in ('superadmin', 'admin')
  );
$$;

create or replace function public.es_moderador_o_admin()
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
      and ra.codigo in ('superadmin', 'admin', 'moderador')
  );
$$;

-- Compatibility wrappers remain wrappers; they do not become a second source.
create or replace function public.can_manage_content()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.es_admin();
$$;

create or replace function public.can_moderate()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.es_moderador_o_admin();
$$;

-- UUID/CANONICAL_CANDIDATE conversation access is retained and hardened;
-- it delegates moderation authority to the same canonical source.
create or replace function public.can_access_conversation(p_conversation_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.es_moderador_o_admin()
      or exists (
        select 1
        from public.conversaciones_soporte cs
        where cs.id = p_conversation_id
          and cs.usuario_id = auth.uid()
      );
$$;

revoke all on function public.es_admin() from public, anon, authenticated;
revoke all on function public.es_moderador_o_admin() from public, anon, authenticated;
revoke all on function public.can_manage_content() from public, anon, authenticated;
revoke all on function public.can_moderate() from public, anon, authenticated;
revoke all on function public.can_access_conversation(uuid) from public, anon, authenticated;
grant execute on function public.es_admin() to authenticated, service_role;
grant execute on function public.es_moderador_o_admin() to authenticated, service_role;
grant execute on function public.can_manage_content() to authenticated, service_role;
grant execute on function public.can_moderate() to authenticated, service_role;
grant execute on function public.can_access_conversation(uuid) to authenticated, service_role;

commit;
