package com.oposicontrol.backend

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminControlOfficialQuestionsTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private val q1 = "55555555-5555-4555-8555-555555555551"
    private val q2 = "55555555-5555-4555-8555-555555555552"

    @Test
    fun aReserveNeedsAnOrderAndAMainQuestionMustNotHaveOne() {
        assertEquals(2, OfficialQuestionFlags.parse(obj("""{"anulada":false,"esReserva":true,"ordenReserva":2}""")).ordenReserva)
        assertNull(OfficialQuestionFlags.parse(obj("""{"anulada":false,"esReserva":true}""")).ordenReserva)
        listOf("0", "11", "-1").forEach {
            assertEquals("RESERVE_ORDER_INVALID", assertFailsWith<BackendDomainException>(it) {
                OfficialQuestionFlags.parse(obj("""{"anulada":false,"esReserva":true,"ordenReserva":$it}"""))
            }.errorCode)
        }
        assertEquals("RESERVE_ORDER_INVALID", assertFailsWith<BackendDomainException> {
            OfficialQuestionFlags.parse(obj("""{"anulada":false,"esReserva":false,"ordenReserva":1}"""))
        }.errorCode)
    }

    @Test
    fun theAnnulmentReasonOnlyMakesSenseWhenAnnulled() {
        assertEquals("ANNULMENT_REASON_WITHOUT_ANNULMENT", assertFailsWith<BackendDomainException> {
            OfficialQuestionFlags.parse(obj("""{"anulada":false,"esReserva":false,"motivoAnulacion":"porque si"}"""))
        }.errorCode)
        val ok = OfficialQuestionFlags.parse(obj("""{"anulada":true,"esReserva":false,"motivoAnulacion":"  Anulada por el tribunal "}"""))
        assertEquals("Anulada por el tribunal", ok.motivo)
        assertNull(OfficialQuestionFlags.parse(obj("""{"anulada":true,"esReserva":false,"motivoAnulacion":""}""")).motivo)
    }

    @Test
    fun theReasonIsCappedAtFiveHundredCodePoints() {
        val long = OfficialQuestionFlags.parse(obj("""{"anulada":true,"esReserva":false,"motivoAnulacion":"${"m".repeat(900)}"}"""))
        assertEquals(500, long.motivo!!.length)
    }

    @Test
    fun unknownKeysMissingKeysAndWrongTypesAreBadRequests() {
        assertFailsWith<BadRequestException> { OfficialQuestionFlags.parse(obj("""{"anulada":false,"esReserva":false,"posicion":3}""")) }
        assertFailsWith<BadRequestException> { OfficialQuestionFlags.parse(obj("""{"anulada":false}""")) }
        assertFailsWith<BadRequestException> { OfficialQuestionFlags.parse(obj("""{"anulada":"si","esReserva":false}""")) }
        assertFailsWith<BadRequestException> { OfficialQuestionFlags.parse(obj("""{"anulada":false,"esReserva":true,"ordenReserva":"1"}""")) }
    }

    private fun fake(withReserveColumns: Boolean = true) = ControlFake { method, uri, _ ->
        when {
            uri.contains("/rpc/admin_official_rules_check") -> V1StubResponse(200, """{"problema":null,"avisos":[],"regla":"REEMPLAZO_POR_RESERVA"}""")
            uri.contains("/rpc/admin_set_exam_question_flags") -> V1StubResponse(200, """{"changed":true,"problema":null,"avisos":["MORE_RESERVES_THAN_MAX","<b>x</b>"]}""")
            uri.contains("/rest/v1/preguntas_examen") ->
                if (uri.contains("es_reserva") && !withReserveColumns) V1StubResponse(400, """{"code":"42703","message":"column preguntas_examen.es_reserva does not exist"}""")
                else V1StubResponse(
                    200,
                    """[{"pregunta_id":"$q1","posicion":1,"anulada":true,"motivo_anulacion":"Tribunal","es_reserva":false,"orden_reserva":null,"es_demo":false,"preguntas":{"titulo":"<b>Plazo</b>‮"}},""" +
                        """{"pregunta_id":"$q2","posicion":2,"anulada":false,"motivo_anulacion":null,"es_reserva":true,"orden_reserva":1,"es_demo":false,"preguntas":{"titulo":"Reserva"}}]""",
                )
            uri.contains("/rest/v1/examenes") -> V1StubResponse(200, """[{"id":"${ControlFake.EXAM}"}]""")
            else -> V1StubResponse(200, "[]")
        }
    }

    @Test
    fun readingListsFlagsSanitizesTitlesAndIncludesTheServerCheck() {
        val dto = fake().gateway().officialQuestions(ControlFake.EXAM)!!
        assertTrue(dto.reservasDisponibles)
        assertEquals(2, dto.preguntas.size)
        assertTrue(dto.preguntas[0].anulada)
        assertEquals(1, dto.preguntas[1].ordenReserva)
        assertEquals("<b>Plazo</b>", dto.preguntas[0].titulo)
        assertEquals("REEMPLAZO_POR_RESERVA", dto.chequeo?.regla)
    }

    @Test
    fun anOldServerWithoutReserveColumnsStillListsTheAnnulledOnes() {
        val f = fake(withReserveColumns = false)
        val dto = f.gateway().officialQuestions(ControlFake.EXAM)!!
        assertFalse(dto.reservasDisponibles)
        assertTrue(dto.preguntas[0].anulada)
        assertNull(dto.chequeo)
    }

    @Test
    fun anUnknownExamIs404() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, "[]") }
        assertNull(f.gateway().officialQuestions(ControlFake.EXAM))
    }

    @Test
    fun writingCallsTheOneFunctionWithTheJwtActorAndNeverPatchesTheTable() {
        val f = fake()
        f.gateway().setOfficialQuestionFlags(ControlFake.EXAM, q2, OfficialQuestionFlags.Input(false, null, true, 2))
        val call = f.calls.first { it.pathAndQuery.contains("/rpc/admin_set_exam_question_flags") }
        val body = obj(call.body)
        assertEquals(ControlFake.ACTOR, body["p_actor"].contentOrNullSafe())
        assertEquals("2", body["p_orden_reserva"].contentOrNullSafe())
        assertEquals("true", body["p_es_reserva"].contentOrNullSafe())
        assertTrue(f.writes().none { it.method == "PATCH" || it.method == "DELETE" }, f.describe())
    }

    @Test
    fun anUnprovisionedFunctionIsAnHonest501AndNothingIsWritten() = testApplication {
        val f = ControlFake { _, uri, _ ->
            if (uri.contains("/rpc/")) V1StubResponse(404, """{"code":"PGRST202"}""") else V1StubResponse(200, """[{"id":"${ControlFake.EXAM}"}]""")
        }
        installControlRoutes(f.gateway())
        val r = sendJson(HttpMethod.Put, "/api/v1/admin/examenes/${ControlFake.EXAM}/preguntas-oficiales/$q1", """{"anulada":true,"esReserva":false}""")
        assertEquals(HttpStatusCode.NotImplemented, r.status)
    }

    @Test
    fun theRoutesValidateIdsAndBodiesBeforeAnyRequest() = testApplication {
        val f = ControlFake()
        installControlRoutes(f.gateway())
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Get, "/api/v1/admin/examenes/no-uuid/preguntas-oficiales").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Put, "/api/v1/admin/examenes/${ControlFake.EXAM}/preguntas-oficiales/xx", """{"anulada":true,"esReserva":false}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Put, "/api/v1/admin/examenes/${ControlFake.EXAM}/preguntas-oficiales/$q1", """{"anulada":false,"esReserva":true,"ordenReserva":99}""").status)
        assertTrue(f.calls.isEmpty(), f.describe())
    }

    @Test
    fun theFunctionResultCarriesNonBlockingWarningsBack() {
        val dto = fake().gateway().setOfficialQuestionFlags(ControlFake.EXAM, q2, OfficialQuestionFlags.Input(false, null, true, null))!!
        assertEquals(listOf("MORE_RESERVES_THAN_MAX", "<b>x</b>"), dto.avisos)
        assertNull(dto.problema)
    }

    @Test
    fun appendingAReserveSendsANullOrder() {
        val f = fake()
        f.gateway().setOfficialQuestionFlags(ControlFake.EXAM, q2, OfficialQuestionFlags.Input(false, null, true, null))
        val body = obj(f.calls.first { it.pathAndQuery.contains("/rpc/admin_set_exam_question_flags") }.body)
        assertEquals(JsonNull, body["p_orden_reserva"])
    }

    @Test
    fun theServerDomainErrorsOfTheFunctionAreTranslatedWithConstantMessages() = testApplication {
        val f = ControlFake { _, uri, _ ->
            if (uri.contains("/rpc/admin_set_exam_question_flags")) {
                V1StubResponse(400, """{"code":"P0001","message":"FLAGS_OFFICIAL_RULES_INVALID","hint":"REEMPLAZO_NOT_ENOUGH_RESERVES","details":"examen secreto"}""")
            } else V1StubResponse(200, """[{"id":"${ControlFake.EXAM}"}]""")
        }
        installControlRoutes(f.gateway())
        val r = sendJson(HttpMethod.Put, "/api/v1/admin/examenes/${ControlFake.EXAM}/preguntas-oficiales/$q1", """{"anulada":true,"esReserva":false}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        assertTrue(r.bodyAsText().contains("FLAGS_OFFICIAL_RULES_INVALID") && !r.bodyAsText().contains("secreto"), r.bodyAsText())
    }
}
