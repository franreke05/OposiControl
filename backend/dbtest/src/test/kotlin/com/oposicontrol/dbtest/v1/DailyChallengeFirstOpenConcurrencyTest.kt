package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.CandidateFiles
import com.oposicontrol.dbtest.PostgresHarness
import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.unico
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CERT-LOAD-1000-2026-10-21 (P0) + migration 20261022103500: the first open of the day (`private.user_challenge_reto`, behind
 * `app_get_daily_challenge`) used to take ONE global advisory lock (`reto-job`) for every user, so a spike of first opens serialized.
 * Now it takes none: an existing row is a lock-free read and a missing row is inserted with ON CONFLICT DO NOTHING on
 * `ux_retos_diarios_segmento`. REAL concurrency (independent connections) proves:
 *  - concurrent first opens of one segment all get the SAME row and the SAME three questions (one row per day and segment);
 *  - a first open waits only for the row being created (a `transactionid` lock), never for an advisory lock;
 *  - neither the fast path nor the creation of a missing segment waits while the job holds `reto-job`; the 101200 body does (negative control);
 *  - many first opens with crossed community orders finish without a deadlock.
 *
 * The functions, the unique indexes and the config reader are read from the REAL migration files (never copied here); only the tables
 * they touch are stubbed with the columns the functions use.
 */
class DailyChallengeFirstOpenConcurrencyTest {

    private val v1200 = mig("supabase/migrations/20261022101200_v1_daily_challenge.sql")
    private val v3500 = mig("supabase/migrations/20261022103500_v1_daily_challenge_dst.sql")
    private val v0900 = mig("supabase/migrations/20261022100900_v1_plan_access_v2.sql")

    private val esp = "e1000000-0000-4000-8000-000000000001"
    private val esp2 = "e1000000-0000-4000-8000-000000000002"
    private val comA = "c1000000-0000-4000-8000-0000000000a1"
    private val comB = "c1000000-0000-4000-8000-0000000000b1"

    private fun mig(path: String): String = File(CandidateFiles.raiz, path).readText().replace("\r\n", "\n")

    /** `create or replace function <name>(` ... `$$;` of a migration, never from a commented ROLLBACK block. */
    private fun fn(sql: String, name: String): String {
        val start = sql.indexOf("\ncreate or replace function $name(")
        check(start >= 0) { "function $name not found" }
        val end = sql.indexOf("\n$$;", start)
        check(end > start) { "end of $name not found" }
        return sql.substring(start + 1, end + 4)
    }

    private fun stmt(sql: String, prefix: String): String {
        val start = sql.indexOf("\n$prefix")
        check(start >= 0) { "$prefix not found" }
        return sql.substring(start + 1, sql.indexOf(";", start) + 1)
    }

