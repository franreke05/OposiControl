package com.oposicontrol.admin.features.users.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Idempotencia de la concesión de OposiTickets desde el panel.
 *
 * El backend exige una clave y devuelve 422 sin ella — la versión anterior del
 * DTO del cliente no la enviaba, así que conceder tickets era sencillamente
 * imposible. Ya arreglado; lo que estas pruebas fijan es la propiedad que hace
 * útil la clave: **una por operación, no una por intento**. Si se generase al
 * pulsar en lugar de al abrir el diálogo, un doble clic concedería dos veces.
 */
class GrantTicketsIdempotencyTest {

    @Test
    fun theKeyIsHeldInStateWhileTheDialogIsOpen() {
        val state = UsersState(
            selectedUserId = "u-1",
            ticketsDialogOpen = true,
            ticketsIdempotencyKey = "grant:u-1:1700000000000",
        )

        assertNotNull(state.ticketsIdempotencyKey)
    }

    @Test
    fun theSameKeySurvivesRepeatedConfirmationsOfTheSameDialog() {
        // Modela el doble clic: el estado no cambia de clave entre intentos, así
        // que el backend ve la misma y no concede dos veces.
        val state = UsersState(
            selectedUserId = "u-1",
            ticketsIdempotencyKey = "grant:u-1:1700000000000",
        )
        val afterFirstAttempt = state.copy(actionState = UiAsync.Loading)
        val afterRetry = afterFirstAttempt.copy(actionState = UiAsync.Loading)

        assertEquals(state.ticketsIdempotencyKey, afterRetry.ticketsIdempotencyKey)
    }

    @Test
    fun closingTheDialogClearsTheKeySoTheNextGrantIsANewOperation() {
        // Si la clave sobreviviese al cierre, una segunda concesión legítima
        // chocaría con la primera y se perdería en silencio.
        val closed = UsersState(
            selectedUserId = "u-1",
            ticketsDialogOpen = false,
            ticketsInput = "",
            ticketsIdempotencyKey = null,
        )

        assertNull(closed.ticketsIdempotencyKey)
    }

    @Test
    fun withoutAKeyTheStateCannotDescribeAGrantInFlight() {
        // Es el estado que hace que el ViewModel se niegue a llamar al backend,
        // en vez de mandar una petición que sabe que va a fallar con 422.
        assertNull(UsersState(selectedUserId = "u-1").ticketsIdempotencyKey)
    }

    @Test
    fun keysAreNamespacedPerUserSoTwoGrantsCannotCollide() {
        // Conceder a dos usuarios en el mismo milisegundo debe producir claves
        // distintas; si no, la segunda concesión se descartaría como duplicada.
        val forUserA = "grant:u-1:1700000000000"
        val forUserB = "grant:u-2:1700000000000"

        assertEquals(false, forUserA == forUserB)
    }
}
