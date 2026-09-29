package com.oposicontrol.dbtest.slice0

import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.SupabaseFixture.USER_C
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migracion EXPAND + BACKFILL de identidad canonica sobre PostgreSQL embebido.
 * Cubre: expand, backfill (bloque DO dentro de la migracion, rol de la migracion, sin
 * funcion runtime) con cada politica de conflicto, VERIFY (SQL documentado), re-ejecucion,
 * delta tras nuevas escrituras legacy, guardia de estado_cuenta, grants
 * (SERVICE_ROLE_AUTH_USERS_BROAD_SELECT = DENIED), re-aplicacion del fichero y rollback.
 */
class IdentityExpandBackfillMigrationTest {

    private companion object {
        const val USER_D = "dddddddd-0000-0000-0000-000000000004"
        const val USER_E = "eeeeeeee-0000-0000-0000-000000000005"
        const val USER_F = "ffffffff-0000-0000-0000-000000000006"
        const val USER_G = "99999999-0000-0000-0000-000000000007"
        const val EF = "e0000000-0000-0000-0000-000000000001"
    }

    private fun Connection.sembrar() {
        ejecutar(
            """
            insert into public.especialidades (id, codigo, nombre, slug) values
              ('$EF', 'EF', 'Educación Física', 'educacion-fisica'),
              ('e0000000-0000-0000-0000-000000000002', 'ING', 'Inglés', 'ingles'),
              ('e0000000-0000-0000-0000-000000000003', 'X1', 'Duplicada', 'dup-a'),
              ('e0000000-0000-0000-0000-000000000004', 'X2', 'Dup A', 'duplicada');

            -- D: perfil canonico creado por el trigger con username 'taken'.
            insert into auth.users (id, email, raw_user_meta_data) values
              ('$USER_D', 'd@test.local', '{"username":"taken"}'),
              ('$USER_E', 'e@test.local', null),
              ('$USER_F', 'f@test.local', null);

            -- B ya tiene perfil canonico con datos propios.
            insert into public.perfiles_usuario (usuario_id, nombre, username, onboarding_completado)
            values ('$USER_B', 'Beatriz', 'bea', true);

            insert into public.usuario (auth_user_id, email, nombre, apellidos, username, especialidad_registro, perfil_completo, deleted_at) values
              ('$USER_A', 'a@test.local', ' Ana ', 'García', 'ana_g', 'EDUCACION FISICA', true, null),
              ('$USER_B', 'b@test.local', 'Bea', 'López', 'bea2', 'Inexistente', false, null),
              ('$USER_C', 'c@test.local', 'Carlos', null, 'Taken', 'Educacion-Fisica', true, null),
              (null, 'x@test.local', 'Huerfano', null, null, null, false, null),
              ('12345678-0000-0000-0000-000000000000', 'y@test.local', 'Fantasma', null, null, null, true, null),
              ('$USER_E', 'e@test.local', 'Eva', null, 'eva', null, true, now()),
              ('$USER_F', 'f@test.local', '   ', null, null, 'DUPLICADA', false, null);
            """.trimIndent(),
        )
    }

    private fun Connection.perfil(uid: String): List<String?> =
        filas(
            "select nombre, apellidos, username, especialidad_principal_id::text, onboarding_completado::text, estado_cuenta " +
                "from public.perfiles_usuario where usuario_id = '$uid'",
        ).single()

    private fun Connection.reporte(): Set<String> =
        filas(
            "select coalesce(usuario_id::text, '-') || '|' || field || '|' || outcome " +
                "from private.identity_backfill_report",
        ).map { it[0]!! }.toSet()

    /** VERIFY = SQL documentado de la cabecera de la migracion, ejecutado tal cual (rol de la migracion). */
    private fun Connection.verifyFilas(): List<List<String?>> = filas(Slice0Db.verifyDe(Slice0Db.IDENTITY))

