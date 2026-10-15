package com.oposicontrol.dbtest.security

import com.oposicontrol.dbtest.ejecutar
import com.oposicontrol.dbtest.slice0.Slice0Db
import com.oposicontrol.dbtest.slice0.aplicar
import com.oposicontrol.dbtest.slice0.filas
import com.oposicontrol.dbtest.slice0.sqlStateDe
import com.oposicontrol.dbtest.slice1.Slice1Db
import com.oposicontrol.dbtest.unico
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Migracion 20261021100000: `public.auditoria_log` pasa a ser de SOLO ANADIR. Sobre la forma de MAIN de la tabla (la de
 * [Slice1Db], con su FK `usuario_id -> auth.users ON DELETE SET NULL`).
 *
 * Control NEGATIVO (sin la migracion `service_role` puede reescribir y borrar filas), despues: UPDATE / DELETE / TRUNCATE
 * rechazados para `service_role` y para el propietario, el INSERT sigue igual, y la UNICA transicion admitida es la que
 * hace la propia FK al borrar el usuario de Auth (actor -> null, resto intacto). Anonimizar a mano el actor de una fila
 * cuyo usuario sigue existiendo NO se admite (borraria quien hizo que). El bloque VERIFY da 0 en todo.
 */
class AuditLogAppendOnlyMigrationTest {
    private val migracion = "supabase/migrations/20261021100000_security_audit_log_append_only.sql"
    private val actor = "cccccccc-0000-4000-8000-0000000000c3"
    private val fila = "dddddddd-0000-4000-8000-0000000000d4"

    private fun <T> escenario(conMigracion: Boolean = true, bloque: (Connection) -> T): T =
        Slice1Db.escenario(migraciones = false) { c ->
            c.ejecutar("insert into auth.users (id, email) values ('$actor', 'actor-audit@test.local')")
            c.ejecutar(
                "insert into public.auditoria_log (id, usuario_id, accion, tabla_afectada, despues) " +
                    "values ('$fila', '$actor', 'producto_comercial_guardado', 'productos_comerciales', '{\"a\":1}')",
            )
            if (conMigracion) c.aplicar(migracion)
            bloque(c)
        }

    private fun Connection.comoServiceRole(sql: String): String? {
        ejecutar("set role service_role")
        return try {
            sqlStateDe(sql)
        } finally {
            ejecutar("reset role")
        }
    }

    private fun Connection.verify(): Map<String, Long> =
        filas(Slice0Db.verifyDe(migracion)).associate { it[0]!! to it[1]!!.toLong() }

    @Test
    fun sinLaMigracionServiceRolePuedeReescribirYBorrarLaAuditoria(): Unit = escenario(conMigracion = false) { c ->
        assertNull(c.comoServiceRole("update public.auditoria_log set accion = 'falsificada' where id = '$fila'"))
        assertNull(c.comoServiceRole("delete from public.auditoria_log where id = '$fila'"))
    }

    @Test
    fun conLaMigracionNadiePuedeReescribirBorrarNiVaciarLaAuditoria(): Unit = escenario { c ->
        assertEquals("P0001", c.comoServiceRole("update public.auditoria_log set accion = 'falsificada' where id = '$fila'"))
        assertEquals("P0001", c.comoServiceRole("update public.auditoria_log set despues = '{}' where id = '$fila'"))
        assertEquals("P0001", c.comoServiceRole("delete from public.auditoria_log where id = '$fila'"))
        assertEquals("P0001", c.comoServiceRole("truncate public.auditoria_log"))
        // Tampoco el propietario (las migraciones y el SQL de soporte corren asi).
        assertEquals("P0001", c.sqlStateDe("update public.auditoria_log set accion = 'falsificada' where id = '$fila'"))
        assertEquals("P0001", c.sqlStateDe("delete from public.auditoria_log"))
        assertEquals("P0001", c.sqlStateDe("truncate public.auditoria_log"))
        assertEquals("producto_comercial_guardado", c.unico("select accion from public.auditoria_log where id = '$fila'"))
    }

    @Test
    fun anadirFilasSigueFuncionando(): Unit = escenario { c ->
        assertNull(
            c.comoServiceRole(
                "insert into public.auditoria_log (usuario_id, accion, tabla_afectada) values ('$actor', 'otra_accion', 'x')",
            ),
        )
        assertEquals("2", c.unico("select count(*)::text from public.auditoria_log"))
    }

    /** La FK `ON DELETE SET NULL` del actor sigue funcionando: borrar la cuenta de Auth no queda bloqueado por la auditoria. */
    @Test
    fun borrarElUsuarioDeAuthDejaElActorANullYConservaLaFila(): Unit = escenario { c ->
        assertNull(c.sqlStateDe("delete from auth.users where id = '$actor'"))
        assertNull(c.unico("select usuario_id::text from public.auditoria_log where id = '$fila'"))
        assertEquals("producto_comercial_guardado", c.unico("select accion from public.auditoria_log where id = '$fila'"))
        assertEquals("{\"a\": 1}", c.unico("select despues::text from public.auditoria_log where id = '$fila'"))
    }

    @Test
    fun anonimizarAManoElActorDeUnUsuarioQueSigueExistiendoNoSeAdmite(): Unit = escenario { c ->
        assertEquals("P0001", c.comoServiceRole("update public.auditoria_log set usuario_id = null where id = '$fila'"))
        assertEquals("P0001", c.sqlStateDe("update public.auditoria_log set usuario_id = null where id = '$fila'"))
        assertEquals(actor, c.unico("select usuario_id::text from public.auditoria_log where id = '$fila'"))
    }

    @Test
    fun aplicarlaDosVecesNoFallaYElVerifyDaCero(): Unit = escenario { c ->
        c.aplicar(migracion)
        val v = c.verify()
        assertTrue(v.isNotEmpty(), v.toString())
        assertTrue(v.values.all { it == 0L }, v.toString())
    }

    @Test
    fun sinLosDisparadoresElVerifyLoDetecta(): Unit = escenario { c ->
        c.ejecutar("drop trigger auditoria_log_append_only_truncate on public.auditoria_log")
        assertTrue(c.verify().values.any { it != 0L })
    }
}