    private fun Connection.instalar(firstOpenFrom: String = v3500) {
        ejecutar(
            """
            create schema if not exists private;
            create table public.configuracion_v1 (clave text primary key, valor_integer integer, valor_text text);
            insert into public.configuracion_v1 (clave, valor_integer, valor_text) values ('zona_horaria', null, 'Europe/Madrid'), ('reto_hora_desbloqueo', 0, null);
            create table public.especialidades (id uuid primary key, activa boolean not null default true);
            create table public.perfiles_usuario (usuario_id uuid primary key, especialidad_principal_id uuid, comunidad_principal_id uuid);
            create table public.comunidades_objetivo_usuario (usuario_id uuid not null, comunidad_id uuid not null, prioridad integer not null default 1);
            create table public.examenes (id uuid primary key, activo boolean not null, publicado_en timestamptz, especialidad_id uuid);
            create table public.preguntas (id uuid primary key, activa boolean not null, tipo_pregunta text not null);
            create table public.preguntas_examen (examen_id uuid not null, pregunta_id uuid not null, anulada boolean, es_demo boolean not null default false);
            create table public.etiquetas_pregunta (pregunta_id uuid not null, etiqueta_id uuid not null);
            create table public.etiquetas_comunidad (etiqueta_id uuid not null, comunidad_id uuid not null);
            create table public.retos_diarios (id uuid primary key default gen_random_uuid(), fecha date not null, titulo text not null, descripcion text,
              objetivo_preguntas integer not null, aciertos_necesarios integer not null, recompensa_puntos integer not null, activo boolean not null,
              empieza_en timestamptz, termina_en timestamptz, especialidad_id uuid, comunidad_id uuid, segmento_usuario text);
            create table public.preguntas_reto_diario (reto_id uuid not null references public.retos_diarios(id), pregunta_id uuid not null, posicion integer not null,
              tipo_slot text not null, primary key (reto_id, pregunta_id), unique (reto_id, posicion));
            create table public.registros_reto_diario (usuario_id uuid not null, reto_id uuid not null, dia date);
            """.trimIndent(),
        )
        ejecutar(stmt(v1200, "create unique index if not exists ux_retos_diarios_segmento"))
        ejecutar(stmt(v1200, "create unique index if not exists ux_registros_reto_usuario_dia"))
        ejecutar(fn(v0900, "private.config_int"))
        listOf("private.reto_tz", "private.reto_today", "private.reto_pool").forEach { ejecutar(fn(v1200, it)) }
        ejecutar(fn(v3500, "private.reto_day_end"))
        ejecutar(fn(v3500, "private.ensure_challenge_row"))
        ejecutar(fn(firstOpenFrom, "private.user_challenge_reto"))
        // content: per specialty one demo immersive, one razonada, one rafaga (national) + one regional razonada for A and for B
        for ((e, tag) in listOf(esp to "1", esp2 to "2")) {
            val ex = "e5000000-0000-4000-8000-00000000000$tag"
            ejecutar(
                """
                insert into public.especialidades (id) values ('$e');
                insert into public.examenes values ('$ex', true, now() - interval '1 day', '$e');
                insert into public.preguntas values ('f800000$tag-0000-4000-8000-000000000001', true, 'inmersiva'), ('f800000$tag-0000-4000-8000-000000000002', true, 'razonada'),
                  ('f800000$tag-0000-4000-8000-000000000003', true, 'rafaga'), ('f800000$tag-0000-4000-8000-0000000000a1', true, 'razonada'), ('f800000$tag-0000-4000-8000-0000000000b1', true, 'razonada');
                insert into public.preguntas_examen select '$ex', p.id, false, p.tipo_pregunta = 'inmersiva' from public.preguntas p where p.id::text like 'f800000$tag%';
                insert into public.etiquetas_pregunta values ('f800000$tag-0000-4000-8000-0000000000a1', '7a000000-0000-4000-8000-0000000000a1'), ('f800000$tag-0000-4000-8000-0000000000b1', '7a000000-0000-4000-8000-0000000000b1');
                """.trimIndent(),
            )
        }
        ejecutar("insert into public.etiquetas_comunidad values ('7a000000-0000-4000-8000-0000000000a1', '$comA'), ('7a000000-0000-4000-8000-0000000000b1', '$comB')")
    }

    private fun Connection.usuario(esp: String, principal: String? = null, objetivo: String? = null): String {
        val u = UUID.randomUUID().toString()
        ejecutar("insert into public.perfiles_usuario values ('$u', '$esp', ${principal?.let { "'$it'" } ?: "null"})")
        if (objetivo != null) ejecutar("insert into public.comunidades_objetivo_usuario values ('$u', '$objetivo', 1)")
        return u
    }

    private fun Connection.primeraApertura(u: String): String = unico("select private.user_challenge_reto('$u', clock_timestamp())::text")!!
    private fun Connection.pid(): String = unico("select pg_backend_pid()::text")!!

