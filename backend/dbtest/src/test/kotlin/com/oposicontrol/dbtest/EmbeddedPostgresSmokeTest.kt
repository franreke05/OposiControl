package com.oposicontrol.dbtest

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike: demuestra que esta maquina puede levantar un PostgreSQL real y
 * desechable. Si esto no pasa, la suite semantica no tiene cimiento y no hay
 * que escribir ni un escenario mas encima.
 */
class EmbeddedPostgresSmokeTest {
    @Test
    fun arrancaUnPostgresRealYDesechable() {
        EmbeddedPostgres.builder().start().use { pg ->
            pg.postgresDatabase.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("select version()").use { rs ->
                        assertTrue(rs.next())
                        val version = rs.getString(1)
                        println("POSTGRES_EMBEBIDO: $version")
                        assertTrue(version.startsWith("PostgreSQL"), version)
                    }
                    // Superusuario: lo necesitamos para crear roles con y sin
                    // BYPASSRLS al probar el fallo abierto de RLS.
                    st.executeQuery("select current_setting('is_superuser')").use { rs ->
                        assertTrue(rs.next())
                        assertEquals("on", rs.getString(1))
                    }
                }
            }
        }
    }
}
