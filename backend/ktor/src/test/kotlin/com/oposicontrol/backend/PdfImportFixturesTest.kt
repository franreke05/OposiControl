package com.oposicontrol.backend

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.Loader
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Importación con PDF REALES (binarios de `src/test/resources/pdf/`, generados una vez con
 * [GeneratePdfFixtures]). Nada de mocks del documento: bytes → `Loader.loadPDF` →
 * `PDFTextStripper` ([extractTextFromPdf]) → [parseQuestionBlocks], y la ruta
 * `POST /importaciones/preguntas/pdf` con el stub HTTP de Supabase para ver el staging que se
 * escribiría.
 */
class PdfImportFixturesTest {

    private val examenId = "22222222-2222-4222-8222-222222222222"
    private val loteId = "11111111-1111-4111-8111-111111111111"

    private fun pdf(name: String): ByteArray =
        assertNotNull(javaClass.getResourceAsStream("/pdf/$name"), "falta el fixture $name").use { it.readBytes() }

    private fun parse(name: String, tipo: String): RafagaParseResult = parseQuestionBlocks(extractTextFromPdf(pdf(name)), tipo)

    // -----------------------------------------------------------------
    // Los ficheros son PDF de verdad
    // -----------------------------------------------------------------

    @Test
    fun everyFixtureIsARealBinaryPdf() {
        PdfFixtureFactory.allNames().forEach { name ->
            val bytes = pdf(name)
            assertEquals("%PDF-", String(bytes, 0, 5, Charsets.ISO_8859_1), "$name no es un PDF")
            Loader.loadPDF(bytes).use { doc -> assertTrue(doc.numberOfPages >= 1, name) }
        }
    }

    // -----------------------------------------------------------------
    // PDF válidos
    // -----------------------------------------------------------------

    @Test
    fun aValidRafagaPdfYieldsItsQuestionsWithAccentsAndUppercasedAnswers() {
        val parsed = parse("rafaga_valid.pdf", "rafaga")

        assertTrue(parsed.bloquesConError.isEmpty(), "${parsed.bloquesConError}")
        assertEquals(3, parsed.preguntas.size)
        assertEquals(listOf("B", "C", "A"), parsed.preguntas.map { it.respuesta })
        assertEquals("¿Cuál es el plazo para interponer un recurso de alzada?", parsed.preguntas[0].titulo)
        assertEquals("¡Atención! ¿Qué órgano resuelve el recurso de reposición?", parsed.preguntas[1].titulo)
        assertTrue(parsed.preguntas.all { it.opciones.size == 4 && !it.anulada && it.motivoAnulacion == null })
        assertTrue(parsed.preguntas.all { it.advertencia == null }, "una ráfaga limpia no lleva aviso")
    }

    @Test
    fun aValidRazonadaPdfKeepsItsReasoningSourceAndTags() {
        val parsed = parse("razonada_valid.pdf", "razonada")

        assertTrue(parsed.bloquesConError.isEmpty(), "${parsed.bloquesConError}")
        assertEquals(3, parsed.preguntas.size)
        assertEquals(listOf("B", "A", "C"), parsed.preguntas.map { it.respuesta })
        assertTrue(parsed.preguntas.all { it.razonamiento.isNotBlank() })
        val primera = parsed.preguntas.first()
        assertTrue(primera.razonamiento.contains("procedimiento agravado"), "el razonamiento de dos líneas se une: ${primera.razonamiento}")
        assertEquals("Constitución Española, arts. 166 a 169", primera.fuente)
        assertEquals(listOf("constitucion", "reforma"), primera.etiquetas)
    }

    @Test
    fun aValidInmersivaPdfRequiresAndKeepsItsReasoning() {
        val parsed = parse("inmersiva_valid.pdf", "inmersiva")

        assertTrue(parsed.bloquesConError.isEmpty(), "${parsed.bloquesConError}")
        assertEquals(2, parsed.preguntas.size)
        assertEquals(listOf("B", "A"), parsed.preguntas.map { it.respuesta })
        assertTrue(parsed.preguntas.all { it.razonamiento.isNotBlank() })
    }

    // -----------------------------------------------------------------
    // Anuladas
    // -----------------------------------------------------------------

