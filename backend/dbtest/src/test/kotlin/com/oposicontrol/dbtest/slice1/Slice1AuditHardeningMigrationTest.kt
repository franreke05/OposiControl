package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_COOKIES
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_OLD
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_PRIV
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_TERMS
import com.oposicontrol.dbtest.slice1.Slice1Db.Res
import com.oposicontrol.dbtest.slice1.Slice1Db.USER_D
import com.oposicontrol.dbtest.slice1.Slice1Db.catalogo
import com.oposicontrol.dbtest.slice1.Slice1Db.estadoComo
import com.oposicontrol.dbtest.slice1.Slice1Db.n
import com.oposicontrol.dbtest.slice1.Slice1Db.onboarding
import com.oposicontrol.dbtest.slice1.Slice1Db.patch
import com.oposicontrol.dbtest.slice1.Slice1Db.sembrar
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.postgresql.util.PSQLException

/**
 * Audit loop 1, area DB: migracion 20260930100500 (forward fix; las cuatro de Slice 1 no se editan).
 * MASTER-DB-001 username de metadata, -003 puerta del indice lower(username) + lock consultivo,
 * -004 privilegios de documentos_legales en base nueva, -006 trim Unicode, -008 replay de onboarding.
 */
class Slice1AuditHardeningMigrationTest {

    private fun <T> escenario(bloque: (Connection) -> T): T = Slice1Db.escenario { c ->
        c.sembrar()
        bloque(c)
    }

