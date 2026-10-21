package com.oposicontrol.admin.features.auth.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.DesktopIconButton
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SectionLink
import androidx.compose.ui.platform.testTag
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_logo
import org.jetbrains.compose.resources.painterResource

/**
 * Acceso al panel.
 *
 * El cambio grande es el ancho. La versión anterior era `fillMaxSize()` con la
 * tarjeta en `fillMaxWidth()` y sin tope, así que en un monitor de 1920 el
 * formulario medía 1872dp: dos campos de veinte caracteres repartidos a lo largo
 * de toda la pantalla. Ahora la columna está topada y centrada.
 *
 * Lo segundo es el teclado: Intro envía desde cualquiera de los dos campos. En
 * una pantalla de login que se usa a diario, obligar a llevar la mano al ratón
 * para el último paso es una fricción gratuita.
 */
@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    /** Formulario «Solicitar acceso». `null` = esta composicion no lo ofrece (no se pinta el enlace). */
    accessRequestViewModel: AccessRequestViewModel? = null,
) {
    val state by viewModel.state.collectAsState()
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var requestingAccess by rememberSaveable { mutableStateOf(false) }
    val tokens = GlassTheme.tokens
    val passwordFocus = remember { FocusRequester() }

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) {
            onAuthenticated()
        }
    }

    val submitting = state.submitState is UiAsync.Loading
    val canSubmit = state.email.isNotBlank() && state.password.isNotBlank() && !submitting
    val submit = {
        if (canSubmit) viewModel.dispatch(AuthIntent.Submit)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = Sizing.formColumnMaxWidth)
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(Res.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(
                    text = "OposiControl",
                    style = MaterialTheme.typography.headlineSmall,
                    color = tokens.textPrimary,
                )
                Text(
                    text = "Centro de control de OposiBots",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(tokens.surfaceSolid)
                    .border(1.dp, tokens.border, RoundedCornerShape(14.dp))
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.field),
            ) {
                if (requestingAccess && accessRequestViewModel != null && !state.isRestoring) {
                    AccessRequestForm(viewModel = accessRequestViewModel, onBackToLogin = { requestingAccess = false })
                    return@Column
                }
                if (state.isRestoring) {
                    Text(
                        text = "Recuperando tu sesión anterior…",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accent,
                    )
                }

                FieldLabel("Usuario")
                GlassTextField(
                    value = state.email,
                    onValueChange = { viewModel.dispatch(AuthIntent.UpdateEmail(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                // Intro en el usuario baja a la contraseña, no envía:
                                // enviar con la contraseña vacía sólo produciría un
                                // error que ya sabemos de antemano.
                                passwordFocus.requestFocus()
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = "Tu usuario de administrador",
                )

                FieldLabel("Contraseña")
                GlassTextField(
                    value = state.password,
                    onValueChange = { viewModel.dispatch(AuthIntent.UpdatePassword(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = "Tu contraseña",
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingContent = {
                        DesktopIconButton(
                            label = if (passwordVisible) "Ocultar contraseña" else "Mostrar contraseña",
                            onClick = { passwordVisible = !passwordVisible },
                            size = 30.dp,
                        ) {
                            AppIconImage(
                                icon = if (passwordVisible) AppIcon.BLOCKED else AppIcon.VIEW,
                                size = 16.dp,
                            )
                        }
                    },
                )

                PrimaryButton(
                    text = "Entrar",
                    onClick = submit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSubmit,
                    loading = submitting,
                )

                (state.submitState as? UiAsync.Error)?.let { failure ->
                    LoginError(message = loginErrorMessage(failure.error))
                }

                // Quien todavia no es administrador lo pide desde aqui; un superadministrador lo aprueba en Usuarios.
                if (accessRequestViewModel != null) {
                    SectionLink(
                        text = "¿No tienes acceso? Solicitar acceso",
                        onClick = { requestingAccess = true },
                        modifier = Modifier.testTag(AUTH_REQUEST_ACCESS_LINK_TAG),
                    )
                }
            }
        }
    }
}

internal const val AUTH_REQUEST_ACCESS_LINK_TAG: String = "auth-request-access-link"

/**
 * El texto del error de acceso.
 *
 * Un `AppError.Authentication` del inicio de sesion ya trae un mensaje redactado para la persona («Usuario o contraseña
 * incorrectos», «Esta cuenta todavía no está activada…», «Demasiados intentos…»): se ensena ESE. `humanMessage()` lo
 * sustituia siempre por «Tu sesión ha caducado», que en esta pantalla es falso para una contrasena mal escrita y para una
 * solicitud de acceso que sigue pendiente de aprobacion. El resto de errores sigue pasando por `humanMessage()`.
 */
internal fun loginErrorMessage(error: AppError): String = when (error) {
    is AppError.Authentication -> error.message.ifBlank { error.humanMessage() }
    // 403 del servidor (no es administrador o la cuenta esta bloqueada): su texto ya esta redactado para la persona.
    is AppError.Authorization -> error.message.ifBlank { error.humanMessage() }
    else -> error.humanMessage()
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = GlassTheme.tokens.textSecondary,
    )
}

/**
 * El error va en su propio contenedor, no como texto rojo suelto: en un
 * formulario corto un párrafo suelto en rojo se confunde con la validación de un
 * campo concreto, y esto es un fallo de toda la operación.
 */
@Composable
private fun LoginError(message: String) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(tokens.danger.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        AppIconImage(AppIcon.WARNING, size = 15.dp, tint = tokens.danger)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.danger,
        )
    }
}
