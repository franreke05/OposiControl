package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-03: la autorización y la verificación del JWT, FUERA del hilo de la petición.
 *
 * El hilo de la petición es un executor de un solo hilo, y [RequestThreadProbe] lo
 * REGISTRA desde dentro en vez de suponerlo por el nombre: la versión anterior de
 * estos casos comparaba contra un nombre esperado y seguía en verde con el salto al
 * pool IO quitado (MUT-F03a y MUT-F03b). El porqué está en el KDoc de la sonda.
 *
 * Hay dos niveles y los dos hacen falta: la función pura ([authorizeAdminCall],
 * [verifiedJwtPrincipalOrNull]) y el CABLEADO, porque que la función salte de hilo no
 * sirve de nada si el plugin deja de llamarla (P1-1).
 */
class AdminAuthorizationOffloadTest {

    private val requestId = "authz-offload-probe-0001"
    private val userId = "33333333-3333-4333-8333-333333333333"

    // -----------------------------------------------------------------
    // Lectura de rol
    // -----------------------------------------------------------------

    @Test
    fun theRoleReadRunsOffTheRequestThreadAndKeepsTheRequestId() = onAPipelineThread { probe ->
        MDC.put(MDC_REQUEST_ID, requestId)
        var roleMdc: String? = null

        val failure = authorizeAdminCall(
            principalName = userId,
            path = "/api/v1/admin/examenes",
            remoteAddress = "203.0.113.7",
            appEnv = "prod",
            hasAdminRole = {
                probe.record("lectura de rol")
                roleMdc = MDC.get(MDC_REQUEST_ID)
                true
            },
        )

        assertNull(failure)
        probe.assertRanOffTheRequestThread("autorización de admin: cada fallo de caché es una lectura a Supabase")
        assertEquals(requestId, roleMdc, "la lectura de rol perdió el request_id al cambiar de hilo")
    }

    @Test
    fun aUserWithoutTheRoleIsStillForbidden() = onAPipelineThread {
        val failure = authorizeAdminCall(
            principalName = userId,
            path = "/api/v1/admin/examenes",
            remoteAddress = "203.0.113.7",
            appEnv = "prod",
            hasAdminRole = { false },
        )

        assertIs<AppForbidden>(failure)
    }

    /**
     * Si la lectura de rol FALLA, el fallo se propaga. Convertirlo en `false` daría un
     * 403 —"no eres admin"— por un Supabase caído: mentir sobre el permiso.
     */
    @Test
    fun aFailedRoleReadPropagatesInsteadOfBecomingForbidden() = onAPipelineThread {
        assertFailsWith<IllegalStateException> {
            authorizeAdminCall(
                principalName = userId,
                path = "/api/v1/admin/examenes",
                remoteAddress = "203.0.113.7",
                appEnv = "prod",
                hasAdminRole = { throw IllegalStateException("Supabase GET /rest/v1/roles_usuario -> 503") },
            )
        }
    }

    @Test
    fun theStaticTokenNeverConsultsTheRole() = onAPipelineThread {
        var consulted = false
        val consult: (String) -> Boolean = { consulted = true; true }

        val legacyLocal = authorizeAdminCall(
            principalName = STATIC_TOKEN_PRINCIPAL,
            path = "/admin/users",
            remoteAddress = "127.0.0.1",
            appEnv = "dev",
            hasAdminRole = consult,
            isLocalPeer = { _, _ -> true },
        )
        val canonical = authorizeAdminCall(
            principalName = STATIC_TOKEN_PRINCIPAL,
            path = "/api/v1/admin/examenes",
            remoteAddress = "127.0.0.1",
            appEnv = "dev",
            hasAdminRole = consult,
            isLocalPeer = { _, _ -> true },
        )

        assertNull(legacyLocal)
        assertIs<AppUnauthorized>(canonical)
        assertFalse(consulted, "el token estático no es una persona: su rol no se consulta")
    }

    // -----------------------------------------------------------------
    // Verificación del JWT
    // -----------------------------------------------------------------

    @Test
    fun theJwtIsVerifiedOffTheRequestThread() = onAPipelineThread { probe ->
        val principal = verifiedJwtPrincipalOrNull("header.payload.signature") {
            probe.record("verificación del JWT")
            userId
        }

        assertEquals(UserIdPrincipal(userId), principal)
        probe.assertRanOffTheRequestThread("verificar el JWT puede descargar el JWKS")
    }

    /** Firma mala, `kid` desconocido o JWKS caído: credencial no válida → `null` → 401, nunca 500. */
    @Test
    fun anUnverifiableTokenIsNoPrincipalAndNotAnError() = onAPipelineThread {
        listOf(
            IllegalStateException("kid no encontrado en JWKS"),
            RuntimeException("The Token's Signature resulted invalid"),
            java.io.IOException("JWKS no disponible"),
        ).forEach { failure ->
            assertNull(verifiedJwtPrincipalOrNull("header.payload.signature") { throw failure }, failure.message)
        }
    }

