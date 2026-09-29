-- =============================================================================
-- SLICE 1 / WP B2 -- Legal documents, legal acceptances, attribution, profile detail,
--                    target communities
-- =============================================================================
-- Local/branch only. NOT applied to MAIN. Idempotent, one transaction.
--
-- MAIN SHAPES (read-only catalog capture, baseline_main.sql): documentos_legales,
-- atribucion_usuario, perfiles_usuario_detalle and comunidades_objetivo_usuario ALREADY
-- EXIST on MAIN with (nearly) the columns Slice 1 needs. Every statement below is
-- create-if-not-exists / add-column-if-not-exists, so on MAIN this migration only adds:
--   * atribucion_usuario.install_referrer + NOT VALID sanity checks,
--   * table aceptaciones_legales (new) and its trigger,
-- and converges a fresh/branch database to the same shape.
--
-- WHAT THIS MIGRATION DOES
--   1. documentos_legales: create-if-not-exists (MAIN shape) + add-column-if-not-exists of every
--      column the scope needs. Existing RLS (documentos_legales_select_publico) and grants are
--      untouched (public read of ACTIVE documents stays).
--   2. NEW aceptaciones_legales (id, usuario_id -> auth.users ON DELETE NO ACTION [legal
--      retention, DEC-008: never cascade], documento_id -> documentos_legales, tipo_documento,
--      version, plataforma android|ios, aceptado_en default now(), unique (usuario_id,
--      documento_id)). Append-only: trigger blocks UPDATE/DELETE for every role, and service_role
--      only gets SELECT+INSERT. RLS enabled, NO policies, everything revoked from
--      public/anon/authenticated.
--   3. atribucion_usuario: add-column install_referrer (and every other scope column if missing).
--      usuario_id is the primary key = write-once (the RPC uses ON CONFLICT DO NOTHING). NOT VALID
--      checks: plataforma android|ios|web, text length <= 500 (never validated against unknown
--      pre-existing rows). RLS deny-all for clients (no policy).
--   4. perfiles_usuario_detalle and comunidades_objetivo_usuario: create-if-not-exists (MAIN shape)
--      + add-column-if-not-exists of the scope columns; RLS enabled, no policy, anon/authenticated
--      have no privilege. The MAIN check edad >= 14 is kept (OpenAPI minimum 0: see
--      SLICE_1_DB_PLAN.md).
--
-- FORWARD PLAN
--   Slice 1 RPCs (20260930100200) are the only writers; Ktor reads documentos_legales with
--   service_role (D1-16). A later contract step may add a retention/anonymization executor for
--   aceptaciones_legales (RETENTION_POLICY_PENDING; nothing implemented here).
--
-- ROLLBACK / MITIGATION
--   Rollback drops aceptaciones_legales (legal acceptances LOST: export first) and the
--   install_referrer column (data lost). Pre-existing MAIN tables/columns are never dropped.
--
-- COMPATIBILITY WITH OLD KTOR
--   Nothing existing is renamed or narrowed; old Ktor never touched these tables.
--
-- LOCKING
--   New table: none. ADD COLUMN IF NOT EXISTS on atribucion_usuario (empty/tiny): brief ACCESS
--   EXCLUSIVE; ADD CONSTRAINT ... NOT VALID takes SHARE ROW EXCLUSIVE without a scan. lock_timeout 5s.
--
-- DATA LOSS
--   None on apply.
--
-- INDEXES
--   aceptaciones_legales: pk(id), unique(usuario_id, documento_id) (lookup of a user's acceptances),
--   idx_aceptaciones_legales_documento_id (FK). Others unchanged.
--
-- RLS AND GRANTS
--   aceptaciones_legales: RLS on, no policies; revoke all from public/anon/authenticated/service_role,
--   grant select, insert to service_role. Existing tables: RLS forced on (already on MAIN);
--   privileges of anon/authenticated on atribucion/detalle/comunidades_objetivo revoked (already none on
--   MAIN); service_role privileges are NOT narrowed (MAIN keeps them). documentos_legales: public read policy kept.
--
-- ROLLBACK (manual; executed verbatim by dbtest)
-- ROLLBACK-BEGIN
-- begin;
-- drop table if exists public.aceptaciones_legales;
-- drop function if exists private.guard_aceptaciones_append_only();
-- alter table public.atribucion_usuario drop constraint if exists atribucion_usuario_plataforma_chk;
-- alter table public.atribucion_usuario drop constraint if exists atribucion_usuario_texto_len_chk;
-- alter table public.atribucion_usuario drop column if exists install_referrer;
-- commit;
-- ROLLBACK-END
--
-- VERIFY (read-only; every check must report failures = 0. Executed verbatim by dbtest.)
-- VERIFY-BEGIN
-- select 'aceptaciones_no_rls' as check_name,
--        (case when (select relrowsecurity from pg_class where oid = 'public.aceptaciones_legales'::regclass) then 0 else 1 end)::bigint as failures,
--        null::text as info
-- union all
-- select 'aceptaciones_policies', count(*), null from pg_policies where schemaname = 'public' and tablename = 'aceptaciones_legales'
-- union all
-- select 'aceptaciones_client_privileges', count(*), null
-- from information_schema.role_table_grants
-- where table_schema = 'public' and table_name = 'aceptaciones_legales' and grantee in ('anon', 'authenticated', 'PUBLIC')
-- union all
-- select 'aceptaciones_service_role_extra_privileges', count(*), null
-- from information_schema.role_table_grants
-- where table_schema = 'public' and table_name = 'aceptaciones_legales' and grantee = 'service_role'
--   and privilege_type not in ('SELECT', 'INSERT')
-- union all
-- select 'aceptaciones_usuario_fk_not_no_action', count(*), null
-- from pg_constraint where conrelid = 'public.aceptaciones_legales'::regclass and contype = 'f'
--   and confrelid = 'auth.users'::regclass and confdeltype <> 'a'
-- union all
-- select 'detail_tables_client_privileges', count(*), null
-- from information_schema.role_table_grants
-- where table_schema = 'public'
--   and table_name in ('atribucion_usuario', 'perfiles_usuario_detalle', 'comunidades_objetivo_usuario')
--   and grantee in ('anon', 'authenticated', 'PUBLIC')
-- union all
-- select 'detail_tables_policies', count(*), null from pg_policies
-- where schemaname = 'public' and tablename in ('atribucion_usuario', 'perfiles_usuario_detalle', 'comunidades_objetivo_usuario')
-- union all
-- select 'atribucion_install_referrer_missing',
--        (case when exists (select 1 from information_schema.columns where table_schema = 'public'
--                             and table_name = 'atribucion_usuario' and column_name = 'install_referrer') then 0 else 1 end)::bigint, null;
-- VERIFY-END
-- =============================================================================

begin;

set local lock_timeout = '5s';

create schema if not exists private;

-- 1. documentos_legales ---------------------------------------------------------------
create table if not exists public.documentos_legales (
    id uuid not null default gen_random_uuid() primary key,
    tipo_documento text not null,
    version text not null,
    titulo text not null,
    contenido text not null,
    activo boolean not null default false,
    publicado_en timestamptz,
    creado_en timestamptz not null default now(),
    actualizado_en timestamptz not null default now()
);
alter table public.documentos_legales
    add column if not exists tipo_documento text,
    add column if not exists version text,
    add column if not exists titulo text,
    add column if not exists contenido text,
    add column if not exists activo boolean not null default false,
    add column if not exists publicado_en timestamptz;

do $c$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.documentos_legales'::regclass and contype = 'u'
                   and pg_get_constraintdef(oid) like 'UNIQUE (tipo_documento, version)%') then
        alter table public.documentos_legales add constraint documentos_legales_tipo_documento_version_key
            unique (tipo_documento, version);
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.documentos_legales'::regclass
                   and conname = 'documentos_legales_tipo_documento_check') then
        alter table public.documentos_legales add constraint documentos_legales_tipo_documento_check
            check (tipo_documento = any (array['privacidad', 'terminos', 'cookies', 'aviso_legal']));
    end if;
