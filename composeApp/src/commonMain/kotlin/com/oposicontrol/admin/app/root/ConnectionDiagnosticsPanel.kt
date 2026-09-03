package com.oposicontrol.admin.app.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.InfoHelpButton
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticCheck
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository

@Composable
fun ConnectionDiagnosticsPanel(
    appConfig: AppConfig,
    diagnosticsRepository: ConnectionDiagnosticsRepository,
    modifier: Modifier = Modifier,
    refreshSignal: Int = 0,
) {
    // Vocabulario de §38, el mismo en todo el panel: OPERATIVO / DEGRADADO /
    // CAÍDO / NO CONECTADO / DESCONOCIDO. Antes eran "OK", "Falla" y
    // "Comprobando" — tres palabras propias de esta pantalla que no significaban
    // nada en el resto de la aplicación.
    var status by remember { mutableStateOf(EstadoDesconocido) }
    var checks by remember { mutableStateOf<List<ConnectionDiagnosticCheck>>(emptyList()) }

    LaunchedEffect(appConfig.baseUrl, appConfig.backendToken, refreshSignal) {
        status = EstadoDesconocido
        checks = listOf(
            ConnectionDiagnosticCheck("URL backend", true, appConfig.baseUrl),
            ConnectionDiagnosticCheck("HTTP local", true, "debug/dev permite 10.0.2.2, localhost y 127.0.0.1"),
            ConnectionDiagnosticCheck("Red", true, "telefono y PC en misma WiFi real"),
        )
        when (val result = diagnosticsRepository.runChecks()) {
            is AppResult.Failure -> {
                // No se pudo llegar al backend siquiera: eso es caído, no
                // degradado. La distinción importa — degradado significa que
                // responde pero algo falla dentro.
                status = EstadoCaido
                checks = checks + ConnectionDiagnosticCheck("Cliente", false, result.error.humanMessage())
            }
            is AppResult.Success -> {
                checks = checks + result.value.checks
                status = when {
                    result.value.checks.all { it.ok } -> EstadoOperativo
                    result.value.checks.none { it.ok } -> EstadoCaido
                    else -> EstadoDegradado
                }
            }
        }
    }

    if (status != EstadoOperativo) {
        GlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 184.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Conexión con el servidor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    InfoHelpButton(Help.estadoSistema)
                    StatusChip(status)
                }
                checks.take(10).forEach { check ->
                    DiagnosticLine(check)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLine(check: ConnectionDiagnosticCheck) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusChip(
            status = if (check.ok) EstadoOperativo else EstadoCaido,
            modifier = Modifier.widthIn(min = 96.dp),
        )
        Text(
            text = "${check.name}: ${check.detail}",
            modifier = Modifier.widthIn(max = 680.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (check.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Vocabulario canónico de estado del sistema (§38). Constantes y no literales
// sueltos: son las mismas palabras que interpreta `statusToneFor` para elegir
// color e icono, así que una errata aquí degradaría el chip a "desconocido" sin
// que nadie se enterase.
private const val EstadoOperativo = "Operativo"
private const val EstadoDegradado = "Degradado"
private const val EstadoCaido = "Caído"
private const val EstadoDesconocido = "Desconocido"
