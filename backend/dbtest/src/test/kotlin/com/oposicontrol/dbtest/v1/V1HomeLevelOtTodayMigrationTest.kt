package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `20261108100100_v1_home_level_ot_today` (F2, FA43): D3-04 `app_get_streak` also serves the Home LEVEL (`liga`) and the "OT hoy" gauge
 * (`otHoy` = free OT earned today / daily cap). Full V1 schema (every pending migration, as the PG17 gate); negative control without it.
 * "Today" is the Madrid day: the boundary is computed here INDEPENDENTLY of the private helpers (date_trunc in Europe/Madrid).
 */
class V1HomeLevelOtTodayMigrationTest {
    private val migration = "20261108100100_v1_home_level_ot_today"
    private val user = "a0000000-0000-0000-0000-0000000008a1"
    private val other = "a0000000-0000-0000-0000-0000000008a2"
    private val liga2 = "1e920000-0000-0000-0000-000000000002"

    private val seed = """
        insert into auth.users (id, email, aud, role) values ('$user', 'home-u@test.local', 'authenticated', 'authenticated'), ('$other', 'home-o@test.local', 'authenticated', 'authenticated');
        insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta)
          values ('$user', 'home', 'home_v1_user', true, 'activa'), ('$other', 'other', 'home_v1_other', true, 'activa') on conflict (usuario_id) do nothing;
        insert into public.ligas_nivel (id, codigo, nombre, preguntas_minimas, ventana_calculo, posicion)
          values ('1e920000-0000-0000-0000-000000000001', 'hl-1', 'Liga Bronce', 0, 5, 1), ('$liga2', 'hl-2', 'Liga Plata', 10, 8, 2);
    """.trimIndent()

    /** Start of TODAY in Madrid, computed without private.reto_today / reto_day_end. */
    private val madridMidnight = "(date_trunc('day', clock_timestamp() at time zone 'Europe/Madrid') at time zone 'Europe/Madrid')"

    private var key = 0

    /** A ledger row written directly (the test runs as the owner): the free-earn guard is opened for this statement only. */
    private fun Connection.ledger(usuario: String, tipo: String, cantidad: Int, creadoEn: String, referenciaTipo: String? = null) {
        key++
        ejecutar(
            """
            begin;
            select set_config('oc.free_earn_writer', 'on', true);
            insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, idempotency_key, creado_en)
              values ('$usuario', '$tipo', $cantidad, 'test', ${referenciaTipo?.let { "'$it'" } ?: "null"}, 'home-test-$key', $creadoEn);
            commit;
            """.trimIndent(),
        )
    }

    private fun Connection.streak(usuario: String = user): String = unico("select public.app_get_streak('$usuario')::text")!!
    private fun Connection.ganados(usuario: String = user): Int = unico("select (public.app_get_streak('$usuario') -> 'otHoy' ->> 'ganados')::int")!!.toInt()
    private fun Connection.tope(): String? = unico("select public.app_get_streak('$user') -> 'otHoy' ->> 'tope'")

    @Test
    fun theStreakReadKeepsItsKeysAndAddsLevelAndOtToday() = V1Db.escenario { c ->
        c.ejecutar(seed)
        // a user without league and without earnings: liga is an explicit null, otHoy = 0 / default cap 100
        assertEquals("""{"liga": null, "otHoy": {"tope": 100, "ganados": 0}, "actual": 0, "maxima": 0, "ultimoDiaSuperado": null}""", c.streak())
        c.ejecutar("insert into public.estado_competitivo_usuario (usuario_id, liga_nivel_id) values ('$user', '$liga2') on conflict (usuario_id) do update set liga_nivel_id = excluded.liga_nivel_id")
        assertEquals("""{"orden": 2, "codigo": "hl-2", "nombre": "Liga Plata"}""", c.unico("select (public.app_get_streak('$user') -> 'liga')::text"))
        // a competitive row WITHOUT a tier yet is still null
        c.ejecutar("insert into public.estado_competitivo_usuario (usuario_id) values ('$other') on conflict (usuario_id) do nothing")
        assertEquals("null", c.unico("select (public.app_get_streak('$other') -> 'liga')::text"))
        // the streak keys come from the same helper as before
        c.ejecutar(
            "insert into public.rachas_usuario (usuario_id, racha_actual_dias, mejor_racha_dias, ultima_actividad_fecha) " +
                "values ('$user', 3, 5, (clock_timestamp() at time zone 'Europe/Madrid')::date)",
        )
        assertEquals(
            c.unico("select private.reto_streak_json('$user', private.reto_today(clock_timestamp()))::text"),
            c.unico("select (public.app_get_streak('$user') - 'liga' - 'otHoy')::text"),
        )
        assertEquals("3", c.unico("select public.app_get_streak('$user') ->> 'actual'"))
    }

