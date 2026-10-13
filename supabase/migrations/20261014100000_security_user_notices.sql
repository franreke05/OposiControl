-- User notices (2026-10-14). Forward-only; creates one table, touches no existing data.
--
-- Until now a moderation warning written from OposiControl was only an audit row: the person warned never saw it. This
-- table is where a notice addressed to ONE user lives until the app shows it. The backend writes a row when an admin
-- registers a warning; OposiBot will read its own rows through the backend (`GET /api/v1/app/me/avisos`) and mark them
-- read (`POST /api/v1/app/me/avisos/{id}/leido`). Those two routes are NOT built yet: they belong to the OposiBot backend
-- phase. The table is created first so that warnings written from now on are already waiting for them.
--
-- Who can touch the table: ONLY the backend (service role). RLS is enabled with NO policies and every privilege is revoked
-- from `public`, `anon` and `authenticated`: a client that talks to PostgREST directly can neither read someone else's
-- notices nor write one for itself or mark one as read.
--
-- `tipo` is a closed vocabulary with one value today (`advertencia`); adding a kind is a new migration that widens the
-- CHECK. `creado_por` is the admin who wrote it (SET NULL if that account disappears: the notice survives its author).
--
-- Rollback (manual, loses the stored notices): drop table public.avisos_usuario;

begin;

create table public.avisos_usuario (
    id uuid primary key default gen_random_uuid(),
    usuario_id uuid not null references auth.users (id) on delete cascade,
    tipo text not null,
    mensaje text not null,
    creado_en timestamptz not null default now(),
    creado_por uuid references auth.users (id) on delete set null,
    leido_en timestamptz,
    constraint avisos_usuario_tipo_chk check (tipo in ('advertencia')),
    constraint avisos_usuario_mensaje_chk check (char_length(btrim(mensaje)) between 1 and 2000),
    -- A notice cannot have been read before it existed.
    constraint avisos_usuario_leido_chk check (leido_en is null or leido_en >= creado_en)
);

comment on table public.avisos_usuario is
    'Avisos dirigidos a un usuario (hoy: advertencias de moderacion). Solo el backend (service_role): RLS sin politicas y permisos revocados a clientes.';

-- The app asks "my notices, newest first" and, most of the time, "my unread ones".
create index avisos_usuario_usuario_idx on public.avisos_usuario (usuario_id, creado_en desc);

create index avisos_usuario_pendientes_idx on public.avisos_usuario (usuario_id) where leido_en is null;

create index avisos_usuario_creado_por_idx on public.avisos_usuario (creado_por) where creado_por is not null;

alter table public.avisos_usuario enable row level security;

revoke all on table public.avisos_usuario from public, anon, authenticated;
grant select, insert, update, delete on table public.avisos_usuario to service_role;

commit;

-- VERIFY-BEGIN
-- select 'table_exists', (count(*) <> 1)::int from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'avisos_usuario' and c.relkind = 'r'
-- union all select 'rls_enabled', (not coalesce(bool_and(c.relrowsecurity), false))::int from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'avisos_usuario'
-- union all select 'no_policies', count(*)::int from pg_policy p join pg_class c on c.oid = p.polrelid join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = 'avisos_usuario'
-- union all select 'anon_has_no_privileges', (has_table_privilege('anon', 'public.avisos_usuario', 'select') or has_table_privilege('anon', 'public.avisos_usuario', 'insert') or has_table_privilege('anon', 'public.avisos_usuario', 'update') or has_table_privilege('anon', 'public.avisos_usuario', 'delete'))::int
-- union all select 'authenticated_has_no_privileges', (has_table_privilege('authenticated', 'public.avisos_usuario', 'select') or has_table_privilege('authenticated', 'public.avisos_usuario', 'insert') or has_table_privilege('authenticated', 'public.avisos_usuario', 'update') or has_table_privilege('authenticated', 'public.avisos_usuario', 'delete'))::int
-- union all select 'service_role_can_write', (not (has_table_privilege('service_role', 'public.avisos_usuario', 'select') and has_table_privilege('service_role', 'public.avisos_usuario', 'insert') and has_table_privilege('service_role', 'public.avisos_usuario', 'update')))::int
-- union all select 'constraints_present', (count(*) <> 3)::int from pg_constraint where conrelid = 'public.avisos_usuario'::regclass and conname in ('avisos_usuario_tipo_chk', 'avisos_usuario_mensaje_chk', 'avisos_usuario_leido_chk')
-- union all select 'foreign_keys_to_auth_users', (count(*) <> 2)::int from pg_constraint where conrelid = 'public.avisos_usuario'::regclass and contype = 'f' and confrelid = 'auth.users'::regclass;
-- VERIFY-END
