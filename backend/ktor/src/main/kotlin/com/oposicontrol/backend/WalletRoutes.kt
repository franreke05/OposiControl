package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** Lo que las rutas de cartera necesitan (SLICE 7). Se construye en el arranque cuando hay resolver de identidad. */
internal class WalletRuntime(
    val resolver: CanonicalIdentityResolver,
    val wallet: WalletService,
    /** E2-P02 / FA19: limite por usuario (WALLET_READ) antes de leer el perfil. Por defecto no limita (pruebas). */
    val limit: suspend (CanonicalUserId) -> Unit = {},
) {
    companion object {
        /** Cableado de produccion: la misma credencial de servicio que el resto del backend. */
        fun fromEnvironment(
            resolver: CanonicalIdentityResolver,
            limiter: OposiBotsAuthRateLimiter? = null,
            environment: (String) -> String? = System::getenv,
        ): WalletRuntime? {
            val transport = SupabaseRequestTransport.fromEnvironment(environment) ?: return null
            return WalletRuntime(
                resolver,
                WalletService(WalletGateway(upstream = { path -> transport.get(path) })),
                limiter.subjectLimit(OposiBotsAuthOperation.WALLET_READ),
            )
        }
    }
}

/**
 * Lecturas de cartera de SLICE 7 (`openapi/wallet/wallet.yaml`), SOLO LECTURA:
 *
 *  - D6-01 `GET /api/v1/app/wallet`              resumen: saldo, ganancias, 5 recientes, consumibles (objeto DESNUDO)
 *  - D6-02 `GET /api/v1/app/wallet/movimientos`  libro paginado por cursor (`tipo`, `origen`)
 *
 * Identidad = SOLO el `sub` del JWT, deny-by-default como el resto de `/api/v1/app`: [CanonicalIdentityResolver.activeIdentity]
 * (401 / 403 ACCOUNT_BLOCKED / ACCOUNT_DELETION_PENDING / PROFILE_MISSING) ANTES de validar parametros o tocar la base. No hay
 * ningun parametro de usuario: un usuario solo puede leer SU cartera. Sin runtime las rutas existen pero fallan cerradas (500).
 */
internal fun Route.walletRoutes(runtime: WalletRuntime?) {
    route("/api/v1/app/wallet") {
        get {
            val rt = runtime.orMisconfigured()
            val identity = rt.resolver.activeIdentity(call, rt.limit)
            requireOnlyKnownQueryParams(call.request.queryParameters, emptySet())
            val summary = rt.wallet.summary(identity.userId)
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.respond(summary)
        }
        get("/movimientos") {
            val rt = runtime.orMisconfigured()
            val identity = rt.resolver.activeIdentity(call, rt.limit)
            val query = parseWalletMovementsQuery(call.request.queryParameters)
            val page = rt.wallet.movements(identity.userId, query)
            // El saldo es del usuario y cambia con cada movimiento: nunca se cachea.
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.respond(page)
        }
    }
}

private fun WalletRuntime?.orMisconfigured(): WalletRuntime =
    this ?: throw CanonicalErrorCode.INTERNAL.exception()
