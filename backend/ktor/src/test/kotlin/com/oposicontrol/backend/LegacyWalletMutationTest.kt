package com.oposicontrol.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * La superficie legacy `POST /admin/ticket-wallets/{userId}/transactions` hacia
 * una mutacion monetaria NO atomica sobre `cartera_tickets`:
 *
 *     val newBalance = (current?.saldo ?: 0.0) + input.amount   // read-modify-write
 *     PATCH /cartera_tickets  ... ; POST /transaccion_ticket ...  // dos escrituras sueltas
 *
 * Eso permite lost updates bajo concurrencia y deja saldo y ledger divergentes si
 * la segunda llamada falla. El escritor canonico (`admin_grant_tickets`) ya es
 * atomico, idempotente y seguro ante concurrencia, y vive en
 * `/api/v1/admin/tickets/{usuarioId}/transacciones`.
 *
 * La ruta legacy debe cerrarse en falso (fail closed) ANTES de tocar Supabase.
 */
class LegacyWalletMutationTest {

    /**
     * URL deliberadamente no enrutable: si el codigo hiciera cualquier llamada
     * upstream antes de rechazar, este test fallaria con un error de conexion
     * en lugar de con el rechazo de dominio. Es lo que prueba que no hay I/O.
     */
    private fun gateway() = SupabaseAdminGateway(
        supabaseUrl = "http://127.0.0.1:1",
        serviceRoleKey = "test-service-role-key",
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun legacyTicketAdjustment_isRejectedBeforeAnyUpstreamCall() {
        val failure = assertFailsWith<BackendDomainException> {
            gateway().rejectLegacyTicketAdjustment(userId = "42")
        }

        assertEquals("WALLET_LEGACY_ROUTE_DISABLED", failure.errorCode)
        assertEquals(HttpStatusCode.Gone, failure.status)
    }

    @Test
    fun rejectionMessagePointsToTheCanonicalAtomicRoute() {
        val failure = assertFailsWith<BackendDomainException> {
            gateway().rejectLegacyTicketAdjustment(userId = "42")
        }

        assertTrue(
            failure.message.contains("/api/v1/admin/tickets"),
            "El error debe dirigir al escritor canonico atomico, no solo negar: ${failure.message}",
        )
    }

    /**
     * El rechazo no puede depender del cuerpo de la peticion.
     *
     * La ruta ya ni siquiera lo lee: leerlo primero hacia que un JSON mal formado
     * muriese en BadRequestException con un 500, en lugar del 410 previsto. El
     * rechazo ocurre por la ruta que es, no por lo que se envie.
     */
    @Test
    fun rejectionDoesNotDependOnAnyRequestBody() {
        val failure = assertFailsWith<BackendDomainException> {
            gateway().rejectLegacyTicketAdjustment(userId = "cualquier-cosa")
        }

        assertEquals("WALLET_LEGACY_ROUTE_DISABLED", failure.errorCode)
    }
}
