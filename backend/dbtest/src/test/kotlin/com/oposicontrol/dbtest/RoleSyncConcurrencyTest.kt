package com.oposicontrol.dbtest

import com.oposicontrol.dbtest.SupabaseFixture.ADMIN
import com.oposicontrol.dbtest.SupabaseFixture.SUPERADMIN
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CONCURRENCIA DE VERDAD: dos conexiones PostgreSQL independientes.
 *
 * La invariante del ultimo superadministrador es GLOBAL. Dos transacciones que
 * degraden cada una a un superadministrador distinto no se ven entre si: cada
 * una cuenta como superviviente al que la otra esta quitando, y al confirmar
 * quedan cero. Eso no se puede demostrar con dos llamadas secuenciales.
 */
class RoleSyncConcurrencyTest {

    @Test
    fun elCerrojoSerializaYNuncaQuedanCeroSuperadministradores(): Unit =
        PostgresHarness.escenario { c1 ->
            c1.asignar(USER_A, SUPERADMIN)
            c1.asignar(USER_B, SUPERADMIN)
            c1.aplicarGuard()

            val c2 = PostgresHarness.conexionExtra(c1)
            try {
                c1.autoCommit = false
                c2.autoCommit = false

                // T1 degrada a A y NO confirma: se queda con el cerrojo.
                assertNull(c1.sincronizarRoles(USER_A, listOf(ADMIN)))

                val resultadoT2 = AtomicReference<String?>(EN_CURSO)
                val arrancado = CountDownLatch(1)
                val terminado = CountDownLatch(1)
                val t2 = Thread {
                    arrancado.countDown()
                    resultadoT2.set(c2.sincronizarRoles(USER_B, listOf(ADMIN)))
                    terminado.countDown()
                }
                t2.isDaemon = true
                t2.start()
                arrancado.await()

                // T2 tiene que quedarse esperando el cerrojo, no adelantar.
                assertEquals(
                    false,
                    terminado.await(2, TimeUnit.SECONDS),
                    "T2 no se bloqueo: el cerrojo consultivo no esta serializando.",
                )
                assertEquals(EN_CURSO, resultadoT2.get())

                c1.commit()
                assertEquals(true, terminado.await(30, TimeUnit.SECONDS), "T2 no desbloqueo.")

                val errorT2 = resultadoT2.get()
                assertNotNull(errorT2, "T2 vio el estado ya actualizado y tenia que abortar.")
                assertEquals(true, "ROLE_SYNC_LAST_SUPERADMIN" in errorT2, errorT2)
                c2.rollback()

                c1.autoCommit = true
                assertEquals(listOf("admin"), c1.rolesDe(USER_A))
                assertEquals(listOf("superadmin"), c1.rolesDe(USER_B))
                assertEquals(1, c1.superadminsActivos(), "Nunca pueden quedar cero.")
            } finally {
                runCatching { c2.close() }
            }
        }

    /**
     * La mutacion del cerrojo. Sin `pg_advisory_xact_lock` las dos degradaciones
     * se entrelazan: cada transaccion cuenta al superadministrador que la otra
     * esta quitando, ninguna aborta, y al confirmar quedan CERO.
     *
     * Es determinista porque la secuencia esta forzada: T1 escribe sin confirmar,
     * T2 hace su operacion entera y confirma, y solo despues confirma T1.
     */
    @Test
    fun sinElCerrojoLasDosDegradacionesSeEntrelazanYDejanCeroSuperadministradores(): Unit =
        PostgresHarness.escenario { c1 ->
            c1.asignar(USER_A, SUPERADMIN)
            c1.asignar(USER_B, SUPERADMIN)
            c1.aplicarGuardMutado {
                it.replace(
                    "perform pg_advisory_xact_lock(hashtextextended('admin-role-sync', 0));",
                    "-- cerrojo retirado por la mutacion",
                )
            }

            val c2 = PostgresHarness.conexionExtra(c1)
            try {
                c1.autoCommit = false
                c2.autoCommit = false

                assertNull(c1.sincronizarRoles(USER_A, listOf(ADMIN)))
                assertNull(c2.sincronizarRoles(USER_B, listOf(ADMIN)), "Sin cerrojo T2 no se bloquea.")
                c2.commit()
                c1.commit()

                c1.autoCommit = true
                assertEquals(
                    0,
                    c1.superadminsActivos(),
                    "Sin el cerrojo la carrera tenia que dejar cero superadministradores.",
                )
            } finally {
                runCatching { c2.close() }
            }
        }

    private companion object {
        const val EN_CURSO = "EN_CURSO"
    }
}
