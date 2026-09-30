package com.oposicontrol.backend

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MASTER2-AUTH-009: la revocacion de sesiones del borrado de cuenta converge. En un REINTENTO (la solicitud ya estaba
 * pendiente y la revocacion no consta `hecha`), un logout respondido 401/403 con `error_code=session_not_found`
 * (MASTER3-AUTH-001: no cualquier 401/403) significa que la sesion YA no existe: se
 * anota `hecha`, no `fallida` (antes nunca convergia). Un fallo transitorio sigue anotando `fallida`, y en el PRIMER
 * intento (sesion recien reautenticada) un 401 no se interpreta como revocada. La base (20260930101500) impide ademas
 * que un `fallida` tardio pise un `hecha` (dbtest Slice1SessionRevocationMonotonicMigrationTest).
 */
class AccountDeletionRevocationConvergenceTest {
    private val original = "2026-09-30T11:00:00.654321+00:00"

    private fun env(alreadyPending: Boolean, stored: String?, logoutFailure: Exception?) = CanonicalTestEnv().apply {
        status = if (alreadyPending) "eliminacion_pendiente" else "activa"
        auth.logoutFailure = logoutFailure
        upstream.onRpc = { function, _ ->
            when (function) {
                "app_solicitar_eliminacion_cuenta" ->
                    rpcOk("""[{"estado_cuenta":"eliminacion_pendiente","solicitada_en":"$original","ya_pendiente":$alreadyPending}]""")
                "app_registrar_revocacion_sesiones" -> rpcOk("""{"revocacionSesiones":"hecha"}""")
                else -> error("RPC inesperado: $function")
            }
        }
        upstream.onGet = { path ->
            if ("solicitudes_eliminacion_cuenta" in path && stored != null) """[{"revocacion_sesiones":"$stored"}]""" else "[]"
        }
    }

    private fun recorded(e: CanonicalTestEnv) =
        e.upstream.lastArgs("app_registrar_revocacion_sesiones")["p_resultado"]!!.jsonPrimitive.content

    @Test
    fun aRetryWhoseLogoutFindsTheSessionAlreadyGoneRecordsHecha() = testApplication {
        // MASTER3-AUTH-001: SOLO la evidencia explicita del proveedor (session_not_found) cuenta como "ya revocada".
        val e = env(alreadyPending = true, stored = "fallida", logoutFailure = OposiBotsAuthProblem.sessionGone())
        application { e.mount(this) }
        assertEquals(HttpStatusCode.Accepted, deleteMe("""{"confirmation":"ELIMINAR"}""").status)
        assertEquals(listOf("logout"), e.auth.calls.toList())
        assertEquals("hecha", recorded(e), "sesion ya revocada = hecha")
    }

    /**
     * MASTER3-AUTH-001 (cambio DELIBERADO de esta prueba): antes afirmaba `hecha` para un 401/403 generico
     * (`authentication()`, `FORBIDDEN`). Eso era el defecto: un 400/422/apikey mala acaba mapeado a 401 y no prueba que
     * la sesion este revocada. Un 401/403 sin la marca `upstreamSessionGone` sigue siendo `fallida`.
     */
    @Test
    fun aRetryWithAGeneric401Or403WithoutEvidenceStaysFallida() {
        listOf<Exception>(
            OposiBotsAuthProblem.authentication(),
            OposiBotsAuthProblem.accessDenied(),
            CanonicalErrorCode.FORBIDDEN.exception(),
            CanonicalErrorCode.UNAUTHENTICATED.exception(),
        ).forEach { generic ->
            testApplication {
                val e = env(alreadyPending = true, stored = "fallida", logoutFailure = generic)
                application { e.mount(this) }
                assertEquals(HttpStatusCode.Accepted, deleteMe("""{"confirmation":"ELIMINAR"}""").status)
                assertEquals("fallida", recorded(e), "${generic::class.simpleName}/${generic.message}: sin evidencia = fallida")
            }
        }
    }

    @Test
    fun onTheFirstAttemptEvenAnExplicitSessionNotFoundIsNotTakenAsRevoked() = testApplication {
        val e = env(alreadyPending = false, stored = null, logoutFailure = OposiBotsAuthProblem.sessionGone())
        application { e.mount(this) }
        assertEquals(HttpStatusCode.Accepted, deleteMe("""{"confirmation":"ELIMINAR","currentPassword":"safe-password-12"}""").status)
        assertEquals("fallida", recorded(e))
    }

    @Test
    fun aTransientLogoutFailureOnRetryStillRecordsFallida() = testApplication {
        val e = env(alreadyPending = true, stored = "fallida", logoutFailure = OposiBotsAuthProblem.unavailable())
        application { e.mount(this) }
        assertEquals(HttpStatusCode.Accepted, deleteMe("""{"confirmation":"ELIMINAR"}""").status)
        assertEquals("fallida", recorded(e))
    }

    @Test
    fun onTheFirstAttemptA401IsNotTakenAsAlreadyRevoked() = testApplication {
        val e = env(alreadyPending = false, stored = null, logoutFailure = OposiBotsAuthProblem.authentication())
        application { e.mount(this) }
        val response = deleteMe("""{"confirmation":"ELIMINAR","currentPassword":"safe-password-12"}""")
        assertEquals(HttpStatusCode.Accepted, response.status)
        assertEquals("fallida", recorded(e))
    }

    private suspend fun ApplicationTestBuilder.deleteMe(body: String) = client.request("/api/v1/app/me") {
        method = HttpMethod.Delete
        header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
