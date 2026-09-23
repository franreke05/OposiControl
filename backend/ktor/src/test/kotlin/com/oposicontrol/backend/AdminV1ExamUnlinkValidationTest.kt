package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `DELETE /api/v1/admin/examenes/{id}/preguntas/{preguntaId}` lleva un
 * identificador del cliente en **primera posición** de la query de un DELETE de
 * PostgREST. Ahí la falta de validación no "estrecha" la consulta: la
 * **ensancha**.
 *
 * CORRECCIÓN: este KDoc afirmaba que era «la única ruta de las cuatro
 * verticales» con esa forma. Era falso — había tres más, cubiertas en
 * `AdminV1FilterTruncationValidationTest`. La afirmación de unicidad era peor
 * que no decir nada: invitaba a dejar de buscar.
 *
 * El vector: `URI.create` parsea `#` como inicio de fragmento y el fragmento no
 * viaja en el request-target, así que un `#` en `examenId` borra todos los
 * filtros posteriores. La query
 * `examen_id=eq.<uuid>#&pregunta_id=eq.<X>` llega al upstream como
 * `examen_id=eq.<uuid>`, y el DELETE que decía quitar UNA pregunta borra
 * **todas las asociaciones del examen**.
 *
 * Lo que estos casos fijan es lo único que de verdad cierra el agujero: que con
 * un identificador que no es un UUID **no sale ni una sola petición hacia
 * Supabase**. Comprobar sólo el código de estado dejaría pasar una
 * implementación que valida después de haber llamado al upstream.
 */
class AdminV1ExamUnlinkValidationTest {

    private val examenId = "22222222-2222-4222-8222-222222222222"
    private val preguntaId = "33333333-3333-4333-8333-333333333333"

    @Test fun aFragmentInTheExamIdNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { unlinkTestModule(upstream) }

        // `%23` es `#`. Si Ktor lo decodifica, el identificador trae un `#`; si
        // no, trae un `%`. Ninguno de los dos es un UUID, y el caso se sostiene
        // en las dos lecturas.
        val response = client.delete("/api/v1/admin/examenes/$examenId%23/preguntas/$preguntaId")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(
            upstream.isEmpty(),
            "ningún DELETE debe haber salido hacia Supabase; salieron: ${upstream.map { it.uri() }}",
        )
    }

    @Test fun aFragmentInTheQuestionIdNeverReachesSupabaseEither() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { unlinkTestModule(upstream) }

        val response = client.delete("/api/v1/admin/examenes/$examenId/preguntas/$preguntaId%23")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "el segundo identificador también se valida antes de llamar")
    }

    /**
     * No-regresión: con dos UUID válidos la ruta sigue funcionando y la RPC de M4
     * (K3-2) recibe **los dos** identificadores como argumentos. Sin este caso,
     * "rechazarlo todo" pasaría los dos anteriores.
     *
     * Desde K3-2 ya no hay filtro de PostgREST que truncar —los ids viajan en el
     * cuerpo JSON de la RPC—, pero la validación se mantiene: un no-uuid sería un
     * 22P02 de Postgres (500 redactado) en vez de un 400 que dice qué campo falla.
     */
    @Test fun twoValidUuidsStillUnlinkASingleQuestion() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { unlinkTestModule(upstream) }

        val response = client.delete("/api/v1/admin/examenes/$examenId/preguntas/$preguntaId")

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = upstream.single { it.uri().rawPath.endsWith("/rpc/admin_unlink_question_from_exam") }
        val body = readRequestBody(rpc)
        assertTrue(body.contains("\"p_examen_id\":\"$examenId\""), body)
        assertTrue(body.contains("\"p_pregunta_id\":\"$preguntaId\""), body)
    }

    // -----------------------------------------------------------------

    /**
     * Módulo mínimo: las rutas sin el `authenticate("gateway-auth")` de
     * producción —lo que se prueba aquí es la validación de entrada, no la
     * autorización, que tiene sus propios tests— y un `StatusPages` que
     * reproduce sólo el handler de `IllegalArgumentException` de
     * `Application.module()`, que es el que convierte un identificador
     * inválido en 400.
     */
    private fun Application.unlinkTestModule(upstream: MutableList<HttpRequest>) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(error = "bad_request", message = cause.message ?: "Invalid request"),
                )
            }
        }
        routing { adminV1Routes(gatewayRecording(upstream)) }
    }

    private fun gatewayRecording(upstream: MutableList<HttpRequest>): AdminV1Gateway =
        AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient({ upstream += it }) { uri ->
                when {
                    // El DELETE devuelve la fila borrada (`return=representation`).
                    uri.contains("/rest/v1/preguntas_examen") && upstream.size == 1 -> V1StubResponse(
                        200,
                        """[{"examen_id":"$examenId","pregunta_id":"$preguntaId","posicion":1,"numero_en_examen":1}]""",
                    )
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
}
