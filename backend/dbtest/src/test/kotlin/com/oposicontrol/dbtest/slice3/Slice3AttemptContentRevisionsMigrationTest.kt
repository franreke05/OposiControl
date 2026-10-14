package com.oposicontrol.dbtest.slice3

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.slice3.Slice3Db.EX
import com.oposicontrol.dbtest.slice3.Slice3Db.TAG
import com.oposicontrol.dbtest.slice3.Slice3Db.UA
import com.oposicontrol.dbtest.slice3.Slice3Db.UB
import com.oposicontrol.dbtest.slice3.Slice3Db.o
import com.oposicontrol.dbtest.slice3.Slice3Db.p
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Migracion 20261016100000 (decision del propietario 2026-10-14, versionado P0): un intento queda ligado a una revision
 * INMUTABLE y REUTILIZABLE de cada pregunta. Prueba obligatoria del dueno a nivel de base:
 * publish X -> start X -> modify/publish Y -> continuar -> preguntas X -> finish -> review X.
 */
class Slice3AttemptContentRevisionsMigrationTest {
    private companion object {
        const val FREEZE = "supabase/migrations/20261010100000_slice3_attempt_freeze_keeps_annulled.sql"
        const val MIGRACION = "supabase/migrations/20261016100000_slice3_attempt_content_revisions.sql"
        const val TAG2 = "7a300000-0000-0000-0000-000000000002"
        const val REC_IMG = "5e300000-0000-0000-0000-000000000001"
        const val REC_AUD = "5e300000-0000-0000-0000-000000000002"

        /**
         * `public.recursos_pregunta` existe en MAIN pero queda FUERA de `main_shape_subset.sql`. Forma copiada de
         * `supabase/canonical/dry_run_fixture/002_content_live_shape.sql` (forma viva de MAIN) + CHECK de Q3.
         */
        val RECURSOS = """
            create table public.recursos_pregunta (
              id uuid default gen_random_uuid() not null primary key,
              pregunta_id uuid not null references public.preguntas(id) on delete cascade,
              codigo_recurso text not null,
              tipo_recurso text not null check (tipo_recurso = any (array['imagen','audio','video','texto','pdf','enlace'])),
              titulo text, descripcion text,
              bucket_storage text default 'recursos-pregunta',
              ruta_storage text, url_externa text, contenido_texto text,
              posicion integer default 1 not null check (posicion > 0),
              activo boolean default true not null,
              metadatos jsonb default '{}'::jsonb not null,
              creado_en timestamptz default now() not null,
              actualizado_en timestamptz default now() not null);
            alter table public.recursos_pregunta enable row level security;
            revoke all on public.recursos_pregunta from anon, authenticated;
        """.trimIndent()

        fun ruta(pregunta: String, recurso: String, ext: String) = "preguntas/$pregunta/$recurso.$ext"
    }

    private fun Connection.preparar() {
        aplicar(FREEZE)
        ejecutar(RECURSOS)
        ejecutar(
            """
            set session_replication_role = replica;
            insert into public.recursos_pregunta (id, pregunta_id, codigo_recurso, tipo_recurso, titulo, ruta_storage, posicion, metadatos) values
              ('$REC_IMG', '${p(1)}', 'R-1', 'imagen', 'Plano', '${ruta(p(1), REC_IMG, "png")}', 1, '{"mime":"image/png","bytes":1200}'),
              ('$REC_AUD', '${p(1)}', 'R-2', 'audio', 'Llamada', '${ruta(p(1), REC_AUD, "mp3")}', 2, '{"mime":"audio/mpeg","bytes":48000}');
            insert into public.recursos_pregunta (pregunta_id, codigo_recurso, tipo_recurso, contenido_texto) values ('${p(1)}', 'R-3', 'texto', 'fuera de V1');
            insert into public.etiquetas (id, nombre, slug) values ('$TAG2', 'Tema 2', 's3-tema-2');
            set session_replication_role = origin;
            """.trimIndent(),
        )
        aplicar(MIGRACION)
    }

    /** Edicion de autoria simulada (los triggers de autoria de MAIN no estan en el subconjunto: replica, como la siembra). */
    private fun Connection.publicarY() = ejecutar(
        """
        set session_replication_role = replica;
        update public.preguntas set titulo = 'Pregunta 1 EDITADA' where id = '${p(1)}';
        update public.opciones_pregunta set texto_opcion = 'Texto Y' where pregunta_id = '${p(1)}';
        update public.soluciones_pregunta set opcion_correcta_id = '${o(2, 2)}', razonamiento = 'Y' where pregunta_id = '${p(2)}';
        update public.recursos_pregunta set activo = false where id = '$REC_AUD';
        delete from public.etiquetas_pregunta where pregunta_id = '${p(3)}';
        insert into public.etiquetas_pregunta (pregunta_id, etiqueta_id) values ('${p(3)}', '$TAG2');
        set session_replication_role = origin;
        """.trimIndent(),
    )

