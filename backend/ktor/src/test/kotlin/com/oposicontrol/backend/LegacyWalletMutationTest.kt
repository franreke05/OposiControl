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
            gateway().appendTicketAdjustment(
                userId = "42",
                input = AdminTicketAdjustmentDto(amount = 50.0, reason = "recompensa manual"),
            )
        }

        assertEquals("WALLET_LEGACY_ROUTE_DISABLED", failure.errorCode)
        assertEquals(HttpStatusCode.Gone, failure.status)
    }

    @Test
    fun rejectionMessagePointsToTheCanonicalAtomicRoute() {
        val failure = assertFailsWith<BackendDomainException> {
            gateway().appendTicketAdjustment(
                userId = "42",
                input = AdminTicketAdjustmentDto(amount = -10.0, reason = "correccion"),
            )
        }

        assertTrue(
            failure.message.contains("/api/v1/admin/tickets"),
            "El error debe dirigir al escritor canonico atomico, no solo negar: ${failure.message}",
        )
    }

    /**
     * El rechazo no puede depender de que el importe sea valido: un ajuste de 0 o
     * sin motivo tambien debe morir en la puerta legacy, no en una validacion que
     * sugiera que con otros datos la ruta si escribiria.
     */
    @Test
    fun rejectionDoesNotDependOnPayloadValidity() {
        val failure = assertFailsWith<BackendDomainException> {
            gateway().appendTicketAdjustment(
                userId = "42",
                input = AdminTicketAdjustmentDto(amount = 0.0, reason = ""),
            )
        }

        assertEquals("WALLET_LEGACY_ROUTE_DISABLED", failure.errorCode)
    }
}
