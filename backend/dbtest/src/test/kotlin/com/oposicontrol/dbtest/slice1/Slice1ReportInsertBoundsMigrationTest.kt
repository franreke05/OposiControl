package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * RC-08 (MASTERF2-DB-002): migracion 20260930102300. Tras 102000 un cliente autenticado aun podia insertar por la Data
 * API un reporte con `creado_en` retrasado (entierra reportes reales en la cola `creado_en asc`) y `motivo`/`descripcion`
 * de tamano arbitrario. Control NEGATIVO sobre el estado de 102000; despues: marcas de tiempo del servidor (otro valor
 * = 42501), texto acotado a 2000 caracteres (23514), insercion legitima intacta, ROLLBACK exacto, filas historicas
 * fuera de rango (NOT VALID, informado) y PREFLIGHT cerrado.
 */
class Slice1ReportInsertBoundsMigrationTest {
    private val migracion = "supabase/migrations/20260930102300_slice1_report_insert_bounds.sql"
    private val reportAction = "supabase/migrations/20260930102000_slice1_report_action_once.sql"
    private val motivoCheck = "reportes_contenido_motivo_longitud_check"
    private val descripcionCheck = "reportes_contenido_descripcion_longitud_check"

    private fun <T> escenario(conMigracion: Boolean = true, antes: (Connection) -> Unit = {}, bloque: (Connection) -> T): T =
        PostgresHarness.escenario { c ->
            c.ejecutar(Slice1ReportActionOnceMigrationTest().mainShapes)
            c.aplicar(reportAction)
            antes(c)
            if (conMigracion) c.aplicar(migracion)
            bloque(c)
        }

    /** Insercion como cliente de PostgREST (`set local role` + claim sub), confirmada si va bien. SQLSTATE o null. */
    private fun Connection.comoCliente(sql: String, sub: String = USER_A, rol: String = "authenticated"): String? {
        ejecutar("begin")
        return try {
            ejecutar("set local role $rol")
            ejecutar("select set_config('request.jwt.claim.sub', '$sub', true)")
            ejecutar(sql)
            ejecutar("commit")
            null
        } catch (e: SQLException) {
            runCatching { ejecutar("rollback") }
            e.sqlState
        }
    }

    private fun insert(columnas: String = "", valores: String = "", motivo: String = "'spam'") =
        "insert into public.reportes_contenido (tipo_entidad, entidad_id, motivo, reportado_por$columnas) values " +
            "('publicacion_oposired', gen_random_uuid(), $motivo, '$USER_A'$valores)"

    private val atrasado = insert(", creado_en", ", now() - interval '30 days'")

    private fun Connection.verify(): List<List<String?>> = filas(Slice0Db.verifyDe(migracion))

    private fun Connection.politica(): String = unico(
        "select pg_get_expr(polwithcheck, polrelid) from pg_policy where polrelid = 'public.reportes_contenido'::regclass " +
            "and polname = 'reportes_contenido_insert_auth'",
    )!!

    private fun Connection.checks(): String? = unico(
        "select string_agg(conname || ':' || convalidated::text, ',' order by conname) from pg_constraint " +
            "where conrelid = 'public.reportes_contenido'::regclass and conname in ('$motivoCheck', '$descripcionCheck')",
    )

    private fun Connection.fallo(sql: String): PSQLException? {
        val e = runCatching { ejecutar(sql) }.exceptionOrNull() as PSQLException?
        runCatching { ejecutar("rollback") }
        autoCommit = true
        return e
    }

    /** Control NEGATIVO: con la politica de 102000 un cliente retrasa creado_en y mete texto sin limite. */
    @Test
    fun antesDeLaMigracionUnClienteRetrasaCreadoEnYMeteTextoSinLimite(): Unit = escenario(conMigracion = false) { c ->
        assertNull(c.comoCliente(atrasado))
        assertEquals("true", c.unico("select bool_and(creado_en < now() - interval '29 days')::text from public.reportes_contenido"))
        assertNull(c.comoCliente(insert(", descripcion", ", repeat('x', 100000)", motivo = "repeat('m', 100000)")))
        assertEquals("2", c.unico("select count(*)::text from public.reportes_contenido"))
        val v = c.verify().associate { it[0]!! to it[1]!!.toLong() }
        assertEquals(1L, v["report_insert_policy_timestamps_missing"], v.toString())
        assertEquals(2L, v["report_text_bound_missing"], v.toString())
    }

    @Test
    fun trasLaMigracionLasMarcasDeTiempoSonDelServidorYElTextoEstaAcotado(): Unit = escenario { c ->
        assertEquals("42501", c.comoCliente(atrasado), "creado_en retrasado")
        assertEquals("42501", c.comoCliente(insert(", creado_en", ", now() + interval '1 day'")), "creado_en futuro")
        assertEquals("42501", c.comoCliente(insert(", actualizado_en", ", now() - interval '1 day'")), "actualizado_en retrasado")
        assertEquals("23514", c.comoCliente(insert(motivo = "repeat('m', 2001)")), "motivo > 2000")
        assertEquals("23514", c.comoCliente(insert(", descripcion", ", repeat('d', 2001)")), "descripcion > 2000")
        assertEquals("0", c.unico("select count(*)::text from public.reportes_contenido"))
        // Insercion legitima: pendiente, propia, columnas del servidor por defecto; el limite cuenta caracteres, no bytes.
        assertNull(c.comoCliente(insert()))
        assertNull(c.comoCliente(insert(", descripcion", ", repeat('ñ', 2000)", motivo = "repeat('ñ', 2000)")))
        assertEquals("2", c.unico("select count(*)::text from public.reportes_contenido where creado_en = actualizado_en and creado_en > now() - interval '1 minute'"))
        // service_role (Ktor) no pasa por la RLS, pero el CHECK tambien le aplica.
        c.ejecutar("set role service_role")
        try {
            assertEquals("23514", runCatching { c.ejecutar(insert(motivo = "repeat('m', 2001)")) }.exceptionOrNull()?.let { (it as SQLException).sqlState })
        } finally {
            c.ejecutar("reset role")
        }
        val v = c.verify()
        assertEquals(3, v.size, v.toString())
        assertTrue(v.all { it[1] == "0" }, v.toString())
        assertEquals("all validated", v.single { it[0] == "report_text_bound_not_validated" }[2])
        assertEquals("$descripcionCheck:true,$motivoCheck:true", c.checks())
    }

