package com.oposicontrol.admin.features.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.AccessRequestRules

internal const val ACCESS_REQUEST_FORM_TAG: String = "access-request-form"
internal const val ACCESS_REQUEST_VERIFY_TAG: String = "access-request-verify"
internal const val ACCESS_REQUEST_VERIFIED_TAG: String = "access-request-verified"

internal const val ACCESS_REQUEST_VERIFY_TITLE = "Verifica tu correo"

/** Revision de seguridad 2026-10-14: la contrasena se elige al verificar, cuando el buzon ya esta demostrado. */
internal const val ACCESS_REQUEST_CHOOSE_PASSWORD = "Elige la contraseña con la que entrarás al panel."

/** El correo puede traer un enlace (plantilla de GoTrue): solo vale el codigo (DEBT-118). */
internal const val ACCESS_REQUEST_NO_LINKS_NOTE = "No pulses ningún enlace del correo: usa solo el código."
internal const val ACCESS_REQUEST_VERIFIED_TITLE = "Correo verificado. Un superadministrador revisará tu solicitud."
internal const val ACCESS_REQUEST_VERIFIED_DETAIL =
    "Hasta que la apruebe, la cuenta no está activada y no podrás iniciar sesión con ella."
internal const val ACCESS_CODE_INVALID_MESSAGE = "El código no es válido o ha caducado. Revisa el correo o pide otro. Tras cinco intentos fallidos la solicitud se anula y hay que enviarla de nuevo."
internal const val ACCESS_CODE_UNAVAILABLE_MESSAGE = "El servicio no está disponible ahora mismo. Inténtalo de nuevo en unos minutos."
internal const val ACCESS_CODE_INVALID_EMAIL_MESSAGE = "El correo de la solicitud no tiene un formato válido. Vuelve al formulario y revísalo."
internal const val ACCESS_REQUEST_EXPIRY_HINT_TITLE = "¿Ha pasado más de una hora?"
internal const val ACCESS_REQUEST_EXPIRY_HINT_DETAIL =
    "Una solicitud sin verificar caduca 1 hora después de enviarla. Si es tu caso, vuelve al formulario y envía otra."

/** El texto de la parte de arriba del paso de verificacion. Funcion pura. */
internal fun accessVerifyIntro(email: String): String =
    "Te hemos enviado un código de ${AccessRequestRules.VERIFICATION_CODE_LENGTH} cifras a $email. Escríbelo aquí para confirmar " +
        "que el correo es tuyo. Si no lo verificas, la solicitud caduca ${AccessRequestRules.UNVERIFIED_EXPIRY_HOURS} hora después de enviarla."

/** «Reenviar código» o, mientras dura el enfriamiento, «Reenviar código (42 s)». */
internal fun accessResendLabel(cooldownSeconds: Int): String =
    if (cooldownSeconds > 0) "Reenviar código ($cooldownSeconds s)" else "Reenviar código"

/** «dentro de 30 segundos» / «dentro de 1 minuto» / «dentro de 15 minutos»; sin `Retry-After`, «dentro de unos minutos». */
internal fun accessRetryAfterLabel(seconds: Long?): String = when {
    seconds == null || seconds <= 0 -> "dentro de unos minutos"
    seconds < 60 -> if (seconds == 1L) "dentro de 1 segundo" else "dentro de $seconds segundos"
    else -> ((seconds + 59) / 60).let { minutes -> if (minutes == 1L) "dentro de 1 minuto" else "dentro de $minutes minutos" }
}

/** Tono, titulo y detalle del aviso de [feedback]. Funcion pura. */
internal fun accessVerificationNotice(feedback: AccessVerificationFeedback, email: String): Triple<WorkspaceNoticeTone, String, String?> =
    when (feedback) {
        AccessVerificationFeedback.InvalidCode -> Triple(WorkspaceNoticeTone.DANGER, ACCESS_CODE_INVALID_MESSAGE, null)
        AccessVerificationFeedback.InvalidEmail -> Triple(WorkspaceNoticeTone.DANGER, ACCESS_CODE_INVALID_EMAIL_MESSAGE, null)
        is AccessVerificationFeedback.RateLimited -> Triple(
            WorkspaceNoticeTone.WARNING,
            "Demasiados intentos",
            "Vuelve a intentarlo ${accessRetryAfterLabel(feedback.retryAfterSeconds)}.",
        )
        AccessVerificationFeedback.Unavailable -> Triple(WorkspaceNoticeTone.WARNING, ACCESS_CODE_UNAVAILABLE_MESSAGE, null)
        is AccessVerificationFeedback.Failed -> Triple(WorkspaceNoticeTone.DANGER, "No se ha podido completar", feedback.error.humanMessage())
        AccessVerificationFeedback.CodeResent -> Triple(
            WorkspaceNoticeTone.INFO,
            "Código reenviado",
            "Si la solicitud sigue pendiente, te llegará un código nuevo a $email.",
        )
    }

