-- Admin access requests (2026-10-13). Forward-only; creates one table, touches no existing data.
--
-- Only `admin` and `superadmin` may use OposiControl. A person without access can ASK for it from the login screen: the
-- backend creates the Auth account (no role, e-mail NOT confirmed, so it cannot sign in anywhere) and stores one row here
-- as `pendiente`. A superadmin reviews it in the
-- Usuarios hub: approving confirms the account, grants the role `admin` through the existing audited RPC
-- (`admin_sync_roles_audited`) and marks
-- the row `aprobada`; rejecting marks it `rechazada` and the account stays unconfirmed and without a role.
--
-- Who can touch the table: ONLY the backend (service role). RLS is enabled with NO policies and every privilege is revoked
-- from `public`, `anon` and `authenticated`, so a client that talks to PostgREST directly can neither read who asked nor
-- write a request for itself. The password is never stored here: it goes from the backend to Supabase Auth and nowhere else.
--
-- Rollback (manual, loses the stored requests): drop table public.solicitudes_acceso_admin;

begin;

create table public.solicitudes_acceso_admin (
    id uuid primary key default gen_random_uuid(),
    usuario_id uuid not null references auth.users (id) on delete cascade,
    nombre text not null,
    email text not null,
    motivo text not null,
    estado text not null default 'pendiente',
    creado_en timestamptz not null default now(),
    resuelto_en timestamptz,
    resuelto_por uuid references auth.users (id) on delete set null,
    motivo_resolucion text,
    constraint solicitudes_acceso_admin_usuario_unico unique (usuario_id),
    constraint solicitudes_acceso_admin_estado_chk check (estado in ('pendiente', 'aprobada', 'rechazada')),
    constraint solicitudes_acceso_admin_nombre_chk check (char_length(nombre) between 1 and 120),
    constraint solicitudes_acceso_admin_email_chk check (char_length(email) between 3 and 320),
    constraint solicitudes_acceso_admin_motivo_chk check (char_length(motivo) between 1 and 1000),
    constraint solicitudes_acceso_admin_motivo_resolucion_chk
        check (motivo_resolucion is null or char_length(motivo_resolucion) <= 500),
    -- A pending request has no resolution; a resolved one always has its instant. `resuelto_por` may become null later
    -- (the reviewer's account was deleted: ON DELETE SET NULL), so it is not part of the rule.
    constraint solicitudes_acceso_admin_resolucion_chk check (
        (estado = 'pendiente' and resuelto_en is null and resuelto_por is null and motivo_resolucion is null)
        or (estado <> 'pendiente' and resuelto_en is not null)
    )
);

comment on table public.solicitudes_acceso_admin is
    'Solicitudes de acceso a OposiControl. Solo el backend (service_role): RLS sin politicas y permisos revocados a clientes.';

-- The review list: pending first (resuelto_en is null), then the most recently resolved.
create index solicitudes_acceso_admin_revision_idx
    on public.solicitudes_acceso_admin (resuelto_en desc nulls first, creado_en desc);

create index solicitudes_acceso_admin_resuelto_por_idx
    on public.solicitudes_acceso_admin (resuelto_por)
    where resuelto_por is not null;

alter table public.solicitudes_acceso_admin enable row level security;

revoke all on table public.solicitudes_acceso_admin from public, anon, authenticated;
grant select, insert, update, delete on table public.solicitudes_acceso_admin to service_role;

commit;

-- VERIFY-BEGIN
-- select 'table_exists', (count(*) <> 1)::int from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'solicitudes_acceso_admin' and c.relkind = 'r'
-- union all select 'rls_enabled', (not coalesce(bool_and(c.relrowsecurity), false))::int from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'solicitudes_acceso_admin'
-- union all select 'no_policies', count(*)::int from pg_policy p join pg_class c on c.oid = p.polrelid join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'solicitudes_acceso_admin'
-- union all select 'anon_has_no_privileges', (has_table_privilege('anon', 'public.solicitudes_acceso_admin', 'select') or has_table_privilege('anon', 'public.solicitudes_acceso_admin', 'insert') or has_table_privilege('anon', 'public.solicitudes_acceso_admin', 'update') or has_table_privilege('anon', 'public.solicitudes_acceso_admin', 'delete'))::int
-- union all select 'authenticated_has_no_privileges', (has_table_privilege('authenticated', 'public.solicitudes_acceso_admin', 'select') or has_table_privilege('authenticated', 'public.solicitudes_acceso_admin', 'insert') or has_table_privilege('authenticated', 'public.solicitudes_acceso_admin', 'update') or has_table_privilege('authenticated', 'public.solicitudes_acceso_admin', 'delete'))::int
-- union all select 'service_role_can_write', (not (has_table_privilege('service_role', 'public.solicitudes_acceso_admin', 'select') and has_table_privilege('service_role', 'public.solicitudes_acceso_admin', 'insert') and has_table_privilege('service_role', 'public.solicitudes_acceso_admin', 'update')))::int
-- union all select 'constraints_present', (count(*) <> 7)::int from pg_constraint where conrelid = 'public.solicitudes_acceso_admin'::regclass and conname in ('solicitudes_acceso_admin_usuario_unico', 'solicitudes_acceso_admin_estado_chk', 'solicitudes_acceso_admin_nombre_chk', 'solicitudes_acceso_admin_email_chk', 'solicitudes_acceso_admin_motivo_chk', 'solicitudes_acceso_admin_motivo_resolucion_chk', 'solicitudes_acceso_admin_resolucion_chk')
-- union all select 'foreign_keys_to_auth_users', (count(*) <> 2)::int from pg_constraint where conrelid = 'public.solicitudes_acceso_admin'::regclass and contype = 'f' and confrelid = 'auth.users'::regclass;
-- VERIFY-END
