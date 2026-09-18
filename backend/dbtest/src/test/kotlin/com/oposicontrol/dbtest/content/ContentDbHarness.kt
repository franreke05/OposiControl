package com.oposicontrol.dbtest.content

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.postgresql.util.PSQLException
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

/**
 * PostgreSQL REAL y desechable con la forma VIVA del contenido de MAIN.
 *
 * Autocontenido a proposito: no comparte cluster, roles ni objetos con
 * `PostgresHarness`. Aquella vertical instala su propio fixture de roles, con
 * otra forma de `roles_app`/`es_admin()`, y mezclar los dos daria una base que no
 * existe en ningun sitio.
 *
 * Por que una PLANTILLA y no instalar el fixture en cada escenario:
 * PostgreSQL dispara los triggers RI de una tabla por NOMBRE
 * (`RI_ConstraintTrigger_a_<oid>`) y comprueba los UNIQUE por OID de indice. Ese
 * orden decide que cascada corre antes y que restriccion salta primero, y el
 * fixture lo reproduce creando los objetos en el orden vivo. Clonar una base
 * (`create database ... template`) copia tambien los OID, asi que TODOS los
 * escenarios heredan exactamente el mismo orden. Reinstalar en cada escenario
 * haria avanzar el contador global de OID y, al cruzar de 5 a 6 cifras, el orden
 * por nombre dejaria de ser el orden de creacion sin que ningun test lo notara.
 *
 * El fixture se lee SIEMPRE del repositorio (una vez por JVM): nunca se copia a un
 * literal, para que lo que se prueba sea el fichero que se revisa.
 */
object ContentDbHarness {
    const val FIXTURE = "supabase/canonical/dry_run_fixture/002_content_live_shape.sql"

    private const val USUARIO = "postgres"
    private const val PLANTILLA = "contenido_vivo_plantilla"
    private val contador = AtomicInteger()

    /**
     * Roles de cluster como en Supabase. `service_role` lleva BYPASSRLS; `anon` y
     * `authenticated` no. Se crean una vez: son globales al cluster.
     */
    private val ROLES_CLUSTER = """
        create role anon nologin;
        create role authenticated nologin;
        create role service_role nologin bypassrls;
    """.trimIndent()

    /**
     * Lo minimo de Supabase que el fixture necesita y que NO es forma de contenido.
     *
     * `auth.users` y `auth.uid()` son stubs: en vivo los pone GoTrue y `auth.uid()`
     * ademas cae a `request.jwt.claims->>'sub'`. `private` existe en vivo con
     * `usage` solo para `service_role`; se crea para las migraciones candidatas.
     */
    val PRERREQUISITOS = """
        create schema auth;
        create schema private;

        create table auth.users (
          id uuid primary key,
          email text
        );

        create function auth.uid() returns uuid
        language sql stable
        as ${'$'}${'$'}
          select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
        ${'$'}${'$'};

        grant usage on schema auth to anon, authenticated, service_role;
        grant usage on schema private to service_role;
    """.trimIndent()

    /** Raiz del repositorio: el primer ancestro con `settings.gradle.kts`. */
    val raiz: File by lazy {
        var d = File(".").absoluteFile
        while (d.parentFile != null && !File(d, "settings.gradle.kts").isFile) d = d.parentFile
        check(File(d, "settings.gradle.kts").isFile) { "No encuentro la raiz del repositorio." }
        d
    }

    fun leerDelRepositorio(ruta: String): String {
        val f = File(raiz, ruta)
        check(f.isFile) { "Falta $ruta" }
        return f.readText()
    }

