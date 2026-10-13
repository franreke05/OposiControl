package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dar de baja y reactivar una cuenta (`POST /api/v1/admin/usuarios/{id}/estado-cuenta`).
 *
 * Es la accion mas delicada del panel sobre una persona, asi que se prueba sobre todo lo que NO debe pasar: que la haga
 * quien no es superadministrador, sobre si mismo, sobre otro superadministrador, sin motivo o sin dejar auditoria.
 */
class AdminAccountStatusTest {
    private val superActor = "5a5a5a5a-0000-4000-8000-000000000001"
    private val adminActor = "5a5a5a5a-0000-4000-8000-000000000002"
    private val target = "5a5a5a5a-0000-4000-8000-000000000003"
    private val otherSuper = "5a5a5a5a-0000-4000-8000-000000000004"
    private val roleSuper = "aaaaaaaa-0000-4000-8000-00000000000a"
    private val roleAdmin = "aaaaaaaa-0000-4000-8000-00000000000b"

    private fun gateway(sent: MutableList<HttpRequest>, estado: String? = "activa", auditStatus: Int = 201): AdminV1Gateway =
        AdminV1Gateway.forTesting(
            supabaseUrl = "https://stub.supabase.test",
            serviceRoleKey = "stub-key",
            json = Json { ignoreUnknownKeys = true },
            client = V1StubHttpClient(sent::add) { uri ->
                val roles = mapOf(superActor to roleSuper, otherSuper to roleSuper, adminActor to roleAdmin)
                when {
                    "/rest/v1/roles_app" in uri -> V1StubResponse(
                        200,
                        """[{"id":"$roleSuper","codigo":"superadmin","nombre":"Superadmin"},{"id":"$roleAdmin","codigo":"admin","nombre":"Admin"}]""",
                    )
                    "/rest/v1/roles_usuario" in uri -> {
                        val id = uri.substringAfter("usuario_id=eq.").substringBefore('&')
                        V1StubResponse(200, roles[id]?.let { """[{"rol_id":"$it"}]""" } ?: "[]")
                    }
                    "/rest/v1/auditoria_log" in uri -> V1StubResponse(auditStatus, "")
                    "/rest/v1/perfiles_usuario" in uri ->
                        V1StubResponse(200, if (estado == null) "[]" else """[{"usuario_id":"$target","estado_cuenta":"$estado"}]""")
                    else -> V1StubResponse(200, "[]")
                }
            },
        )

    private fun List<HttpRequest>.patches() = filter { it.method() == "PATCH" }

    private fun List<HttpRequest>.audits() = filter { it.method() == "POST" && "auditoria_log" in it.uri().toString() }.map(::readRequestBody)

    @Test
    fun aSuperadminBlocksAnActiveAccountWithAReasonAndItIsAuditedFirst() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val result = gateway(sent).setAccountStatus(target, "bloqueada", "  Uso indebido.  ", superActor)

        assertEquals(AdminAccountStatusResponse(target, "bloqueada", cambiado = true), result)
        val patch = sent.patches().single()
        // Condicionada al estado leido: dos superadministradores a la vez no se pisan.
        assertTrue("usuario_id=eq.$target" in patch.uri().toString() && "estado_cuenta=eq.activa" in patch.uri().toString())
        assertTrue("bloqueada" in readRequestBody(patch))
        val audit = sent.audits().single()
        assertTrue("cuenta_dada_de_baja" in audit && "Uso indebido." in audit, audit)
        assertTrue(sent.indexOfFirst { "auditoria_log" in it.uri().toString() } < sent.indexOf(patch), "la auditoria va antes de tocar la cuenta")
    }

    @Test
    fun reactivatingWritesActivaAndItsOwnAuditAction() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        gateway(sent, estado = "bloqueada").setAccountStatus(target, "activa", "Aclarado.", superActor)
        assertTrue("activa" in readRequestBody(sent.patches().single()))
        assertTrue("cuenta_reactivada" in sent.audits().single())
    }

    @Test
    fun askingForTheStateItAlreadyHasWritesNothing() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val result = gateway(sent, estado = "bloqueada").setAccountStatus(target, "bloqueada", "Otra vez.", superActor)
        assertFalse(result.cambiado)
        assertTrue(sent.patches().isEmpty() && sent.audits().isEmpty())
    }

    @Test
    fun onlyASuperadminCanDoIt() {
        listOf(adminActor, null).forEach { actor ->
            val sent = CopyOnWriteArrayList<HttpRequest>()
            val error = assertFailsWith<BackendDomainException> { gateway(sent).setAccountStatus(target, "bloqueada", "m", actor) }
            assertEquals(HttpStatusCode.Forbidden, error.status, "actor=$actor")
            assertTrue(sent.patches().isEmpty() && sent.audits().isEmpty())
        }
    }

    @Test
    fun neverOnYourselfNorOnAnotherSuperadminNorOnARequestedDeletion() {
        val self = CopyOnWriteArrayList<HttpRequest>()
        assertEquals("ACCOUNT_STATUS_SELF", assertFailsWith<BackendDomainException> { gateway(self).setAccountStatus(superActor, "bloqueada", "m", superActor) }.errorCode)

        val peer = CopyOnWriteArrayList<HttpRequest>()
        assertEquals(
            "ACCOUNT_STATUS_TARGET_SUPERADMIN",
            assertFailsWith<BackendDomainException> { gateway(peer).setAccountStatus(otherSuper, "bloqueada", "m", superActor) }.errorCode,
        )

        val pending = CopyOnWriteArrayList<HttpRequest>()
        assertEquals(
            "ACCOUNT_DELETION_PENDING",
            assertFailsWith<BackendDomainException> { gateway(pending, estado = "eliminacion_pendiente").setAccountStatus(target, "bloqueada", "m", superActor) }.errorCode,
        )
        assertTrue((self + peer + pending).none { it.method() == "PATCH" })
    }

    @Test
    fun aReasonIsMandatoryAndTheStateIsOneOfTwo() {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        assertEquals("ACCOUNT_STATUS_REASON_REQUIRED", assertFailsWith<BackendDomainException> { gateway(sent).setAccountStatus(target, "bloqueada", "   ", superActor) }.errorCode)
        assertEquals(
            "ACCOUNT_STATUS_REASON_REQUIRED",
            assertFailsWith<BackendDomainException> { gateway(sent).setAccountStatus(target, "bloqueada", "x".repeat(ACCOUNT_STATUS_REASON_MAX + 1), superActor) }.errorCode,
        )
        assertEquals("ACCOUNT_STATUS_INVALID", assertFailsWith<BackendDomainException> { gateway(sent).setAccountStatus(target, "eliminacion_pendiente", "m", superActor) }.errorCode)
        assertFailsWith<IllegalArgumentException> { gateway(sent).setAccountStatus("1 or 1=1", "bloqueada", "m", superActor) }
        assertTrue(sent.isEmpty(), "nada de esto llega a salir")
    }

    @Test
    fun withoutAnAuditRowTheAccountIsNotTouchedAndAnUnknownUserIsNotFound() {
        val noAudit = CopyOnWriteArrayList<HttpRequest>()
        assertFailsWith<Exception> { gateway(noAudit, auditStatus = 500).setAccountStatus(target, "bloqueada", "m", superActor) }
        assertTrue(noAudit.patches().isEmpty(), "sin constancia no hay cambio")

        val missing = CopyOnWriteArrayList<HttpRequest>()
        assertEquals("USER_NOT_FOUND", assertFailsWith<BackendDomainException> { gateway(missing, estado = null).setAccountStatus(target, "bloqueada", "m", superActor) }.errorCode)
    }
}
