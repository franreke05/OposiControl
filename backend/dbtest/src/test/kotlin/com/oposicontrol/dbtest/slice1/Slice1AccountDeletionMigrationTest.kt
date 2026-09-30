package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.SupabaseFixture.USER_C
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice1.Slice1Db.DOC_TERMS
import com.oposicontrol.dbtest.slice1.Slice1Db.NOEXISTE
import com.oposicontrol.dbtest.slice1.Slice1Db.Res
import com.oposicontrol.dbtest.slice1.Slice1Db.USER_D
import com.oposicontrol.dbtest.slice1.Slice1Db.USER_E
import com.oposicontrol.dbtest.slice1.Slice1Db.estadoComo
import com.oposicontrol.dbtest.slice1.Slice1Db.llamar
import com.oposicontrol.dbtest.slice1.Slice1Db.n
import com.oposicontrol.dbtest.slice1.Slice1Db.onboarding
import com.oposicontrol.dbtest.slice1.Slice1Db.patch
import com.oposicontrol.dbtest.slice1.Slice1Db.sembrar
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Solicitud de eliminacion de cuenta (D1-19): persistencia minima + estado seguro. La ejecucion fisica es
 * DEFERRED_EXECUTION: estas pruebas afirman que NO hay borrado, anonimizacion ni auth.users tocado.
 */
class Slice1AccountDeletionMigrationTest {

    private fun <T> escenario(bloque: (Connection) -> T): T = Slice1Db.escenario { c ->
        c.sembrar()
        bloque(c)
    }

    private fun Connection.solicitar(uid: String) =
        llamar("select estado_cuenta || '|' || solicitada_en::text || '|' || ya_pendiente::text from public.app_solicitar_eliminacion_cuenta('$uid')")

    private fun Connection.estado(uid: String): String? =
        unico("select estado_cuenta from public.perfiles_usuario where usuario_id = '$uid'")

    private fun Connection.solicitud(uid: String): List<String?>? =
        filas("select estado, revocacion_sesiones, paso_ejecucion::text, reintentos::text, ultimo_error_codigo, solicitada_en::text, actualizado_en::text from public.solicitudes_eliminacion_cuenta where usuario_id = '$uid'").singleOrNull()

    private fun Res.assertError(sqlState: String, mensaje: String, contexto: String = "") =
        assertEquals(sqlState to mensaje, this.sqlState to this.mensaje, "$contexto -> $this")

    @Test
    fun solicitarEnUnaTransaccionCambiaEstadoCreaSolicitudYAuditoria(): Unit = escenario { c ->
        assertTrue(c.onboarding(USER_A).ok)
        val r = c.solicitar(USER_A)
        assertTrue(r.ok, r.toString())
        val (estado, ts, ya) = r.valor!!.split("|")
        assertEquals("eliminacion_pendiente", estado)
        assertEquals("false", ya)
        assertEquals("eliminacion_pendiente", c.estado(USER_A))
        val s = c.solicitud(USER_A)!!
        assertEquals(listOf("solicitada", "pendiente", "1", "0", null), s.take(5))
        assertEquals(ts, s[5], "solicitada_en devuelto = el persistido (now() del servidor)")
        assertEquals(
            listOf("cuenta_eliminacion_solicitada", "perfiles_usuario", USER_A, """{"estado_cuenta": "activa"}""", """{"estado_cuenta": "eliminacion_pendiente"}"""),
            c.filas("select accion, tabla_afectada, registro_id::text, antes::text, despues::text from public.auditoria_log where usuario_id = '$USER_A'").single(),
        )
        // DEFERRED_EXECUTION: nada se borra ni se anonimiza; auth.users intacto.
        assertEquals("ana_g", c.unico("select username from public.perfiles_usuario where usuario_id = '$USER_A'"))
        assertEquals("Ana", c.unico("select nombre from public.perfiles_usuario where usuario_id = '$USER_A'"))
        assertEquals("1", c.unico("select count(*) from auth.users where id = '$USER_A'"))
        assertEquals("a@test.local", c.unico("select email from auth.users where id = '$USER_A'"))
        assertEquals("2", c.unico("select count(*) from public.aceptaciones_legales where usuario_id = '$USER_A'"))
    }

