package com.oposicontrol.dbtest.v1

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `20261114400100_v1_servicios_canje` (F28 step 2, FA52): the app Market in at most three sections (`app_market_catalog`) and redeeming a service with
 * OposiTickets (`app_redeem_service`): ledger debit + activation in ONE transaction under the wallet lock, replay by (user, key), refusals write nothing.
 */
class V1ServiciosCanjeMigrationTest {
    private val migration = "20261114400100_v1_servicios_canje"
    private val dependientes = V1Db.pendientes.filter { it > migration && it.startsWith("2026111440") }.toSet()
    private val u = "a0000000-0000-0000-0000-0000000028a1"
    private val other = "a0000000-0000-0000-0000-0000000028b1"

    private fun Connection.setup(saldo: Int = 100) {
        ejecutar(
            """
            insert into auth.users (id, email, aud, role, email_confirmed_at) values ('$u', 'f28-a@test.local', 'authenticated', 'authenticated', now()),
              ('$other', 'f28-b@test.local', 'authenticated', 'authenticated', now());
            insert into public.perfiles_usuario (usuario_id, nombre_mostrar, username, onboarding_completado, estado_cuenta) values
              ('$u', 'a', 'f28_a', true, 'activa'), ('$other', 'b', 'f28_b', true, 'activa') on conflict (usuario_id) do nothing;
            insert into public.transacciones_cartera (usuario_id, tipo, cantidad, descripcion, referencia_tipo, idempotency_key) values ('$u', 'ajuste', $saldo, 'saldo inicial', 'admin', 'f28-seed-$u');
            """.trimIndent(),
        )
    }

    private fun Connection.saldo(user: String = u): String = unico("select coalesce((select saldo_tickets from public.carteras_usuario where usuario_id = '$user'), 0)::text")!!

    private fun Connection.redeem(codigo: String, key: String, expected: Int? = null, user: String = u): List<String?> =
        filas("select outcome, transaccion_id::text, saldo::text, activacion::text from public.app_redeem_service('$user', '$codigo', '$key', ${expected ?: "null"})").single()

    private fun Connection.refused(codigo: String, key: String, expected: Int? = null, user: String = u): String =
        runCatching { redeem(codigo, key, expected, user) }.exceptionOrNull()?.message ?: "NO ERROR"

    private fun Connection.j(json: String?, path: String): String? = unico("select ('${json!!.replace("'", "''")}'::jsonb) #>> '{$path}'")

    private fun Connection.catalog(user: String = u): String = unico("select public.app_market_catalog('$user')::text")!!

