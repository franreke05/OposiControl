package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit loop 2, area DB: migracion 20260930100900 (MASTER2-IDEM-002). Dos PRIMERAS llamadas concurrentes e
 * identicas a `record_event` con la misma (usuario, clave): sin cerrojo, las dos fallan el lookup, la perdedora
 * choca con el indice unico por usuario y recibe 23505 (409 UNIQUE_VIOLATION en Ktor). Con el cerrojo consultivo
 * de transaccion la segunda espera, ve la fila confirmada y devuelve el MISMO event_id (o
 * EVENT_IDEMPOTENCY_CONFLICT si la carga difiere). Concurrencia de verdad: dos conexiones PostgreSQL.
 */
class Slice1EventRetryConcurrencyMigrationTest {
    private val m100800 = "supabase/migrations/20260930100800_slice1_event_idempotency_per_user.sql"
    private val m100900 = "supabase/migrations/20260930100900_slice1_record_event_concurrent_retry.sql"
    private val candidate009 = "supabase/canonical/product_metrics_candidate_v1/009_record_event_idempotency_rpc.sql"
    private val firma = "public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)"
    private val u1 = "b1b1b1b1-0000-4000-8000-000000000001"
    private val u2 = "b2b2b2b2-0000-4000-8000-000000000002"

    /** Forma de MAIN (baseline) de eventos_app: igual que en Slice1EventIdempotencyMigrationTest. */
    private val eventosMain = """
        create table public.eventos_app (
          id uuid not null default gen_random_uuid() primary key,
          usuario_id uuid references auth.users(id) on delete set null,
          sesion_id uuid, nombre_evento text not null, pantalla text, entidad_tipo text, entidad_id uuid,
          plataforma text, version_app text, idioma text, duracion_ms integer,
          propiedades jsonb not null default '{}'::jsonb, creado_en timestamptz not null default now(),
          event_version integer not null default 1, occurred_en timestamptz not null default now(),
          received_en timestamptz not null default now(), especialidad_id uuid, intento_id uuid, pregunta_id uuid,
          idempotency_key text not null constraint eventos_app_idempotency_key_non_blank check (length(btrim(idempotency_key)) > 0)
        );
        create unique index ux_eventos_app_idempotency_key on public.eventos_app using btree (idempotency_key);
        insert into auth.users (id, email) values ('$u1', 'b1@test.local'), ('$u2', 'b2@test.local');
    """.trimIndent()

    private fun <T> escenario(conFix: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.ejecutar(eventosMain)
        c.aplicar(candidate009)
        c.aplicar(m100800)
        if (conFix) c.aplicar(m100900)
        bloque(c)
    }

    /** Resultado de una llamada: event_id o SQLSTATE + mensaje. */
    private data class R(val eventId: String?, val sqlState: String? = null, val mensaje: String? = null)

    private fun Connection.evento(usuario: String, clave: String, nombre: String = "pantalla_vista"): R = try {
        R(unico("select event_id from public.record_event(p_usuario_id => '$usuario', p_nombre_evento => '$nombre', p_idempotency_key => '$clave')"))
    } catch (e: SQLException) {
        R(null, e.sqlState, e.message)
    }

    private fun Connection.verify(ruta: String): Map<String, Long> =
        filas(Slice0Db.verifyDe(ruta)).associate { it[0]!! to it[1]!!.toLong() }

    /**
     * T1 (c1) registra el evento y NO confirma. T2 (c2) lanza [segunda] en otro hilo. Devuelve el resultado de T1,
     * si T2 quedo bloqueada mientras T1 seguia abierta, y el resultado final de T2 tras el commit de T1.
     */
    private fun carrera(c1: Connection, primera: (Connection) -> R, segunda: (Connection) -> R): Triple<R, Boolean, R> {
        val c2 = PostgresHarness.conexionExtra(c1)
        try {
            c1.autoCommit = false
            c2.autoCommit = false
            val r1 = primera(c1)
            assertNull(r1.sqlState, "T1 tenia que registrar el evento: $r1")
            val r2 = AtomicReference<R?>(null)
            val terminado = CountDownLatch(1)
            val t2 = Thread {
                r2.set(segunda(c2))
                terminado.countDown()
            }
            t2.isDaemon = true
            t2.start()
            val bloqueada = !terminado.await(2, TimeUnit.SECONDS)
            c1.commit()
            assertTrue(terminado.await(30, TimeUnit.SECONDS), "T2 no termino tras el commit de T1")
            val res = assertNotNull(r2.get())
            if (res.sqlState == null) c2.commit() else c2.rollback()
            c1.autoCommit = true
            return Triple(r1, bloqueada, res)
        } finally {
            runCatching { c2.close() }
        }
    }

