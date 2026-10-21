package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `20261022103400_v1_exam_penalty_official_scope` (propietario 2026-10-17; CERT-EXAMS-2026-10-21 B-03): la penalizacion
 * (PD = A - E/(n-1), o una fraccion fija) existe SOLO en examenes OFICIALES, es un dato por examen y se CONGELA por intento.
 * Sobre el esquema V1 completo (todas las pendientes) con el motor real; control negativo: sin esta migracion, el defecto vuelve.
 */
class V1PenaltyOfficialScopeMigrationTest {
    private val migration = "20261022103400_v1_exam_penalty_official_scope"
    private val user = "a0000000-0000-0000-0000-0000000005b1"
    private val admin = "a0000000-0000-0000-0000-0000000005b2"
    private val esp = "e1000000-0000-0000-0000-0000000005b1"
    private val com = "c1000000-0000-0000-0000-0000000005b1"
    private val official = "e5b00000-0000-0000-0000-000000000001"
    private val plain = "e5b00000-0000-0000-0000-000000000002"
    private fun q(e: Int, n: Int) = "f5b00000-0000-0000-000$e-00000000000$n"
    private fun opt(e: Int, n: Int, letra: Int) = "0a5b0000-0000-000$e-000$n-00000000000$letra"
    private fun exam(e: Int) = if (e == 1) official else plain

