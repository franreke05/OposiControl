package com.oposicontrol.backend

import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MASTER4-IDEM-001: la auditoria de RESULTADO del camino de EXITO no corria en NonCancellable (la de fallo si): si la
 * llamada se cancelaba justo despues de una mutacion ya aplicada, la escritura de la fila `*_ado` lanzaba
 * CancellationException y la fila de intencion quedaba sin contrapartida. Ahora se escribe siempre y la cancelacion se
 * sigue propagando despues.
 */
class LegacyUserAdminOutcomeAuditCancellationTest {

    private val detalle = JsonObject(mapOf("requestId" to JsonPrimitive("req-1")))

    @Test
    fun theOutcomeRowIsWrittenEvenWhenTheCallIsAlreadyCancelledAndTheCancellationStillPropagates() = runBlocking {
        val written = CopyOnWriteArrayList<String>()
        val continuedAfter = AtomicBoolean()
        val policy = OposiBotsUserAdminPolicy(
            isAdministrative = { false },
            isSuperadmin = { true },
            // Suspende de verdad (cambio de dispatcher): sin NonCancellable lanzaria CancellationException aqui.
            audit = { accion, _, _ -> withContext(Dispatchers.IO) { written += accion } },
        )
        val job = launch {
            currentCoroutineContext().job.cancel()
            policy.auditOutcome(ACTION_DELETED, "55555555-5555-4555-8555-555555555555", detalle)
            continuedAfter.set(true)
        }
        job.join()
        assertEquals(listOf(ACTION_DELETED), written.toList(), "la fila de resultado se escribe aunque la llamada este cancelada")
        assertFalse(continuedAfter.get(), "la cancelacion se propaga tras escribir, no se traga")
        assertTrue(job.isCancelled)
    }

    @Test
    fun aCancellationRaisedByTheAuditWriterItselfOnAnActiveCallIsLoggedLikeAnyOtherWriteFailure() = runBlocking {
        val policy = OposiBotsUserAdminPolicy(
            isAdministrative = { false },
            isSuperadmin = { true },
            audit = { _, _, _ -> throw CancellationException("timeout interno del escritor") },
        )
        // No lanza: una operacion completada no se convierte en error por su fila de resultado.
        policy.auditOutcome(ACTION_UPDATED, null, detalle)
    }

    @Test
    fun aCallCancelledRightAfterTheCommittedDeletionStillGetsItsOutcomeRow() = testApplication {
        val deleted = AtomicBoolean()
        val audits = CopyOnWriteArrayList<String>()
        val gateway = object : OposiBotsUserAdminGateway {
            private fun dto(id: Int) = OposiBotsUserAdminDto(
                id, "55555555-5555-4555-8555-555555555555", "x@example.com", "N", "A", "u", "Celador", true, null, "t", "t",
            )
            override suspend fun listUsers(limit: Int, offset: Int, search: String?) = emptyList<OposiBotsUserAdminDto>()
            override suspend fun getUser(id: Int) = if (id == 7 && !deleted.get()) dto(id) else null
            override suspend fun createUser(request: OposiBotsUserAdminCreateRequest) = dto(8)
            override suspend fun updateUser(id: Int, request: OposiBotsUserAdminUpdateRequest) = getUser(id)
            override suspend fun deleteUser(id: Int): Boolean {
                deleted.set(true)
                // El cliente se desconecta justo cuando la baja (irreversible) ya esta aplicada.
                currentCoroutineContext().job.cancel()
                return true
            }
        }
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            installRequestCorrelation()
            installBackendStatusPages()
            install(Authentication) { bearer("gateway-auth") { authenticate { UserIdPrincipal(it.token) } } }
            routing {
                authenticate("gateway-auth") {
                    oposiBotsUserAdminRoutes(
                        gateway,
                        OposiBotsUserAdminPolicy(
                            isAdministrative = { false },
                            isSuperadmin = { true },
                            audit = { accion, _, _ -> withContext(Dispatchers.IO) { audits += accion } },
                        ),
                    )
                }
            }
        }
        runCatching {
            client.delete("/api/v1/admin/oposibots/usuarios/7") {
                header(HttpHeaders.Authorization, "Bearer 44444444-4444-4444-8444-444444444444")
            }
        }
        assertTrue(deleted.get())
        assertEquals(
            listOf(ACTION_DELETE_INTENT, ACTION_DELETED),
            audits.toList(),
            "la intencion tiene su fila de resultado aunque la llamada se cancelara tras la mutacion",
        )
    }
}