    private fun Connection.verifyHardening(): Map<String, Long> =
        filas(Slice0Db.verifyDe(Slice1Db.HARDENING)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.verifyVerde() {
        val f = verifyHardening()
        assertEquals(11, f.size, f.toString())
        assertTrue(f.values.all { it == 0L }, "VERIFY de hardening no esta verde: $f")
    }

    private fun Res.assertError(sqlState: String, mensaje: String, contexto: String = "") {
        assertEquals(sqlState to mensaje, this.sqlState to this.mensaje, "$contexto -> $this")
    }

    private fun Connection.username(uid: String): String? =
        unico("select username from public.perfiles_usuario where usuario_id = '$uid'")

    // ---------------------------------------------------------------- MASTER-DB-001

    @Test
    fun handleNewAuthUserNuncaCopiaElUsernameDeLaMetadataDelCliente(): Unit = escenario { c ->
        // Alta con metadata username: el perfil nace sin username (lo fija solo el onboarding del servidor).
        c.ejecutar("insert into auth.users (id, email, raw_user_meta_data) values ('12345678-1234-1234-1234-123456789012', 'n@test.local', '{\"username\":\"meta_user\",\"nombre\":\"Meta\"}')")
        assertEquals(
            listOf(null, "Meta", "activa"),
            c.filas("select username, nombre_mostrar, estado_cuenta from public.perfiles_usuario where usuario_id = '12345678-1234-1234-1234-123456789012'").single(),
        )

        // Cuenta activa con username NULL: un cambio de user_metadata (GoTrue) no puede fijarlo.
        assertNull(c.username(USER_A))
        c.ejecutar("update auth.users set raw_user_meta_data = '{\"username\":\"bypass\"}' where id = '$USER_A'")
        assertNull(c.username(USER_A), "el trigger SECURITY DEFINER no debe saltarse el guard server-owned")
        // ...y el camino directo del cliente sigue denegado por el guard de 20260930100000.
        assertEquals("42501", c.estadoComo("authenticated", "update public.perfiles_usuario set username = 'bypass' where usuario_id = '$USER_A'", USER_A))

        // Un username de metadata que colisiona (sin distinguir mayusculas) ya no aborta el alta ni el update.
        assertTrue(c.onboarding(USER_D, username = "Ocupado", docs = "array[]::uuid[]", plataforma = null).ok)
        assertNull(c.sqlStateDe("update auth.users set raw_user_meta_data = '{\"username\":\"ocupado\"}' where id = '$USER_A'"))
        assertNull(c.sqlStateDe("insert into auth.users (id, email, raw_user_meta_data) values ('22345678-1234-1234-1234-123456789012', 'm@test.local', '{\"username\":\"OCUPADO\"}')"))
        assertNull(c.username("22345678-1234-1234-1234-123456789012"))
        assertEquals("Ocupado", c.username(USER_D))
        c.verifyVerde()
    }

    // ---------------------------------------------------------------- MASTER-DB-003

    @Test
    fun laPuertaDelIndiceLowerUsernameAbortaSiHayColisionesYLoCreaAlResolverlas(): Unit = Slice1Db.escenario(migraciones = false) { c ->
        c.sembrar()
        c.ejecutar("update public.perfiles_usuario set username = 'Bob' where usuario_id = '$USER_A'")
        c.ejecutar("update public.perfiles_usuario set username = 'bob' where usuario_id = '$USER_B'")
        listOf(Slice1Db.GUARDS, Slice1Db.LEGAL, Slice1Db.RPCS, Slice1Db.DELETION).forEach { c.aplicar(it) } // GUARDS solo avisa
        assertEquals("0", c.unico("select count(*) from pg_indexes where indexname = 'perfiles_usuario_username_lower_uq'"))

        val error = try {
            c.aplicar(Slice1Db.HARDENING)
            fail("la migracion debe abortar mientras falte el indice por colisiones")
        } catch (e: SQLException) {
            (e as? PSQLException)?.serverErrorMessage?.message
        }
        c.ejecutar("rollback") // el script abrio su propia transaccion
        assertEquals("SLICE1_USERNAME_LOWER_INDEX_GATE", error)
        // Nada aplicado: una sola transaccion.
        assertEquals("0", c.unico("select count(*) from pg_proc where proname = 'slice1_trim'"))

        c.ejecutar("update public.perfiles_usuario set username = 'bob2' where usuario_id = '$USER_B'")
        c.aplicar(Slice1Db.HARDENING)
        assertEquals("1", c.unico("select count(*) from pg_indexes where indexname = 'perfiles_usuario_username_lower_uq'"))
        c.verifyVerde()
    }

    @Test
    fun elLockConsultivoSerializaReclamacionesConcurrentesAunSinIndice(): Unit = escenario { c ->
        // Simula una base donde el indice no pudo crearse: solo el lock evita 'Foo' + 'foo'.
        c.ejecutar("drop index public.perfiles_usuario_username_lower_uq")
        for (camino in listOf("onboarding", "patch")) {
            val nombre = "carrera_$camino"
            val c1 = PostgresHarness.conexionExtra(c)
            val c2 = PostgresHarness.conexionExtra(c)
            val pool = Executors.newSingleThreadExecutor()
            try {
                c1.autoCommit = false
                val primero = if (camino == "onboarding") {
                    c1.onboarding(USER_A, username = nombre.uppercase(), docs = "array[]::uuid[]", plataforma = null)
                } else {
                    c1.patch(USER_A, """{"username":"${nombre.uppercase()}"}""")
                }
                assertTrue(primero.ok, primero.toString()) // transaccion abierta: lock tomado, sin commit
                val segundo = pool.submit<Res> {
                    if (camino == "onboarding") {
                        c2.onboarding(USER_D, username = nombre, docs = "array[]::uuid[]", plataforma = null)
                    } else {
                        c2.patch(USER_D, """{"username":"$nombre"}""")
                    }
                }
                Thread.sleep(1500)
                assertFalse(segundo.isDone, "$camino: la segunda reclamacion debe esperar al lock consultivo")
                c1.commit()
                segundo.get(30, TimeUnit.SECONDS).assertError("23505", "USERNAME_TAKEN", camino)
            } finally {
                pool.shutdownNow()
                runCatching { c1.rollback() }
                c1.close()
                c2.close()
            }
            assertEquals(1L, c.n("select count(*) from public.perfiles_usuario where lower(username) = '$nombre'"), camino)
        }
        // El camino patch escribio de verdad el username del ganador.
        assertEquals("CARRERA_PATCH", c.username(USER_A))
    }

    // ---------------------------------------------------------------- MASTER-DB-004

    @Test
    fun documentosLegalesEnBaseNuevaSinPreRevokeQuedaSoloConSelectParaClientes(): Unit = Slice0Db.escenario { c ->
        // Sin MAIN_SHAPES: la tabla la crea 20260930100100 con los privilegios por defecto de Supabase.
        c.aplicar(Slice0Db.IDENTITY)
        c.aplicar(Slice0Db.IDEMPOTENCY)
        listOf(Slice1Db.GUARDS, Slice1Db.LEGAL, Slice1Db.RPCS).forEach { c.aplicar(it) }
        fun privilegios(rol: String) = c.unico(
            "select string_agg(p, ',' order by p) from unnest(array['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER']) p " +
                "where has_table_privilege('$rol', 'public.documentos_legales', p)",
        )
        // El defecto existe antes del fix (prueba que el test mide algo real).
        assertEquals("DELETE,INSERT,REFERENCES,SELECT,TRIGGER,TRUNCATE,UPDATE", privilegios("anon"))

        c.aplicar(Slice1Db.HARDENING)
        assertEquals("SELECT", privilegios("anon"))
        assertEquals("SELECT", privilegios("authenticated"))
        assertEquals("DELETE,INSERT,REFERENCES,SELECT,TRIGGER,TRUNCATE,UPDATE", privilegios("service_role"))
        val v = c.verifyHardening()
        assertEquals(0L, v["documentos_legales_client_write_privileges"], v.toString())
        assertEquals(0L, v["documentos_legales_client_select_missing"], v.toString())
        assertEquals("42501", c.estadoComo("anon", "truncate public.documentos_legales"))
        assertEquals("42501", c.estadoComo("authenticated", "insert into public.documentos_legales (tipo_documento, version, titulo, contenido) values ('terminos', 'x', 't', 'c')", USER_A))
    }

    @Test
    fun documentosLegalesSobreLaFormaDeMainNoCambiaNada(): Unit = Slice1Db.escenario(migraciones = false) { c ->
        listOf(Slice1Db.GUARDS, Slice1Db.LEGAL, Slice1Db.RPCS, Slice1Db.DELETION).forEach { c.aplicar(it) }
        val acl = "select coalesce(relacl::text, '') from pg_class where oid = 'public.documentos_legales'::regclass"
        val antes = c.unico(acl)
        c.aplicar(Slice1Db.HARDENING)
        assertEquals(antes, c.unico(acl))
    }

    // ---------------------------------------------------------------- MASTER-DB-006

    @Test
    fun nombresSoloConEspaciosUnicodeSeRechazanYLosBordesSeRecortan(): Unit = escenario { c ->
        val blancos = listOf("\t", "\n", "\u00a0", "\u3000", "\t\u00a0\n", "\u2007\u202f", "\ufeff")
        for (b in blancos) {
            val cp = b.map { "U+%04X".format(it.code) }.joinToString()
            c.onboarding(USER_A, nombre = b).assertError("22023", "INVALID_ARGUMENT", "nombre $cp")
            assertEquals("nombre", c.onboarding(USER_A, nombre = b).hint)
            c.onboarding(USER_A, apellidos = b).assertError("22023", "INVALID_ARGUMENT", "apellidos $cp")
        }
        assertEquals("false", c.unico("select onboarding_completado::text from public.perfiles_usuario where usuario_id = '$USER_A'"))

        val ok = c.onboarding(USER_A, nombre = "\u00a0\tAna\n", apellidos = "\u3000Garcia\u00a0", referral = "\t\u00a0")
        assertTrue(ok.ok, ok.toString())
        assertEquals(listOf("Ana", "Garcia"), c.filas("select nombre, apellidos from public.perfiles_usuario where usuario_id = '$USER_A'").single())
        assertEquals(0L, c.n("select count(*) from public.atribucion_usuario where usuario_id = '$USER_A'"), "referral en blanco = sin atribucion")

        // Patch (private.slice1_text): nombre en blanco Unicode -> INVALID_ARGUMENT; detail en blanco -> NULL.
        c.patch(USER_A, """{"nombre":"\u00a0\t"}""").assertError("22023", "INVALID_ARGUMENT")
        c.patch(USER_A, """{"apellidos":"\n\u3000"}""").assertError("22023", "INVALID_ARGUMENT")
        assertTrue(c.patch(USER_A, """{"nombre":" Bea\u00a0","detail":{"ciudad":"\u00a0\t","franja":"\u2003manana\t"}}""").ok)
        assertEquals("Bea", c.unico("select nombre from public.perfiles_usuario where usuario_id = '$USER_A'"))
        assertEquals(
            listOf(null, "manana"),
            c.filas("select ciudad, franja_preferente_estudio from public.perfiles_usuario_detalle where usuario_id = '$USER_A'").single(),
        )
        // El helper devuelve NULL para NULL y conserva el interior.
        assertEquals("a \u00a0b", c.unico("select private.slice1_trim(E'\\t a \u00a0b\\u00a0')"))
        assertNull(c.unico("select private.slice1_trim(null)"))
    }

    // ---------------------------------------------------------------- MASTER-DB-008

    @Test
    fun replayIdenticoRegistraDocumentosYAtribucionSinTocarElPerfil(): Unit = escenario { c ->
        val primera = c.onboarding(USER_A, docs = "array['$DOC_TERMS']::uuid[]")
        assertTrue(primera.ok, primera.toString())
        val etag = primera.campo("actualizadoEn")
        assertEquals(0L, c.n("select count(*) from public.atribucion_usuario where usuario_id = '$USER_A'"))

        // Replay identico con un documento nuevo + atribucion: 200 replay, perfil intacto, TODO registrado.
        val replay = c.onboarding(
            USER_A, docs = "array['$DOC_TERMS','$DOC_PRIV','$DOC_COOKIES']::uuid[]", plataforma = "ios",
            attribution = """{"fuente":"replay"}""", referral = "REF-1",
        )
        assertTrue(replay.ok, replay.toString())
        assertEquals("true", replay.campo("replay"))
        assertEquals(etag, replay.campo("actualizadoEn"), "el replay no escribe el perfil")
        assertEquals(
            setOf("$DOC_TERMS|android", "$DOC_PRIV|ios", "$DOC_COOKIES|ios"),
            c.filas("select documento_id::text || '|' || plataforma from public.aceptaciones_legales where usuario_id = '$USER_A'").map { it[0]!! }.toSet(),
        )
        assertEquals(listOf("replay", "REF-1"), c.filas("select fuente, codigo_referido from public.atribucion_usuario where usuario_id = '$USER_A'").single())

        // Write-once: otro replay no reescribe la atribucion ni duplica aceptaciones.
        assertTrue(c.onboarding(USER_A, docs = "array['$DOC_PRIV']::uuid[]", attribution = """{"fuente":"otra"}""").ok)
        assertEquals("replay", c.unico("select fuente from public.atribucion_usuario where usuario_id = '$USER_A'"))
        assertEquals(3L, c.n("select count(*) from public.aceptaciones_legales where usuario_id = '$USER_A'"))

        // Replay con documento no activo o sin plataforma: error estable, nada escrito (sin exito falso).
        val antes = c.n("select count(*) from public.aceptaciones_legales")
        c.onboarding(USER_A, docs = "array['$DOC_OLD']::uuid[]").assertError("22023", "DOCUMENT_NOT_ACTIVE")
        c.onboarding(USER_A, docs = "array['$DOC_PRIV']::uuid[]", plataforma = null).assertError("22023", "INVALID_ARGUMENT")
        c.onboarding(USER_A, attribution = """{"plataforma":"symbian"}""").assertError("22023", "INVALID_ARGUMENT")
        assertEquals(antes, c.n("select count(*) from public.aceptaciones_legales"))

        // Identidad distinta: conflicto antes de validar o escribir nada.
        c.onboarding(USER_A, nombre = "Otra", docs = "array['$DOC_OLD']::uuid[]").assertError("OC409", "ALREADY_ONBOARDED_CONFLICT")
        assertEquals(etag, Res(c.unico("select private.slice1_profile_json('$USER_A')::text")).campo("actualizadoEn"))
    }

    // ---------------------------------------------------------------- VERIFY / reaplicar / rollback

    @Test
    fun verifyReaplicarYRollbackRealDelHardening(): Unit = escenario { c ->
        c.verifyVerde()
        val completo = c.catalogo()
        c.aplicar(Slice1Db.HARDENING)
        c.verifyVerde()
        assertEquals(completo, c.catalogo(), "reaplicar no cambia el catalogo")

        c.ejecutar(Slice0Db.rollbackDe(Slice1Db.HARDENING))
        val v = c.verifyHardening()
        assertEquals(1L, v["trim_helper_missing"], v.toString())
        assertEquals(1L, v["handle_new_auth_user_reads_metadata_username"], v.toString())
        assertEquals(2L, v["rpc_username_lock_missing"], v.toString())
        assertEquals(1L, v["onboarding_replay_skips_documents"], v.toString())
        // El rollback es real: vuelve el comportamiento previo (copia del username de metadata).
        c.ejecutar("update auth.users set raw_user_meta_data = '{\"username\":\"volvio\"}' where id = '$USER_A'")
        assertEquals("volvio", c.username(USER_A))
        // Y las RPCs previas siguen funcionando (no dependen del helper borrado).
        assertTrue(c.onboarding(USER_D, username = "dora", docs = "array[]::uuid[]", plataforma = null).ok)

        c.aplicar(Slice1Db.HARDENING)
        c.verifyVerde()
    }
}
