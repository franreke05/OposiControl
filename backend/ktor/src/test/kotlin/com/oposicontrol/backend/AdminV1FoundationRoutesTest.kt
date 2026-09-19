package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
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
 * Fase 1 sobre las rutas de `AdminV1Routes`: lo que sale (o no sale) hacia Supabase
 * y lo que vuelve al cliente.
 *
 * Todo pasa por el `StatusPages` de producción (`installBackendStatusPages`): lo que
 * se prueba es la respuesta real, no la de una copia.
 *
 * El pipeline se fija a un hilo propio con [RequestThreadProbe], que además lo
 * REGISTRA desde dentro. Así "esta llamada al gateway fue al pool IO" se puede
 * afirmar de verdad: la sonda compara identidades de hilo, no nombres esperados.
 */
class AdminV1FoundationRoutesTest {

    private val pipelineThreadName = "foundation-routes-pipeline-thread"

    /** La sonda de la app en vuelo: la crea [routesApp] y la usan los casos de salto de hilo. */
    private lateinit var requestThreadProbe: RequestThreadProbe
    private val especialidadId = "44444444-4444-4444-8444-444444444444"
    private val comunidadId = "55555555-5555-4555-8555-555555555555"
    private val examenId = "66666666-6666-4666-8666-666666666666"

    /** Una petición al upstream y el hilo desde el que salió. */
    private data class Upstream(val request: HttpRequest, val thread: String)

    /** Una ruta con su nombre, para que cada fallo diga cuál fue. */
    private data class RouteCall(val route: String, val call: suspend () -> HttpResponse)

    // -----------------------------------------------------------------
    // F-10 · Especialidades y comunidades en el pool IO
    // -----------------------------------------------------------------

    @Test
    fun everySpecialtyAndCommunityRouteCallsTheGatewayOffTheRequestThread() = routesApp { upstream ->
        val calls = listOf(
            RouteCall("GET especialidades") { client.get("/api/v1/admin/especialidades") },
            RouteCall("POST especialidades") { client.post("/api/v1/admin/especialidades") { jsonBody("""{"nombre":"TCAE"}""") } },
            RouteCall("PATCH especialidades/{id}") {
                client.patch("/api/v1/admin/especialidades/$especialidadId") { jsonBody("""{"nombre":"TCAE"}""") }
            },
            RouteCall("DELETE especialidades/{id}") { client.delete("/api/v1/admin/especialidades/$especialidadId") },
            RouteCall("GET especialidades/{id}/capacidades") {
                client.get("/api/v1/admin/especialidades/$especialidadId/capacidades")
            },
            RouteCall("PUT especialidades/{id}/capacidades") {
                client.put("/api/v1/admin/especialidades/$especialidadId/capacidades") {
                    jsonBody("""{"especialidadId":"$especialidadId"}""")
                }
            },
            RouteCall("GET comunidades") { client.get("/api/v1/admin/comunidades-autonomas") },
            RouteCall("POST comunidades") {
                client.post("/api/v1/admin/comunidades-autonomas") { jsonBody("""{"nombre":"Madrid"}""") }
            },
            RouteCall("PATCH comunidades/{id}") {
                client.patch("/api/v1/admin/comunidades-autonomas/$comunidadId") { jsonBody("""{"nombre":"Madrid"}""") }
            },
            RouteCall("DELETE comunidades/{id}") { client.delete("/api/v1/admin/comunidades-autonomas/$comunidadId") },
        )

        calls.forEach { (route, call) ->
            upstream.clear()
            requestThreadProbe.clear()
            val response = call()

            assertTrue(response.status.value in 200..299, "$route respondió ${response.status}: ${response.bodyAsText()}")
            assertTrue(upstream.isNotEmpty(), "$route no llegó a llamar al gateway")
            // Cada petición al upstream se compara con el hilo REAL de la petición, que
            // la sonda registró desde dentro del pipeline. Una llamada bloqueante ahí
            // ocupa el hilo hasta 20 s y deja a Netty sin aceptar peticiones nuevas.
            requestThreadProbe.assertRanOffTheRequestThread(route)
        }
    }