    private fun Connection.fallosVerify(): Map<String, Long> =
        verifyFilas().associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.marcaLegacy(): String? =
        verifyFilas().single { it[0] == "legacy_write_watermark" }[2]

    private fun Connection.verifyVerde() {
        val f = fallosVerify()
        assertEquals(8, f.size, "checks de VERIFY: $f")
        assertTrue(f.values.all { it == 0L }, "VERIFY no esta verde: $f")
    }

    /**
     * El backfill vive DENTRO de la migracion (bloque DO, rol de la migracion): no hay funcion runtime.
     * Ejecutarlo = (re)aplicar el fichero, que es idempotente. Es tambien el "delta re-run" de Slice 1.
     */
    private fun Connection.backfill() = aplicar(Slice0Db.IDENTITY)

    private val ROLES_API = listOf("anon", "authenticated", "service_role")

    private fun Connection.instantanea(): List<List<String?>> =
        filas("select * from public.perfiles_usuario order by usuario_id")

    @Test
    fun expandAnadeColumnasCanonicasSinTocarLegacy(): Unit = Slice0Db.escenario { c ->
        c.ejecutar("insert into public.perfiles_usuario (usuario_id) values ('$USER_A')")
        c.aplicar(Slice0Db.IDENTITY)

        val cols = c.filas(
            "select column_name, is_nullable, coalesce(column_default, '') from information_schema.columns " +
                "where table_schema = 'public' and table_name = 'perfiles_usuario' " +
                "and column_name in ('nombre','apellidos','estado_cuenta','perfil_intro_visto_en') order by column_name",
        )
        assertEquals(
            listOf(
                listOf("apellidos", "YES", ""),
                listOf("estado_cuenta", "NO", "'activa'::text"),
                listOf("nombre", "YES", ""),
                listOf("perfil_intro_visto_en", "YES", ""),
            ),
            cols,
        )
        assertEquals("activa", c.unico("select estado_cuenta from public.perfiles_usuario where usuario_id = '$USER_A'"))
        assertEquals("23514", c.sqlStateDe("update public.perfiles_usuario set estado_cuenta = 'foo' where usuario_id = '$USER_A'"))
        assertEquals("23514", c.sqlStateDe("update public.perfiles_usuario set nombre = repeat('x', 101) where usuario_id = '$USER_A'"))
        // FK y ON DELETE intactos (DEC-008 abierto).
        assertEquals(
            "FOREIGN KEY (usuario_id) REFERENCES auth.users(id) ON DELETE CASCADE",
            c.unico("select pg_get_constraintdef(oid) from pg_constraint where conname = 'perfiles_usuario_usuario_id_fkey'"),
        )
        // public.usuario y su RPC legacy siguen en pie.
        assertEquals("1", c.unico("select count(*) from pg_class where oid = to_regclass('public.usuario')"))
        assertEquals("1", c.unico("select count(*) from pg_proc where proname = 'oposibots_upsert_own_profile'"))
        // Con public.usuario vacio el backfill de la migracion es un no-op (caso MAIN esperado).
        assertEquals("0", c.unico("select count(*) from private.identity_backfill_report"))
    }

    @Test
    fun laMigracionEjecutaElBackfillAtomicamenteAlAplicarseConDatosLegacy(): Unit = Slice0Db.escenario { c ->
        c.ejecutar(
            """
            insert into public.especialidades (id, codigo, nombre, slug) values ('$EF', 'EF', 'Educación Física', 'educacion-fisica');
            insert into public.usuario (auth_user_id, email, nombre, apellidos, username, especialidad_registro, perfil_completo)
            values ('$USER_A', 'a@test.local', ' Ana ', 'García', 'ana_g', 'EDUCACION FISICA', true);
            """.trimIndent(),
        )
        // Primera y unica aplicacion: columnas + trigger + backfill en la misma transaccion, sin funcion runtime.
        c.aplicar(Slice0Db.IDENTITY)
        assertEquals(listOf("Ana", "García", "ana_g", EF, "true", "activa"), c.perfil(USER_A))
        assertEquals(
            setOf("$USER_A|profile|profile_created", "$USER_A|nombre|filled", "$USER_A|apellidos|filled", "$USER_A|username|filled",
                "$USER_A|especialidad_principal_id|filled", "$USER_A|onboarding_completado|filled"),
            c.reporte(),
        )
        c.verifyVerde()
    }

