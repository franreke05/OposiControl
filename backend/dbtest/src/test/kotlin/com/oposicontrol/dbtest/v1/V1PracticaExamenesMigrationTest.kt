package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `20261114200100_v1_practica_examenes` (F23, FA47b, plan del propietario 2026-11-14 "Generar examen"): la practica puede limitarse a 1..50 examenes ELEGIDOS, cada uno dentro
 * del alcance de la practica (activo, publicado, de la especialidad, veredicto FREE / GRANTED / SUBSCRIPTION); fuera = EXAM_NOT_ACCESSIBLE sin crear nada. La disponibilidad
 * suma `examenes_adquiridos` = examenes distintos con concesion VIGENTE, activos, publicados y de la especialidad (el conjunto de D2-13 vista=ADQUIRIDOS).
 * Sin elegir: el comportamiento de 20261109100100 (y la llamada de dos argumentos sigue valiendo). Control negativo + VERIFY / ROLLBACK.
 */
class V1PracticaExamenesMigrationTest {
    private val migration = "20261114200100_v1_practica_examenes"
    private val user = "a0000000-0000-0000-0000-0000000023b1"
    private val esp = "e1000000-0000-0000-0000-0000000023b1"
    private val esp2 = "e1000000-0000-0000-0000-0000000023b2"
    private val com = "c1000000-0000-0000-0000-0000000023b1"
    private val free = "e23b0000-0000-0000-0000-000000000001"     // gratuito sin concesion: q 1..3
    private val granted = "e23b0000-0000-0000-0000-000000000002"  // premium concedido: q 4
    private val locked = "e23b0000-0000-0000-0000-000000000003"   // premium sin acceso: q 5
    private val foreign = "e23b0000-0000-0000-0000-000000000004"  // gratuito de otra especialidad (con concesion): q 6
    private val draft = "e23b0000-0000-0000-0000-000000000005"    // gratuito sin publicar (con concesion): q 7
    private val annulled = "e23b0000-0000-0000-0000-000000000006" // gratuito con su unica pregunta anulada: q 8
    private val granted2 = "e23b0000-0000-0000-0000-000000000007" // premium con DOS concesiones vigentes: q 9
    private val expired = "e23b0000-0000-0000-0000-000000000008"  // premium con concesion caducada: q 10
    private val future = "e23b0000-0000-0000-0000-000000000009"   // premium con concesion que empieza manana: q 11
    private val missing = "e23b0000-0000-0000-0000-0000000000ff"

