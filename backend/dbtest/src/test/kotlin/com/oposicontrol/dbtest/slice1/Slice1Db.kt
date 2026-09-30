package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import org.postgresql.util.PSQLException

/**
 * SLICE 1 / WP B: soporte para probar las migraciones REALES de Slice 1 sobre el
 * PostgreSQL embebido (nunca MAIN). Reutiliza el fixture de Slice 0 y anade la forma
 * de MAIN de las tablas que Slice 1 usa y que ya existen alli (baseline_main.sql,
 * lectura de catalogo): documentos_legales, atribucion_usuario, perfiles_usuario_detalle,
 * comunidades_objetivo_usuario y auditoria_log.
 */
object Slice1Db {
    const val GUARDS = "supabase/migrations/20260930100000_slice1_profile_guards.sql"
    const val LEGAL = "supabase/migrations/20260930100100_slice1_legal_attribution_detail.sql"
    const val RPCS = "supabase/migrations/20260930100200_slice1_profile_command_rpcs.sql"
    const val DELETION = "supabase/migrations/20260930100300_slice1_account_deletion_requests.sql"
    /** Audit loop 1 (MASTER-DB-001/003/004/006/008): forward fix, the four files above stay untouched. */
    const val HARDENING = "supabase/migrations/20260930100500_slice1_audit_hardening.sql"
    /** Audit loop 2 (MASTER2-AUTH-009): 'hecha' terminal en app_registrar_revocacion_sesiones; forward fix. */
    const val REVOCATION_MONOTONIC = "supabase/migrations/20260930101500_slice1_session_revocation_monotonic.sql"
    val TODAS = listOf(GUARDS, LEGAL, RPCS, DELETION, HARDENING, REVOCATION_MONOTONIC)

    const val USER_D = "dddddddd-0000-0000-0000-000000000004"
    const val USER_E = "eeeeeeee-0000-0000-0000-000000000005"
    const val USER_F = "ffffffff-0000-0000-0000-000000000006"

    const val ESP1 = "e1000000-0000-0000-0000-000000000001"
    const val ESP2 = "e1000000-0000-0000-0000-000000000002"
    const val ESP_OFF = "e1000000-0000-0000-0000-000000000003"
    const val COM1 = "c1000000-0000-0000-0000-000000000001"
    const val COM2 = "c1000000-0000-0000-0000-000000000002"
    const val COM3 = "c1000000-0000-0000-0000-000000000003"
    const val DOC_TERMS = "d1000000-0000-0000-0000-000000000001"
    const val DOC_PRIV = "d1000000-0000-0000-0000-000000000002"
    const val DOC_OLD = "d1000000-0000-0000-0000-000000000003"
    const val DOC_COOKIES = "d1000000-0000-0000-0000-000000000004"
    const val NOEXISTE = "99999999-9999-9999-9999-999999999999"

    /** Forma de MAIN (baseline_main.sql) + privilegios de MAIN de esas tablas. */
    val MAIN_SHAPES = """
        create table public.documentos_legales (
          id uuid not null default gen_random_uuid() primary key,
          tipo_documento text not null, version text not null, titulo text not null, contenido text not null,
          activo boolean not null default false, publicado_en timestamptz,
          creado_en timestamptz not null default now(), actualizado_en timestamptz not null default now(),
          constraint documentos_legales_tipo_documento_version_key unique (tipo_documento, version),
          constraint documentos_legales_tipo_documento_check
            check (tipo_documento = any (array['privacidad'::text, 'terminos'::text, 'cookies'::text, 'aviso_legal'::text]))
        );
        alter table public.documentos_legales enable row level security;
        create policy documentos_legales_select_publico on public.documentos_legales
          for select to anon, authenticated using (((activo = true) or public.es_admin()));
        create trigger trg_documentos_legales_actualizado_en before update on public.documentos_legales
          for each row execute function public.establecer_actualizado_en();

        create table public.atribucion_usuario (
          usuario_id uuid not null primary key references auth.users(id) on delete cascade,
          fuente text, canal text, campania text, referido_por uuid references auth.users(id),
          codigo_referido text, plataforma text, creado_en timestamptz not null default now(),
          metadatos jsonb not null default '{}'::jsonb
        );
        create index idx_atribucion_usuario_referido_por on public.atribucion_usuario (referido_por);
        alter table public.atribucion_usuario enable row level security;

        create table public.perfiles_usuario_detalle (
          usuario_id uuid not null primary key references auth.users(id) on delete cascade,
          nombre_completo text, edad integer, ciudad text, estado_laboral text, centro_trabajo text,
          tiempo_disponible_estudio_minutos integer, franja_preferente_estudio text, fecha_examen_objetivo date,
          preferencias_horarias jsonb not null default '{}'::jsonb, metadatos jsonb not null default '{}'::jsonb,
          actualizado_en timestamptz not null default now(),
          constraint perfiles_usuario_detalle_edad_check check (((edad is null) or (edad >= 14)))
        );
        alter table public.perfiles_usuario_detalle enable row level security;

        create table public.comunidades_objetivo_usuario (
          usuario_id uuid not null references auth.users(id) on delete cascade,
          comunidad_id uuid not null references public.comunidades_autonomas(id),
          prioridad integer not null default 1, creado_en timestamptz not null default now(),
          primary key (usuario_id, comunidad_id)
        );
        create index idx_comunidades_objetivo_usuario_comunidad_id on public.comunidades_objetivo_usuario (comunidad_id);
        alter table public.comunidades_objetivo_usuario enable row level security;

        create table public.auditoria_log (
          id uuid not null default gen_random_uuid() primary key,
          usuario_id uuid references auth.users(id) on delete set null,
          accion text not null, tabla_afectada text, registro_id uuid, antes jsonb, despues jsonb,
          ip text, user_agent text, creado_en timestamptz not null default now()
        );
        create index idx_auditoria_log_usuario_id on public.auditoria_log (usuario_id);
        alter table public.auditoria_log enable row level security;
        create policy auditoria_admin_select on public.auditoria_log for select to authenticated using (public.es_admin());

        revoke all on table public.documentos_legales from public, anon, authenticated, service_role;
        grant select on table public.documentos_legales to anon, authenticated;
        grant all on table public.documentos_legales to service_role;
        revoke all on table public.atribucion_usuario, public.perfiles_usuario_detalle, public.comunidades_objetivo_usuario
          from public, anon, authenticated, service_role;
        grant all on table public.atribucion_usuario, public.perfiles_usuario_detalle, public.comunidades_objetivo_usuario to service_role;
        revoke all on table public.auditoria_log from public, anon, authenticated, service_role;
        grant select on table public.auditoria_log to anon, authenticated;
        grant all on table public.auditoria_log to service_role;
    """.trimIndent()

