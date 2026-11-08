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
 * `20261108100500_v1_exam_turno_acceso` (F15, owner 2026-11-08, FA45): optional `examenes.turno_acceso` (libre, promocion_interna,
 * estabilizacion, discapacidad; NULL = sin indicar) and the D2-13 collection read serving it. Full V1 schema; negative control without
 * the migration.
 */
class V1ExamTurnoAccesoMigrationTest {
    private val migration = "20261108100500_v1_exam_turno_acceso"
    private val user = "a0000000-0000-0000-0000-0000000f1501"
    private val esp = "e1000000-0000-0000-0000-0000000f1501"
    private val com = "c1000000-0000-0000-0000-0000000f1501"
    private val withRoute = "e5f15000-0000-0000-0000-000000000001"
    private val withoutRoute = "e5f15000-0000-0000-0000-000000000002"

    /** Two published exams granted to [user]: one with a route (set afterwards), one without. */
    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$user', 'f15@test.local', 'authenticated', 'authenticated');
        insert into public.especialidades (id, codigo, nombre, slug, activa) values ('$esp', 'F15', 'F15', 'f15', true);
        insert into public.comunidades_autonomas (id, codigo, nombre, slug) values ('$com', 'F15', 'Com F15', 'com-f15');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta, especialidad_principal_id)
          values ('$user', 'f15', 'f15_user', true, 'activa', '$esp')
          on conflict (usuario_id) do update set especialidad_principal_id = excluded.especialidad_principal_id;
        begin;
        set local session_replication_role = replica;
        insert into public.examenes (id, codigo_examen, especialidad_id, comunidad_id, titulo, tipo_examen, tipo_acceso, activo, publicado_en, anio)
          values ('$withRoute', 'F15-1', '$esp', '$com', 'Solucionario Madrid 2024', 'inmersivo', 'premium', true, now() - interval '1 day', 2024),
                 ('$withoutRoute', 'F15-2', '$esp', '$com', 'Rafaga', 'rafaga', 'gratuito', true, now() - interval '1 day', 2023);
        insert into public.contenido_desbloqueado_usuario (usuario_id, examen_id, motivo, valido_desde)
          values ('$user', '$withRoute', 'admin', now() - interval '2 hour'), ('$user', '$withoutRoute', 'admin', now() - interval '1 hour');
        commit;
    """.trimIndent()

    private fun Connection.errorState(sql: String): String? = try {
        ejecutar(sql); null
    } catch (e: SQLException) {
        e.sqlState
    }

    private fun Connection.collectionRoutes(): List<List<String?>> = filas(
        """select i -> 'examenes' ->> 'id', coalesce(i -> 'examenes' ->> 'turno_acceso', 'null'), (i -> 'examenes') ? 'turno_acceso'
             from jsonb_array_elements(public.app_my_exams_collection('$user', null, null, null, null, null, 21, false, null) -> 'items') i
            order by 1""",
    )

    @Test
    fun theColumnAcceptsTheFourCodesAndNullAndRejectsAnythingElse() = V1Db.escenario { c ->
        c.ejecutar(seed)
        assertEquals("null", c.unico("select coalesce(turno_acceso, 'null') from public.examenes where id = '$withRoute'"), "existing exams start as sin indicar")
        listOf("libre", "promocion_interna", "estabilizacion", "discapacidad").forEach { code ->
            assertNull(c.errorState("update public.examenes set turno_acceso = '$code' where id = '$withRoute'"), code)
            assertEquals(code, c.unico("select turno_acceso from public.examenes where id = '$withRoute'"))
        }
        assertNull(c.errorState("update public.examenes set turno_acceso = null where id = '$withRoute'"), "clearing = sin indicar")
        listOf("Libre", "promocion interna", "concurso_oposicion", "").forEach { bad ->
            assertEquals("23514", c.errorState("update public.examenes set turno_acceso = '$bad' where id = '$withRoute'"), "'$bad' is outside the closed set")
        }
        // the commercial tier is a different column and keeps its own vocabulary
        assertEquals("23514", c.errorState("update public.examenes set tipo_acceso = 'libre' where id = '$withRoute'"))
    }

    @Test
    fun theCollectionReadServesTheRouteOrNull() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar("update public.examenes set turno_acceso = 'promocion_interna' where id = '$withRoute'")
        assertEquals(
            listOf(listOf(withRoute, "promocion_interna", "t"), listOf(withoutRoute, "null", "t")),
            c.collectionRoutes().map { listOf(it[0], it[1], it[2]?.take(1)) },
        )
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackRestoresTheCollectionRead() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(5, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar("update public.examenes set turno_acceso = 'libre' where id = '$withRoute'")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("0", c.unico("select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'examenes' and column_name = 'turno_acceso'"))
        // the collection read still works after the rollback, without the key (the 20261022101000 body)
        assertEquals(listOf("f", "f"), c.collectionRoutes().map { it[2]?.take(1) })
        assertTrue(c.filas(verify).any { it[1] != "0" }, "VERIFY fails without the migration")
        // and the migration re-applies cleanly (idempotent forward)
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" }, "VERIFY = 0 after re-applying")
    }

    @Test
    fun negativeControlWithoutTheMigrationThereIsNoRoute() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        assertEquals("42703", c.errorState("update public.examenes set turno_acceso = 'libre' where id = '$withRoute'"), "no column")
        assertEquals(listOf("f", "f"), c.collectionRoutes().map { it[2]?.take(1) }, "the collection read has no turno_acceso key")
    }
}