/**
 * «Solicitar acceso», dentro de la tarjeta de la pantalla de acceso.
 *
 * Tres caras: el formulario; tras un envio aceptado, «verifica tu correo» con el codigo de 6 cifras; y, verificado, el
 * aviso de que la solicitud queda pendiente de un superadministrador. Lo que se ensena tras enviar es el MISMO exista ya
 * el correo o no (el servidor tampoco lo distingue).
 */
@Composable
internal fun AccessRequestForm(
    viewModel: AccessRequestViewModel,
    onBackToLogin: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    AccessRequestContent(
        state = state,
        onIntent = viewModel::dispatch,
        onBackToLogin = {
            viewModel.dispatch(AccessRequestIntent.Reset)
            onBackToLogin()
        },
    )
}

/** El formulario, por ESTADO: se puede renderizar contra estados fabricados, sin Koin ni red. */
@Composable
internal fun AccessRequestContent(
    state: AccessRequestState,
    onIntent: (AccessRequestIntent) -> Unit,
    onBackToLogin: () -> Unit,
) {
    when (state.step) {
        AccessRequestStep.FORM -> Unit
        AccessRequestStep.VERIFY -> {
            AccessRequestVerifyContent(state, onIntent, onBackToLogin)
            return
        }
        AccessRequestStep.VERIFIED -> {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(ACCESS_REQUEST_VERIFIED_TAG),
                verticalArrangement = Arrangement.spacedBy(Spacing.field),
            ) {
                WorkspaceNotice(
                    tone = WorkspaceNoticeTone.INFO,
                    title = ACCESS_REQUEST_VERIFIED_TITLE,
                    detail = ACCESS_REQUEST_VERIFIED_DETAIL,
                )
                PrimaryButton(text = "Volver al inicio de sesión", onClick = onBackToLogin, modifier = Modifier.fillMaxWidth())
            }
            return
        }
    }

    val errors = state.fieldErrors
    val submitting = state.isSubmitting

    Column(
        modifier = Modifier.fillMaxWidth().testTag(ACCESS_REQUEST_FORM_TAG),
        verticalArrangement = Arrangement.spacedBy(Spacing.field),
    ) {
        Text(
            text = "Solicitar acceso",
            style = MaterialTheme.typography.titleMedium,
            color = GlassTheme.tokens.textPrimary,
        )
        Text(
            text = "OposiControl es solo para administradores. Rellena tus datos y un superadministrador revisará la solicitud.",
            style = MaterialTheme.typography.bodySmall,
            color = GlassTheme.tokens.textSecondary,
        )

        LabeledField(
            label = "Nombre",
            value = state.name,
            onValueChange = { onIntent(AccessRequestIntent.UpdateName(it.take(AccessRequestRules.NAME_MAX))) },
            placeholder = "Tu nombre y apellidos",
            required = true,
            maxWidth = Sizing.formColumnMaxWidth,
            supportingText = errors[AccessRequestField.NAME],
            isError = AccessRequestField.NAME in errors,
        )
        LabeledField(
            label = "Correo",
            value = state.email,
            onValueChange = { onIntent(AccessRequestIntent.UpdateEmail(it.take(AccessRequestRules.EMAIL_MAX))) },
            placeholder = "tu@correo.es",
            required = true,
            maxWidth = Sizing.formColumnMaxWidth,
            supportingText = errors[AccessRequestField.EMAIL],
            isError = AccessRequestField.EMAIL in errors,
        )
        LabeledField(
            label = "Motivo",
            value = state.reason,
            onValueChange = { onIntent(AccessRequestIntent.UpdateReason(it.take(AccessRequestRules.REASON_MAX))) },
            placeholder = "Para qué necesitas entrar en el panel",
            required = true,
            singleLine = false,
            minLines = 3,
            maxWidth = Sizing.formColumnMaxWidth,
            supportingText = errors[AccessRequestField.REASON] ?: "${state.reason.length} / ${AccessRequestRules.REASON_MAX}",
            isError = AccessRequestField.REASON in errors,
        )

        (state.submitState as? UiAsync.Error)?.let { failure ->
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.DANGER,
                title = "No se ha podido enviar la solicitud",
                detail = failure.error.humanMessage(),
            )
        }

        PrimaryButton(
            text = "Enviar solicitud",
            onClick = { onIntent(AccessRequestIntent.Submit) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !submitting,
            loading = submitting,
        )
        GhostButton(
            text = "Volver al inicio de sesión",
            onClick = onBackToLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !submitting,
        )
    }
}

