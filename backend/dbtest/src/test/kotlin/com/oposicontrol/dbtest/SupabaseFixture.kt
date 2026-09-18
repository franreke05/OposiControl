package com.oposicontrol.dbtest

/**
 * Contrato MINIMO de Supabase necesario para ejecutar el candidate de verdad.
 *
 * No reconstruye Supabase: solo lo que las invariantes tocan -- `auth.users`,
 * el catalogo de roles, la tabla de asignaciones, `auth.uid()`, `es_admin()`, la
 * RLS equivalente de MAIN y la funcion legacy que el candidate reemplaza.
 */
object SupabaseFixture {
    /** Roles del cluster. En Supabase `service_role` lleva BYPASSRLS. */
    val ROLES_CLUSTER = """
        create role anon nologin;
        create role authenticated nologin;
        create role service_role nologin bypassrls;
    """.trimIndent()

    val SQL = """
        create extension if not exists pgcrypto;
        create schema if not exists auth;

        create table auth.users (
          id uuid primary key,
          email text unique
        );

        -- Equivalente al `auth.uid()` de Supabase: lee el claim del JWT. Sin
        -- sesion de PostgREST devuelve NULL, que es justo el caso que importa
        -- cuando la RPC corre como DEFINER.
        create or replace function auth.uid() returns uuid
        language sql stable
        as ${'$'}${'$'}
          select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
        ${'$'}${'$'};

        create table public.roles_app (
          id uuid primary key default gen_random_uuid(),
          codigo text not null unique,
          nombre text not null default ''
        );

        create table public.roles_usuario (
          usuario_id uuid not null references auth.users(id) on delete cascade,
          rol_id uuid not null references public.roles_app(id) on delete cascade,
          primary key (usuario_id, rol_id)
        );

        create or replace function public.es_admin() returns boolean
        language sql stable security definer set search_path = ''
        as ${'$'}${'$'}
          select exists (
            select 1 from public.roles_usuario ru
            join public.roles_app r on r.id = ru.rol_id
            where ru.usuario_id = auth.uid()
              and lower(r.codigo) in ('superadmin', 'admin')
          )
        ${'$'}${'$'};

        -- RLS equivalente a la de MAIN:
        -- supabase/migrations/20260803102048_fix_rls_auth_initplan.sql:50
        alter table public.roles_usuario enable row level security;
        create policy roles_usuario_select_propio on public.roles_usuario
          for select using (((usuario_id = (select auth.uid())) or public.es_admin()));

        -- Funcion LEGACY, tal cual esta en MAIN hoy: sin guardas, security
        -- invoker. Es el estado del que parte el candidate.
        create or replace function public.admin_sync_roles(
          p_usuario_id uuid,
          p_role_ids uuid[]
        )
        returns void
        language plpgsql
        security invoker
        set search_path = public
        as ${'$'}${'$'}
        begin
          delete from public.roles_usuario where usuario_id = p_usuario_id;

          insert into public.roles_usuario (usuario_id, rol_id)
          select p_usuario_id, role_id
          from (select distinct unnest(coalesce(p_role_ids, array[]::uuid[])) as role_id) roles;
        end;
        ${'$'}${'$'};

        revoke execute on function public.admin_sync_roles(uuid, uuid[]) from public, anon, authenticated;
        grant execute on function public.admin_sync_roles(uuid, uuid[]) to service_role;

        insert into public.roles_app (id, codigo, nombre) values
          ('11111111-1111-1111-1111-111111111111', 'superadmin', 'Superadministrador'),
          ('22222222-2222-2222-2222-222222222222', 'admin',      'Administrador'),
          ('33333333-3333-3333-3333-333333333333', 'moderador',  'Moderador'),
          ('44444444-4444-4444-4444-444444444444', 'usuario',    'Usuario');

        insert into auth.users (id, email) values
          ('aaaaaaaa-0000-0000-0000-000000000001', 'a@test.local'),
          ('bbbbbbbb-0000-0000-0000-000000000002', 'b@test.local'),
          ('cccccccc-0000-0000-0000-000000000003', 'c@test.local');
    """.trimIndent()

    const val SUPERADMIN = "11111111-1111-1111-1111-111111111111"
    const val ADMIN = "22222222-2222-2222-2222-222222222222"
    const val MODERADOR = "33333333-3333-3333-3333-333333333333"
    const val USUARIO = "44444444-4444-4444-4444-444444444444"

    const val USER_A = "aaaaaaaa-0000-0000-0000-000000000001"
    const val USER_B = "bbbbbbbb-0000-0000-0000-000000000002"
    const val USER_C = "cccccccc-0000-0000-0000-000000000003"
}