    @Test
    fun elRollbackRestauraLaPoliticaDe102000ExactaYTodoEsIdempotente(): Unit = escenario(conMigracion = false) { c ->
        val pol102000 = c.politica()
        c.aplicar(migracion)
        val aplicada = c.politica()
        assertNotEquals(pol102000, aplicada)
        c.aplicar(migracion) // re-ejecucion: no-op
        assertEquals(aplicada, c.politica())
        assertEquals("$descripcionCheck:true,$motivoCheck:true", c.checks())
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(pol102000, c.politica(), "WITH CHECK de 102000 byte a byte")
        assertNull(c.checks())
        c.ejecutar(Slice0Db.rollbackDe(migracion)) // re-ejecutable
        assertEquals(pol102000, c.politica())
        assertNull(c.comoCliente(atrasado), "el ROLLBACK reabre (documentado) el camino")
        c.aplicar(migracion)
        assertEquals(aplicada, c.politica())
        assertTrue(c.verify().all { it[1] == "0" }, c.verify().toString())
    }

    /** Filas historicas fuera de rango: el CHECK queda NOT VALID (aplica a filas nuevas) y el VERIFY lo informa. */
    @Test
    fun filasHistoricasFueraDeRangoDejanElCheckNotValidPeroAplicado(): Unit = escenario(
        antes = { it.ejecutar(insert(motivo = "repeat('m', 3000)")) },
    ) { c ->
        assertEquals("$descripcionCheck:true,$motivoCheck:false", c.checks())
        assertEquals("23514", c.comoCliente(insert(motivo = "repeat('m', 2001)")), "una fila nueva sigue acotada")
        val v = c.verify()
        assertTrue(v.all { it[1] == "0" }, v.toString())
        assertEquals("not validated: $motivoCheck", v.single { it[0] == "report_text_bound_not_validated" }[2])
        c.aplicar(migracion) // re-ejecucion con la fila aun fuera de rango: sigue NOT VALID, sin error
        c.ejecutar("update public.reportes_contenido set motivo = 'spam'")
        c.aplicar(migracion) // y una vez corregida, la re-ejecucion la valida
        assertEquals("$descripcionCheck:true,$motivoCheck:true", c.checks())
    }

    @Test
    fun preflightFallaCerradoSiLaPoliticaOLosChecksNoSonLosEsperados(): Unit = escenario(conMigracion = false) { c ->
        fun rechaza(caso: String, sembrar: String, deshacer: String) {
            c.ejecutar(sembrar)
            val pol = c.politica()
            val chk = c.checks()
            val e = c.fallo(Slice0Db.leer(migracion))
            assertEquals("REPORT_INSERT_BOUNDS_PREFLIGHT", e?.serverErrorMessage?.message, caso)
            assertEquals(pol, c.politica(), "politica sin tocar ($caso)")
            assertEquals(chk, c.checks(), "checks sin tocar ($caso)")
            c.ejecutar(deshacer)
        }
        val pol102000 = c.politica()
        rechaza(
            "politica de MAIN (102000 no aplicada a la politica)",
            "alter policy reportes_contenido_insert_auth on public.reportes_contenido with check ((reportado_por = (select auth.uid())))",
            "alter policy reportes_contenido_insert_auth on public.reportes_contenido with check (" +
                "reportado_por = (select auth.uid()) and estado = 'pendiente' and revisado_por is null and revisado_en is null " +
                "and tickets_otorgados = 0 and recompensa_entregada = false and accion_pendiente = false)",
        )
        assertEquals(pol102000, c.politica())
        rechaza(
            "check con el mismo nombre y otra definicion",
            "alter table public.reportes_contenido add constraint $motivoCheck check (char_length(motivo) <= 9000)",
            "alter table public.reportes_contenido drop constraint $motivoCheck",
        )
        rechaza(
            "sin la columna de 102000",
            "alter policy reportes_contenido_insert_auth on public.reportes_contenido with check ((reportado_por = (select auth.uid()))); " +
                "alter table public.reportes_contenido drop column accion_pendiente",
            "alter table public.reportes_contenido add column accion_pendiente boolean not null default false; " +
                "alter policy reportes_contenido_insert_auth on public.reportes_contenido with check (" +
                "reportado_por = (select auth.uid()) and estado = 'pendiente' and revisado_por is null and revisado_en is null " +
                "and tickets_otorgados = 0 and recompensa_entregada = false and accion_pendiente = false)",
        )
        assertEquals(pol102000, c.politica())
        c.aplicar(migracion) // control: el estado de 102000 aplica
        assertTrue(c.verify().all { it[1] == "0" }, c.verify().toString())
    }
}