    @Test
    fun repetirLaSolicitudEsIdempotenteYDevuelveLaOriginal(): Unit = escenario { c ->
        val primera = c.solicitar(USER_A).valor!!.split("|")
        val fila = c.solicitud(USER_A)
        val audit = c.n("select count(*) from public.auditoria_log")
        repeat(3) {
            val otra = c.solicitar(USER_A)
            assertTrue(otra.ok, otra.toString())
            val partes = otra.valor!!.split("|")
            assertEquals("eliminacion_pendiente", partes[0])
            assertEquals(primera[1], partes[1], "devuelve solicitada_en ORIGINAL")
            assertEquals("true", partes[2])
        }
        assertEquals(fila, c.solicitud(USER_A), "fila intacta (ni siquiera actualizado_en)")
        assertEquals(1L, c.n("select count(*) from public.solicitudes_eliminacion_cuenta where usuario_id = '$USER_A'"))
        assertEquals(audit, c.n("select count(*) from public.auditoria_log"))
        // Auto-curacion: pendiente sin fila (p. ej. fijado por operador) crea la fila y marca ya_pendiente.
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'eliminacion_pendiente' where usuario_id = '$USER_D'")
        val heal = c.solicitar(USER_D)
        assertEquals("eliminacion_pendiente", heal.valor!!.split("|")[0])
        assertEquals("true", heal.valor!!.split("|")[2])
        assertEquals(1L, c.n("select count(*) from public.solicitudes_eliminacion_cuenta where usuario_id = '$USER_D'"))
    }

    @Test
    fun estadosNoElegiblesSeDenieganSinEscribirNada(): Unit = escenario { c ->
        c.ejecutar("alter table public.perfiles_usuario drop constraint perfiles_usuario_estado_cuenta_chk")
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'zzz' where usuario_id = '$USER_D'")
        val filasAntes = c.n("select count(*) from public.solicitudes_eliminacion_cuenta") + c.n("select count(*) from public.auditoria_log")
        c.solicitar(USER_B).assertError("42501", "ACCOUNT_BLOCKED")
        c.solicitar(USER_D).assertError("42501", "FORBIDDEN")
        c.solicitar(USER_E).assertError("OC404", "PROFILE_MISSING")
        c.solicitar(NOEXISTE).assertError("OC404", "PROFILE_MISSING")
        c.llamar("select * from public.app_solicitar_eliminacion_cuenta(null)").assertError("22023", "INVALID_ARGUMENT")
        assertEquals("bloqueada", c.estado(USER_B))
        assertEquals("zzz", c.estado(USER_D))
        assertEquals(filasAntes, c.n("select count(*) from public.solicitudes_eliminacion_cuenta") + c.n("select count(*) from public.auditoria_log"))
        assertNull(c.solicitud(USER_B))
    }

    @Test
    fun laSolicitudEsAtomicaSiFallaLaAuditoria(): Unit = escenario { c ->
        c.ejecutar("alter table public.auditoria_log add constraint zz_fallo check (accion <> 'cuenta_eliminacion_solicitada')")
        assertEquals("23514", c.solicitar(USER_A).sqlState)
        assertEquals("activa", c.estado(USER_A), "el cambio de estado se revierte")
        assertNull(c.solicitud(USER_A), "la solicitud se revierte")
        c.ejecutar("alter table public.auditoria_log drop constraint zz_fallo")
        assertTrue(c.solicitar(USER_A).ok)
    }

    @Test
    fun solicitudesConcurrentesProducenUnaSolaFilaYUnaAuditoria(): Unit = escenario { c ->
        val cx = List(4) { PostgresHarness.conexionExtra(c) }
        val barrera = CyclicBarrier(cx.size)
        val pool = Executors.newFixedThreadPool(cx.size)
        try {
            val res = cx.map { con -> pool.submit<Slice1Db.Res> { barrera.await(); con.solicitar(USER_A) } }
                .map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(res.all { it.ok }, res.toString())
            assertEquals(1, res.count { it.valor!!.endsWith("|false") }, res.toString())
            assertEquals(3, res.count { it.valor!!.endsWith("|true") }, res.toString())
            assertEquals(1, res.map { it.valor!!.split("|")[1] }.toSet().size, "todas ven la misma solicitada_en")
        } finally {
            pool.shutdownNow()
            cx.forEach { it.close() }
        }
        assertEquals(1L, c.n("select count(*) from public.solicitudes_eliminacion_cuenta where usuario_id = '$USER_A'"))
        assertEquals(1L, c.n("select count(*) from public.auditoria_log where accion = 'cuenta_eliminacion_solicitada'"))
    }