    private fun Connection.contenidoDe(intento: String, pregunta: String, campo: String): String? =
        unico(
            "select r.contenido ->> '$campo' from public.preguntas_intento pi join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id " +
                "where pi.intento_id = '$intento' and pi.pregunta_id = '$pregunta'",
        )

    @Test
    fun publishXStartXPublishYContinueFinishReviewX() = Slice3Db.escenario { c ->
        c.preparar()
        val (outcome, intento) = c.iniciar(UA, EX)
        assertEquals("CREATED", outcome)
        intento!!
        assertEquals("0", c.unico("select count(*) from public.preguntas_intento where intento_id = '$intento' and revision_pregunta_id is null"), "toda pregunta congelada queda ligada")
        assertEquals("5", c.unico("select count(*) from public.revisiones_pregunta"), "una revision por pregunta activa (4 evaluables + 1 anulada)")
        // Recursos V1 en la revision: imagen + audio activos, nunca el `texto`.
        assertEquals(
            listOf(listOf(REC_IMG, "imagen", "image/png"), listOf(REC_AUD, "audio", "audio/mpeg")),
            c.filas(
                "select x ->> 'id', x ->> 'tipo', x ->> 'mime' from public.preguntas_intento pi join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id, " +
                    "jsonb_array_elements(r.contenido -> 'recursos') x where pi.intento_id = '$intento' and pi.pregunta_id = '${p(1)}' order by (x ->> 'posicion')::int",
            ),
        )

        // El admin edita (Y): enunciado y opciones de 1, solucion de 2 (A -> B), un recurso de 1 archivado, etiqueta de 3.
        c.publicarY()

        // Continuar: 2 se corrige con la solucion de X (A), no con la viva (B).
        val r2 = c.responder(UA, intento, p(2), o(2, 1), 0)
        assertEquals("OK", r2["outcome"])
        assertEquals("ANSWERED", r2["estado"])
        val reveal = c.filas(
            "select r ->> 'es_correcta', r ->> 'opcion_correcta_id' from public.app_record_attempt_answer('$UA', '$intento', '${p(2)}', '${o(2, 1)}', 0, 1, null) as r",
        ).single()
        assertEquals(listOf("true", o(2, 1)), reveal, "el no-op de la misma opcion revela la correccion de X (una sola llamada)")
        assertEquals("true", c.unico("select es_correcta::text from public.respuestas_intento where intento_id = '$intento' and pregunta_id = '${p(2)}'"))
        // Responder 1 con la opcion B: X dice A, incorrecta.
        val r1 = c.filas(
            "select r ->> 'outcome', r ->> 'es_correcta', r ->> 'opcion_correcta_id' from public.app_record_attempt_answer('$UA', '$intento', '${p(1)}', '${o(1, 2)}', 0, 0, null) as r",
        ).single()
        assertEquals(listOf("OK", "false", o(1, 1)), r1)

        // Preguntas X: el intento sigue viendo el contenido de X.
        assertEquals("Pregunta 1", c.contenidoDe(intento, p(1), "titulo"))
        assertEquals(
            listOf(listOf("Opcion"), listOf("Opcion")),
            c.filas(
                "select x ->> 'texto' from public.preguntas_intento pi join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id, " +
                    "jsonb_array_elements(r.contenido -> 'opciones') x where pi.intento_id = '$intento' and pi.pregunta_id = '${p(1)}'",
            ),
        )
        assertEquals(
            "2",
            c.unico(
                "select jsonb_array_length(r.contenido -> 'recursos') from public.preguntas_intento pi join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id " +
                    "where pi.intento_id = '$intento' and pi.pregunta_id = '${p(1)}'",
            ),
            "el recurso archivado en Y sigue en X",
        )

        // Finish: estado congelado con la correccion de X y resumen por etiqueta con la etiqueta de X.
        assertEquals("OK", c.finalizar(UA, intento)["outcome"])
        assertEquals(
            listOf(listOf("1", "WRONG"), listOf("2", "CORRECT"), listOf("3", "UNANSWERED"), listOf("4", "UNANSWERED"), listOf("5", "ANNULLED")),
            c.filas("select posicion, estado_final from public.preguntas_intento where intento_id = '$intento' order by posicion"),
        )
        assertEquals(
            listOf(listOf(TAG, "4", "1", "1")),
            c.filas("select etiqueta_id, total_preguntas, correctas, falladas from public.resumen_intento_etiqueta where intento_id = '$intento'"),
            "3 cuenta en la etiqueta de X (TAG), no en la de Y (TAG2)",
        )

        // Review X: la solucion de 2 sigue siendo A y el enunciado de 1 el de X.
        assertEquals(
            o(2, 1),
            c.unico(
                "select r.solucion ->> 'opcion_correcta_id' from public.preguntas_intento pi join public.revisiones_pregunta r on r.id = pi.revision_pregunta_id " +
                    "where pi.intento_id = '$intento' and pi.pregunta_id = '${p(2)}'",
            ),
        )
        assertEquals("Pregunta 1", c.contenidoDe(intento, p(1), "titulo"))

        // Un intento NUEVO (otro usuario) ve Y; las preguntas sin cambios REUTILIZAN la revision (nada se copia por intento).
        val (_, nuevo) = c.iniciar(UB, EX)
        nuevo!!
        assertEquals("Pregunta 1 EDITADA", c.contenidoDe(nuevo, p(1), "titulo"))
        assertEquals("8", c.unico("select count(*) from public.revisiones_pregunta"), "5 de X + 3 nuevas (1, 2, 3 cambiaron)")
        val mismo = "select pi.revision_pregunta_id from public.preguntas_intento pi where pi.intento_id = '%s' and pi.pregunta_id = '${p(4)}'"
        assertEquals(c.unico(mismo.format(intento)), c.unico(mismo.format(nuevo)), "4 sin cambios: la misma fila")
        val p1 = "select pi.revision_pregunta_id from public.preguntas_intento pi where pi.intento_id = '%s' and pi.pregunta_id = '${p(1)}'"
        assertNotEquals(c.unico(p1.format(intento)), c.unico(p1.format(nuevo)))
        val y2 = c.filas(
            "select r ->> 'es_correcta', r ->> 'opcion_correcta_id' from public.app_record_attempt_answer('$UB', '$nuevo', '${p(2)}', '${o(2, 1)}', 0, 0, null) as r",
        ).single()
        assertEquals(listOf("false", o(2, 2)), y2, "el intento nuevo se corrige con Y")
    }

