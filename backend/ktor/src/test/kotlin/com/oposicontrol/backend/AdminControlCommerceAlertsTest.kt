package com.oposicontrol.backend

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
import kotlin.test.assertTrue

class AdminControlCommerceAlertsTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private val exam = ControlFake.EXAM
    private val community = ControlFake.USER

    private fun examRow(comprable: Boolean = false, precio: String = "null", dias: Int = 30, comunidad: String? = community) =
        """[{"id":"$exam","codigo_examen":"EX-1","titulo":"Examen","tipo_examen":"inmersivo","es_oficial":true,"publicado_en":null,""" +
            """"comunidad_id":${comunidad?.let { "\"$it\"" } ?: "null"},"comprable_con_tickets":$comprable,"precio_tickets":$precio,"dias_acceso_compra":$dias,""" +
            """"comunidades_autonomas":{"nombre":"Comunidad de Madrid"}}]"""

    @Test
    fun sellableNeedsAPriceAndDaysEvaluatedOnTheResultingState() {
        val current = ExamEconomyRules.Current(false, null, 30, community)
        // Marcar comprable sin precio => rechazo
        assertEquals("EXAM_NOT_PURCHASABLE_PRICE", assertFailsWith<BackendDomainException> {
            ExamEconomyRules.resulting(current, ExamEconomyRules.parse(obj("""{"comprableConTickets":true}""")))
        }.errorCode)
        // Con precio en el mismo cuerpo => vale
        val cols = ExamEconomyRules.resulting(current, ExamEconomyRules.parse(obj("""{"comprableConTickets":true,"precioTickets":1300}""")))
        assertEquals("true", cols["comprable_con_tickets"].contentOrNullSafe())
        assertEquals("1300", cols["precio_tickets"].contentOrNullSafe())
        // Ya comprable: no se puede borrar el precio
        val sellable = ExamEconomyRules.Current(true, 600, 30, community)
        assertEquals("EXAM_NOT_PURCHASABLE_PRICE", assertFailsWith<BackendDomainException> {
            ExamEconomyRules.resulting(sellable, ExamEconomyRules.parse(obj("""{"precioTickets":null}""")))
        }.errorCode)
        assertEquals("EXAM_NOT_PURCHASABLE_PRICE", assertFailsWith<BackendDomainException> {
            ExamEconomyRules.resulting(sellable, ExamEconomyRules.parse(obj("""{"precioTickets":0}""")))
        }.errorCode)
    }

    @Test
    fun boundsAndTypesOfThePatchAreEnforced() {
        assertEquals("EXAM_PRICE_INVALID", assertFailsWith<BackendDomainException> { ExamEconomyRules.parse(obj("""{"precioTickets":-1}""")) }.errorCode)
        assertEquals("EXAM_PRICE_INVALID", assertFailsWith<BackendDomainException> { ExamEconomyRules.parse(obj("""{"precioTickets":1000001}""")) }.errorCode)
        assertEquals("EXAM_ACCESS_DAYS_INVALID", assertFailsWith<BackendDomainException> { ExamEconomyRules.parse(obj("""{"diasAccesoCompra":0}""")) }.errorCode)
        assertEquals("EXAM_ACCESS_DAYS_INVALID", assertFailsWith<BackendDomainException> { ExamEconomyRules.parse(obj("""{"diasAccesoCompra":3651}""")) }.errorCode)
        assertFailsWith<BadRequestException> { ExamEconomyRules.parse(obj("""{}""")) }
        assertFailsWith<BadRequestException> { ExamEconomyRules.parse(obj("""{"precioTickets":"10"}""")) }
        assertFailsWith<BadRequestException> { ExamEconomyRules.parse(obj("""{"diasAccesoCompra":null}""")) }
        assertFailsWith<BadRequestException> { ExamEconomyRules.parse(obj("""{"esOficial":true}""")) }
    }

    @Test
    fun theCommunityIsMandatoryAndNeverClearable() {
        assertEquals("EXAM_COMMUNITY_REQUIRED", assertFailsWith<BackendDomainException> { ExamEconomyRules.parse(obj("""{"comunidadId":null}""")) }.errorCode)
        assertEquals("EXAM_COMMUNITY_REQUIRED", assertFailsWith<BackendDomainException> { ExamEconomyRules.parse(obj("""{"comunidadId":"  "}""")) }.errorCode)
        assertFailsWith<BadRequestException> { ExamEconomyRules.parse(obj("""{"comunidadId":"ES-MD"}""")) }
        assertEquals(community, ExamEconomyRules.parse(obj("""{"comunidadId":"${community.uppercase()}"}""")).comunidadId)
    }

    @Test
    fun updatingWritesOnePatchAndOneAuditRowWithBeforeAndAfter() {
        val f = ControlFake { _, uri, _ -> if (uri.contains("/rest/v1/examenes")) V1StubResponse(200, examRow()) else V1StubResponse(201, "") }
        f.gateway().updateExamEconomy(exam, ExamEconomyRules.parse(obj("""{"comprableConTickets":true,"precioTickets":600,"diasAccesoCompra":45}""")))
        val patch = f.writes().single { it.method == "PATCH" }
        assertTrue(patch.pathAndQuery.contains("id=eq.$exam"))
        listOf("\"comprable_con_tickets\":true", "\"precio_tickets\":600", "\"dias_acceso_compra\":45").forEach { assertTrue(patch.body.contains(it), patch.body) }
        val audit = f.writes().single { it.pathAndQuery.contains("auditoria_log") }
        assertTrue(audit.body.contains("examen_economia_modificada") && audit.body.contains("\"antes\"") && audit.body.contains("\"despues\""), audit.body)
    }

    @Test
    fun anInvalidResultingStateWritesNothing() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, examRow()) }
        assertFailsWith<BackendDomainException> {
            f.gateway().updateExamEconomy(exam, ExamEconomyRules.parse(obj("""{"comprableConTickets":true}""")))
        }
        assertTrue(f.writes().isEmpty(), f.describe())
    }

    @Test
    fun theDatabaseCommunityTriggerIsATranslated422() = testApplication {
        val f = ControlFake { method, _, _ ->
            if (method == "PATCH") V1StubResponse(400, """{"code":"23514","message":"EXAM_COMMUNITY_REQUIRED"}""") else V1StubResponse(200, examRow())
        }
        installControlRoutes(f.gateway())
        val r = sendJson(HttpMethod.Patch, "/api/v1/admin/examenes-economia/$exam", """{"comunidadId":"$community"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
    }

    @Test
    fun theEconomyListReadsTheCommunityNameAndPaginates() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, examRow(true, "1300"), contentRange = "0-0/42") }
        val page = f.gateway().listExamEconomy(25, 0, soloSinComunidad = false)
        assertEquals(42, page.total)
        assertEquals("Comunidad de Madrid", page.data.single().comunidadNombre)
        assertEquals(1300, page.data.single().precioTickets)
        f.gateway().listExamEconomy(25, 50, soloSinComunidad = true)
        assertTrue(f.calls.last().pathAndQuery.contains("comunidad_id=is.null") && f.calls.last().pathAndQuery.contains("offset=50"), f.describe())
    }

    // ------------------------------------------------------------ pedidos

    private fun orderFake() = ControlFake { _, uri, _ ->
        when {
            uri.contains("/rest/v1/compras_usuario") -> V1StubResponse(
                200,
                """[{"id":"$exam","usuario_id":"$community","proveedor":"google_play","producto_codigo":"pack_100","tipo_producto":"pack","estado":"en_revision","precio_centimos":100,"moneda":"EUR","identificador_transaccion":"GPA.1","cantidad_tickets":100,"creado_en":"2026-10-17T10:00:00Z"}]""",
                contentRange = "0-0/1",
            )
            uri.contains("/rest/v1/perfiles_usuario") -> V1StubResponse(200, """[{"usuario_id":"$community","username":"pepe"}]""")
            uri.contains("/rest/v1/refund_case") -> V1StubResponse(200, """[{"id":"${ControlFake.OTHER}","compra_id":"$exam","estado":"abierto","ot_revertidos":40,"ot_gastados":60,"politica":"P2","detalle":[{"examen":"x"}]}]""")
            else -> V1StubResponse(200, "[]")
        }
    }

    @Test
    fun ordersCarryHandleAndRefundCaseButNeverThePaymentPayload() {
        val f = orderFake()
        val page = f.gateway().listOrders("en_revision", 25, 0)
        val o = page.data.single()
        assertEquals("pepe", o.usuarioHandle)
        assertEquals(60, o.reembolso?.otGastados)
        assertTrue(f.calls.first().pathAndQuery.contains("estado=eq.en_revision"))
        assertTrue(!f.calls.first().pathAndQuery.contains("payload_validacion"), "el payload de validacion no se lee")
    }

    @Test
    fun aMissingRefundTableDoesNotBreakTheOrderList() {
        val f = ControlFake { _, uri, _ ->
            when {
                uri.contains("/rest/v1/refund_case") -> V1StubResponse(404, """{"code":"PGRST205"}""")
                uri.contains("/rest/v1/compras_usuario") -> V1StubResponse(200, """[{"id":"$exam","usuario_id":"$community","estado":"pagada"}]""", contentRange = "0-0/1")
                else -> V1StubResponse(200, "[]")
            }
        }
        assertEquals(1, f.gateway().listOrders(null, 25, 0).data.size)
    }

    @Test
    fun anInternalNoteIsAuditedAndChangesNothingElse() {
        val f = ControlFake { method, uri, _ -> if (method == "POST") V1StubResponse(201, "") else V1StubResponse(200, """[{"id":"$exam"}]""") }
        assertTrue(f.gateway().addOrderNote(exam, "Verificado con Google Play"))
        val writes = f.writes()
        assertEquals(1, writes.size, f.describe())
        assertTrue(writes.single().pathAndQuery.endsWith("auditoria_log?") || writes.single().pathAndQuery.contains("/rest/v1/auditoria_log"))
        assertTrue(writes.single().body.contains("pedido_nota_interna") && writes.single().body.contains(ControlFake.ACTOR))
    }

    @Test
    fun orderRoutesValidateStateIdAndNote() = testApplication {
        val f = ControlFake()
        installControlRoutes(f.gateway())
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Get, "/api/v1/admin/pedidos?estado=hackeado").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Get, "/api/v1/admin/pedidos/no-uuid").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Post, "/api/v1/admin/pedidos/$exam/nota", """{"nota":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Post, "/api/v1/admin/pedidos/$exam/nota", """{"nota":"ok ok","estado":"pagada"}""").status)
        assertTrue(f.calls.isEmpty(), f.describe())
    }

    // ------------------------------------------------------------ alertas

    @Test
    fun fastWindowsJoinHandleLeagueAndThreshold() {
        val f = ControlFake { _, uri, _ ->
            when {
                uri.contains("/rest/v1/ranking_ventanas_rapidas") -> V1StubResponse(
                    200,
                    """[{"usuario_id":"$community","liga_nivel_id":"${ControlFake.OTHER}","ventana_calculo":50,"ventana_tiempo_mediano_ms":420.0,"actualizado_en":"2026-10-18T01:00:00Z"}]""",
                    contentRange = "0-0/1",
                )
                uri.contains("/rest/v1/perfiles_usuario") -> V1StubResponse(200, """[{"usuario_id":"$community","username":"veloz"}]""")
                uri.contains("/rest/v1/ligas_nivel") -> V1StubResponse(200, """[{"id":"${ControlFake.OTHER}","nombre":"Liga 50"}]""")
                uri.contains("/rest/v1/configuracion_v1") -> V1StubResponse(200, """[{"valor_integer":1000}]""")
                else -> V1StubResponse(200, "[]")
            }
        }
        val page = f.gateway().fastWindows(25, 0)
        val row = page.data.single()
        assertEquals("veloz", row.handle)
        assertEquals("Liga 50", row.ligaNombre)
        assertEquals(420L, row.medianaMs)
        assertEquals(1000, page.umbralMs)
        assertTrue(f.writes().isEmpty(), "la vista de alertas es de solo lectura: " + f.describe())
    }

    @Test
    fun alertRoutesExposeOnlyGetAndValidatePaging() = testApplication {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, "[]", contentRange = "*/0") }
        installControlRoutes(f.gateway())
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Get, "/api/v1/admin/alertas/ranking-ventanas-rapidas?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Get, "/api/v1/admin/alertas/ranking-ventanas-rapidas?offset=-5").status)
        assertEquals(HttpStatusCode.OK, sendJson(HttpMethod.Get, "/api/v1/admin/alertas/ranking-ventanas-rapidas").status)
    }

    @Test
    fun forumDriftReadsTheLatestAuditRows() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, """[{"id":"r1","creado_en":"2026-10-18T02:00:00Z","despues":{"filas":3}}]""") }
        val rows = f.gateway().forumDrift(20)
        assertEquals(3, rows.single().filas)
        assertTrue(f.calls.single().pathAndQuery.contains("accion=eq.foro_contadores_desviados"))
    }
}