    @Test
    fun unaVezPendienteLosComandosDePerfilYLosClientesQuedanBloqueados(): Unit = escenario { c ->
        assertTrue(c.onboarding(USER_A).ok)
        assertTrue(c.solicitar(USER_A).ok)
        c.patch(USER_A, """{"nombre":"Z"}""").assertError("42501", "ACCOUNT_DELETION_PENDING")
        c.onboarding(USER_A).assertError("42501", "ACCOUNT_DELETION_PENDING")
        c.llamar("select public.app_aceptar_documentos_legales('$USER_A', array['$DOC_TERMS']::uuid[], 'ios')::text")
            .assertError("42501", "ACCOUNT_DELETION_PENDING")
        // Guard de Slice 0: perfil no activo es de solo lectura para clientes (token aun valido via PostgREST).
        assertEquals("42501", c.estadoComo("authenticated", "update public.perfiles_usuario set nombre = 'Z' where usuario_id = '$USER_A'", USER_A))
        assertEquals("42501", c.estadoComo("authenticated", "update public.perfiles_usuario set estado_cuenta = 'activa' where usuario_id = '$USER_A'", USER_A))
        assertEquals("eliminacion_pendiente", c.estado(USER_A))
    }

    @Test
    fun tablaDenyAllFkNoCascadeYUnaSolaSolicitudAbiertaPorUsuario(): Unit = escenario { c ->
        assertTrue(c.solicitar(USER_A).ok)
        val t = "public.solicitudes_eliminacion_cuenta"
        assertEquals("true", c.unico("select relrowsecurity::text from pg_class where oid = '$t'::regclass"))
        assertEquals("0", c.unico("select count(*) from pg_policies where tablename = 'solicitudes_eliminacion_cuenta'"))
        for (rol in listOf("anon", "authenticated")) {
            for (p in listOf("select", "insert", "update", "delete", "truncate", "references", "trigger")) {
                assertEquals("false", c.unico("select has_table_privilege('$rol', '$t', '$p')::text"), "$rol $p")
            }
            assertEquals("42501", c.estadoComo(rol, "select * from $t", USER_A), rol)
            assertEquals("42501", c.estadoComo(rol, "insert into $t (usuario_id) values ('$USER_D')", USER_D), rol)
            assertEquals("42501", c.estadoComo(rol, "update $t set estado = 'completada'", USER_A), rol)
            assertEquals("42501", c.estadoComo(rol, "delete from $t", USER_A), rol)
        }
        // service_role: solo select/insert/update.
        for ((p, esperado) in listOf("select" to "true", "insert" to "true", "update" to "true", "delete" to "false", "truncate" to "false")) {
            assertEquals(esperado, c.unico("select has_table_privilege('service_role', '$t', '$p')::text"), p)
        }
        assertEquals("42501", c.estadoComo("service_role", "delete from $t"))
        // Aunque un GRANT accidental abriera SELECT, RLS sin politicas devuelve 0 filas.
        c.ejecutar("grant select on $t to authenticated")
        c.ejecutar("select set_config('request.jwt.claim.sub', '$USER_A', false); set role authenticated")
        try {
            assertEquals(0L, c.n("select count(*) from $t"))
        } finally {
            c.ejecutar("reset role")
        }
        c.ejecutar("revoke select on $t from authenticated")
        // Una sola solicitud por usuario; estados validos.
        assertEquals("23505", c.estadoComo("service_role", "insert into $t (usuario_id) values ('$USER_A')"))
        assertEquals("23514", c.estadoComo("service_role", "update $t set estado = 'borrada' where usuario_id = '$USER_A'"))
        assertEquals("23514", c.estadoComo("service_role", "update $t set revocacion_sesiones = 'quiza' where usuario_id = '$USER_A'"))
        // FK ON DELETE NO ACTION: no se puede borrar auth.users mientras exista la solicitud (y nada cascadea).
        assertEquals("NO ACTION", c.unico("select case confdeltype when 'a' then 'NO ACTION' when 'c' then 'CASCADE' else confdeltype::text end from pg_constraint where conrelid = '$t'::regclass and contype = 'f'"))
        assertEquals("23503", c.estadoComo("postgres", "delete from auth.users where id = '$USER_A'"))
        assertEquals("1", c.unico("select count(*) from $t where usuario_id = '$USER_A'"))
        assertEquals("1", c.unico("select count(*) from public.perfiles_usuario where usuario_id = '$USER_A'"))
    }

