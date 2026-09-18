package com.oposicontrol.dbtest

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

/**
 * PostgreSQL REAL, efimero y aislado. Sin Docker, sin daemon, sin MAIN.
 *
 * Un cluster por JVM (arrancarlo cuesta segundos) y una base de datos NUEVA por
 * escenario, de modo que ningun test vea el estado de otro. El cluster entero
 * muere con la JVM.
 */
object PostgresHarness {
    private val contador = AtomicInteger()

    private val pg: EmbeddedPostgres by lazy {
        val instancia = EmbeddedPostgres.builder().start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { instancia.close() } })
        instancia.postgresDatabase.connection.use { c -> c.ejecutar(SupabaseFixture.ROLES_CLUSTER) }
        instancia
    }

    fun version(): String =
        pg.postgresDatabase.connection.use { it.unico("select version()")!! }

    /** Base nueva con el fixture legacy instalado. */
    fun <T> escenario(bloque: (Connection) -> T): T {
        val nombre = "escenario_" + contador.incrementAndGet()
        pg.postgresDatabase.connection.use { c -> c.ejecutar("create database $nombre") }
        return pg.getDatabase("postgres", nombre).connection.use { c ->
            c.ejecutar(SupabaseFixture.SQL)
            bloque(c)
        }
    }

    fun conexionMaestra(): java.sql.Connection = pg.postgresDatabase.connection

    fun baseLlamada(nombre: String): java.sql.Connection = pg.getDatabase("postgres", nombre).connection

    /** Segunda conexion independiente a la misma base: concurrencia de verdad. */
    fun conexionExtra(c: Connection): Connection {
        val db = c.catalog
        return pg.getDatabase("postgres", db).connection
    }
}

fun Connection.ejecutar(sql: String) {
    createStatement().use { it.execute(sql) }
}

fun Connection.unico(sql: String): String? =
    createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
    }

fun Connection.entero(sql: String): Int = unico(sql)!!.toInt()

/** Instala el candidate REAL del repositorio. */
fun Connection.aplicarGuard() = ejecutar(CandidateFiles.guard())

/** Instala una version MUTADA del candidate real. Solo en memoria y en esta base. */
fun Connection.aplicarGuardMutado(mutacion: (String) -> String) {
    val original = CandidateFiles.guard()
    val mutado = mutacion(original)
    check(mutado != original) { "La mutacion no cambio nada: el test no probaria nada." }
    ejecutar(mutado)
}

fun Connection.aplicarRollback() = ejecutar(CandidateFiles.rollback())

/** Llama a la RPC. Devuelve null si fue bien, o el mensaje de error si abortó. */
fun Connection.sincronizarRoles(usuarioId: String, roleIds: List<String>): String? {
    val array = roleIds.joinToString(",") { "'$it'::uuid" }
    val literal = if (roleIds.isEmpty()) "array[]::uuid[]" else "array[$array]"
    return try {
        ejecutar("select public.admin_sync_roles('$usuarioId'::uuid, $literal)")
        null
    } catch (e: SQLException) {
        e.message
    }
}

/** Codigos de rol que tiene un usuario, ordenados. */
fun Connection.rolesDe(usuarioId: String): List<String> =
    createStatement().use { st ->
        st.executeQuery(
            "select r.codigo from public.roles_usuario ru " +
                "join public.roles_app r on r.id = ru.rol_id " +
                "where ru.usuario_id = '$usuarioId'::uuid order by r.codigo",
        ).use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

/**
 * Oraculo INDEPENDIENTE del SQL que se esta probando.
 *
 * Canonicaliza en Kotlin (`trim().lowercase()`, el espejo de `RoleSyncInvariants`)
 * en vez de llamar a `admin_role_code_canonical`: si contara con la funcion del
 * candidate, una mutacion que rompiera el canonicalizador rompería tambien la
 * medicion y el test se quedaria verde.
 */
fun Connection.superadminsActivos(): Int =
    createStatement().use { st ->
        st.executeQuery(
            "select r.codigo from public.roles_usuario ru join public.roles_app r on r.id = ru.rol_id",
        ).use { rs ->
            var n = 0
            while (rs.next()) if (rs.getString(1).trim().lowercase() == "superadmin") n++
            n
        }
    }

fun Connection.asignar(usuarioId: String, rolId: String) =
    ejecutar("insert into public.roles_usuario (usuario_id, rol_id) values ('$usuarioId'::uuid, '$rolId'::uuid)")

/**
 * Escenario sobre una base con intercalacion ICU distinta (p. ej. `tr-TR`).
 * Devuelve null si esta instalacion de PostgreSQL no trae ICU: en ese caso la
 * cobertura se declara NOT_AVAILABLE en vez de fingirse.
 */
fun <T> PostgresHarness.escenarioConIcu(locale: String, bloque: (java.sql.Connection) -> T): T? {
    val nombre = "icu_" + java.util.UUID.randomUUID().toString().take(8).replace("-", "")
    val creada = try {
        conexionMaestra().use { c ->
            c.ejecutar(
                "create database $nombre template template0 " +
                    "locale_provider icu icu_locale '$locale' locale 'C'",
            )
        }
        true
    } catch (e: java.sql.SQLException) {
        println("ICU_NO_DISPONIBLE: " + e.message)
        false
    }
    if (!creada) return null
    return baseLlamada(nombre).use { c ->
        c.ejecutar(SupabaseFixture.SQL)
        bloque(c)
    }
}