end
$c$;

alter table public.documentos_legales enable row level security;
do $p$
begin
    if not exists (select 1 from pg_policies where schemaname = 'public' and tablename = 'documentos_legales'
                   and policyname = 'documentos_legales_select_publico') then
        create policy documentos_legales_select_publico on public.documentos_legales
            for select to anon, authenticated using (activo = true or public.es_admin());
    end if;
end
$p$;

-- 2. aceptaciones_legales (append-only, never cascades from auth.users) -----------------
create table if not exists public.aceptaciones_legales (
    id uuid primary key default gen_random_uuid(),
    usuario_id uuid not null references auth.users (id) on delete no action,
    documento_id uuid not null references public.documentos_legales (id) on delete no action,
    tipo_documento text not null
        constraint aceptaciones_legales_tipo_chk check (tipo_documento in ('privacidad', 'terminos', 'cookies', 'aviso_legal')),
    version text not null,
    plataforma text not null
        constraint aceptaciones_legales_plataforma_chk check (plataforma in ('android', 'ios')),
    aceptado_en timestamptz not null default now(),
    constraint aceptaciones_legales_usuario_documento_key unique (usuario_id, documento_id)
);
create index if not exists idx_aceptaciones_legales_documento_id on public.aceptaciones_legales (documento_id);