    /** Dos examenes publicados (1 oficial con la racional, 2 no oficial sin penalizacion), 3 preguntas de 4 opciones (A correcta). */
    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$user', 'pen-u@test.local', 'authenticated', 'authenticated'), ('$admin', 'pen-a@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'V1P', 'V1P', 'v1p', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'V1P', 'Com V1P', 'com-v1p');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$user', 'pen', 'pen_v1_user', true, 'activa', '$esp'), ('$admin', 'adm', 'pen_v1_admin', true, 'activa', '$esp')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        insert into public.roles_app (codigo, nombre) select 'admin', 'Admin' where not exists (select 1 from public.roles_app where codigo = 'admin');
        insert into public.roles_usuario (usuario_id, rol_id) select '$admin', id from public.roles_app where codigo = 'admin';
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, regla_anuladas, es_oficial, penalizacion_racional)
          values ('$official', 'V1P-1', '$esp', '$com', 'Oficial', 'rafaga', 'gratuito', true, now() - interval '1 day', 'NO_PUNTUA', true, true),
                 ('$plain', 'V1P-2', '$esp', '$com', 'Rafaga', 'rafaga', 'gratuito', true, now() - interval '1 day', 'NO_PUNTUA', false, false);
        insert into public.preguntas (id, codigo_pregunta, tipo_pregunta, titulo, activa)
          select ('f5b00000-0000-0000-000' || e || '-00000000000' || n)::uuid, 'V1P-' || e || '-' || n, 'rafaga', 'Pregunta', true from generate_series(1, 2) e, generate_series(1, 3) n;
        insert into public.opciones_pregunta (id, pregunta_id, letra, texto_opcion, posicion)
          select ('0a5b0000-0000-000' || e || '-000' || n || '-00000000000' || o)::uuid, ('f5b00000-0000-0000-000' || e || '-00000000000' || n)::uuid, chr(64 + o), 'Opcion', o
          from generate_series(1, 2) e, generate_series(1, 3) n, generate_series(1, 4) o;
        insert into public.soluciones_pregunta (pregunta_id, opcion_correcta_id, razonamiento)
          select ('f5b00000-0000-0000-000' || e || '-00000000000' || n)::uuid, ('0a5b0000-0000-000' || e || '-000' || n || '-000000000001')::uuid, 'x'
          from generate_series(1, 2) e, generate_series(1, 3) n;
        insert into public.preguntas_examen (examen_id, pregunta_id, posicion, numero_en_examen)
          select case when e = 1 then '$official'::uuid else '$plain'::uuid end, ('f5b00000-0000-0000-000' || e || '-00000000000' || n)::uuid, n, n
          from generate_series(1, 2) e, generate_series(1, 3) n;
        commit;
    """.trimIndent()

    private fun Connection.start(e: Int): String = unico("select public.app_start_exam_attempt('$user', '${exam(e)}') ->> 'intento_id'")!!

    /** 1 acierto + 2 fallos de 4 opciones y entrega: con la racional, PD = 1 - 2/3 = 1/3 sobre 3 -> 1.11; sin ella 3.33. */
    private fun Connection.playAndFinish(e: Int, attempt: String) {
        listOf(1 to 1, 2 to 2, 3 to 2).forEach { (n, letra) ->
            ejecutar("select public.app_record_attempt_answer('$user', '$attempt', '${q(e, n)}', '${opt(e, n, letra)}', 1000, 0, null)")
        }
        ejecutar("select public.app_finish_exam_attempt('$user', '$attempt')")
    }

    private fun Connection.attempt(id: String): String =
        filas("select coalesce(penalizacion_racional::text, 'null'), nota::text, coalesce(penalizacion_descuento::text, 'null') from public.intentos_examen where id = '$id'")
            .single().joinToString("|")

    private fun Connection.examRule(id: String): String =
        filas("select es_oficial::text, penalizacion_racional::text, penalizacion_num::text, penalizacion_den::text from public.examenes where id = '$id'").single().joinToString("|")

    private fun Connection.errorOf(sql: String): String? = try {
        ejecutar(sql); null
    } catch (e: SQLException) {
        (e as? org.postgresql.util.PSQLException)?.serverErrorMessage?.message ?: e.message
    }

    @Test
    fun anOfficialExamIsPenalisedAndANonOfficialOneNever() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val a = c.start(1)
        c.playAndFinish(1, a)
        assertEquals("true|1.11|0.67", c.attempt(a), "official: the rational penalty applies")
        val b = c.start(2)
        c.playAndFinish(2, b)
        assertEquals("false|3.33|null", c.attempt(b), "non-official: never penalised")
    }

    @Test
    fun aPenaltyCannotBeWrittenOnANonOfficialExamThroughAnyDoor() = V1Db.escenario { c ->
        c.ejecutar(seed)
        assertEquals("GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM", c.errorOf("update public.examenes set penalizacion_racional = true where id = '$plain'"), "direct PATCH (DEBT-159 fallback)")
        assertEquals("GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM", c.errorOf("update public.examenes set penalizacion_num = 1, penalizacion_den = 3 where id = '$plain'"), "a fixed fraction too")
        assertEquals(
            "GRADING_PENALTY_REQUIRES_OFFICIAL_EXAM",
            c.errorOf("select public.admin_set_exam_grading_rules('$admin', '$plain', null, 0, 1, true, 'regla del tribunal')"),
            "the admin RPC answers with its own code",
        )
        // the CHECK holds even with triggers off
        assertEquals(
            "23514",
            c.sqlStateDe("begin; set local session_replication_role = replica; update public.examenes set penalizacion_racional = true where id = '$plain'; commit;")
                .also { c.ejecutar("rollback") },
        )
        assertEquals("false|false|0|1", c.examRule(plain), "nothing was stored")
        // an official exam keeps accepting both kinds of rule through the RPC
        assertEquals("true", c.unico("select (public.admin_set_exam_grading_rules('$admin', '$official', 'NO_PUNTUA', 1, 3, false, 'regla del tribunal') ->> 'changed')"))
        assertEquals("true|false|1|3", c.examRule(official))
    }

    @Test
    fun becomingOfficialSwitchesTheDefaultOnAndLeavingItClearsItWhileFrozenAttemptsKeepTheirRule() = V1Db.escenario { c ->
        c.ejecutar(seed)
        // created official -> ON; created non-official -> off (no replica: the real INSERT path of Control)
        c.ejecutar(
            """insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, es_oficial)
               values ('e5b00000-0000-0000-0000-000000000003', 'V1P-3', '$esp', '$com', 'Nuevo oficial', 'rafaga', 'gratuito', false, true)""",
        )
        assertEquals("true|true|0|1", c.examRule("e5b00000-0000-0000-0000-000000000003"), "created official: default ON in the same INSERT")
        c.ejecutar("update public.examenes set es_oficial = true where id = '$plain'")
        assertEquals("true|true|0|1", c.examRule(plain), "switched to official: default ON")
        c.ejecutar("update public.examenes set es_oficial = false where id = '$plain'")
        assertEquals("false|false|0|1", c.examRule(plain), "switched back: cleared")
        assertEquals(
            "2",
            c.unico("select count(*) from public.auditoria_log where accion = 'examen_reglas_puntuacion_modificadas' and registro_id = '$plain'"),
            "both implicit changes are audited",
        )

        // frozen per attempt: started while official, the exam is un-officialised mid-attempt
        val a = c.start(1)
        assertEquals("true|null|null", c.attempt(a))
        c.ejecutar("update public.examenes set es_oficial = false where id = '$official'")
        assertEquals("false|false|0|1", c.examRule(official))
        c.playAndFinish(1, a)
        assertEquals("true|1.11|0.67", c.attempt(a), "the frozen penalty still applies to the attempt started while official")
        val b = c.start(1)
        c.playAndFinish(1, b)
        assertEquals("false|3.33|null", c.attempt(b), "a new attempt on the now non-official exam is never penalised")
        // whoever writes the snapshot of a non-official exam, no penalty is frozen (an open attempt, its snapshot reset and written again)
        val open = c.start(1)
        c.ejecutar("update public.intentos_examen set penalizacion_racional = null, penalizacion_num = null, penalizacion_den = null where id = '$open'")
        c.ejecutar("update public.intentos_examen set penalizacion_racional = true, penalizacion_num = 1, penalizacion_den = 3 where id = '$open'")
        assertEquals(
            "false|0|1",
            c.filas("select penalizacion_racional::text, penalizacion_num::text, penalizacion_den::text from public.intentos_examen where id = '$open'").single().joinToString("|"),
            "a first freeze on a non-official exam stores no penalty",
        )
        assertEquals("true", c.unico("select penalizacion_racional::text from public.intentos_examen where id = '$a'"), "the attempt frozen while official never changes")
    }

    @Test
    fun negativeControlWithoutTheMigrationANonOfficialExamIsPenalised() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        c.ejecutar("update public.examenes set penalizacion_racional = true where id = '$plain'")
        val b = c.start(2)
        c.playAndFinish(2, b)
        assertEquals("true|1.11|0.67", c.attempt(b), "the defect the migration closes: a non-official rafaga is penalised")
        assertTrue(c.errorOf("select public.admin_set_exam_grading_rules('$admin', '$plain', null, 0, 1, true, 'regla del tribunal')") == null, "and the RPC accepted it")
    }
}