    @Test
    fun registrarRevocacionDeSesionesEsIdempotenteYNoCambiaElEstado(): Unit = escenario { c ->
        c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'hecha')::text").assertError("OC404", "NO_DELETION_REQUEST")
        assertTrue(c.solicitar(USER_A).ok)
        val r = c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'hecha')::text")
        assertTrue(r.ok, r.toString())
        assertEquals("hecha", r.campo("revocacionSesiones"))
        val s = c.solicitud(USER_A)!!
        assertEquals("hecha", s[1])
        assertEquals("solicitada", s[0], "estado de ejecucion no cambia: DEFERRED_EXECUTION")
        // Idempotente: repetir no cambia actualizado_en.
        assertTrue(c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'hecha')::text").ok)
        assertEquals(s, c.solicitud(USER_A))
        // MASTER2-AUTH-009 (cambio DELIBERADO; antes esta prueba fijaba el defecto `hecha` -> `fallida`): con
        // 20260930101500 'hecha' es terminal; un 'fallida' tardio no la degrada, no toca actualizado_en y no da OC404.
        val tardio = c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'fallida')::text")
        assertTrue(tardio.ok, tardio.toString())
        assertEquals("hecha", tardio.campo("revocacionSesiones"))
        assertEquals(s, c.solicitud(USER_A))
        c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'quiza')::text").assertError("22023", "INVALID_ARGUMENT")
        c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', null)::text").assertError("22023", "INVALID_ARGUMENT")
        c.llamar("select public.app_registrar_revocacion_sesiones(null, 'hecha')::text").assertError("22023", "INVALID_ARGUMENT")
        assertEquals("eliminacion_pendiente", c.estado(USER_A))
        // Una repeticion de la solicitud no reinicia el resultado de la revocacion.
        assertEquals("true", c.solicitar(USER_A).valor!!.split("|")[2])
        assertEquals("hecha", c.solicitud(USER_A)!![1])
    }

    @Test
    fun cuentaRestauradaPorOperadorPuedeVolverASolicitar(): Unit = escenario { c ->
        assertTrue(c.solicitar(USER_A).ok)
        c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'hecha')::text")
        c.ejecutar("update public.perfiles_usuario set estado_cuenta = 'activa' where usuario_id = '$USER_A'") // operador
        val r = c.solicitar(USER_A)
        assertEquals("false", r.valor!!.split("|")[2])
        assertEquals(listOf("solicitada", "pendiente", "1", "0", null), c.solicitud(USER_A)!!.take(5), "solicitud reiniciada")
        assertEquals(2L, c.n("select count(*) from public.auditoria_log where accion = 'cuenta_eliminacion_solicitada'"))
    }

    @Test
    fun verifyReaplicarYRollbackDeLaSolicitud(): Unit = escenario { c ->
        fun verde() {
            val f = c.filas(Slice0Db.verifyDe(Slice1Db.DELETION)).associate { it[0]!! to it[1]!!.toLong() }
            assertEquals(7, f.size, f.toString())
            assertTrue(f.values.all { it == 0L }, "VERIFY: $f")
        }
        // USER_C se sembro como eliminacion_pendiente sin fila (auto-curacion cubierta en otro test).
        c.ejecutar("insert into public.solicitudes_eliminacion_cuenta (usuario_id) values ('$USER_C')")
        verde()
        assertTrue(c.solicitar(USER_A).ok)
        val fila = c.solicitud(USER_A)
        c.aplicar(Slice1Db.DELETION) // reaplicar no borra ni cambia la solicitud
        assertEquals(fila, c.solicitud(USER_A))
        verde()

        c.ejecutar(Slice0Db.rollbackDe(Slice1Db.DELETION))
        assertNull(c.unico("select to_regclass('public.solicitudes_eliminacion_cuenta')::text"))
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('app_solicitar_eliminacion_cuenta','app_registrar_revocacion_sesiones')"))
        // El estado de la cuenta NO se revierte solo (documentado): sigue pendiente, sin fila de solicitud.
        assertEquals("eliminacion_pendiente", c.estado(USER_A))
        c.ejecutar(Slice0Db.rollbackDe(Slice1Db.DELETION)) // idempotente
        c.aplicar(Slice1Db.DELETION)
        // Mitigacion: la RPC repara la fila que falta y devuelve ya_pendiente = true.
        val heal = c.solicitar(USER_A)
        assertEquals("true", heal.valor!!.split("|")[2])
        assertEquals(1L, c.n("select count(*) from public.solicitudes_eliminacion_cuenta"))
        assertTrue(c.solicitar(USER_C).ok) // C tambien estaba pendiente sin fila tras el rollback
        verde()
    }
}