    @Test
    fun anAnnulledQuestionAndANormalOneInTheSamePdf() {
        val parsed = parse("razonada_annulled.pdf", "razonada")

        assertTrue(parsed.bloquesConError.isEmpty(), "${parsed.bloquesConError}")
        val (anulada, normal) = parsed.preguntas
        assertTrue(anulada.anulada)
        assertEquals("Anulada por el tribunal", anulada.motivoAnulacion)
        assertEquals("B", anulada.respuesta, "la anulada conserva su respuesta y su razonamiento")
        assertTrue(anulada.razonamiento.contains("artículo 91"))
        assertFalse(normal.anulada)
        assertNull(normal.motivoAnulacion)
        assertNull(anulada.advertencia, "una anulada válida es una fila ok")
    }

    @Test
    fun aMultiPageMixedPdfKeepsEveryQuestionIncludingTheOneThatCrossesAPage() {
        Loader.loadPDF(pdf("mixed_normal_annulled.pdf")).use { doc -> assertEquals(3, doc.numberOfPages) }

        val parsed = parse("mixed_normal_annulled.pdf", "razonada")

        assertTrue(parsed.bloquesConError.isEmpty(), "${parsed.bloquesConError}")
        assertEquals((1..8).toList(), parsed.preguntas.map { it.numero })
        assertEquals(listOf(false, true, false, true, false, true, false, false), parsed.preguntas.map { it.anulada })
        assertEquals(
            listOf(null, "Enunciado ambiguo según el tribunal", null, null, null, "Dos respuestas correctas", null, null),
            parsed.preguntas.map { it.motivoAnulacion },
            "la cuarta está anulada sin motivo: motivo null",
        )
        val cruzada = parsed.preguntas[2]
        assertTrue(
            cruzada.razonamiento.contains("primera página") && cruzada.razonamiento.contains("segunda"),
            "la pregunta que cruza de página no se parte: ${cruzada.razonamiento}",
        )
        assertNull(cruzada.advertencia, "no había marcador de página en medio")
    }

    // -----------------------------------------------------------------
    // PDF con errores: llegan como filas de error, nunca se pierden ni se fusionan
    // -----------------------------------------------------------------

    @Test
    fun aNumberedSeparatorIsAClearErrorAndTheNeighboursSurvive() {
        val parsed = parse("invalid_separator.pdf", "rafaga")

        assertEquals(listOf(1, 3), parsed.preguntas.map { it.numero })
        val error = assertNotNull(parsed.bloquesConError.singleOrNull(), "${parsed.bloquesConError}")
        assertEquals(2, error.numero)
        assertTrue(error.detalle.contains("Separador no válido: usa exactamente --- PREGUNTA ---"), error.detalle)
        assertEquals("Pregunta con el separador numerado", error.titulo)
    }

    @Test
    fun theCoverPageBeforeTheFirstSeparatorCreatesNoRow() {
        val parsed = parse("with_preamble.pdf", "rafaga")

        assertTrue(parsed.bloquesConError.isEmpty(), "el preámbulo no genera fila: ${parsed.bloquesConError}")
        assertEquals(listOf(1, 2), parsed.preguntas.map { it.numero })
        assertEquals(listOf("¿Cuál es la capital de España?", "¿En qué año se aprobó la Constitución?"), parsed.preguntas.map { it.titulo })
        assertTrue(parsed.preguntas.none { it.titulo.contains("portada") })
    }

    @Test
    fun aDuplicatedFieldIsAnErrorAndKeepsTheFirstAnswer() {
        val parsed = parse("duplicated_field.pdf", "rafaga")

        assertEquals(1, parsed.preguntas.size)
        val error = assertNotNull(parsed.bloquesConError.singleOrNull())
        assertEquals(2, error.numero)
        assertTrue(error.detalle.contains("Campo duplicado: RESPUESTA"), error.detalle)
        assertEquals("B", error.respuesta)
    }