    private fun esperaBloqueada(observador: Connection, pid: String): Pair<String?, String?> {
        repeat(200) {
            val w = observador.createStatement().use { st ->
                st.executeQuery("select wait_event_type, wait_event from pg_stat_activity where pid = $pid").use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null to null }
            }
            if (w.first == "Lock") return w
            Thread.sleep(25)
        }
        return null to null
    }

    private fun Connection.filasNacionales(e: String = esp): Int =
        unico("select count(*) from public.retos_diarios where especialidad_id = '$e' and comunidad_id is null and fecha = private.reto_today(clock_timestamp())")!!.toInt()

    @Test
    fun aSecondFirstOpenOfTheSameMissingSegmentWaitsOnlyForThatRowAndGetsTheSameChallenge(): Unit = PostgresHarness.escenario { c ->
        c.instalar()
        val uA = c.usuario(esp)
        val uB = c.usuario(esp)
        val c1 = PostgresHarness.conexionExtra(c)
        val c2 = PostgresHarness.conexionExtra(c)
        try {
            c1.autoCommit = false
            c2.autoCommit = false
            val retoA = c1.primeraApertura(uA)  // creates the row, NOT committed yet
            val pid2 = c2.pid()
            val retoB = AtomicReference<Any?>()
            val t = Thread { retoB.set(runCatching { c2.primeraApertura(uB) }.getOrElse { it }) }.apply { isDaemon = true; start() }
            val (tipo, evento) = esperaBloqueada(c, pid2)
            assertEquals("Lock", tipo, "the second first open must wait for the row being created")
            assertEquals("transactionid", evento, "it waits on that row (unique index), never on an advisory lock")
            c1.commit()
            t.join(10_000)
            assertEquals(retoA, retoB.get(), "both users get the SAME challenge row")
            c2.commit()
            assertEquals(1, c.filasNacionales(), "exactly one row for the day and segment")
            assertEquals("3", c.unico("select count(*) from public.preguntas_reto_diario where reto_id = '$retoA'"), "one question set, inserted once")
        } finally {
            c1.close(); c2.close()
        }
    }

    @Test
    fun whileTheJobHoldsItsLockTheFastPathAndAMissingSegmentDoNotWaitAndTakeNoAdvisoryLock(): Unit = PostgresHarness.escenario { c ->
        c.instalar()
        val seed = c.primeraApertura(c.usuario(esp))  // autocommit: today's row of esp exists
        val job = PostgresHarness.conexionExtra(c)
        val user = PostgresHarness.conexionExtra(c)
        try {
            job.autoCommit = false
            job.unico("select pg_advisory_xact_lock(hashtextextended('reto-job', 0))::text")
            user.ejecutar("set statement_timeout = '5s'")
            user.autoCommit = false
            assertEquals(seed, user.primeraApertura(c.usuario(esp)), "fast path (row exists) answers while the job lock is held")
            assertNotNull(user.primeraApertura(c.usuario(esp2)), "a missing segment is created while the job lock is held")
            assertEquals("0", user.unico("select count(*) from pg_locks where locktype = 'advisory' and pid = pg_backend_pid()"), "the first open took no advisory lock")
            user.commit()
            assertEquals(1, c.filasNacionales(esp2))
        } finally {
            job.close(); user.close()
        }
    }

    @Test
    fun negativeControlThe101200FirstOpenWaitsForTheGlobalJobLock(): Unit = PostgresHarness.escenario { c ->
        c.instalar(firstOpenFrom = v1200)
        val job = PostgresHarness.conexionExtra(c)
        val user = PostgresHarness.conexionExtra(c)
        try {
            job.autoCommit = false
            job.unico("select pg_advisory_xact_lock(hashtextextended('reto-job', 0))::text")
            user.ejecutar("set statement_timeout = '1s'")
            val e = runCatching { user.primeraApertura(c.usuario(esp)) }.exceptionOrNull()
            assertTrue(e is SQLException && e.sqlState == "57014", "the old body serializes every first open on reto-job: $e")
        } finally {
            job.close(); user.close()
        }
    }

    @Test
    fun manyConcurrentFirstOpensWithCrossedCommunitiesAllGetTheirSegmentsSingleRowWithoutDeadlock(): Unit = PostgresHarness.escenario { c ->
        c.instalar()
        // half the users see A then B, the other half B then A (principal vs target): before 20261022103500 the creation order followed a UNION
        val usuarios = (0 until 12).map { i -> if (i % 2 == 0) c.usuario(esp, comA, comB) else c.usuario(esp, comB, comA) }
        val pool = Executors.newFixedThreadPool(usuarios.size)
        val salida = CountDownLatch(1)
        try {
            val futuros = usuarios.map { u ->
                pool.submit<String> {
                    PostgresHarness.conexionExtra(c).use { k ->
                        k.autoCommit = false
                        k.ejecutar("set lock_timeout = '20s'")
                        salida.await()
                        val r = k.primeraApertura(u)
                        k.commit()
                        r
                    }
                }
            }
            salida.countDown()
            val retos = futuros.map { runCatching { it.get(60, TimeUnit.SECONDS) }.getOrElse { e -> fail("a first open failed (deadlock or lock wait): $e") } }
            val porPrincipal = usuarios.zip(retos).groupBy({ (u, _) -> c.unico("select comunidad_principal_id::text from public.perfiles_usuario where usuario_id = '$u'") }, { it.second })
            porPrincipal.forEach { (com, ids) -> assertEquals(1, ids.toSet().size, "every user of community $com gets the same row") }
            assertEquals(1, c.filasNacionales())
            for (com in listOf(comA, comB)) {
                assertEquals("1", c.unico("select count(*) from public.retos_diarios where especialidad_id = '$esp' and comunidad_id = '$com'"), "one regional row for $com")
            }
            assertEquals("0", c.unico("select count(*) from (select reto_id from public.preguntas_reto_diario group by reto_id having count(*) <> 3) x"), "every row has exactly its three questions")
        } finally {
            pool.shutdownNow()
        }
    }
}
