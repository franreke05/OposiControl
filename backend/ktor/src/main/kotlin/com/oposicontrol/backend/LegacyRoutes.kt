package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// =====================================================================
// COMPATIBILIDAD LEGACY (SLICE 0, `specs.legacy`, FROZEN)
//
// 1) Deprecation/Sunset: plugin de ruta [LegacyRoute]. `Deprecation:
//    @<unix-seconds>` (RFC 9745) + `Link: <sucesor>; rel="successor-version"`;
//    `Sunset: <IMF-fixdate>` (RFC 8594) SOLO cuando el propietario aprueba una
//    fecha. Cuenta `legacy_route_requests_total{endpoint="EP-xxx"}` en el
//    registro Prometheus existente: es la prueba de "cero trafico" para retirar.
// 2) Errores: las rutas legacy usan el MISMO StatusPages, asi que emiten el sobre
//    canonico + sus campos legacy aditivos. No hay cuerpos de error legacy.
// 3) Adaptador -> servicio canonico ([LegacyAdapter]): la ruta legacy solo
//    traduce su peticion al comando canonico, llama al MISMO servicio que la
//    ruta canonica y traduce el resultado a su cable. Sin reglas de negocio, sin
//    tablas ni RPC directas, sin escribir almacenes legacy.
// 4) Sin alias nuevos: cada operacion canonica tiene exactamente una ruta.
// =====================================================================

/**
 * Una ruta legacy con su sucesora canonica. [sunsetAt] null hasta que el propietario la apruebe. [successorPath] null =
 * la sucesora canonica aun NO existe como ruta (contrato congelado TARGET, Slice 3+): no se anuncia un `Link` a una
 * ruta que no existe (MASTER3-API-003).
 */
data class LegacyEndpoint(
    val endpointId: String,
    val method: String,
    val legacyPath: String,
    val successorPath: String?,
    val deprecatedAt: Instant,
    val sunsetAt: Instant? = null,
)

/**
 * Las 9 rutas de compatibilidad que SLICE 0 marca como deprecadas. EP-124 y
 * EP-218 lo reciben en su corte de SLICE 1. Ninguna tiene `Sunset` todavia: la
 * fecha es decision del propietario por endpoint cuando salga la release
 * canonica de OC/OB, y nunca se retira una ruta con trafico en el contador.
 */
object LegacyEndpoints {
    /** Fecha de entrada de SLICE 0: cuando empieza la ventana de deprecacion. */
    val SLICE_0_DEPRECATED_AT: Instant = Instant.parse("2026-09-28T00:00:00Z")

    val EP_046 = LegacyEndpoint("EP-046", "GET", "/api/v1/admin/dashboard", "/api/v1/admin/dashboard/overview", SLICE_0_DEPRECATED_AT)
    // MASTER5-OPENAPI-002: las sucesoras de noticias/tipos-noticia (/api/v1/admin/noticias, /tipos-noticia) NO existen
    // como ruta ni en el OpenAPI congelado (TARGET futuro); sin sucesora direccionable no se anuncia `Link` (igual que APP-*).
    // EP-046 y EP-144 si tienen sucesora ruteada en AdminV1Routes.
    val EP_015 = LegacyEndpoint("EP-015", "DELETE", "/content/noticias/{noticiaId}", null, SLICE_0_DEPRECATED_AT)
    val EP_025 = LegacyEndpoint("EP-025", "GET", "/admin/news-types", null, SLICE_0_DEPRECATED_AT)
    val EP_111 = LegacyEndpoint("EP-111", "GET", "/content/noticias", null, SLICE_0_DEPRECATED_AT)
    val EP_145 = LegacyEndpoint("EP-145", "POST", "/admin/news-types", null, SLICE_0_DEPRECATED_AT)
    val EP_199 = LegacyEndpoint("EP-199", "POST", "/content/noticias", null, SLICE_0_DEPRECATED_AT)
    val EP_200 = LegacyEndpoint("EP-200", "POST", "/content/noticias/assets", null, SLICE_0_DEPRECATED_AT)
    val EP_229 = LegacyEndpoint("EP-229", "PUT", "/content/noticias", null, SLICE_0_DEPRECATED_AT)
    val EP_144 = LegacyEndpoint("EP-144", "POST", "/admin/moderation/actions", "/api/v1/admin/moderacion/actions", SLICE_0_DEPRECATED_AT)

    val SLICE_0: List<LegacyEndpoint> = listOf(EP_046, EP_015, EP_025, EP_111, EP_145, EP_199, EP_200, EP_229, EP_144)

    /** Fecha de entrada de SLICE 1: EP-124 y EP-218 quedan deprecadas al cortar perfil/onboarding al servicio canonico. */
    val SLICE_1_DEPRECATED_AT: Instant = Instant.parse("2026-09-30T00:00:00Z")

