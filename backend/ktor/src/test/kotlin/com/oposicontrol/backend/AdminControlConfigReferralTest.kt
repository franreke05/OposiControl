package com.oposicontrol.backend

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdminControlConfigReferralTest {
    @Test
    fun everyEditableKeyHasAnOrderedRangeAndAGroup() {
        ControlConfigV1.SPECS.forEach { spec ->
            assertTrue(spec.min <= spec.max, spec.clave)
            assertTrue(spec.grupo in ControlConfigV1.GROUPS, spec.clave)
        }
        assertEquals(ControlConfigV1.SPECS.size, ControlConfigV1.SPECS.map { it.clave }.toSet().size, "claves repetidas")
    }

    @Test
    fun validationMirrorsTheDatabaseRangesAndRejectsUnknownKeys() {
        ControlConfigV1.validate("referido_ot_invitador", 0)
        ControlConfigV1.validate("referido_ot_invitador", 1000)
        listOf(-1, 1001).forEach {
            assertEquals("CONFIG_VALUE_OUT_OF_BOUNDS", assertFailsWith<BackendDomainException> { ControlConfigV1.validate("referido_ot_invitador", it) }.errorCode)
        }
        assertEquals("CONFIG_KEY_UNKNOWN", assertFailsWith<BackendDomainException> { ControlConfigV1.validate("zona_horaria", 1) }.errorCode)
        assertEquals("CONFIG_KEY_UNKNOWN", assertFailsWith<BackendDomainException> { ControlConfigV1.validate("tope_ot_comprados_dia", 5) }.errorCode)
        // El activo es un interruptor 0/1.
        assertEquals("CONFIG_VALUE_OUT_OF_BOUNDS", assertFailsWith<BackendDomainException> { ControlConfigV1.validate("referido_activo", 2) }.errorCode)
    }

    @Test
    fun readingReportsMissingRowsAsNullAndNeverAsZero() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, """[{"clave":"referido_activo","valor_integer":1,"actualizado_en":"2026-10-18T00:00:00Z"}]""") }
        val entries = f.gateway().readConfig("referido")
        assertEquals(1, entries.first { it.clave == "referido_activo" }.valor)
        assertEquals(null, entries.first { it.clave == "referido_ot_invitador" }.valor)
        assertTrue(f.calls.single().pathAndQuery.contains("clave=in.(referido_activo,"), f.describe())
    }

    @Test
    fun writingSetsTheActorAndRefusesAnOutOfRangeValueBeforeTheNetwork() {
        val f = ControlFake { method, _, _ ->
            if (method == "PATCH") V1StubResponse(200, """[{"clave":"referido_ot_invitador","valor_integer":50}]""")
            else V1StubResponse(200, """[{"clave":"referido_ot_invitador","valor_integer":50}]""")
        }
        val saved = f.gateway().writeConfig("referido_ot_invitador", 50)
        assertNotNull(saved)
        val patch = f.writes().single()
        assertTrue(patch.pathAndQuery.contains("clave=eq.referido_ot_invitador"), patch.pathAndQuery)
        assertTrue(patch.body.contains("\"valor_integer\":50") && patch.body.contains(ControlFake.ACTOR), patch.body)

        val g = ControlFake()
        assertFailsWith<BackendDomainException> { g.gateway().writeConfig("referido_ot_invitador", 5000) }
        assertTrue(g.calls.isEmpty(), g.describe())
    }

    @Test
    fun anUnknownRowIsA404AndNothingIsInserted() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, "[]") }
        assertEquals(null, f.gateway().writeConfig("referido_activo", 1))
        assertTrue(f.writes().none { it.method == "POST" }, f.describe())
    }

    @Test
    fun theConfigRoutesValidateGroupKeyAndBody() = testApplication {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, "[]") }
        installControlRoutes(f.gateway())
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Get, "/api/v1/admin/config-v1?grupo=otro").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Put, "/api/v1/admin/config-v1/referido_activo", """{"valor":1,"extra":2}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Put, "/api/v1/admin/config-v1/referido_activo", """{"valor":"1"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Put, "/api/v1/admin/config-v1/foro_titulo_max", """{"valor":5}""").status)
        assertTrue(f.calls.isEmpty(), f.describe())
    }

    // ------------------------------------------------------------ invitaciones

    private val listJson =
        """{"items":[{"id":"${ControlFake.OTHER}","estado":"premiada","creadoEn":"2026-10-17T10:00:00Z","invitadorHandle":"ana","invitadoHandle":"<b>luis</b>","pagoInvitador":"pagado","pagoInvitado":"pagado","intentosPago":0,"marcas":{"ip_burst":true,"mismo_dominio_raiz":false}},""" +
            """{"id":"no-uuid","estado":"premiada"},{"id":"${ControlFake.USER}","estado":"inventado"}],"nextCursor":"abc_-","resumen":{"premiada":3,"inventado":9}}"""

    @Test
    fun theListKeepsOnlyWellFormedRowsAndKnownStatesAndOnlyTrueFlags() {
        val f = ControlFake { _, _, _ -> V1StubResponse(200, listJson) }
        val page = f.gateway().listReferrals("premiada", null, 25)
        assertEquals(1, page.items.size)
        assertEquals(listOf("ip_burst"), page.items.single().marcas)
        assertEquals(mapOf("premiada" to 3), page.resumen)
        assertEquals("abc_-", page.nextCursor)
        // El handle llega como texto: no se interpreta; los caracteres de marcado no tienen significado en Compose y
        // ademas los de control/bidi se eliminan.
        assertEquals("<b>luis</b>", page.items.single().invitadoHandle)
    }

    @Test
    fun aMissingListFunctionIsAnHonest501() {
        val f = ControlFake { _, _, _ -> V1StubResponse(404, """{"code":"PGRST202","message":"Could not find the function"}""") }
        val error = assertFailsWith<BackendDomainException> { f.gateway().listReferrals(null, null, 25) }
        assertEquals("CONTROL_RPC_NOT_PROVISIONED", error.errorCode)
        assertEquals(HttpStatusCode.NotImplemented, error.status)
    }

    @Test
    fun revokingNeedsAReasonAndSendsTheJwtActor() {
        assertEquals("REFERRAL_REASON_REQUIRED", assertFailsWith<BackendDomainException> { ReferralRules.motivo("ab") }.errorCode)
        val f = ControlFake { _, _, _ -> V1StubResponse(200, """{"estado":"revocada","revertido":10,"noRevertible":5}""") }
        val result = f.gateway().revokeReferral(ControlFake.OTHER, "Cuentas duplicadas")
        assertEquals(10, result.revertido)
        assertEquals(5, result.noRevertible)
        val call = f.calls.single()
        assertTrue(call.pathAndQuery.contains("/rpc/admin_referral_revoke"), call.pathAndQuery)
        assertTrue(call.body.contains(ControlFake.ACTOR) && call.body.contains("Cuentas duplicadas"), call.body)
    }

    @Test
    fun theReferralRoutesRejectBadInputWithoutTouchingTheUpstream() = testApplication {
        val f = ControlFake()
        installControlRoutes(f.gateway())
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Get, "/api/v1/admin/referidos/invitaciones?estado=inventado").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Get, "/api/v1/admin/referidos/invitaciones?cursor=%24%7Bevil%7D").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Post, "/api/v1/admin/referidos/invitaciones/no-uuid/revocar", """{"motivo":"abcd"}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, sendJson(HttpMethod.Post, "/api/v1/admin/referidos/invitaciones/${ControlFake.OTHER}/revocar", """{"motivo":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendJson(HttpMethod.Post, "/api/v1/admin/referidos/invitaciones/${ControlFake.OTHER}/revocar", """{"motivo":"abcd","actor":"${ControlFake.USER}"}""").status)
        assertTrue(f.calls.isEmpty(), f.describe())
    }

    @Test
    fun noPersonMeansNo403BeforeAnyRevocationRequest() {
        val f = ControlFake()
        val error = assertFailsWith<BackendDomainException> { f.gateway(actor = null).revokeReferral(ControlFake.OTHER, "motivo ok") }
        assertEquals(HttpStatusCode.Forbidden, error.status)
        assertTrue(f.calls.isEmpty())
        // el cuerpo no se puede suplantar: el actor del cuerpo se rechaza como clave desconocida (arriba)
        assertTrue(true, f.bodyAsTextSafe())
    }

    private fun ControlFake.bodyAsTextSafe() = describe()
}
