package com.oposicontrol.dbtest.slice0

import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.SupabaseFixture.USER_A
import com.oposicontrol.dbtest.SupabaseFixture.USER_B
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Almacen Idempotency-Key sobre PostgreSQL embebido: new, replay, mismatch,
 * in-progress, stale takeover, release-then-retry, expiry, barrido acotado,
 * N begins concurrentes, validacion, grants/RLS, re-aplicacion y rollback.
 */
class IdempotencyKeysMigrationTest {

    private data class Begin(
        val outcome: String,
        val id: String?,
        val status: String?,
        val body: String?,
        val headers: String?,
        val retryAfter: String?,
        val token: String? = null,
    ) {
        /** NEW/STALE: el par (record_id, claim_token) que exigen complete/release. */
        val claim: Pair<String, String> get() = id!! to token!!
    }

    private companion object {
        const val SCOPE = "POST /api/v1/app/examenes/{examId}/intentos"
        const val KEY = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val FP1 = "a".repeat(64)
        val FP2 = "b".repeat(64)
    }

    private fun Connection.begin(
        key: String = KEY,
        fp: String = FP1,
        actor: String = USER_A,
        scope: String = SCOPE,
    ): Begin = filas(
        "select outcome, record_id::text, stored_status::text, stored_body::text, stored_headers::text, " +
            "retry_after_seconds::text, claim_token::text from public.idem_begin('$actor'::uuid, '$scope', '$key', '$fp')",
    ).single().let { Begin(it[0]!!, it[1], it[2], it[3], it[4], it[5], it[6]) }

    private fun Connection.complete(
        claim: Pair<String, String>,
        status: Int = 201,
        headers: String = """{"Location":"/api/v1/app/intentos/1"}""",
    ): String? =
        unico("select public.idem_complete('${claim.first}'::uuid, '${claim.second}'::uuid, $status, '{\"ok\":true}'::jsonb, '$headers'::jsonb)::text")

    private fun Connection.release(claim: Pair<String, String>): String? =
        unico("select public.idem_release('${claim.first}'::uuid, '${claim.second}'::uuid)::text")

