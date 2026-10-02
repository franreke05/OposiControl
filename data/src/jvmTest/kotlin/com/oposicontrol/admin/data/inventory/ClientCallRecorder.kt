package com.oposicontrol.admin.data.inventory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.config.Environment
import com.oposicontrol.admin.core.common.config.FeatureFlags
import com.oposicontrol.admin.core.common.logging.NoOpLogger
import com.oposicontrol.admin.core.database.AdminDatabase
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.File

/**
 * INV-NETWORK-COMPLETE (cliente OC): marco de pruebas para el registrador de llamadas HTTP. SOLO CODIGO DE PRUEBA.
 *
 * Una fila de [CLIENT_CALL_ROWS] ejecuta UN metodo del cliente (Api / Ktor*RepositoryImpl) contra un `HttpClient(MockEngine)`,
 * graba `METODO + clase de host + plantilla de ruta` (consulta fuera; ids centinela `ID<n>` -> `{}`, igual que el inventario de
 * rutas del servidor) y exige que la secuencia grabada sea EXACTAMENTE la declarada y que cada llamada exista en
 * `ops/inventory/routes.combined.txt` (backend) o en la lista cerrada de identidad (Supabase Auth). Sin reflexion ni parser.
 */
internal enum class HostClass { BACKEND, SUPABASE, UNKNOWN }

/** Llamada observada o esperada: metodo + clase de host + plantilla de ruta (`/a/{}/b`). */
internal data class Call(val method: String, val path: String, val host: HostClass = HostClass.BACKEND) {
    override fun toString() = "$method $path [$host]"
}

internal fun get(path: String) = Call("GET", path)
internal fun post(path: String) = Call("POST", path)
internal fun put(path: String) = Call("PUT", path)
internal fun patch(path: String) = Call("PATCH", path)
internal fun delete(path: String) = Call("DELETE", path)

/** Mismo criterio que el inventario del servidor: sin consulta y cualquier id (centinela `ID<n>`) pasa a `{}`. */
internal fun normalizeTemplate(path: String): String = path.substringBefore('?').replace(Regex("ID[0-9]+"), "{}")

