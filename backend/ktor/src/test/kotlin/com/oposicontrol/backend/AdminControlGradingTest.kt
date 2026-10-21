package com.oposicontrol.backend

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminControlGradingTest {
    private fun body(vararg pairs: Pair<String, String>): JsonObject =
        Json.parseToJsonElement("{" + pairs.joinToString(",") { "\"${it.first}\":${it.second}" } + "}").jsonObject

    private val valid = arrayOf(
        "reglaAnuladas" to "null",
        "penalizacionRacional" to "false",
        "penalizacionNum" to "0",
        "penalizacionDen" to "1",
    )

    @Test
    fun aValidBodyParsesAndNullRuleMeansDerived() {
        val input = GradingRules.parse(body(*valid))
        assertNull(input.reglaAnuladas)
        assertEquals(false, input.penalizacionRacional)
    }

    @Test
    fun racionalAndAFixedFractionAreMutuallyExclusive() {
        val error = assertFailsWith<BackendDomainException> {
            GradingRules.parse(body("reglaAnuladas" to "null", "penalizacionRacional" to "true", "penalizacionNum" to "1", "penalizacionDen" to "3"))
        }
        assertEquals("GRADING_PENALTY_CONFLICT", error.errorCode)
        assertEquals(HttpStatusCode.UnprocessableEntity, error.status)
    }

    @Test
    fun racionalWithZeroOverOneIsAccepted() {
        val input = GradingRules.parse(body("reglaAnuladas" to "\"NO_PUNTUA\"", "penalizacionRacional" to "true", "penalizacionNum" to "0", "penalizacionDen" to "1"))
        assertTrue(input.penalizacionRacional)
        assertEquals("NO_PUNTUA", input.reglaAnuladas)
    }

    @Test
    fun racionalWithADenominatorOtherThanOneIsRejected() {
        val error = assertFailsWith<BackendDomainException> {
            GradingRules.parse(body("reglaAnuladas" to "null", "penalizacionRacional" to "true", "penalizacionNum" to "0", "penalizacionDen" to "3"))
        }
        assertEquals("GRADING_FRACTION_INVALID", error.errorCode)
    }

    @Test
    fun fractionBoundsAreEnforced() {
        listOf(0 to 0, 4 to 3, -1 to 3, 1 to 101, 1 to -2).forEach { (num, den) ->
            val error = assertFailsWith<BackendDomainException>("$num/$den") {
                GradingRules.parse(body("reglaAnuladas" to "null", "penalizacionRacional" to "false", "penalizacionNum" to "$num", "penalizacionDen" to "$den"))
            }
            assertEquals("GRADING_FRACTION_INVALID", error.errorCode, "$num/$den")
        }
        val ok = GradingRules.parse(body("reglaAnuladas" to "null", "penalizacionRacional" to "false", "penalizacionNum" to "1", "penalizacionDen" to "3"))
        assertEquals(1 to 3, ok.penalizacionNum to ok.penalizacionDen)
    }

    @Test
    fun anUnknownRuleOrPuntuaAciertoIsRejected() {
        listOf("PUNTUA_ACIERTO", "reemplazo", "").forEach { regla ->
            if (regla.isEmpty()) {
                // cadena vacia = derivada (como null): lo normaliza el parser
                assertNull(GradingRules.parse(body("reglaAnuladas" to "\"\"", *valid.drop(1).toTypedArray())).reglaAnuladas)
            } else {
                val error = assertFailsWith<BackendDomainException>(regla) {
                    GradingRules.parse(body("reglaAnuladas" to "\"$regla\"", *valid.drop(1).toTypedArray()))
                }
                assertEquals("GRADING_RULE_INVALID", error.errorCode)
            }
        }
    }

    @Test
    fun unknownKeysMissingKeysAndWrongTypesAreBadRequests() {
        assertFailsWith<BadRequestException> { GradingRules.parse(body(*valid, "esOficial" to "true")) }
        assertFailsWith<BadRequestException> { GradingRules.parse(body("reglaAnuladas" to "null", "penalizacionRacional" to "false", "penalizacionNum" to "0")) }
        assertFailsWith<BadRequestException> { GradingRules.parse(body("reglaAnuladas" to "null", "penalizacionRacional" to "\"true\"", "penalizacionNum" to "0", "penalizacionDen" to "1")) }
        assertFailsWith<BadRequestException> { GradingRules.parse(body("penalizacionRacional" to "false", "penalizacionNum" to "0", "penalizacionDen" to "1")) }
    }

    @Test
    fun theReasonIsSanitizedAndCapped() {
        val dirty = "motivo\u202Eoculto\u200B con \u0007 control " + "x".repeat(400)
        val input = GradingRules.parse(body(*valid, "motivo" to "\"${dirty.replace("\u0007", "\\u0007")}\""))
        val motivo = input.motivo!!
        assertTrue(motivo.length <= GradingRules.MOTIVO_MAX)
        assertTrue(motivo.none { it == '\u202E' || it == '\u200B' || it == '\u0007' }, motivo)
    }

    private fun examJson(published: Boolean = false, regla: String? = null, racional: Boolean = false) =
        """[{"id":"${ControlFake.EXAM}","es_oficial":true,"publicado_en":${if (published) "\"2026-10-01T00:00:00Z\"" else "null"},""" +
            """"regla_anuladas":${regla?.let { "\"$it\"" } ?: "null"},"penalizacion_num":0,"penalizacion_den":1,"penalizacion_racional":$racional}]"""

    private fun fake(published: Boolean = false, checkStatus: Int = 200, gradingRpc: Boolean = false) = ControlFake { method, uri, _ ->
        when {
            uri.contains("/rpc/admin_set_exam_grading_rules") ->
                if (gradingRpc) V1StubResponse(200, """{"changed":true,"reglaEfectiva":"NO_PUNTUA","problema":null,"avisos":[]}""")
                else V1StubResponse(404, """{"code":"PGRST202","message":"not found"}""")
            uri.contains("/rpc/admin_official_rules_check") ->
                if (checkStatus == 200) V1StubResponse(200, """{"problema":null,"avisos":["MORE_RESERVES_THAN_MAX"],"regla":"NO_PUNTUA"}""")
                else V1StubResponse(404, """{"code":"PGRST202","message":"not found"}""")
            uri.contains("/rest/v1/preguntas_examen") ->
                V1StubResponse(200, """[{"es_reserva":false,"anulada":true},{"es_reserva":false,"anulada":false},{"es_reserva":true,"anulada":false}]""")
            uri.contains("/rest/v1/examenes") && method == "PATCH" -> V1StubResponse(200, examJson(published, "NO_PUNTUA", true))
            uri.contains("/rest/v1/examenes") -> V1StubResponse(200, examJson(published))
            uri.contains("/rest/v1/auditoria_log") && method == "POST" -> V1StubResponse(201, "")
            else -> V1StubResponse(200, "[]")
        }
    }

    @Test
    fun readingReturnsCountsAndTheServerCheck() {
        val f = fake()
        val rules = f.gateway().getGradingRules(ControlFake.EXAM)!!
        assertEquals(1, rules.reservas)
        assertEquals(1, rules.anuladasPrincipales)
        assertEquals(listOf("MORE_RESERVES_THAN_MAX"), rules.chequeo?.avisos)
    }

    @Test
    fun aMissingCheckFunctionDegradesToNullInsteadOfFailing() {
        val rules = fake(checkStatus = 404).gateway().getGradingRules(ControlFake.EXAM)!!
        assertNull(rules.chequeo)
    }

    @Test
    fun savingSendsOneFourColumnPatchAndAnActorAuditRow() {
        val f = fake()
        val saved = f.gateway().putGradingRules(
            ControlFake.EXAM,
            GradingRules.Input("NO_PUNTUA", true, 0, 1, "Cambio de criterio"),
        )
        assertTrue(saved != null)
        val writes = f.writes()
        val patch = writes.single { it.method == "PATCH" }
        assertTrue(patch.pathAndQuery.contains("id=eq.${ControlFake.EXAM}"), f.describe())
        listOf("\"regla_anuladas\":\"NO_PUNTUA\"", "\"penalizacion_racional\":true", "\"penalizacion_num\":0", "\"penalizacion_den\":1")
            .forEach { assertTrue(patch.body.contains(it), patch.body) }
        val audit = writes.single { it.method == "POST" && it.pathAndQuery.contains("auditoria_log") }
        assertTrue(audit.body.contains(ControlFake.ACTOR), audit.body)
        assertTrue(audit.body.contains("Cambio de criterio"), audit.body)
    }

    @Test
    fun aPublishedExamNeedsAReasonAndNothingIsWrittenWithoutIt() {
        val f = fake(published = true)
        val error = assertFailsWith<BackendDomainException> {
            f.gateway().putGradingRules(ControlFake.EXAM, GradingRules.Input("NO_PUNTUA", false, 0, 1, null))
        }
        assertEquals("GRADING_REASON_REQUIRED", error.errorCode)
        assertTrue(f.writes().isEmpty(), f.describe())
    }

    @Test
    fun noIdentifiablePersonMeansForbiddenBeforeAnyRequest() {
        val f = fake()
        val error = assertFailsWith<BackendDomainException> {
            f.gateway(actor = null).putGradingRules(ControlFake.EXAM, GradingRules.Input(null, false, 0, 1, null))
        }
        assertEquals(HttpStatusCode.Forbidden, error.status)
        assertTrue(f.calls.isEmpty(), f.describe())
    }

    @Test
    fun anUnchangedSaveDoesNotWriteAnAuditRow() {
        val f = ControlFake { method, uri, _ ->
            when {
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, "[]")
                uri.contains("/rpc/") -> V1StubResponse(404, """{"code":"PGRST202"}""")
                uri.contains("/rest/v1/examenes") -> V1StubResponse(200, examJson())
                else -> V1StubResponse(200, "[]")
            }
        }
        f.gateway().putGradingRules(ControlFake.EXAM, GradingRules.Input(null, false, 0, 1, null))
        assertTrue(f.writes().none { it.pathAndQuery.contains("auditoria_log") }, f.describe())
    }

    @Test
    fun auditTrailMergesTheDatabaseRowAndTheActorRow() {
        val f = ControlFake { _, uri, _ ->
            if (uri.contains("auditoria_log")) {
                V1StubResponse(
                    200,
                    """[{"id":"r2","usuario_id":null,"accion":"examen_reglas_puntuacion_motivo","antes":{"actor":"${ControlFake.ACTOR}","publicado":false},"despues":{"motivo":"Por el tribunal"},"creado_en":"2026-10-18T10:00:01Z"},""" +
                        """{"id":"r1","usuario_id":null,"accion":"examen_reglas_puntuacion_modificadas","antes":{"regla_anuladas":null,"penalizacion_num":0,"penalizacion_den":1,"penalizacion_racional":false},"despues":{"regla_anuladas":"NO_PUNTUA","penalizacion_num":0,"penalizacion_den":1,"penalizacion_racional":true},"creado_en":"2026-10-18T10:00:00Z"}]""",
                )
            } else V1StubResponse(200, "[]")
        }
        val trail = f.gateway().gradingAudit(ControlFake.EXAM)
        assertEquals(2, trail.size)
        assertEquals(ControlFake.ACTOR, trail[0].actorId)
        assertEquals("Por el tribunal", trail[0].motivo)
        assertEquals(true, trail[1].despues?.penalizacionRacional)
        val query = f.calls.single().pathAndQuery
        assertTrue(query.contains("registro_id=eq.${ControlFake.EXAM}") && query.contains("tabla_afectada=eq.examenes"), query)
    }

    @Test
    fun theRouteRejectsAnInvalidBodyWith400Or422AndTouchesNothing() = testApplication {
        val f = fake()
        installControlRoutes(f.gateway())

        val unknown = sendJson(HttpMethod.Put, "/api/v1/admin/examenes/${ControlFake.EXAM}/reglas-puntuacion",
            """{"reglaAnuladas":null,"penalizacionRacional":false,"penalizacionNum":0,"penalizacionDen":1,"esOficial":false}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status, unknown.bodyAsText())

        val conflict = sendJson(HttpMethod.Put, "/api/v1/admin/examenes/${ControlFake.EXAM}/reglas-puntuacion",
            """{"reglaAnuladas":null,"penalizacionRacional":true,"penalizacionNum":1,"penalizacionDen":3}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, conflict.status, conflict.bodyAsText())

        val badId = sendJson(HttpMethod.Get, "/api/v1/admin/examenes/no-es-uuid/reglas-puntuacion")
        assertEquals(HttpStatusCode.BadRequest, badId.status, badId.bodyAsText())

        assertTrue(f.calls.isEmpty(), f.describe())
    }

    @Test
    fun theRouteServesRulesAndAuditWithNoStore() = testApplication {
        val f = fake()
        installControlRoutes(f.gateway())
        val response = sendJson(HttpMethod.Get, "/api/v1/admin/examenes/${ControlFake.EXAM}/reglas-puntuacion")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers["Cache-Control"])
        assertTrue(response.bodyAsText().contains("\"esOficial\":true"), response.bodyAsText())
    }

    @Test
    fun theDatabaseRejectionOfInvalidRulesIsA422WithAConstantMessage() = testApplication {
        val f = ControlFake { method, uri, _ ->
            when {
                uri.contains("/rpc/") -> V1StubResponse(404, """{"code":"PGRST202"}""")
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, "[]")
                method == "PATCH" -> V1StubResponse(400, """{"code":"P0001","message":"GRADING_PENALTY_CONFLICT","hint":"pista-interna","details":"examen secreto"}""")
                else -> V1StubResponse(200, examJson())
            }
        }
        installControlRoutes(f.gateway())
        val response = sendJson(HttpMethod.Put, "/api/v1/admin/examenes/${ControlFake.EXAM}/reglas-puntuacion",
            """{"reglaAnuladas":"REEMPLAZO_POR_RESERVA","penalizacionRacional":false,"penalizacionNum":0,"penalizacionDen":1}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("GRADING_PENALTY_CONFLICT"), response.bodyAsText())
        assertTrue(!response.bodyAsText().contains("secreto") && !response.bodyAsText().contains("pista-interna"), response.bodyAsText())
    }

    @Test
    fun savingThroughTheTransactionalRpcWritesNoSecondAuditRowAndNoPatch() {
        val f = fake(gradingRpc = true)
        val saved = f.gateway().putGradingRules(ControlFake.EXAM, GradingRules.Input("NO_PUNTUA", true, 0, 1, "Cambio de criterio"))
        assertTrue(saved != null)
        val writes = f.writes()
        val rpc = writes.single()
        assertTrue(rpc.pathAndQuery.contains("/rpc/admin_set_exam_grading_rules"), f.describe())
        listOf(
            "\"p_actor\":\"${ControlFake.ACTOR}\"", "\"p_examen\":\"${ControlFake.EXAM}\"", "\"p_regla_anuladas\":\"NO_PUNTUA\"",
            "\"p_penalizacion_num\":0", "\"p_penalizacion_den\":1", "\"p_penalizacion_racional\":true", "\"p_motivo\":\"Cambio de criterio\"",
        ).forEach { assertTrue(rpc.body.contains(it), rpc.body) }
        assertTrue(writes.none { it.method == "PATCH" || it.pathAndQuery.contains("auditoria_log") }, f.describe())
    }

    @Test
    fun aDomainErrorFromTheRpcIsPropagatedNotSwallowedByTheLegacyFallback() = testApplication {
        val f = ControlFake { _, uri, _ ->
            when {
                uri.contains("/rpc/admin_set_exam_grading_rules") -> V1StubResponse(400, """{"code":"P0001","message":"GRADING_PENALTY_CONFLICT","details":"secreto"}""")
                uri.contains("/rpc/") -> V1StubResponse(404, """{"code":"PGRST202"}""")
                uri.contains("/rest/v1/preguntas_examen") -> V1StubResponse(200, "[]")
                else -> V1StubResponse(200, examJson())
            }
        }
        installControlRoutes(f.gateway())
        val response = sendJson(HttpMethod.Put, "/api/v1/admin/examenes/${ControlFake.EXAM}/reglas-puntuacion",
            """{"reglaAnuladas":null,"penalizacionRacional":false,"penalizacionNum":0,"penalizacionDen":1}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("GRADING_PENALTY_CONFLICT") && !response.bodyAsText().contains("secreto"), response.bodyAsText())
        assertTrue(f.writes().none { it.method == "PATCH" }, f.describe())
    }

    @Test
    fun theRpcTrailRowCarriesTheActorAndTheReasonInOneRow() {
        val f = ControlFake { _, uri, _ ->
            if (uri.contains("auditoria_log")) {
                V1StubResponse(200, """[{"id":"r1","usuario_id":"${ControlFake.ACTOR}","accion":"examen_reglas_puntuacion_modificadas","antes":{"regla_anuladas":null,"penalizacion_num":0,"penalizacion_den":1,"penalizacion_racional":false},"despues":{"regla_anuladas":"NO_PUNTUA","penalizacion_num":0,"penalizacion_den":1,"penalizacion_racional":true,"motivo":"Por el tribunal"},"creado_en":"2026-10-18T10:00:00Z"}]""")
            } else V1StubResponse(200, "[]")
        }
        val entry = f.gateway().gradingAudit(ControlFake.EXAM).single()
        assertEquals(ControlFake.ACTOR, entry.actorId)
        assertEquals("Por el tribunal", entry.motivo)
    }
}
