package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decisión del propietario 2026-10-20: el rol `moderador` entra en el panel SOLO para moderar el foro. Lista cerrada
 * [MODERATOR_ADMIN_PATHS]: sesión, cola, moderar y leer restricciones. Todo lo demás sigue siendo de admin; un moderador
 * bloqueado (hasModeratorRole = false, porque exige cuenta activa) no entra en nada.
 */
class AdminModeratorForumAccessTest {
    private val mod = "mod"
    private val admin = "admin"
    private val user = "user"

    private fun decide(principal: String, path: String) = adminAuthorizationFailure(
        principalName = principal, path = path, remoteAddress = "127.0.0.1", appEnv = "dev",
        hasAdminRole = { it == admin }, hasModeratorRole = { it == mod },
    )

    @Test
    fun aModeratorReachesExactlyTheForumModerationSurface() {
        for (p in listOf("/api/v1/admin/sesion", "/api/v1/admin/foro/cola", "/api/v1/admin/foro/moderar", "/api/v1/admin/foro/restricciones", "/api/v1/admin/foro/cola/")) {
            assertNull(decide(mod, p), p)
        }
    }

    @Test
    fun aModeratorIsForbiddenEverywhereElseIncludingForumConfiguration() {
        for (p in listOf(
            "/api/v1/admin/foro/canales", "/api/v1/admin/foro/canales/1", "/api/v1/admin/foro/bloqueos", "/api/v1/admin/foro/bloqueos/x",
            "/api/v1/admin/foro/restricciones/00000000-0000-4000-8000-000000000001", "/api/v1/admin/foro/imagenes-purga",
            "/api/v1/admin/foro/colaX", "/api/v1/admin/foro", "/api/v1/admin/pedidos", "/api/v1/admin/examenes", "/api/v1/admin/auditoria/movimientos",
            "/api/v1/admin/usuarios", "/api/v1/admin/moderacion/reportes", "/admin/users",
        )) {
            assertTrue(decide(mod, p) is AppForbidden, p)
        }
    }

    @Test
    fun noRoleOrABlockedModeratorGetsNothingAndAdminsKeepEverything() {
        assertTrue(decide(user, "/api/v1/admin/foro/cola") is AppForbidden, "sin rol (o moderador bloqueado)")
        assertTrue(decide(user, "/api/v1/admin/sesion") is AppForbidden)
        assertNull(decide(admin, "/api/v1/admin/foro/canales"))
        assertNull(decide(admin, "/api/v1/admin/pedidos"))
    }

    @Test
    fun theStaticTokenIsNotAModerator() {
        assertTrue(decide(STATIC_TOKEN_PRINCIPAL, "/api/v1/admin/foro/cola") is AppUnauthorized)
    }

    /** La regla instalada de verdad sobre rutas (el plugin, no solo la función), y el rol solo se lee cuando hace falta. */
    @Test
    fun thePluginAppliesTheRuleOnRealRoutes() {
        val moderatorLookups = mutableListOf<String>()
        testApplication {
            application {
                install(StatusPages) {
                    exception<AppUnauthorized> { call, _ -> call.respond(HttpStatusCode.Unauthorized) }
                    exception<AppForbidden> { call, _ -> call.respond(HttpStatusCode.Forbidden) }
                }
                install(Authentication) {
                    bearer("gateway-auth") { authenticate { c -> c.token.takeIf { it in setOf(mod, admin, user) }?.let(::UserIdPrincipal) } }
                }
                routing {
                    authenticate("gateway-auth") {
                        install(AdminAuthorizationPlugin) {
                            appEnv = "dev"
                            hasAdminRole = { it == admin }
                            hasModeratorRole = { moderatorLookups += it; it == mod }
                        }
                        get("/api/v1/admin/sesion") { call.respond(HttpStatusCode.OK) }
                        get("/api/v1/admin/foro/cola") { call.respond(HttpStatusCode.OK) }
                        post("/api/v1/admin/foro/moderar") { call.respond(HttpStatusCode.OK) }
                        get("/api/v1/admin/foro/restricciones") { call.respond(HttpStatusCode.OK) }
                        delete("/api/v1/admin/foro/restricciones/{id}") { call.respond(HttpStatusCode.NoContent) }
                        post("/api/v1/admin/foro/canales") { call.respond(HttpStatusCode.Created) }
                        patch("/api/v1/admin/foro/canales/{id}") { call.respond(HttpStatusCode.OK) }
                        get("/api/v1/admin/pedidos") { call.respond(HttpStatusCode.OK) }
                    }
                }
            }
            suspend fun status(token: String?, method: String, path: String): HttpStatusCode {
                val r = when (method) {
                    "POST" -> client.post(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
                    "PATCH" -> client.patch(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
                    "DELETE" -> client.delete(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
                    else -> client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
                }
                return r.status
            }
            assertEquals(HttpStatusCode.OK, status(mod, "GET", "/api/v1/admin/sesion"))
            assertEquals(HttpStatusCode.OK, status(mod, "GET", "/api/v1/admin/foro/cola"))
            assertEquals(HttpStatusCode.OK, status(mod, "POST", "/api/v1/admin/foro/moderar"))
            assertEquals(HttpStatusCode.OK, status(mod, "GET", "/api/v1/admin/foro/restricciones"))
            assertEquals(HttpStatusCode.Forbidden, status(mod, "DELETE", "/api/v1/admin/foro/restricciones/u1"))
            assertEquals(HttpStatusCode.Forbidden, status(mod, "POST", "/api/v1/admin/foro/canales"))
            assertEquals(HttpStatusCode.Forbidden, status(mod, "PATCH", "/api/v1/admin/foro/canales/1"))
            assertEquals(HttpStatusCode.Forbidden, status(mod, "GET", "/api/v1/admin/pedidos"))
            assertEquals(HttpStatusCode.Forbidden, status(user, "GET", "/api/v1/admin/foro/cola"))
            assertEquals(HttpStatusCode.Created, status(admin, "POST", "/api/v1/admin/foro/canales"))
            assertEquals(HttpStatusCode.Unauthorized, status(null, "GET", "/api/v1/admin/foro/cola"))
        }
        assertTrue(admin !in moderatorLookups, "a un admin no se le consulta el rol moderador")
        assertTrue(moderatorLookups.none { it == mod } || moderatorLookups.count { it == mod } == 4, "solo en las 4 llamadas de la lista: $moderatorLookups")
    }
}