/** Entorno de una fila: cliente grabador + dependencias minimas del modulo `data`. Cada fila recibe uno nuevo. */
internal class Env {
    val recorded = mutableListOf<Call>()
    val appConfig = AppConfig(
        environment = Environment.DEV,
        baseUrl = "http://backend.test",
        backendToken = "test-token",
        supabaseUrl = "https://example.supabase.co",
        supabaseAnonKey = "anon-key",
        supabaseBucketExamResources = "exam-resources",
        featureFlags = FeatureFlags(enableLogs = false, enableAnalytics = false),
        appName = "OposiControl Admin",
        appVersion = "test",
    )
    val session: SessionLocalDataSource = SessionLocalDataSource(
        AdminDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { AdminDatabase.Schema.create(it) }),
    )
    val adapter = NetworkResultAdapter(NoOpLogger)

    /** Responde 200 `{"data":[]}` a todo: lo que se mide es la peticion, no el mapeo de la respuesta. */
    val client: HttpClient = HttpClient(
        MockEngine { request ->
            recorded += Call(
                method = request.method.value,
                path = normalizeTemplate(request.url.encodedPath),
                host = when (request.url.host) {
                    "backend.test" -> HostClass.BACKEND
                    "example.supabase.co" -> HostClass.SUPABASE
                    else -> HostClass.UNKNOWN
                },
            )
            respond(
                content = """{"data":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
}

/**
 * UNA fila por sitio de llamada HTTP (un metodo con dos ramas `client.post`/`client.patch` = dos filas).
 * [expect] es la secuencia exacta grabada (la primera es la del propio sitio; las siguientes, seguimientos como un refresco).
 */
internal class CallRow(
    val file: String,
    val name: String,
    val expect: List<Call>,
    /** Metodos publicos que la fila ejecuta (por defecto, el nombre sin el sufijo `(rama)`): cobertura por punto de entrada. */
    val covers: Set<String>,
    val body: suspend Env.() -> Unit,
) {
    constructor(file: String, name: String, expect: List<Call>, body: suspend Env.() -> Unit) :
        this(file, name, expect, setOf(name.substringBefore('(')), body)
    constructor(file: String, name: String, expect: Call, body: suspend Env.() -> Unit) : this(file, name, listOf(expect), body)
}

/** Ejecuta la fila. Los fallos de mapeo se ignoran a proposito (la respuesta es minima): solo cuenta lo que se envio. */
internal suspend fun CallRow.record(): List<Call> {
    val env = Env()
    try { body(env) } catch (_: Exception) { /* Api* propagan el error de mapeo: irrelevante aqui */ }
    env.client.close()
    return env.recorded.toList()
}

/** Cierre de identidad: unica superficie directa a Supabase permitida (GoTrue), clase IDENTITY_NON_BUSINESS. */
internal val SUPABASE_IDENTITY_ALLOWED = setOf("POST /auth/v1/token", "POST /auth/v1/logout")

// ---------------------------------------------------------------------------------------------------- comparadores puros

/** Rutas servidas (`ops/inventory/routes.combined.txt`, generado del arbol REAL de Ktor): `METODO /ruta/{}`. */
internal fun parseServedRoutes(lines: List<String>): Set<String> =
    lines.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()

/** Violaciones de una fila: secuencia distinta de la declarada, host desconocido o llamada que no es una ruta servida. */
internal fun rowViolations(row: CallRow, recorded: List<Call>, served: Set<String>): List<String> {
    val out = mutableListOf<String>()
    val who = "${File(row.file).name}#${row.name}"
    if (recorded != row.expect) out += "$who: recorded ${recorded} but the row declares ${row.expect}"
    recorded.forEach { c ->
        val key = "${c.method} ${c.path}"
        when (c.host) {
            HostClass.BACKEND -> if (key !in served) out += "$who: $key is not a route in routes.combined.txt"
            HostClass.SUPABASE -> if (key !in SUPABASE_IDENTITY_ALLOWED) out += "$who: direct Supabase call outside the identity allowlist: $key"
            HostClass.UNKNOWN -> out += "$who: call to an unknown host: $key"
        }
    }
    if (recorded.isEmpty()) out += "$who: recorded no call at all"
    return out
}

/**
 * Completitud por fichero: filas + pendientes (rejilla que solo encoge) == sitios de llamada del tripwire.
 * Un metodo nuevo sin fila sube los sitios y FALLA; una entrada pendiente ya resuelta (0) o sobrante tambien.
 */
internal fun completenessViolations(
    sitesByFile: Map<String, Int>,
    rowsByFile: Map<String, Int>,
    pending: Map<String, Int>,
): List<String> {
    val out = mutableListOf<String>()
    sitesByFile.forEach { (file, sites) ->
        val rows = rowsByFile[file] ?: 0
        val pend = pending[file] ?: 0
        if (rows + pend != sites) {
            out += "${File(file).name}: $sites HTTP call sites but $rows recorder rows + $pend pending (a new call needs a row; pending may only shrink)"
        }
    }
    (rowsByFile.keys + pending.keys).filter { it !in sitesByFile }.forEach { out += "$it: has rows/pending but is not an HTTPCALL client file" }
    pending.filterValues { it <= 0 }.keys.forEach { out += "${File(it).name}: stale pending entry (0 left): remove it" }
    return out
}

// ------------------------------------------------------------------------------- completitud endurecida (R5-HARDEN)

/**
 * Egreso Ktor en TEXTO CRUDO (sin lexer, sin quitar comentarios, sin exigir receptor): cualquier invocacion de un verbo del
 * cliente, con o sin genericos, en cualquier linea. Asi cuentan `client.get(`, `client\n    .get(` (multilinea),
 * `with(client) { get(` y `client.apply { post(` igual. Lo que coincide y NO es egreso (un `put(` de `buildJsonObject`, un
 * `get()` de Koin, la definicion/invocacion de un ayudante privado llamado `get`/`post`) se descuenta SOLO por [SiteExclusion]
 * con fichero + patron + cuenta EXACTA + justificacion: si la cuenta cambia, falla (no absorbe sitios nuevos).
 */
internal val EGRESS_VERB = Regex(
    """\b(get|post|put|delete|patch|head|options|request|submitForm|submitFormWithBinaryData|""" +
        """prepareGet|preparePost|preparePut|prepareDelete|preparePatch|prepareHead|prepareOptions|prepareRequest)""" +
        """\s*(<[^()]*>)?\s*\(""",
)

/** Coincidencia de [EGRESS_VERB] que no es egreso. `pattern` es una Regex sobre el texto crudo del fichero. */
internal data class SiteExclusion(val file: String, val pattern: String, val count: Int, val why: String)

/** Sitios de egreso del fichero y violaciones de sus exclusiones (cuenta distinta, sin justificacion, o que no tapa un verbo). */
internal fun egressSites(file: String, text: String, exclusions: List<SiteExclusion>): Pair<Int, List<String>> {
    val out = mutableListOf<String>()
    var sites = EGRESS_VERB.findAll(text).count()
    exclusions.filter { it.file == file }.forEach { ex ->
        val hits = Regex(ex.pattern).findAll(text).map { it.value }.toList()
        val who = "${File(file).name} exclusion /${ex.pattern}/"
        if (ex.why.isBlank()) out += "$who: missing justification"
        if (hits.size != ex.count) out += "$who: matches ${hits.size} times but is pinned to ${ex.count} (a new call is never absorbed)"
        hits.filterNot { EGRESS_VERB.containsMatchIn(it) }.forEach { out += "$who: match '$it' covers no egress verb" }
        sites -= hits.size
    }
    return sites to out
}

/**
 * Cobertura por PUNTO DE ENTRADA (plan validado, paso 6: completitud por reflexion de Java): los metodos publicos declarados de
 * la clase cliente (y de su fachada `<Fichero>Kt`), sin sinteticos ni puentes, deben ser EXACTAMENTE
 * filas (`covers`) + pendientes + miembros sin HTTP justificados. Un metodo nuevo que reutiliza un ayudante (o llama en
 * multilinea / `with(client)`) no tiene fila y FALLA aunque no anada ningun sitio.
 */
internal fun publicMethodNames(vararg classes: Class<*>): Set<String> = classes.flatMap { c ->
    c.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }.map { it.name }
}.toSet()

internal fun entryPointViolations(
    file: String,
    publicMethods: Set<String>,
    covered: Set<String>,
    pending: Set<String>,
    nonHttp: Set<String>,
): List<String> {
    val who = File(file).name
    val out = mutableListOf<String>()
    (publicMethods - covered - pending - nonHttp).sorted()
        .forEach { out += "$who#$it: public method without a recorder row, pending entry or non-HTTP justification" }
    (covered - publicMethods).sorted().forEach { out += "$who#$it: a row covers a method that is not public in the class" }
    (pending - publicMethods).sorted().forEach { out += "$who#$it: stale pending method (not public)" }
    (pending intersect covered).sorted().forEach { out += "$who#$it: stale pending method (it has a row now): remove it" }
    (nonHttp - publicMethods).sorted().forEach { out += "$who#$it: stale non-HTTP entry (not public)" }
    (nonHttp intersect (covered + pending)).sorted().forEach { out += "$who#$it: non-HTTP entry that also has a row/pending" }
    return out
}
