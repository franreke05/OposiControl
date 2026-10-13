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
internal const val ACCESS_REQUEST_SENT_TAG: String = "access-request-sent"

internal const val ACCESS_REQUEST_SENT_TITLE = "Solicitud enviada"
internal const val ACCESS_REQUEST_SENT_DETAIL =
    "Un superadministrador tiene que aprobarla. Hasta entonces la cuenta no está activada y no podrás iniciar sesión con ella."

/**
 * «Solicitar acceso», dentro de la tarjeta de la pantalla de acceso.
 *
 * Dos caras: el formulario y, tras un envio aceptado, el aviso de que la solicitud queda pendiente de un
 * superadministrador. El aviso es el MISMO exista ya el correo o no (el servidor tampoco lo distingue).
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
    if (state.isSent) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(ACCESS_REQUEST_SENT_TAG),
            verticalArrangement = Arrangement.spacedBy(Spacing.field),
        ) {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.INFO,
                title = ACCESS_REQUEST_SENT_TITLE,
                detail = ACCESS_REQUEST_SENT_DETAIL,
            )
            PrimaryButton(text = "Volver al inicio de sesión", onClick = onBackToLogin, modifier = Modifier.fillMaxWidth())
        }
        return
    }

    var passwordVisible by rememberSaveable { mutableStateOf(false) }
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
        PasswordField(
            label = "Contraseña",
            value = state.password,
            onValueChange = { onIntent(AccessRequestIntent.UpdatePassword(it.take(AccessRequestRules.PASSWORD_MAX))) },
            placeholder = "Entre ${AccessRequestRules.PASSWORD_MIN} y ${AccessRequestRules.PASSWORD_MAX} caracteres",
            visible = passwordVisible,
            onToggleVisible = { passwordVisible = !passwordVisible },
            error = errors[AccessRequestField.PASSWORD],
        )
        PasswordField(
            label = "Repite la contraseña",
            value = state.passwordConfirmation,
            onValueChange = { onIntent(AccessRequestIntent.UpdatePasswordConfirmation(it.take(AccessRequestRules.PASSWORD_MAX))) },
            placeholder = "La misma contraseña",
            visible = passwordVisible,
            onToggleVisible = null,
            error = errors[AccessRequestField.PASSWORD_CONFIRMATION],
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
                // La contrasena no se conserva tras un envio: hay que volver a escribirla para reintentar.
                detail = failure.error.humanMessage() + " Vuelve a escribir la contraseña para intentarlo otra vez.",
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
