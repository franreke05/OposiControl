package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AccessRequestRules
import com.oposicontrol.admin.domain.model.AccessRequestSubmission
import com.oposicontrol.admin.domain.repository.AccessRequestsRepository

sealed interface AccessRequestIntent {
    data class UpdateName(val value: String) : AccessRequestIntent
    data class UpdateEmail(val value: String) : AccessRequestIntent
    data class UpdatePassword(val value: String) : AccessRequestIntent
    data class UpdatePasswordConfirmation(val value: String) : AccessRequestIntent
    data class UpdateReason(val value: String) : AccessRequestIntent
    data object Submit : AccessRequestIntent

    /** Vacia el formulario (al volver al inicio de sesion o para enviar otra solicitud). */
    data object Reset : AccessRequestIntent
}

enum class AccessRequestField { NAME, EMAIL, PASSWORD, PASSWORD_CONFIRMATION, REASON }

/**
 * Estado del formulario «Solicitar acceso».
 *
 * La contrasena vive aqui SOLO mientras se escribe: al enviar se saca del estado ANTES de la llamada de red y no vuelve,
 * salga bien o mal. `toString` no la imprime (un estado se acaba registrando tarde o temprano).
 */
data class AccessRequestState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val passwordConfirmation: String = "",
    val reason: String = "",
    val fieldErrors: Map<AccessRequestField, String> = emptyMap(),
    /** `Success` = solicitud enviada: la pantalla ensena el aviso de «pendiente de aprobacion» en lugar del formulario. */
    val submitState: UiAsync<Unit> = UiAsync.Idle,
) {
    val isSubmitting: Boolean get() = submitState is UiAsync.Loading
    val isSent: Boolean get() = submitState is UiAsync.Success

    override fun toString(): String =
        "AccessRequestState(name=$name, email=$email, password=<${password.length}>, reason=<${reason.length}>, " +
            "fieldErrors=${fieldErrors.keys}, submitState=$submitState)"
}

/** Validacion del formulario. Funcion pura: los mismos limites que el servidor ([AccessRequestRules]). */
internal fun validateAccessRequest(state: AccessRequestState): Map<AccessRequestField, String> = buildMap {
    val name = state.name.trim()
    when {
        name.isEmpty() -> put(AccessRequestField.NAME, "Escribe tu nombre.")
        name.length > AccessRequestRules.NAME_MAX -> put(AccessRequestField.NAME, "El nombre no puede pasar de ${AccessRequestRules.NAME_MAX} caracteres.")
    }
    val email = state.email.trim()
    when {
        email.isEmpty() -> put(AccessRequestField.EMAIL, "Escribe tu correo.")
        !AccessRequestRules.isValidEmail(email) -> put(AccessRequestField.EMAIL, "El correo no tiene un formato válido.")
    }
    when {
        state.password.isEmpty() -> put(AccessRequestField.PASSWORD, "Elige una contraseña.")
        state.password.length !in AccessRequestRules.PASSWORD_MIN..AccessRequestRules.PASSWORD_MAX -> put(
            AccessRequestField.PASSWORD,
            "La contraseña debe tener entre ${AccessRequestRules.PASSWORD_MIN} y ${AccessRequestRules.PASSWORD_MAX} caracteres.",
        )
    }
    if (state.passwordConfirmation != state.password) {
        put(AccessRequestField.PASSWORD_CONFIRMATION, "Las dos contraseñas no coinciden.")
    }
    val reason = state.reason.trim()
    when {
        reason.isEmpty() -> put(AccessRequestField.REASON, "Cuenta para qué necesitas el acceso.")
        reason.length > AccessRequestRules.REASON_MAX -> put(AccessRequestField.REASON, "El motivo no puede pasar de ${AccessRequestRules.REASON_MAX} caracteres.")
    }
}

/**
 * Formulario publico «Solicitar acceso» de la pantalla de acceso.
 *
 * El servidor responde lo mismo exista ya el correo o no, y esta pantalla tambien: tras un envio aceptado solo dice que
 * la solicitud esta enviada y que un superadministrador tiene que aprobarla.
 */
class AccessRequestViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AccessRequestsRepository,
) : MviViewModel<AccessRequestState, AccessRequestIntent>(
    initialState = AccessRequestState(),
    dispatcherProvider = dispatcherProvider,
) {
    override suspend fun handleIntent(intent: AccessRequestIntent) {
        when (intent) {
            is AccessRequestIntent.UpdateName -> edit(AccessRequestField.NAME) { it.copy(name = intent.value) }
            is AccessRequestIntent.UpdateEmail -> edit(AccessRequestField.EMAIL) { it.copy(email = intent.value) }
            is AccessRequestIntent.UpdatePassword -> edit(AccessRequestField.PASSWORD, AccessRequestField.PASSWORD_CONFIRMATION) { it.copy(password = intent.value) }
            is AccessRequestIntent.UpdatePasswordConfirmation ->
                edit(AccessRequestField.PASSWORD_CONFIRMATION) { it.copy(passwordConfirmation = intent.value) }
            is AccessRequestIntent.UpdateReason -> edit(AccessRequestField.REASON) { it.copy(reason = intent.value) }
            AccessRequestIntent.Submit -> submit()
            AccessRequestIntent.Reset -> if (!currentState().isSubmitting) updateState { AccessRequestState() }
        }
    }

    /** Un cambio limpia el error de ESE campo y el error del envio anterior; el resto de avisos se queda. */
    private fun edit(vararg fields: AccessRequestField, change: (AccessRequestState) -> AccessRequestState) {
        updateState { state ->
            if (state.isSubmitting || state.isSent) return@updateState state
            change(state).copy(
                fieldErrors = state.fieldErrors - fields.toSet(),
                submitState = if (state.submitState is UiAsync.Error) UiAsync.Idle else state.submitState,
            )
        }
    }

    private suspend fun submit() {
        val state = currentState()
        if (state.isSubmitting || state.isSent) return
        val errors = validateAccessRequest(state)
        if (errors.isNotEmpty()) {
            updateState { it.copy(fieldErrors = errors) }
            return
        }
        val submission = AccessRequestSubmission(
            name = state.name.trim(),
            email = state.email.trim(),
            password = state.password,
            reason = state.reason.trim(),
        )
        // La contrasena sale del estado ANTES de la llamada: desde aqui solo existe en `submission`, que muere con esta funcion.
        updateState { it.copy(password = "", passwordConfirmation = "", fieldErrors = emptyMap(), submitState = UiAsync.Loading) }
        when (val result = repository.submitRequest(submission)) {
            // Enviada: no queda nada del formulario en memoria.
            is AppResult.Success -> updateState { AccessRequestState(submitState = UiAsync.Success(Unit)) }
            // El resto de campos se conserva para no reescribirlos; la contrasena hay que volver a teclearla.
            is AppResult.Failure -> updateState { it.copy(submitState = UiAsync.Error(result.error)) }
        }
    }
}