    // -----------------------------------------------------------------
    // F-06 · Ids de ruta validados antes de salir
    // -----------------------------------------------------------------

    @Test
    fun anInvalidPathIdIsA400WithoutCallingSupabaseAndWithoutLeakingTheProject() = routesApp { upstream ->
        val calls = listOf(
            RouteCall("PATCH especialidades") {
                client.patch("/api/v1/admin/especialidades/a%20b") { jsonBody("""{"nombre":"TCAE"}""") }
            },
            RouteCall("DELETE especialidades") { client.delete("/api/v1/admin/especialidades/a%20b") },
            RouteCall("PATCH comunidades") {
                client.patch("/api/v1/admin/comunidades-autonomas/a%20b") { jsonBody("""{"nombre":"Madrid"}""") }
            },
            RouteCall("DELETE comunidades") { client.delete("/api/v1/admin/comunidades-autonomas/$comunidadId%23") },
        )

        calls.forEach { (route, call) ->
            upstream.clear()
            val response = call()

            assertEquals(HttpStatusCode.BadRequest, response.status, route)
            val body = response.bodyAsText()
            assertTrue(upstream.isEmpty(), "$route salió hacia Supabase con un id inválido: ${upstream.map { it.request.uri() }}")
            listOf("stub.supabase.test", "supabase", "/rest/v1/").forEach { leak ->
                assertFalse(body.contains(leak, ignoreCase = true), "$route filtra '$leak': $body")
            }
        }
    }

    // -----------------------------------------------------------------
    // F-07 · Ids de cuerpo validados antes de salir
    // -----------------------------------------------------------------

    @Test
    fun anInvalidBodyIdCreatingAnExamOrAQuestionIsA400WithoutCallingSupabase() = routesApp { upstream ->
        val calls = listOf(
            RouteCall("examen · especialidadId") {
                client.post("/api/v1/admin/examenes") { jsonBody("""{"especialidadId":"x-probe-c","titulo":"Examen"}""") }
            },
            RouteCall("examen · comunidadId") {
                client.post("/api/v1/admin/examenes") {
                    jsonBody("""{"especialidadId":"$especialidadId","comunidadId":"x-probe-c","titulo":"Examen"}""")
                }
            },
            RouteCall("pregunta · examenId") {
                client.post("/api/v1/admin/preguntas") {
                    jsonBody("""{"examenId":"x-probe-c","titulo":"P"}""")
                }
            },
        )

        calls.forEach { (route, call) ->
            upstream.clear()
            val response = call()

            assertEquals(HttpStatusCode.BadRequest, response.status, "$route: ${response.bodyAsText()}")
            assertTrue(upstream.isEmpty(), "$route llegó a Supabase: ${upstream.map { it.request.uri() }}")
            assertFalse(response.bodyAsText().contains("x-probe-c"), "$route repite el valor del cliente")
        }
    }

    // -----------------------------------------------------------------
    // F-09 · El tipo de archivo del cliente no vuelve en el mensaje
    // -----------------------------------------------------------------

    /**
     * Desde K5 el rechazo es el 422 de dominio `EXAM_COVER_TYPE_INVALID` (antes un
     * 400 genérico), y la regla de F-09 sigue igual: el tipo del cliente no vuelve.
     */
    @Test
    fun aCoverWithAForbiddenMimeTypeIsRejectedWithoutEchoingIt() = routesApp { upstream ->
        val response = client.post("/api/v1/admin/examenes/$examenId/portada") {
            setBody(multipartFile(bytes = ByteArray(16), contentType = "application/x-probe-c"))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("exam_cover_type_invalid"), body)
        assertFalse(body.contains("x-probe-c"), "el rechazo repite el tipo que mandó el cliente: $body")
        assertTrue(upstream.isEmpty())
    }

    // -----------------------------------------------------------------
    // F-05 · Importación de PDF: sólo un rechazo del PDF es 422
    // -----------------------------------------------------------------