    @Test
    fun theCatalogHasTheThreeSectionsInOrderWithOnlyActiveVisibleItemsAndTheRemainingUses() = V1Db.escenario { c ->
        c.setup()
        c.ejecutar(
            """
            insert into public.productos_comerciales (codigo, tipo_producto, nombre, precio_centimos, moneda, cantidad_tickets, activo, visible, posicion) values
              ('pack_2600', 'pack_tickets', '2.600 OT', 2399, 'EUR', 2600, true, true, 2), ('pack_1300', 'pack_tickets', '1.300 OT', 1299, 'EUR', 1300, true, true, 1),
              ('pack_oculto', 'pack_tickets', 'x', 999, 'EUR', 100, true, false, 3), ('pack_sin_precio', 'pack_tickets', 'x', null, 'EUR', 100, true, true, 4);
            insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets, precio_centimos, activo, visible, posicion) values
              ('tutoria_1h', 'Tutoria', 'tutoria', 500, 1999, true, true, 1), ('papel', 'Modo papel', 'modo_papel', 15, null, true, true, 2),
              ('oculto', 'Oculto', 'protector_racha', 5, null, true, false, 1), ('inactivo', 'Inactivo', 'protector_racha', 5, null, false, true, 1);
            update public.servicios set seccion = 'OPOSITICKETS', posicion = 0 where codigo = 'papel';
            """.trimIndent(),
        )
        val cat = c.catalog()
        val sections = c.filas("select s ->> 'codigo', s ->> 'titulo', (select string_agg(i ->> 'codigo', ',') from jsonb_array_elements(s -> 'items') i) from jsonb_array_elements(('${cat.replace("'", "''")}'::jsonb) -> 'secciones') s")
            .map { it.joinToString("|") }
        assertEquals(
            listOf(
                "OPOSITICKETS|OposiTickets|pack_1300,pack_2600,papel",
                "POTENCIADORES|Potenciadores y protecciones|protector_racha,protector_tickets,protector_dual,potenciador_x2,potenciador_x3,potenciador_x4,doble_oportunidad,triple_oportunidad",
                "SERVICIOS|Servicios y accesos|tutoria_1h",
            ),
            sections,
            "fixed section order; packs first then a service Control filed under OPOSITICKETS; hidden / inactive / unpriced never",
        )
        assertEquals("1300|false|0|pack_tickets", listOf("0,items,0,cantidadTickets", "0,items,0,comprableConTickets", "0,items,0,disponibles", "0,items,0,tipo").joinToString("|") { c.j(cat, "secciones,$it")!! })
        assertEquals("false|500|1999", listOf("comprableConTickets", "precioTickets", "precioCentimos").joinToString("|") { c.j(cat, "secciones,2,items,0,$it")!! }, "tutoria is listed but not redeemed with OT")
        assertEquals("true", c.j(cat, "secciones,0,items,2,comprableConTickets"), "modo papel: purchasable (inert in 1.3)")
        assertEquals("4", c.j(cat, "secciones,1,items,5,multiplicador"))
        assertEquals("2", c.j(cat, "secciones,1,items,7,usos"))
        // buying twice adds up
        c.redeem("triple_oportunidad", "k-cat-1")
        c.redeem("triple_oportunidad", "k-cat-2")
        assertEquals("4", c.j(c.catalog(), "secciones,1,items,7,disponibles"), "2 activations x 2 uses")
        assertEquals("0", c.j(c.catalog(other), "secciones,1,items,7,disponibles"), "another user sees only their own")
        // an empty market is valid
        c.ejecutar("update public.servicios set visible = false; update public.productos_comerciales set activo = false")
        assertEquals("""{"secciones": []}""", c.catalog())
    }

    @Test
    fun aPurchaseDebitsOnceCreatesTheActivationAndTheSameKeyReplays() = V1Db.escenario { c ->
        c.setup()
        val first = c.redeem("protector_racha", "k1", expected = 20)
        assertEquals("CREATED|80", "${first[0]}|${first[2]}")
        val act = first[3]
        assertEquals("protector_racha|protector_racha|activa|1", listOf("servicioCodigo", "tipo", "estado", "usosRestantes").joinToString("|") { c.j(act, it)!! })
        assertEquals(null, c.j(act, "activaHasta"), "no duration = no expiry")
        assertEquals("compra_servicio|-20|servicio|80", c.filas("select tipo, cantidad::text, referencia_tipo, saldo_resultante::text from public.transacciones_cartera where usuario_id = '$u' and idempotency_key = 'k1'").single().joinToString("|"))
        assertEquals(
            "1|0|activa|${first[1]}",
            c.filas("select usos_disponibles::text, usos_consumidos::text, estado, metadatos ->> 'transaccion_id' from public.activaciones_servicio_usuario where id = '${c.j(act, "id")}'").single().joinToString("|"),
        )
        // replay: the same transaction, no second debit
        val again = c.redeem("protector_racha", "k1", expected = 20)
        assertEquals(listOf("REPLAY", first[1], "80"), again.take(3))
        assertEquals(c.j(act, "id"), c.j(again[3], "id"))
        assertEquals("1", c.unico("select count(*) from public.transacciones_cartera where usuario_id = '$u' and tipo = 'compra_servicio'"))
        assertEquals("1", c.unico("select count(*) from public.activaciones_servicio_usuario where usuario_id = '$u'"))
        // the same key for another body or another operation
        assertTrue(c.refused("protector_tickets", "k1").contains("IDEMPOTENCY_KEY_REUSED"))
        assertTrue(c.refused("protector_racha", "k1", expected = 21).contains("IDEMPOTENCY_KEY_REUSED"))
        assertTrue(c.refused("protector_racha", "f28-seed-$u").contains("IDEMPOTENCY_KEY_REUSED"), "a key used by another ledger operation")
        // usos / duracion / multiplicador frozen at purchase
        c.ejecutar("update public.servicios set duracion_horas = 24 where codigo = 'potenciador_x2'")
        val pot = c.redeem("potenciador_x2", "k2")
        assertEquals("2", c.j(pot[3], "multiplicador"))
        assertEquals("true", c.unico("select (activa_hasta between now() + interval '23 hours 59 minutes' and now() + interval '24 hours 1 minute')::text from public.activaciones_servicio_usuario where id = '${c.j(pot[3], "id")}'"))
        assertEquals("2", c.j(c.redeem("triple_oportunidad", "k3")[3], "usosRestantes"))
        assertEquals("0", c.saldo(), "100 - 20 - 30 - 50")
    }

    @Test
    fun refusalsWriteNothing() = V1Db.escenario { c ->
        c.setup(saldo = 50)
        c.ejecutar(
            """
            insert into public.servicios (codigo, nombre, tipo_servicio, precio_tickets, activo, visible) values
              ('tutoria_1h', 'Tutoria', 'tutoria', 10, true, true), ('oculto', 'Oculto', 'protector_racha', 5, true, false),
              ('gratis', 'Gratis', 'protector_racha', 0, true, true), ('dinero', 'Dinero', 'protector_racha', null, true, true);
            update public.servicios set precio_centimos = 199 where codigo = 'dinero';
            """.trimIndent(),
        )
        val before = c.unico("select count(*) from public.transacciones_cartera") to c.unico("select count(*) from public.activaciones_servicio_usuario")
        val insufficient = c.refused("potenciador_x4", "r1")
        assertTrue(insufficient.contains("INSUFFICIENT_BALANCE") && insufficient.contains("\"balance\":50") && insufficient.contains("\"price\":90"), insufficient)
        assertTrue(c.refused("protector_racha", "r2", expected = 25).let { it.contains("PRICE_CHANGED") && it.contains("\"price\":20") })
        assertTrue(c.refused("tutoria_1h", "r3").contains("SERVICE_NOT_PURCHASABLE"), "premium/correccion/tutoria/contenido_extra are not redeemed with OT")
        assertTrue(c.refused("gratis", "r4").contains("SERVICE_NOT_PURCHASABLE"), "a 0 OT price is not a free giveaway")
        assertTrue(c.refused("dinero", "r5").contains("SERVICE_NOT_PURCHASABLE"), "money only")
        assertTrue(c.refused("oculto", "r6").contains("SERVICE_NOT_FOUND"), "hidden = not on sale")
        assertTrue(c.refused("no_existe", "r7").contains("SERVICE_NOT_FOUND"))
        c.ejecutar("update public.servicios set activo = false where codigo = 'protector_racha'")
        assertTrue(c.refused("protector_racha", "r8").contains("SERVICE_NOT_FOUND"), "inactive = the same answer")
        assertTrue(c.refused("protector_tickets", " ").contains("IDEMPOTENCY_KEY_REQUIRED"))
        assertTrue(c.refused("protector_tickets", "r9", user = "a0000000-0000-0000-0000-00000000dead").contains("WALLET_INVALID_USER"))
        assertEquals(before, c.unico("select count(*) from public.transacciones_cartera") to c.unico("select count(*) from public.activaciones_servicio_usuario"))
        assertEquals("50", c.saldo())
        // exactly the balance is enough
        assertEquals("CREATED|10", c.redeem("protector_dual", "r10").let { "${it[0]}|${it[2]}" })
    }

    @Test
    fun theVerifyBlockAndTheRollback() = V1Db.escenario(sin = dependientes) { c ->
        val verify = Slice0Db.verifyDe("supabase/migrations/$migration.sql")
        val rows = c.filas(verify)
        assertEquals(4, rows.size, "VERIFY rows: $rows")
        assertEquals(emptyList(), rows.filter { it[1] != "0" }, "every VERIFY counter is 0")
        assertEquals(
            "falsefalse;falsefalse",
            c.filas("select has_function_privilege('anon', f, 'execute')::text || has_function_privilege('authenticated', f, 'execute')::text from (values ('public.app_redeem_service(uuid,text,text,integer)'), ('public.app_market_catalog(uuid)')) x(f)")
                .joinToString(";") { it[0]!! },
        )
        c.ejecutar(Slice0Db.rollbackDe("supabase/migrations/$migration.sql"))
        assertEquals("0", c.unico("select count(*) from pg_proc where proname in ('app_redeem_service', 'app_market_catalog', 'servicio_disponibles', 'servicio_comprable', 'servicio_activacion_json')"))
        c.ejecutar(Slice0Db.leer("supabase/migrations/$migration.sql"))
        assertEquals(emptyList(), c.filas(verify).filter { it[1] != "0" }, "re-applying converges")
    }
}