    @Test
    fun aMalformedQuestionsAreErrorRowsAndTheGoodOneStillImports() {
        val parsed = parse("malformed_question.pdf", "rafaga")

        assertEquals(listOf(3), parsed.preguntas.map { it.numero })
        assertEquals(listOf(1, 2), parsed.bloquesConError.map { it.numero })
        assertTrue(parsed.bloquesConError[0].detalle.contains("C: Opción C obligatoria"), parsed.bloquesConError[0].detalle)
        assertTrue(parsed.bloquesConError[1].detalle.contains("RESPUESTA: Debe ser A, B, C o D"), parsed.bloquesConError[1].detalle)
        assertEquals("E", parsed.bloquesConError[1].respuesta, "la letra leída viaja; el staging la sanea a NULL")
    }

    @Test
    fun aPdfWithoutSelectableTextIsRejectedAsA422() {
        assertTrue(extractTextFromPdf(pdf(PdfFixtureFactory.NO_TEXT_FIXTURE)).isBlank())

        val error = assertFailsWith<PdfImportRejectedException> {
            gateway(mutableListOf(), "rafaga").crearLoteDesdePdf(examenId, "rafaga", "escaneado.pdf", pdf(PdfFixtureFactory.NO_TEXT_FIXTURE))
        }
        assertTrue(error.message.orEmpty().contains("no contiene texto seleccionable"), error.message)
    }

    // -----------------------------------------------------------------
    // Ruta: POST /importaciones/preguntas/pdf con el PDF real
    // -----------------------------------------------------------------

    @Test
    fun uploadingAnAnnulledPdfWritesAnulladaAndMotivoToStagingAsOkRows() = routeApp("razonado") { upstream ->
        val response = postPdf("razonada_annulled.pdf", "razonado")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rows = stagingRows(upstream)
        assertEquals(2, rows.size)
        assertEquals(listOf(true, false), rows.map { it["anulada"]!!.jsonPrimitive.boolean })
        assertEquals("Anulada por el tribunal", rows[0]["motivo_anulacion"]!!.jsonPrimitive.content)
        assertTrue(rows[1]["motivo_anulacion"] is JsonNull, "una normal viaja con motivo null explícito (mismas claves en todas las filas)")
        assertEquals(listOf("ok", "ok"), rows.map { it["estado_parseo"]!!.jsonPrimitive.content }, "anulada es ortogonal al estado de parseo")
        assertEquals(listOf(1, 2), rows.map { it["posicion"]!!.jsonPrimitive.int })
        assertEquals("B", rows[0]["respuesta_correcta"]!!.jsonPrimitive.content)
        val lote = upstream.first { it.method() == "POST" && it.uri().rawPath.endsWith("/lotes_importacion_preguntas") }
        assertTrue(readRequestBody(lote).contains("\"total_detectadas\":2"))
    }

    @Test
    fun uploadingAMixedPdfMapsEachRowsAnnulment() = routeApp("razonado") { upstream ->
        val response = postPdf("mixed_normal_annulled.pdf", "razonado")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rows = stagingRows(upstream)
        assertEquals(8, rows.size)
        assertEquals(
            listOf(false, true, false, true, false, true, false, false),
            rows.map { it["anulada"]!!.jsonPrimitive.boolean },
        )
        assertEquals(
            listOf(null, "Enunciado ambiguo según el tribunal", null, null, null, "Dos respuestas correctas", null, null),
            rows.map { it["motivo_anulacion"]!!.jsonPrimitive.contentOrNull },
        )
        assertTrue(rows.all { it["estado_parseo"]!!.jsonPrimitive.content == "ok" })
    }

    @Test
    fun uploadingAPdfWithABadSeparatorWritesAnErrorRowInsteadOfMergingOrLosingIt() = routeApp("rafaga") { upstream ->
        val response = postPdf("invalid_separator.pdf", "rafaga")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rows = stagingRows(upstream)
        assertEquals(listOf("ok", "error", "ok"), rows.map { it["estado_parseo"]!!.jsonPrimitive.content })
        assertTrue(
            rows[1]["error_parseo"]!!.jsonPrimitive.content.contains("Separador no válido: usa exactamente --- PREGUNTA ---"),
            rows[1].toString(),
        )
        assertEquals(listOf(false, false, false), rows.map { it["anulada"]!!.jsonPrimitive.boolean })
    }

