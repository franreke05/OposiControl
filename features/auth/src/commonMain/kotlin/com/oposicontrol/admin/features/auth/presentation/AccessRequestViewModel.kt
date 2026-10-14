package com.oposicontrol.admin.features.auth.presentation

import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AccessRequestCodeOutcome
import com.oposicontrol.admin.domain.model.AccessRequestRules
import com.oposicontrol.admin.domain.model.AccessRequestSubmission
import com.oposicontrol.admin.domain.repository.AccessRequestsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface AccessRequestIntent {
    data class UpdateName(val value: String) : AccessRequestIntent
    data class UpdateEmail(val value: String) : AccessRequestIntent
    data class UpdatePassword(val value: String) : AccessRequestIntent
    data class UpdatePasswordConfirmation(val value: String) : AccessRequestIntent
    data class UpdateReason(val value: String) : AccessRequestIntent
    data object Submit : AccessRequestIntent

    /** Paso «verifica tu correo»: solo cifras, como mucho 6. */
    data class UpdateCode(val value: String) : AccessRequestIntent
    data object Verify : AccessRequestIntent
    data object ResendCode : AccessRequestIntent

    /** Del paso de verificacion al formulario, con el correo ya escrito (para enviar otra solicitud si caduco). */
    data object BackToForm : AccessRequestIntent

    /** Vacia el formulario (al volver al inicio de sesion o para enviar otra solicitud). */
    data object Reset : AccessRequestIntent
}

enum class AccessRequestField { NAME, EMAIL, PASSWORD, PASSWORD_CONFIRMATION, REASON }

/** Las tres caras de «Solicitar acceso»: el formulario, «verifica tu correo» y «correo verificado». */
enum class AccessRequestStep { FORM, VERIFY, VERIFIED }

/** Lo ultimo que paso en el paso de verificacion. Los textos los pone la pantalla. */
sealed interface AccessVerificationFeedback {
    /** El servidor no dice por que (codigo mal, caducado, usado, solicitud caducada...): un unico mensaje. */
    data object InvalidCode : AccessVerificationFeedback
    data object InvalidEmail : AccessVerificationFeedback
    data class RateLimited(val retryAfterSeconds: Long?) : AccessVerificationFeedback

    /** 503: el servicio de correo / auth no responde. */
    data object Unavailable : AccessVerificationFeedback
    data class Failed(val error: AppError) : AccessVerificationFeedback
    data object CodeResent : AccessVerificationFeedback
}

/**
 * Estado del formulario «Solicitar acceso».
 *
 * La contrasena se escribe en el paso de VERIFICACION (revision de seguridad 2026-10-14) y vive aqui SOLO mientras se
 * escribe: al verificar se saca del estado ANTES de la llamada de red y no vuelve, salga bien o mal. `toString` no la imprime (un estado se acaba registrando tarde o temprano), ni el codigo.
 */
data class AccessRequestState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val passwordConfirmation: String = "",
    val reason: String = "",
    val fieldErrors: Map<AccessRequestField, String> = emptyMap(),
    /** `Success` = solicitud enviada (el paso pasa a [AccessRequestStep.VERIFY]). */
    val submitState: UiAsync<Unit> = UiAsync.Idle,
    val step: AccessRequestStep = AccessRequestStep.FORM,
    /** El correo CANONICO con el que se envio la solicitud: a el va el codigo y con el se verifica. */
    val verificationEmail: String = "",
    val code: String = "",
    val codeError: String? = null,
    val verifying: Boolean = false,
    val resending: Boolean = false,
    /** Segundos hasta poder pedir otro codigo (0 = ya se puede). */
    val resendCooldownSeconds: Int = 0,
    /** Codigos rechazados seguidos: a partir de [EXPIRY_HINT_AFTER_FAILURES] la pantalla sugiere enviar otra solicitud. */
    val failedVerifications: Int = 0,
    val verificationFeedback: AccessVerificationFeedback? = null,
) {
    val isSubmitting: Boolean get() = submitState is UiAsync.Loading
    val isSent: Boolean get() = submitState is UiAsync.Success
    val canVerify: Boolean get() = step == AccessRequestStep.VERIFY && !verifying && AccessRequestRules.isValidVerificationCode(code)
    val canResend: Boolean get() = step == AccessRequestStep.VERIFY && !resending && !verifying && resendCooldownSeconds == 0
    val showExpiryHint: Boolean get() = failedVerifications >= EXPIRY_HINT_AFTER_FAILURES

    override fun toString(): String =
        "AccessRequestState(name=$name, email=$email, password=<${password.length}>, reason=<${reason.length}>, " +
            "fieldErrors=${fieldErrors.keys}, submitState=$submitState, step=$step, code=<${code.length}>, " +
            "verifying=$verifying, resending=$resending, cooldown=$resendCooldownSeconds, failed=$failedVerifications, " +
            "feedback=$verificationFeedback)"

    companion object {
        const val RESEND_COOLDOWN_SECONDS = 60
        const val EXPIRY_HINT_AFTER_FAILURES = 2
    }
}