    private val pg: EmbeddedPostgres by lazy {
        val instancia = EmbeddedPostgres.builder().start()
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { instancia.close() } })
        instancia.postgresDatabase.connection.use { c -> c.ejecutar(ROLES_CLUSTER) }
        instancia
    }

    /**
     * Instala prerrequisitos + fixture en la plantilla. Si falla, el siguiente
     * acceso lo reintenta desde una plantilla vacia, nunca sobre una a medias.
     */
    private val plantilla: String by lazy {
        pg.postgresDatabase.connection.use { c ->
            c.ejecutar("drop database if exists $PLANTILLA")
            c.ejecutar("create database $PLANTILLA")
        }
        pg.getDatabase(USUARIO, PLANTILLA).connection.use { c ->
            c.ejecutar(PRERREQUISITOS)
            c.ejecutar(leerDelRepositorio(FIXTURE))
        }
        PLANTILLA
    }

    fun version(): String = pg.postgresDatabase.connection.use { it.unico("select version()")!! }

    /**
     * Base NUEVA clonada de la plantilla, con autocommit y como superusuario.
     * Se borra al terminar (con FORCE, por si el test dejo conexiones abiertas).
     */
    fun <T> escenario(bloque: (Connection) -> T): T {
        val origen = plantilla
        val nombre = "contenido_" + contador.incrementAndGet()
        pg.postgresDatabase.connection.use { c -> c.ejecutar("create database $nombre template $origen") }
        try {
            return pg.getDatabase(USUARIO, nombre).connection.use(bloque)
        } finally {
            runCatching {
                pg.postgresDatabase.connection.use { c -> c.ejecutar("drop database if exists $nombre with (force)") }
            }
        }
    }

    /** Segunda conexion INDEPENDIENTE a la misma base: concurrencia de verdad. */
    fun otraConexion(c: Connection): Connection = pg.getDatabase(USUARIO, c.catalog).connection
}

fun Connection.ejecutar(sql: String) {
    createStatement().use { it.execute(sql) }
}

fun Connection.unico(sql: String): String? =
    createStatement().use { st ->
        st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
    }

fun Connection.entero(sql: String): Int = unico(sql)!!.toInt()

/** Instala un fichero SQL del repositorio tal cual (p. ej. una migracion candidata). */
fun Connection.instalarDelRepositorio(ruta: String) = ejecutar(ContentDbHarness.leerDelRepositorio(ruta))

/**
 * Lo que PostgreSQL dice de un fallo, sin interpretar: el SQLSTATE y los campos
 * del mensaje del servidor. `contexto` es el WHERE (p. ej. que funcion de trigger
 * lanzo el RAISE), que es lo que distingue dos triggers con el mismo mensaje.
 */
data class FalloSql(
    val sqlState: String?,
    val restriccion: String?,
    val columna: String?,
    val mensaje: String?,
    val contexto: String?,
)

fun SQLException.comoFallo(): FalloSql {
    val servidor = (this as? PSQLException)?.serverErrorMessage
    return FalloSql(
        sqlState = sqlState,
        restriccion = servidor?.constraint,
        columna = servidor?.column,
        mensaje = servidor?.message ?: message,
        contexto = servidor?.where,
    )
}

/** Ejecuta y devuelve el fallo, o `null` si la sentencia fue bien. */
fun Connection.fallo(sql: String): FalloSql? =
    try {
        ejecutar(sql)
        null
    } catch (e: SQLException) {
        e.comoFallo()
    }

/**
 * Ejecuta [bloque] como un rol de Supabase, con `request.jwt.claim.sub` = [sub]
 * (o sin claim). Nivel de sesion y en autocommit: un fallo dentro del bloque no
 * deja la conexion abortada, y el `reset` final siempre corre.
 */
fun <T> Connection.comoRol(rol: String, sub: String? = null, bloque: (Connection) -> T): T {
    require(rol in setOf("anon", "authenticated", "service_role")) { "Rol no previsto: $rol" }
    check(autoCommit) { "comoRol espera autocommit: un fallo dejaria la sesion abortada." }
    ejecutar("select set_config('request.jwt.claim.sub', '${sub ?: ""}', false)")
    ejecutar("set role $rol")
    try {
        return bloque(this)
    } finally {
        ejecutar("reset role")
        // auth.uid() convierte '' en NULL: equivale a no tener claim.
        ejecutar("select set_config('request.jwt.claim.sub', '', false)")
    }
}