    /** Base con la migracion aplicada y la sesion como service_role (Ktor). */
    private fun <T> escenario(bloque: (Connection) -> T): T = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDEMPOTENCY)
        c.ejecutar("set role service_role")
        bloque(c)
    }

    @Test
    fun newGuardaFilaEnCursoConLeaseYTtl(): Unit = escenario { c ->
        val r = c.begin(key = KEY.uppercase())
        assertEquals("NEW", r.outcome)
        assertNotNull(r.id)
        assertNotNull(r.token, "NEW devuelve el token de fencing")
        assertEquals(
            listOf(KEY, "in_progress", "true", "true"),
            c.filas(
                "select idem_key, state, (locked_until > now())::text, (expires_at = created_at + interval '24 hours')::text " +
                    "from public.idempotency_keys",
            ).single(),
        )
        // La clave se normaliza a minusculas: la variante en minusculas es la misma.
        assertEquals("IN_PROGRESS", c.begin().outcome)
    }

    @Test
    fun replayDevuelveLaRespuestaGuardadaSinReejecutar(): Unit = escenario { c ->
        val claim = c.begin().claim
        val id = claim.first
        assertEquals("true", c.complete(claim))
        val r = c.begin()
        assertEquals("REPLAY", r.outcome)
        assertEquals(id, r.id)
        assertEquals("201", r.status)
        assertEquals("""{"ok": true}""", r.body)
        assertEquals("""{"Location": "/api/v1/app/intentos/1"}""", r.headers)
        assertEquals("false", c.complete(claim), "completar dos veces no reescribe")
        assertEquals("false", c.release(claim), "release nunca borra una fila completada")
        assertEquals("1", c.unico("select count(*) from public.idempotency_keys"))
    }

    @Test
    fun mismatchConOtroFingerprintEnCualquierEstado(): Unit = escenario { c ->
        val claim = c.begin().claim
        assertEquals(Begin("MISMATCH", null, null, null, null, null, null), c.begin(fp = FP2))
        c.complete(claim)
        assertEquals("MISMATCH", c.begin(fp = FP2).outcome)
        assertEquals(FP1, c.unico("select request_fingerprint from public.idempotency_keys"))
    }

    @Test
    fun inProgressConLeaseVivo(): Unit = escenario { c ->
        c.begin()
        assertEquals(Begin("IN_PROGRESS", null, null, null, null, "1", null), c.begin())
    }

    @Test
    fun staleTakeoverRenuevaElLease(): Unit = escenario { c ->
        val first = c.begin()
        c.ejecutar("update public.idempotency_keys set locked_until = now() - interval '1 second'")
        val r = c.begin()
        assertEquals("STALE", r.outcome)
        assertEquals(first.id, r.id)
        assertNotEquals(first.token, r.token, "el takeover rota el token de fencing")
        assertEquals("true", c.unico("select (locked_until > now())::text from public.idempotency_keys"))
        assertEquals("IN_PROGRESS", c.begin().outcome, "tras el takeover el lease vuelve a proteger")
        assertEquals("true", c.complete(r.claim))
        assertEquals("REPLAY", c.begin().outcome)
    }

    /** RB-1-01: el dueno lento cuyo lease fue tomado (STALE) ya no puede completar ni liberar. */
    @Test
    fun staleTakeoverFencesTheSlowOriginalOwner(): Unit = escenario { c ->
        val a = c.begin()
        c.ejecutar("update public.idempotency_keys set locked_until = now() - interval '1 second'")
        val b = c.begin()
        assertEquals("STALE", b.outcome)
        assertEquals("false", c.release(a.claim), "A.release tardio no borra la fila de B")
        assertEquals("false", c.complete(a.claim), "A.complete tardio no escribe bajo el claim de B")
        assertEquals(
            listOf("in_progress", "true"),
            c.filas("select state, (response_status is null)::text from public.idempotency_keys").single(),
        )
        assertEquals("IN_PROGRESS", c.begin().outcome, "un tercer begin no obtiene NEW mientras B ejecuta")
        assertEquals("true", c.complete(b.claim))
        assertEquals("REPLAY", c.begin().outcome)
    }

    @Test
    fun releaseYReintentoConLaMismaClave(): Unit = escenario { c ->
        val claim = c.begin().claim
        assertEquals("true", c.release(claim))
        assertEquals("0", c.unico("select count(*) from public.idempotency_keys"))
        val r = c.begin(fp = FP2) // tras un fallo, el reintento puede incluso cambiar el cuerpo
        assertEquals("NEW", r.outcome)
        assertNotEquals(claim.first, r.id)
    }

    @Test
    fun expiracionYBarridoAcotado(): Unit = escenario { c ->
        val claim = c.begin().claim
        val id = claim.first
        c.complete(claim)
        c.ejecutar(
            """
            update public.idempotency_keys set expires_at = now() - interval '1 second';
            insert into public.idempotency_keys (actor_id, scope, idem_key, request_fingerprint, state, locked_until, created_at, expires_at)
            select '$USER_B', 'POST /x', gen_random_uuid()::text, '${FP1}', 'in_progress', now() - interval '2 days',
                   now() - interval '2 days', now() - interval '1 day' - (g || ' seconds')::interval
            from generate_series(1, 150) g;
            """.trimIndent(),
        )
        val r = c.begin()
        assertEquals("NEW", r.outcome, "una fila expirada con la misma clave no se reproduce")
        assertNotEquals(id, r.id)
        assertEquals("50", c.unico("select count(*) from public.idempotency_keys where expires_at <= now()"), "barre como mucho 100")
        c.begin(key = "11111111-1111-4111-8111-111111111111")
        assertEquals("0", c.unico("select count(*) from public.idempotency_keys where expires_at <= now()"))
    }

    @Test
    fun unicidadPorActorScopeYClave(): Unit = escenario { c ->
        assertEquals("NEW", c.begin().outcome)
        assertEquals("NEW", c.begin(actor = USER_B).outcome)
        assertEquals("NEW", c.begin(scope = "POST /api/v1/app/reto-diario/{retoId}/intentos").outcome)
        assertEquals("3", c.unico("select count(*) from public.idempotency_keys"))
        c.ejecutar("reset role")
        assertEquals(
            "23505",
            c.sqlStateDe(
                "insert into public.idempotency_keys (actor_id, scope, idem_key, request_fingerprint, state, expires_at) " +
                    "values ('$USER_A', '$SCOPE', '$KEY', '$FP1', 'in_progress', now() + interval '1 day')",
            ),
        )
        assertEquals(
            "23514",
            c.sqlStateDe(
                "insert into public.idempotency_keys (actor_id, scope, idem_key, request_fingerprint, state, expires_at) " +
                    "values ('$USER_A', 'S', '$KEY', '$FP1', 'completed', now() + interval '1 day')",
            ),
            "completed exige status y completed_at",
        )
    }

    @Test
    fun argumentosInvalidosSonRechazados(): Unit = escenario { c ->
        fun st(sql: String) = c.sqlStateDe(sql)
        assertEquals("22023", st("select * from public.idem_begin('$USER_A', '$SCOPE', 'no-es-uuid', '$FP1')"))
        assertEquals("22023", st("select * from public.idem_begin('$USER_A', '$SCOPE', '$KEY', 'xyz')"))
        assertEquals("22023", st("select * from public.idem_begin('$USER_A', '', '$KEY', '$FP1')"))
        assertEquals("22023", st("select * from public.idem_begin(null, '$SCOPE', '$KEY', '$FP1')"))
        assertEquals("22023", st("select * from public.idem_begin('$USER_A', '$SCOPE', '$KEY', '$FP1', interval '8 days')"))
        val (id, token) = c.begin().claim
        assertEquals("22023", st("select public.idem_complete('$id', '$token', 500, '{}'::jsonb, '{}'::jsonb)"))
        assertEquals("22023", st("select public.idem_complete('$id', '$token', 200, '{}'::jsonb, '{\"Set-Cookie\":\"x\"}'::jsonb)"))
        assertEquals("in_progress", c.unico("select state from public.idempotency_keys"))
        assertEquals("true", c.unico("select public.idem_complete('$id', '$token', 204, null, null)::text"))
    }

    @Test
    fun nBeginsConcurrentesConvergenEnUnSoloNew() {
        val n = 8
        Slice0Db.escenario { c ->
            c.aplicar(Slice0Db.IDEMPOTENCY)
            val conexiones = (1..n).map { PostgresHarness.conexionExtra(c).also { it.ejecutar("set role service_role") } }
            val barrera = CyclicBarrier(n)
            val resultados = Collections.synchronizedList(mutableListOf<String>())
            val pool = Executors.newFixedThreadPool(n)
            try {
                conexiones.forEach { cx ->
                    pool.submit {
                        barrera.await(10, TimeUnit.SECONDS)
                        resultados += cx.begin().outcome
                    }
                }
                pool.shutdown()
                assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
                conexiones.forEach { runCatching { it.close() } }
            }
            assertEquals(n, resultados.size, "resultados: $resultados")
            assertEquals(1, resultados.count { it == "NEW" }, "resultados: $resultados")
            assertEquals(n - 1, resultados.count { it == "IN_PROGRESS" }, "resultados: $resultados")
            assertEquals("1", c.unico("select count(*) from public.idempotency_keys"))
        }
    }

    @Test
    fun soloServiceRoleConRlsSinPoliticas(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDEMPOTENCY)
        val funciones = listOf(
            "public.idem_begin(uuid,text,text,text,interval,interval)",
            "public.idem_complete(uuid,uuid,integer,jsonb,jsonb)",
            "public.idem_release(uuid,uuid)",
        )
        for (rol in listOf("anon", "authenticated")) {
            for (p in listOf("select", "insert", "update", "delete")) {
                assertEquals("false", c.unico("select has_table_privilege('$rol', 'public.idempotency_keys', '$p')::text"), "$rol $p")
            }
            funciones.forEach { f ->
                assertEquals("false", c.unico("select has_function_privilege('$rol', '$f', 'execute')::text"), "$rol $f")
            }
            assertEquals("42501", c.sqlStateDe("begin; set local role $rol; select count(*) from public.idempotency_keys; commit;"))
            runCatching { c.ejecutar("rollback") }
            assertEquals("42501", c.sqlStateDe("begin; set local role $rol; select * from public.idem_begin('$USER_A', 'S', '$KEY', '$FP1'); commit;"))
            runCatching { c.ejecutar("rollback") }
        }
        funciones.forEach { f ->
            assertEquals("true", c.unico("select has_function_privilege('service_role', '$f', 'execute')::text"), f)
            assertEquals("true", c.unico("select prosecdef::text from pg_proc where oid = '$f'::regprocedure"), f)
            assertEquals("search_path=\"\"", c.unico("select proconfig[1] from pg_proc where oid = '$f'::regprocedure"), f)
        }
        assertEquals("true", c.unico("select relrowsecurity::text from pg_class where oid = 'public.idempotency_keys'::regclass"))
        assertEquals("0", c.unico("select count(*) from pg_policies where tablename = 'idempotency_keys'"))
        assertEquals("1", c.unico("select count(*) from pg_indexes where indexname = 'idx_idempotency_keys_expires_at'"))
    }

    @Test
    fun reAplicarYRollback(): Unit = Slice0Db.escenario { c ->
        c.aplicar(Slice0Db.IDEMPOTENCY)
        c.ejecutar("set role service_role")
        c.complete(c.begin().claim)
        c.ejecutar("reset role")
        c.aplicar(Slice0Db.IDEMPOTENCY)
        c.ejecutar("set role service_role")
        assertEquals("REPLAY", c.begin().outcome, "re-aplicar la migracion conserva las filas")
        c.ejecutar("reset role")

        c.ejecutar(Slice0Db.rollbackDe(Slice0Db.IDEMPOTENCY))
        assertNull(c.unico("select to_regclass('public.idempotency_keys')::text"))
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('idem_begin','idem_complete','idem_release')"))
        c.ejecutar(Slice0Db.rollbackDe(Slice0Db.IDEMPOTENCY)) // idempotente

        c.aplicar(Slice0Db.IDEMPOTENCY)
        c.ejecutar("set role service_role")
        assertEquals("NEW", c.begin().outcome)
        assertFalse(c.begin().outcome == "NEW")
    }
}
