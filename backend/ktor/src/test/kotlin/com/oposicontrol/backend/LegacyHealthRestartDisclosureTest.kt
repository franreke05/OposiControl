package com.oposicontrol.backend

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * SEC-ALLOWDEVUSER: el `/health` legado es publico. Fuera de un entorno EXPLICITO de desarrollo no debe publicar
 * `restartEnabled` (ni mucho menos `"true"`), aunque `OPOSICONTROL_ENABLE_BACKEND_RESTART=true` este definido.
 * Se monta la MISMA ruta que usa `module()` ([legacyHealthRoute]).
 */
class LegacyHealthRestartDisclosureTest {

    private fun healthBody(appEnv: String?, restartFlag: String?): Map<String, String> {
        var body = ""
        testApplication {
            install(ContentNegotiation) { json() }
            routing { legacyHealthRoute(appEnv = appEnv, restartFlag = restartFlag) }
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            body = response.bodyAsText()
        }
        return Json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun productionLikeEnvironmentsNeverExposeRestartEnabledEvenWithTheFlagOn() {
        listOf("production", "staging", "unknown-value", "", null).forEach { env ->
            val body = healthBody(appEnv = env, restartFlag = "true")
            assertEquals("ok", body["status"], "status ($env)")
            assertFalse("restartEnabled" in body, "restartEnabled publicado con APP_ENV=$env: $body")
        }
    }

    @Test
    fun developmentStillReportsTheRestartSwitch() {
        assertEquals("true", healthBody(appEnv = "dev", restartFlag = "true")["restartEnabled"])
        assertEquals("true", healthBody(appEnv = "local", restartFlag = null)["restartEnabled"])
        assertEquals("false", healthBody(appEnv = "dev", restartFlag = "false")["restartEnabled"])
    }
}
