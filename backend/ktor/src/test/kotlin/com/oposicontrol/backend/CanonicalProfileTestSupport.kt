package com.oposicontrol.backend

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Soporte comun de las pruebas de SLICE 1: identidad, GoTrue y Supabase falsos, y el modulo Ktor con las rutas canonicas. */
internal const val TEST_UID = "0f8fad5b-d9cb-469f-a165-70867728950e"
internal const val TEST_TOKEN = "aaa.bbb.ccc"

/** Los endpoints de /v1/auth exigen un bearer de 40+ caracteres. */
internal val TEST_LONG_TOKEN = "aaa.${"b".repeat(48)}.ccc"
internal const val TEST_SPECIALTY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
internal const val TEST_COMMUNITY = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
internal const val TEST_DOCUMENT = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
internal const val TEST_UPDATED_AT = "2026-09-30T10:00:00.123456Z"

internal val testJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/** GoTrue falso: registra las llamadas y permite forzar cada fallo. */
internal class FakeAuthProvider : AuthProviderClient {
    var user = AuthProviderUser(TEST_UID, "person@example.com", listOf("email"), "2026-01-02T03:04:05.123456+00:00")
    var userFailure: BackendDomainException? = null
    var passwordFailure: BackendDomainException? = null
    var logoutFailure: Exception? = null
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun user(accessToken: String, context: OposiBotsAuthContext): AuthProviderUser {
        calls += "user"
        userFailure?.let { throw it }
        return user
    }

    override suspend fun verifyPassword(email: String, password: String, context: OposiBotsAuthContext) {
        calls += "verifyPassword:$email"
        passwordFailure?.let { throw it }
    }

    override suspend fun logout(accessToken: String, context: OposiBotsAuthContext) {
        calls += "logout"
        logoutFailure?.let { throw it }
    }
}

/** Supabase falso (clave de servicio): registra `GET`, `RPC` y `PATCH` y responde lo que cada prueba programe. */
internal class FakeUpstream : CanonicalUpstream {
    val log = CopyOnWriteArrayList<String>()
    val rpcCalls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    var onGet: (String) -> String = { "[]" }
    var onRpc: (String, JsonObject) -> SupabaseRawResponse = { _, _ -> SupabaseRawResponse(200, "{}") }

    override fun get(path: String): String {
        log += "GET $path"
        return onGet(path)
    }

    override fun rpc(function: String, body: String): SupabaseRawResponse {
        val args = Json.parseToJsonElement(body).jsonObject
        log += "RPC $function"
        rpcCalls += function to args
        return onRpc(function, args)
    }

    fun rpcNames(): List<String> = rpcCalls.map { it.first }
    fun lastArgs(function: String): JsonObject = rpcCalls.last { it.first == function }.second
}

internal fun rpcOk(body: String) = SupabaseRawResponse(200, body)

internal fun rpcError(status: Int, sqlState: String, message: String, hint: String? = null) = SupabaseRawResponse(
    status,
    """{"code":"$sqlState","message":"$message","details":null,"hint":${hint?.let { "\"$it\"" } ?: "null"}}""",
)