    @Test
    fun laMigracionEliminaLasRutinasBorradorDelBackfillSiExisten(): Unit = Slice0Db.escenario { c ->
        c.ejecutar(
            """
            create schema if not exists private;
            grant usage on schema private to service_role;
            create function private.identity_backfill_run() returns void language sql security definer as 'select 1';
            create function private.identity_backfill_verify() returns void language sql security definer as 'select 1';
            create function private.identity_norm_text(text) returns text language sql as 'select ${'$'}1';
            grant execute on function private.identity_backfill_run() to service_role;
            grant execute on function private.identity_backfill_verify() to service_role;
            """.trimIndent(),
        )
        c.aplicar(Slice0Db.IDENTITY)
        assertEquals("0", c.unico("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'private' and p.proname like 'identity_%'"))
    }

    @Test
    fun backfillRellenaSoloNulosYReportaCadaConflicto(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDENTITY)
        c.sembrar()
        c.backfill()

        assertEquals(listOf("Ana", "García", "ana_g", EF, "true", "activa"), c.perfil(USER_A))
        // B: nombre y username canonicos NO se sobrescriben; onboarding no baja.
        assertEquals(listOf("Beatriz", "López", "bea", null, "true", "activa"), c.perfil(USER_B))
        // C: username en colision (case-insensitive con D) se salta; slug casa.
        assertEquals(listOf("Carlos", null, null, EF, "true", "activa"), c.perfil(USER_C))
        assertEquals("taken", c.perfil(USER_D)[2])
        // E: fila legacy borrada -> solo reporte.
        assertEquals(listOf(null, null, null, null, "false", "activa"), c.perfil(USER_E))
        // F: nombre en blanco ignorado; especialidad ambigua sin rellenar.
        assertEquals(listOf(null, null, null, null, "false", "activa"), c.perfil(USER_F))

