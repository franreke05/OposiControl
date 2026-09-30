package com.oposicontrol.dbtest.slice1

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Audit loop 1, area KTOR_SEC: migracion 20260930100800 (MASTER-SEC-007). La clave de idempotencia de
 * `record_event` es POR USUARIO: otro usuario que reutiliza una clave ya no recibe EVENT_IDEMPOTENCY_CONFLICT
 * (oraculo de existencia) ni bloquea el evento legitimo. Base de partida: forma de MAIN de `eventos_app` y el
 * `record_event` del candidate 009 (identico al de MAIN).
 */
class Slice1EventIdempotencyMigrationTest {
    private val migracion = "supabase/migrations/20260930100800_slice1_event_idempotency_per_user.sql"
    private val candidate009 = "supabase/canonical/product_metrics_candidate_v1/009_record_event_idempotency_rpc.sql"
    private val u1 = "a1a1a1a1-0000-4000-8000-000000000001"
    private val u2 = "a2a2a2a2-0000-4000-8000-000000000002"

    /** Forma de MAIN (baseline): tabla, FK SET NULL a auth.users e indice UNICO GLOBAL de la clave. */
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
        insert into auth.users (id, email) values ('$u1', 'u1@test.local'), ('$u2', 'u2@test.local');
    """.trimIndent()

    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T = PostgresHarness.escenario { c ->
        c.ejecutar(eventosMain)
        c.aplicar(candidate009)
        if (conMigracion) c.aplicar(migracion)
        bloque(c)
    }

    private fun Connection.evento(usuario: String, clave: String, nombre: String = "pantalla_vista"): String =
        unico("select event_id from public.record_event(p_usuario_id => '$usuario', p_nombre_evento => '$nombre', p_idempotency_key => '$clave')")!!

    private fun Connection.errorDe(usuario: String, clave: String, nombre: String): String? = try {
        evento(usuario, clave, nombre)
        null
    } catch (e: SQLException) {
        e.message
    }

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    @Test
    fun antesDeLaMigracionLaClaveEraGlobalYOtroUsuarioRecibiaConflicto(): Unit = escenario(conMigracion = false) { c ->
        c.evento(u1, "k-1")
        assertTrue(c.errorDe(u2, "k-1", "pantalla_vista").orEmpty().contains("EVENT_IDEMPOTENCY_CONFLICT"))
    }

    @Test
    fun laClaveEsPorUsuarioYElReintentoIdenticoSigueDevolviendoElMismoEvento(): Unit = escenario { c ->
        val primero = c.evento(u1, "k-1")
        assertEquals(primero, c.evento(u1, " k-1 "), "reintento identico (con espacios) del mismo usuario")
        val otro = c.evento(u2, "k-1")
        assertNotEquals(primero, otro, "otro usuario con la misma clave es otro evento, sin conflicto")
        assertTrue(c.errorDe(u1, "k-1", "otro_evento").orEmpty().contains("EVENT_IDEMPOTENCY_CONFLICT"), "mismo usuario, carga distinta")
        assertEquals("2", c.unico("select count(*) from public.eventos_app"))
    }

    @Test
    fun borrarUsuariosQueCompartianClaveNoChocaConElIndice(): Unit = escenario { c ->
        c.evento(u1, "k-1")
        c.evento(u2, "k-1")
        c.ejecutar("delete from auth.users where id in ('$u1', '$u2')")
        assertEquals("2", c.unico("select count(*) from public.eventos_app where usuario_id is null"))
    }

    @Test
    fun elIndicePorUsuarioImpideDuplicadosDelMismoUsuario(): Unit = escenario { c ->
        c.evento(u1, "k-1")
        assertEquals("23505", c.sqlStateDe("insert into public.eventos_app (usuario_id, nombre_evento, idempotency_key) values ('$u1', 'x', 'k-1')"))
    }

    @Test
    fun verifyEnVerdeYSoloServiceRoleEjecuta(): Unit = escenario { c ->
        val v = c.verify()
        assertEquals(5, v.size, v.toString())
        assertTrue(v.values.all { it == 0L }, "VERIFY no esta verde: $v")
        assertEquals("f", c.unico("select has_function_privilege('anon', 'public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)'::regprocedure, 'execute')"))
        assertEquals("t", c.unico("select has_function_privilege('service_role', 'public.record_event(uuid, text, text, text, uuid, jsonb, integer, timestamptz, uuid, uuid, uuid, text)'::regprocedure, 'execute')"))
    }

    @Test
    fun elRollbackRestauraLaFormaDeMainYSeDetieneSiHayClavesCompartidas(): Unit = escenario { c ->
        c.evento(u1, "k-1")
        c.evento(u2, "k-1")
        try {
            c.ejecutar(Slice0Db.rollbackDe(migracion))
            fail("el rollback no puede recrear el indice global con claves compartidas")
        } catch (e: SQLException) {
            assertEquals("23505", e.sqlState)
            c.ejecutar("rollback")
        }
        c.ejecutar("delete from public.eventos_app where usuario_id = '$u2'")
        c.ejecutar(Slice0Db.rollbackDe(migracion))
        assertEquals(
            listOf(listOf("ux_eventos_app_idempotency_key")),
            c.filas("select indexname from pg_indexes where tablename = 'eventos_app' and indexname like 'ux_%'"),
        )
        assertTrue(c.errorDe(u2, "k-1", "pantalla_vista").orEmpty().contains("EVENT_IDEMPOTENCY_CONFLICT"), "vuelve la clave global")
    }
}
