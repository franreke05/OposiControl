package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.security.MessageDigest
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `20261111100000_v1_demo_aleatoria` (F14b, propietario 2026-11-11 y 2026-11-14): el conjunto AUTOMATICO de demo son N preguntas elegidas al azar UNA VEZ
 * (las N primeras elegibles por `md5(examen_id || pregunta_id)` al publicarse el examen o en el relleno) y guardadas en `private.demo_set_fijo`: anadir
 * preguntas no lo cambia; una que deja de ser elegible se sustituye SOLA por la siguiente del orden md5 y la sustituta se queda. El explicito completo de
 * Control sigue ganando; "menos de N = sin demo" como en F14. Control negativo: ROLLBACK.
 */
class V1DemoAleatoriaMigrationTest {
    private val migration = "20261111100000_v1_demo_aleatoria"
    private val u1 = "a0000000-0000-0000-0000-00000014b001"
    private val u2 = "a0000000-0000-0000-0000-00000014b002"
    private val esp = "e1000000-0000-0000-0000-00000014b001"
    private val com = "c1000000-0000-0000-0000-00000014b001"
    private val big = "e14b0000-0000-0000-0000-000000000001"
    private val small = "e14b0000-0000-0000-0000-000000000002"

    /** Preguntas 1..13 del examen grande (12 principales + la 13 de reserva; la 4 inactiva); 14..18 del pequeno (4 principales + la 18 de reserva). */
    private fun q(n: Int) = "f14b0000-0000-0000-0000-0000000000" + n.toString().padStart(2, '0')

    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$u1', 'f14b-1@test.local', 'authenticated', 'authenticated'), ('$u2', 'f14b-2@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F14B', 'F14B', 'f14b', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F14B', 'Com F14B', 'com-f14b');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$u1', 'b1', 'f14b_user_1', true, 'activa', '$esp'), ('$u2', 'b2', 'f14b_user_2', true, 'activa', '$esp')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, demo_disponible)
          values ('$big', 'F14B-1', '$esp', '$com', 'Grande', 'rafaga', 'premium', true, now() - interval '1 day', true),
                 ('$small', 'F14B-2', '$esp', '$com', 'Pequeno', 'rafaga', 'premium', true, now() - interval '1 day', true);
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'F14B-' || n, 'rafaga', 'Pregunta ' || n, n <> 4 from generate_series(1, 18) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0c14b000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'A', 'Si', 1 from generate_series(1, 18) n
          union all
          select ('0c14c000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'B', 'No', 2 from generate_series(1, 18) n;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0c14b000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'x' from generate_series(1, 18) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, es_reserva, orden_reserva)
          select '$big'::uuid, ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n, n = 13, case when n = 13 then 1 end from generate_series(1, 13) n
          union all
          select '$small'::uuid, ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n - 13, n - 13, n = 18, case when n = 18 then 1 end from generate_series(14, 18) n;
        commit;
    """.trimIndent()

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** La regla, calculada FUERA de la base: las N primeras elegibles por md5(examen || pregunta), en orden del examen. */
    private fun expected(eligible: List<Int>, n: Int = 5) = eligible.sortedBy { md5(big + q(it)) }.take(n).sorted().map(::q)

    private fun Connection.efectivas(exam: String = big): List<String> =
        filas("select unnest(private.demo_preguntas_efectivas('$exam'))::text").map { it[0]!! }
    private fun Connection.startDemo(u: String, exam: String = big): String = unico("select public.app_start_exam_attempt('$u', '$exam', 'DEMO')::text")!!
    private fun Connection.outcome(json: String) = unico("select ('$json'::jsonb) ->> 'outcome'")
    private fun Connection.frozen(json: String): List<String> =
        filas("select pregunta_id::text from public.preguntas_intento where intento_id = (('$json'::jsonb) ->> 'intento_id')::uuid order by posicion").map { it[0]!! }
    private fun Connection.fijado(exam: String = big): List<String> =
        filas(
            "select f.pregunta_id::text from private.demo_set_fijo f join public.preguntas_examen pe on pe.examen_id = f.examen_id and pe.pregunta_id = f.pregunta_id " +
                "where f.examen_id = '$exam' order by pe.posicion",
        ).map { it[0]!! }

    /** El relleno de la migracion para un examen (la semilla entra con session_replication_role = replica: sin triggers). */
    private fun Connection.fijar(exam: String = big) = ejecutar("select private.demo_set_fijar('$exam')")

    /** Preguntas nuevas [from]..[to] (posicion = numero) del examen grande, principales; la reserva 13 pasa al final (posicion 100). Sin replica: con triggers. */
    private fun addQuestions(from: Int, to: Int) = """
        begin;
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'F14B-' || n, 'rafaga', 'Pregunta ' || n, true from generate_series($from, $to) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0c14b000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'A', 'Si', 1 from generate_series($from, $to) n
          union all
          select ('0c14c000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'B', 'No', 2 from generate_series($from, $to) n;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0c14b000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'x' from generate_series($from, $to) n;
        update public.preguntas_examen set posicion = 100, numero_en_examen = 100 where examen_id = '$big' and pregunta_id = '${q(13)}';
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
          select '$big'::uuid, ('f14b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n from generate_series($from, $to) n;
        commit;
    """.trimIndent()

    private val eligibleAtSeed = (1..12).filter { it != 4 }  // 13 is a reserve, 4 is inactive
    private val added = (19..38).toList()

    @Test
    fun theAutomaticSetIsRandomLookingButIdenticalForEveryUserAndEveryCall() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val set = expected(eligibleAtSeed)
        assertEquals(listOf(q(1), q(3), q(8), q(9), q(11)), set, "precomputed rank of this seed")
        assertEquals(set, c.efectivas(), "before fixing, the read previews the md5 rank")
        c.fijar()
        assertEquals(set, c.fijado(), "fixing stores exactly the md5 choice")
        assertEquals(set, c.efectivas(), "the database serves the fixed set")
        assertEquals(set, c.efectivas(), "a second call returns the same set")
        assertNotEquals(listOf(q(1), q(2), q(3), q(5), q(6)), set, "not the first five by position any more")
        assertEquals("true", c.unico("select public.demo_listo(e)::text from public.examenes e where e.id = '$big'"))
        val a = c.startDemo(u1)
        val b = c.startDemo(u2)
        assertEquals("CREATED|CREATED", c.outcome(a) + "|" + c.outcome(b))
        assertEquals(set, c.frozen(a), "user 1 gets the fixed set, in exam order")
        assertEquals(set, c.frozen(b), "user 2 gets exactly the same set")
        val aId = c.unico("select ('$a'::jsonb) ->> 'intento_id'")
        assertEquals(
            "QUESTION_NOT_IN_ATTEMPT",
            c.unico("select public.app_record_attempt_answer('$u1', '$aId', '${q(2)}', '0c14b000-0000-0000-0000-000000000002', 0, 0, null) ->> 'outcome'"),
            "a question outside the set (one of the first five by position) is unreachable",
        )
        assertEquals("true|false|false", c.filas("select es_demo::text, cuenta_para_ranking::text, es_primer_intento_examen::text from public.intentos_examen where id = '$aId'").single().joinToString("|"), "a demo never counts")
        assertEquals("0", c.unico("select count(*)::text from public.preguntas_examen where examen_id = '$big' and es_demo"), "the explicit flags of Control are never written")
    }

    @Test
    fun addingQuestionsNeverChangesTheFixedSet() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.fijar()
        val set = expected(eligibleAtSeed)
        c.ejecutar(addQuestions(19, 38))
        assertNotEquals(set, expected(eligibleAtSeed + added), "negative control: a pure md5 rank WOULD take some of the new questions")
        assertEquals(set, c.fijado(), "the stored set did not move")
        assertEquals(set, c.efectivas(), "the served set did not move")
        assertEquals(set, c.frozen(c.startDemo(u1)))
    }

    @Test
    fun aCompleteExplicitSetStillWins() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.fijar()
        c.ejecutar("update public.preguntas_examen set es_demo = true where examen_id = '$big' and posicion in (2, 5, 6, 10, 12)")
        assertEquals(listOf(q(2), q(5), q(6), q(10), q(12)), c.efectivas())
        assertEquals(listOf(q(2), q(5), q(6), q(10), q(12)), c.frozen(c.startDemo(u1)))
    }

    @Test
    fun anAnnulledMemberIsReplacedAloneAndTheReplacementStays() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.fijar()
        c.ejecutar(addQuestions(19, 38))
        val original = listOf(1, 3, 8, 9, 11)
        assertEquals(original.map(::q), c.fijado())
        c.ejecutar("update public.preguntas_examen set anulada = true, motivo_anulacion = 'tribunal' where examen_id = '$big' and posicion = 9")
        // the replacement = the next of the md5 rank among the eligible non-members, the new questions included
        val members = original - 9
        val next = (eligibleAtSeed - 9 + added).filter { it !in members }.minByOrNull { md5(big + q(it)) }!!
        val replaced = (members + next).sorted().map(::q)
        assertEquals(replaced, c.fijado(), "only the annulled one was replaced, and the replacement is stored")
        assertEquals(replaced, c.efectivas())
        assertEquals(replaced, c.frozen(c.startDemo(u1)))
        c.ejecutar("update public.preguntas_examen set anulada = false, motivo_anulacion = null where examen_id = '$big' and posicion = 9")
        assertEquals(replaced, c.efectivas(), "restored: the replacement stays (the set never changes back)")
        c.ejecutar("update public.preguntas set activa = false where id = '${q(1)}'")
        val after = c.fijado()
        assertEquals(4, after.intersect(replaced.toSet()).size, "a deactivated member is also replaced alone: $after")
        assertTrue(q(1) !in after)
        assertEquals(after, c.efectivas())
    }

    @Test
    fun aDraftIsFixedOnlyWhenPublished() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar("update public.examenes set publicado_en = null where id = '$big'")
        c.fijar()
        assertEquals(emptyList(), c.fijado(), "a draft is not fixed")
        c.ejecutar(addQuestions(19, 38))
        assertEquals(emptyList(), c.fijado(), "uploading questions to a draft fixes nothing")
        c.ejecutar("update public.examenes set publicado_en = now() where id = '$big'")
        assertEquals(expected(eligibleAtSeed + added), c.fijado(), "publishing fixes the md5 choice over everything uploaded")
    }

    @Test
    fun fewerThanNEligibleQuestionsMeansNoDemo() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.fijar(small)
        assertEquals(emptyList(), c.fijado(small), "nothing to fix")
        assertEquals(emptyList(), c.efectivas(small), "4 main questions + 1 reserve: no set")
        assertEquals("false", c.unico("select public.demo_listo(e)::text from public.examenes e where e.id = '$small'"))
        assertEquals("EXAM_LOCKED", c.outcome(c.startDemo(u1, small)))
    }

    @Test
    fun theBackfillFixesPublishedExamsTheVerifyPassesAndTheRollbackRestoresF14() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        val src = "select md5(prosrc) from pg_proc where oid = 'private.demo_preguntas_efectivas(uuid)'::regprocedure"
        val f14 = c.unico(src)
        assertEquals(listOf(q(1), q(2), q(3), q(5), q(6)), c.efectivas(), "negative control: F14 = first five by position")
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(expected(eligibleAtSeed), c.fijado(), "the backfill fixed the published exam")
        assertEquals(emptyList(), c.fijado(small))
        assertEquals(expected(eligibleAtSeed), c.efectivas())
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(12, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar("delete from private.demo_set_fijo where examen_id = '$big'")
        assertEquals(listOf("demo_aleatoria_published_exam_without_fixed_set"), c.filas(verify).filter { it[1] != "0" }.map { it[0] }, "VERIFY sees a published exam without its set")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals(f14, c.unico(src), "the rollback restores the F14 body byte for byte")
        assertEquals("false", c.unico("select (to_regclass('private.demo_set_fijo') is not null)::text"))
        assertEquals(listOf(q(1), q(2), q(3), q(5), q(6)), c.efectivas())
        // without the table the VERIFY cannot even be planned (relation missing): that is a failure too
        assertTrue(runCatching { c.filas(verify).any { it[1] != "0" } }.getOrDefault(true), "VERIFY fails without the migration")
    }
}

