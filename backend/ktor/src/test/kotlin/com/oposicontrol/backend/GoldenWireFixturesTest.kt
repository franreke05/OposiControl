package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * MASTER-OPENAPI-010: fixtures GOLDEN de lo que Ktor emite de verdad hoy para las operaciones que consume OposiBot.
 *
 * Cada fichero de `src/test/resources/golden/` es la respuesta real (no escrita a mano) y esta pinchada aqui contra el codigo
 * de produccion. OposiBot guarda el MISMO texto como constante en `CanonicalGoldenFixturesTest` y lo decodifica con su cliente
 * real: si Ktor cambia una respuesta, esta prueba falla y obliga a regenerar el golden y a actualizar la copia de OposiBot.
 */
class GoldenWireFixturesTest {

    private fun golden(name: String): JsonElement {
        val text = assertNotNull(javaClass.getResourceAsStream("/golden/$name"), "missing golden $name").readBytes().decodeToString()
        return Json.parseToJsonElement(text)
    }

    private fun actual(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun getMeIsExactlyTheGoldenResponse() = testApplication {
        val env = CanonicalTestEnv().apply {
            upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson() else "[]" }
        }
        application { env.mount(this) }
        val response = client.get("/api/v1/app/me") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(golden("app-me.json"), actual(response.bodyAsText()), "actual: ${response.bodyAsText()}")
    }

    @Test
    fun accountBlockedErrorEnvelopeIsExactlyTheGoldenResponse() = testApplication {
        val env = CanonicalTestEnv().apply {
            status = "bloqueada"
            upstream.onGet = { path -> if ("perfiles_usuario" in path) profileRowJson("bloqueada") else "[]" }
        }
        application { env.mount(this) }
        val response = client.get("/api/v1/app/me") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            header("X-Request-Id", "golden-request-0001")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(golden("error-account-blocked.json"), actual(response.bodyAsText()), "actual: ${response.bodyAsText()}")
    }

    @Test
    fun theAuthSessionIsExactlyTheGoldenShapeWithTheProductionJson() {
        // El mismo `Json` que instala Application.kt (ignoreUnknownKeys + explicitNulls = false).
        val production = Json { ignoreUnknownKeys = true; explicitNulls = false; prettyPrint = false }
        val session = OposiBotsAuthSessionDto(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAtEpochSeconds = 4102444800,
            user = OposiBotsAuthUserDto(id = TEST_UID, email = "person@example.com", profileComplete = true),
        )
        val text = production.encodeToString(session)
        assertEquals(golden("auth-session.json"), actual(text), "actual: $text")
    }
}
