package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.SupabaseFixture.USER_C
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.postgresql.util.PSQLException

/**
 * MASTER6-API-001: migracion 20260930102000. `admin_record_report_action` bloquea la fila del reporte, comprueba
 * (compare-and-set) que esta cerrado en el estado esperado por el mismo moderador y que aun no tiene accion, e inserta
 * la accion en la MISMA transaccion. Antes Ktor hacia GET "sin accion" + POST: dos reanudaciones concurrentes insertaban
 * dos filas en `acciones_moderacion`.
 */
class Slice1ReportActionOnceMigrationTest {
    private val migracion = "supabase/migrations/20260930102000_slice1_report_action_once.sql"
    private val fn = "public.admin_record_report_action(uuid,text,uuid,text,text)"
    private val reporte = "7e7e7e7e-0000-4000-8000-000000000001"

    /** Forma de MAIN (baseline_main.sql) de las dos tablas, con su FK, CHECK e indice no unico. */
    private val mainShapes = """
        create table public.reportes_contenido (
          id uuid not null default gen_random_uuid() primary key, reportado_por uuid, tipo_entidad text not null,
          entidad_id uuid not null, motivo text not null, descripcion text, estado text not null default 'pendiente',
          creado_en timestamptz not null default now(), actualizado_en timestamptz not null default now(),
          tickets_otorgados integer not null default 0, recompensa_entregada boolean not null default false,
          revisado_por uuid, revisado_en timestamptz
        );
        create table public.acciones_moderacion (
          id uuid not null default gen_random_uuid() primary key,
          reporte_id uuid references public.reportes_contenido(id) on delete set null,
          moderador_id uuid references auth.users(id) on delete set null,
          tipo_accion text not null, notas text, creado_en timestamptz not null default now(),
          constraint acciones_moderacion_tipo_accion_check check ((tipo_accion = any (array['ocultar'::text, 'restaurar'::text,
            'eliminar'::text, 'advertir'::text, 'bloquear_usuario'::text, 'descartar'::text])))
        );
        create index idx_acciones_moderacion_reporte_id on public.acciones_moderacion (reporte_id);
        alter table public.acciones_moderacion enable row level security;
        alter table public.reportes_contenido enable row level security;
    """.trimIndent()

    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.ejecutar(mainShapes)
        if (conMigracion) c.aplicar(migracion)
        bloque(c)
    }

    private fun Connection.reporteCerrado(estado: String, revisor: String?) = ejecutar(
        "insert into public.reportes_contenido (id, tipo_entidad, entidad_id, motivo, estado, revisado_por) values " +
            "('$reporte', 'publicacion_oposired', gen_random_uuid(), 'spam', '$estado', ${revisor?.let { "'$it'" } ?: "null"})",
    )

    private fun Connection.registrar(estado: String, moderador: String?, accion: String = "ocultar"): Result<Boolean> = try {
        ejecutar("set role service_role")
        Result.success(
            unico(
                "select public.admin_record_report_action('$reporte'::uuid, '$estado', " +
                    "${moderador?.let { "'$it'::uuid" } ?: "null::uuid"}, '$accion', 'nota')::text",
            ) == "true",
        )
    } catch (e: SQLException) {
        Result.failure(e)
    } finally {
        runCatching { ejecutar("reset role") }
    }

    private fun Connection.acciones(): Long =
        unico("select count(*) from public.acciones_moderacion where reporte_id = '$reporte'")!!.toLong()

    @Test
    fun registraLaAccionUnaSolaVezYElReintentoEsNoOp(): Unit = escenario { c ->
        c.reporteCerrado("resuelto", USER_A)
        assertTrue(c.registrar("resuelto", USER_A).getOrThrow())
        assertFalse(c.registrar("resuelto", USER_A).getOrThrow(), "resolucion ya completa")
        assertEquals(1L, c.acciones())
        assertEquals(USER_A, c.unico("select moderador_id::text from public.acciones_moderacion where reporte_id = '$reporte'"))
    }

    @Test
    fun otroModeradorOtroEstadoUnReporteAbiertoOInexistenteNoEscriben(): Unit = escenario { c ->
        c.reporteCerrado("resuelto", USER_A)
        assertFalse(c.registrar("resuelto", USER_B).getOrThrow(), "otro moderador")
        assertFalse(c.registrar("resuelto", null).getOrThrow(), "sin actor frente a un revisor real")
        assertFalse(c.registrar("descartado", USER_A).getOrThrow(), "otro destino")
        assertFalse(c.registrar("pendiente", USER_A).getOrThrow(), "un estado abierto nunca se acepta")
        c.ejecutar("update public.reportes_contenido set estado = 'revisado'")
        assertFalse(c.registrar("revisado", USER_A).getOrThrow())
        c.ejecutar("delete from public.reportes_contenido")
        assertFalse(c.registrar("resuelto", USER_A).getOrThrow(), "reporte inexistente")
        assertEquals(0L, c.unico("select count(*) from public.acciones_moderacion")!!.toLong())
    }

    @Test
    fun elCheckDeTipoAccionSePropaga(): Unit = escenario { c ->
        c.reporteCerrado("descartado", USER_A)
        val error = c.registrar("descartado", USER_A, accion = "inventada").exceptionOrNull() as PSQLException?
        assertEquals("23514", error?.sqlState)
        assertEquals(0L, c.acciones())
    }

    /**
     * Concurrencia REAL: N conexiones del mismo moderador reanudan a la vez el mismo reporte cerrado sin accion. Solo una
     * inserta; el resto espera al cerrojo de fila, ve la accion confirmada y devuelve false.
     */
    @Test
    fun reanudacionesConcurrentesSoloCreanUnaFila(): Unit = escenario { c ->
        c.reporteCerrado("resuelto", USER_A)
        val n = 6
        val conexiones = List(n) { PostgresHarness.conexionExtra(c) }
        val pool = Executors.newFixedThreadPool(n)
        val barrera = CyclicBarrier(n)
        try {
            val resultados = conexiones.map { cx ->
                pool.submit<Boolean> {
                    barrera.await(10, TimeUnit.SECONDS)
                    cx.registrar("resuelto", USER_A).getOrThrow()
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, resultados.count { it }, "una sola gana: $resultados")
            assertEquals(1L, c.acciones())
        } finally {
            pool.shutdownNow()
            conexiones.forEach { runCatching { it.close() } }
        }
    }

    /** Determinista: la primera llamada tiene la fila bloqueada y aun no confirma; la segunda espera y luego ve la accion. */
    @Test
    fun laSegundaEsperaAlCerrojoYVeLaAccionConfirmada(): Unit = escenario { c ->
        c.reporteCerrado("resuelto", USER_A)
        val otra = PostgresHarness.conexionExtra(c)
        val pool = Executors.newSingleThreadExecutor()
        try {
            otra.autoCommit = false
            otra.ejecutar("set role service_role")
            assertEquals("true", otra.unico("select public.admin_record_report_action('$reporte'::uuid, 'resuelto', '$USER_A'::uuid, 'ocultar', null)::text"))
            val segunda = pool.submit<Boolean> { c.registrar("resuelto", USER_A).getOrThrow() }
            Thread.sleep(300)
            assertFalse(segunda.isDone, "espera al cerrojo de la fila del reporte")
            otra.commit()
            assertFalse(segunda.get(20, TimeUnit.SECONDS), "tras la confirmacion ve la accion: no inserta otra")
            assertEquals(1L, c.acciones())
        } finally {
            pool.shutdownNow()
            otra.close()
        }
    }

    @Test
    fun soloServiceRoleDefinerConSearchPathFijoYRollbackIdempotente(): Unit = escenario { c ->
        fun verify() = c.filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }
        val v = verify()
        assertEquals(3, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY: $v")
        assertEquals("true", c.unico("select has_function_privilege('service_role', '$fn', 'execute')::text"))
        assertEquals("false", c.unico("select has_function_privilege('anon', '$fn', 'execute')::text"))
        assertEquals("false", c.unico("select has_function_privilege('authenticated', '$fn', 'execute')::text"))
        assertEquals("search_path=\"\";row_security=off", c.unico("select array_to_string(proconfig, ';') from pg_proc where oid = '$fn'::regprocedure"))
        // Un cliente no puede llamarla aunque tenga la tabla abierta.
        c.reporteCerrado("resuelto", USER_C)
        c.ejecutar("set role authenticated")
        val denegado = runCatching { c.unico("select public.admin_record_report_action('$reporte'::uuid, 'resuelto', '$USER_C'::uuid, 'ocultar', null)") }
        c.ejecutar("reset role")
        assertEquals("42501", (denegado.exceptionOrNull() as PSQLException?)?.sqlState)

        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertNull(c.unico("select to_regprocedure('$fn')::text"))
        assertEquals(1L, verify()["report_action_function_missing"])
        assertNotNull(c.unico("select to_regclass('public.idx_acciones_moderacion_reporte_id')::text"), "el rollback no toca el indice")
        c.aplicar(migracion)
        c.aplicar(migracion)
        assertTrue(verify().values.all { it == 0L })
    }

    @Test
    fun preflightSinLasTablasOConTiposDistintosNoCreaNada(): Unit = escenario(conMigracion = false) { c ->
        c.ejecutar("alter table public.acciones_moderacion alter column notas type varchar(50)")
        val error = runCatching { c.aplicar(migracion) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        c.autoCommit = true
        assertEquals("REPORT_ACTION_PREFLIGHT", error?.serverErrorMessage?.message)
        assertNull(c.unico("select to_regprocedure('$fn')::text"))
        c.ejecutar("drop table public.acciones_moderacion")
        val sinTabla = runCatching { c.aplicar(migracion) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        assertEquals("REPORT_ACTION_PREFLIGHT", sinTabla?.serverErrorMessage?.message)
    }
}
