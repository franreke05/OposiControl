package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pregunta ANULADA en el examen (M10), lado consumo: qué sirve la app, con cuántas preguntas nace
 * el intento, cómo se responde una anulada y cómo se cierra el intento.
 *
 * Contrato con la app móvil externa (ver el KDoc de `examenDetalle`, `responder` y `finalizar`):
 * la anulada se sirve en su posición con `anulada = true`, no puntúa, y los totales del intento
 * ya vienen SIN ella.
 */
class AppV1AnnulledQuestionsTest {

    private val usuarioId = "77777777-7777-4777-8777-777777777777"
    private val examenId = "88888888-8888-4888-8888-888888888888"
    private val intentoId = "99999999-9999-4999-8999-999999999999"
    private val p1 = "aaaaaaaa-aaaa-4aaa-8aaa-000000000001"
    private val p2 = "aaaaaaaa-aaaa-4aaa-8aaa-000000000002"
    private val p3 = "aaaaaaaa-aaaa-4aaa-8aaa-000000000003"
    private val p4 = "aaaaaaaa-aaaa-4aaa-8aaa-000000000004"
    private val opcion = "bbbbbbbb-bbbb-4bbb-8bbb-000000000001"
    private val correcta = "bbbbbbbb-bbbb-4bbb-8bbb-000000000002"

    // -----------------------------------------------------------------
    // Entrega
    // -----------------------------------------------------------------