    @Test
    fun revisionsAndBindingsAreImmutableAndClosedToClients() = Slice3Db.escenario { c ->
        c.preparar()
        val intento = c.iniciar(UA, EX).second!!
        assertEquals("P0001", c.sqlStateDe("update public.revisiones_pregunta set contenido = '{}'::jsonb"))
        val otra = c.unico("select id from public.revisiones_pregunta where pregunta_id = '${p(2)}'")
        assertEquals(
            "P0001",
            c.sqlStateDe("update public.preguntas_intento set revision_pregunta_id = '$otra' where intento_id = '$intento' and pregunta_id = '${p(1)}'"),
        )
        assertEquals(
            listOf(listOf("false", "false", "true")),
            c.filas(
                "select has_table_privilege('anon', 'public.revisiones_pregunta', 'select')::text, has_table_privilege('authenticated', 'public.revisiones_pregunta', 'select')::text, " +
                    "has_table_privilege('service_role', 'public.revisiones_pregunta', 'select')::text",
            ),
        )
    }

    @Test
    fun attemptsFrozenBeforeTheMigrationKeepTheLiveBehaviour() = Slice3Db.escenario { c ->
        c.aplicar(FREEZE)
        c.ejecutar(RECURSOS)
        val viejo = c.iniciar(UA, EX).second!!
        c.aplicar(MIGRACION)
        assertNull(c.unico("select revision_pregunta_id from public.preguntas_intento where intento_id = '$viejo' and posicion = 1"))
        c.publicarY()
        val r = c.filas(
            "select r ->> 'es_correcta', r ->> 'opcion_correcta_id' from public.app_record_attempt_answer('$UA', '$viejo', '${p(2)}', '${o(2, 2)}', 0, 0, null) as r",
        ).single()
        assertEquals(listOf("true", o(2, 2)), r, "sin revision: solucion viva (comportamiento anterior)")
        assertEquals("OK", c.finalizar(UA, viejo)["outcome"])
    }

    @Test
    fun negativeControlWithoutTheMigrationAnEditChangesTheGradeOfAnOpenAttempt() = Slice3Db.escenario { c ->
        c.aplicar(FREEZE)
        val intento = c.iniciar(UA, EX).second!!
        c.ejecutar(RECURSOS)
        c.publicarY()
        c.responder(UA, intento, p(2), o(2, 1), 0)
        assertEquals(
            "false",
            c.unico("select es_correcta::text from public.respuestas_intento where intento_id = '$intento' and pregunta_id = '${p(2)}'"),
            "F3 reproducido: sin revisiones la correccion sigue a la edicion",
        )
    }

    @Test
    fun verifyIsCleanReapplyIsHarmlessAndRollbackFallsBackToLive() = Slice3Db.escenario { c ->
        c.preparar()
        val verify = c.filas(Slice0Db.verifyDe(MIGRACION))
        assertEquals(10, verify.size)
        verify.forEach { assertEquals("0", it[1], "VERIFY ${it[0]}") }
        c.aplicar(MIGRACION)
        val intento = c.iniciar(UA, EX).second!!
        assertNotNull(c.unico("select revision_pregunta_id from public.preguntas_intento where intento_id = '$intento' and posicion = 1"))
        c.ejecutar(Slice0Db.rollbackDe(MIGRACION))
        assertNull(c.unico("select to_regclass('public.revisiones_pregunta')"))
        // Tras el ROLLBACK se reaplican los cuerpos previos (20261008100200 + 20261010100000), como dice la cabecera.
        c.aplicar(Slice3Db.HARDENING)
        c.aplicar(FREEZE)
        val otro = c.iniciar(UB, EX)
        assertEquals("CREATED", otro.first)
    }
}
