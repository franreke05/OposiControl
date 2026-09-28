package com.oposicontrol.dbtest.slice0

import com.oposicontrol.dbtest.CandidateFiles
import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import java.io.File
import java.sql.Connection
import java.sql.SQLException

/**
 * SLICE 0 / S0-DB: soporte para probar las migraciones REALES del repositorio
 * sobre el PostgreSQL embebido y desechable de [PostgresHarness] (zonky, sin
 * Docker, sin URL de entorno: nunca MAIN).
 *
 * Los ficheros se leen siempre de `supabase/migrations`; el rollback se extrae
 * del bloque `ROLLBACK-BEGIN/END` del propio fichero, asi que lo que se prueba es
 * exactamente lo que se revisa.
 */
object Slice0Db {
    const val IDENTITY = "supabase/migrations/20260928100000_identity_canonical_profile_expand.sql"
    const val IDEMPOTENCY = "supabase/migrations/20260928100100_idempotency_keys.sql"
    private const val LEGACY_USUARIO_BASE = "supabase/migrations/20260803092231_oposibots_usuario_base_table.sql"
    private const val LEGACY_USUARIO_PROFILE = "supabase/migrations/20260803092306_oposibots_auth_profile_rls.sql"

    fun leer(ruta: String): String {
        val f = File(CandidateFiles.raiz, ruta)
        check(f.isFile) { "Falta $ruta" }
        return f.readText().replace("\r\n", "\n")
    }

    fun rollbackDe(ruta: String): String {
        val lineas = leer(ruta).lines()
        val ini = lineas.indexOfFirst { it.trim() == "-- ROLLBACK-BEGIN" }
        val fin = lineas.indexOfFirst { it.trim() == "-- ROLLBACK-END" }
        check(ini >= 0 && fin > ini) { "$ruta no tiene bloque ROLLBACK-BEGIN/END" }
        return lineas.subList(ini + 1, fin).joinToString("\n") { l ->
            check(l.startsWith("--")) { "Linea de rollback sin comentar: $l" }
            l.removePrefix("--").removePrefix(" ")
        }
    }

    /**
     * Forma de MAIN leida por catalogo (information_schema/pg_constraint/pg_trigger,
     * 2026-09-28): perfiles_usuario, especialidades, handle_new_auth_user,
     * establecer_actualizado_en, RLS y grants. Ademas imita los privilegios por
     * defecto de Supabase en `public`, para que los REVOKE de las migraciones se
     * prueben de verdad.
     */
    val PREREQUISITOS = """
        alter table auth.users add column if not exists raw_user_meta_data jsonb;
        grant usage on schema auth to anon, authenticated, service_role;

        create or replace function auth.jwt() returns jsonb
        language sql stable
        as ${'$'}${'$'}
          select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb
        ${'$'}${'$'};

        alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
        alter default privileges in schema public grant all on functions to anon, authenticated, service_role;
        alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;

        create table public.comunidades_autonomas (
          id uuid primary key default gen_random_uuid(),
          nombre text not null
        );

        create table public.especialidades (
          id uuid primary key default gen_random_uuid(),
          codigo text not null unique,
          nombre text not null,
          slug text not null unique,
          descripcion text,
          activa boolean not null default true,
          posicion integer not null default 1,
          creado_en timestamptz not null default now(),
          actualizado_en timestamptz not null default now()
        );

        create table public.perfiles_usuario (
          usuario_id uuid primary key references auth.users(id) on delete cascade,
          nombre_mostrar text,
          username text unique,
          avatar_url text,
          especialidad_principal_id uuid,
          comunidad_principal_id uuid,
          es_invitado boolean not null default false,
          onboarding_completado boolean not null default false,
          creado_en timestamptz not null default now(),
          actualizado_en timestamptz not null default now(),
          constraint perfiles_usuario_especialidad_fk foreign key (especialidad_principal_id)
            references public.especialidades(id) on delete set null,
          constraint perfiles_usuario_comunidad_fk foreign key (comunidad_principal_id)
            references public.comunidades_autonomas(id) on delete set null
        );
        create index idx_perfiles_usuario_username on public.perfiles_usuario (username);

        create or replace function public.establecer_actualizado_en()
        returns trigger language plpgsql set search_path = public
        as ${'$'}${'$'} begin new.actualizado_en = now(); return new; end; ${'$'}${'$'};

        create trigger trg_perfiles_usuario_actualizado_en
        before update on public.perfiles_usuario
        for each row execute function public.establecer_actualizado_en();

        create or replace function public.handle_new_auth_user()
        returns trigger language plpgsql security definer set search_path = public
        as ${'$'}${'$'}
        begin
          insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, avatar_url, actualizado_en)
          values (
            new.id,
            coalesce(new.raw_user_meta_data ->> 'nombre', new.raw_user_meta_data ->> 'name',
                     split_part(coalesce(new.email, 'usuario'), '@', 1)),
            nullif(new.raw_user_meta_data ->> 'username', ''),
            nullif(new.raw_user_meta_data ->> 'avatar_url', ''),
            now()
          )
          on conflict (usuario_id) do update
          set nombre_mostrar = coalesce(public.perfiles_usuario.nombre_mostrar, excluded.nombre_mostrar),
              username = coalesce(public.perfiles_usuario.username, excluded.username),
              avatar_url = coalesce(public.perfiles_usuario.avatar_url, excluded.avatar_url),
              actualizado_en = now();
          return new;
        end;
        ${'$'}${'$'};

        create trigger trg_auth_user_created
        after insert or update of email, raw_user_meta_data on auth.users
        for each row execute function public.handle_new_auth_user();

        alter table public.perfiles_usuario enable row level security;
        create policy perfiles_usuario_insert_propio on public.perfiles_usuario
          for insert with check ((usuario_id = (select auth.uid())));
        create policy perfiles_usuario_select_propio on public.perfiles_usuario
          for select using (((usuario_id = (select auth.uid())) or public.es_admin()));
        create policy perfiles_usuario_update_propio on public.perfiles_usuario
          for update using (((usuario_id = (select auth.uid())) or public.es_admin()))
          with check (((usuario_id = (select auth.uid())) or public.es_admin()));
    """.trimIndent()

    /** Base nueva: fixture Supabase + forma MAIN + migraciones legacy REALES de public.usuario. */
    fun <T> escenario(bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.ejecutar(PREREQUISITOS)
        c.ejecutar(leer(LEGACY_USUARIO_BASE))
        c.ejecutar(leer(LEGACY_USUARIO_PROFILE))
        bloque(c)
    }
}

fun Connection.aplicar(ruta: String) = ejecutar(Slice0Db.leer(ruta))

fun Connection.filas(sql: String): List<List<String?>> =
    createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
        }
    }

/** SQLSTATE del error, o null si la sentencia fue bien. */
fun Connection.sqlStateDe(sql: String): String? =
    try {
        ejecutar(sql)
        null
    } catch (e: SQLException) {
        e.sqlState
    }
