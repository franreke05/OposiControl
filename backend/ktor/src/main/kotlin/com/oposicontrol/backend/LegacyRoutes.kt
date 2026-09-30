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

/**
 * Una ruta legacy con su sucesora canonica. [sunsetAt] null hasta que el propietario la apruebe. [successorPath] vacio =
 * el contrato congelado aun NO declara sucesora (no se anuncia un `Link` a una ruta que no existe).
 */
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

    // MASTER2-OPENAPI-006: las 9 rutas vivas de `/api/v1/app` (AppV1Routes.kt) que el contrato congelado no declara. Solo
    // `iniciar` y `responder` tienen sucesora congelada (Slice 3, TARGET); el resto no tiene sucesora hasta Slice 2+.
    // Criterio de retirada: contador `legacy_route_requests_total{endpoint="APP-*"}` a cero tras la release canonica de OB.
    val APP_HOME = LegacyEndpoint("APP-HOME", "GET", "/api/v1/app/home", "", SLICE_1_DEPRECATED_AT)
    val APP_ESTADISTICAS = LegacyEndpoint("APP-ESTADISTICAS", "GET", "/api/v1/app/estadisticas", "", SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_CATALOG = LegacyEndpoint("APP-COMMERCIAL-CATALOG", "GET", "/api/v1/app/commercial/catalog", "", SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_PURCHASES = LegacyEndpoint("APP-COMMERCIAL-PURCHASES", "GET", "/api/v1/app/commercial/purchases", "", SLICE_1_DEPRECATED_AT)
    val APP_COMMERCIAL_SUBSCRIPTIONS = LegacyEndpoint("APP-COMMERCIAL-SUBSCRIPTIONS", "GET", "/api/v1/app/commercial/subscriptions", "", SLICE_1_DEPRECATED_AT)
    val APP_EVENTOS = LegacyEndpoint("APP-EVENTOS", "POST", "/api/v1/app/eventos", "", SLICE_1_DEPRECATED_AT)
    val APP_FEEDBACK_PREGUNTA = LegacyEndpoint("APP-FEEDBACK-PREGUNTA", "POST", "/api/v1/app/feedback/pregunta", "", SLICE_1_DEPRECATED_AT)
    val APP_INICIAR = LegacyEndpoint(
        "APP-EXAMEN-INICIAR", "POST", "/api/v1/app/examenes/{id}/iniciar", "/api/v1/app/examenes/{examId}/intentos", SLICE_1_DEPRECATED_AT,
    )
    val APP_RESPONDER = LegacyEndpoint(
        "APP-INTENTO-RESPONDER", "POST", "/api/v1/app/intentos/{id}/responder",
        "/api/v1/app/intentos/{attemptId}/respuestas/{preguntaId}", SLICE_1_DEPRECATED_AT,
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

    /**
     * RFC 8288: el destino es una URI-Reference; `{noticiaId}` (plantilla OpenAPI) no lo es. Los segmentos con llaves se
     * descartan y se anuncia la URI de la coleccion.
     */
    fun successorLink(path: String): String {
        val uri = path.split("/").takeWhile { "{" !in it && "}" !in it }.joinToString("/")
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
    val link = endpoint.successorPath.takeIf { it.isNotBlank() }?.let(LegacyHeaders::successorLink)
    val sunset = endpoint.sunsetAt?.let(LegacyHeaders::sunset)

    onCall { call ->
        counter.increment()
        // Antes del handler: tambien las respuestas de error (StatusPages) las llevan.
        call.response.header(LegacyHeaders.DEPRECATION, deprecation)
        link?.let { call.response.header(HttpHeaders.Link, it) }
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