    private fun q(n: Int) = "f23b0000-0000-0000-0000-0000000000" + n.toString().padStart(2, '0')

    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$user', 'pe-u@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F23E', 'F23E', 'f23e', true), ('$esp2', 'F23F', 'F23F', 'f23f', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F23E', 'Com F23E', 'com-f23e');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$user', 'pe', 'p_f23e_user', true, 'activa', '$esp')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
          values ('$free', 'F23E-1', '$esp', '$com', 'Gratis', 'rafaga', 'gratuito', true, now() - interval '1 day'),
                 ('$granted', 'F23E-2', '$esp', '$com', 'Comprado', 'rafaga', 'premium', true, now() - interval '1 day'),
                 ('$locked', 'F23E-3', '$esp', '$com', 'Cerrado', 'rafaga', 'premium', true, now() - interval '1 day'),
                 ('$foreign', 'F23E-4', '$esp2', '$com', 'Otra', 'rafaga', 'gratuito', true, now() - interval '1 day'),
                 ('$draft', 'F23E-5', '$esp', '$com', 'Borrador', 'rafaga', 'gratuito', true, null),
                 ('$annulled', 'F23E-6', '$esp', '$com', 'Anulado', 'rafaga', 'gratuito', true, now() - interval '1 day'),
                 ('$granted2', 'F23E-7', '$esp', '$com', 'Comprado 2', 'rafaga', 'premium', true, now() - interval '1 day'),
                 ('$expired', 'F23E-8', '$esp', '$com', 'Caducado', 'rafaga', 'premium', true, now() - interval '1 day'),
                 ('$future', 'F23E-9', '$esp', '$com', 'Futuro', 'rafaga', 'premium', true, now() - interval '1 day');
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f23b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'F23E-' || n, 'rafaga', 'Pregunta ' || n, true from generate_series(1, 11) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0c23e000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f23b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'A', 'Si', 1 from generate_series(1, 11) n
          union all
          select ('0c23f000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('f23b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'B', 'No', 2 from generate_series(1, 11) n;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f23b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, ('0c23e000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, 'x' from generate_series(1, 11) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva)
          select '$free'::uuid, ('f23b0000-0000-0000-0000-0000000000' || lpad(n::text, 2, '0'))::uuid, n, n, false, null, false, null from generate_series(1, 3) n
          union all select '$granted'::uuid, '${q(4)}'::uuid, 1, 1, false, null, false, null
          union all select '$locked'::uuid, '${q(5)}'::uuid, 1, 1, false, null, false, null
          union all select '$foreign'::uuid, '${q(6)}'::uuid, 1, 1, false, null, false, null
          union all select '$draft'::uuid, '${q(7)}'::uuid, 1, 1, false, null, false, null
          union all select '$annulled'::uuid, '${q(8)}'::uuid, 1, 1, true, 'anulada', false, null
          union all select '$granted2'::uuid, '${q(9)}'::uuid, 1, 1, false, null, false, null
          union all select '$expired'::uuid, '${q(10)}'::uuid, 1, 1, false, null, false, null
          union all select '$future'::uuid, '${q(11)}'::uuid, 1, 1, false, null, false, null;
        insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde, valido_hasta)
          values ('$user', '$granted', 'compra', now() - interval '1 day', null),
                 ('$user', '$granted2', 'compra', now() - interval '3 day', null),
                 ('$user', '$granted2', 'regalo', now() - interval '1 day', now() + interval '30 day'),
                 ('$user', '$foreign', 'compra', now() - interval '1 day', null),
                 ('$user', '$draft', 'compra', now() - interval '1 day', null),
                 ('$user', '$expired', 'compra', now() - interval '10 day', now() - interval '1 day'),
                 ('$user', '$future', 'compra', now() + interval '1 day', null);
        commit;
    """.trimIndent()

    private fun arr(vararg ids: String) = "array[" + ids.joinToString(",") { "'$it'" } + "]::uuid[]"
    private fun Connection.start(n: Int, exams: String?): String =
        unico("select public.app_start_practice_attempt('$user', $n${exams?.let { ", $it" }.orEmpty()})::text")!!
    private fun Connection.outcome(json: String) = unico("select ('$json'::jsonb) ->> 'outcome'")
    private fun Connection.attemptOf(json: String) = unico("select ('$json'::jsonb) ->> 'intento_id'")!!
    private fun Connection.frozen(attempt: String): Set<String> =
        filas("select pregunta_id::text from public.preguntas_intento where intento_id = '$attempt'").map { it[0]!! }.toSet()
    private fun Connection.practices() = unico("select count(*)::text from public.intentos_examen where usuario_id = '$user' and es_practica")!!.toInt()

    @Test
    fun theQuestionsComeOnlyFromTheChosenExams() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val onlyFree = c.attemptOf(c.start(10, arr(free)))
        assertEquals(setOf(q(1), q(2), q(3)), c.frozen(onlyFree))
        assertEquals("FREE", c.unico("select acceso_estado from public.intentos_examen where id = '$onlyFree'"))
        assertEquals("[\"$free\"]", c.unico("select metadatos -> 'practicaExamenes' from public.intentos_examen where id = '$onlyFree'"), "the choice is kept on the attempt")
        val onlyGranted = c.attemptOf(c.start(5, arr(granted, granted2)))
        assertEquals(setOf(q(4), q(9)), c.frozen(onlyGranted))
        assertEquals("GRANTED", c.unico("select acceso_estado from public.intentos_examen where id = '$onlyGranted'"), "every question from a GRANTED exam: exempt from the daily limit")
        assertEquals(
            listOf("t", "t"),
            c.filas("select es_practica::text, es_demo::text from public.intentos_examen where id = '$onlyGranted'").single().map { it!!.take(1) },
            "es_demo / es_practica flagging unchanged",
        )
        val both = c.attemptOf(c.start(20, arr(free, granted)))
        assertEquals(setOf(q(1), q(2), q(3), q(4)), c.frozen(both))
        // no choice = the whole scope (FA47), also with the two-argument call an older image makes
        val all = c.attemptOf(c.start(20, null))
        assertEquals(setOf(q(1), q(2), q(3), q(4), q(9)), c.frozen(all), "free + granted + granted2; never locked, foreign, draft, annulled, expired or future")
        assertNull(c.unico("select metadatos -> 'practicaExamenes' from public.intentos_examen where id = '$all'"))
    }

    @Test
    fun aChosenExamOutsideTheScopeCreatesNothing() = V1Db.escenario { c ->
        c.ejecutar(seed)
        for (bad in listOf(locked, foreign, draft, expired, future, missing)) {
            val r = c.start(5, arr(free, bad))
            assertEquals("EXAM_NOT_ACCESSIBLE", c.outcome(r), "$bad: $r")
            assertEquals("[\"$bad\"]", c.unico("select ('$r'::jsonb) -> 'examen_ids'"))
        }
        assertEquals(0, c.practices(), "nothing created")
        assertEquals("PRACTICE_EMPTY", c.outcome(c.start(5, arr(annulled))), "an accessible exam without servable questions")
        assertEquals(0, c.practices())
    }

    @Test
    fun malformedChoicesAreRejected() = V1Db.escenario { c ->
        c.ejecutar(seed)
        assertEquals("INVALID_EXAMS", c.outcome(c.start(5, "array[]::uuid[]")))
        assertEquals("INVALID_EXAMS", c.outcome(c.start(5, arr(free, free))))
        assertEquals("INVALID_EXAMS", c.outcome(c.start(5, "array['$free', null]::uuid[]")))
        assertEquals("INVALID_EXAMS", c.outcome(c.start(5, "(select array_agg(gen_random_uuid()) from generate_series(1, 51))")))
        assertEquals("INVALID_SIZE", c.outcome(c.start(7, arr(free))))
        assertEquals(0, c.practices())
    }

    @Test
    fun availabilityCountsTheAcquiredExamsOnce() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val a = c.unico("select public.app_practice_availability('$user')::text")!!
        assertEquals("2", c.unico("select ('$a'::jsonb ->> 'examenes_adquiridos')"), "granted + granted2 (two grants, once); not free (no grant), foreign, draft, expired or future: $a")
        assertEquals("5", c.unico("select ('$a'::jsonb ->> 'preguntas')"))
        c.ejecutar("update public.perfiles_usuario set especialidad_principal_id = null where usuario_id = '$user'")
        assertEquals("0", c.unico("select public.app_practice_availability('$user') ->> 'examenes_adquiridos'"), "no specialty: nothing acquired in scope")
        assertEquals("0", c.unico("select public.app_practice_availability(null) ->> 'examenes_adquiridos'"))
    }

    @Test
    fun theVerifyAndRollbackBehave() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(6, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("1", c.unico("select count(*)::text from pg_proc where proname = 'app_start_practice_attempt'"), "only the two-argument function again")
        assertEquals("CREATED", c.outcome(c.start(5, null)))
        assertNull(c.unico("select public.app_practice_availability('$user') ->> 'examenes_adquiridos'"))
        assertTrue(c.filas(verify).any { it[1] != "0" }, "VERIFY fails without the migration")
    }

    @Test
    fun withoutTheMigrationThereIsNoChoice() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        assertEquals("0", c.unico("select count(*)::text from pg_proc where proname = 'app_start_practice_attempt' and pronargs = 3"))
        assertNull(c.unico("select public.app_practice_availability('$user') ->> 'examenes_adquiridos'"))
    }
}
