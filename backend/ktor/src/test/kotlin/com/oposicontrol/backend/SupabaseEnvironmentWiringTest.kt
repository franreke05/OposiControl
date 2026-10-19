package com.oposicontrol.backend

import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F-01 · F-02 · F-16: de dónde sale la credencial de Supabase y cómo viaja.
 *
 * Tres garantías, cada una con su prueba:
 *
 * 1. **El backend arranca con la configuración canónica** —sólo
 *    `SUPABASE_SECRET_KEY`—. Ocho `fromEnvironment` leían sólo la variable legacy y
 *    `Application.module()` se detenía. Se prueba con un entorno INYECTADO: tocar
 *    el del proceso haría el caso dependiente de la máquina.
 * 2. **Una sola fuente de credencial.** Ningún fichero lee la variable legacy por
 *    su cuenta; la precedencia la decide `SupabaseRequestAuthPolicy`. Con las dos
 *    variables definidas (caso C4), gana SIEMPRE la canónica en todos los gateways.
 * 3. **Nadie firma a mano.** `Bearer <clave>` sólo cuando la clave tiene forma de
 *    JWT; una `sb_secret_…` viaja únicamente como `apikey`.
 */
class SupabaseEnvironmentWiringTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun environment(vararg values: Pair<String, String>): (String) -> String? {
        val map = values.toMap()
        return { name -> map[name] }
    }

    // -----------------------------------------------------------------
    // 1 · Arranque
    // -----------------------------------------------------------------

    @Test
    fun everyGatewayIsConfiguredWithOnlyTheCanonicalSecretKey() {
        val missing = unconfiguredSupabaseGateways(
            json,
            environment("SUPABASE_URL" to "https://stub.supabase.test/", "SUPABASE_SECRET_KEY" to "sb_secret_wiring_fixture"),
        )

        assertEquals(
            emptyList(),
            missing,
            "con sólo SUPABASE_SECRET_KEY estos gateways devuelven null y Application.module() no arranca: $missing",
        )
    }

    @Test
    fun theLegacyKeyStillWorksAsAnExplicitFallback() {
        val missing = unconfiguredSupabaseGateways(
            json,
            environment("SUPABASE_URL" to "https://stub.supabase.test", "SUPABASE_SERVICE_ROLE_KEY" to "legacy.service.role"),
        )

        assertEquals(emptyList(), missing, "la legacy es fallback, no se puede romper el entorno actual: $missing")
    }

    @Test
    fun withoutAnyServerKeyEveryGatewayReportsItselfUnconfigured() {
        val missing = unconfiguredSupabaseGateways(json, environment("SUPABASE_URL" to "https://stub.supabase.test"))

        assertEquals(9, missing.size, "cada gateway sin clave tiene que decir que no está configurado: $missing")
    }

    @Test
    fun theStartupMessageNamesTheCanonicalVariableAndNoValue() {
        val message = missingSupabaseConfigurationMessage("servir contenido desde Ktor")

        assertTrue(message.contains("SUPABASE_SECRET_KEY"), message)
        assertTrue(message.contains("SUPABASE_URL"), message)
        assertFalse(message.contains("sb_secret_"), message)
        assertFalse(message.contains("eyJ"), message)
    }

    // -----------------------------------------------------------------
    // 2 · Una sola fuente de credencial (vigilancia sobre las fuentes)
    // -----------------------------------------------------------------

    /**
     * Ningún fichero de producción lee `SUPABASE_SERVICE_ROLE_KEY` salvo la política.
     *
     * Es la red para los gateways que no admiten un entorno inyectado
     * (`SupabaseV1Gateway`, `AdminV1Gateway`) y para cualquier gateway nuevo: si uno
     * vuelve a leer la variable legacy directamente, en el caso C4 —las dos
     * variables definidas— esa lectura usaría una credencial distinta del resto.
     * `OposiBotsAuthConfiguration` queda fuera: es OposiBot, con su propia
     * resolución `SECRET ?: SERVICE_ROLE`, y no es de esta fase.
     */
    @Test
    fun noProductionSourceReadsTheLegacyKeyOutsideTheCredentialPolicy() {
        val reads = Regex("""(getenv|environment)\(\s*"SUPABASE_SERVICE_ROLE_KEY"\s*\)""")

        val offenders = mainSources()
            .filterKeys { it !in setOf("SupabaseRequestAuthPolicy.kt", "OposiBotsAuthConfiguration.kt") }
            .filterValues { reads.containsMatchIn(it) }
            .keys

        assertEquals(emptySet(), offenders, "leen la variable legacy por su cuenta: $offenders")
    }

    /**
     * Nadie compone `Bearer <clave>` ni la cabecera `apikey` a mano: lo hace
     * `SupabaseRequestAuthPolicy`. Se ignoran los comentarios, que citan el patrón
     * precisamente para explicar por qué ya no está.
     */
    @Test
    fun noProductionSourceSignsSupabaseRequestsByHand() {
        val handBearer = Regex(""""Bearer \$""")
        val handApiKey = Regex(""""apikey"\s*,""")
        // StoreGooglePlay.kt firma con un token OAuth de la cuenta de servicio de GOOGLE (Play Developer API), no con una clave de Supabase.
        val allowed = setOf("SupabaseRequestAuthPolicy.kt", "OposiBotsAuthGateway.kt", "StoreGooglePlay.kt")

        val offenders = mainSources()
            .filterKeys { it !in allowed }
            .mapValues { (_, text) -> text.lineSequence().map { it.trim() }.filterNot { it.startsWith("*") || it.startsWith("//") } }
            .filterValues { lines -> lines.any { handBearer.containsMatchIn(it) || handApiKey.containsMatchIn(it) } }
            .keys

        assertEquals(emptySet(), offenders, "firman la petición a mano en vez de con la política: $offenders")
    }

    // -----------------------------------------------------------------
    // 3 · Cabeceras de los gateways legacy (F-02)
    // -----------------------------------------------------------------

    @Test
    fun aSecretKeyTravelsOnlyAsApiKeyInTheLegacyAdminAndAnalyticsGateways() {
        headersSentBy("sb_secret_legacy_fixture").forEach { (gateway, request) ->
            assertEquals("sb_secret_legacy_fixture", request.headers().firstValue("apikey").orElse(null), gateway)
            assertTrue(
                request.headers().firstValue("Authorization").isEmpty,
                "$gateway manda una sb_secret_ como Bearer: ${request.headers().map()}",
            )
        }
    }

    /**
     * La credencial se FIJA (`setHeader`), no se AÑADE (`header`). Sobre un builder que
     * ya trajera `apikey` —reutilizado, o con una puesta a mano antes— `header` dejaría
     * dos valores, y cuál de los dos usa la pasarela de Supabase no lo decide nadie.
     */
    @Test
    fun theApiKeyIsSetOnceEvenOverABuilderThatAlreadyCarriesOne() {
        val request = HttpRequest.newBuilder(java.net.URI("https://stub.supabase.test/rest/v1/examenes"))
            .header("apikey", "sb_secret_anterior")
            .supabaseElevatedAuth("sb_secret_fixture")
            .GET()
            .build()

        assertEquals(listOf("sb_secret_fixture"), request.headers().allValues("apikey"))
    }

    @Test
    fun aJwtShapedLegacyKeyAlsoTravelsAsBearerInTheLegacyGateways() {
        headersSentBy("aaa.bbb.ccc").forEach { (gateway, request) ->
            assertEquals("aaa.bbb.ccc", request.headers().firstValue("apikey").orElse(null), gateway)
            assertEquals("Bearer aaa.bbb.ccc", request.headers().firstValue("Authorization").orElse(null), gateway)
        }
    }

    @Test
    fun theOposiBotsUserAdminGatewayFollowsTheSamePolicy() = runBlocking {
        listOf("sb_secret_user_admin_fixture" to null, "aaa.bbb.ccc" to "Bearer aaa.bbb.ccc").forEach { (key, bearer) ->
            val requests = CopyOnWriteArrayList<HttpRequestData>()
            val client = KtorHttpClient(
                MockEngine { request ->
                    requests += request
                    respond(
                        content = ByteReadChannel("[]"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
            val gateway = SupabaseOposiBotsUserAdminGateway("https://stub.supabase.test", key, json, client)

            gateway.listUsers(limit = 10, offset = 0, search = null)

            val sent = requests.single()
            assertEquals(key, sent.headers["apikey"])
            assertEquals(bearer, sent.headers[HttpHeaders.Authorization], "clave $key")
        }
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    /** La primera petición de cada gateway legacy con constructor inyectable. */
    private fun headersSentBy(key: String): List<Pair<String, HttpRequest>> {
        val admin = CopyOnWriteArrayList<HttpRequest>()
        SupabaseAdminGateway(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = key,
            json = json,
            client = V1StubHttpClient(admin::add) { V1StubResponse(200, "[]") },
        ).listUsers()

        val analytics = CopyOnWriteArrayList<HttpRequest>()
        AdminAnalyticsGateway(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = key,
            json = json,
            client = V1StubHttpClient(analytics::add) { V1StubResponse(206, "[]", contentRange = "0-0/1") },
        ).economia()

        return listOf(
            "SupabaseAdminGateway" to assertNotNull(admin.firstOrNull(), "SupabaseAdminGateway no hizo ninguna petición"),
            "AdminAnalyticsGateway" to assertNotNull(analytics.firstOrNull(), "AdminAnalyticsGateway no hizo ninguna petición"),
        )
    }

    /** `Nombre.kt` → texto, de todas las fuentes de producción del backend. */
    private fun mainSources(): Map<String, String> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        val root = assertNotNull(dir, "No se encontró la raíz del repositorio desde ${System.getProperty("user.dir")}")
        val sources = File(root, "backend/ktor/src/main/kotlin/com/oposicontrol/backend")
        assertTrue(sources.isDirectory, "No existe $sources")
        return sources.listFiles { file -> file.extension == "kt" }.orEmpty().associate { it.name to it.readText() }
    }
}
