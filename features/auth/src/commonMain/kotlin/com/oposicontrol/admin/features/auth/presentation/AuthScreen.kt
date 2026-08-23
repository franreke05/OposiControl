package com.oposicontrol.admin.features.auth.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_logo
import org.jetbrains.compose.resources.painterResource

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) {
            onAuthenticated()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Image(
            painter = painterResource(Res.drawable.ic_logo),
            contentDescription = "Logo",
            modifier = Modifier.size(120.dp)
        )

        Text(
            text = "OposiControl Admin",
            style = MaterialTheme.typography.headlineSmall,
            color = GlassTheme.tokens.textPrimary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Acceso administrador",
                    style = MaterialTheme.typography.titleMedium,
                    color = GlassTheme.tokens.textPrimary,
                )

                if (state.isRestoring) {
                    Text(
                        text = "Recuperando sesion anterior...",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.accent,
                    )
                }

                Text(
                    text = "Usuario",
                    style = MaterialTheme.typography.labelMedium,
                )
                GlassTextField(
                    value = state.email,
                    onValueChange = { viewModel.dispatch(AuthIntent.UpdateEmail(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Introduce tu usuario",
                )
                Text(
                    text = "Contraseña",
                    style = MaterialTheme.typography.labelMedium,
                )
                GlassTextField(
                    value = state.password,
                    onValueChange = { viewModel.dispatch(AuthIntent.UpdatePassword(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Introduce tu contraseña",
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            modifier = Modifier
                                .size(40.dp)
                                .semantics {
                                    contentDescription = if (passwordVisible) {
                                        "Ocultar contraseña"
                                    } else {
                                        "Mostrar contraseña"
                                    }
                                },
                        ) {
                            PasswordVisibilityIcon(visible = passwordVisible)
                        }
                    },
                )
                GlassActionButton(
                    text = "Entrar",
                    onClick = { viewModel.dispatch(AuthIntent.Submit) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.submitState !is UiAsync.Loading,
                )
                when (val submitState = state.submitState) {
                    is UiAsync.Error -> Text(
                        text = submitState.error.message,
                        color = MaterialTheme.colorScheme.error,
                    )

                    UiAsync.Loading -> Text(
                        text = "Autenticando...",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.accent,
                    )

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun PasswordVisibilityIcon(visible: Boolean) {
    val color = GlassTheme.tokens.textSecondary
    Canvas(modifier = Modifier.size(22.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val eye = Path().apply {
            moveTo(size.width * 0.1f, size.height * 0.5f)
            cubicTo(
                size.width * 0.3f,
                size.height * 0.12f,
                size.width * 0.7f,
                size.height * 0.12f,
                size.width * 0.9f,
                size.height * 0.5f,
            )
            cubicTo(
                size.width * 0.7f,
                size.height * 0.88f,
                size.width * 0.3f,
                size.height * 0.88f,
                size.width * 0.1f,
                size.height * 0.5f,
            )
        }
        drawPath(
            path = eye,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        drawCircle(
            color = color,
            radius = 2.6.dp.toPx(),
            center = Offset(size.width / 2f, size.height / 2f),
        )
        if (!visible) {
            drawLine(
                color = color,
                start = Offset(size.width * 0.14f, size.height * 0.14f),
                end = Offset(size.width * 0.86f, size.height * 0.86f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