    /** Un archivo que no es un PDF: 422 con un mensaje escrito para el administrador. */
    @Test
    fun anUnreadablePdfIsA422WithAHumanMessageAndNothingFromPdfBox() = routesApp { _ ->
        val response = postPdf(bytes = "esto no es un pdf".encodeToByteArray())

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("pdf_unprocessable"), body)
        listOf("Header", "versioninfo", "End-of-File", "PDFBox", "Exception", "COSStream").forEach { leak ->
            assertFalse(body.contains(leak), "el 422 filtra '$leak': $body")
        }
    }

    /**
     * Un fallo de Supabase durante la importación NO es un 422 con su texto: llega a
     * StatusPages y sale redactado. Antes la ruta capturaba `Throwable` y devolvía el
     * `message`, que era el cuerpo crudo de PostgREST.
     */
    @Test
    fun anUpstreamFailureDuringThePdfImportIsRedactedAndNotEchoed() = routesApp(
        responder = { uri ->
            if (uri.contains("/rest/v1/examenes")) {
                V1StubResponse(
                    500,
                    """{"code":"XX000","details":"Failing row contains (x-probe-c)","hint":"x-hint-c",""" +
                        """"message":"new row for relation \"x\" violates check constraint \"x_check\""}""",
                )
            } else {
                V1StubResponse(200, "[]")
            }
        },
    ) { _ ->
        val response = postPdf(bytes = "esto no es un pdf".encodeToByteArray())

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("backend_supabase_error"), body)
        listOf("x-probe-c", "x-hint-c", "/rest/v1/", "Failing row", "\"code\"", "constraint").forEach { leak ->
            assertFalse(body.contains(leak), "el error de importación filtra '$leak': $body")
        }
    }

    // -----------------------------------------------------------------
    // F-13 · Multipart que suspende, con el límite exacto
    // -----------------------------------------------------------------

    @Test
    fun aPdfOfExactlyTheLimitIsReadAndOneByteMoreIsRejected() = routesApp { _ ->
        val limit = 20 * 1024 * 1024

        val atLimit = postPdf(bytes = ByteArray(limit))
        assertEquals(
            HttpStatusCode.UnprocessableEntity,
            atLimit.status,
            "20 MB justos se aceptan y llegan al lector de PDF: ${atLimit.bodyAsText()}",
        )

        val overLimit = postPdf(bytes = ByteArray(limit + 1))
        assertEquals(HttpStatusCode.BadRequest, overLimit.status)
        assertTrue(overLimit.bodyAsText().contains("20 MB"), "el rechazo por tamaño dice el límite")
    }

    /** Ninguna lectura de multipart bloqueante queda en las rutas. */
    @Test
    fun theAdminRoutesNoLongerReadMultipartThroughABlockingStream() {
        val source = repoFile("backend/ktor/src/main/kotlin/com/oposicontrol/backend/AdminV1Routes.kt")
            .lineSequence()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }
            .joinToString("\n")

        assertFalse(source.contains("streamProvider()"), "vuelve a leerse multipart con un InputStream bloqueante")
        assertFalse(source.contains("readNBytes("), "vuelve a leerse multipart con un InputStream bloqueante")
    }

    // -----------------------------------------------------------------
    // F-05 · `error_parseo` sólo con mensajes escritos para el administrador
    // -----------------------------------------------------------------

    /**
     * Desde KI3-1 la publicación es UNA RPC y Kotlin ya no anota nada en las filas:
     * un fallo de infraestructura de la RPC es un 500 redactado con referencia, y
     * ni el cuerpo de PostgREST llega al cliente ni se escribe en `error_parseo`
     * (que ahora sólo escriben el parser, la revisión humana y la propia base).
     */
    @Test
    fun aFailedImportPublishIsRedactedAndWritesNothingIntoTheStagingRows() = routesApp(
        responder = { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_preguntas") -> V1StubResponse(
                    500,
                    """{"code":"XX000","details":"Failing row contains (x-probe-c)","hint":"x-hint-c","message":"x-probe-c exploto"}""",
                )
                else -> defaultResponder(uri)
            }
        },
    ) { upstream ->
        val loteId = "77777777-7777-4777-8777-777777777777"

        val response = client.post("/api/v1/admin/importaciones/$loteId/importar")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val body = response.bodyAsText()
        listOf("x-probe-c", "x-hint-c", "Failing row", "/rest/v1/", "XX000").forEach { leak ->
            assertFalse(body.contains(leak), "la respuesta filtra '$leak': $body")
        }
        assertTrue(
            upstream.none { it.request.method() == "PATCH" && it.request.uri().rawPath.contains("preguntas_parseadas_staging") },
            "Kotlin ya no escribe diagnósticos fila a fila: ${upstream.map { "${it.request.method()} ${it.request.uri().rawPath}" }}",
        )
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun defaultResponder(uri: String): V1StubResponse = when {
        uri.contains("/rest/v1/especialidades") -> V1StubResponse(200, """[{"id":"$especialidadId","nombre":"TCAE"}]""")
        uri.contains("/rest/v1/comunidades_autonomas") -> V1StubResponse(200, """[{"id":"$comunidadId","nombre":"Madrid"}]""")
        uri.contains("/rest/v1/examenes") -> V1StubResponse(
            200,
            """[{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"$especialidadId",""" +
                """"titulo":"Examen","tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true}]""",
        )
        else -> V1StubResponse(200, "[]")
    }

    /**
     * El pipeline se FIJA al hilo de [probe] con [pinPipelineTo].
     *
     * Antes se configuraba `engine { dispatcher = … }` y se comparaban NOMBRES de
     * hilo. El motor de pruebas no garantiza ejecutar los handlers en ese dispatcher,
     * así que «la llamada no salió del hilo llamado así» era cierta también sin el
     * salto al pool IO: la mutación que quita `withUpstreamIo` de una ruta pasaba
     * entera (MUT-F10). Fijando el pipeline, el hilo de la petición es un hecho
     * registrado y no una suposición.
     */
    private fun routesApp(
        responder: (String) -> V1StubResponse = ::defaultResponder,
        block: suspend ApplicationTestBuilder.(upstream: MutableList<Upstream>) -> Unit,
    ) {
        RequestThreadProbe(pipelineThreadName).use { probe ->
            requestThreadProbe = probe
            testApplication {
                val upstream = CopyOnWriteArrayList<Upstream>()
                val gateway = AdminV1Gateway.forTesting(
                    supabaseUrl = "https://stub.supabase.test",
                    serviceRoleKey = "stub-key",
                    json = Json { ignoreUnknownKeys = true },
                    client = V1StubHttpClient(
                        {
                            upstream += Upstream(it, Thread.currentThread().name)
                            probe.record(it.uri().rawPath)
                        },
                        responder,
                    ),
                )
                application {
                    pinPipelineTo(probe)
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                    installRequestCorrelation()
                    installBackendStatusPages()
                    routing { adminV1Routes(gateway) }
                }
                block(upstream)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.postPdf(bytes: ByteArray): HttpResponse =
        client.post("/api/v1/admin/importaciones/preguntas/pdf") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("examenId", examenId)
                        append("tipoExamen", "rafaga")
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"t1.pdf\"")
                                append(HttpHeaders.ContentType, "application/pdf")
                            },
                        )
                    },
                ),
            )
        }

    private fun multipartFile(bytes: ByteArray, contentType: String): MultiPartFormDataContent =
        MultiPartFormDataContent(
            formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"portada.bin\"")
                        append(HttpHeaders.ContentType, contentType)
                    },
                )
            },
        )

    private fun io.ktor.client.request.HttpRequestBuilder.jsonBody(json: String) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private fun repoFile(relativePath: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        val root = assertNotNull(dir, "No se encontró la raíz del repositorio desde ${System.getProperty("user.dir")}")
        return File(root, relativePath).readText()
    }
}