        assertEquals(
            setOf(
                "$USER_A|profile|profile_created",
                "$USER_A|nombre|filled",
                "$USER_A|apellidos|filled",
                "$USER_A|username|filled",
                "$USER_A|especialidad_principal_id|filled",
                "$USER_A|onboarding_completado|filled",
                "$USER_B|nombre|conflict_kept_existing",
                "$USER_B|apellidos|filled",
                "$USER_B|username|conflict_kept_existing",
                "$USER_B|especialidad_principal_id|specialty_unmatched",
                "$USER_C|profile|profile_created",
                "$USER_C|nombre|filled",
                "$USER_C|username|skipped_username_collision",
                "$USER_C|especialidad_principal_id|filled",
                "$USER_C|onboarding_completado|filled",
                "$USER_F|especialidad_principal_id|specialty_ambiguous",
                "-|row|orphan_no_auth_user",
                "-|row|orphan_unknown_auth_user",
                "$USER_E|row|orphan_deleted",
            ),
            c.reporte(),
        )
        // Huerfanos: ningun perfil nuevo para el auth_user_id desconocido.
        assertEquals("0", c.unico("select count(*) from public.perfiles_usuario where usuario_id = '12345678-0000-0000-0000-000000000000'"))
        c.verifyVerde()
    }

    @Test
    fun reEjecutarElBackfillEsNoOpYVerifyDetectaDeltas(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDENTITY)
        c.sembrar()
        c.backfill()
        val reporte1 = c.reporte()
        val datos1 = c.instantanea()
        val marca1 = c.marcaLegacy()

        c.backfill()
        assertEquals(datos1, c.instantanea(), "la re-ejecucion cambio datos")
        assertEquals(
            reporte1.filterNot { it.endsWith("|filled") || it.endsWith("|profile_created") }.toSet(),
            c.reporte(),
        )
        c.verifyVerde()
        assertEquals(marca1, c.marcaLegacy())

        // Escrituras legacy posteriores (ventana previa al cutover).
        c.ejecutar("update public.usuario set nombre = 'Anita', updated_at = now() + interval '1 second' where auth_user_id = '$USER_A'")
        c.ejecutar("insert into auth.users (id, email) values ('$USER_G', 'g@test.local')")
        c.ejecutar("insert into public.usuario (auth_user_id, email, nombre, perfil_completo) values ('$USER_G', 'g@test.local', 'Gema', true)")
        val f = c.fallosVerify()
        assertEquals(2L, f["nombre_diff"], "VERIFY debe ver ambos deltas (A cambiado, G nuevo): $f")
        assertEquals(1L, f["onboarding_diff"], "VERIFY debe ver ambos deltas: $f")
        assertTrue(marca1 != c.marcaLegacy())

        c.backfill() // delta re-run
        c.verifyVerde()
        assertEquals("Ana", c.perfil(USER_A)[0], "nunca se sobrescribe dato canonico")
        assertTrue("$USER_A|nombre|conflict_kept_existing" in c.reporte())
        assertEquals(listOf("Gema", null, null, null, "true", "activa"), c.perfil(USER_G))
    }

    @Test
    fun elWriterLegacySigueFuncionandoYNoEscribeEnPerfiles(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDENTITY)
        c.ejecutar("insert into public.perfiles_usuario (usuario_id) values ('$USER_A')")
        val antes = c.instantanea()
        c.ejecutar(
            """
            begin;
            set local role authenticated;
            set local request.jwt.claim.sub = '$USER_A';
            set local request.jwt.claims = '{"sub":"$USER_A","is_anonymous":false}';
            select public.oposibots_upsert_own_profile('Ana', 'García', 'ana_g', 'Inglés');
            commit;
            """.trimIndent(),
        )
        assertEquals("Ana", c.unico("select nombre from public.usuario where auth_user_id = '$USER_A'"))
        assertEquals(antes, c.instantanea(), "sin dual-write: el writer legacy no toca perfiles_usuario")
    }

    @Test
    fun estadoCuentaNoEsEscribiblePorClientes(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDENTITY)
        c.ejecutar("insert into public.perfiles_usuario (usuario_id) values ('$USER_A')")

        fun comoCliente(rol: String, sql: String): String? =
            c.sqlStateDe("begin; set local role $rol; set local request.jwt.claim.sub = '$USER_A'; $sql; commit;")
                .also { runCatching { c.ejecutar("rollback") } }

        assertEquals("42501", comoCliente("authenticated", "update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '$USER_A'"))
        assertNull(comoCliente("authenticated", "update public.perfiles_usuario set nombre = 'Ana', perfil_intro_visto_en = now() where usuario_id = '$USER_A'"))
        assertEquals("Ana", c.unico("select nombre from public.perfiles_usuario where usuario_id = '$USER_A'"))

        // Servidor (service_role / Ktor) si puede; y un cliente no puede des-bloquearse.
        assertNull(comoCliente("service_role", "update public.perfiles_usuario set estado_cuenta = 'bloqueada' where usuario_id = '$USER_A'"))
        assertEquals("42501", comoCliente("authenticated", "update public.perfiles_usuario set estado_cuenta = 'activa' where usuario_id = '$USER_A'"))
        assertEquals("bloqueada", c.unico("select estado_cuenta from public.perfiles_usuario where usuario_id = '$USER_A'"))

        c.ejecutar("delete from public.perfiles_usuario where usuario_id = '$USER_B'")
        assertEquals(
            "42501",
            c.sqlStateDe(
                "begin; set local role authenticated; set local request.jwt.claim.sub = '$USER_B'; " +
                    "insert into public.perfiles_usuario (usuario_id, estado_cuenta) values ('$USER_B', 'bloqueada'); commit;",
            ).also { runCatching { c.ejecutar("rollback") } },
        )
    }

    /** PF3-3-02: un perfil no activo es de solo lectura para clientes (token aun valido via PostgREST). */
    @Test
    fun perfilNoActivoEsSoloLecturaParaClientes(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDENTITY)
        c.ejecutar("insert into public.perfiles_usuario (usuario_id, nombre) values ('$USER_A', 'Ana')")

        fun comoCliente(rol: String, sql: String): String? =
            c.sqlStateDe("begin; set local role $rol; set local request.jwt.claim.sub = '$USER_A'; $sql; commit;")
                .also { runCatching { c.ejecutar("rollback") } }

        val editar = "update public.perfiles_usuario set nombre = 'Mallory', perfil_intro_visto_en = now() where usuario_id = '$USER_A'"

        // activa: sigue siendo editable por su dueno.
        assertNull(comoCliente("authenticated", editar))
        assertEquals("Mallory", c.unico("select nombre from public.perfiles_usuario where usuario_id = '$USER_A'"))

        for (estado in listOf("eliminacion_pendiente", "bloqueada")) {
            // El servidor (rol propietario / service_role) fija el estado; los clientes no pueden.
            c.ejecutar("update public.perfiles_usuario set estado_cuenta = '$estado', nombre = 'Anon' where usuario_id = '$USER_A'")
            assertEquals("42501", comoCliente("authenticated", editar), estado)
            assertEquals("42501", comoCliente("authenticated", "update public.perfiles_usuario set estado_cuenta = 'activa' where usuario_id = '$USER_A'"), estado)
            assertEquals("42501", comoCliente("authenticated", "update public.perfiles_usuario set username = 'x_$estado' where usuario_id = '$USER_A'"), estado)
            assertEquals("Anon", c.unico("select nombre from public.perfiles_usuario where usuario_id = '$USER_A'"), estado)
            assertEquals(estado, c.unico("select estado_cuenta from public.perfiles_usuario where usuario_id = '$USER_A'"))
            // El servidor si puede seguir escribiendo (p. ej. paso de anonimizacion, DEC-008).
            assertNull(comoCliente("service_role", "update public.perfiles_usuario set nombre = 'Anon2' where usuario_id = '$USER_A'"))
            assertEquals("Anon2", c.unico("select nombre from public.perfiles_usuario where usuario_id = '$USER_A'"), estado)
        }

        // El mensaje distingue "no activa" de "estado no escribible".
        val msg = try {
            c.ejecutar("begin; set local role authenticated; set local request.jwt.claim.sub = '$USER_A'; $editar; commit;")
            null
        } catch (e: java.sql.SQLException) {
            e.message
        } finally {
            runCatching { c.ejecutar("rollback") }
        }
        assertTrue(msg?.contains("ESTADO_CUENTA_NOT_ACTIVE") == true, "mensaje: $msg")
    }

    @Test
    fun noExisteNingunaRutinaRuntimeDeBackfillNiGrantsParaRolesDeApi(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDENTITY)
        c.sembrar()
        c.backfill()

        // FIX-D1: el backfill NO es una funcion. Ninguna rutina identity_* ni SECURITY DEFINER en private.
        assertEquals("0", c.unico("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'private' and p.proname like 'identity_%'"))
        assertEquals("0", c.unico("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'private' and p.prosecdef"))
        assertEquals("0", c.unico("select count(*) from pg_proc where prosrc ilike '%identity_backfill%'"))

        for (rol in ROLES_API) {
            // Sin EXECUTE utilizable: la rutina no existe (42883) o el esquema private no es accesible (42501).
            for (f in listOf("identity_backfill_run", "identity_backfill_verify")) {
                val st = c.sqlStateDe("begin; set local role $rol; select * from private.$f(); commit").also { runCatching { c.ejecutar("rollback") } }
                assertTrue(st == "42883" || st == "42501", "$rol $f -> $st")
            }
            // El informe es solo del propietario: ningun rol de API lo lee ni lo escribe.
            for (p in listOf("select", "insert", "update", "delete")) {
                assertEquals("false", c.unico("select has_table_privilege('$rol', 'private.identity_backfill_report', '$p')::text"), "$rol $p")
            }
            assertEquals(
                "42501",
                c.sqlStateDe("begin; set local role $rol; select count(*) from private.identity_backfill_report; commit").also { runCatching { c.ejecutar("rollback") } },
                rol,
            )
            assertEquals(
                "42501",
                c.sqlStateDe("begin; set local role $rol; delete from private.identity_backfill_report where true; commit").also { runCatching { c.ejecutar("rollback") } },
                rol,
            )
        }
        for (rol in listOf("anon", "authenticated")) {
            assertEquals("false", c.unico("select has_schema_privilege('$rol', 'private', 'usage')::text"))
        }
        assertEquals("true", c.unico("select relrowsecurity::text from pg_class where oid = 'private.identity_backfill_report'::regclass"))
        assertEquals("0", c.unico("select count(*) from pg_policies where schemaname = 'private' and tablename = 'identity_backfill_report'"))
        // La funcion trigger del guard sigue siendo SECURITY INVOKER y sin EXECUTE para anon/authenticated.
        assertEquals("false", c.unico("select prosecdef::text from pg_proc where oid = 'private.guard_perfiles_estado_cuenta()'::regprocedure"))
        for (rol in listOf("anon", "authenticated")) {
            assertEquals("false", c.unico("select has_function_privilege('$rol', 'private.guard_perfiles_estado_cuenta()', 'execute')::text"), rol)
        }
    }

    /** SERVICE_ROLE_AUTH_USERS_BROAD_SELECT = DENIED: la migracion no concede ni abre ninguna via de lectura de auth.users. */
    @Test
    fun serviceRoleAuthUsersBroadSelectDenied(): Unit = Slice0Db.escenario { c ->
        val aclAntes = c.unico("select coalesce(relacl::text, '') from pg_class where oid = 'auth.users'::regclass")
        val aclColumnasAntes = c.unico("select coalesce(string_agg(attname || ':' || attacl::text, ',' order by attname), '') from pg_attribute where attrelid = 'auth.users'::regclass and attacl is not null")

        // El harness modela MAIN: ningun rol de API tiene privilegio alguno sobre auth.users.
        fun assertDenegado() {
            for (rol in ROLES_API) {
                for (p in listOf("select", "insert", "update", "delete", "references", "trigger")) {
                    assertEquals("false", c.unico("select has_table_privilege('$rol', 'auth.users', '$p')::text"), "$rol $p")
                }
                assertEquals("false", c.unico("select has_any_column_privilege('$rol', 'auth.users', 'select')::text"), "$rol columnas")
                assertEquals("42501", c.sqlStateDe("begin; set local role $rol; select count(*) from auth.users; commit").also { runCatching { c.ejecutar("rollback") } }, rol)
                assertEquals("42501", c.sqlStateDe("begin; set local role $rol; select id from auth.users limit 1; commit").also { runCatching { c.ejecutar("rollback") } }, rol)
            }
        }
        assertDenegado()

        // Expand con public.usuario vacio, luego datos legacy y backfill real dentro de la migracion (aplicar + delta).
        c.aplicar(Slice0Db.IDENTITY)
        val definerPublicAntes = c.unico("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public' and p.prosecdef and p.prosrc ilike '%auth.users%'")
        c.sembrar()
        c.aplicar(Slice0Db.IDENTITY)
        c.aplicar(Slice0Db.IDENTITY)

        assertDenegado()
        assertEquals(aclAntes, c.unico("select coalesce(relacl::text, '') from pg_class where oid = 'auth.users'::regclass"), "la migracion no cambia la ACL de auth.users")
        assertEquals(aclColumnasAntes, c.unico("select coalesce(string_agg(attname || ':' || attacl::text, ',' order by attname), '') from pg_attribute where attrelid = 'auth.users'::regclass and attacl is not null"))
        // Y ninguna rutina SECURITY DEFINER nueva (en private/public) toca auth.users por otra via.
        assertEquals("0", c.unico("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'private' and p.prosecdef"))
        assertEquals(definerPublicAntes, c.unico("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public' and p.prosecdef and p.prosrc ilike '%auth.users%'"))
    }

    /** El mecanismo canonico (backfill bajo el rol de la migracion) funciona aunque service_role no tenga SELECT en auth.users. */
    @Test
    fun elBackfillCanonicoFuncionaSinPrivilegiosDeServiceRoleYEsIdempotente(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDENTITY)
        c.sembrar()
        assertEquals("false", c.unico("select has_table_privilege('service_role', 'auth.users', 'select')::text"))
        val marca = c.unico("select md5(string_agg(t::text, '|' order by id)) from public.usuario t")

        c.backfill()
        assertEquals("Ana", c.perfil(USER_A)[0])
        assertEquals("1", c.unico("select count(*) from private.identity_backfill_report where outcome = 'orphan_deleted'"))
        assertEquals("1", c.unico("select count(*) from private.identity_backfill_report where outcome = 'orphan_no_auth_user'"))
        assertEquals("1", c.unico("select count(*) from private.identity_backfill_report where outcome = 'skipped_username_collision'"))
        assertEquals("1", c.unico("select count(*) from private.identity_backfill_report where outcome = 'specialty_ambiguous'"))
        c.verifyVerde()
        val datos = c.instantanea()

        // Idempotente: re-ejecutar no cambia datos, no sobrescribe canonico y public.usuario queda intacto.
        repeat(2) { c.backfill() }
        assertEquals(datos, c.instantanea())
        assertEquals("Beatriz", c.perfil(USER_B)[0])
        assertEquals("bea", c.perfil(USER_B)[2])
        assertEquals(marca, c.unico("select md5(string_agg(t::text, '|' order by id)) from public.usuario t"), "public.usuario no cambia")
        c.verifyVerde()

        // service_role sigue sin poder leer auth.users tras el backfill, y no puede invocarlo ni releerlo.
        assertEquals("false", c.unico("select has_table_privilege('service_role', 'auth.users', 'select')::text"))
    }

    @Test
    fun reAplicarLaMigracionYRollbackSonSeguros(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDENTITY)
        c.sembrar()
        c.backfill()
        c.aplicar(Slice0Db.IDENTITY) // re-aplicar no falla ni borra datos
        assertEquals("Ana", c.perfil(USER_A)[0])
        c.verifyVerde()

        val legacyAntes = c.filas("select * from public.usuario order by id")
        c.ejecutar(Slice0Db.rollbackDe(Slice0Db.IDENTITY))

        assertEquals(
            "0",
            c.unico(
                "select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'perfiles_usuario' " +
                    "and column_name in ('nombre','apellidos','estado_cuenta','perfil_intro_visto_en')",
            ),
        )
        assertNull(c.unico("select to_regclass('private.identity_backfill_report')::text"))
        assertEquals("0", c.unico("select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'private' and p.proname like 'identity_%'"))
        assertEquals("0", c.unico("select count(*) from pg_trigger where tgname = 'trg_perfiles_usuario_estado_cuenta_guard'"))
        assertEquals(legacyAntes, c.filas("select * from public.usuario order by id"), "rollback no toca public.usuario")
        // Perfiles creados por el backfill siguen (el rollback no borra filas).
        assertEquals("ana_g", c.unico("select username from public.perfiles_usuario where usuario_id = '$USER_A'"))

        // Rollback idempotente y camino de vuelta: re-aplicar + backfill reconstruye.
        c.ejecutar(Slice0Db.rollbackDe(Slice0Db.IDENTITY))
        c.aplicar(Slice0Db.IDENTITY)
        c.backfill()
        assertEquals("Ana", c.perfil(USER_A)[0])
        c.verifyVerde()
    }
}