/** JSON que devuelven `app_completar_onboarding` / `app_actualizar_perfil`. */
internal fun profileRpcJson(
    username: String? = "ada_l",
    nombre: String? = "Ada",
    apellidos: String? = "Lovelace",
    especialidad: String? = TEST_SPECIALTY,
    comunidad: String? = null,
    onboarding: Boolean = true,
    estado: String = "activa",
    updatedAt: String = TEST_UPDATED_AT,
    replay: Boolean? = null,
) = buildString {
    append("""{"usuarioId":"$TEST_UID","username":${q(username)},"nombreMostrar":${q(nombre)},"nombre":${q(nombre)},"apellidos":${q(apellidos)},""")
    append(""""especialidadPrincipalId":${q(especialidad)},"comunidadPrincipalId":${q(comunidad)},"avatarUrl":"avatars/raw/secret-path.png",""")
    append(""""onboardingCompleted":$onboarding,"estadoCuenta":"$estado","introSeenAt":null,"creadoEn":"2026-01-02T03:04:05.123456Z","actualizadoEn":"$updatedAt"""")
    if (replay != null) append(""","replay":$replay""")
    append("}")
}

/** Fila de `perfiles_usuario` (lectura de GET /me, snake_case). */
internal fun profileRowJson(estado: String = "activa", onboarding: Boolean = true, updatedAt: String = "2026-09-30T10:00:00.123456+00:00") =
    """[{"usuario_id":"$TEST_UID","username":"ada_l","nombre_mostrar":"Ada","nombre":"Ada","apellidos":"Lovelace","especialidad_principal_id":"$TEST_SPECIALTY","comunidad_principal_id":null,"onboarding_completado":$onboarding,"estado_cuenta":"$estado","perfil_intro_visto_en":null,"creado_en":"2026-01-02T03:04:05.123456+00:00","actualizado_en":"$updatedAt"}]"""

private fun q(value: String?) = value?.let { "\"$it\"" } ?: "null"

/**
 * Dobles EXPLICITOS de las dos guardas de solicitudes de acceso (AccessRequestMailGuard.kt) para las pruebas que no las
 * ejercitan. Produccion ya no tiene ningun valor por defecto que no bloquee: quien monta un runtime o un gateway a mano
 * tiene que nombrar uno de estos (o la guarda real sobre un upstream falso).
 */
internal val NoAccessRequestsMailGuard = AccessRequestMailGuard { _, _ -> false }
internal val NoAccessRequestsSessionGate = AccessRequestSessionGate { _, _ -> AccessRequestGateDecision.Allow }

/** El gateway real con la puerta de sesion de solicitudes de acceso apagada A PROPOSITO (pruebas que no tratan de ella). */
internal fun authGatewayWithoutAccessRequests(
    configuration: OposiBotsAuthConfiguration,
    json: kotlinx.serialization.json.Json,
    client: io.ktor.client.HttpClient,
    profileDirectory: CanonicalProfileDirectory? = null,
) = SupabaseOposiBotsAuthGateway(configuration, json, NoAccessRequestsSessionGate, client, profileDirectory)

internal object TestAllowAllLimiter : OposiBotsAuthRateLimiter {
    override suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String) = OposiBotsRateLimitDecision(true)
    override suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String) = OposiBotsRateLimitDecision(true)
}

internal class TestCountingLimiter(private val subjectAllowed: Boolean = true, private val ipAllowed: Boolean = true) : OposiBotsAuthRateLimiter {
    val ipChecks = CopyOnWriteArrayList<OposiBotsAuthOperation>()
    val subjectChecks = CopyOnWriteArrayList<Pair<OposiBotsAuthOperation, String>>()
    override suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String): OposiBotsRateLimitDecision {
        ipChecks += operation
        return OposiBotsRateLimitDecision(ipAllowed, 42)
    }

    override suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String): OposiBotsRateLimitDecision {
        subjectChecks += operation to subject
        return OposiBotsRateLimitDecision(subjectAllowed, 42)
    }
}

internal fun testCanonicalConfiguration() = OposiBotsAuthConfiguration(
    supabaseUrl = "https://example.supabase.co",
    publishableKey = "publishable",
    secretKey = "secret",
    appEnvironment = "test",
    passwordRecoveryRedirectUrl = null,
    appleClientId = null,
    appleClientSecret = null,
    captchaRequired = false,
    trustProxyHeaders = false,
    trustedProxyHosts = emptySet(),
    forwardClientIpToSupabase = false,
    rateLimitMode = "memory",
    rateLimitHmacSecret = "test-only-rate-limit-secret-32chars",
    valkeyUrl = null,
)

