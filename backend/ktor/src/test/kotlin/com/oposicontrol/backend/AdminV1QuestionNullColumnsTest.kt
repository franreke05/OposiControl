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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KQ0-2 (QN-12) · Un NULL real de la base no tumba el banco de preguntas.
 *
 * `fuente_texto` y `dificultad` (en `preguntas`) y `titulo` y `descripcion` (en
 * `recursos_pregunta`) son NULL-ables. Las filas de Kotlin los declaraban `String`
 * con default, y el `Json` del gateway no coacciona nulos: en cuanto Q2 o una
 * importación escribieran un NULL, el listado y el detalle responderían 5xx. Hoy no
 * hay ningún NULL en MAIN, así que el defecto no se ve en vivo: por eso se fija aquí
 * con las dos rutas reales que lo sufrían.
 */
class AdminV1QuestionNullColumnsTest {

    private val preguntaId = "c3c3c3c3-c3c3-4c3c-8c3c-c3c3c3c3c3c3"

    @Test
    fun theListAndTheDetailSurviveNullColumns() = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installBackendStatusPages()
            routing { adminV1Routes(gateway()) }
        }

        val listado = client.get("/api/v1/admin/preguntas")
        val detalle = client.get("/api/v1/admin/preguntas/$preguntaId")

        assertEquals(HttpStatusCode.OK, listado.status, listado.bodyAsText())
        assertEquals(HttpStatusCode.OK, detalle.status, detalle.bodyAsText())
        val cuerpo = detalle.bodyAsText()
        // NULL en la base → texto vacío en la respuesta: la forma de lectura no cambia.
        // (La dificultad vacía y el título vacío del recurso valen su default, y el
        // `Json` del servidor no codifica defaults: no viajan, y el cliente lee "".)
        assertTrue(cuerpo.contains("\"fuenteTexto\":\"\""), cuerpo)
        assertTrue(!cuerpo.contains("null"), "un NULL de la base llegó a la respuesta: $cuerpo")
        // Y nunca una dificultad inventada.
        assertTrue(!cuerpo.contains("\"media\""), "un NULL se leyó como «media»: $cuerpo")
    }

    private fun gateway(): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({}) { uri ->
            when {
                // `recursos_pregunta`, `opciones_pregunta` y `soluciones_pregunta` ANTES
                // que `preguntas`, que es prefijo de las tres.
                uri.contains("/rest/v1/recursos_pregunta") -> V1StubResponse(
                    200,
                    """[{"id":"e5e5e5e5-e5e5-4e5e-8e5e-e5e5e5e5e5e5","pregunta_id":"$preguntaId","codigo_recurso":"R-1",""" +
                        """"tipo_recurso":"texto","titulo":null,"descripcion":null,"contenido_texto":"x","posicion":1,"activo":true}]""",
                )
                uri.contains("/rest/v1/opciones_pregunta") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/soluciones_pregunta") -> V1StubResponse(200, "[]")
                uri.contains("/rest/v1/preguntas") -> V1StubResponse(
                    200,
                    """[{"id":"$preguntaId","codigo_pregunta":"PREG-000001",""" +
                        """"tipo_pregunta":"inmersiva","titulo":"Titulo","fuente_texto":null,"dificultad":null,"activa":true}]""",
                    contentRange = "0-0/1",
                )
                else -> V1StubResponse(200, "[]")
            }
        },
    )
}