internal const val PASSWORD_RULE_MESSAGE = "La contraseña debe tener entre 12 y 72 caracteres, sin tildes, eñes ni emojis."

/** Validacion del formulario (paso 1). Funcion pura: los mismos limites que el servidor ([AccessRequestRules]). */
internal fun validateAccessRequest(state: AccessRequestState): Map<AccessRequestField, String> = buildMap {
    val name = state.name.trim()
    when {
        name.isEmpty() -> put(AccessRequestField.NAME, "Escribe tu nombre.")
        name.length > AccessRequestRules.NAME_MAX -> put(AccessRequestField.NAME, "El nombre no puede pasar de ${AccessRequestRules.NAME_MAX} caracteres.")
    }
    val email = AccessRequestRules.canonicalEmail(state.email)
    when {
        email == "" -> put(AccessRequestField.EMAIL, "Escribe tu correo.")
        email == null || !AccessRequestRules.isValidEmail(email) -> put(AccessRequestField.EMAIL, "El correo no tiene un formato válido.")
    }
    val reason = state.reason.trim()
    when {
        reason.isEmpty() -> put(AccessRequestField.REASON, "Cuenta para qué necesitas el acceso.")
        reason.length > AccessRequestRules.REASON_MAX -> put(AccessRequestField.REASON, "El motivo no puede pasar de ${AccessRequestRules.REASON_MAX} caracteres.")
    }
}

/**
 * Validacion de la contrasena del paso de verificacion (revision de seguridad 2026-10-14: se elige al verificar, no al pedir
 * el acceso). Funcion pura: la regla de contrasena nueva del servidor ([AccessRequestRules.isValidNewPassword]).
 */
internal fun validateAccessPassword(state: AccessRequestState): Map<AccessRequestField, String> = buildMap {
    when {
        state.password.isEmpty() -> put(AccessRequestField.PASSWORD, "Elige una contraseña.")
        !AccessRequestRules.isValidNewPassword(state.password) -> put(AccessRequestField.PASSWORD, PASSWORD_RULE_MESSAGE)
    }
    if (state.passwordConfirmation != state.password) {
        put(AccessRequestField.PASSWORD_CONFIRMATION, "Las dos contraseñas no coinciden.")
    }
}

/**
 * Formulario publico «Solicitar acceso» de la pantalla de acceso, y su paso de verificacion del correo.
 *
 * El servidor responde lo mismo exista ya el correo o no, y esta pantalla tambien: tras un envio aceptado pide el codigo
 * de 6 cifras que llega al correo. Verificado el correo, un superadministrador revisa la solicitud.
 */
class AccessRequestViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: AccessRequestsRepository,
) : MviViewModel<AccessRequestState, AccessRequestIntent>(
    initialState = AccessRequestState(),
    dispatcherProvider = dispatcherProvider,
) {
    private var cooldownJob: Job? = null

    override suspend fun handleIntent(intent: AccessRequestIntent) {
        when (intent) {
            is AccessRequestIntent.UpdateName -> edit(AccessRequestField.NAME) { it.copy(name = intent.value) }
            is AccessRequestIntent.UpdateEmail -> edit(AccessRequestField.EMAIL) { it.copy(email = intent.value) }
            is AccessRequestIntent.UpdatePassword ->
                editVerifyPassword(AccessRequestField.PASSWORD, AccessRequestField.PASSWORD_CONFIRMATION) { it.copy(password = intent.value) }
            is AccessRequestIntent.UpdatePasswordConfirmation ->
                editVerifyPassword(AccessRequestField.PASSWORD_CONFIRMATION) { it.copy(passwordConfirmation = intent.value) }
            is AccessRequestIntent.UpdateReason -> edit(AccessRequestField.REASON) { it.copy(reason = intent.value) }
            AccessRequestIntent.Submit -> submit()
            is AccessRequestIntent.UpdateCode -> updateState { state ->
                if (state.step != AccessRequestStep.VERIFY || state.verifying) return@updateState state
                val digits = intent.value.filter { it in '0'..'9' }.take(AccessRequestRules.VERIFICATION_CODE_LENGTH)
                state.copy(code = digits, codeError = null)
            }
            AccessRequestIntent.Verify -> verify()
            AccessRequestIntent.ResendCode -> resend()
            AccessRequestIntent.BackToForm -> {
                val state = currentState()
                if (state.step == AccessRequestStep.VERIFY && !state.verifying && !state.resending) {
                    stopCooldown()
                    updateState { AccessRequestState(email = state.verificationEmail) }
                }
            }
            AccessRequestIntent.Reset -> {
                val state = currentState()
                if (!state.isSubmitting && !state.verifying && !state.resending) {
                    stopCooldown()
                    updateState { AccessRequestState() }
                }
            }
        }
    }

    /** Un cambio limpia el error de ESE campo y el error del envio anterior; el resto de avisos se queda. */
    private fun edit(vararg fields: AccessRequestField, change: (AccessRequestState) -> AccessRequestState) {
        updateState { state ->
            if (state.isSubmitting || state.step != AccessRequestStep.FORM) return@updateState state
            change(state).copy(
                fieldErrors = state.fieldErrors - fields.toSet(),
                submitState = if (state.submitState is UiAsync.Error) UiAsync.Idle else state.submitState,
            )
        }
    }

    /** La contrasena se escribe en el paso de VERIFICACION. Un cambio limpia el error de ESE campo. */
    private fun editVerifyPassword(vararg fields: AccessRequestField, change: (AccessRequestState) -> AccessRequestState) {
        updateState { state ->
            if (state.step != AccessRequestStep.VERIFY || state.verifying) return@updateState state
            change(state).copy(fieldErrors = state.fieldErrors - fields.toSet())
        }
    }

    private suspend fun submit() {
        val state = currentState()
        if (state.isSubmitting || state.step != AccessRequestStep.FORM) return
        val errors = validateAccessRequest(state)
        if (errors.isNotEmpty()) {
            updateState { it.copy(fieldErrors = errors) }
            return
        }
        // Validado arriba: el correo tiene forma canonica valida. Viaja canonico, como lo guarda el servidor.
        val email = AccessRequestRules.canonicalEmail(state.email).orEmpty()
        val submission = AccessRequestSubmission(
            name = state.name.trim(),
            email = email,
            reason = state.reason.trim(),
        )
        updateState { it.copy(fieldErrors = emptyMap(), submitState = UiAsync.Loading) }
        when (val result = repository.submitRequest(submission)) {
            // Enviada: no queda nada del formulario en memoria salvo el correo, al que va el codigo.
            is AppResult.Success -> {
                updateState {
                    AccessRequestState(
                        submitState = UiAsync.Success(Unit),
                        step = AccessRequestStep.VERIFY,
                        verificationEmail = email,
                    )
                }
                // El correo con el codigo acaba de salir: pedir otro al instante no sirve de nada.
                startCooldown(AccessRequestState.RESEND_COOLDOWN_SECONDS)
            }
            // Los campos se conservan para no reescribirlos.
            is AppResult.Failure -> updateState { it.copy(submitState = UiAsync.Error(result.error)) }
        }
    }

    private suspend fun verify() {
        val state = currentState()
        if (state.step != AccessRequestStep.VERIFY || state.verifying) return
        val codeError = if (AccessRequestRules.isValidVerificationCode(state.code)) null else CODE_INCOMPLETE_MESSAGE
        val passwordErrors = validateAccessPassword(state)
        if (codeError != null || passwordErrors.isNotEmpty()) {
            updateState { it.copy(codeError = codeError, fieldErrors = passwordErrors) }
            return
        }
        val password = state.password
        // La contrasena sale del estado ANTES de la llamada: desde aqui solo existe en `password`, que muere con esta funcion.
        // Si la verificacion falla hay que volver a escribirla (el campo queda vacio y lo dice su validacion).
        updateState {
            it.copy(
                verifying = true,
                codeError = null,
                fieldErrors = emptyMap(),
                password = "",
                passwordConfirmation = "",
                verificationFeedback = null,
            )
        }
        when (val result = repository.verifyEmail(state.verificationEmail, state.code, password)) {
            is AppResult.Success -> when (val outcome = result.value) {
                AccessRequestCodeOutcome.Accepted -> {
                    stopCooldown()
                    updateState { AccessRequestState(step = AccessRequestStep.VERIFIED, verificationEmail = it.verificationEmail) }
                }
                // Un codigo rechazado no se deja en el campo: hay que escribir el bueno.
                AccessRequestCodeOutcome.InvalidCode -> updateState {
                    it.copy(
                        verifying = false,
                        code = "",
                        failedVerifications = it.failedVerifications + 1,
                        verificationFeedback = AccessVerificationFeedback.InvalidCode,
                    )
                }
                // El servidor rechazo la contrasena con su regla (no dice nada del codigo): error de ESE campo, el codigo se queda.
                AccessRequestCodeOutcome.InvalidPassword -> updateState {
                    it.copy(verifying = false, fieldErrors = mapOf(AccessRequestField.PASSWORD to PASSWORD_RULE_MESSAGE))
                }
                else -> updateState { it.copy(verifying = false, verificationFeedback = outcome.toFeedback()) }
            }
            is AppResult.Failure -> updateState { it.copy(verifying = false, verificationFeedback = result.error.toFeedback()) }
        }
    }

    private suspend fun resend() {
        val state = currentState()
        if (!state.canResend) return
        updateState { it.copy(resending = true, verificationFeedback = null) }
        when (val result = repository.resendCode(state.verificationEmail)) {
            is AppResult.Success -> when (val outcome = result.value) {
                AccessRequestCodeOutcome.Accepted -> {
                    updateState { it.copy(resending = false, verificationFeedback = AccessVerificationFeedback.CodeResent) }
                    startCooldown(AccessRequestState.RESEND_COOLDOWN_SECONDS)
                }
                is AccessRequestCodeOutcome.RateLimited -> {
                    updateState { it.copy(resending = false, verificationFeedback = outcome.toFeedback()) }
                    // Lo que diga el servidor, y nunca menos que el enfriamiento normal.
                    val wait = (outcome.retryAfterSeconds ?: 0L)
                        .coerceIn(AccessRequestState.RESEND_COOLDOWN_SECONDS.toLong(), MAX_COOLDOWN_SECONDS)
                    startCooldown(wait.toInt())
                }
                else -> updateState { it.copy(resending = false, verificationFeedback = outcome.toFeedback()) }
            }
            is AppResult.Failure -> updateState { it.copy(resending = false, verificationFeedback = result.error.toFeedback()) }
        }
    }

    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        updateState { it.copy(resendCooldownSeconds = seconds) }
        cooldownJob = scope.launch {
            while (currentState().resendCooldownSeconds > 0) {
                delay(1_000)
                updateState { it.copy(resendCooldownSeconds = (it.resendCooldownSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    private fun stopCooldown() {
        cooldownJob?.cancel()
        cooldownJob = null
    }

    private fun AccessRequestCodeOutcome.toFeedback(): AccessVerificationFeedback? = when (this) {
        AccessRequestCodeOutcome.Accepted -> null
        AccessRequestCodeOutcome.InvalidCode -> AccessVerificationFeedback.InvalidCode
        AccessRequestCodeOutcome.InvalidEmail -> AccessVerificationFeedback.InvalidEmail
        // Solo lo devuelve verificar, que lo trata aparte (error del campo); aqui no hay aviso que poner.
        AccessRequestCodeOutcome.InvalidPassword -> null
        is AccessRequestCodeOutcome.RateLimited -> AccessVerificationFeedback.RateLimited(retryAfterSeconds)
    }

    private fun AppError.toFeedback(): AccessVerificationFeedback =
        if (this is AppError.Network && code == SERVICE_UNAVAILABLE) AccessVerificationFeedback.Unavailable else AccessVerificationFeedback.Failed(this)

    private companion object {
        const val SERVICE_UNAVAILABLE = 503

        /** Tope del enfriamiento local: un `Retry-After` desmesurado no bloquea el boton durante horas. */
        const val MAX_COOLDOWN_SECONDS = 15L * 60L
    }
}

internal const val CODE_INCOMPLETE_MESSAGE = "Escribe las 6 cifras del código."