    @Test
    fun sinElFixElReintentoConcurrenteIdenticoFallaCon23505(): Unit = escenario(conFix = false) { c ->
        // Reproduce el defecto (evidencia del hallazgo): el perdedor choca con el indice unico.
        val (_, _, r2) = carrera(c, { it.evento(u1, "k-race") }, { it.evento(u1, "k-race") })
        assertEquals("23505", r2.sqlState, "sin cerrojo el perdedor recibe unique_violation: $r2")
    }

    @Test
    fun conElFixElReintentoConcurrenteIdenticoEsperaYDevuelveElMismoEvento(): Unit = escenario { c ->
        val (r1, bloqueada, r2) = carrera(c, { it.evento(u1, "k-race") }, { it.evento(u1, " k-race ") })
        assertTrue(bloqueada, "T2 tenia que esperar el cerrojo de (usuario, clave)")
        assertNull(r2.sqlState, "el reintento identico concurrente no puede fallar: $r2")
        assertEquals(r1.eventId, r2.eventId, "mismo event_id que la llamada original")
        assertEquals("1", c.unico("select count(*) from public.eventos_app where idempotency_key = 'k-race'"))
    }

    @Test
    fun conElFixUnaCargaDistintaConcurrenteEsConflictoNoUniqueViolation(): Unit = escenario { c ->
        val (_, bloqueada, r2) = carrera(c, { it.evento(u1, "k-race") }, { it.evento(u1, "k-race", "otro_evento") })
        assertTrue(bloqueada)
        assertEquals("P0001", r2.sqlState, r2.toString())
        assertTrue(r2.mensaje.orEmpty().contains("EVENT_IDEMPOTENCY_CONFLICT"), r2.toString())
        assertEquals("1", c.unico("select count(*) from public.eventos_app"))
    }

    @Test
    fun conElFixOtroUsuarioConLaMismaClaveNoEspera(): Unit = escenario { c ->
        val (r1, bloqueada, r2) = carrera(c, { it.evento(u1, "k-race") }, { it.evento(u2, "k-race") })
        assertFalse(bloqueada, "el cerrojo es por (usuario, clave): otro usuario no espera")
        assertNull(r2.sqlState, r2.toString())
        assertNotEquals(r1.eventId, r2.eventId)
        assertEquals("2", c.unico("select count(*) from public.eventos_app"))
    }

    @Test
    fun conElFixElComportamientoSerieNoCambia(): Unit = escenario { c ->
        val a = c.evento(u1, "k-1")
        assertEquals(a.eventId, c.evento(u1, "k-1").eventId)
        assertTrue(c.evento(u1, "k-1", "otro").mensaje.orEmpty().contains("EVENT_IDEMPOTENCY_CONFLICT"))
        assertNotEquals(a.eventId, c.evento(u2, "k-1").eventId)
        assertTrue(c.evento(u1, "  ").mensaje.orEmpty().contains("EVENT_IDEMPOTENCY_KEY_REQUIRED"))
    }

    @Test
    fun verifyDeAmbasMigracionesEnVerdeYAclSoloServiceRole(): Unit = escenario { c ->
        val v8 = c.verify(m100800)
        assertEquals(5, v8.size, v8.toString())
        assertTrue(v8.values.all { it == 0L }, "VERIFY 100800 no esta verde tras 100900: $v8")
        val v9 = c.verify(m100900)
        assertEquals(5, v9.size, v9.toString())
        assertTrue(v9.values.all { it == 0L }, "VERIFY 100900 no esta verde: $v9")
        for (rol in listOf("anon", "authenticated", "public")) {
            assertEquals("f", c.unico("select has_function_privilege('$rol', '$firma'::regprocedure, 'execute')"), rol)
        }
        assertEquals("t", c.unico("select has_function_privilege('service_role', '$firma'::regprocedure, 'execute')"))
    }

    @Test
    fun verifyDetectaQueFaltaElCerrojoSinElFix(): Unit = escenario(conFix = false) { c ->
        assertEquals(1L, c.verify(m100900)["record_event_retry_lock_missing"])
    }

    @Test
    fun elRollbackRestauraElCuerpoDe100800YSePuedeReaplicar(): Unit = escenario(conFix = false) { c ->
        val cuerpo100800 = c.unico("select pg_get_functiondef('$firma'::regprocedure)")
        c.aplicar(m100900)
        c.ejecutar(Slice0Db.rollbackDe(m100900))
        assertEquals(cuerpo100800, c.unico("select pg_get_functiondef('$firma'::regprocedure)"), "rollback = cuerpo de 100800 byte a byte")
        assertFalse(c.unico("select pg_get_functiondef('$firma'::regprocedure)").orEmpty().contains("pg_advisory_xact_lock"))
        assertEquals(1L, c.verify(m100900)["record_event_retry_lock_missing"])
        assertTrue(c.verify(m100800).values.all { it == 0L }, "tras el rollback sigue la forma de 100800")
        c.aplicar(m100900)
        assertTrue(c.verify(m100900).values.all { it == 0L }, "re-aplicar 100900 es idempotente")
    }
}
