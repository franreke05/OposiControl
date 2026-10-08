package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** Lo que las lecturas de consumidor de FA15 necesitan. Se construye en el arranque cuando hay resolver de identidad. */
internal class AppReadsRuntime(
    val resolver: CanonicalIdentityResolver,
    val reads: AppReadsService,
) {
    companion object {
        /** Cableado de produccion: la misma credencial de servicio que el resto del backend. */
        fun fromEnvironment(resolver: CanonicalIdentityResolver, environment: (String) -> String? = System::getenv): AppReadsRuntime? {
            val transport = SupabaseRequestTransport.fromEnvironment(environment) ?: return null
            return AppReadsRuntime(resolver, AppReadsService(AppReadsGateway(upstream = { path -> transport.get(path) })))
        }
    }
}

/**
 * Lecturas de consumidor de FA15 (`openapi/billing/billing.yaml`, `openapi/community/community.yaml`), SOLO LECTURA:
 *
 *  - D7-05 `GET /api/v1/app/me/billing-history`   compras y suscripciones propias, por cursor (`kind`)
 *  - D5-01 `GET /api/v1/app/oposired/categorias`  categorias activas de OposiRed (catalogo acotado)
 *  - D5-16 `GET /api/v1/app/opositester/me`       contadores propios de OposiTester (objeto DESNUDO)
 *
 * Identidad = SOLO el `sub` del JWT, deny-by-default como el resto de `/api/v1/app`: [CanonicalIdentityResolver.activeIdentity]
 * (401 / 403 ACCOUNT_BLOCKED / ACCOUNT_DELETION_PENDING / PROFILE_MISSING) ANTES de validar parametros o tocar la base. No hay
 * ningun parametro de usuario. Sin runtime las rutas existen pero fallan cerradas (500).
 */
internal fun Route.appReadsRoutes(runtime: AppReadsRuntime?) {
    route("/api/v1/app") {
        get("/me/billing-history") {
            val rt = runtime.orMisconfigured()
            val identity = rt.resolver.activeIdentity(call)
            val query = parseBillingHistoryQuery(call.request.queryParameters)
            val page = rt.reads.billingHistory(identity.userId, query)
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.respond(page)
        }
        get("/oposired/categorias") {
            val rt = runtime.orMisconfigured()
            rt.resolver.activeIdentity(call)
            val categories = rt.reads.forumCategories()
            call.response.header(HttpHeaders.CacheControl, "private, max-age=300")
            call.respond(categories)
        }
        get("/opositester/me") {
            val rt = runtime.orMisconfigured()
            val identity = rt.resolver.activeIdentity(call)
            val status = rt.reads.opositester(identity.userId)
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.respond(status)
        }
    }
}

private fun AppReadsRuntime?.orMisconfigured(): AppReadsRuntime =
    this ?: throw CanonicalErrorCode.INTERNAL.exception()
