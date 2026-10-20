package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * PAY-F3: migracion 20261023100200. El dashboard de Control contaba como ingreso toda compra `pagada`, incluidas las de
 * sandbox de las tiendas. Forma de MAIN: las columnas que lee la funcion + `entorno` (OposiBot 20261022102600) y la funcion
 * de 20260917090229, que es EXACTAMENTE el bloque ROLLBACK de la migracion (se instala ejecutandolo).
 */
class RevenueExcludesSandboxMigrationTest {
    private val migracion = "supabase/migrations/20261023100200_security_revenue_excludes_sandbox.sql"

    private val tablas = """
        create table public.compras_usuario (id serial primary key, estado text not null, precio_centimos integer, moneda text not null default 'EUR', entorno text);
        create table public.carteras_usuario (usuario_id serial primary key, saldo_tickets integer not null default 0);
        insert into public.compras_usuario (estado, precio_centimos, entorno) values
          ('pagada', 449, null), ('pagada', 999, 'produccion'), ('pagada', 449, 'sandbox'), ('pendiente', 100, 'produccion');
        insert into public.carteras_usuario (saldo_tickets) values (50), (25);
    """.trimIndent()

    private fun <T> escenario(conEntorno: Boolean = true, conMigracion: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.ejecutar(if (conEntorno) tablas else tablas.replace(", entorno text", "").replace(", entorno)", ")").replace(", null)", ")").replace(", 'produccion')", ")").replace(", 'sandbox')", ")"))
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // forma de MAIN (20260917090229)
        if (conMigracion) c.aplicar(migracion)
        bloque(c)
    }

    private fun Connection.totales(): String = unico("select gross_revenue_cents || '|' || paid_purchases || '|' || tickets_in_circulation from public.admin_dashboard_economy_totals()")!!

    private fun Connection.verify(): Map<String, Long> = filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    @Test
    fun antesLaCompraSandboxContabaComoIngreso(): Unit = escenario(conMigracion = false) { c ->
        assertEquals("1897|3|75", c.totales(), "MAIN: 449 + 999 + 449 de sandbox")
        assertEquals(1L, c.verify()["revenue_counts_sandbox"])
    }

    @Test
    fun trasLaMigracionSoloCuentaProduccionYLegado(): Unit = escenario { c ->
        assertEquals("1448|2|75", c.totales(), "legado (entorno nulo) + produccion; el sandbox no es ingreso; los OT no cambian")
        assertTrue(c.verify().values.all { it == 0L }, c.verify().toString())
    }

    @Test
    fun rollbackExactoEIdempotente(): Unit = escenario { c ->
        c.aplicar(migracion)
        assertEquals("1448|2|75", c.totales())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals("1897|3|75", c.totales(), "vuelve el cuerpo de MAIN")
        c.aplicar(migracion)
        assertTrue(c.verify().values.all { it == 0L })
    }

    /** Sin `compras_usuario.entorno` (sin la 102600 de OposiBot) la migracion no se aplica y no cambia nada. */
    @Test
    fun preflightFallaCerradoSinLaColumnaEntorno(): Unit = escenario(conEntorno = false, conMigracion = false) { c ->
        val antes = c.totales()
        val e = runCatching { c.ejecutar(Slice0Db.leer(migracion)) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        c.autoCommit = true
        assertEquals("P0001", e?.sqlState)
        assertEquals("REVENUE_SANDBOX_PREFLIGHT", e?.serverErrorMessage?.message)
        assertEquals(antes, c.totales())
    }
}
