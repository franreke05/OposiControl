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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.reflect.Modifier

/**
 * INV-NETWORK-COMPLETE (cliente OC): marco de pruebas para el registrador de llamadas HTTP. SOLO CODIGO DE PRUEBA.
 *
 * Una fila de [CLIENT_CALL_ROWS] ejecuta metodos del cliente (Api / Ktor*RepositoryImpl) contra un `HttpClient(MockEngine)`,
 * graba `METODO + clase de host + plantilla de ruta + NOMBRES de la consulta` (ids centinela `ID<n>` -> `{n}`, de modo que el
 * ORDEN de los ids se compara) y exige que la secuencia grabada sea EXACTAMENTE la declarada y que cada llamada exista en
 * `ops/inventory/routes.combined.txt` (backend) o en la lista cerrada de identidad (Supabase Auth).
 */
internal enum class HostClass { BACKEND, SUPABASE, UNKNOWN }

/**
 * Llamada observada o esperada: metodo + clase de host + plantilla de ruta + nombres de parametros de consulta (ordenados, sin
 * valores). En la ruta grabada cada id centinela `ID<n>` es `{n}`; en la declarada `{}` acepta cualquier id pero varios `{}`
 * de una misma ruta exigen ids CRECIENTES de izquierda a derecha (una ruta con los ids cambiados de sitio falla) y `{n}` exige
 * exactamente ese id.
 */
internal data class Call(
    val method: String,
    val path: String,
    val host: HostClass = HostClass.BACKEND,
    val query: List<String> = emptyList(),
) {
    /** Plantilla sin indices (`{n}` -> `{}`): la forma de `routes.combined.txt`. */
    val template: String get() = path.replace(ID_SLOT, "{}")
    override fun toString() = "$method $path${if (query.isEmpty()) "" else query.joinToString("&", "?")} [$host]"
}

private val ID_SLOT = Regex("""\{[0-9]*\}""")

/** Llamada declarada: `"/ruta/{}?limit&offset"` -> ruta + nombres de consulta ordenados. */
internal fun call(method: String, spec: String, host: HostClass = HostClass.BACKEND): Call =
    Call(method, spec.substringBefore('?'), host, spec.substringAfter('?', "").split('&').filter { it.isNotBlank() }.sorted())

internal fun get(spec: String) = call("GET", spec)
internal fun post(spec: String) = call("POST", spec)
internal fun put(spec: String) = call("PUT", spec)
internal fun patch(spec: String) = call("PATCH", spec)
internal fun delete(spec: String) = call("DELETE", spec)

/** Sin consulta; cada id centinela `ID<n>` pasa a `{n}` (el indice se conserva para comparar el orden de los ids). */
internal fun normalizeTemplate(path: String): String = path.substringBefore('?').replace(Regex("ID([0-9]+)"), "{$1}")