    /** `GET /v1/auth/me`: la lectura ahora es `perfiles_usuario` via el servicio canonico, no `public.usuario`. */
    val EP_124 = LegacyEndpoint("EP-124", "GET", "/v1/auth/me", "/api/v1/app/me", SLICE_1_DEPRECATED_AT)

    /** `POST /v1/auth/register/profile`: escribe `perfiles_usuario` via `app_completar_onboarding`, nunca `public.usuario`. */
    val EP_218 = LegacyEndpoint("EP-218", "POST", "/v1/auth/register/profile", "/api/v1/app/me/onboarding", SLICE_1_DEPRECATED_AT)

    val SLICE_1: List<LegacyEndpoint> = listOf(EP_124, EP_218)

    // MASTER2-OPENAPI-006: las 9 rutas vivas de `/api/v1/app` (AppV1Routes.kt) que el contrato congelado no declara. Ninguna
    // tiene sucesora ROUTEADA todavia (MASTER3-API-003): `iniciar` -> `POST /api/v1/app/examenes/{examId}/intentos` y
    // `responder` -> `/api/v1/app/intentos/{attemptId}/respuestas/{preguntaId}` son TARGET de Slice 3 y solo
    // recibiran `Link` cuando existan (anunciar la coleccion truncada apuntaba a URIs inexistentes/no relacionadas).
    // Criterio de retirada: contador `legacy_route_requests_total{endpoint="APP-*"}` a cero tras la release canonica de OB.
    val APP_HOME = LegacyEndpoint("APP-HOME", "GET", "/api/v1/app/home", null, SLICE_1_DEPRECATED_AT)
    val APP_ESTADISTICAS = LegacyEndpoint("APP-ESTADISTICAS", "GET", "/api/v1/app/estadisticas", null, SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_CATALOG = LegacyEndpoint("APP-COMMERCIAL-CATALOG", "GET", "/api/v1/app/commercial/catalog", null, SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_PURCHASES = LegacyEndpoint("APP-COMMERCIAL-PURCHASES", "GET", "/api/v1/app/commercial/purchases", null, SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_SUBSCRIPTIONS = LegacyEndpoint("APP-COMMERCIAL-SUBSCRIPTIONS", "GET", "/api/v1/app/commercial/subscriptions", null, SLICE_1_DEPRECATED_AT)
    val APP_EVENTOS = LegacyEndpoint("APP-EVENTOS", "POST", "/api/v1/app/eventos", null, SLICE_1_DEPRECATED_AT)
    val APP_FEEDBACK_PREGUNTA = LegacyEndpoint("APP-FEEDBACK-PREGUNTA", "POST", "/api/v1/app/feedback/pregunta", null, SLICE_1_DEPRECATED_AT)
    val APP_INICIAR = LegacyEndpoint(
        "APP-EXAMEN-INICIAR", "POST", "/api/v1/app/examenes/{id}/iniciar", null, SLICE_1_DEPRECATED_AT,
    )
    val APP_RESPONDER = LegacyEndpoint(
        "APP-INTENTO-RESPONDER", "POST", "/api/v1/app/intentos/{id}/responder", null, SLICE_1_DEPRECATED_AT,
    )

    val SLICE_1_APP: List<LegacyEndpoint> = listOf(
        APP_HOME, APP_ESTADISTICAS, APP_COMMERCIAL_CATALOG, APP_COMMERCIAL_PURCHASES, APP_COMMERCIAL_SUBSCRIPTIONS,
        APP_EVENTOS, APP_FEEDBACK_PREGUNTA, APP_INICIAR, APP_RESPONDER,
    )
}

/** Nombre Micrometer; Prometheus lo publica como `legacy_route_requests_total`. */
internal const val LEGACY_ROUTE_REQUESTS_METRIC = "legacy.route.requests"

internal object LegacyHeaders {
    const val DEPRECATION = "Deprecation"
    const val SUNSET = "Sunset"

    /** RFC 9745: `@<unix-seconds>`. */
    fun deprecation(at: Instant): String = "@${at.epochSecond}"

    /** RFC 8594: IMF-fixdate. */
    fun sunset(at: Instant): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC))

    private val TEMPLATE_VARIABLE = Regex("\\{([^/{}]+)}")

    /**
     * RFC 8288: el destino es una URI-Reference; una plantilla OpenAPI (`{noticiaId}`) no lo es. MASTER4-API-004: ya no se
     * trunca al primer `{` (eso anunciaba una coleccion que no es la sucesora del recurso). Cada variable se sustituye por
     * su valor real (codificado como segmento de ruta) tomado de [params] (MASTER5-API-003: SOLO parametros de RUTA, nunca de query: la query la controla el llamador); si alguna no se puede resolver (la ruta legacy
     * no la lleva en la URL, p.ej. va en el cuerpo) NO hay sucesor direccionable y se devuelve null (sin `Link`).
     */
    fun successorLink(path: String, params: (String) -> String? = { null }): String? {
        var unresolved = false
        val uri = TEMPLATE_VARIABLE.replace(path) { m ->
            val value = params(m.groupValues[1])?.takeIf { it.isNotEmpty() }
            if (value == null) unresolved = true
            value?.encodeURLPathPart().orEmpty()
        }
        if (unresolved || '{' in uri || '}' in uri) return null
        return "<$uri>; rel=\"successor-version\""
    }
}

class LegacyRouteConfig {
    lateinit var endpoint: LegacyEndpoint
    internal var registry: MeterRegistry = BackendPrometheusMetrics.registry
}

/**
 * Plugin de RUTA: se instala en la hoja legacy concreta ([legacyRoute]), no en
 * un prefijo, para no marcar metodos hermanos que no son esa ruta.
 */
val LegacyRoute = createRouteScopedPlugin("LegacyRoute", ::LegacyRouteConfig) {
    val endpoint = pluginConfig.endpoint
    val counter: Counter = Counter.builder(LEGACY_ROUTE_REQUESTS_METRIC)
        .description("Peticiones a rutas legacy deprecadas (criterio de retirada: cero)")
        .tag("endpoint", endpoint.endpointId)
        .register(pluginConfig.registry)
    val deprecation = LegacyHeaders.deprecation(endpoint.deprecatedAt)
    val successor = endpoint.successorPath?.takeIf { it.isNotBlank() }
    val sunset = endpoint.sunsetAt?.let(LegacyHeaders::sunset)

    onCall { call ->
        counter.increment()
        // Antes del handler: tambien las respuestas de error (StatusPages) las llevan.
        call.response.header(LegacyHeaders.DEPRECATION, deprecation)
        successor?.let { path -> LegacyHeaders.successorLink(path) { call.pathParameterValue(it) } }
            ?.let { call.response.header(HttpHeaders.Link, it) }
        sunset?.let { call.response.header(LegacyHeaders.SUNSET, it) }
    }
}

/** Marca esta ruta hoja como legacy deprecada. Devuelve la misma ruta. */
fun Route.legacyRoute(endpoint: LegacyEndpoint): Route = apply {
    install(LegacyRoute) { this.endpoint = endpoint }
}

internal fun Route.legacyRoute(endpoint: LegacyEndpoint, registry: MeterRegistry): Route = apply {
    install(LegacyRoute) {
        this.endpoint = endpoint
        this.registry = registry
    }
}

/**
 * Patron de REFERENCIA para una ruta legacy: traducir, llamar al servicio canonico y
 * traducir de vuelta. NOTA: hoy NO esta cableado en produccion; solo lo instancia
 * `LegacyRouteTest`. Los endpoints EP-124/EP-218 se sirven con `LegacyProfileAdapter`
 * (`SpecialtyResolver.kt`) y rutas escritas a mano; unificarlos es deuda registrada.
 *
 * [canonical] es la MISMA funcion de servicio que usa la ruta canonica (no una
 * copia): las reglas de negocio, las escrituras y los errores de dominio viven
 * ahi. Las dos traducciones son puras; un error del servicio sube tal cual a
 * StatusPages y sale con el sobre canonico.
 */
class LegacyAdapter<LegacyIn, CanonicalIn, CanonicalOut, LegacyOut>(
    val endpoint: LegacyEndpoint,
    private val toCanonical: (LegacyIn) -> CanonicalIn,
    private val canonical: suspend (CanonicalIn) -> CanonicalOut,
    private val toLegacy: (CanonicalOut) -> LegacyOut,
) {
    suspend operator fun invoke(input: LegacyIn): LegacyOut = toLegacy(canonical(toCanonical(input)))
}

/**
 * MASTER5-API-003: valor de un parametro de RUTA. `call.parameters` de Ktor 2 mezcla ruta Y query, y con un nombre repetido
 * `call.parameters[name]` puede devolver el de la query (controlado por el llamador). Las ocurrencias que NO son de la
 * query son las de la ruta: a los valores de `call.parameters` se les resta, una a una, los de `queryParameters`.
 */
internal fun io.ktor.server.application.ApplicationCall.pathParameterValue(name: String): String? {
    val remaining = parameters.getAll(name).orEmpty().toMutableList()
    request.queryParameters.getAll(name).orEmpty().forEach { remaining.remove(it) }
    return remaining.firstOrNull()
}
