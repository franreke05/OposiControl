package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MASTER5-API-008: las operaciones de dos pasos de la superficie legacy (`/api/v1/admin/oposibots/usuarios`) no dejan
 * medio estado cuando el cliente se desconecta (cancelacion) entre los pasos, y el borrado es reintentable si su
 * segundo paso falla.
 */
class OposiBotsUserAdminGatewayCancellationTest {
    private val authId = "33333333-3333-4333-8333-333333333333"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val row =
        """[{"id":42,"auth_user_id":"$authId","email":"x@example.com","nombre":"N","apellidos":"A","username":null,""" +
            """"especialidad_registro":"E","perfil_completo":true,"perfil_completado_at":null,"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-01T00:00:00Z"}]"""

    private class Harness(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val requests = CopyOnWriteArrayList<String>()
        val client = HttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            handler(request)
        })
    }

    private fun gateway(h: Harness) =
        SupabaseOposiBotsUserAdminGateway("https://example.supabase.co", "service-role-key", Json { ignoreUnknownKeys = true }, h.client)

    /** Fuera de la jerarquia de la prueba: el fallo de la llamada cancelada no debe tumbar el `runBlocking`. */
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val createRequest =OposiBotsUserAdminCreateRequest("x@example.com", null, "N", "A", null, "E")

    @Test
    fun cancellingBetweenTheTwoCreateStepsStillCompensatesTheAuthIdentity() = runBlocking {
        val insertReached = CompletableDeferred<Unit>()
        val insertRelease = CompletableDeferred<Unit>()
        val h = Harness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/auth/v1/admin/users" ->
                    respond(ByteReadChannel("""{"id":"$authId"}"""), HttpStatusCode.OK, jsonHeaders)
                request.method == HttpMethod.Post && request.url.encodedPath == "/rest/v1/usuario" -> {
                    insertReached.complete(Unit)
                    insertRelease.await()
                    respond(ByteReadChannel("""{"message":"boom"}"""), HttpStatusCode.InternalServerError, jsonHeaders)
                }
                else -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, jsonHeaders)
            }
        }
        val call = detached.async { gateway(h).createUser(createRequest) }
        withTimeout(5_000) { insertReached.await() }
        call.cancel() // el cliente se va con la fila a medio insertar
        insertRelease.complete(Unit)
        runCatching { withTimeout(5_000) { call.await() } }
        withTimeout(5_000) { while (h.requests.size < 3) kotlinx.coroutines.delay(10) }
        assertEquals(
            listOf("POST /auth/v1/admin/users", "POST /rest/v1/usuario", "DELETE /auth/v1/admin/users/$authId"),
            h.requests.toList(),
            "la compensacion corre aunque la peticion se haya cancelado",
        )
    }

    @Test
    fun cancellingDuringTheAuthCreateStillFinishesTheUnit() = runBlocking {
        val authReached = CompletableDeferred<Unit>()
        val authRelease = CompletableDeferred<Unit>()
        val h = Harness { request ->
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/auth/v1/admin/users" -> {
                    authReached.complete(Unit)
                    authRelease.await()
                    respond(ByteReadChannel("""{"id":"$authId"}"""), HttpStatusCode.OK, jsonHeaders)
                }
                request.method == HttpMethod.Post && request.url.encodedPath == "/rest/v1/usuario" ->
                    respond(ByteReadChannel(row), HttpStatusCode.Created, jsonHeaders)
                else -> respond(ByteReadChannel(""), HttpStatusCode.NoContent, jsonHeaders)
            }
        }
        val call = detached.async { gateway(h).createUser(createRequest) }
        withTimeout(5_000) { authReached.await() }
        call.cancel()
        authRelease.complete(Unit)
        runCatching { withTimeout(5_000) { call.await() } }
        withTimeout(5_000) { while (h.requests.size < 2) kotlinx.coroutines.delay(10) }
        assertEquals(
            listOf("POST /auth/v1/admin/users", "POST /rest/v1/usuario"),
            h.requests.toList(),
            "una identidad creada nunca queda sin su fila (ni sin compensacion)",
        )
    }

    @Test
    fun cancellingDuringTheAuthDeleteStillRemovesTheRow() = runBlocking {
        val authDeleteReached = CompletableDeferred<Unit>()
        val authDeleteRelease = CompletableDeferred<Unit>()
        val h = Harness { request ->
            when {
                request.method == HttpMethod.Get -> respond(ByteReadChannel(row), HttpStatusCode.OK, jsonHeaders)
                request.method == HttpMethod.Delete && request.url.encodedPath == "/auth/v1/admin/users/$authId" -> {
                    authDeleteReached.complete(Unit)
                    authDeleteRelease.await()
                    respond(ByteReadChannel(""), HttpStatusCode.OK, jsonHeaders)
                }
                else -> respond(ByteReadChannel(row), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val call = detached.async { gateway(h).deleteUser(42) }
        withTimeout(5_000) { authDeleteReached.await() }
        call.cancel()
        authDeleteRelease.complete(Unit)
        runCatching { withTimeout(5_000) { call.await() } }
        withTimeout(5_000) { while (h.requests.size < 3) kotlinx.coroutines.delay(10) }
        assertEquals("DELETE /rest/v1/usuario", h.requests.last(), "la fila tambien se borra: ${h.requests}")
    }

    @Test
    fun aFailedAuthDeleteLeavesTheRowSoTheRetryCompletesTheDeletion() = runBlocking {
        var authDeleteStatus = HttpStatusCode.InternalServerError
        var rowExists = true
        val h = Harness { request ->
            when {
                request.method == HttpMethod.Get ->
                    respond(ByteReadChannel(if (rowExists) row else "[]"), HttpStatusCode.OK, jsonHeaders)
                request.method == HttpMethod.Delete && request.url.encodedPath == "/auth/v1/admin/users/$authId" ->
                    respond(ByteReadChannel("""{"message":"x"}"""), authDeleteStatus, jsonHeaders)
                request.method == HttpMethod.Delete && request.url.encodedPath == "/rest/v1/usuario" -> {
                    rowExists = false
                    respond(ByteReadChannel(row), HttpStatusCode.OK, jsonHeaders)
                }
                else -> error("inesperado: ${request.method} ${request.url}")
            }
        }
        val g = gateway(h)
        val failure = assertFailsWith<SupabaseHttpStatusException> { g.deleteUser(42) }
        assertEquals(500, failure.status)
        assertTrue(rowExists, "si Auth falla, la fila sigue: el reintento puede completar el borrado")
        assertTrue(h.requests.none { it == "DELETE /rest/v1/usuario" })

        // Reintento: Auth ya habia borrado la identidad (404 = exito) y la fila se borra.
        authDeleteStatus = HttpStatusCode.NotFound
        assertEquals(true, g.deleteUser(42))
        assertEquals(false, rowExists)
        // Y un tercer intento ya es 404 limpio (sin fila).
        assertEquals(false, g.deleteUser(42))
    }

    @Test
    fun aUuidContaining404IsNotMistakenForAnAuth404() = runBlocking {
        val tricky = "40440440-4404-4404-8404-404404404404"
        val trickyRow = row.replace(authId, tricky)
        val h = Harness { request ->
            when {
                request.method == HttpMethod.Get -> respond(ByteReadChannel(trickyRow), HttpStatusCode.OK, jsonHeaders)
                request.method == HttpMethod.Delete && request.url.encodedPath.startsWith("/auth/") ->
                    respond(ByteReadChannel("""{"message":"x"}"""), HttpStatusCode.BadGateway, jsonHeaders)
                else -> respond(ByteReadChannel(trickyRow), HttpStatusCode.OK, jsonHeaders)
            }
        }
        assertFailsWith<SupabaseHttpStatusException> { gateway(h).deleteUser(42) }
        assertTrue(h.requests.none { it == "DELETE /rest/v1/usuario" }, "un 502 de Auth no es un 404 por el texto de la ruta")
    }
}
