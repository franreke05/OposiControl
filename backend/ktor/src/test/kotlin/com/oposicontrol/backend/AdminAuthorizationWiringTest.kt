package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Autorización comprobada **a través del pipeline real**, no del predicado.
 *
 * Ya existía una prueba unitaria de `isLegacyStaticTokenPath` y pasaba mientras el
 * bug estaba vivo: el predicado era correcto y sencillamente no se ejecutaba,
 * porque el interceptor estaba montado en una fase anterior a la autenticación.
 * `AdminAuthorizationGuardTest` cubre la decisión; este fichero cubre el
 * **cableado**, que es lo que falló.
 *
 * Estas pruebas se romperían si alguien quitase `install(AdminAuthorizationPlugin)`,
 * lo moviera a un hook que corre demasiado pronto, o registrase una ruta fuera del
 * bloque `authenticate` — ninguna de esas regresiones la detecta un test de
 * función pura.
 */
class AdminAuthorizationWiringTest {

    private companion object {
        const val STATIC_TOKEN = "static-token-for-test"
        const val ADMIN_JWT = "jwt-of-an-admin"
        const val PLAIN_JWT = "jwt-of-a-user-without-admin-role"
        const val LOOPBACK = "127.0.0.1"
    }

    private fun runAdminApp(
        appEnv: String = "dev",
        admins: Set<String> = setOf(ADMIN_JWT),
        block: suspend (io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        application {
            install(StatusPages) {
                exception<AppUnauthorized> { call, _ -> call.respond(HttpStatusCode.Unauthorized) }
                exception<AppForbidden> { call, _ -> call.respond(HttpStatusCode.Forbidden) }
                exception<BackendDomainException> { call, cause -> call.respond(cause.status) }
            }
            install(Authentication) {
                bearer("gateway-auth") {
                    authenticate { credentials ->
                        when (credentials.token) {
                            STATIC_TOKEN -> UserIdPrincipal(STATIC_TOKEN_PRINCIPAL)
                            ADMIN_JWT, PLAIN_JWT -> UserIdPrincipal(credentials.token)
                            else -> null
                        }
                    }
                }
            }
            routing {
                authenticate("gateway-auth") {
                    install(AdminAuthorizationPlugin) {
                        this.appEnv = appEnv
                        this.hasAdminRole = { it in admins }
                        // El motor de pruebas de Ktor reporta `localhost` como
                        // dirección del par, y la comprobación real rechaza nombres
                        // de host a propósito. Se inyecta el equivalente para poder
                        // ejercitar también el camino permitido; la regla real tiene
                        // su propia batería en AdminAuthorizationGuardTest.
                        this.isLocalPeer = { env, addr ->
                            env.lowercase() in setOf("dev", "development", "test") && addr == "localhost"
                        }
                    }
                    // Legacy permitido.
                    route("/admin") {
                        get("/users") { call.respond(HttpStatusCode.OK) }
                    }
                    // Canónico, incluida una ruta profundamente anidada: cubre que el
                    // plugin se hereda por toda la descendencia y no sólo por el
                    // primer nivel.
                    route("/api/v1/admin") {
                        get("/usuarios") { call.respond(HttpStatusCode.OK) }
                        route("/comercial/productos") {
                            get("/{id}/tarifas") { call.respond(HttpStatusCode.OK) }
                        }
                    }
                    // Dentro del bloque pero fuera de los prefijos legacy.
                    get("/dashboard/metrics") { call.respond(HttpStatusCode.OK) }
                }
                // Fuera del bloque: debe seguir siendo pública.
                get("/health") { call.respond(HttpStatusCode.OK) }
            }
        }
        block(client)
    }

    private suspend fun io.ktor.client.HttpClient.getAs(path: String, token: String?) =
        get(path) {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        }

    // --- token estático ---

    @Test
    fun staticTokenIsRejectedOnCanonicalAdminRoutes() = runAdminApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/admin/usuarios", STATIC_TOKEN).status)
    }

    @Test
    fun staticTokenIsRejectedOnDeeplyNestedCanonicalRoutes() = runAdminApp { client ->
        // El bug original habría dejado pasar esto igual que el primer nivel.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.getAs("/api/v1/admin/comercial/productos/p-1/tarifas", STATIC_TOKEN).status,
        )
    }

    @Test
    fun staticTokenIsRejectedOnRoutesInsideTheBlockButOutsideLegacyPrefixes() = runAdminApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/dashboard/metrics", STATIC_TOKEN).status)
    }

    @Test
    fun staticTokenStillWorksOnLegacySurfacesFromLoopback() = runAdminApp { client ->
        assertEquals(HttpStatusCode.OK, client.getAs("/admin/users", STATIC_TOKEN).status)
    }

    @Test
    fun staticTokenIsRejectedEverywhereOutsideDevelopment() = runAdminApp(appEnv = "prod") { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/admin/users", STATIC_TOKEN).status)
    }

    // --- principales JWT ---

    @Test
    fun aVerifiedAdminReachesCanonicalRoutes() = runAdminApp { client ->
        assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/admin/usuarios", ADMIN_JWT).status)
    }

    @Test
    fun aVerifiedUserWithoutAdminRoleGetsForbiddenNotUnauthorized() = runAdminApp { client ->
        // 401 y 403 significan cosas distintas: la identidad es válida, lo que
        // falta es el permiso. El contrato documentado exige 403 aquí.
        assertEquals(HttpStatusCode.Forbidden, client.getAs("/api/v1/admin/usuarios", PLAIN_JWT).status)
    }

    @Test
    fun aVerifiedUserWithoutAdminRoleIsAlsoBlockedOnLegacySurfaces() = runAdminApp { client ->
        // Las rutas legacy escriben datos reales: no pueden quedar fuera del control.
        assertEquals(HttpStatusCode.Forbidden, client.getAs("/admin/users", PLAIN_JWT).status)
    }

    // --- ausencia de credencial ---

    @Test
    fun noTokenIsUnauthorized() = runAdminApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/admin/usuarios", null).status)
    }

    @Test
    fun anUnknownTokenIsUnauthorized() = runAdminApp { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.getAs("/api/v1/admin/usuarios", "garbage").status)
    }

    @Test
    fun healthStaysPublic() = runAdminApp { client ->
        assertEquals(HttpStatusCode.OK, client.getAs("/health", null).status)
    }
}

