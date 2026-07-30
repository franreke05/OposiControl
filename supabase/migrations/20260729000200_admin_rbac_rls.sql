-- Normalized admin RBAC, explicit grants and RLS.
-- Historical permissive policies are removed only from non-news admin tables.

begin;

create table if not exists public.app_permission (
    code text primary key,
    description text not null,
    created_at timestamptz not null default now()
);

create table if not exists public.role_permission (
    role_id integer not null references public.app_role(id) on delete cascade,
    permission_code text not null references public.app_permission(code) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (role_id, permission_code)
);

insert into public.app_permission(code, description)
values
    ('dashboard.read', 'Ver el resumen administrativo'),
    ('users.read', 'Consultar usuarios administrativos'),
    ('users.write', 'Invitar, editar y desactivar usuarios'),
    ('roles.read', 'Consultar roles y permisos'),
    ('roles.write', 'Editar roles y asignaciones'),
    ('content.read', 'Consultar especialidades, examenes y preguntas'),
    ('content.write', 'Editar contenido de examenes'),
    ('content.publish', 'Publicar o archivar examenes'),
    ('exam_access.read', 'Consultar accesos a examenes'),
    ('exam_access.write', 'Conceder o revocar accesos'),
    ('commerce.read', 'Consultar catalogo y configuracion comercial'),
    ('commerce.write', 'Editar catalogo y ofertas'),
    ('tickets.read', 'Consultar carteras y movimientos'),
    ('tickets.adjust', 'Aplicar ajustes transaccionales de tickets'),
    ('support.read', 'Consultar conversaciones de soporte'),
    ('support.reply', 'Responder conversaciones de soporte'),
    ('support.internal_note', 'Crear notas internas de soporte'),
    ('moderation.read', 'Consultar acciones de moderacion'),
    ('moderation.write', 'Ejecutar acciones de moderacion'),
    ('audit.read', 'Consultar el registro de auditoria'),
    ('settings.read', 'Consultar preferencias administrativas'),
    ('settings.write', 'Editar preferencias administrativas'),
    ('search.read', 'Usar la busqueda administrativa'),
    ('maintenance', 'Ejecutar tareas restringidas de mantenimiento')
on conflict (code) do update
set description = excluded.description;

insert into public.app_role(name, description)
select
    'superadmin',
    'Acceso administrativo completo'
where not exists (
    select 1
    from public.app_role
    where lower(name) = 'superadmin'
      and deleted_at is null
);

-- Preserve access for an existing full-admin role without inventing a new user.
insert into public.role_permission(role_id, permission_code)
select role.id, permission.code
from public.app_role role
cross join public.app_permission permission
where lower(role.name) in ('admin', 'administrator', 'administrador', 'superadmin')
  and role.deleted_at is null
on conflict (role_id, permission_code) do nothing;

create or replace function private.current_usuario_id()
returns integer
language sql
stable
security definer
set search_path = ''
as $$
    select usuario.id
    from public.usuario
    where usuario.auth_user_id = auth.uid()
      and usuario.activo
      and usuario.deleted_at is null
    limit 1
$$;

create or replace function private.has_permission(required_permission text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.usuario
        join public.user_role
          on user_role.usuario_id = usuario.id
        join public.app_role
          on app_role.id = user_role.role_id
        join public.role_permission
          on role_permission.role_id = app_role.id
        join public.app_permission
          on app_permission.code = role_permission.permission_code
        where usuario.auth_user_id = auth.uid()
          and usuario.activo
          and usuario.deleted_at is null
          and app_role.deleted_at is null
          and app_permission.code = required_permission
    )
$$;

create or replace function private.has_any_permission(required_permissions text[])
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select coalesce(
        bool_or(private.has_permission(permission_code)),
        false
    )
    from unnest(required_permissions) as permission_code
$$;

