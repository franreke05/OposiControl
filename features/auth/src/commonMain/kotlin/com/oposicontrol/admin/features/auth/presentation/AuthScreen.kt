package com.oposicontrol.admin.features.auth.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassTextField
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_logo
import kotlinx.coroutines.flow.collect
import org.jetbrains.compose.resources.painterResource

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel, onAuthenticated) {
        viewModel.effects.collect { effect ->
            when (effect) {
                AuthEffect.Authenticated -> onAuthenticated()
            }
        }
    }

    AuthContent(
        state = state,
        onIntent = viewModel::dispatch,
    )
}

@Composable
fun AuthContent(
    state: AuthState,
    onIntent: (AuthIntent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
                    text = "Email",
                    style = MaterialTheme.typography.labelMedium,
                )
                GlassTextField(
                    value = state.email,
                    onValueChange = { onIntent(AuthIntent.UpdateEmail(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "tu@email.com",
                )
                Text(
                    text = "Contrasena",
                    style = MaterialTheme.typography.labelMedium,
                )
                GlassTextField(
                    value = state.password,
                    onValueChange = { onIntent(AuthIntent.UpdatePassword(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Introduce tu contrasena",
                    visualTransformation = PasswordVisualTransformation(),
                )
                GlassActionButton(
                    text = "Entrar",
                    onClick = { onIntent(AuthIntent.Submit) },
                    modifier = Modifier.fillMaxWidth(),
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
