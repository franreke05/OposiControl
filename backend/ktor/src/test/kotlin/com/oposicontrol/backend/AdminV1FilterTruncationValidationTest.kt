package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El mismo vector que `AdminV1ExamUnlinkValidationTest`, en las demás rutas que
 * lo tienen: un identificador del cliente que acaba dentro de un filtro de
 * PostgREST, donde un `#` no estrecha la consulta sino que **borra todo lo que
 * viene detrás**.
 *
 * | Ruta | Filtro que compone el gateway | Qué desaparece al truncar |
 * |---|---|---|
 * | `PATCH /examenes/{id}/preguntas/orden` | `?examen_id=eq.$id&pregunta_id=eq.…` | el filtro de pregunta: el bucle escribe `posicion` sobre TODAS las filas del examen, una vez por cada id del cuerpo, y el examen acaba con todas sus preguntas en la misma posición |
 * | `PATCH /preguntas/{id}/recursos/{recursoId}` | `?id=eq.$recursoId&pregunta_id=eq.$id` | el filtro de PERTENENCIA: el título y el contenido de un recurso ajeno se sobrescriben sin comprobar de qué pregunta es (cuando esta tabla se escribió, el PATCH reasignaba además `pregunta_id`, y el recurso cambiaba de dueño; hoy ya no manda esa columna) |
 * | `DELETE /preguntas/{id}/recursos/{recursoId}` | igual | igual, archivando |
 * | `DELETE /preguntas/{id}` | `?id=eq.$id` con `{"activa":false}` | el filtro ENTERO: archiva el banco de preguntas completo |
 * | `PATCH /examenes/{id}` | `?id=eq.$id` con el cuerpo del editor | el filtro ENTERO: reescribe todos los exámenes con los datos de uno |
 * | `DELETE /examenes/{id}` | `?id=eq.$id` con `{"activo":false}` | el filtro ENTERO: archiva el catálogo |
 * | `GET /moderacion/hilos/{id}/respuestas` | `?publicacion_id=eq.$id&order=…&limit=…` | el `order` y el `limit`: lectura sin cota de `comentarios_oposired` |
 *
 * **Esta tabla NO es un inventario cerrado, y la versión anterior de este KDoc
 * se equivocó al escribirla como si lo fuera** («los otros tres sitios»). Había
 * dos más, y entraban por una puerta que la tabla ni miraba: el **cuerpo** de
 * `PATCH /preguntas/{id}` (`examenId` y `recursos[].id`). Esos dos los cubre
 * `IndependentBodyIdentifierTruncationTest`, y la regla real está escrita donde
 * toca, en el comentario de `DELETE /examenes/{id}/preguntas/{preguntaId}`: todo
 * identificador de cliente que acabe en un filtro de PostgREST se valida, venga
 * por el path o por el cuerpo.
 *
 * Igual que en el fichero hermano, lo que se fija es **que no sale ni una
 * petición**, no el código de estado. Un test que sólo mirara el 400 dejaría
 * pasar una implementación que valida después de haber llamado al upstream.
 */
class AdminV1FilterTruncationValidationTest {

    private val examenId = "44444444-4444-4444-8444-444444444444"
    private val preguntaId = "55555555-5555-4555-8555-555555555555"
    private val recursoId = "66666666-6666-4666-8666-666666666666"

    // -----------------------------------------------------------------
    // PATCH /examenes/{id}/preguntas/orden
    // -----------------------------------------------------------------

    @Test fun aFragmentInTheExamIdOfAReorderNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.patch("/api/v1/admin/examenes/$examenId%23/preguntas/orden") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["$preguntaId"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(
            upstream.isEmpty(),
            "ninguna escritura debe haber salido; salieron: ${upstream.map { it.uri() }}",
        )
    }

    @Test fun aNonUuidInsideTheReorderBodyIsRejectedBeforeAnyWrite() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.patch("/api/v1/admin/examenes/$examenId/preguntas/orden") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["$preguntaId","no-soy-un-uuid"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        // Desde K3-1 la reordenación es UNA RPC y ya no hay bucle que pueda quedarse
        // a medias; la validación del cuerpo se mantiene igualmente, porque un
        // no-uuid dentro del `uuid[]` sería un 22P02 de Postgres (500 redactado) en
        // vez de un 400 que dice qué campo está mal. Cero peticiones.
        assertTrue(upstream.isEmpty(), "la validación del cuerpo va antes de llamar a la RPC")
    }

