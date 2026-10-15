package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MASTER2-OBS-006: la auditoria "obligatoria" de la superficie legacy `/api/v1/admin/oposibots/usuarios` iba
 * DESPUES de la mutacion irreversible: un fallo devolvia 500 tras borrar y el reintento (404) nunca escribia la fila;
 * el PATCH no se auditaba. Ahora: fila de intencion obligatoria ANTES de mutar, fila de resultado despues (su fallo se
 * registra en ERROR y no convierte en error una operacion completada) y el PATCH se audita igual.
 */
class LegacyUserAdminAuditOrderingTest {
    private val actor = "44444444-4444-4444-8444-444444444444"
    private val target = "55555555-5555-4555-8555-555555555555"

    private class Fake : OposiBotsUserAdminGateway {
        val deleted = CopyOnWriteArrayList<Int>()
        val updated = CopyOnWriteArrayList<Int>()
        val events = CopyOnWriteArrayList<String>()
        private fun dto(id: Int) = OposiBotsUserAdminDto(id, "55555555-5555-4555-8555-555555555555", "x@example.com", "N", "A", "u", "Celador", true, null, "t", "t")
        override suspend fun listUsers(limit: Int, offset: Int, search: String?) = listOf(dto(7))
        override suspend fun getUser(id: Int) = if (id == 7 && id !in deleted) dto(id) else null
        override suspend fun createUser(request: OposiBotsUserAdminCreateRequest) = dto(8)
        override suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest) =
            getUser(id)?.also { updated += id; events += "mutate:update" }
        override suspend fun deleteUser(id: Int): Boolean = (getUser(id) != null).also { if (it) { deleted += id; events += "mutate:delete" } }
    }

    private data class Audit(val accion: String, val authUserId: String?, val detalle: JsonObject)

    private fun Application.module(fake: Fake, audits: MutableList<Audit>, failing: (String) -> Boolean) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
        installRequestCorrelation()
        installBackendStatusPages()
        install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
        routing {
            authenticate("gateway-auth") {
                oposiBotsUserAdminRoutes(
                    fake,
                    OposiBotsUserAdminPolicy(
                        isAdministrative = { false },
                        isSuperadmin = { true },
                        audit = { accion, authUserId, detalle ->
                            fake.events += "audit:$accion"
                            if (failing(accion)) error("audit down")
                            audits += Audit(accion, authUserId, detalle)
                        },
                    ),
                )
            }
        }
    }

    @Test
    fun theMandatoryAuditIsWrittenBeforeTheIrreversibleDeletion() = testApplication {
        val fake = Fake()
        val audits = CopyOnWriteArrayList<Audit>()
        application { module(fake, audits) { false } }
        val response = client.delete("/api/v1/admin/oposibots/usuarios/7") { header(HttpHeaders.Authorization, "Bearer $actor") }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(
            listOf("audit:$ACTION_DELETE_INTENT", "mutate:delete", "audit:$ACTION_DELETED"),
            fake.events.toList(),
        )
        val requestId = response.headers["X-Request-Id"]
        assertTrue(audits.all { it.detalle["requestId"]?.jsonPrimitive?.content == requestId }, "intencion y resultado correlacionados: $audits")
    }

    @Test
    fun aFailedOutcomeAuditAfterTheDeletionDoesNotTurnACompletedDeletionIntoAnError() = testApplication {
        val fake = Fake()
        val audits = CopyOnWriteArrayList<Audit>()
        application { module(fake, audits) { it == ACTION_DELETED } }
        val response = client.delete("/api/v1/admin/oposibots/usuarios/7") { header(HttpHeaders.Authorization, "Bearer $actor") }
        assertEquals(HttpStatusCode.NoContent, response.status, "la baja se hizo y la intencion quedo auditada")
        assertEquals(listOf(7), fake.deleted.toList())
        assertEquals(listOf(ACTION_DELETE_INTENT to target), audits.map { it.accion to it.authUserId })
    }

    @Test
    fun patchIsAuditedWithFieldNamesOnlyAndAFailedIntentAuditBlocksTheUpdate() = testApplication {
        val fake = Fake()
        val audits = CopyOnWriteArrayList<Audit>()
        var intentDown = false
        application { module(fake, audits) { it == ACTION_UPDATE_INTENT && intentDown } }
        val ok = client.patch("/api/v1/admin/oposibots/usuarios/7") {
            header(HttpHeaders.Authorization, "Bearer $actor")
            contentType(ContentType.Application.Json)
            setBody("""{"nombre":"Secreto","perfilCompleto":true}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(listOf(ACTION_UPDATE_INTENT, ACTION_UPDATED), audits.map { it.accion })
        val campos = audits.last().detalle["campos"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("nombre", "perfilCompleto"), campos)
        assertTrue(audits.none { "Secreto" in it.detalle.toString() }, "nunca valores personales en la auditoria")
        assertEquals(listOf("audit:$ACTION_UPDATE_INTENT", "mutate:update", "audit:$ACTION_UPDATED"), fake.events.toList())

        intentDown = true
        fake.updated.clear()
        val blocked = client.patch("/api/v1/admin/oposibots/usuarios/7") {
            header(HttpHeaders.Authorization, "Bearer $actor")
            contentType(ContentType.Application.Json)
            setBody("""{"apellidos":"B"}""")
        }
        assertTrue(blocked.status.value !in 200..299, "sin auditoria de intencion no hay 2xx: ${blocked.status}")
        assertTrue(fake.updated.isEmpty(), "sin auditoria de intencion no se modifica nada")
    }
}