    @Test
    fun uploadingAPdfWithBrokenQuestionsWritesTheErrorRowsAndSanitisesTheAnswer() = routeApp("rafaga") { upstream ->
        val response = postPdf("malformed_question.pdf", "rafaga")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rows = stagingRows(upstream)
        assertEquals(listOf("error", "error", "ok"), rows.map { it["estado_parseo"]!!.jsonPrimitive.content })
        assertTrue(rows[1]["respuesta_correcta"] is JsonNull, "una letra fuera de A-D viaja como NULL: ${rows[1]}")
    }

    @Test
    fun uploadingAPdfWithADuplicatedFieldWritesAnErrorRow() = routeApp("rafaga") { upstream ->
        val response = postPdf("duplicated_field.pdf", "rafaga")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val rows = stagingRows(upstream)
        assertEquals(listOf("ok", "error"), rows.map { it["estado_parseo"]!!.jsonPrimitive.content })
        assertTrue(rows[1]["error_parseo"]!!.jsonPrimitive.content.contains("Campo duplicado: RESPUESTA"))
    }

    @Test
    fun uploadingAPdfWithPreambleWritesNoRowForTheCover() = routeApp("rafaga") { upstream ->
        val response = postPdf("with_preamble.pdf", "rafaga")

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        assertEquals(2, stagingRows(upstream).size)
    }

    @Test
    fun uploadingAScannedPdfIs422AndWritesNothing() = routeApp("rafaga") { upstream ->
        val response = postPdf(PdfFixtureFactory.NO_TEXT_FIXTURE, "rafaga")

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("pdf_unprocessable"), response.bodyAsText())
        assertTrue(upstream.none { it.method() == "POST" }, "no se escribió lote ni staging para un PDF sin texto")
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun stagingRows(upstream: List<HttpRequest>): List<JsonObject> {
        val insert = assertNotNull(
            upstream.firstOrNull { it.method() == "POST" && it.uri().rawPath.endsWith("/preguntas_parseadas_staging") },
            "no salió el INSERT de staging: " + upstream.joinToString { "${it.method()} ${it.uri().rawPath}" },
        )
        return (Json.parseToJsonElement(readRequestBody(insert)) as JsonArray).map { it.jsonObject }
    }

    private fun examenRow(tipo: String) =
        """{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"33333333-3333-4333-8333-333333333333","titulo":"Examen","tipo_examen":"$tipo","tipo_acceso":"gratuito","activo":true}"""

    private fun loteRow(tipo: String) =
        """{"id":"$loteId","examen_id":"$examenId","nombre_archivo_original":"t.pdf","tipo_examen":"$tipo",""" +
            """"estado":"parseado","total_detectadas":1,"total_importadas":0}"""

    private fun gateway(upstream: MutableList<HttpRequest>, tipo: String): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_duplicados") -> V1StubResponse(
                    404,
                    """{"code":"PGRST202","details":null,"hint":null,"message":"Could not find the function"}""",
                )
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRow(tipo)}]")
                // El INSERT no devuelve cuerpo; la relectura del lote (GET) ve el staging vacío.
                uri.contains("/rest/v1/preguntas_parseadas_staging") ->
                    if (upstream.lastOrNull()?.method() == "POST") V1StubResponse(201, "") else V1StubResponse(200, "[]")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, "[${examenRow(tipo)}]")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    private fun routeApp(tipo: String, block: suspend ApplicationTestBuilder.(upstream: MutableList<HttpRequest>) -> Unit) =
        testApplication {
            val upstream = CopyOnWriteArrayList<HttpRequest>()
            val gateway = gateway(upstream, tipo)
            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
                installRequestCorrelation()
                installBackendStatusPages()
                routing { adminV1Routes(gateway) }
            }
            block(upstream)
        }

    private suspend fun ApplicationTestBuilder.postPdf(name: String, tipoExamen: String): HttpResponse =
        client.post("/api/v1/admin/importaciones/preguntas/pdf") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("examenId", examenId)
                        append("tipoExamen", tipoExamen)
                        append(
                            "file",
                            pdf(name),
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$name\"")
                                append(HttpHeaders.ContentType, "application/pdf")
                            },
                        )
                    },
                ),
            )
        }
}