revoke all on function private.current_usuario_id() from public;
revoke all on function private.has_permission(text) from public;
revoke all on function private.has_any_permission(text[]) from public;
grant usage on schema private to authenticated, service_role;
grant execute on function private.current_usuario_id() to authenticated, service_role;
grant execute on function private.has_permission(text) to authenticated, service_role;
grant execute on function private.has_any_permission(text[]) to authenticated, service_role;

create or replace function public.admin_permissions_for_subject(
    p_auth_user_id uuid
)
returns table(permission_code text)
language sql
stable
security definer
set search_path = ''
as $$
    select distinct app_permission.code
    from public.usuario
    join public.user_role
      on user_role.usuario_id = usuario.id
    join public.app_role
      on app_role.id = user_role.role_id
    join public.role_permission
      on role_permission.role_id = app_role.id
    join public.app_permission
      on app_permission.code = role_permission.permission_code
    where usuario.auth_user_id = p_auth_user_id
      and usuario.activo
      and usuario.deleted_at is null
      and app_role.deleted_at is null
    order by app_permission.code
$$;

revoke all on function public.admin_permissions_for_subject(uuid)
from public, anon, authenticated;
grant execute on function public.admin_permissions_for_subject(uuid)
to service_role;

-- Remove derived PII from global search. Source records remain untouched.
delete from public.entity_tag
where lower(entity_type) in (
    'user',
    'usuario',
    'support_message',
    'support_conversation',
    'moderation_action',
    'audit_log',
    'ticket_transaction',
    'transaccion_ticket'
);

delete from public.search_index
where lower(entity_type) in (
    'user',
    'usuario',
    'support_message',
    'support_conversation',
    'moderation_action',
    'audit_log',
    'ticket_transaction',
    'transaccion_ticket'
);

create or replace function private.reject_sensitive_search_entry()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    if lower(new.entity_type) in (
        'user',
        'usuario',
        'support_message',
        'support_conversation',
        'moderation_action',
        'audit_log',
        'ticket_transaction',
        'transaccion_ticket'
    ) then
        raise exception 'Sensitive entity type cannot be indexed: %', new.entity_type
            using errcode = '22023';
    end if;
    return new;
end;
$$;

drop trigger if exists reject_sensitive_search_entry on public.search_index;
create trigger reject_sensitive_search_entry
before insert or update on public.search_index
for each row execute function private.reject_sensitive_search_entry();

-- Remove every historical policy from the managed tables before applying RBAC.
do $$
declare
    managed_table text;
    existing_policy record;
begin
    foreach managed_table in array array[
        'app_role',
        'app_permission',
        'role_permission',
        'especialidad',
        'usuario',
        'user_role',
        'examen',
        'acceso_examen_usuario',
        'pregunta',
        'opcion_pregunta',
        'recurso_pregunta',
        'articulo_tienda',
        'cartera_tickets',
        'compra_usuario',
        'inventario_usuario',
        'transaccion_ticket',
        'oferta_tienda',
        'recurso_temporal_tienda',
        'campana_rafaga',
        'support_conversation',
        'support_message',
        'support_participant',
        'moderation_action',
        'audit_log',
        'tag',
        'search_index',
        'entity_tag',
        'admin_preference'
    ]
    loop
        execute format('alter table public.%I enable row level security', managed_table);
        for existing_policy in
            select policyname
            from pg_policies
            where schemaname = 'public'
              and tablename = managed_table
        loop
            execute format(
                'drop policy %I on public.%I',
                existing_policy.policyname,
                managed_table
            );
        end loop;
    end loop;
end;
$$;

do $$
declare
    policy_config record;
    sequence_name text;
