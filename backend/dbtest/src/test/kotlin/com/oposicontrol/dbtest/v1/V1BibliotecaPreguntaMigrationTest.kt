package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `20261114200000_v1_biblioteca_pregunta` (F30, FA50, plan del propietario 2026-11-14): la Biblioteca abre la pregunta guardada ahi mismo. `app_saved_question_detail`
 * solo responde si el usuario la guardo Y puede abrir alguno de sus examenes (la regla de la lista); contenido y solucion VIVOS (las funciones que congelan revisiones),
 * mas el examen elegido (enlace no anulado primero, luego la publicacion mas reciente), su posicion y si esta anulada. `app_list_saved` devuelve tipo_examen y usa la
 * MISMA eleccion. No escribe nada (no toca el limitador). B1 (revision Control 2026-11-14): la solucion solo si el usuario ya la resolvio (intento FINALIZADO
 * que la contiene; un intento abierto nunca cuenta, B1-bis); guardar exige que la pregunta le haya sido revelada; las reservas sin usar no cuentan. Control negativo + VERIFY / ROLLBACK.
 */
class V1BibliotecaPreguntaMigrationTest {
    private val migration = "20261114200000_v1_biblioteca_pregunta"
    private val user = "a0000000-0000-0000-0000-0000000030b1"
    private val other = "a0000000-0000-0000-0000-0000000030b2"
    private val esp = "e1000000-0000-0000-0000-0000000030b1"
    private val com = "c1000000-0000-0000-0000-0000000030b1"
    private val inmersivo = "e30b0000-0000-0000-0000-000000000001" // gratuito inmersivo, publicado hace 2 dias: q1 (posicion 3), q2
    private val newer = "e30b0000-0000-0000-0000-000000000002"     // gratuito inmersivo mas reciente: q1 (posicion 1), q3
    private val locked = "e30b0000-0000-0000-0000-000000000003"    // premium sin acceso: q4
    private fun q(n: Int) = "f30b0000-0000-0000-0000-00000000000$n"
    private fun ok(n: Int) = "0c30a000-0000-0000-0000-00000000000$n"

    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$user', 'b-u@test.local', 'authenticated', 'authenticated'), ('$other', 'b-o@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F30', 'F30', 'f30', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F30', 'Com F30', 'com-f30');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$user', 'bu', 'b_f30_user', true, 'activa', '$esp'), ('$other', 'bo', 'b_f30_other', true, 'activa', '$esp')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en)
          values ('$inmersivo', 'F30-1', '$esp', '$com', 'Inmersivo viejo', 'inmersivo', 'gratuito', true, now() - interval '2 day'),
                 ('$newer', 'F30-2', '$esp', '$com', 'Inmersivo nuevo', 'inmersivo', 'gratuito', true, now() - interval '1 day'),
                 ('$locked', 'F30-3', '$esp', '$com', 'Cerrado', 'inmersivo', 'premium', true, now() - interval '1 day');
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f30b0000-0000-0000-0000-00000000000' || n)::uuid, 'F30-' || n, 'inmersiva', 'Enunciado ' || n, true from generate_series(1, 4) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0c30a000-0000-0000-0000-00000000000' || n)::uuid, ('f30b0000-0000-0000-0000-00000000000' || n)::uuid, 'A', 'Si', 1 from generate_series(1, 4) n
          union all
          select ('0c30b000-0000-0000-0000-00000000000' || n)::uuid, ('f30b0000-0000-0000-0000-00000000000' || n)::uuid, 'B', 'No', 2 from generate_series(1, 4) n;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f30b0000-0000-0000-0000-00000000000' || n)::uuid, ('0c30a000-0000-0000-0000-00000000000' || n)::uuid, 'Razon ' || n from generate_series(1, 4) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen, anulada, motivo_anulacion, es_reserva, orden_reserva)
          values ('$inmersivo', '${q(1)}', 3, 3, false, null, false, null), ('$inmersivo', '${q(2)}', 1, 1, false, null, false, null),
                 ('$newer', '${q(1)}', 1, 1, false, null, false, null), ('$newer', '${q(3)}', 2, 2, false, null, false, null),
                 ('$locked', '${q(4)}', 1, 1, false, null, false, null);
        insert into public.recursos_guardados_usuario (usuario_id, tipo, pregunta_id) values ('$user', 'pregunta', '${q(1)}'), ('$user', 'pregunta', '${q(2)}'),
          ('$user', 'pregunta', '${q(4)}'), ('$other', 'pregunta', '${q(3)}');
        commit;
    """.trimIndent()

    private fun Connection.detail(u: String, n: Int) = unico("select public.app_saved_question_detail('$u', '${q(n)}')::text")!!
    private fun Connection.field(json: String, path: String) = unico("select ('$json'::jsonb) #>> '{${path}}'")
    private fun Connection.errorOf(sql: String): String? = try {
        ejecutar(sql); null
    } catch (e: SQLException) {
        (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.message ?: e.message
    }
    private fun Connection.save(u: String, n: Int) = errorOf("select public.app_save_item('$u', 'pregunta', '${q(n)}')")

    /** Un intento del usuario (sin triggers) con esas preguntas, en ese estado y con ese acceso. */
    private fun Connection.attempt(id: String, u: String, examen: String, estado: String, acceso: String, vararg ns: Int) = ejecutar(
        "begin; set local session_replication_role = replica; " +
            "insert into public.intentos_examen (id, usuario_id, examen_id, estado, acceso_estado) values ('$id', '$u', '$examen', '$estado', '$acceso'); " +
            ns.mapIndexed { i, n -> "insert into public.preguntas_intento (intento_id, pregunta_id, posicion) values ('$id', '${q(n)}', ${i + 1});" }.joinToString(" ") +
            " commit;",
    )
    private fun Connection.answer(intento: String, n: Int) = ejecutar(
        "begin; set local session_replication_role = replica; " +
            "insert into public.respuestas_intento (intento_id, pregunta_id, opcion_seleccionada_id) values ('$intento', '${q(n)}', '${ok(n)}'); commit;",
    )
    private val att1 = "a7700000-0000-0000-0000-0000000030b1"
    private val att2 = "a7700000-0000-0000-0000-0000000030b2"
    private val att3 = "a7700000-0000-0000-0000-0000000030b3"
    private val limitedPlan = """
        insert into public.planes_suscripcion (id, codigo, nombre, nivel) values ('b1000000-0000-0000-0000-0000000030b1', 'gratuito-f30', 'Gratuito', 0);
        insert into public.beneficios_plan (plan_id, codigo_beneficio, valor_integer) values ('b1000000-0000-0000-0000-0000000030b1', 'limite_preguntas_24h', 2);
    """.trimIndent()

    @Test
    fun theDetailIsTheLiveQuestionOfTheChosenExam() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val d = c.detail(user, 1)
        assertEquals("OK", c.field(d, "outcome"), d)
        assertEquals(newer, c.field(d, "examen_id"), "two accessible links: the latest publication")
        assertEquals("Inmersivo nuevo", c.field(d, "examen_titulo"))
        assertEquals("inmersivo", c.field(d, "tipo_examen"))
        assertEquals("1", c.field(d, "posicion"))
        assertEquals("false", c.field(d, "anulada"))
        assertEquals("Enunciado 1", c.field(d, "contenido,titulo"))
        assertEquals("2", c.unico("select jsonb_array_length(('$d'::jsonb) -> 'contenido' -> 'opciones')::text"))
        assertEquals("null", c.unico("select jsonb_typeof(('$d'::jsonb) -> 'solucion')"), "saved, never answered: no solution (JSON null), still OK")
        // saved, not answered, open attempt -> no solution
        c.attempt(att1, user, newer, "en_progreso", "FREE", 1, 3)
        val open = c.detail(user, 1)
        assertEquals("OK", c.field(open, "outcome"))
        assertNull(c.field(open, "solucion,opcion_correcta_id"), "open attempt, question not answered: the answer stays hidden")
        // B1-bis: open attempt answered -> still no solution (the answer can still be changed)
        c.answer(att1, 1)
        assertEquals("null", c.unico("select jsonb_typeof(('${c.detail(user, 1)}'::jsonb) -> 'solucion')"), "answered inside an OPEN attempt: no solution")
        // after finishing -> solution
        c.ejecutar("begin; set local session_replication_role = replica; update public.intentos_examen set estado = 'finalizado' where id = '$att1'; commit;")
        val answered = c.detail(user, 1)
        assertEquals(ok(1), c.field(answered, "solucion,opcion_correcta_id"))
        assertEquals("Razon 1", c.field(answered, "solucion,razonamiento"))
        // a finished attempt that contains it also counts (q2: never answered, attempt finished)
        assertNull(c.field(c.detail(user, 2), "solucion,opcion_correcta_id"))
        c.attempt(att2, user, inmersivo, "finalizado", "FREE", 2)
        assertEquals(ok(2), c.field(c.detail(user, 2), "solucion,opcion_correcta_id"))
        // annulled in the newer exam: the non-annulled link wins
        c.ejecutar("update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where examen_id = '$newer' and pregunta_id = '${q(1)}'")
        val d2 = c.detail(user, 1)
        assertEquals(inmersivo, c.field(d2, "examen_id"))
        assertEquals("3", c.field(d2, "posicion"))
        assertEquals("false", c.field(d2, "anulada"))
        // annulled everywhere: still served, flagged
        c.ejecutar("update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where pregunta_id = '${q(1)}'")
        val d3 = c.detail(user, 1)
        assertEquals("OK", c.field(d3, "outcome"))
        assertEquals(newer, c.field(d3, "examen_id"))
        assertEquals("true", c.field(d3, "anulada"))
        assertEquals("0", c.unico("select count(*)::text from public.preguntas_reveladas_usuario where usuario_id = '$user'"), "nothing revealed: no quota")
    }

    @Test
    fun onlyWhatTheUserSavedAndCanOpen() = V1Db.escenario { c ->
        c.ejecutar(seed)
        assertEquals("NOT_FOUND", c.field(c.detail(user, 3), "outcome"), "not saved by this user (another user saved it)")
        assertEquals("OK", c.field(c.detail(other, 3), "outcome"))
        assertEquals("NOT_FOUND", c.field(c.detail(user, 4), "outcome"), "saved, but its only exam is locked")
        c.ejecutar("update public.preguntas set activa = false where id = '${q(2)}'")
        assertEquals("NOT_FOUND", c.field(c.detail(user, 2), "outcome"), "inactive question")
        c.ejecutar("begin; set local session_replication_role = replica; update public.examenes set publicado_en = null where id in ('$inmersivo', '$newer'); commit;")
        assertEquals("NOT_FOUND", c.field(c.detail(user, 1), "outcome"), "no published exam")
        assertEquals("NOT_FOUND", c.unico("select public.app_saved_question_detail(null, '${q(1)}') ->> 'outcome'"))
    }

    @Test
    fun savingNeedsTheQuestionRevealedToTheUser() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar(limitedPlan)
        // q2 is in no attempt of `other`: accessible exam, but never revealed to them
        assertEquals("NOT_FOUND", c.save(other, 2))
        // open limited attempt: the D2-07 id list is not a reveal; the limiter row is
        c.attempt(att1, other, inmersivo, "en_progreso", "FREE", 1, 2)
        assertEquals("NOT_FOUND", c.save(other, 2), "in the open attempt but its page was never served (limited plan)")
        c.ejecutar("insert into public.preguntas_reveladas_usuario (usuario_id, pregunta_id, revelada_en) values ('$other', '${q(2)}', now())")
        assertNull(c.save(other, 2), "served by the limiter: can be saved")
        // answered counts as revealed
        c.answer(att1, 1)
        assertNull(c.save(other, 1))
        // GRANTED attempt: the limiter does not count it, every question in it is revealable
        c.ejecutar("delete from public.recursos_guardados_usuario where usuario_id = '$other'")
        c.attempt(att2, other, newer, "en_progreso", "GRANTED", 3)
        assertNull(c.save(other, 3))
        // without a daily limit (no Gratuito plan row) any question of an attempt of theirs is revealable
        c.ejecutar("delete from public.beneficios_plan where plan_id = 'b1000000-0000-0000-0000-0000000030b1'; delete from public.planes_suscripcion where id = 'b1000000-0000-0000-0000-0000000030b1'")
        c.attempt(att3, user, newer, "en_progreso", "FREE", 3)
        assertNull(c.save(user, 3))
        // an already saved question still answers its original guardado_en (idempotent)
        assertNull(c.save(user, 4), "already saved: no new check")
    }

    @Test
    fun anUnusedReserveIsNotShownNorSaved() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar("begin; set local session_replication_role = replica; update public.preguntas_examen set es_reserva = true, orden_reserva = 1, posicion = 9 where examen_id = '$newer' and pregunta_id = '${q(3)}'; commit;")
        assertEquals("NOT_FOUND", c.field(c.detail(other, 3), "outcome"), "its only link is an unused reserve")
        assertEquals("false", c.unico("select accesible::text from public.app_list_saved('$other', null, null, null, 10)"))
        c.attempt(att1, other, newer, "finalizado", "FREE", 3)
        c.answer(att1, 3)
        val d = c.detail(other, 3)
        assertEquals("OK", c.field(d, "outcome"), "used (answered): shown")
        assertEquals(ok(3), c.field(d, "solucion,opcion_correcta_id"))
    }

    @Test
    fun theListCarriesTheExamTypeAndTheSameChoice() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar("update public.preguntas_examen set anulada = true, motivo_anulacion = 'x' where pregunta_id = '${q(1)}'")
        val rows = c.filas(
            "select target_id::text, accesible::text, coalesce(examen_id::text, '-'), coalesce(tipo_examen, '-') from public.app_list_saved('$user', null, null, null, 10) order by target_id",
        )
        assertEquals(
            listOf(listOf(q(1), "true", newer, "inmersivo"), listOf(q(2), "true", inmersivo, "inmersivo"), listOf(q(4), "false", "-", "-")),
            rows,
            "annulled everywhere = accesible (its detail says anulada); locked = not accesible",
        )
    }

    @Test
    fun theVerifyAndRollbackBehave() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(7, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("0", c.unico("select count(*)::text from pg_proc where proname in ('app_saved_question_detail', 'saved_question_resolved', 'saved_question_revealed')"))
        assertEquals("0", c.unico("select count(*)::text from pg_proc where proname = 'app_save_item' and prosrc like '%saved_question_revealed%'"), "the original save is back")
        assertEquals("3", c.unico("select count(*)::text from public.app_list_saved('$user', null, null, null, 10)"), "the original list is back")
        assertTrue(c.filas(verify).any { it[1] != "0" }, "VERIFY fails without the migration")
    }

    @Test
    fun withoutTheMigrationThereIsNoDetail() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        assertEquals("0", c.unico("select count(*)::text from pg_proc where proname = 'app_saved_question_detail'"))
        assertNull(c.unico("select string_agg(a, ',') from pg_proc p, unnest(p.proargnames) a where p.proname = 'app_list_saved' and a = 'tipo_examen'"))
    }
}
