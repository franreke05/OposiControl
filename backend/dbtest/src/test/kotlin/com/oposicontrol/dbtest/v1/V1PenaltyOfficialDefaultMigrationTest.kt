package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `20261022103400_v1_exam_penalty_official_default` (CERT-EXAMS-2026-10-21 B-03 (b); propietario 2026-10-22: la penalizacion se aplica
 * tambien a examenes NO oficiales). Un examen creado o pasado a oficial nace con la racional activada EN LA BASE, sin pisar nunca un
 * ajuste explicito; la regla sigue siendo un dato por examen congelado por intento. Esquema V1 completo; control negativo sin la migracion.
 */
class V1PenaltyOfficialDefaultMigrationTest {
    private val migration = "20261022103400_v1_exam_penalty_official_default"
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
    fun officialAndNonOfficialExamsAreBothPenalisedWithTheirOwnRule() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar("update public.examenes set penalizacion_racional = true where id = '$plain'")
        val a = c.start(1)
        c.playAndFinish(1, a)
        assertEquals("true|1.11|0.67", c.attempt(a), "official: the rational penalty applies")
        val b = c.start(2)
        c.playAndFinish(2, b)
        assertEquals("true|1.11|0.67", c.attempt(b), "non-official (owner 2026-10-22): the rational penalty applies too")
        // and the admin RPC accepts a penalty on a non-official exam (no restriction): the panel behaviour is unchanged
        assertEquals("true", c.unico("select (public.admin_set_exam_grading_rules('$admin', '$plain', 'NO_PUNTUA', 1, 3, false, 'regla del tribunal') ->> 'changed')"))
        assertEquals("false|false|1|3", c.examRule(plain))
        assertEquals(null, c.errorOf("update public.examenes set penalizacion_racional = false, penalizacion_num = 0, penalizacion_den = 1 where id = '$plain'"), "a direct PATCH still works")
    }

    @Test
    fun becomingOfficialSwitchesTheDefaultOnWithoutEverOverridingAnExplicitSetting() = V1Db.escenario { c ->
        c.ejecutar(seed)
        // created official -> ON in the same INSERT (the real create path of Control, triggers on)
        c.ejecutar(
            """insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, es_oficial)
               values ('e5b00000-0000-0000-0000-000000000003', 'V1P-3', '$esp', '$com', 'Nuevo oficial', 'rafaga', 'gratuito', false, true)""",
        )
        assertEquals("true|true|0|1", c.examRule("e5b00000-0000-0000-0000-000000000003"), "created official: default ON")
        // switched to official -> ON and audited; switched back -> the penalty is kept
        c.ejecutar("update public.examenes set es_oficial = true where id = '$plain'")
        assertEquals("true|true|0|1", c.examRule(plain), "switched to official: default ON")
        assertEquals("1", c.unico("select count(*) from public.auditoria_log where accion = 'examen_reglas_puntuacion_modificadas' and registro_id = '$plain'"), "audited")
        c.ejecutar("update public.examenes set es_oficial = false where id = '$plain'")
        assertEquals("false|true|0|1", c.examRule(plain), "leaving official changes nothing")
        // an explicit setting in the same statement always wins
        c.ejecutar("update public.examenes set penalizacion_racional = false where id = '$plain'")
        c.ejecutar("update public.examenes set es_oficial = true, penalizacion_num = 1, penalizacion_den = 3 where id = '$plain'")
        assertEquals("true|false|1|3", c.examRule(plain), "explicit fraction kept, no default on top")
        c.ejecutar("update public.examenes set es_oficial = false where id = '$plain'")
        c.ejecutar("update public.examenes set es_oficial = true, penalizacion_num = 0, penalizacion_den = 1 where id = '$plain'")
        assertEquals("true|false|0|1", c.examRule(plain), "explicit change to 0/1 kept, no default on top")
    }

    @Test
    fun theRuleIsFrozenPerAttemptWhateverHappensToTheExamAfterTheStart() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val a = c.start(1)
        assertEquals("true|null|null", c.attempt(a))
        c.ejecutar("update public.examenes set es_oficial = false, penalizacion_racional = false where id = '$official'")
        c.playAndFinish(1, a)
        assertEquals("true|1.11|0.67", c.attempt(a), "frozen at the first freeze: the attempt keeps the penalty")
        val b = c.start(1)
        c.playAndFinish(1, b)
        assertEquals("false|3.33|null", c.attempt(b), "a new attempt freezes the new (off) rule")
        c.ejecutar("update public.examenes set penalizacion_racional = true where id = '$official'")
        assertEquals("false|3.33|null", c.attempt(b), "a finished attempt never moves")
    }

    @Test
    fun negativeControlWithoutTheMigrationAnOfficialExamIsCreatedWithoutPenalty() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        c.ejecutar(
            """insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, es_oficial)
               values ('e5b00000-0000-0000-0000-000000000003', 'V1P-3', '$esp', '$com', 'Nuevo oficial', 'rafaga', 'gratuito', false, true)""",
        )
        assertEquals("true|false|0|1", c.examRule("e5b00000-0000-0000-0000-000000000003"), "the gap B-03 (b) closes: only a best-effort PATCH would turn it on")
        c.ejecutar("update public.examenes set es_oficial = true where id = '$plain'")
        assertEquals("true|false|0|1", c.examRule(plain), "and nothing happened on a later switch to official")
    }
}