    /** Base nueva: fixture Slice 0 + forma MAIN + migraciones Slice 0 (+ las de Slice 1 si [migraciones]). */
    fun <T> escenario(migraciones: Boolean = true, bloque: (Connection) -> T): T = Slice0Db.escenario { c ->
        c.ejecutar(MAIN_SHAPES)
        c.aplicar(Slice0Db.IDENTITY)
        c.aplicar(Slice0Db.IDEMPOTENCY)
        if (migraciones) TODAS.forEach { c.aplicar(it) }
        bloque(c)
    }

    /** Catalogo + usuarios sinteticos: A activa, B bloqueada, C eliminacion_pendiente, D activa, E sin perfil. */
    fun Connection.sembrar() {
        ejecutar(
            """
            insert into public.especialidades (id, codigo, nombre, slug, activa) values
              ('$ESP1', 'E1', 'Educacion Fisica', 'ef', true),
              ('$ESP2', 'E2', 'Ingles', 'ing', true),
              ('$ESP_OFF', 'E3', 'Inactiva', 'off', false);
            insert into public.comunidades_autonomas (id, nombre) values
              ('$COM1', 'Andalucia'), ('$COM2', 'Aragon'), ('$COM3', 'Asturias');
            insert into public.documentos_legales (id, tipo_documento, version, titulo, contenido, activo, publicado_en) values
              ('$DOC_TERMS', 'terminos', 'v2', 'Terminos', 'texto', true, now() - interval '2 day'),
              ('$DOC_PRIV', 'privacidad', 'v2', 'Privacidad', 'texto', true, now() - interval '1 day'),
              ('$DOC_OLD', 'terminos', 'v1', 'Terminos v1', 'texto', false, now() - interval '30 day'),
              ('$DOC_COOKIES', 'cookies', 'v1', 'Cookies', 'texto', true, now());
            -- A, B y C ya existen en auth.users (fixture base, sin perfil): se les crea el perfil.
            insert into auth.users (id, email, raw_user_meta_data) values
              ('$USER_D', 'd@test.local', null),
              ('$USER_E', 'e@test.local', null);
            insert into public.perfiles_usuario (usuario_id) values
              ('${com.oposicontrol.dbtest.SupabaseFixture.USER_A}'),
              ('${com.oposicontrol.dbtest.SupabaseFixture.USER_B}'),
              ('${com.oposicontrol.dbtest.SupabaseFixture.USER_C}');
            update public.perfiles_usuario set estado_cuenta = 'bloqueada'
              where usuario_id = '${com.oposicontrol.dbtest.SupabaseFixture.USER_B}';
            update public.perfiles_usuario set estado_cuenta = 'eliminacion_pendiente'
              where usuario_id = '${com.oposicontrol.dbtest.SupabaseFixture.USER_C}';
            delete from public.perfiles_usuario where usuario_id = '$USER_E';
            """.trimIndent(),
        )
    }