/**
 * Tope de lo que admite el campo de contrasena: UNO mas que el maximo valido. Cortar justo en el maximo truncaria en
 * silencio una contrasena pegada mas larga (y luego no coincidiria al iniciar sesion); asi la validacion la rechaza.
 */
private const val PASSWORD_INPUT_MAX = AccessRequestRules.PASSWORD_MAX + 1

/** Paso «verifica tu correo»: el codigo de 6 cifras, reenviarlo con enfriamiento y volver atras. */
@Composable
private fun AccessRequestVerifyContent(
    state: AccessRequestState,
    onIntent: (AccessRequestIntent) -> Unit,
    onBackToLogin: () -> Unit,
) {
    val busy = state.verifying || state.resending
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val errors = state.fieldErrors
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ACCESS_REQUEST_VERIFY_TAG),
        verticalArrangement = Arrangement.spacedBy(Spacing.field),
    ) {
        Text(
            text = ACCESS_REQUEST_VERIFY_TITLE,
            style = MaterialTheme.typography.titleMedium,
            color = GlassTheme.tokens.textPrimary,
        )
        Text(
            text = accessVerifyIntro(state.verificationEmail),
            style = MaterialTheme.typography.bodySmall,
            color = GlassTheme.tokens.textSecondary,
        )
        LabeledField(
            label = "Código",
            value = state.code,
            onValueChange = { onIntent(AccessRequestIntent.UpdateCode(it)) },
            placeholder = "${AccessRequestRules.VERIFICATION_CODE_LENGTH} cifras",
            required = true,
            maxWidth = Sizing.formColumnMaxWidth,
            supportingText = state.codeError,
            isError = state.codeError != null,
        )
        Text(
            text = ACCESS_REQUEST_CHOOSE_PASSWORD,
            style = MaterialTheme.typography.bodySmall,
            color = GlassTheme.tokens.textSecondary,
        )
        PasswordField(
            label = "Contraseña",
            value = state.password,
            onValueChange = { onIntent(AccessRequestIntent.UpdatePassword(it.take(PASSWORD_INPUT_MAX))) },
            placeholder = "Entre ${AccessRequestRules.PASSWORD_MIN} y ${AccessRequestRules.PASSWORD_MAX} caracteres, sin tildes ni eñes",
            visible = passwordVisible,
            onToggleVisible = { passwordVisible = !passwordVisible },
            error = errors[AccessRequestField.PASSWORD],
        )
        PasswordField(
            label = "Repite la contraseña",
            value = state.passwordConfirmation,
            onValueChange = { onIntent(AccessRequestIntent.UpdatePasswordConfirmation(it.take(PASSWORD_INPUT_MAX))) },
            placeholder = "La misma contraseña",
            visible = passwordVisible,
            onToggleVisible = null,
            error = errors[AccessRequestField.PASSWORD_CONFIRMATION],
        )
        WorkspaceNotice(tone = WorkspaceNoticeTone.INFO, title = ACCESS_REQUEST_NO_LINKS_NOTE)

        state.verificationFeedback?.let { feedback ->
            val (tone, title, detail) = accessVerificationNotice(feedback, state.verificationEmail)
            WorkspaceNotice(tone = tone, title = title, detail = detail)
        }
        if (state.showExpiryHint) {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.WARNING,
                title = ACCESS_REQUEST_EXPIRY_HINT_TITLE,
                detail = ACCESS_REQUEST_EXPIRY_HINT_DETAIL,
            )
        }

        PrimaryButton(
            text = "Verificar",
            onClick = { onIntent(AccessRequestIntent.Verify) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canVerify && !state.resending,
            loading = state.verifying,
        )
        GhostButton(
            text = accessResendLabel(state.resendCooldownSeconds),
            onClick = { onIntent(AccessRequestIntent.ResendCode) },
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canResend,
            loading = state.resending,
        )
        GhostButton(
            text = "Volver al formulario",
            onClick = { onIntent(AccessRequestIntent.BackToForm) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        GhostButton(
            text = "Volver al inicio de sesión",
            onClick = onBackToLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisible: (() -> Unit)?,
    error: String?,
) {
    val tokens = GlassTheme.tokens
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(text = "$label *", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingContent = onToggleVisible?.let { toggle ->
                {
                    DesktopIconButton(
                        label = if (visible) "Ocultar contraseñas" else "Mostrar contraseñas",
                        onClick = toggle,
                        size = 30.dp,
                    ) {
                        AppIconImage(icon = if (visible) AppIcon.BLOCKED else AppIcon.VIEW, size = 16.dp)
                    }
                }
            },
            isError = error != null,
        )
        if (error != null) {
            Text(text = error, style = MaterialTheme.typography.bodySmall, color = tokens.danger)
        }
    }
}