    @Test fun aValidReorderReachesTheRpcWithBothIdentifiers() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.patch("/api/v1/admin/examenes/$examenId/preguntas/orden") {
            contentType(ContentType.Application.Json)
            setBody("""{"ids":["$preguntaId"]}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = upstream.single { it.uri().rawPath.endsWith("/rpc/admin_reorder_exam_questions") }
        val body = readRequestBody(rpc)
        assertTrue(body.contains("\"p_examen_id\":\"$examenId\""), body)
        assertTrue(body.contains("\"p_pregunta_ids\":[\"$preguntaId\"]"), body)
        assertTrue(
            upstream.none { it.method() == "PATCH" && it.uri().rawPath.contains("preguntas_examen") },
            "las posiciones las escribe la RPC, no un bucle de PATCH",
        )
    }

    // -----------------------------------------------------------------
    // PATCH / DELETE /preguntas/{id}/recursos/{recursoId}
    // -----------------------------------------------------------------

    @Test fun aFragmentInTheResourceIdNeverReachesSupabaseOnArchive() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.delete("/api/v1/admin/preguntas/$preguntaId/recursos/$recursoId%23")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "el filtro que se perdería es el de pertenencia: no se llama")
    }

    @Test fun aFragmentInTheResourceIdNeverReachesSupabaseOnUpdate() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.patch("/api/v1/admin/preguntas/$preguntaId/recursos/$recursoId%23") {
            contentType(ContentType.Application.Json)
            setBody("""{"tipoRecurso":"texto","titulo":"t","posicion":1}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "update reasigna pregunta_id: sin el filtro de pertenencia, roba el recurso")
    }

    @Test fun aFragmentInTheQuestionIdOfAResourceRouteIsRejectedToo() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.delete("/api/v1/admin/preguntas/$preguntaId%23/recursos/$recursoId")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "el segundo identificador del filtro también se valida")
    }

    /** No-regresión: con identificadores válidos la ruta sigue archivando y
     * el PATCH lleva **los dos** filtros. Sin esto, "rechazarlo todo" pasaría
     * los cuatro casos anteriores. */
    @Test fun validIdsStillArchiveTheResourceWithBothFilters() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.delete("/api/v1/admin/preguntas/$preguntaId/recursos/$recursoId")

        assertEquals(HttpStatusCode.OK, response.status)
        val write = upstream.first { it.method() == "PATCH" && it.uri().rawPath.contains("recursos_pregunta") }
        val query = write.uri().rawQuery.orEmpty()
        assertTrue(query.contains("id=eq.$recursoId"), query)
        assertTrue(query.contains("pregunta_id=eq.$preguntaId"), query)
    }

    // -----------------------------------------------------------------
    // Los `{id}` sueltos: aquí el truncado no deja un filtro a medias,
    // deja la escritura SIN filtro
    // -----------------------------------------------------------------

    /**
     * `archivePregunta` compone `PATCH /preguntas?id=eq.$id` con
     * `{"activa":false}`. Truncado, sale un PATCH sin ningún filtro: **el banco
     * de preguntas entero pasa a `activa=false`**. Es el caso más caro de todo
     * este fichero y no estaba validado.
     */
    @Test fun aFragmentInTheQuestionIdOfAnArchiveNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.delete("/api/v1/admin/preguntas/$preguntaId%23")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "un PATCH sin filtro archivaría TODAS las preguntas: ${upstream.map { it.uri() }}")
    }

    /** Mismo mecanismo sobre `examenes`: el parche de un examen escrito sobre todos. */
    @Test fun aFragmentInTheExamIdOfAnUpdateNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.patch("/api/v1/admin/examenes/$examenId%23") {
            contentType(ContentType.Application.Json)
            setBody("""{"especialidadId":"$preguntaId","titulo":"Examen"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "sin filtro, un PATCH reescribe todos los exámenes: ${upstream.map { it.uri() }}")
    }

    @Test fun aFragmentInTheExamIdOfAnArchiveNeverReachesSupabase() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.delete("/api/v1/admin/examenes/$examenId%23")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "sin filtro, se archiva el catálogo entero: ${upstream.map { it.uri() }}")
    }

    /**
     * Aquí no hay escritura, hay AMPLIFICACIÓN DE LECTURA: el `#` se lleva por
     * delante el `order` y el `limit`, y la página de respuestas de un hilo pasa
     * a ser una descarga sin cota de `comentarios_oposired`.
     */
    @Test fun aFragmentInTheThreadIdLeavesTheForumPageWithoutItsLimit() = testApplication {
        val upstream = mutableListOf<HttpRequest>()
        application { truncationTestModule(upstream) }

        val response = client.get("/api/v1/admin/moderacion/hilos/$preguntaId%23/respuestas")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(upstream.isEmpty(), "la lectura sin cota no llega a salir: ${upstream.map { it.uri() }}")
    }

    // -----------------------------------------------------------------

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