begin
    for policy_config in
        select *
        from (
            values
                ('app_role', 'roles.read', 'roles.write'),
                ('app_permission', 'roles.read', null),
                ('role_permission', 'roles.read', 'roles.write'),
                ('especialidad', 'content.read', 'content.write'),
                ('usuario', 'users.read', 'users.write'),
                ('user_role', 'users.read', null),
                ('examen', 'content.read', 'content.write'),
                ('acceso_examen_usuario', 'exam_access.read', null),
                ('pregunta', 'content.read', 'content.write'),
                ('opcion_pregunta', 'content.read', 'content.write'),
                ('recurso_pregunta', 'content.read', 'content.write'),
                ('articulo_tienda', 'commerce.read', 'commerce.write'),
                ('cartera_tickets', 'tickets.read', null),
                ('compra_usuario', 'commerce.read', null),
                ('inventario_usuario', 'commerce.read', null),
                ('transaccion_ticket', 'tickets.read', null),
                ('oferta_tienda', 'commerce.read', 'commerce.write'),
                ('recurso_temporal_tienda', 'commerce.read', 'commerce.write'),
                ('campana_rafaga', 'commerce.read', 'commerce.write'),
                ('support_conversation', 'support.read', 'support.reply'),
                ('support_message', 'support.read', null),
                ('support_participant', 'support.read', null),
                ('moderation_action', 'moderation.read', null),
                ('audit_log', 'audit.read', null),
                ('tag', 'search.read', 'maintenance'),
                ('search_index', 'search.read', 'maintenance'),
                ('entity_tag', 'search.read', 'maintenance')
        ) as configured(table_name, read_permission, write_permission)
    loop
        execute format(
            'revoke all on table public.%I from public, anon, authenticated',
            policy_config.table_name
        );
        execute format(
            'grant select on table public.%I to authenticated',
            policy_config.table_name
        );
        execute format(
            'create policy oposicontrol_admin_select on public.%I ' ||
            'for select to authenticated using (private.has_permission(%L))',
            policy_config.table_name,
            policy_config.read_permission
        );

        if policy_config.write_permission is not null then
            execute format(
                'grant insert, update, delete on table public.%I to authenticated',
                policy_config.table_name
            );
            execute format(
                'create policy oposicontrol_admin_insert on public.%I ' ||
                'for insert to authenticated with check (private.has_permission(%L))',
                policy_config.table_name,
                policy_config.write_permission
            );
            execute format(
                'create policy oposicontrol_admin_update on public.%I ' ||
                'for update to authenticated ' ||
                'using (private.has_permission(%L)) ' ||
                'with check (private.has_permission(%L))',
                policy_config.table_name,
                policy_config.write_permission,
                policy_config.write_permission
            );
            execute format(
                'create policy oposicontrol_admin_delete on public.%I ' ||
                'for delete to authenticated using (private.has_permission(%L))',
                policy_config.table_name,
                policy_config.write_permission
            );
        end if;

        select pg_get_serial_sequence(
            format('public.%I', policy_config.table_name),
            'id'
        )
        into sequence_name;
        if sequence_name is not null then
            execute format(
                'grant usage, select on sequence %s to authenticated',
                sequence_name
            );
        end if;
    end loop;
end;
$$;

revoke all on table public.admin_preference from public, anon, authenticated;
grant select, insert, update on table public.admin_preference to authenticated;

create policy oposicontrol_admin_preference_select
on public.admin_preference
for select
to authenticated
using (
    auth_user_id = auth.uid()
    and private.has_permission('settings.read')
);

create policy oposicontrol_admin_preference_insert
on public.admin_preference
for insert
to authenticated
with check (
    auth_user_id = auth.uid()
    and private.has_permission('settings.write')
);

create policy oposicontrol_admin_preference_update
on public.admin_preference
for update
to authenticated
using (
    auth_user_id = auth.uid()
    and private.has_permission('settings.write')
)
with check (
    auth_user_id = auth.uid()
    and private.has_permission('settings.write')
);

-- Public may never forge audit or idempotency records.
revoke all on table public.audit_log from anon;
revoke all on table private.api_idempotency from public, anon, authenticated;
revoke all on table private.migration_guardrail from public, anon, authenticated;
revoke all on table private.storage_cleanup_outbox from public, anon, authenticated;

commit;
