package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice1.Slice1Db.n
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * Audit loop 4: migracion 20260930101730 enmendada en sitio.
 *  - MASTER4-DB-003: gate, predicado del indice y VERIFY usan la misma regla (`despues->>'transactionId' is not null`);
 *    un transactionId JSON null (o un `despues` array/cadena que contiene "transactionId") no es un duplicado.
 *  - MASTER4-DB-001: PREFLIGHT de la tabla/columnas y post-check de la definicion EXACTA del indice: un indice
 *    previo con el mismo nombre pero no unico, invalido, con otras columnas u otro predicado hace fallar la migracion.
 */
class Slice1TicketsGrantAuditUniqueLoop4Test {
    private val migracion = "supabase/migrations/20260930101730_slice1_tickets_grant_audit_unique.sql"
    private val indice = "public.auditoria_log_tickets_grant_tx_uq"
    private val tx1 = "7d7d7d7d-0000-4000-8000-000000000001"
    private val registro = "6c6c6c6c-0000-4000-8000-000000000001"

    private fun Connection.auditar(accion: String, despues: String?): String? = try {
        ejecutar(
            "insert into public.auditoria_log (accion, tabla_afectada, registro_id, despues) values " +
                "('$accion', 'carteras_usuario', '$registro', ${despues?.let { "'$it'::jsonb" } ?: "null"})",
        )
        null
    } catch (e: SQLException) {
        e.sqlState
    }

    /** Aplica la migracion y devuelve el error del servidor (null si entro). Deja la conexion utilizable. */
    private fun Connection.aplicarMigracion(): PSQLException? {
        val error = runCatching { aplicar(migracion) }.exceptionOrNull()
        if (error != null) {
            runCatching { ejecutar("rollback") }
            autoCommit = true
        }
        return error as PSQLException?
    }

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    private fun Connection.definicion(): String? =
        unico("select pg_get_indexdef(to_regclass('$indice'))")

    // ---------------------------------------------------------------- MASTER4-DB-003

