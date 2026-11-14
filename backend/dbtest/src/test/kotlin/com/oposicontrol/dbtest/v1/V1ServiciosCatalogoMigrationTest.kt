package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `20261114400000_v1_servicios_catalogo` (F28 step 1, FA52): servicios gets visible / posicion / seccion (one of the owner's 3 Market sections,
 * derived from the type when a write leaves it empty) and the consumable metadata (usos, duracion_horas, multiplicador), plus the PLACEHOLDER seed.
 */
class V1ServiciosCatalogoMigrationTest {
    private val migration = "20261114400000_v1_servicios_catalogo"
    private val dependientes = V1Db.pendientes.filter { it > migration && it.startsWith("2026111440") }.toSet()

    @Test
    fun existingRowsGetDerivedDefaultsAndTheSeedNeverOverwritesAControlRow() = V1Db.escenario(sin = setOf(migration) + dependientes) { c ->
        // rows Control created before the migration (MAIN today): one with the same code as a seed row
        c.ejecutar(
            """
            insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets, activo) values
              ('protector_racha', 'Mi protector', 'protector_racha', 99, false),
              ('tri', 'Triple', 'triple_oportunidad', 10, true),
              ('pot', 'Pot', 'potenciador', 10, true),
              ('tut', 'Tutoria', 'tutoria', null, false);
            """.trimIndent(),
        )
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        val rows = c.filas("select codigo, seccion, posicion::text, coalesce(usos::text, '-'), coalesce(multiplicador::text, '-'), coalesce(precio_tickets::text, '-'), activo::text, visible::text from public.servicios order by codigo")
            .associate { it[0]!! to it.drop(1).joinToString("|") }
        assertEquals("POTENCIADORES|10|1|-|99|false|true", rows["protector_racha"], "a Control row keeps its name/price/state: the seed never overwrites it")
        assertEquals("POTENCIADORES|60|2|-|10|true|true", rows["tri"], "triple: 2 uses, position by type")
        assertEquals("POTENCIADORES|40|1|2.00|10|true|true", rows["pot"], "a potenciador without a multiplier gets x2")
        assertEquals("SERVICIOS|100|-|-|-|false|true", rows["tut"], "a non-consumable has no uses")
        assertEquals("POTENCIADORES|60|1|4.00|90|true|true", rows["potenciador_x4"], "PLACEHOLDER seed from the client document")
        assertEquals("Mi protector", c.unico("select nombre from public.servicios where codigo = 'protector_racha'"))
        assertEquals("11", c.unico("select count(*) from public.servicios"), "4 existing + 7 seeded (protector_racha already existed)")
    }

    @Test
    fun theSeedTheSectionTriggerAndTheChecks() = V1Db.escenario { c ->
        val seed = c.filas("select codigo, tipo_servicio, precio_tickets::text, coalesce(usos::text, '-'), coalesce(multiplicador::text, '-'), seccion from public.servicios order by posicion, codigo")
            .map { it.joinToString("|") }
        assertEquals(
            listOf(
                "protector_racha|protector_racha|20|1|-|POTENCIADORES", "protector_tickets|protector_tickets|30|1|-|POTENCIADORES",
                "protector_dual|protector_dual|40|1|-|POTENCIADORES", "potenciador_x2|potenciador|30|1|2.00|POTENCIADORES",
                "potenciador_x3|potenciador|60|1|3.00|POTENCIADORES", "potenciador_x4|potenciador|90|1|4.00|POTENCIADORES",
                "doble_oportunidad|doble_oportunidad|30|1|-|POTENCIADORES", "triple_oportunidad|triple_oportunidad|50|2|-|POTENCIADORES",
            ),
            seed,
        )
        // an older Control upsert without seccion gets the section of its type; an explicit one is kept
        c.ejecutar("insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets) values ('papel', 'Papel', 'modo_papel', 5), ('car', 'Carta', 'carta_ccaa_comodin', 5)")
        c.ejecutar("insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets, seccion) values ('pr2', 'Premium', 'premium', 5, 'OPOSITICKETS')")
        assertEquals("SERVICIOS|POTENCIADORES|OPOSITICKETS", c.unico("select string_agg(seccion, '|' order by array_position(array['papel','car','pr2'], codigo)) from public.servicios where codigo in ('papel','car','pr2')"))
        c.ejecutar("update public.servicios set seccion = null where codigo = 'pr2'")
        assertEquals("SERVICIOS", c.unico("select seccion from public.servicios where codigo = 'pr2'"), "clearing it re-derives it")
        for ((sql, check) in listOf(
            "update public.servicios set seccion = 'OTRA' where codigo = 'papel'" to "servicios_seccion_check",
            "update public.servicios set usos = 0 where codigo = 'papel'" to "servicios_usos_check",
            "update public.servicios set duracion_horas = 0 where codigo = 'papel'" to "servicios_duracion_horas_check",
            "update public.servicios set multiplicador = 11 where codigo = 'papel'" to "servicios_multiplicador_check",
            "update public.servicios set multiplicador = 0.5 where codigo = 'papel'" to "servicios_multiplicador_check",
            "update public.servicios set posicion = -1 where codigo = 'papel'" to "servicios_posicion_check",
        )) {
            val e = runCatching { c.ejecutar(sql) }.exceptionOrNull()
            assertTrue(e?.message?.contains(check) == true, "$sql -> $check: $e")
        }
        c.ejecutar("update public.servicios set usos = 3, duracion_horas = 48, multiplicador = 2.5, visible = false, posicion = 7 where codigo = 'papel'")
        assertEquals("3|48|2.50|false|7", c.unico("select usos || '|' || duracion_horas || '|' || multiplicador || '|' || visible || '|' || posicion from public.servicios where codigo = 'papel'"))
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackRemovesIt() = V1Db.escenario(sin = dependientes) { c ->
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(6, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("0", c.unico("select count(*) from information_schema.columns where table_schema = 'public' and table_name = 'servicios' and column_name in ('visible','posicion','seccion','usos','duracion_horas','multiplicador')"))
        assertEquals("8", c.unico("select count(*) from public.servicios"), "the seeded rows are data and stay")
        // re-applying after the rollback converges again
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" })
    }
}
