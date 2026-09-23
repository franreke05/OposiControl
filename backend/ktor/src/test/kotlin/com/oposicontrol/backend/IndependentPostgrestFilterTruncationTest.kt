package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlin.test.assertTrue

/**
 * HASHIRA C — verificación independiente de FASE 1. **SE ESPERA QUE ESTOS DOS
 * CASOS FALLEN HOY.** Documentan un defecto abierto, no una regresión de B.
 *
 * FASE 1 cerró el truncado por `#` en
 * `DELETE /api/v1/admin/examenes/{id}/preguntas/{preguntaId}`
 * (`AdminV1ExamUnlinkValidationTest`), y el KDoc de esa ruta
 * (`AdminV1Routes.kt:224-227`) afirma que es **«la única ruta de las cuatro
 * verticales con un identificador crudo del cliente en primera posición»**.
 *
 * Esa afirmación no es cierta. Quedan al menos dos sitios más con la misma
 * forma, en las mismas verticales, con el identificador del cliente delante:
 *
 * - `AdminV1Routes.kt:2421` — `PATCH /rest/v1/preguntas_examen?examen_id=eq.$examenId&pregunta_id=eq.$preguntaId`,
 *   alcanzable por `PATCH /api/v1/admin/examenes/{id}/preguntas/orden` (`:209`).
 *   `examenId` llega crudo. Truncado el filtro `pregunta_id`, el bucle de
 *   reordenación escribe `posicion` sobre **todas** las filas del examen, una
 *   vez por cada id del cuerpo: el examen acaba con todas las preguntas en la
 *   misma posición.
 * - `AdminV1Routes.kt:2520` y `:2543` — `?id=eq.$recursoId&pregunta_id=eq.$preguntaId`,
 *   alcanzables por `PATCH`/`DELETE /api/v1/admin/preguntas/{id}/recursos/{recursoId}`
 *   (`:342`, `:351`). `recursoId` llega crudo y va delante. El filtro que
 *   desaparece es justamente **el de pertenencia**: el recurso se modifica o se
 *   archiva sin comprobar de qué pregunta es.
 *
 * Ambas superficies son ADMIN, así que esto es P2 y no P0. Pero es el MISMO
 * vector que la fase declaró cerrado, y la afirmación de unicidad lo convierte
 * en una red de seguridad falsa: quien lea ese KDoc dejará de buscar.
 *
 * La aserción es **agnóstica del arreglo**: vale tanto si se valida el
 * identificador en la ruta (no sale ninguna petición) como si se codifica el
 * valor (sale, pero con los dos filtros intactos). Lo que se comprueba es lo
 * único que importa —lo que llega al request-target—, y por eso se mira
 * `uri().rawQuery` y no `uri().toString()`: `toString()` incluye el fragmento,
 * así que un `assertTrue(uri.contains("pregunta_id="))` pasaría con el filtro
 * ya truncado. Esa es exactamente la trampa de SEC-001.
 */
class IndependentPostgrestFilterTruncationTest {

    private val examenId = "44444444-4444-4444-8444-444444444444"
    private val preguntaId = "55555555-5555-4555-8555-555555555555"
    private val recursoId = "66666666-6666-4666-8666-666666666666"

    @Test fun reorderingQuestionsKeepsTheQuestionFilterInTheRequestTarget() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        // `%23` es `#`. URI.create lo lee como inicio de fragmento y todo lo que
        // va detrás deja de viajar en el request-target.
        runCatching {
            client.patch("/api/v1/admin/examenes/$examenId%23/preguntas/orden") {
                contentType(ContentType.Application.Json)
                setBody("""{"ids":["$preguntaId"]}""")
            }
        }

        assertFiltersSurvived(
            upstream,
            table = "/rest/v1/preguntas_examen",
            filters = listOf("examen_id=eq.", "pregunta_id=eq."),
        )
    }

    @Test fun archivingAResourceKeepsTheOwnershipFilterInTheRequestTarget() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        runCatching { client.delete("/api/v1/admin/preguntas/$preguntaId/recursos/$recursoId%23") }

        assertFiltersSurvived(
            upstream,
            table = "/rest/v1/recursos_pregunta",
            filters = listOf("id=eq.", "pregunta_id=eq."),
        )
    }

    // -----------------------------------------------------------------

    /**
     * No se afirma el código de estado a propósito: si el arreglo es validar,
     * la respuesta es 400 y no sale ninguna petición; si es codificar, es 200 y
     * sale una con los dos filtros. Las dos formas pasan. Lo que no puede pasar
     * es que salga una escritura con un filtro de menos.
     */
    private fun assertFiltersSurvived(
        upstream: List<HttpRequest>,
        table: String,
        filters: List<String>,
    ) {
        val writes = upstream.filter {
            it.method() in setOf("PATCH", "DELETE") && it.uri().rawPath.contains(table)
        }
        writes.forEach { request ->
            val query = request.uri().rawQuery.orEmpty()
            filters.forEach { filter ->
                assertTrue(
                    query.contains(filter),
                    "una escritura salió hacia Supabase sin el filtro '$filter'. " +
                        "query real: '$query' · fragmento descartado: '${request.uri().rawFragment}'",
                )
            }
        }
    }

    private fun Application.truncationTestModule(upstream: MutableList<HttpRequest>) {
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
                    uri.contains("/rest/v1/recursos_pregunta") -> V1StubResponse(
                        200,
                        """[{"id":"$recursoId","pregunta_id":"$preguntaId","codigo_recurso":"R-1",""" +
                            """"tipo_recurso":"texto","posicion":1,"activo":false}]""",
                    )
                    else -> V1StubResponse(200, "[]")
                }
            },
        )
}