    @Test
    fun transactionIdJsonNullNoEsDuplicadoNiEnElGateNiEnVerify(): Unit = Slice1Db.escenario { c ->
        // Filas que el indice acepta (clave NULL, distintas entre si): antes el gate las agrupaba y abortaba.
        repeat(2) { assertNull(c.auditar("tickets_grant", """{"transactionId":null,"cantidad":5}""")) }
        repeat(2) { assertNull(c.auditar("tickets_grant", """["transactionId"]""")) }
        repeat(2) { assertNull(c.auditar("tickets_grant", """"transactionId"""")) }
        assertNull(c.aplicarMigracion(), "el gate no debe abortar con transactionId JSON null")
        assertEquals(mapOf("tickets_grant_tx_index_missing" to 0L, "tickets_grant_duplicate_transactions" to 0L), c.verify())
        // Tras la migracion siguen entrando (el indice no las cubre) y la garantia real sigue intacta.
        assertNull(c.auditar("tickets_grant", """{"transactionId":null}"""))
        assertNull(c.auditar("tickets_grant", """{"transactionId":"$tx1"}"""))
        assertEquals("23505", c.auditar("tickets_grant", """{"transactionId":"$tx1","cantidad":9}"""))
        assertEquals(0L, c.verify()["tickets_grant_duplicate_transactions"])
    }

    @Test
    fun conNullsPresentesElGateSigueDetectandoSoloLosDuplicadosReales(): Unit = Slice1Db.escenario { c ->
        repeat(3) { c.auditar("tickets_grant", """{"transactionId":null}""") }
        repeat(2) { c.auditar("tickets_grant", """{"transactionId":"$tx1"}""") }
        val error = assertNotNull(c.aplicarMigracion(), "duplicado real -> gate")
        assertEquals("TICKETS_GRANT_AUDIT_DUPLICATES_GATE", error.serverErrorMessage?.message)
        assertTrue(error.serverErrorMessage?.detail?.startsWith("1 wallet transaction(s)") == true, "${error.serverErrorMessage?.detail}")
        assertEquals(1L, c.verify()["tickets_grant_duplicate_transactions"], "VERIFY cuenta solo el grupo con clave no nula")
        assertEquals(5L, c.n("select count(*) from public.auditoria_log"), "no se borra evidencia")
        assertNull(c.definicion())
    }

    @Test
    fun elIndiceTieneLaMismaReglaQueElGateYSirveALaComprobacionPreviaDeKtor(): Unit = Slice1Db.escenario { c ->
        assertNull(c.aplicarMigracion())
        assertEquals(
            "CREATE UNIQUE INDEX auditoria_log_tickets_grant_tx_uq ON public.auditoria_log USING btree " +
                "(((despues ->> 'transactionId'::text))) WHERE ((accion = 'tickets_grant'::text) AND " +
                "((despues ->> 'transactionId'::text) IS NOT NULL))",
            c.definicion(),
        )
        // La forma del GET de Ktor (accion=eq & despues->>transactionId=eq) implica el predicado parcial.
        c.ejecutar("set enable_seqscan = off")
        val plan = c.filas(
            "explain (costs off) select id from public.auditoria_log where accion = 'tickets_grant' " +
                "and registro_id = '$registro' and (despues ->> 'transactionId') = '$tx1' limit 1",
        ).joinToString("\n") { it[0]!! }
        c.ejecutar("reset enable_seqscan")
        assertTrue("auditoria_log_tickets_grant_tx_uq" in plan, plan)
    }

    // ---------------------------------------------------------------- MASTER4-DB-001

    private fun conIndicePrevio(ddl: String) = Slice1Db.escenario { c ->
        c.ejecutar(ddl)
        val antes = c.definicion()
        val error = assertNotNull(c.aplicarMigracion(), "indice previo con el mismo nombre: $ddl")
        assertEquals("TICKETS_GRANT_AUDIT_UNIQUE_WRONG_DEFINITION", error.serverErrorMessage?.message)
        assertEquals(antes, c.definicion(), "la migracion no toca el objeto previo")
        assertEquals(1L, c.verify()["tickets_grant_tx_index_missing"], "VERIFY marca la deriva")
    }

    @Test
    fun indicePrevioNoUnicoConElMismoNombreHaceFallarLaMigracion() =
        conIndicePrevio("create index auditoria_log_tickets_grant_tx_uq on public.auditoria_log ((despues ->> 'transactionId')) where accion = 'tickets_grant' and (despues ->> 'transactionId') is not null")

    @Test
    fun indicePrevioUnicoConOtrasColumnasHaceFallarLaMigracion() =
        conIndicePrevio("create unique index auditoria_log_tickets_grant_tx_uq on public.auditoria_log (registro_id, (despues ->> 'transactionId')) where accion = 'tickets_grant' and (despues ->> 'transactionId') is not null")

    @Test
    fun indicePrevioConElPredicadoDeLoop3HaceFallarLaMigracion() =
        conIndicePrevio("create unique index auditoria_log_tickets_grant_tx_uq on public.auditoria_log ((despues ->> 'transactionId')) where accion = 'tickets_grant' and despues ? 'transactionId'")

    @Test
    fun indicePrevioUnicoSinPredicadoHaceFallarLaMigracion() =
        conIndicePrevio("create unique index auditoria_log_tickets_grant_tx_uq on public.auditoria_log ((despues ->> 'transactionId'))")

    @Test
    fun relacionPreviaQueNoEsIndiceHaceFallarLaMigracion(): Unit = Slice1Db.escenario { c ->
        c.ejecutar("create table public.auditoria_log_tickets_grant_tx_uq (x int)")
        val error = assertNotNull(c.aplicarMigracion())
        assertEquals("TICKETS_GRANT_AUDIT_UNIQUE_WRONG_DEFINITION", error.serverErrorMessage?.message)
        assertTrue("not an index" in (error.serverErrorMessage?.detail ?: ""), "${error.serverErrorMessage?.detail}")
        assertEquals(1L, c.verify()["tickets_grant_tx_index_missing"])
    }

    @Test
    fun indiceConLaDefinicionCorrectaPeroInvalidoHaceFallarLaReaplicacion(): Unit = Slice1Db.escenario { c ->
        assertNull(c.aplicarMigracion())
        // Simula el resto de un CREATE INDEX CONCURRENTLY fallido (indisvalid = false) con la definicion exacta.
        c.ejecutar("update pg_index set indisvalid = false where indexrelid = to_regclass('$indice')")
        val error = assertNotNull(c.aplicarMigracion())
        assertEquals("TICKETS_GRANT_AUDIT_UNIQUE_WRONG_DEFINITION", error.serverErrorMessage?.message)
        assertEquals(1L, c.verify()["tickets_grant_tx_index_missing"])
    }

    @Test
    fun laComprobacionExactaNoDependeDelSearchPath(): Unit = Slice1Db.escenario { c ->
        c.ejecutar("set search_path = ''")
        try {
            assertNull(c.aplicarMigracion(), "search_path vacio")
            assertNull(c.aplicarMigracion(), "reaplicar con search_path vacio")
        } finally {
            c.ejecutar("reset search_path")
        }
        assertEquals(0L, c.verify()["tickets_grant_tx_index_missing"])
    }

    @Test
    fun preflightSinTablaAuditoria(): Unit = Slice1Db.escenario { c ->
        c.ejecutar("drop table public.auditoria_log")
        val error = assertNotNull(c.aplicarMigracion())
        assertEquals("TICKETS_GRANT_AUDIT_PREFLIGHT", error.serverErrorMessage?.message)
    }

    @Test
    fun preflightConDespuesQueNoEsJsonb(): Unit = Slice1Db.escenario { c ->
        c.ejecutar("alter table public.auditoria_log alter column despues type text using despues::text")
        val error = assertNotNull(c.aplicarMigracion())
        assertEquals("TICKETS_GRANT_AUDIT_PREFLIGHT", error.serverErrorMessage?.message)
        assertNull(c.definicion())
    }

    @Test
    fun preflightSinColumnaAccion(): Unit = Slice1Db.escenario { c ->
        c.ejecutar("alter table public.auditoria_log drop column accion")
        val error = assertNotNull(c.aplicarMigracion())
        assertEquals("TICKETS_GRANT_AUDIT_PREFLIGHT", error.serverErrorMessage?.message)
    }
}
