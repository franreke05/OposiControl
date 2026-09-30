package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice1.Slice1Db.n
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit loop 3 (MASTER3-OBS-002): migracion 20260930101730. La unicidad de la fila `tickets_grant` por transaccion
 * de cartera ya no depende del leer-y-luego-insertar de Ktor: un indice unico parcial la garantiza de forma atomica
 * (dos conexiones reales), sin tocar ninguna otra accion ni las filas historicas sin transactionId.
 */
class Slice1TicketsGrantAuditUniqueMigrationTest {
    private val migracion = "supabase/migrations/20260930101730_slice1_tickets_grant_audit_unique.sql"
    private val tx1 = "7c7c7c7c-0000-4000-8000-000000000001"
    private val tx2 = "7c7c7c7c-0000-4000-8000-000000000002"
    private val registro = "6b6b6b6b-0000-4000-8000-000000000001"

    /** SQLSTATE del INSERT (null si entro). Como service_role, igual que el POST de Ktor via PostgREST. */
    private fun Connection.auditar(accion: String, despues: String?): String? = try {
        ejecutar("set role service_role")
        ejecutar(
            "insert into public.auditoria_log (accion, tabla_afectada, registro_id, despues) values " +
                "('$accion', 'carteras_usuario', '$registro', ${despues?.let { "'$it'::jsonb" } ?: "null"})",
        )
        null
    } catch (e: SQLException) {
        e.sqlState
    } finally {
        runCatching { ejecutar("reset role") }
    }

    private fun grant(tx: String) = """{"transactionId":"$tx","cantidad":5}"""

    @Test
    fun unaSegundaFilaDeLaMismaTransaccionEs23505YLasDemasNoCambian(): Unit = Slice1Db.escenario { c ->
        c.aplicar(migracion)
        assertNull(c.auditar("tickets_grant", grant(tx1)))
        assertEquals("23505", c.auditar("tickets_grant", grant(tx1)), "misma transaccion -> unique_violation")
        assertNull(c.auditar("tickets_grant", grant(tx2)), "otra transaccion entra")
        // Filas historicas sin transactionId (Ktor de MAIN) y otras acciones: fuera del indice.
        assertNull(c.auditar("tickets_grant", """{"cantidad":5}"""))
        assertNull(c.auditar("tickets_grant", """{"cantidad":5}"""))
        assertNull(c.auditar("tickets_grant", null))
        assertNull(c.auditar("otra_accion", grant(tx1)))
        assertNull(c.auditar("otra_accion", grant(tx1)))
        assertEquals(1L, c.n("select count(*) from public.auditoria_log where accion = 'tickets_grant' and despues ->> 'transactionId' = '$tx1'"))
    }

    @Test
    fun dosInsercionesConcurrentesDeLaMismaTransaccionDejanUnaSolaFila(): Unit = Slice1Db.escenario { c ->
        c.aplicar(migracion)
        val otra = PostgresHarness.conexionExtra(c)
        val pool = Executors.newSingleThreadExecutor()
        try {
            c.autoCommit = false
            assertNull(c.auditar("tickets_grant", grant(tx1)))
            // La segunda espera al cerrojo del indice unico de la primera (transaccion abierta).
            val segunda = pool.submit<String?> { otra.auditar("tickets_grant", grant(tx1)) }
            Thread.sleep(300)
            assertFalse(segunda.isDone, "la segunda debe esperar a la primera")
            c.commit()
            assertEquals("23505", segunda.get(20, TimeUnit.SECONDS), "la perdedora recibe unique_violation (= ya auditada)")
            c.autoCommit = true
            assertEquals(1L, c.n("select count(*) from public.auditoria_log where despues ->> 'transactionId' = '$tx1'"))
        } finally {
            pool.shutdownNow()
            otra.close()
        }
    }

    @Test
    fun conDuplicadosPreviosLaMigracionSeNiegaYNoBorraNada(): Unit = Slice1Db.escenario { c ->
        assertNull(c.auditar("tickets_grant", grant(tx1)))
        assertNull(c.auditar("tickets_grant", grant(tx1)))
        val error = runCatching { c.aplicar(migracion) }.exceptionOrNull()
        assertTrue(error is SQLException && "TICKETS_GRANT_AUDIT_DUPLICATES_GATE" in (error.message ?: ""), "gate: $error")
        runCatching { c.ejecutar("rollback") }
        c.autoCommit = true
        assertEquals(2L, c.n("select count(*) from public.auditoria_log where accion = 'tickets_grant'"), "no se borra evidencia")
        assertEquals(0L, c.n("select count(*) from pg_class where relname = 'auditoria_log_tickets_grant_tx_uq'"))
    }

    @Test
    fun verifyEnVerdeRollbackQuitaElIndiceYReaplicarEsIdempotente(): Unit = Slice1Db.escenario { c ->
        c.aplicar(migracion)
        c.ejecutar("reset role")
        val f = c.filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }
        assertEquals(2, f.size, f.toString())
        assertTrue(f.values.all { it == 0L }, "VERIFY: $f")

        c.ejecutar(Slice0Db.rollbackDe(migracion))
        val g = c.filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }
        assertEquals(1L, g["tickets_grant_tx_index_missing"], "tras el rollback no hay indice: $g")
        assertNull(c.auditar("tickets_grant", grant(tx1)))
        assertNull(c.auditar("tickets_grant", grant(tx2)))

        c.aplicar(migracion)
        c.aplicar(migracion) // idempotente
        val h = c.filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }
        assertTrue(h.values.all { it == 0L }, "VERIFY tras reaplicar: $h")
    }
}