    /** La cancelación es del llamante, no una credencial mala: no se convierte en 401. */
    @Test
    fun cancellationIsNotSwallowedAsAnInvalidToken() = onAPipelineThread {
        assertFailsWith<CancellationException> {
            verifiedJwtPrincipalOrNull("header.payload.signature") { throw CancellationException("cliente desconectado") }
        }
    }

    /**
     * Lo que `module()` cablea en `authenticate { … }`, ya como función propia
     * ([gatewayAuthPrincipal], P1-1): el JWT se verifica fuera del hilo de la petición.
     *
     * Antes era una lambda dentro de `module()`, y `module()` no se puede construir sin
     * el entorno entero de Supabase: el cableado real de la verificación no lo probaba
     * nadie, sólo la función que la lambda llamaba.
     */
    @Test
    fun theModuleWiringVerifiesTheJwtOffTheRequestThread() = onAPipelineThread { probe ->
        val principal = gatewayAuthPrincipal(
            token = "header.payload.signature",
            appEnv = "prod",
            verify = { probe.record("verificación del JWT del módulo"); userId },
            expectedStaticToken = "un-token-estatico-que-no-es-el-de-la-llamada",
            allowDevToken = false,
        )

        assertEquals(UserIdPrincipal(userId), principal)
        probe.assertRanOffTheRequestThread("gatewayAuthPrincipal")
    }

    /** El token estático no verifica nada: no hay JWKS que descargar ni salto que pagar. */
    @Test
    fun theStaticTokenIsAnIdentityWithoutVerifying() = onAPipelineThread { probe ->
        val principal = gatewayAuthPrincipal(
            token = "el-token-configurado",
            appEnv = "prod",
            verify = { probe.record("verificación indebida"); error("el token estático no se verifica") },
            expectedStaticToken = "el-token-configurado",
            allowDevToken = false,
        )

        assertEquals(UserIdPrincipal(STATIC_TOKEN_PRINCIPAL), principal)
    }

    // -----------------------------------------------------------------
    // Cableado real (P1-1): el plugin lee el rol fuera del hilo de la petición
    // -----------------------------------------------------------------

    /**
     * El plugin de autorización corre delante de CADA handler administrativo. Si
     * leyera el rol en el hilo de la petición, el pool se agotaría antes incluso de
     * que el handler llegara a saltar al pool IO — y ninguna prueba del handler lo
     * vería, porque el handler sí salta.
     */
    @Test
    fun theAuthorizationPluginReadsTheRoleOffTheRequestThread() {
        RequestThreadProbe("authz-plugin-request-thread").use { probe ->
            testApplication {
                application {
                    pinPipelineTo(probe)
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                    installRequestCorrelation()
                    installBackendStatusPages()
                    install(Authentication) {
                        bearer("gateway-auth") {
                            authenticate { credentials -> UserIdPrincipal(credentials.token) }
                        }
                    }
                    routing {
                        authenticate("gateway-auth") {
                            install(AdminAuthorizationPlugin) {
                                this.appEnv = "prod"
                                this.hasAdminRole = { probe.record("lectura de rol del plugin"); true }
                            }
                            get("/api/v1/admin/probe") { call.respond(HttpStatusCode.OK) }
                        }
                    }
                }

                val response = client.get("/api/v1/admin/probe") {
                    header(HttpHeaders.Authorization, "Bearer $userId")
                }
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            }
            probe.assertRanOffTheRequestThread("AdminAuthorizationPlugin")
        }
    }

    // -----------------------------------------------------------------
    // Cableado real: un fallo de rol es 500 con referencia, no 403
    // -----------------------------------------------------------------

    @Test
    fun aFailedRoleReadThroughThePipelineIsA500WithReferenceAndNotA403() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            install(Authentication) {
                bearer("gateway-auth") {
                    authenticate { credentials -> UserIdPrincipal(credentials.token) }
                }
            }
            routing {
                authenticate("gateway-auth") {
                    install(AdminAuthorizationPlugin) {
                        this.appEnv = "prod"
                        this.hasAdminRole = { throw IllegalStateException("Supabase GET /rest/v1/roles_usuario -> 503: x-hint-c") }
                    }
                    get("/api/v1/admin/probe") { call.respond(HttpStatusCode.OK) }
                }
            }
        }

        val response = client.get("/api/v1/admin/probe") {
            header(HttpHeaders.Authorization, "Bearer $userId")
            header("X-Request-Id", requestId)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("backend_supabase_error"), body)
        assertTrue(body.contains("Referencia: $requestId"), body)
        assertFalse(body.contains("x-hint-c"), body)
        assertFalse(body.contains("roles_usuario"), body)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    /**
     * Ejecuta [block] en un hilo de un solo uso que hace de "hilo de la petición".
     *
     * El hilo NO se identifica por su nombre: [RequestThreadProbe] lo registra desde
     * dentro. Ver su KDoc para por qué la versión anterior —comparar contra un nombre
     * esperado— dejaba pasar la mutación que quita el salto al pool IO.
     */
    private fun onAPipelineThread(block: suspend (probe: RequestThreadProbe) -> Unit) {
        RequestThreadProbe("authz-test-request-thread").use { probe ->
            probe.onRequestThread {
                try {
                    block(probe)
                } finally {
                    MDC.clear()
                }
            }
        }
    }
}
