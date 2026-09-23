package com.oposicontrol.backend

import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
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
 * Pregunta ANULADA en un examen (M10), lado admin: staging, resumen de lotes, resultado de la
 * publicación, relaciones pregunta↔examen en las lecturas y llamadores manuales de las RPC.
 *
 * La anulación es una propiedad de la RELACIÓN pregunta↔examen: nada de esto toca `preguntas`.
 */
class AdminV1AnnulmentTest {

    private val loteId = "11111111-1111-4111-8111-111111111111"
    private val loteId2 = "11111111-1111-4111-8111-222222222222"
    private val examenId = "22222222-2222-4222-8222-222222222222"
    private val especialidadId = "33333333-3333-4333-8333-333333333333"
    private val filaId = "44444444-4444-4444-8444-444444444444"
    private val preguntaId = "77777777-7777-4777-8777-777777777777"

    // -----------------------------------------------------------------
    // PATCH /importaciones/staging/{id}
    // -----------------------------------------------------------------

    @Test
    fun anAnnulmentAndItsTrimmedReasonAreWrittenToTheStagingColumns() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gateway(upstream).updateStaging(
            filaId,
            AdminStagingItemDto(titulo = "T", anulada = true, motivoAnulacion = "  Anulada por el tribunal  "),
        )

        val patch = stagingPatch(upstream)
        assertTrue(patch.contains("\"anulada\":true"), patch)
        assertTrue(patch.contains("\"motivo_anulacion\":\"Anulada por el tribunal\""), patch)
    }

    @Test
    fun anEmptyOrNullReasonIsWrittenAsExplicitNullSoItClearsTheStoredOne() {
        listOf(null, "", "   ").forEach { motivo ->
            val upstream = CopyOnWriteArrayList<HttpRequest>()

            gateway(upstream).updateStaging(filaId, AdminStagingItemDto(titulo = "T", anulada = true, motivoAnulacion = motivo))

            val patch = stagingPatch(upstream)
            assertTrue(patch.contains("\"anulada\":true"), patch)
            assertTrue(patch.contains("\"motivo_anulacion\":null"), "«$motivo»: $patch")
        }
    }

    /** Desanular: `anulada:false` viaja siempre, y con él el motivo a NULL (el CHECK lo exige). */
    @Test
    fun unAnnullingClearsTheReasonInTheSameWrite() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gateway(upstream).updateStaging(filaId, AdminStagingItemDto(titulo = "T", anulada = false))

        val patch = stagingPatch(upstream)
        assertTrue(patch.contains("\"anulada\":false"), patch)
        assertTrue(patch.contains("\"motivo_anulacion\":null"), patch)
    }

    @Test
    fun aReasonWithoutAnnulmentIsRejectedBeforeAnythingLeaves() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        val error = assertFailsWith<IllegalArgumentException> {
            gateway(upstream).updateStaging(filaId, AdminStagingItemDto(titulo = "T", anulada = false, motivoAnulacion = "porque sí"))
        }

        assertTrue(error.message.orEmpty().contains("solo se admite si la pregunta esta anulada"), error.message)
        assertTrue(upstream.isEmpty(), upstream.describe())
    }

    @Test
    fun aReasonOver500CharactersIsRejectedAndExactly500IsAccepted() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        val error = assertFailsWith<IllegalArgumentException> {
            gateway(upstream).updateStaging(
                filaId,
                AdminStagingItemDto(titulo = "T", anulada = true, motivoAnulacion = "m".repeat(501)),
            )
        }
        assertTrue(error.message.orEmpty().contains("500"), error.message)
        assertTrue(upstream.isEmpty(), upstream.describe())

        gateway(upstream).updateStaging(filaId, AdminStagingItemDto(titulo = "T", anulada = true, motivoAnulacion = "m".repeat(500)))
        assertTrue(stagingPatch(upstream).contains("\"motivo_anulacion\":\"${"m".repeat(500)}\""))
    }

    /** La ruta lo traduce a un 400 con el mensaje, sin llamar a Supabase. */
    @Test
    fun theRouteAnswersA400ForAReasonWithoutAnnulment() = testApplication {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = gateway(upstream)
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            routing { adminV1Routes(gateway) }
        }

        val response = client.patch("/api/v1/admin/importaciones/staging/$filaId") {
            contentType(ContentType.Application.Json)
            setBody("""{"titulo":"T","anulada":false,"motivoAnulacion":"porque sí"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("anulada"), response.bodyAsText())
        assertTrue(upstream.isEmpty(), upstream.describe())
    }

    @Test
    fun theStagingDtoReadsTheColumnsBackAndAlwaysEncodesAnulada() {
        val dto = gateway(CopyOnWriteArrayList()).updateStaging(
            filaId,
            AdminStagingItemDto(titulo = "T", anulada = true, motivoAnulacion = "x"),
        )

        assertTrue(dto.anulada)
        assertEquals("Anulada por el tribunal", dto.motivoAnulacion)
        assertTrue(Json.encodeToString(AdminStagingItemDto.serializer(), AdminStagingItemDto()).contains("\"anulada\":false"))
    }

    // -----------------------------------------------------------------
    // Creación de lote: columnas de staging
    // -----------------------------------------------------------------

    @Test
    fun crearLoteWritesAnulladaAndMotivoOnEveryRowWithTheSameKeys() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        gateway(upstream).crearLote(
            CrearLoteRequest(
                examenId = examenId,
                preguntas = listOf(
                    AdminStagingItemDto(posicion = 1, titulo = "Anulada", anulada = true, motivoAnulacion = "Tribunal"),
                    AdminStagingItemDto(posicion = 2, titulo = "Normal"),
                ),
            ),
        )

        val insert = readRequestBody(upstream.single { it.method() == "POST" && it.uri().rawPath.endsWith("/preguntas_parseadas_staging") })
        assertEquals(2, Regex("\"anulada\":").findAll(insert).count(), insert)
        assertTrue(insert.contains("\"anulada\":true,\"motivo_anulacion\":\"Tribunal\""), insert)
        assertTrue(insert.contains("\"anulada\":false,\"motivo_anulacion\":null"), insert)
    }

    /** Una fila de ERROR con un motivo ilegible no tira el lote entero; una fila ok con uno ilegible, sí es un 400. */
    @Test
    fun anErrorRowWithAnUnusableReasonDoesNotBreakTheBatchButAnOkRowDoes() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        gateway(upstream).crearLote(
            CrearLoteRequest(
                examenId = examenId,
                preguntas = listOf(
                    AdminStagingItemDto(posicion = 1, titulo = "Errata", estadoParseo = "error", motivoAnulacion = "sin ESTADO"),
                    AdminStagingItemDto(posicion = 2, titulo = "Larga", estadoParseo = "error", anulada = true, motivoAnulacion = "m".repeat(600)),
                ),
            ),
        )
        val insert = readRequestBody(upstream.single { it.method() == "POST" && it.uri().rawPath.endsWith("/preguntas_parseadas_staging") })
        assertFalse(insert.contains("sin ESTADO") || insert.contains("m".repeat(600)), "el motivo ilegible no viaja a la columna")

        assertFailsWith<IllegalArgumentException> {
            gateway(CopyOnWriteArrayList()).crearLote(
                CrearLoteRequest(examenId = examenId, preguntas = listOf(AdminStagingItemDto(titulo = "T", motivoAnulacion = "x"))),
            )
        }
    }

    /** Una fila inválida hace fallar la creación ANTES de insertar el lote: nada que compensar, nada huérfano. */
    @Test
    fun aValidationFailureInAStagingRowNeverInsertsTheBatch() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()

        assertFailsWith<IllegalArgumentException> {
            gateway(upstream).crearLote(
                CrearLoteRequest(
                    examenId = examenId,
                    preguntas = listOf(
                        AdminStagingItemDto(posicion = 1, titulo = "Buena"),
                        AdminStagingItemDto(posicion = 2, titulo = "Motivo sin anulada", motivoAnulacion = "x"),
                    ),
                ),
            )
        }

        assertTrue(
            upstream.none { it.method() != "GET" },
            "ningún POST/DELETE: ${upstream.map { it.method() + " " + it.uri().rawPath }}",
        )
    }

    // -----------------------------------------------------------------
    // Resumen del lote
    // -----------------------------------------------------------------

    @Test
    fun theLotListCarriesAServerSideSummaryPerLote() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = summaryGateway(upstream) { uri ->
            when {
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRow(loteId)},${loteRow(loteId2)}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(
                    200,
                    stagingStates(loteId to "ok", loteId to "aprobada", loteId to "error", loteId to "advertencia", loteId to "omitida",
                        loteId to "importada", loteId2 to "ok"),
                    contentRange = "0-6/7",
                )
                else -> V1StubResponse(200, "[]")
            }
        }

        val lotes = gateway.listLotes()

        assertEquals(AdminLoteResumenDto(listas = 2, conError = 1, advertencias = 1, omitidas = 1, importadas = 1), lotes[0].resumen)
        assertEquals(AdminLoteResumenDto(listas = 1), lotes[1].resumen)
        val query = upstream.single { it.uri().rawPath.endsWith("/preguntas_parseadas_staging") }.uri().rawQuery
        assertTrue(query.contains("select=lote_id,estado_parseo") && query.contains("lote_id=in.($loteId,$loteId2)"), query)
    }

    /** NUNCA un recuento sobre la primera página: se pagina hasta el total que dice Content-Range. */
    @Test
    fun theSummaryPaginatesUntilTheTotalInsteadOfSilentlyStoppingAtOnePage() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = summaryGateway(upstream) { uri ->
            when {
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRow(loteId)}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") && uri.contains("offset=0") ->
                    V1StubResponse(200, stagingStates(*Array(1000) { loteId to "ok" }), contentRange = "0-999/1500")
                uri.contains("/rest/v1/preguntas_parseadas_staging") && uri.contains("offset=1000") ->
                    V1StubResponse(200, stagingStates(*Array(500) { loteId to "error" }), contentRange = "1000-1499/1500")
                else -> V1StubResponse(200, "[]")
            }
        }

        val resumen = assertNotNull(gateway.listLotes().single().resumen)

        assertEquals(1000, resumen.listas)
        assertEquals(500, resumen.conError, "la segunda página también cuenta")
        assertEquals(2, upstream.count { it.uri().rawPath.endsWith("/preguntas_parseadas_staging") })
    }

    /** Si no se puede calcular el resumen, es null: nunca un 0/0 inventado, y el listado sigue funcionando. */
    @Test
    fun anUnreadableSummaryIsNullAndTheListStillWorks() {
        val gateway = summaryGateway(CopyOnWriteArrayList()) { uri ->
            when {
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRow(loteId)}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(500, """{"message":"boom"}""")
                else -> V1StubResponse(200, "[]")
            }
        }

        val lote = gateway.listLotes().single()

        assertNull(lote.resumen)
        assertEquals(loteId, lote.id)
    }

    @Test
    fun aLoteWithNoRowsHasARealZeroSummary() {
        val gateway = summaryGateway(CopyOnWriteArrayList()) { uri ->
            when {
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRow(loteId)}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(200, "[]", contentRange = "*/0")
                else -> V1StubResponse(200, "[]")
            }
        }

        assertEquals(AdminLoteResumenDto(), gateway.listLotes().single().resumen)
    }

    @Test
    fun theDetailSummaryIsDerivedFromTheRowsAndIsNullWhenRowsAreMissing() {
        fun detail(totalDetectadas: Int): AdminLoteDto? = summaryGateway(CopyOnWriteArrayList()) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_duplicados") -> V1StubResponse(404, """{"code":"PGRST202","message":"x"}""")
                uri.contains("/rest/v1/lotes_importacion_preguntas") ->
                    V1StubResponse(200, "[${loteRow(loteId, totalDetectadas)}]")
                uri.contains("/rest/v1/preguntas_parseadas_staging") -> V1StubResponse(
                    200,
                    "[${stagingRowJson("ok", anulada = true)},${stagingRowJson("error")},${stagingRowJson("omitida")}]",
                )
                else -> V1StubResponse(200, "[]")
            }
        }.getLote(loteId)

        assertEquals(AdminLoteResumenDto(listas = 1, conError = 1, omitidas = 1), detail(3)?.resumen)
        assertTrue(detail(3)!!.staging.first().anulada, "la fila anulada llega al detalle")
        assertNull(detail(1500)?.resumen, "faltan filas (max-rows): no se afirma nada")
    }

    // -----------------------------------------------------------------
    // Resultado de publicar
    // -----------------------------------------------------------------

    @Test
    fun thePublishResultCarriesTheAnnulledCount() {
        fun publish(rpcBody: String) = summaryGateway(CopyOnWriteArrayList()) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_import_lote_preguntas") -> V1StubResponse(200, rpcBody)
                uri.contains("/rest/v1/rpc/admin_import_lote_duplicados") -> V1StubResponse(404, """{"code":"PGRST202","message":"x"}""")
                uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRow(loteId)}]")
                uri.contains("/rest/v1/auditoria_log") -> V1StubResponse(201, "")
                else -> V1StubResponse(200, "[]")
            }
        }.importarLote(loteId).resultado

        val conAnuladas = publish(
            """{"ejecutado":true,"nuevas":3,"vinculadas":1,"omitidas":0,"total_importadas":4,"anuladas":2}""",
        )
        assertEquals(2, conAnuladas.anuladas)
        assertEquals(3, conAnuladas.nuevas)
        // Una RPC anterior a M10 no la devuelve: 0, no un fallo de decodificación.
        assertEquals(0, publish("""{"ejecutado":true,"nuevas":1,"vinculadas":0,"omitidas":0,"total_importadas":1}""").anuladas)
        assertTrue(Json.encodeToString(AdminImportResultadoDto.serializer(), conAnuladas).contains("\"anuladas\":2"))
    }

    // -----------------------------------------------------------------
    // Relaciones pregunta <-> examen en las lecturas
    // -----------------------------------------------------------------

    @Test
    fun theQuestionsOfAnExamCarryTheirRelationAnnulment() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = summaryGateway(upstream) { uri ->
            when {
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
                    200,
                    """[{"examen_id":"$examenId","pregunta_id":"$preguntaId","posicion":1,"numero_en_examen":1,""" +
                        """"anulada":true,"motivo_anulacion":"Anulada por el tribunal"},""" +
                        """{"examen_id":"$examenId","pregunta_id":"$preguntaId2","posicion":2,"numero_en_examen":2}]""",
                )
                uri.contains("/rest/v1/preguntas?") -> V1StubResponse(200, "[${preguntaRow(preguntaId)},${preguntaRow(preguntaId2)}]")
                else -> V1StubResponse(200, "[]")
            }
        }

        val preguntas = gateway.listPreguntasExamen(examenId)

        assertEquals(listOf(true, false), preguntas.map { it.anulada })
        assertEquals(listOf("Anulada por el tribunal", null), preguntas.map { it.motivoAnulacion })
        assertTrue(Json.encodeToString(AdminPreguntaDto.serializer(), preguntas[1]).contains("\"anulada\":false"))
    }

    @Test
    fun theExamsOfAQuestionCarryTheRelationAnnulmentPerExam() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = summaryGateway(upstream) { uri ->
            when {
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
                    200,
                    """[{"posicion":4,"anulada":true,"motivo_anulacion":"Tribunal","examenes":{"id":"$examenId","titulo":"Oficial 2025","activo":true}},""" +
                        """{"posicion":9,"examenes":{"id":"$examenId2","titulo":"Simulacro 2026","activo":true}}]""",
                )
                else -> V1StubResponse(200, "[]")
            }
        }

        val vinculos = gateway.listExamenesDePregunta(preguntaId)

        assertEquals(listOf(true, false), vinculos.map { it.anulada }, "la misma pregunta: anulada en uno y normal en otro")
        assertEquals(listOf("Tribunal", null), vinculos.map { it.motivoAnulacion })
        assertTrue(upstream.single().uri().rawQuery.startsWith("select=*,examenes("), upstream.single().uri().rawQuery)
    }

    // -----------------------------------------------------------------
    // Llamadores manuales de las RPC: no envían la anulación salvo que se pida
    // -----------------------------------------------------------------

    @Test
    fun manualCreateAndLinkNeverSendAnnulmentParameters() {
        val upstream = CopyOnWriteArrayList<HttpRequest>()
        val gateway = summaryGateway(upstream) { uri ->
            when {
                uri.contains("/rest/v1/rpc/admin_create_question") ->
                    V1StubResponse(200, """{"pregunta_id":"$preguntaId","codigo_pregunta":"PREG-000001","creada":true,"posicion_asignada":1}""")
                uri.contains("/rest/v1/rpc/admin_link_question_to_exam") ->
                    V1StubResponse(200, """[{"posicion_asignada":1,"numero_asignado":1,"ya_vinculada":false}]""")
                uri.contains("/rest/v1/preguntas") -> V1StubResponse(200, "[${preguntaRow(preguntaId)}]")
                else -> V1StubResponse(200, "[]")
            }
        }

        gateway.createPregunta(
            AdminPreguntaDto(
                id = preguntaId,
                titulo = "T",
                examenId = examenId,
                opciones = listOf(
                    AdminOpcionDto(letra = "A", texto = "a", esCorrecta = true),
                    AdminOpcionDto(letra = "B", texto = "b"),
                    AdminOpcionDto(letra = "C", texto = "c"),
                    AdminOpcionDto(letra = "D", texto = "d"),
                ),
                // Aunque el DTO de lectura tuviera anulada=true, el alta no la manda: la anulación
                // se decide en la importación / vinculación explícita, no editando la pregunta.
                anulada = true,
                motivoAnulacion = "no debe viajar",
            ),
        )
        gateway.vincularPreguntaExamen(examenId, preguntaId, null)

        val create = readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_create_question") })
        val link = readRequestBody(upstream.single { it.uri().rawPath.endsWith("/rpc/admin_link_question_to_exam") })
        listOf(create, link).forEach { body ->
            assertFalse(body.contains("p_anulada"), body)
            assertFalse(body.contains("p_motivo_anulacion"), body)
        }
        assertTrue(create.contains("\"p_examen_id\":\"$examenId\""), "la creación con examen sigue vinculando: $create")
        assertTrue(link.contains("\"p_examen_id\"") && link.contains("\"p_pregunta_id\""), link)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private val preguntaId2 = "77777777-7777-4777-8777-888888888888"
    private val examenId2 = "22222222-2222-4222-8222-999999999999"

    private fun preguntaRow(id: String) =
        """{"id":"$id","codigo_pregunta":"PREG-1","tipo_pregunta":"rafaga","titulo":"T","activa":true}"""

    private fun loteRow(id: String, totalDetectadas: Int = 1) =
        """{"id":"$id","examen_id":"$examenId","nombre_archivo_original":"t.pdf","tipo_examen":"rafaga",""" +
            """"estado":"parseado","total_detectadas":$totalDetectadas,"total_importadas":0}"""

    private fun stagingStates(vararg filas: Pair<String, String>): String =
        filas.joinToString(",", "[", "]") { (lote, estado) -> """{"lote_id":"$lote","estado_parseo":"$estado"}""" }

    private fun stagingRowJson(estado: String, anulada: Boolean = false): String =
        """{"id":"$filaId","lote_id":"$loteId","posicion":1,"bloque_original":"crudo","titulo":"Plazo",""" +
            """"opcion_a":"a","opcion_b":"b","opcion_c":"c","opcion_d":"d","respuesta_correcta":"B",""" +
            """"razonamiento":"","fuente_texto":"","etiqueta_texto":"","estado_parseo":"$estado",""" +
            """"anulada":$anulada,"motivo_anulacion":${if (anulada) "\"Anulada por el tribunal\"" else "null"}}"""

    private fun examenRowJson() =
        """{"id":"$examenId","codigo_examen":"EX-1","especialidad_id":"$especialidadId","titulo":"Examen",""" +
            """"tipo_examen":"rafaga","tipo_acceso":"gratuito","activo":true}"""

    private fun stagingPatch(upstream: List<HttpRequest>): String = readRequestBody(
        assertNotNull(
            upstream.firstOrNull { it.method() == "PATCH" && it.uri().rawPath.endsWith("/preguntas_parseadas_staging") },
            "no salió el PATCH: " + upstream.describe(),
        ),
    )

    private fun gateway(upstream: MutableList<HttpRequest>): AdminV1Gateway = summaryGateway(upstream) { uri ->
        when {
            uri.contains("/rest/v1/rpc/admin_import_lote_duplicados") -> V1StubResponse(404, """{"code":"PGRST202","message":"x"}""")
            uri.contains("/rest/v1/lotes_importacion_preguntas") -> V1StubResponse(200, "[${loteRow(loteId)}]")
            uri.contains("/rest/v1/preguntas_parseadas_staging") ->
                if (upstream.lastOrNull()?.method() == "PATCH") {
                    V1StubResponse(200, "[${stagingRowJson("ok", anulada = true)}]")
                } else {
                    V1StubResponse(if (upstream.lastOrNull()?.method() == "POST") 201 else 200, if (upstream.lastOrNull()?.method() == "POST") "" else "[]")
                }
            uri.contains("/rest/v1/examenes") -> V1StubResponse(200, "[${examenRowJson()}]")
            else -> V1StubResponse(200, "[]")
        }
    }

    private fun summaryGateway(
        upstream: MutableList<HttpRequest>,
        responder: (String) -> V1StubResponse,
    ): AdminV1Gateway = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ upstream += it }, responder),
    )

    private fun List<HttpRequest>.describe(): String =
        "(${size}) " + joinToString(", ") { "${it.method()} ${it.uri().rawPath}?${it.uri().rawQuery.orEmpty()}" }
}