    @Test
    fun otTodayCountsOnlyFreeEarningsOfTheMadridDay() = V1Db.escenario { c ->
        c.ejecutar(seed)
        // the independent boundary assumes the seeded zone (20261022100900); a different zone must fail here, not silently
        assertEquals("Europe/Madrid", c.unico("select private.reto_tz()"))
        // the Madrid boundary: the last microsecond of yesterday is out, the first of today is in, tomorrow is out
        c.ledger(user, "reto_diario", 7, "$madridMidnight - interval '1 microsecond'")
        c.ledger(user, "reto_diario", 10, madridMidnight)
        c.ledger(user, "bonus_racha", 5, "$madridMidnight + interval '1 hour'")
        c.ledger(user, "referido", 20, "clock_timestamp()")
        c.ledger(user, "reporte_validado", 3, "clock_timestamp()", referenciaTipo = "admin") // counted: the cap counts it too
        c.ledger(user, "reto_diario", 99, "$madridMidnight + interval '1 day'")
        assertEquals(38, c.ganados(), "10 + 5 + 20 + 3; yesterday (7) and tomorrow (99) are out")
        // excluded ledger types: purchases, admin grants and adjustments, gifts, refunds and spending
        c.ledger(user, "recarga", 500, "clock_timestamp()")
        c.ledger(user, "pack_tickets", 100, "clock_timestamp()")
        c.ledger(user, "ajuste", 40, "clock_timestamp()", referenciaTipo = "admin")
        c.ledger(user, "regalo", 30, "clock_timestamp()")
        c.ledger(user, "gasto", -50, "clock_timestamp()")
        assertEquals(38, c.ganados(), "only reto_diario, bonus_racha, referido and reporte_validado count")
        // own data only
        c.ledger(other, "reto_diario", 15, "clock_timestamp()")
        assertEquals(38, c.ganados())
        assertEquals(15, c.ganados(other))
    }

    @Test
    fun theGaugeIsTheWindowTheEarnCapEnforcesAndDeferredRewardsAreNotEarned() = V1Db.escenario { c ->
        c.ejecutar(seed)
        c.ejecutar("insert into public.configuracion_v1 (clave, valor_integer) values ('tope_ot_gratis_dia', 50) on conflict (clave) do update set valor_integer = 50")
        assertEquals("50", c.tope())
        assertEquals("CREDITED", c.unico("select estado from public.app_credit_free_ot('$user', 'reto_diario', 30, 'reto', null, 'cap-1', '{}'::jsonb)"))
        assertEquals(30, c.ganados())
        // 30 + 25 > 50: the single earn function DEFERS the reward; it is not earned yet, the gauge stays at 30 of 50
        assertEquals("DEFERRED", c.unico("select estado from public.app_credit_free_ot('$user', 'bonus_racha', 25, 'reto', null, 'cap-2', '{}'::jsonb)"))
        assertEquals(30, c.ganados())
        assertEquals("CREDITED", c.unico("select estado from public.app_credit_free_ot('$user', 'referido', 20, 'invitacion', null, 'cap-3', '{}'::jsonb)"))
        assertEquals(50, c.ganados(), "exactly the cap: the next free OT of today would be deferred")
        assertEquals("DEFERRED", c.unico("select estado from public.app_credit_free_ot('$user', 'referido', 1, 'invitacion', null, 'cap-4', '{}'::jsonb)"))
        // tope = 0 means NO daily cap (as in free_ot_apply): null
        c.ejecutar("update public.configuracion_v1 set valor_integer = 0 where clave = 'tope_ot_gratis_dia'")
        assertEquals(null, c.tope())
        assertEquals("null", c.unico("select (public.app_get_streak('$user') -> 'otHoy' -> 'tope')::text"))
    }

    @Test
    fun theVerifyBlockProvesTheEndStateAndTheRollbackRestoresTheOldBody() = V1Db.escenario { c ->
        c.ejecutar(seed)
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(4, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        assertEquals("false|false|true", c.acl())
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("""{"actual": 0, "maxima": 0, "ultimoDiaSuperado": null}""", c.streak(), "the 20261022101200 body is back")
        assertEquals("false|false|true", c.acl(), "same ACL after the rollback")
        val after = c.filas(verify).filter { it[1] != "0" }.map { it[0] }
        assertTrue("streak_read_without_level_or_ot_today" in after && "ot_today_types_differ_from_the_earn_cap" in after, "after the rollback the VERIFY detects it: $after")
    }

    @Test
    fun withoutTheMigrationTheReadHasNoLevelNorOtToday() = V1Db.escenario(sin = setOf(migration)) { c ->
        c.ejecutar(seed)
        assertEquals("""{"actual": 0, "maxima": 0, "ultimoDiaSuperado": null}""", c.streak())
        assertNotEquals("0", c.filas(Slice0Db.verifyDe("supabase/migrations/$migration.sql")).first { it[0] == "streak_read_without_level_or_ot_today" }[1])
    }

    private fun Connection.acl(): String =
        filas(
            "select has_function_privilege('anon', 'public.app_get_streak(uuid)', 'execute')::text, has_function_privilege('authenticated', 'public.app_get_streak(uuid)', 'execute')::text, " +
                "has_function_privilege('service_role', 'public.app_get_streak(uuid)', 'execute')::text",
        ).single().joinToString("|")
}
