package com.oposicontrol.dbtest.slice7

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice3.Slice3Db
import com.oposicontrol.dbtest.unico
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SLICE 7: migracion 20261008100500 (indice keyset `(usuario_id, creado_en desc, id desc)` de `transacciones_cartera`, para
 * D6-02) sobre la forma de MAIN en el PostgreSQL embebido (nunca MAIN). No toca datos, politicas ni grants.
 */
class Slice7WalletLedgerIndexMigrationTest {

    private companion object {
        const val MIGRACION = "supabase/migrations/20261008100500_slice7_wallet_ledger_keyset_index.sql"
        const val INDICE = "idx_transacciones_cartera_usuario_creado_id"
        const val U = "a3000000-0000-0000-0000-00000000000a"
    }

    private fun existe(c: java.sql.Connection) = c.unico("select count(*) from pg_indexes where schemaname = 'public' and indexname = '$INDICE'")

    @Test
    fun appliesIsIdempotentVerifiesAtZeroAndTheRollbackDropsOnlyTheIndex() = Slice3Db.escenario(migraciones = false) { c ->
        val indicesAntes = c.unico("select string_agg(indexname, ',' order by indexname) from pg_indexes where schemaname = 'public' and tablename = 'transacciones_cartera'")
        assertEquals("0", existe(c))
        c.aplicar(MIGRACION)
        c.aplicar(MIGRACION) // idempotente
        assertEquals("1", existe(c))
        fun verify() = c.filas(Slice0Db.verifyDe(MIGRACION)).associate { it[0]!! to it[1]!! }
        assertEquals(mapOf("wallet_ledger_keyset_index_missing" to "0"), verify())
        assertTrue(
            c.unico("select indexdef from pg_indexes where indexname = '$INDICE'")!!.endsWith("(usuario_id, creado_en DESC, id DESC)"),
            "definicion exacta del indice keyset",
        )
        // El indice anterior (usuario_id) sigue: solo se AÑADE.
        assertEquals("1", c.unico("select count(*) from pg_indexes where indexname = 'idx_transacciones_cartera_usuario'"))

        c.ejecutar(Slice0Db.rollbackDe(MIGRACION))
        assertEquals("0", existe(c))
        assertEquals(mapOf("wallet_ledger_keyset_index_missing" to "1"), verify())
        assertEquals(indicesAntes, c.unico("select string_agg(indexname, ',' order by indexname) from pg_indexes where schemaname = 'public' and tablename = 'transacciones_cartera'"))
        c.aplicar(MIGRACION)
        assertEquals(mapOf("wallet_ledger_keyset_index_missing" to "0"), verify())
    }

    @Test
    fun theKeysetQueryOfTheWalletReadIsServedByTheIndexWithoutASort() = Slice3Db.escenario(migraciones = false) { c ->
        c.aplicar(MIGRACION)
        c.ejecutar("insert into auth.users (id, email) values ('$U', 'w@s7.local') on conflict do nothing")
        c.ejecutar(
            "insert into public.transacciones_cartera (usuario_id, tipo, cantidad, saldo_resultante, creado_en) " +
                "select '$U', 'recarga', 1, n, now() - (n || ' minutes')::interval from generate_series(1, 400) n",
        )
        c.ejecutar("analyze public.transacciones_cartera")
        c.ejecutar("set enable_seqscan = off; set enable_bitmapscan = off")
        val plan = c.filas(
            "explain select id from public.transacciones_cartera where usuario_id = '$U' " +
                "and (creado_en < now() or (creado_en = now() and id < gen_random_uuid())) order by creado_en desc, id desc limit 21",
        ).joinToString("\n") { it[0]!! }
        assertTrue(plan.contains(INDICE), plan)
        assertTrue(!plan.contains("Sort"), "el orden sale del indice, sin ordenar: $plan")
    }

    @Test
    fun anIndexWithTheSameNameButAnotherDefinitionStopsTheMigrationAndChangesNothing() = Slice3Db.escenario(migraciones = false) { c ->
        c.ejecutar("create index $INDICE on public.transacciones_cartera (usuario_id)")
        val error = runCatching { c.aplicar(MIGRACION) }.exceptionOrNull() as? SQLException
        assertNotNull(error, "debe detenerse")
        c.ejecutar("rollback")
        assertTrue(error.message.orEmpty().contains("WALLET_LEDGER_KEYSET_INDEX_PREFLIGHT"), error.message)
        assertEquals("(usuario_id)", c.unico("select right(indexdef, 12) from pg_indexes where indexname = '$INDICE'"), "no se toca")
    }

    @Test
    fun theMigrationStopsWithoutTheLedgerTable() = Slice3Db.escenario(migraciones = false) { c ->
        c.ejecutar("drop table public.transacciones_cartera")
        val error = runCatching { c.aplicar(MIGRACION) }.exceptionOrNull() as? SQLException
        assertNotNull(error)
        c.ejecutar("rollback")
        assertTrue(error.message.orEmpty().contains("WALLET_LEDGER_KEYSET_INDEX_PREFLIGHT"), error.message)
    }
}
