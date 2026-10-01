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
 * (compare-and-set) que esta cerrado en el estado esperado por el mismo moderador y con la marca en vuelo
 * `accion_pendiente` (que solo pone el compare-and-set del Ktor nuevo), inserta la accion y limpia la marca en la MISMA
 * transaccion. Antes Ktor hacia GET "sin accion" + POST: dos reanudaciones concurrentes insertaban dos filas en
 * `acciones_moderacion`. Round 3: hechos verificados del Ktor antiguo (MAIN) en
 * [ningunCierreHistoricoDelKtorAntiguoEsReanudableNiRecibeOtraAccion]: accion PRIMERO (creado_en = now() de la BD),
 * despues el PATCH con `revisado_en` = reloj de Ktor; por eso ninguna clave de tiempo distingue un cierre historico.
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

    /** Cierre del Ktor NUEVO: el compare-and-set deja la marca `accion_pendiente` = true hasta que la RPC registra. */
    private fun Connection.reporteCerrado(estado: String, revisor: String?) = reporteCerradoEn(estado, revisor, "now()")

    /** Cierre del Ktor NUEVO con `revisado_en` del reloj de Ktor (aqui, una expresion SQL) y la marca en vuelo. */
    private fun Connection.reporteCerradoEn(estado: String, revisor: String?, revisadoEn: String) = ejecutar(
        "insert into public.reportes_contenido (id, tipo_entidad, entidad_id, motivo, estado, revisado_por, revisado_en, accion_pendiente) values " +
            "('$reporte', 'publicacion_oposired', gen_random_uuid(), 'spam', '$estado', ${revisor?.let { "'$it'" } ?: "null"}, $revisadoEn, true)",
    )

    /**
     * Cierre HISTORICO tal y como lo escribio el Ktor de MAIN (`git show main:.../AdminV1Routes.kt` resolverReporte): sin
     * marca (la columna aun no existe), `revisado_en` del reloj de Ktor tomado DESPUES de insertar la accion.
     */
    private fun Connection.reporteAbierto(id: String) = ejecutar(
        "insert into public.reportes_contenido (id, tipo_entidad, entidad_id, motivo) values ('$id', 'publicacion_oposired', gen_random_uuid(), 'spam')",
    )

    private fun Connection.cierreHistorico(id: String, estado: String, revisor: String?, revisadoEn: String) = ejecutar(
        "update public.reportes_contenido set estado = '$estado', revisado_por = ${revisor?.let { "'$it'" } ?: "null"}, " +
            "revisado_en = $revisadoEn where id = '$id'",
    )

    private fun Connection.marca(): String? = unico("select accion_pendiente::text from public.reportes_contenido where id = '$reporte'")

    /** Accion insertada FUERA de la RPC (staff por la politica, Ktor antiguo o datos de MAIN). */
    private fun Connection.accionDirecta(moderador: String?, creadoEn: String, accion: String = "ocultar", id: String = reporte) = ejecutar(
        "insert into public.acciones_moderacion (reporte_id, moderador_id, tipo_accion, creado_en) values " +
            "('$id', ${moderador?.let { "'$it'" } ?: "null"}, '$accion', $creadoEn)",
    )

    private fun Connection.registrar(estado: String, moderador: String?, accion: String = "ocultar", id: String = reporte): Result<Boolean> = try {
        ejecutar("set role service_role")
        Result.success(
            unico(
                "select public.admin_record_report_action('$id'::uuid, '$estado', " +
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
        assertEquals("true", c.marca())
        assertTrue(c.registrar("resuelto", USER_A).getOrThrow())
        assertEquals("false", c.marca(), "la insercion limpia la marca en la misma transaccion")
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

    /**
     * Gate PG17 (MEDIUM, P16): una accion anterior al cierre (con el reporte abierto, de otro moderador o del propio
     * revisor) o una accion de otro staff no es la accion de ESTA resolucion: la resolucion se registra una vez. Antes
     * cualquier fila del reporte devolvia false y la resolucion quedaba cerrada sin accion para siempre.
     */
    @Test
    fun unaAccionAnteriorAlCierreODeOtroStaffNoBloqueaLaResolucion(): Unit = escenario { c ->
        c.reporteCerradoEn("resuelto", USER_A, "now() - interval '10 minutes'")
        c.accionDirecta(USER_B, "now() - interval '1 hour'")
        c.accionDirecta(USER_A, "now() - interval '30 minutes'")
        c.accionDirecta(USER_B, "now() - interval '5 minutes'", accion = "restaurar")
        assertTrue(c.registrar("resuelto", USER_A, accion = "eliminar").getOrThrow(), "la resolucion se registra")
        assertEquals(4L, c.acciones())
        assertFalse(c.registrar("resuelto", USER_A, accion = "eliminar").getOrThrow(), "y solo una vez")
        assertFalse(c.registrar("resuelto", USER_A, accion = "advertir").getOrThrow(), "ni con otro tipo")
        assertEquals(4L, c.acciones())
        assertEquals(
            1L,
            c.unico("select count(*) from public.acciones_moderacion where reporte_id = '$reporte' and tipo_accion = 'eliminar'")!!.toLong(),
        )
    }

    /**
     * Round 3 (gate 102000 MEDIUM, REGRESION del round 2). Cambio DELIBERADO: sustituye a
     * `laAccionDelRevisorTrasElCierreCompletaLaResolucion`, que modelaba el Ktor antiguo con la accion DESPUES del cierre;
     * el Ktor de MAIN (`git show main:.../AdminV1Routes.kt` resolverReporte) insertaba la accion PRIMERO (creado_en =
     * now() de la BD) y despues hacia el PATCH con `revisado_en` = reloj de Ktor, con `moderador_id` = `revisado_por` =
     * el actor JWT (NULL con token estatico), y sin guarda de estado (re-resolver anadia otra accion). Esos datos
     * existen ANTES de la migracion: se insertan sin la columna y la migracion se aplica encima. Ningun cierre historico
     * es reanudable ni recibe una segunda accion, sea cual sea el orden, el actor o si tiene accion.
     */
    @Test
    fun ningunCierreHistoricoDelKtorAntiguoEsReanudableNiRecibeOtraAccion(): Unit = escenario(conMigracion = false) { c ->
        data class Caso(val id: String, val estado: String, val revisor: String?, val accionEn: String?)
        val casos = listOf(
            // Orden real del Ktor antiguo: accion unos milisegundos/segundos ANTES de revisado_en, actor JWT.
            Caso("7e7e7e7e-0000-4000-8000-0000000000a1", "resuelto", USER_A, "now() - interval '10 minutes' - interval '2 seconds'"),
            // Reloj de Ktor atrasado: la accion queda DESPUES de revisado_en.
            Caso("7e7e7e7e-0000-4000-8000-0000000000a2", "resuelto", USER_A, "now() - interval '9 minutes'"),
            // Descartado por token estatico: actor NULL en accion y en revisado_por.
            Caso("7e7e7e7e-0000-4000-8000-0000000000a3", "descartado", null, "now() - interval '10 minutes' - interval '1 second'"),
            // Cerrado sin ninguna accion (p. ej. el moderador borrado, la fila fuera de la RPC o un cierre a mano).
            Caso("7e7e7e7e-0000-4000-8000-0000000000a4", "resuelto", USER_A, null),
            Caso("7e7e7e7e-0000-4000-8000-0000000000a5", "descartado", null, null),
        )
        for (k in casos) {
            c.reporteAbierto(k.id)
            k.accionEn?.let { c.accionDirecta(k.revisor, it, accion = if (k.estado == "descartado") "descartar" else "ocultar", id = k.id) }
            c.cierreHistorico(k.id, k.estado, k.revisor, "now() - interval '10 minutes'")
        }
        val antes = c.unico("select count(*) from public.acciones_moderacion")!!.toLong()
        c.aplicar(migracion)
        for (k in casos) {
            assertEquals("false", c.unico("select accion_pendiente::text from public.reportes_contenido where id = '${k.id}'"), k.toString())
            for (accion in listOf("ocultar", "eliminar")) {
                val r = c.registrar(k.estado, k.revisor, accion = if (k.estado == "descartado") "descartar" else accion, id = k.id)
                assertFalse(r.getOrThrow(), "cierre historico nunca reanudable: $k")
            }
        }
        assertEquals(antes, c.unico("select count(*) from public.acciones_moderacion")!!.toLong(), "ninguna segunda accion")
    }

    /**
     * Round 3 (gate 102000 LOW): la accion se sella con el `now()` de la BD aunque el reloj de Ktor vaya ADELANTADO
     * (`revisado_en` en el futuro): nunca un `creado_en` futuro. La unicidad la da la marca, no las marcas de tiempo.
     * Cambio DELIBERADO: sustituye a `conElRelojDeKtorAdelantadoLaAccionCumpleLaClaveYNoSeDuplica`, que exigia el sello
     * futuro `greatest(now(), revisado_en)`.
     */
    @Test
    fun conElRelojDeKtorAdelantadoLaAccionNoSeSellaEnElFuturoYNoSeDuplica(): Unit = escenario { c ->
        c.reporteCerradoEn("resuelto", USER_A, "now() + interval '1 hour'")
        assertTrue(c.registrar("resuelto", USER_A).getOrThrow())
        assertEquals(
            "true",
            c.unico("select (creado_en <= clock_timestamp())::text from public.acciones_moderacion where reporte_id = '$reporte'"),
        )
        assertFalse(c.registrar("resuelto", USER_A).getOrThrow())
        assertEquals(1L, c.acciones())
    }

    /**
     * Gate PG17 (INFO): semantica explicita de `p_moderador_id` NULL. Solo casa con un reporte cerrado SIN actor
     * (`revisado_por` NULL, la resolucion legada por token estatico), una sola vez y con la misma clave; nunca con un
     * reporte cerrado por un moderador real, y un moderador real nunca completa un cierre sin actor.
     */
    @Test
    fun unModeradorNuloSoloRegistraUnCierreSinActorYUnaSolaVez(): Unit = escenario { c ->
        c.reporteCerradoEn("descartado", null, "now() - interval '10 minutes'")
        c.accionDirecta(null, "now() - interval '1 hour'") // de un moderador ya borrado (ON DELETE SET NULL), anterior
        assertFalse(c.registrar("descartado", USER_A, accion = "descartar").getOrThrow(), "un actor real no casa con un cierre sin actor")
        assertTrue(c.registrar("descartado", null, accion = "descartar").getOrThrow(), "la accion anterior no es la resolucion")
        assertFalse(c.registrar("descartado", null, accion = "descartar").getOrThrow(), "una sola vez")
        assertEquals(2L, c.acciones())
        assertNull(c.unico("select moderador_id::text from public.acciones_moderacion where reporte_id = '$reporte' and tipo_accion = 'descartar'"))
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
        // Round 3: + report_action_marker_column_missing (la marca en vuelo).
        assertEquals(4, v.size, v.toString())
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
        assertEquals(1L, verify()["report_action_marker_column_missing"])
        assertEquals("0", c.unico("select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'reportes_contenido' and column_name = 'accion_pendiente'"))
        assertEquals(1L, c.unico("select count(*) from public.reportes_contenido where id = '$reporte'")!!.toLong(), "el rollback no borra filas")
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

    /** Una `accion_pendiente` previa que no sea boolean NOT NULL no se reutiliza: la migracion no crea nada. */
    @Test
    fun preflightRechazaUnaMarcaPreexistenteDeOtroTipo(): Unit = escenario(conMigracion = false) { c ->
        c.ejecutar("alter table public.reportes_contenido add column accion_pendiente text")
        val error = runCatching { c.aplicar(migracion) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        c.autoCommit = true
        assertEquals("REPORT_ACTION_PREFLIGHT", error?.serverErrorMessage?.message)
        assertNull(c.unico("select to_regprocedure('$fn')::text"))
        c.ejecutar("alter table public.reportes_contenido drop column accion_pendiente")
        c.ejecutar("alter table public.reportes_contenido add column accion_pendiente boolean")
        val nullable = runCatching { c.aplicar(migracion) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        assertEquals("REPORT_ACTION_PREFLIGHT", nullable?.serverErrorMessage?.message)
    }

    /** El preflight sigue exigiendo `revisado_en` y `creado_en` (Ktor y la tabla los escriben): con otro tipo no crea nada. */
    @Test
    fun preflightExigeLasMarcasDeTiempoDeLaClave(): Unit = escenario(conMigracion = false) { c ->
        c.ejecutar("alter table public.reportes_contenido alter column revisado_en type text")
        val error = runCatching { c.aplicar(migracion) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        c.autoCommit = true
        assertEquals("REPORT_ACTION_PREFLIGHT", error?.serverErrorMessage?.message)
        assertNull(c.unico("select to_regprocedure('$fn')::text"))
        c.ejecutar("alter table public.reportes_contenido alter column revisado_en type timestamptz using revisado_en::timestamptz")
        c.ejecutar("alter table public.acciones_moderacion rename column creado_en to creado")
        val sinCreado = runCatching { c.aplicar(migracion) }.exceptionOrNull() as PSQLException?
        runCatching { c.ejecutar("rollback") }
        assertEquals("REPORT_ACTION_PREFLIGHT", sinCreado?.serverErrorMessage?.message)
    }
}