/** Un entorno completo: perfil canonico real ([SupabaseCanonicalProfileService]) sobre Supabase y GoTrue falsos. */
internal class CanonicalTestEnv(
    var limiter: OposiBotsAuthRateLimiter = TestAllowAllLimiter,
    var gateway: OposiBotsAuthGateway = NoAuthGateway,
) {
    val upstream = FakeUpstream()
    val auth = FakeAuthProvider()

    /** `estado_cuenta` de la fila de perfil; `null` = la columna llega a `null`; [profileExists] `false` = sin fila. */
    var status: String? = "activa"
    var profileExists = true
    var onboarding = false

    /** `amr[].timestamp` del JWT. */
    var authTimes: List<Long> = emptyList()
    var now: Instant = Instant.parse("2026-09-30T12:00:00Z")

    val profiles: CanonicalProfileService = SupabaseCanonicalProfileService(upstream, auth, testJson)
    val deletion = AccountDeletionService(upstream, auth, clock = { now }, json = testJson)

    private val resolver = CanonicalIdentityResolver(
        verifySubject = { if (it == TEST_TOKEN || it == TEST_LONG_TOKEN) TEST_UID else error("firma invalida") },
        directory = { id ->
            if (!profileExists) null else CanonicalUserIdentity(id, AccountStatus.fromWire(status), onboarding)
        },
        verifyClaims = { if (it == TEST_TOKEN || it == TEST_LONG_TOKEN) VerifiedJwtClaims(TEST_UID, authTimes = authTimes) else error("firma invalida") },
    )

    /** Puente del codigo de invitacion del registro (null = no cableado). */
    var referral: ReferralAttach? = null

    val specialties = SpecialtyResolver { text -> if (text.equals("Sanidad", ignoreCase = true)) TEST_SPECIALTY else null }

    fun runtime() = OposiBotsAuthRuntime(
        configuration = testCanonicalConfiguration(),
        gateway = gateway,
        rateLimiter = limiter,
        json = testJson,
        canonical = CanonicalProfileRuntime(resolver, profiles, deletion, LegacyProfileAdapter(profiles, referral, specialties)),
        accessRequestMailGuard = NoAccessRequestsMailGuard,
    )

    fun mount(application: Application) = with(application) {
        install(ContentNegotiation) { json(testJson) }
        installRequestCorrelation()
        installBackendStatusPages()
        val runtime = runtime()
        routing {
            oposiBotsAuthRoutes(runtime)
            canonicalProfileRoutes(runtime)
        }
    }
}

/** Gateway de auth que no debe usarse (las pruebas de perfil no tocan `/v1/auth`), salvo las de los adaptadores legacy. */
internal object NoAuthGateway : OposiBotsAuthGateway {
    private fun unused(): Nothing = error("el gateway de auth no interviene en esta prueba")
    override suspend fun register(request: OposiBotsRegisterRequest, context: OposiBotsAuthContext) = unused()
    override suspend fun resend(request: OposiBotsEmailResendRequest, context: OposiBotsAuthContext) = unused()
    override suspend fun exchangeEmail(request: OposiBotsEmailExchangeRequest, context: OposiBotsAuthContext) = unused()
    override suspend fun login(request: OposiBotsLoginRequest, context: OposiBotsAuthContext) = unused()
    override suspend fun refresh(request: OposiBotsRefreshRequest, context: OposiBotsAuthContext) = unused()
    override suspend fun logout(accessToken: String, context: OposiBotsAuthContext) = unused()
    override suspend fun recover(request: OposiBotsRecoverRequest, context: OposiBotsAuthContext) = unused()
    override suspend fun reset(request: OposiBotsResetPasswordRequest, context: OposiBotsAuthContext) = unused()
    override suspend fun google(request: OposiBotsGoogleRequest, context: OposiBotsAuthContext) = unused()
    override suspend fun apple(request: OposiBotsAppleRequest, context: OposiBotsAuthContext) = unused()
}

internal fun JsonObject.str(name: String): String? = this[name]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.issueFields(): List<String> =
    (this["details"] as? JsonObject)?.get("issues")?.jsonArray?.map { (it as JsonObject).str("field").orEmpty() }.orEmpty()

internal fun JsonObject.issueNames(): List<String> =
    (this["details"] as? JsonObject)?.get("issues")?.jsonArray?.map { (it as JsonObject).str("issue").orEmpty() }.orEmpty()

internal fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }
