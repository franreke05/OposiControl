package com.oposicontrol.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MASTER2-OBS-009: una repeticion idempotente de `admin_grant_tickets` (misma clave -> mismo transaction_id, sin
 * volver a acreditar) escribia OTRA fila `tickets_grant`, indistinguible de una concesion nueva. Ahora la fila lleva
 * el transactionId y no se duplica si ya existe una para esa transaccion.
 */
class AdminGrantTicketsReplayAuditTest {
    private val user = "6b6b6b6b-0000-4000-8000-000000000001"
    private val tx = "7c7c7c7c-0000-4000-8000-000000000002"

    private class Stub {
        val sent = CopyOnWriteArrayList<HttpRequest>()
        val audited = CopyOnWriteArrayList<String>()
        var auditLookupStatus = 200
    }

    private fun gateway(stub: Stub) = AdminV1Gateway.forTesting(
        supabaseUrl = "https://stub.supabase.test",
        serviceRoleKey = "stub-key",
        json = Json { ignoreUnknownKeys = true },
        client = V1StubHttpClient({ request ->
            stub.sent += request
            if (request.method() == "POST" && request.uri().path == "/rest/v1/auditoria_log") stub.audited += readRequestBody(request)
        }) { uri ->
            when {
                "/rest/v1/rpc/admin_grant_tickets" in uri -> V1StubResponse(200, """[{"transaction_id":"$tx","saldo_resultante":15}]""")
                "/rest/v1/auditoria_log?select=id" in uri -> when {
                    stub.auditLookupStatus != 200 -> V1StubResponse(stub.auditLookupStatus, """{"message":"down"}""")
                    stub.audited.isEmpty() -> V1StubResponse(200, "[]")
                    else -> V1StubResponse(200, """[{"id":"x"}]""")
                }
                "/rest/v1/auditoria_log" in uri -> V1StubResponse(201, "")
                else -> V1StubResponse(200, "[]")
            }
        },
    )

    @Test
    fun anIdempotentReplayDoesNotWriteASecondGrantAuditRow() {
        val stub = Stub()
        val g = gateway(stub)
        assertEquals(15, g.grantTickets(user, 5, "premio", "ajuste", "key-1"))
        assertEquals(15, g.grantTickets(user, 5, "premio", "ajuste", "key-1"))

        assertEquals(1, stub.audited.size, "una sola fila por transaccion: ${stub.audited}")
        val row = Json.parseToJsonElement(stub.audited.single()).jsonArray.single().jsonObject
        assertEquals("tickets_grant", row["accion"]!!.jsonPrimitive.content)
        assertEquals(tx, row["despues"]!!.jsonObject["transactionId"]!!.jsonPrimitive.content, "fila identificable por transaccion")

        val lookup = stub.sent.first { "select=id" in it.uri().toString() }.uri().toString()
        assertTrue("accion=eq.tickets_grant" in lookup && "registro_id=eq.$user" in lookup, lookup)
        assertTrue("despues-%3E%3EtransactionId=eq.$tx" in lookup, lookup)
    }

    @Test
    fun anUnreadableAuditLookupStillWritesTheRowNeverSkipsIt() {
        val stub = Stub().apply { auditLookupStatus = 503 }
        val g = gateway(stub)
        g.grantTickets(user, 5, "premio", "ajuste", "key-1")
        assertEquals(1, stub.audited.size, "sin poder comprobar, se audita (fila identificable por transactionId)")
    }
}
