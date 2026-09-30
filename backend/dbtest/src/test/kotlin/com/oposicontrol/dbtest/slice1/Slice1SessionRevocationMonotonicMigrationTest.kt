package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice1.Slice1Db.llamar
import com.oposicontrol.dbtest.slice1.Slice1Db.sembrar
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit loop 2 (MASTER2-AUTH-009): migracion 20260930101500. `app_registrar_revocacion_sesiones` ya no es un
 * sobrescritor ciego: 'hecha' es terminal (un 'fallida' tardio o reintentado no la degrada) y los registradores
 * concurrentes de la misma solicitud se serializan con un cerrojo de fila. Concurrencia real: dos conexiones.
 */
class Slice1SessionRevocationMonotonicMigrationTest {

    private fun Connection.revocacion(uid: String): String? =
        unico("select revocacion_sesiones from public.solicitudes_eliminacion_cuenta where usuario_id = '$uid'")

    private fun Connection.solicitar(uid: String) =
        llamar("select estado_cuenta from public.app_solicitar_eliminacion_cuenta('$uid')")

    @Test
    fun fallidaNuncaDegradaHechaYFallidaSiPuedeSubirAHecha(): Unit = Slice1Db.escenario { c ->
        c.sembrar()
        assertTrue(c.solicitar(USER_A).ok)
        assertEquals("pendiente", c.revocacion(USER_A))

        assertEquals("fallida", c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'fallida')::text").campo("revocacionSesiones"))
        assertEquals("fallida", c.revocacion(USER_A))
        // Un reintento que si revoca converge a 'hecha'.
        assertEquals("hecha", c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'hecha')::text").campo("revocacionSesiones"))
        assertEquals("hecha", c.revocacion(USER_A))
        // Y ya no baja nunca.
        val tardio = c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'fallida')::text")
        assertTrue(tardio.ok, tardio.toString())
        assertEquals("hecha", tardio.campo("revocacionSesiones"), "devuelve el valor ALMACENADO")
        assertEquals("hecha", c.revocacion(USER_A))
    }

    @Test
    fun unFallidaConcurrenteQueEsperaAlHechaNoLoPisa(): Unit = Slice1Db.escenario { c ->
        c.sembrar()
        assertTrue(c.solicitar(USER_A).ok)
        val otra = PostgresHarness.conexionExtra(c)
        val pool = Executors.newSingleThreadExecutor()
        try {
            c.autoCommit = false
            val primero = c.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'hecha')::text")
            assertTrue(primero.ok, primero.toString())
            // La segunda llamada ('fallida') queda bloqueada en el cerrojo de fila de la primera.
            val segunda = pool.submit<Slice1Db.Res> {
                otra.llamar("select public.app_registrar_revocacion_sesiones('$USER_A', 'fallida')::text")
            }
            Thread.sleep(300)
            assertTrue(!segunda.isDone, "la segunda debe esperar al cerrojo de la primera")
            c.commit()
            val r = segunda.get(20, TimeUnit.SECONDS)
            assertTrue(r.ok, r.toString())
            assertEquals("hecha", r.campo("revocacionSesiones"))
            c.autoCommit = true
            assertEquals("hecha", c.revocacion(USER_A))
        } finally {
            pool.shutdownNow()
            otra.close()
        }
    }

    @Test
    fun verifyEnVerdeYRollbackRestauraLaDefinicionAnterior(): Unit = Slice1Db.escenario { c ->
        c.ejecutar("reset role")
        val f = c.filas(Slice0Db.verifyDe(Slice1Db.REVOCATION_MONOTONIC)).associate { it[0]!! to it[1]!!.toLong() }
        assertEquals(2, f.size, f.toString())
        assertTrue(f.values.all { it == 0L }, "VERIFY: $f")

        c.ejecutar(Slice0Db.rollbackDe(Slice1Db.REVOCATION_MONOTONIC))
        val g = c.filas(Slice0Db.verifyDe(Slice1Db.REVOCATION_MONOTONIC)).associate { it[0]!! to it[1]!!.toLong() }
        assertEquals(1L, g["revocation_not_monotonic"], "tras el rollback vuelve la definicion anterior: $g")
        assertEquals(0L, g["revocation_rpc_executable_by_clients"])

        c.aplicar(Slice1Db.REVOCATION_MONOTONIC)
        c.aplicar(Slice1Db.REVOCATION_MONOTONIC) // idempotente
        val h = c.filas(Slice0Db.verifyDe(Slice1Db.REVOCATION_MONOTONIC)).associate { it[0]!! to it[1]!!.toLong() }
        assertTrue(h.values.all { it == 0L }, "VERIFY tras reaplicar: $h")
    }
}
