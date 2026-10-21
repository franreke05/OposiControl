package com.oposicontrol.admin.app.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.components.ErrorStateCard
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.AdminSessionInfo

/**
 * Qué parte del panel puede usar la sesión, según el SERVIDOR (`GET /api/v1/admin/sesion`). Decisión del propietario
 * 2026-10-20: el rol `moderador` entra solo a moderar el foro. El permiso real lo impone el guard de Ktor; esto solo
 * evita enseñar pantallas que el servidor va a rechazar.
 */
internal sealed interface PanelAccess {
    data object Checking : PanelAccess
    data object Full : PanelAccess
    data object ForumModerator : PanelAccess
    data class Denied(val message: String) : PanelAccess
}

internal const val PANEL_ACCESS_DENIED_DEFAULT = "No tienes permisos para acceder al panel de administración."

/**
 * Admin → todo. Moderador sin rol admin → solo la cola del foro. Un 403 → acceso denegado con el texto del servidor.
 * Cualquier otro fallo (red, 5xx) → panel completo como hasta ahora: el servidor sigue rechazando lo que no toca y un
 * fallo transitorio no debe dejar fuera a un administrador.
 */
internal fun panelAccessFor(session: AppResult<AdminSessionInfo>): PanelAccess = when (session) {
    is AppResult.Success -> when {
        session.value.isAdmin || session.value.isSuperAdmin -> PanelAccess.Full
        session.value.roles.any { it.trim().lowercase() == "moderador" } -> PanelAccess.ForumModerator
        else -> PanelAccess.Denied(PANEL_ACCESS_DENIED_DEFAULT)
    }
    is AppResult.Failure -> when (val e = session.error) {
        is AppError.Authorization -> PanelAccess.Denied(e.message.ifBlank { PANEL_ACCESS_DENIED_DEFAULT })
        else -> PanelAccess.Full
    }
}

internal const val MODERATOR_PANEL_TITLE = "Moderación del foro"

/** Panel reducido del moderador: título, cerrar sesión y la cola (la pone [content]). */
@Composable
internal fun ModeratorPanel(onLogout: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(MODERATOR_PANEL_TITLE, style = MaterialTheme.typography.titleLarge)
                Text("Tu rol es moderador: puedes revisar y moderar publicaciones y comentarios.", style = MaterialTheme.typography.bodyMedium)
            }
            SecondaryButton(text = "Cerrar sesión", onClick = onLogout, modifier = Modifier.width(160.dp))
        }
        content()
    }
}

@Composable
internal fun PanelAccessChecking() {
    Column(modifier = Modifier.fillMaxSize().padding(Spacing.lg)) { LoadingStateCard() }
}

@Composable
internal fun PanelAccessDenied(message: String, onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        ErrorStateCard(message = message, title = "Sin acceso al panel")
        SecondaryButton(text = "Cerrar sesión", onClick = onLogout, modifier = Modifier.width(160.dp))
    }
}
