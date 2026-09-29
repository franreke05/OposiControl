package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
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

/** Una ruta legacy con su sucesora canonica. [sunsetAt] null hasta que el propietario la apruebe. */
data class LegacyEndpoint(
    val endpointId: String,
    val method: String,
    val legacyPath: String,
    val successorPath: String,
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

    fun successorLink(path: String): String = "<$path>; rel=\"successor-version\""
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
    val link = LegacyHeaders.successorLink(endpoint.successorPath)
    val sunset = endpoint.sunsetAt?.let(LegacyHeaders::sunset)

    onCall { call ->
        counter.increment()
        // Antes del handler: tambien las respuestas de error (StatusPages) las llevan.
        call.response.header(LegacyHeaders.DEPRECATION, deprecation)
        call.response.header(HttpHeaders.Link, link)
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
 * El UNICO patron permitido para una ruta legacy que sigue viva: traducir,
 * llamar al servicio canonico y traducir de vuelta.
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