create or replace function private.guard_aceptaciones_append_only()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    raise exception using errcode = '42501', message = 'ACEPTACIONES_LEGALES_APPEND_ONLY';
end;
$$;
revoke all on function private.guard_aceptaciones_append_only() from public, anon, authenticated;

drop trigger if exists trg_aceptaciones_legales_append_only on public.aceptaciones_legales;
create trigger trg_aceptaciones_legales_append_only
before update or delete on public.aceptaciones_legales
for each row execute function private.guard_aceptaciones_append_only();

alter table public.aceptaciones_legales enable row level security;
revoke all on table public.aceptaciones_legales from public, anon, authenticated, service_role;
grant select, insert on table public.aceptaciones_legales to service_role;

-- 3. atribucion_usuario (write-once by primary key) ---------------------------------------
create table if not exists public.atribucion_usuario (
    usuario_id uuid not null primary key references auth.users (id) on delete cascade,
    fuente text,
    canal text,
    campania text,
    referido_por uuid references auth.users (id),
    codigo_referido text,
    plataforma text,
    creado_en timestamptz not null default now(),
    metadatos jsonb not null default '{}'::jsonb
);
alter table public.atribucion_usuario
    add column if not exists fuente text,
    add column if not exists canal text,
    add column if not exists campania text,
    add column if not exists plataforma text,
    add column if not exists install_referrer text,
    add column if not exists codigo_referido text,
    add column if not exists referido_por uuid,
    add column if not exists creado_en timestamptz not null default now();

do $a$
begin
    if not exists (select 1 from pg_constraint where conrelid = 'public.atribucion_usuario'::regclass
                   and conname = 'atribucion_usuario_plataforma_chk') then
        alter table public.atribucion_usuario add constraint atribucion_usuario_plataforma_chk
            check (plataforma is null or plataforma in ('android', 'ios', 'web')) not valid;
    end if;
    if not exists (select 1 from pg_constraint where conrelid = 'public.atribucion_usuario'::regclass
                   and conname = 'atribucion_usuario_texto_len_chk') then
        alter table public.atribucion_usuario add constraint atribucion_usuario_texto_len_chk
            check (char_length(fuente) <= 500 and char_length(canal) <= 500 and char_length(campania) <= 500
                   and char_length(install_referrer) <= 500 and char_length(codigo_referido) <= 64) not valid;
    end if;
end
$a$;

-- 4. perfiles_usuario_detalle / comunidades_objetivo_usuario ------------------------------
create table if not exists public.perfiles_usuario_detalle (
    usuario_id uuid not null primary key references auth.users (id) on delete cascade,
    nombre_completo text,
    edad integer,
    ciudad text,
    estado_laboral text,
    centro_trabajo text,
    tiempo_disponible_estudio_minutos integer,
    franja_preferente_estudio text,
    fecha_examen_objetivo date,
    preferencias_horarias jsonb not null default '{}'::jsonb,
    metadatos jsonb not null default '{}'::jsonb,
    actualizado_en timestamptz not null default now(),
    constraint perfiles_usuario_detalle_edad_check check (edad is null or edad >= 14)
);
alter table public.perfiles_usuario_detalle
    add column if not exists edad integer,
    add column if not exists ciudad text,
    add column if not exists estado_laboral text,
    add column if not exists centro_trabajo text,
    add column if not exists tiempo_disponible_estudio_minutos integer,
    add column if not exists franja_preferente_estudio text,
    add column if not exists fecha_examen_objetivo date,
    add column if not exists actualizado_en timestamptz not null default now();

create table if not exists public.comunidades_objetivo_usuario (
    usuario_id uuid not null references auth.users (id) on delete cascade,
    comunidad_id uuid not null references public.comunidades_autonomas (id),
    prioridad integer not null default 1,
    creado_en timestamptz not null default now(),
    primary key (usuario_id, comunidad_id)
);
alter table public.comunidades_objetivo_usuario
    add column if not exists prioridad integer not null default 1,
    add column if not exists creado_en timestamptz not null default now();
create index if not exists idx_comunidades_objetivo_usuario_comunidad_id
    on public.comunidades_objetivo_usuario (comunidad_id);

alter table public.atribucion_usuario enable row level security;
alter table public.perfiles_usuario_detalle enable row level security;
alter table public.comunidades_objetivo_usuario enable row level security;
revoke all on table public.atribucion_usuario from public, anon, authenticated;
revoke all on table public.perfiles_usuario_detalle from public, anon, authenticated;
revoke all on table public.comunidades_objetivo_usuario from public, anon, authenticated;
-- Fresh/branch databases: service_role needs DML (MAIN already has it; re-granting is a no-op there).
grant select, insert, update, delete on table public.atribucion_usuario to service_role;
grant select, insert, update, delete on table public.perfiles_usuario_detalle to service_role;
grant select, insert, update, delete on table public.comunidades_objetivo_usuario to service_role;

commit;
