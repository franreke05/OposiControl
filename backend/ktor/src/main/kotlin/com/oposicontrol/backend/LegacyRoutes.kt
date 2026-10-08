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
    // FA11 (D4-01..D4-11): las sucesoras canonicas de noticias y tipos de noticia YA estan ruteadas (NewsRoutes.kt) y comparten
    // servicio (NewsService) con estas rutas. El `Link` solo se emite cuando el id de la sucesora viaja en la URL legacy
    // (EP-015); en EP-229 el id va en el cuerpo y en EP-145 el `codigo`: ahi la sucesora queda registrada pero sin `Link`.
    // Ninguna se retira: el contador `legacy_route_requests_total` tiene que llegar a cero (y el cliente OC migrar) antes.
    val EP_015 = LegacyEndpoint("EP-015", "DELETE", "/content/noticias/{noticiaId}", "/api/v1/admin/noticias/{noticiaId}", SLICE_0_DEPRECATED_AT)
    val EP_025 = LegacyEndpoint("EP-025", "GET", "/admin/news-types", "/api/v1/admin/tipos-noticia", SLICE_0_DEPRECATED_AT)
    val EP_111 = LegacyEndpoint("EP-111", "GET", "/content/noticias", "/api/v1/admin/noticias", SLICE_0_DEPRECATED_AT)
    val EP_145 = LegacyEndpoint("EP-145", "POST", "/admin/news-types", "/api/v1/admin/tipos-noticia/{codigo}", SLICE_0_DEPRECATED_AT)
    val EP_199 = LegacyEndpoint("EP-199", "POST", "/content/noticias", "/api/v1/admin/noticias", SLICE_0_DEPRECATED_AT)
    val EP_200 = LegacyEndpoint("EP-200", "POST", "/content/noticias/assets", "/api/v1/admin/noticias/assets", SLICE_0_DEPRECATED_AT)
    val EP_229 = LegacyEndpoint("EP-229", "PUT", "/content/noticias", "/api/v1/admin/noticias/{noticiaId}", SLICE_0_DEPRECATED_AT)
    val EP_144 = LegacyEndpoint("EP-144", "POST", "/admin/moderation/actions", "/api/v1/admin/moderacion/actions", SLICE_0_DEPRECATED_AT)

    val SLICE_0: List<LegacyEndpoint> = listOf(EP_046, EP_015, EP_025, EP_111, EP_145, EP_199, EP_200, EP_229, EP_144)

    /** Fecha de entrada de SLICE 1: EP-124 y EP-218 quedan deprecadas al cortar perfil/onboarding al servicio canonico. */
    val SLICE_1_DEPRECATED_AT: Instant = Instant.parse("2026-09-30T00:00:00Z")

    /** `GET /v1/auth/me`: la lectura ahora es `perfiles_usuario` via el servicio canonico, no `public.usuario`. */
    val EP_124 = LegacyEndpoint("EP-124", "GET", "/v1/auth/me", "/api/v1/app/me", SLICE_1_DEPRECATED_AT)

    /** `POST /v1/auth/register/profile`: escribe `perfiles_usuario` via `app_completar_onboarding`, nunca `public.usuario`. */
    val EP_218 = LegacyEndpoint("EP-218", "POST", "/v1/auth/register/profile", "/api/v1/app/me/onboarding", SLICE_1_DEPRECATED_AT)

    val SLICE_1: List<LegacyEndpoint> = listOf(EP_124, EP_218)

    // MASTER2-OPENAPI-006: las 9 rutas vivas de `/api/v1/app` (AppV1Routes.kt) que el contrato congelado no declara. Las 7
    // primeras no tienen sucesora ROUTEADA todavia (MASTER3-API-003): no anuncian `Link`.
    // Criterio de retirada: contador `legacy_route_requests_total{endpoint="APP-*"}` a cero tras la release canonica de OB.
    val APP_HOME = LegacyEndpoint("APP-HOME", "GET", "/api/v1/app/home", null, SLICE_1_DEPRECATED_AT)
    val APP_ESTADISTICAS = LegacyEndpoint("APP-ESTADISTICAS", "GET", "/api/v1/app/estadisticas", null, SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_CATALOG = LegacyEndpoint("APP-COMMERCIAL-CATALOG", "GET", "/api/v1/app/commercial/catalog", null, SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_PURCHASES = LegacyEndpoint("APP-COMMERCIAL-PURCHASES", "GET", "/api/v1/app/commercial/purchases", null, SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_SUBSCRIPTIONS = LegacyEndpoint("APP-COMMERCIAL-SUBSCRIPTIONS", "GET", "/api/v1/app/commercial/subscriptions", null, SLICE_1_DEPRECATED_AT)
    val APP_EVENTOS = LegacyEndpoint("APP-EVENTOS", "POST", "/api/v1/app/eventos", null, SLICE_1_DEPRECATED_AT)
    val APP_FEEDBACK_PREGUNTA = LegacyEndpoint("APP-FEEDBACK-PREGUNTA", "POST", "/api/v1/app/feedback/pregunta", null, SLICE_1_DEPRECATED_AT)
    // GATE SLICE 3 (F-01 / F-02): `iniciar` y `responder` estan RETIRADAS: responden 410 LEGACY_ATTEMPT_ROUTE_RETIRED antes
    // de autenticar, leer el cuerpo o tocar upstream ([rejectLegacyAttemptRoute]). `iniciar` abria intentos sin pasar por
    // EntitlementPolicy (D7-25) y `responder` devolvia la correccion al momento (D2-09 la oculta) contra el examen vivo. Sin
    // llamador vivo (la app consumidora no tiene ninguna llamada de examen). Siguen montadas y contadas; sus sucesoras YA
    // estan ruteadas (Slice 3, AttemptRoutes.kt): D2-03 `POST /examenes/{examId}/intentos` (el `Link` usa el `{id}` de la
    // ruta legacy, que es el mismo examen) y D2-09 `PUT /intentos/{attemptId}/respuestas/{preguntaId}` (la pregunta viajaba
    // en el cuerpo legacy, que no se lee: sin URI resoluble no se emite `Link`; la sucesora queda registrada aqui).
    val APP_INICIAR = LegacyEndpoint(
        "APP-EXAMEN-INICIAR", "POST", "/api/v1/app/examenes/{id}/iniciar", "/api/v1/app/examenes/{id}/intentos", SLICE_1_DEPRECATED_AT,
    )
    val APP_RESPONDER = LegacyEndpoint(
        "APP-INTENTO-RESPONDER", "POST", "/api/v1/app/intentos/{id}/responder",
        "/api/v1/app/intentos/{id}/respuestas/{preguntaId}", SLICE_1_DEPRECATED_AT,
    )

    /** Las dos rutas legacy de intento retiradas en el gate de Slice 3 (410 sin I/O). */
    val RETIRED_ATTEMPT_ROUTES: List<LegacyEndpoint> get() = listOf(APP_INICIAR, APP_RESPONDER)

    // RC-10 (MASTERF1-API-002): escritores legacy de `public.usuario` / `app_role` SIN llamador vivo en OC/OB (route matrix
    // audit 2026-10-01). Escribian sin la guarda MASTER-AUTH-001 (borrado de cuentas administrativas) ni auditoria
    // obligatoria: responden 410 Gone ANTES de leer el cuerpo o tocar upstream. La ruta sigue montada y contada (una build
    // antigua instalada es desconocida): solo se borra con el contador a cero. Sin sucesora direccionable (null).
    val EP_152 = LegacyEndpoint("EP-152", "POST", "/admin/users", null, SLICE_1_DEPRECATED_AT)
    val EP_153 = LegacyEndpoint("EP-153", "POST", "/admin/users/{userId}", null, SLICE_1_DEPRECATED_AT)
    val EP_224 = LegacyEndpoint("EP-224", "PUT", "/admin/users/{userId}", null, SLICE_1_DEPRECATED_AT)
    val EP_001 = LegacyEndpoint("EP-001", "DELETE", "/admin/users/{userId}", null, SLICE_1_DEPRECATED_AT)
    val EP_147 = LegacyEndpoint("EP-147", "POST", "/admin/roles", null, SLICE_1_DEPRECATED_AT)
    val EP_148 = LegacyEndpoint("EP-148", "POST", "/admin/roles/{roleId}", null, SLICE_1_DEPRECATED_AT)
    val EP_222 = LegacyEndpoint("EP-222", "PUT", "/admin/roles/{roleId}", null, SLICE_1_DEPRECATED_AT)

    val RETIRED_ADMIN_WRITERS: List<LegacyEndpoint> = listOf(EP_152, EP_153, EP_224, EP_001, EP_147, EP_148, EP_222)

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