    /** Resultado de una llamada: valor (texto) o error (SQLSTATE + mensaje + hint). */
    data class Res(val valor: String?, val sqlState: String? = null, val mensaje: String? = null, val hint: String? = null) {
        val ok get() = sqlState == null
        fun campo(clave: String): String? {
            val m = Regex("\"" + Regex.escape(clave) + "\": (null|true|false|\"([^\"]*)\"|[-0-9.]+)").find(valor ?: "") ?: return null
            val v = m.groupValues[1]
            return if (v == "null") null else if (v.startsWith("\"")) m.groupValues[2] else v
        }
    }

    /** Ejecuta [sql] con el rol de API indicado (sesion) y captura resultado o error, sin dejar transaccion abortada. */
    fun Connection.llamar(sql: String, rol: String = "service_role", sub: String? = null): Res {
        ejecutar("set role $rol")
        if (sub != null) ejecutar("select set_config('request.jwt.claim.sub', '$sub', false)")
        return try {
            Res(unico(sql))
        } catch (e: SQLException) {
            val s = (e as? PSQLException)?.serverErrorMessage
            Res(null, e.sqlState, s?.message ?: e.message, s?.hint)
        } finally {
            runCatching { ejecutar("reset role") }
        }
    }

    /** SQLSTATE de una sentencia ejecutada como [rol] (null si fue bien). */
    fun Connection.estadoComo(rol: String, sql: String, sub: String? = null): String? {
        ejecutar("set role $rol")
        if (sub != null) ejecutar("select set_config('request.jwt.claim.sub', '$sub', false)")
        return try {
            ejecutar(sql)
            null
        } catch (e: SQLException) {
            e.sqlState
        } finally {
            runCatching { ejecutar("reset role") }
        }
    }

    fun Connection.onboarding(
        uid: String,
        username: String = "ana_g",
        nombre: String = "Ana",
        apellidos: String = "Garcia",
        esp: String? = ESP1,
        docs: String = "array['$DOC_TERMS','$DOC_PRIV']::uuid[]",
        plataforma: String? = "android",
        attribution: String? = null,
        referral: String? = null,
    ): Res = llamar(
        "select public.app_completar_onboarding('$uid', ${lit(username)}, ${lit(nombre)}, ${lit(apellidos)}, " +
            "${esp?.let { "'$it'::uuid" } ?: "null::uuid"}, $docs, ${lit(plataforma)}, " +
            "${attribution?.let { "'$it'::jsonb" } ?: "null::jsonb"}, ${lit(referral)})::text",
    )

    fun Connection.patch(uid: String, patch: String, ifMatch: String? = null): Res =
        llamar("select public.app_actualizar_perfil('$uid', '$patch'::jsonb, ${ifMatch?.let { "'$it'::timestamptz" } ?: "null::timestamptz"})::text")

    fun Connection.aceptar(uid: String, docs: String, plataforma: String? = "ios"): Res =
        llamar("select public.app_aceptar_documentos_legales('$uid', $docs, ${lit(plataforma)})::text")

    fun lit(s: String?): String = if (s == null) "null::text" else "'" + s.replace("'", "''") + "'"

    fun Connection.n(sql: String): Long = unico(sql)!!.toLong()

    /** Huella del catalogo de public/private para el diff de esquema (nombres, no cuerpos). */
    fun Connection.catalogo(): Set<String> = buildSet {
        fun q(sql: String) = filas(sql).forEach { add(it[0]!!) }
        q("select 'col:' || table_schema || '.' || table_name || '.' || column_name || ':' || data_type || ':' || is_nullable || ':' || coalesce(column_default, '') from information_schema.columns where table_schema in ('public','private')")
        q("select 'con:' || conrelid::regclass::text || '.' || conname || ':' || pg_get_constraintdef(oid) from pg_constraint where connamespace in ('public'::regnamespace,'private'::regnamespace)")
        q("select 'idx:' || schemaname || '.' || tablename || '.' || indexname || ':' || indexdef from pg_indexes where schemaname in ('public','private')")
        q("select 'trg:' || tgrelid::regclass::text || '.' || tgname from pg_trigger where not tgisinternal and (tgrelid::regclass::text not like 'auth.%' or tgrelid = 'auth.users'::regclass)")
        q("select 'fn:' || n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')' || ':' || p.prosecdef::text || ':' || coalesce(p.proconfig::text, '') from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname in ('public','private')")
        q("select 'pol:' || schemaname || '.' || tablename || '.' || policyname || ':' || cmd || ':' || roles::text from pg_policies where schemaname in ('public','private')")
        q("select 'rls:' || c.oid::regclass::text || ':' || c.relrowsecurity::text from pg_class c where c.relnamespace in ('public'::regnamespace,'private'::regnamespace) and c.relkind = 'r'")
        q("select 'acl:' || c.oid::regclass::text || ':' || coalesce(c.relacl::text, '') from pg_class c where c.relnamespace in ('public'::regnamespace,'private'::regnamespace) and c.relkind = 'r'")
        q("select 'facl:' || p.oid::regprocedure::text || ':' || coalesce(p.proacl::text, '') from pg_proc p where p.pronamespace in ('public'::regnamespace,'private'::regnamespace)")
    }
}
