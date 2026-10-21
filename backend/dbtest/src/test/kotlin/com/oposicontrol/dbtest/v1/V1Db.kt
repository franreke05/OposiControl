package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * V1: el esquema de examen ACTUAL (las migraciones PENDIENTES de `supabase/baseline/PENDING_MIGRATIONS.txt`, en orden) sobre el
 * PostgreSQL embebido, con dos conexiones reales. Hasta ahora `backend/dbtest` solo cargaba el cuerpo de Slice 3 (`Slice3Db`):
 * las carreras del intento sobre el cuerpo V1 solo existian en el gate PG17, y por tiempos (`sleep 0.4`).
 *
 * Misma entrada que el gate PG17 (`scripts/db-pg17-gate.sh`): `main_shape_subset.sql` (forma de MAIN generada) + los
 * `main_shape_*extra*.sql` + cada pendiente tal cual. Diferencias, todas del motor y no del comportamiento que se prueba:
 * el embebido es PG16 (sin el privilegio `MAINTAIN`, que se quita de los GRANT/REVOKE) y no trae `uuid-ossp`,
 * `pg_stat_statements` ni `supabase_vault` (se quitan sus `CREATE EXTENSION`; `pgcrypto` se crea en `extensions`). Los
 * stubs de `auth` son los de GoTrue que necesitan estas migraciones.
 */
object V1Db {
    private const val MAIN_SUBSET = "supabase/baseline/main_shape_subset.sql"
    private const val PENDING = "supabase/baseline/PENDING_MIGRATIONS.txt"

    // Mismo orden que el gate: extra, extra_plans, auth_users, extra_engagement, extra_import.
    private val EXTRAS = listOf(
        "supabase/baseline/main_shape_subset_extra.sql",
        "supabase/baseline/main_shape_subset_extra_plans.sql",
        "supabase/baseline/main_shape_auth_users.sql",
        "supabase/baseline/main_shape_subset_extra_engagement.sql",
        "supabase/baseline/main_shape_subset_extra_import.sql",
    )

    private val contador = AtomicInteger()
    private val MAINTAIN = Regex("""\bMAINTAIN,\s*|,\s*MAINTAIN\b""", RegexOption.IGNORE_CASE)

    private val PRERREQUISITOS = """
        create schema if not exists extensions;
        create extension if not exists pgcrypto with schema extensions;
        create schema if not exists auth;
        create table auth.users (
          id uuid primary key, email text, aud text, role text, raw_user_meta_data jsonb, raw_app_meta_data jsonb,
          confirmed_at timestamptz, created_at timestamptz default now(), updated_at timestamptz default now(), deleted_at timestamptz,
          is_anonymous boolean not null default false, banned_until timestamptz, last_sign_in_at timestamptz
        );
        create function auth.uid() returns uuid language sql stable
        as ${'$'}${'$'} select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid ${'$'}${'$'};
        create function auth.role() returns text language sql stable
        as ${'$'}${'$'} select nullif(current_setting('request.jwt.claim.role', true), '')::text ${'$'}${'$'};
        create function auth.jwt() returns jsonb language sql stable
        as ${'$'}${'$'} select coalesce(nullif(current_setting('request.jwt.claims', true), ''), '{}')::jsonb ${'$'}${'$'};
        grant usage on schema auth to anon, authenticated, service_role;
        grant usage on schema extensions to anon, authenticated, service_role;
    """.trimIndent()

    private fun adaptado(ruta: String): String =
        Slice0Db.leer(ruta).lines()
            .filterNot { it.trimStart().startsWith("CREATE EXTENSION", ignoreCase = true) }
            .map { linea -> if (Regex("""\b(GRANT|REVOKE)\b""", RegexOption.IGNORE_CASE).containsMatchIn(linea)) linea.replace(MAINTAIN, "") else linea }
            .joinToString("\n")

    val pendientes: List<String> by lazy {
        Slice0Db.leer(PENDING).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private val esquema: List<Pair<String, String>> by lazy {
        (listOf(MAIN_SUBSET) + EXTRAS + pendientes.map { "supabase/migrations/$it.sql" }).map { it to adaptado(it) }
    }

    /** Base nueva con el esquema V1 completo (menos [sin]: control negativo). [bloque] recibe la conexion principal (autocommit). */
    fun <T> escenario(sin: Set<String> = emptySet(), bloque: (Connection) -> T): T {
        check(sin.all { it in pendientes }) { "no es una pendiente: $sin" }
        val nombre = "v1_" + contador.incrementAndGet()
        PostgresHarness.conexionMaestra().use { it.ejecutar("create database $nombre") }
        return PostgresHarness.baseLlamada(nombre).use { c ->
            c.ejecutar(PRERREQUISITOS)
            esquema.filterNot { (ruta, _) -> sin.any { ruta.endsWith("/$it.sql") } }.forEach { (ruta, sql) ->
                try {
                    c.ejecutar(sql)
                } catch (e: java.sql.SQLException) {
                    throw IllegalStateException("$ruta no carga en el embebido: ${e.message}", e)
                }
            }
            bloque(c)
        }
    }
}