    @Test
    fun theExamServesEveryActiveQuestionInPlaceWithItsAnnulmentAndTheEvaluableTotal() {
        val seen = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(record = { seen += it.uri().toString() }) { uri ->
            when {
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
                    200,
                    """[{"pregunta_id":"$p1","posicion":1,"anulada":false},""" +
                        """{"pregunta_id":"$p2","posicion":2,"anulada":true,"motivo_anulacion":"Anulada por el tribunal"},""" +
                        """{"pregunta_id":"$p3","posicion":3},""" +
                        """{"pregunta_id":"$p4","posicion":4,"anulada":false}]""",
                )
                // p4 está archivada: no se sirve ni cuenta.
                uri.contains("/rest/v1/preguntas?") -> V1StubResponse(
                    200,
                    """[{"id":"$p1","titulo":"Uno"},{"id":"$p2","titulo":"Dos"},{"id":"$p3","titulo":"Tres"}]""",
                )
                uri.contains("/rest/v1/opciones_pregunta") -> V1StubResponse(
                    200,
                    """[{"id":"$opcion","pregunta_id":"$p2","letra":"A","texto_opcion":"a"}]""",
                )
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, """[{"id":"$examenId","titulo":"Oficial","total_preguntas":3}]""")
                else -> V1StubResponse(200, "[]")
            }
        }

        val detalle = assertNotNullDetalle(gateway.examenDetalle(examenId))

        assertEquals(listOf(p1, p2, p3), detalle.preguntas.map { it.id }, "todas las activas, en su posición original")
        assertEquals(listOf(1, 2, 3), detalle.preguntas.map { it.posicion })
        assertEquals(listOf(false, true, false), detalle.preguntas.map { it.anulada })
        assertEquals(listOf(null, "Anulada por el tribunal", null), detalle.preguntas.map { it.motivoAnulacion })
        assertEquals(2, detalle.totalEvaluables, "servidas activas y NO anuladas")
        assertEquals(3, detalle.examen.totalPreguntas, "totalPreguntas no cambia de significado: posiciones activas")
        assertTrue(
            seen.any { it.contains("/rest/v1/preguntas_examen?select=*&examen_id=eq.$examenId") },
            "se lee select=* (compatible con una base sin M10): $seen",
        )
    }

    @Test
    fun theDeliveryDtosAlwaysEncodeTheNewFieldsEvenWhenTheyAreDefaults() {
        val json = Json { encodeDefaults = false }
        val pregunta = json.encodeToString(AppPreguntaDto.serializer(), AppPreguntaDto("p", "t", 1, emptyList()))
        val respuesta = json.encodeToString(ResponderResponse.serializer(), ResponderResponse(esCorrecta = true, opcionCorrectaId = "o"))
        val detalle = json.encodeToString(
            AppExamenDetalleResponse.serializer(),
            AppExamenDetalleResponse(AppExamenDto("e", "t"), emptyList()),
        )

        assertTrue(pregunta.contains("\"anulada\":false"), pregunta)
        assertTrue(respuesta.contains("\"anulada\":false"), respuesta)
        assertTrue(detalle.contains("\"totalEvaluables\":0"), detalle)
    }

    // -----------------------------------------------------------------
    // iniciar
    // -----------------------------------------------------------------

    /** P1, P2 (anulada), P3, P4 (archivada): el intento nace con DOS evaluables (P1 y P3). */
    @Test
    fun anAttemptIsSizedWithTheEvaluableQuestionsOnly() {
        val seen = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(record = { seen += it.uri().toString(); bodies += readRequestBody(it) }) { uri ->
            when {
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(
                    200,
                    """[{"pregunta_id":"$p1"},{"pregunta_id":"$p2","anulada":true},{"pregunta_id":"$p3"},{"pregunta_id":"$p4"}]""",
                )
                // Devuelve sólo las activas de las que se piden: p4 está archivada.
                uri.contains("/rest/v1/preguntas?") -> {
                    val pedidas = Regex("in\\.\\(([^)]*)\\)").find(uri)!!.groupValues[1].split(",")
                    V1StubResponse(200, pedidas.filter { it != p4 }.joinToString(",", "[", "]") { """{"id":"$it"}""" })
                }
                uri.contains("/rest/v1/intentos_examen") -> V1StubResponse(200, "[${intentoJson(totalPreguntas = 2)}]")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, """[{"id":"$examenId"}]""")
                else -> V1StubResponse(200, "[]")
            }
        }

        val intento = gateway.iniciar(usuarioId, examenId)

        val pedidas = seen.first { it.contains("/rest/v1/preguntas?") }
        assertFalse(pedidas.contains(p2), "la anulada no entra en el recuento: $pedidas")
        assertTrue(pedidas.contains("activa=eq.true"), pedidas)
        assertTrue(bodies.any { it.contains("\"total_preguntas\":2") }, "total_preguntas = evaluables (2 de 4): $bodies")
        assertEquals(2, intento.totalPreguntas)
    }

    @Test
    fun anExamWhoseQuestionsAreAllAnnulledSizesToZeroWithoutAskingForQuestions() {
        val seen = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = gatewayWith(record = { seen += it.uri().toString(); bodies += readRequestBody(it) }) { uri ->
            when {
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, """[{"pregunta_id":"$p1","anulada":true}]""")
                uri.contains("/rest/v1/intentos_examen") -> V1StubResponse(200, "[${intentoJson(totalPreguntas = 0)}]")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, """[{"id":"$examenId"}]""")
                else -> V1StubResponse(200, "[]")
            }
        }

        gateway.iniciar(usuarioId, examenId)

        assertTrue(bodies.any { it.contains("\"total_preguntas\":0") }, "$bodies")
        assertFalse(seen.any { it.contains("/rest/v1/preguntas?") }, "$seen")
    }

    // -----------------------------------------------------------------
    // responder
    // -----------------------------------------------------------------

    @Test
    fun answeringAnAnnulledQuestionReturnsAnuladaWithoutErrorAndWithTheCorrectOption() {
        val gateway = answerGateway(relation = """{"pregunta_id":"$p2","anulada":true}""", rpc = recordAnswer(esCorrecta = "null"))

        val respuesta = gateway.responder(usuarioId, intentoId, ResponderRequest(p2, opcion))

        assertTrue(respuesta.anulada)
        assertFalse(respuesta.esCorrecta, "una anulada no es acierto")
        assertEquals(correcta, respuesta.opcionCorrectaId)
    }

    /** `record_answer` devolviendo es_correcta NULL es, por sí solo, la señal de anulada. */
    @Test
    fun aNullCorrectnessFromTheRpcMeansAnnulledEvenIfTheRelationReadSaysNothing() {
        val gateway = answerGateway(relation = """{"pregunta_id":"$p2"}""", rpc = recordAnswer(esCorrecta = "null"))

        val respuesta = gateway.responder(usuarioId, intentoId, ResponderRequest(p2, opcion))

        assertTrue(respuesta.anulada)
        assertFalse(respuesta.esCorrecta)
    }

    /** Una RPC anterior a M10 sí puntuaría: la relación anulada manda y nunca se enseña «acierto». */
    @Test
    fun theRelationWinsOverALegacyRpcThatScoredTheAnswer() {
        val gateway = answerGateway(relation = """{"pregunta_id":"$p2","anulada":true}""", rpc = recordAnswer(esCorrecta = "true"))

        val respuesta = gateway.responder(usuarioId, intentoId, ResponderRequest(p2, opcion))

        assertTrue(respuesta.anulada)
        assertFalse(respuesta.esCorrecta)
    }

    @Test
    fun aNormalAnswerKeepsItsCorrectnessAndIsNotAnnulled() {
        val acierto = answerGateway(relation = """{"pregunta_id":"$p1"}""", rpc = recordAnswer(esCorrecta = "true"))
            .responder(usuarioId, intentoId, ResponderRequest(p1, opcion))
        assertTrue(acierto.esCorrecta)
        assertFalse(acierto.anulada)

        val fallo = answerGateway(relation = """{"pregunta_id":"$p1","anulada":false}""", rpc = recordAnswer(esCorrecta = "false"))
            .responder(usuarioId, intentoId, ResponderRequest(p1, opcion))
        assertFalse(fallo.esCorrecta)
        assertFalse(fallo.anulada, "un fallo normal NO es una anulada")
    }

    @Test
    fun theExistingAnswerValidationsStillHold() {
        val seen = CopyOnWriteArrayList<HttpRequest>()
        val sinRelacion = answerGateway(relation = null, rpc = recordAnswer("true"), record = { seen += it })

        assertFailsWith<IllegalArgumentException> { sinRelacion.responder(usuarioId, intentoId, ResponderRequest(p1, opcion)) }
        assertTrue(seen.none { it.uri().rawPath.contains("record_answer") }, "no se llama a la RPC con una pregunta ajena")

        val finalizado = answerGateway(relation = """{"pregunta_id":"$p1"}""", rpc = recordAnswer("true"), estado = "finalizado")
        assertFailsWith<IllegalArgumentException> { finalizado.responder(usuarioId, intentoId, ResponderRequest(p1, opcion)) }
    }

    // -----------------------------------------------------------------
    // finalizar
    // -----------------------------------------------------------------

    /**
     * Respuestas guardadas: P1 acierto, P2 acierto (pero P2 se anuló DESPUÉS), P3 fallo. La anulada
     * ni suma ni resta: 2 respondidas, 1 correcta, 1 fallada, 50 %, puntuación 1.
     */
    @Test
    fun finishingIgnoresAnswersToQuestionsAnnulledAfterTheyWereAnswered() {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = finishGateway(
            asociaciones = """[{"pregunta_id":"$p1"},{"pregunta_id":"$p2","anulada":true},{"pregunta_id":"$p3"}]""",
            respuestas = """[{"pregunta_id":"$p1","es_correcta":true},{"pregunta_id":"$p2","es_correcta":true},{"pregunta_id":"$p3","es_correcta":false}]""",
            evaluablesActivas = listOf(p1, p3),
            record = { bodies += readRequestBody(it) },
        )

        gateway.finalizar(usuarioId, intentoId)

        val patch = bodies.first { it.contains("\"estado\":\"finalizado\"") }
        assertTrue(patch.contains("\"total_respondidas\":2"), patch)
        assertTrue(patch.contains("\"total_correctas\":1"), patch)
        assertTrue(patch.contains("\"total_falladas\":1"), patch)
        assertTrue(patch.contains("\"porcentaje_acierto\":50.0"), patch)
        assertTrue(patch.contains("\"puntuacion\":1"), patch)
        assertTrue(patch.contains("\"total_sin_responder\":0"), "2 evaluables − 2 respondidas: $patch")
    }

    @Test
    fun unansweredEvaluableQuestionsAreCountedButAnnulledOnesAreNot() {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = finishGateway(
            // P1, P3 y P4 evaluables; P2 anulada. Sólo se respondió P1.
            asociaciones = """[{"pregunta_id":"$p1"},{"pregunta_id":"$p2","anulada":true},{"pregunta_id":"$p3"},{"pregunta_id":"$p4"}]""",
            respuestas = """[{"pregunta_id":"$p1","es_correcta":true}]""",
            evaluablesActivas = listOf(p1, p3, p4),
            record = { bodies += readRequestBody(it) },
        )

        gateway.finalizar(usuarioId, intentoId)

        val patch = bodies.first { it.contains("\"estado\":\"finalizado\"") }
        assertTrue(patch.contains("\"total_respondidas\":1"), patch)
        assertTrue(patch.contains("\"total_sin_responder\":2"), "P3 y P4 sin responder; la anulada no cuenta: $patch")
        assertTrue(patch.contains("\"porcentaje_acierto\":100.0"), patch)
    }

    /** Sin anuladas ni respuestas: 0 %, sin dividir por cero, y sin_responder no baja de 0. */
    @Test
    fun anEmptyAttemptDoesNotDivideByZeroAndNeverGoesNegative() {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = finishGateway(
            asociaciones = """[{"pregunta_id":"$p1","anulada":true}]""",
            respuestas = """[{"pregunta_id":"$p1","es_correcta":true}]""",
            evaluablesActivas = emptyList(),
            record = { bodies += readRequestBody(it) },
        )

        gateway.finalizar(usuarioId, intentoId)

        val patch = bodies.first { it.contains("\"estado\":\"finalizado\"") }
        assertTrue(patch.contains("\"total_respondidas\":0"), patch)
        assertTrue(patch.contains("\"porcentaje_acierto\":0.0"), patch)
        assertTrue(patch.contains("\"total_sin_responder\":0"), patch)
    }

    @Test
    fun theFinishFormulaIsUnchangedForAnExamWithoutAnnulments() {
        val bodies = CopyOnWriteArrayList<String>()
        val gateway = finishGateway(
            asociaciones = """[{"pregunta_id":"$p1"},{"pregunta_id":"$p3"}]""",
            respuestas = """[{"pregunta_id":"$p1","es_correcta":true},{"pregunta_id":"$p3","es_correcta":true}]""",
            evaluablesActivas = listOf(p1, p3),
            record = { bodies += readRequestBody(it) },
        )

        gateway.finalizar(usuarioId, intentoId)

        val patch = bodies.first { it.contains("\"estado\":\"finalizado\"") }
        assertTrue(patch.contains("\"total_respondidas\":2") && patch.contains("\"total_correctas\":2"), patch)
        assertTrue(patch.contains("\"total_falladas\":0") && patch.contains("\"puntuacion\":2"), patch)
    }

    // -----------------------------------------------------------------
    // Andamiaje
    // -----------------------------------------------------------------

    private fun assertNotNullDetalle(d: AppExamenDetalleResponse?): AppExamenDetalleResponse =
        checkNotNull(d) { "el examen no se sirvió" }

    private fun recordAnswer(esCorrecta: String) =
        """[{"respuesta_id":null,"es_correcta":$esCorrecta,"opcion_correcta_id":"$correcta",""" +
            """"es_primer_intento_pregunta":false,"cuenta_para_ranking":false}]"""

    private fun intentoJson(totalPreguntas: Int = 0, estado: String = "en_progreso") =
        """{"id":"$intentoId","examen_id":"$examenId","estado":"$estado","total_preguntas":$totalPreguntas,""" +
            """"total_respondidas":0,"total_correctas":0,"porcentaje_acierto":0.0}"""

    private fun answerGateway(
        relation: String?,
        rpc: String,
        estado: String = "en_progreso",
        record: (HttpRequest) -> Unit = {},
    ) = gatewayWith(record) { uri ->
        when {
            uri.contains("/rest/v1/rpc/record_answer") -> V1StubResponse(200, rpc)
            uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, relation?.let { "[$it]" } ?: "[]")
            uri.contains("/rest/v1/opciones_pregunta") -> V1StubResponse(200, """[{"id":"$opcion"}]""")
            uri.contains("/rest/v1/intentos_examen") -> V1StubResponse(200, "[${intentoJson(estado = estado)}]")
            else -> V1StubResponse(200, "[]")
        }
    }

    private fun finishGateway(
        asociaciones: String,
        respuestas: String,
        evaluablesActivas: List<String>,
        record: (HttpRequest) -> Unit,
    ) = gatewayWith(record) { uri ->
        when {
            uri.contains("/rest/v1/respuestas_intento") -> V1StubResponse(200, respuestas)
            uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, asociaciones)
            uri.contains("/rest/v1/preguntas?") -> V1StubResponse(
                200,
                evaluablesActivas.joinToString(",", "[", "]") { """{"id":"$it"}""" },
            )
            uri.contains("/rest/v1/intentos_examen") -> V1StubResponse(200, "[${intentoJson()}]")
            else -> V1StubResponse(200, "[]")
        }
    }

    private fun gatewayWith(
        record: (HttpRequest) -> Unit = {},
        responder: (String) -> V1StubResponse,
    ): AppV1Gateway = AppV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient(record, responder),
    )
}
