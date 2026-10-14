package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** Lo que las rutas de catalogo y derechos necesitan (SLICE 2). Se construye en el arranque cuando hay resolver de identidad. */
internal class CatalogRuntime(
    val resolver: CanonicalIdentityResolver,
    val catalog: CatalogService,
    val entitlement: EntitlementService,
) {
    companion object {
        /** Cableado de produccion: misma credencial de servicio y mismo bucket de portadas que el resto del backend. */
        fun fromEnvironment(resolver: CanonicalIdentityResolver, environment: (String) -> String? = System::getenv): CatalogRuntime? {
            val upstream = TransportCatalogUpstream.fromEnvironment(environment) ?: return null
            val gateway = CatalogGateway(upstream)
            return CatalogRuntime(resolver, CatalogService(gateway), EntitlementService(gateway))
        }
    }
}

/**
 * Lecturas de consumidor de SLICE 2 (`openapi/catalog/catalog.yaml`, `openapi/entitlements/entitlements.yaml`):
 *
 *  - D2-01 `GET /api/v1/app/examenes`            lista paginada por cursor, `accessState`
 *  - D2-02 `GET /api/v1/app/examenes/{examId}`   detalle SIN cuerpos de pregunta (SEC-004)
 *  - D2-13 `GET /api/v1/app/me/examenes`         `vista` EN_CURSO / ADQUIRIDOS / COLECCION
 *  - D2-17 `GET /api/v1/public/especialidades`   catalogo de especialidades activas (ANON, sin identidad, cacheado)
 *  - D7-02 `GET /api/v1/app/me/entitlement`      estado premium + creditos (solo para mostrar; la puerta es D7-25)
 *
 * Identidad = SOLO el `sub` del JWT, deny-by-default como el resto de `/api/v1/app`: [CanonicalIdentityResolver.activeIdentity]
 * (401 / 403 ACCOUNT_BLOCKED / ACCOUNT_DELETION_PENDING / PROFILE_MISSING) ANTES de validar parametros o tocar la base.
 * Sin runtime (sin resolver de identidad) las rutas existen pero fallan cerrado con 500: nunca sirven sin identidad.
 */
internal fun Route.catalogRoutes(runtime: CatalogRuntime?) {
    // D2-17 (ANON): catalogo de especialidades activas con ids REALES. Sin sesion: una cache de proceso acota la carga en la
    // base (como las lecturas publicas de /v1) y la respuesta es cacheable por el cliente.
    val specialtiesCache = java.util.concurrent.atomic.AtomicReference<Pair<Long, PublicSpecialtiesResponse>?>(null)
    route("/api/v1/public") {
        get("/especialidades") {
            val rt = runtime.orMisconfigured()
            val now = System.currentTimeMillis()
            val body = specialtiesCache.get()?.takeIf { now - it.first < SPECIALTIES_CACHE_MS }?.second
                ?: rt.catalog.publicSpecialties().also { specialtiesCache.set(now to it) }
            call.cache("public, max-age=300")
            call.respond(body)
        }
    }
    route("/api/v1/app") {
        get("/examenes") {
            val rt = runtime.orMisconfigured()
            val identity = rt.resolver.activeIdentity(call)
            val query = parseExamListQuery(call.request.queryParameters)
            val page = rt.catalog.listExams(identity.userId, query)
            call.cache("private, max-age=60")
            call.respond(page)
        }
        get("/examenes/{examId}") {
            val rt = runtime.orMisconfigured()
            val identity = rt.resolver.activeIdentity(call)
            val examId = call.parameters["examId"].orEmpty().trim()
            if (!examId.isUuid()) throw validationError(ErrorIssue("examId", CanonicalIssue.INVALID_FORMAT))
            val exam = rt.catalog.getExam(identity.userId, examId.lowercase())
            // accessState es por espectador: nunca se cachea.
            call.cache("private, no-store")
            call.respond(exam)
        }
        route("/me") {
            get("/examenes") {
                val rt = runtime.orMisconfigured()
                val identity = rt.resolver.activeIdentity(call)
                val query = parseMyExamsQuery(call.request.queryParameters)
                val page = rt.catalog.listMyExams(identity.userId, query)
                call.cache("private, max-age=30")
                call.respond(page)
            }
            get("/entitlement") {
                val rt = runtime.orMisconfigured()
                val identity = rt.resolver.activeIdentity(call)
                val entitlement = rt.entitlement.entitlement(identity.userId)
                call.cache("private, no-store")
                // Objeto DESNUDO (sin envoltorio `data`): OAS `Entitlement`.
                call.respond(entitlement)
            }
        }
    }
}

/** D2-17: vida de la cache de proceso del catalogo de especialidades. */
private const val SPECIALTIES_CACHE_MS = 60_000L

private fun ApplicationCall.cache(value: String) {
    response.header(HttpHeaders.CacheControl, value)
}

private fun CatalogRuntime?.orMisconfigured(): CatalogRuntime =
    this ?: throw CanonicalErrorCode.INTERNAL.exception()