/** Unica forma de grabar una peticion (Env y motores propios de una fila): host clasificado + nombres de consulta. */
internal fun recordedCall(request: HttpRequestData): Call = Call(
    method = request.method.value,
    path = normalizeTemplate(request.url.encodedPath),
    host = when (request.url.host) {
        "backend.test" -> HostClass.BACKEND
        "example.supabase.co" -> HostClass.SUPABASE
        else -> HostClass.UNKNOWN
    },
    query = request.url.parameters.names().sorted(),
)

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
            recorded += recordedCall(request)
            respond(
                content = """{"data":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
}

/**
 * UNA fila por sitio de egreso. [site] es la FUNCION del fichero que contiene el sitio (por defecto, el nombre sin el sufijo
 * `(rama)`): una funcion con N sitios necesita N filas con ese `site`. [covers] son los puntos de entrada publicos que la fila
 * ejecuta (nombre, o firma `nombre(Tipo,Tipo)` si el nombre esta sobrecargado). [expect] es la secuencia exacta grabada.
 */
internal class CallRow(
    val file: String,
    val name: String,
    val expect: List<Call>,
    val covers: Set<String> = setOf(name.substringBefore('(')),
    val site: String = name.substringBefore('('),
    val body: suspend Env.() -> Unit,
) {
    constructor(
        file: String,
        name: String,
        expect: Call,
        covers: Set<String> = setOf(name.substringBefore('(')),
        site: String = name.substringBefore('('),
        body: suspend Env.() -> Unit,
    ) : this(file, name, listOf(expect), covers, site, body)
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

/** Ruta grabada (`{n}`) contra la declarada (`{}` = cualquiera pero crecientes; `{k}` = exactamente k). */
internal fun pathMatches(declared: String, recorded: String): Boolean {
    val slots = mutableListOf<Boolean>() // true = `{}` libre
    val rx = buildString {
        var i = 0
        ID_SLOT.findAll(declared).forEach { m ->
            append(Regex.escape(declared.substring(i, m.range.first)))
            val free = m.value == "{}"
            slots += free
            append(if (free) """\{([0-9]+)\}""" else """\{(""" + m.value.removeSurrounding("{", "}") + """)\}""")
            i = m.range.last + 1
        }
        append(Regex.escape(declared.substring(i)))
    }
    val m = Regex(rx).matchEntire(recorded) ?: return false
    val free = m.groupValues.drop(1).filterIndexed { k, _ -> slots[k] }.map { it.toInt() }
    return free.zipWithNext().all { (a, b) -> a < b }
}

internal fun sameCall(declared: Call, recorded: Call): Boolean = declared.method == recorded.method &&
    declared.host == recorded.host && declared.query == recorded.query && pathMatches(declared.path, recorded.path)

internal fun sameSequence(declared: List<Call>, recorded: List<Call>): Boolean =
    declared.size == recorded.size && declared.zip(recorded).all { (d, r) -> sameCall(d, r) }

/** Llamada grabada desconocida: no es ruta servida, no es identidad permitida o va a un host desconocido. */
internal fun unknownCallViolation(who: String, c: Call, served: Set<String>): String? {
    val key = "${c.method} ${c.template}"
    return when (c.host) {
        HostClass.BACKEND -> if (key !in served) "$who: $key is not a route in routes.combined.txt" else null
        HostClass.SUPABASE -> if (key !in SUPABASE_IDENTITY_ALLOWED) "$who: direct Supabase call outside the identity allowlist: $key" else null
        HostClass.UNKNOWN -> "$who: call to an unknown host: $key"
    }
}

/** Violaciones de una fila: secuencia distinta de la declarada (metodo, ruta, orden de ids, consulta), host o ruta desconocidos. */
internal fun rowViolations(row: CallRow, recorded: List<Call>, served: Set<String>): List<String> {
    val out = mutableListOf<String>()
    val who = "${File(row.file).name}#${row.name}"
    if (!sameSequence(row.expect, recorded)) out += "$who: recorded $recorded but the row declares ${row.expect}"
    recorded.forEach { c -> unknownCallViolation(who, c, served)?.let { out += it } }
    if (recorded.isEmpty()) out += "$who: recorded no call at all"
    return out
}

// ------------------------------------------------------------------------- conteo de egreso en texto crudo (R5/R6-HARDEN)

/**
 * Egreso Ktor en TEXTO CRUDO (sin lexer, sin quitar comentarios, sin exigir receptor): cualquier invocacion de un verbo del
 * cliente, con o sin genericos, seguida de `(` o de la lambda constructora `{` (`client.get { url(..) }`,
 * `request { }`, `prepareRequest { }`). Cuentan igual `client.get(`, `client\n    .get(` (multilinea),
 * `with(client) { get(` y `client.apply { post(`. Lo que coincide y NO es egreso se descuenta SOLO por [SiteExclusion].
 */
internal val EGRESS_VERB = Regex(
    """\b(get|post|put|delete|patch|head|options|request|submitForm|submitFormWithBinaryData|""" +
        """prepareGet|preparePost|preparePut|prepareDelete|preparePatch|prepareHead|prepareOptions|prepareRequest)""" +
        """\s*(<[^()]*>)?\s*[({]""",
)

/**
 * Declaracion de funcion en texto crudo (genericos anidados y receptor de extension incluidos). El sitio pertenece a la
 * ULTIMA declaracion anterior: basta para dar a cada sitio la clave `fichero#funcion`. Si una declaracion se cuela en un
 * comentario el sitio cambia de clave y el conteo por funcion FALLA (nunca pasa en silencio: el total por fichero no cambia).
 */
internal val FUN_DECL = Regex(
    """\bfun\s+(?:<(?:[^<>]|<(?:[^<>]|<[^<>]*>)*>)*>\s*)?(?:[\w.]+(?:<(?:[^<>]|<[^<>]*>)*>)?\??\.)?(\w+)\s*\(""",
)

internal const val TOP_LEVEL = "<top-level>"

/** Funcion que contiene la posicion `at` del texto ([TOP_LEVEL] si no hay ninguna declaracion antes). */
internal fun enclosingFunction(text: String, at: Int): String =
    FUN_DECL.findAll(text).lastOrNull { it.range.first < at }?.groupValues?.get(1) ?: TOP_LEVEL

/** Coincidencia de [EGRESS_VERB] que no es egreso. `pattern` es una Regex sobre el texto crudo del fichero. */
internal data class SiteExclusion(val file: String, val pattern: String, val count: Int, val why: String)

/**
 * Sitios de egreso del fichero POR FUNCION y violaciones de sus exclusiones (cuenta distinta, sin justificacion, o una
 * coincidencia que no tapa EXACTAMENTE un verbo).
 */
internal fun egressSitesByFunction(file: String, text: String, exclusions: List<SiteExclusion>): Pair<Map<String, Int>, List<String>> {
    val out = mutableListOf<String>()
    val egress = EGRESS_VERB.findAll(text).map { it.range.first }.toList()
    val excluded = mutableSetOf<Int>()
    exclusions.filter { it.file == file }.forEach { ex ->
        val hits = Regex(ex.pattern).findAll(text).toList()
        val who = "${File(file).name} exclusion /${ex.pattern}/"
        if (ex.why.isBlank()) out += "$who: missing justification"
        if (hits.size != ex.count) out += "$who: matches ${hits.size} times but is pinned to ${ex.count} (a new call is never absorbed)"
        hits.forEach { h ->
            val inside = egress.filter { it in h.range }
            if (inside.size != 1) out += "$who: match '${h.value}' covers ${inside.size} egress verbs (exactly 1 required)"
            excluded += inside
        }
    }
    val funs = FUN_DECL.findAll(text).map { it.range.first to it.groupValues[1] }.toList()
    val byFun = egress.filterNot { it in excluded }
        .groupingBy { at -> funs.lastOrNull { it.first < at }?.second ?: TOP_LEVEL }.eachCount()
    return byFun to out
}

/** Total de sitios de egreso del fichero (suma de [egressSitesByFunction]). */
internal fun egressSites(file: String, text: String, exclusions: List<SiteExclusion>): Pair<Int, List<String>> =
    egressSitesByFunction(file, text, exclusions).let { (m, v) -> m.values.sum() to v }

/**
 * Completitud: por fichero `filas == sitios` y por FUNCION `filas con ese site == sitios de la funcion`. Un sitio nuevo
 * (rama nueva, forma lambda, multilinea, `with(client)`, ayudante nuevo) sube la cuenta de SU funcion y FALLA sin su fila;
 * una fila sobrante (o copiada para rellenar otro fichero/funcion) tambien falla.
 */
internal fun completenessViolations(sites: Map<String, Map<String, Int>>, rows: List<CallRow>): List<String> {
    val out = mutableListOf<String>()
    val rowsByFile = rows.groupBy { it.file }
    sites.forEach { (file, byFun) ->
        val fileRows = rowsByFile[file].orEmpty()
        val base = File(file).name
        val total = byFun.values.sum()
        if (fileRows.size != total) out += "$base: $total HTTP call sites but ${fileRows.size} recorder rows (a new call needs a row)"
        val rowsBySite = fileRows.groupingBy { it.site }.eachCount()
        (byFun.keys + rowsBySite.keys).sorted().forEach { f ->
            val s = byFun[f] ?: 0
            val r = rowsBySite[f] ?: 0
            if (s != r) out += "$base#$f: $s HTTP call sites in this function but $r recorder rows with site=$f"
        }
    }
    rowsByFile.keys.filter { it !in sites }.forEach { out += "$it: has rows but is not an HTTPCALL client file" }
    return out
}

/**
 * Anti-relleno: las filas de una funcion con N sitios graban entre todas al menos N llamadas DISTINTAS. N filas identicas
 * que recorren un solo camino no prueban N sitios. (Una invocacion que recorre todos los sitios, como `runChecks`, si lo prueba.)
 */
internal fun paddingViolations(sites: Map<String, Map<String, Int>>, recordedByRow: Map<CallRow, List<Call>>): List<String> =
    sites.flatMap { (file, byFun) ->
        byFun.mapNotNull { (f, n) ->
            val distinct = recordedByRow.filterKeys { it.file == file && it.site == f }.values.flatten()
                .map { "${it.method} ${it.template} ${it.query}" }.toSet()
            if (distinct.size < n) "${File(file).name}#$f: $n sites but its rows record only ${distinct.size} distinct calls $distinct" else null
        }
    }

// ------------------------------------------------------------ rutas y filtros en linea (R6-HARDEN): cada uno lo ejecuta una fila

/** Literal de ruta en texto crudo: cadena que empieza por `/` o por interpolaciones seguidas de `/`. */
internal val PATH_LITERAL = Regex("\"((?:\\$\\{[^}\"\\n]*\\}|\\$\\w+)*/[^\"\\n]*)\"")
private val INTERPOLATION = Regex("""\$\{[^}"\n]*\}|\$\w+""")

/** Filtro de consulta en texto crudo: `parameter("nombre", ...)` del constructor de peticiones de Ktor. */
internal val QUERY_PARAMETER = Regex("""\bparameter\(\s*"(\w+)"""")

/** Requisito estatico: una funcion del fichero contiene una ruta en linea (partes fijas) o un filtro de consulta con nombre. */
internal data class StaticTarget(val function: String, val source: String, val pathPattern: Regex?, val queryNames: List<String>)

/** Rutas en linea (con al menos una letra fija) y filtros `parameter("x"` del fichero, cada uno con su funcion. */
internal fun staticTargets(text: String): List<StaticTarget> {
    val out = mutableListOf<StaticTarget>()
    PATH_LITERAL.findAll(text).forEach { m ->
        val raw = m.groupValues[1]
        val pieces = INTERPOLATION.split(raw.substringBefore('?'))
        val names = raw.substringAfter('?', "").split('&').map { it.substringBefore('=') }.filter { it.isNotBlank() && !it.startsWith("$") }
        if (pieces.any { p -> p.any(Char::isLetter) } || names.isNotEmpty()) {
            val rx = Regex(pieces.joinToString(".*") { Regex.escape(it) })
            out += StaticTarget(enclosingFunction(text, m.range.first), m.value, rx, names)
        }
    }
    QUERY_PARAMETER.findAll(text).forEach { m ->
        out += StaticTarget(enclosingFunction(text, m.range.first), m.value, null, listOf(m.groupValues[1]))
    }
    return out
}

/**
 * Cada ruta en linea y cada filtro de consulta de una funcion lo graba alguna fila de ESA funcion (fila con `site` o `covers`
 * igual a la funcion); si ninguna fila nombra la funcion (constante o ayudante de URL), alguna fila del fichero. Cierra la
 * rama nueva con una ruta nueva (`if (..) "$MARKET/__ghost" else ..`) y el filtro renombrado o en una rama sin ejecutar.
 */
internal fun staticTargetViolations(file: String, text: String, recordedByRow: Map<CallRow, List<Call>>): List<String> {
    val fileRows = recordedByRow.filterKeys { it.file == file }
    return staticTargets(text).mapNotNull { t ->
        val own = fileRows.filterKeys { r -> r.site == t.function || r.covers.any { it.substringBefore('(') == t.function } }
        val candidates = (own.ifEmpty { fileRows }).values.flatten()
        val ok = candidates.any { c -> (t.pathPattern?.containsMatchIn(c.path) ?: true) && c.query.containsAll(t.queryNames) }
        if (ok) null else "${File(file).name}#${t.function}: ${t.source} is not exercised by any recorder row of this function"
    }
}

// ------------------------------------------------------------------- puntos de entrada por FIRMA (reflexion de Java)

private val HTTP_CLIENT = HttpClient::class.java

/** Firma estable: `nombre(TipoSimple,TipoSimple)` sin la `Continuation` final de una funcion `suspend`. */
internal fun signatureOf(m: java.lang.reflect.Method): String =
    m.name + m.parameterTypes.filter { it.name != "kotlin.coroutines.Continuation" }.joinToString(",", "(", ")") { it.simpleName }

private fun Class<*>.publicMethodSignatures(): List<String> =
    declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }.map(::signatureOf)

/** Clase que puede alcanzar el cliente: guarda o recibe un `HttpClient`, o una clase del fichero que lo alcanza (inner incluida). */
internal fun clientReaching(classes: Collection<Class<*>>): Set<Class<*>> {
    val reach = mutableSetOf<Class<*>>()
    fun Class<*>.typesSeen(): List<Class<*>> = declaredFields.map { it.type } +
        declaredConstructors.flatMap { it.parameterTypes.toList() } + declaredMethods.flatMap { it.parameterTypes.toList() }
    var grew = true
    while (grew) {
        grew = false
        classes.filter { it !in reach }.forEach { c ->
            if (c.typesSeen().any { t -> HTTP_CLIENT.isAssignableFrom(t) || t in reach }) { reach += c; grew = true }
        }
    }
    return reach
}

/** Clases anidadas (recursivo, companion incluido), sin sinteticas ni privadas. */
internal fun nestedClasses(c: Class<*>): List<Class<*>> = c.declaredClasses
    .filter { !it.isSynthetic && !Modifier.isPrivate(it.modifiers) }
    .flatMap { listOf(it) + nestedClasses(it) }

/**
 * Puntos de entrada de un fichero cliente, por FIRMA: metodos publicos de la clase principal y de la fachada `<Fichero>Kt`
 * (sin prefijo), y de toda otra clase del fichero o anidada (inner, companion, objeto) que alcanza el cliente
 * (prefijo `Clase.`). Una sobrecarga nueva es una firma nueva; una clase interna nueva tambien.
 */
internal fun entryPointSignatures(main: Class<*>, facade: Class<*>?, others: List<Class<*>>): Set<String> {
    val all = (listOf(main) + others).flatMap { listOf(it) + nestedClasses(it) }.distinct()
    val reaching = clientReaching(all + listOfNotNull(facade))
    val out = mutableSetOf<String>()
    out += main.publicMethodSignatures()
    facade?.let { out += it.publicMethodSignatures() }
    (all - main).filter { it in reaching }.forEach { c ->
        val prefix = (if (c.name.startsWith(main.name + "$")) c.name.removePrefix(main.name + "$") else c.name.substringAfterLast('.')).replace('$', '.')
        c.publicMethodSignatures().forEach { out += "$prefix.$it" }
    }
    return out
}

/** Nombre o firma listados (`covers`, `NON_HTTP_MEMBERS`) contra las firmas reales: un nombre sobrecargado es ambiguo. */
private fun resolve(who: String, listed: Set<String>, entries: Set<String>, what: String, out: MutableList<String>): Set<String> =
    listed.flatMap { e ->
        val hits = if ('(' in e) entries.filter { it == e } else entries.filter { it.substringBefore('(') == e }
        when {
            hits.isEmpty() -> { out += "$who#$e: $what a method that is not public in the class"; emptyList() }
            hits.size > 1 -> { out += "$who#$e: overloaded name ${hits.sorted()}: $what must name the exact signature"; emptyList() }
            else -> hits
        }
    }.toSet()

/**
 * Cobertura por PUNTO DE ENTRADA: toda firma publica de [entryPointSignatures] esta cubierta por una fila (`covers`) o es un
 * miembro sin HTTP justificado. Un metodo nuevo que reutiliza un ayudante, una SOBRECARGA nueva de un nombre ya cubierto o
 * un metodo de una clase interna nueva no tiene fila y FALLA aunque no anada ningun sitio.
 */
internal fun entryPointViolations(file: String, entries: Set<String>, covered: Set<String>, nonHttp: Set<String>): List<String> {
    val who = File(file).name
    val out = mutableListOf<String>()
    val cov = resolve(who, covered, entries, "a row covers", out)
    val non = resolve(who, nonHttp, entries, "a stale non-HTTP entry names", out)
    (entries - cov - non).sorted().forEach { out += "$who#$it: public method without a recorder row or non-HTTP justification" }
    (non intersect cov).sorted().forEach { out += "$who#$it: non-HTTP entry that also has a row" }
    return out
}

/** Clases de nivel superior declaradas en el texto del fichero (para cargarlas por reflexion). */
internal val TOP_LEVEL_CLASS = Regex(
    """^(?:@\w+(?:\([^)\n]*\))?\s+)*(?:(?:private|internal|public|data|sealed|abstract|open|enum|value|annotation|fun)\s+)*(?:class|object|interface)\s+(\w+)""",
    RegexOption.MULTILINE,
)
