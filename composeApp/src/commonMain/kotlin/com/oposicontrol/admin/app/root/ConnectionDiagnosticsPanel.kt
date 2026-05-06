package com.oposicontrol.admin.app.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.domain.model.ConnectionDiagnosticCheck
import com.oposicontrol.admin.domain.repository.ConnectionDiagnosticsRepository

@Composable
fun ConnectionDiagnosticsPanel(
    appConfig: AppConfig,
    diagnosticsRepository: ConnectionDiagnosticsRepository,
    modifier: Modifier = Modifier,
) {
    var status by remember { mutableStateOf("Comprobando") }
    var checks by remember { mutableStateOf<List<ConnectionDiagnosticCheck>>(emptyList()) }

    LaunchedEffect(appConfig.baseUrl, appConfig.backendToken) {
        status = "Comprobando"
        checks = listOf(
            ConnectionDiagnosticCheck("URL backend", true, appConfig.baseUrl),
            ConnectionDiagnosticCheck("HTTP Android", true, "cleartext permitido en debug"),
            ConnectionDiagnosticCheck("Red", true, "telefono y PC en misma WiFi real"),
        )
        when (val result = diagnosticsRepository.runChecks()) {
            is AppResult.Failure -> {
                status = "Falla"
                checks = checks + ConnectionDiagnosticCheck("Cliente", false, result.error.message)
            }
            is AppResult.Success -> {
                checks = checks + result.value.checks
                status = if (result.value.checks.all { it.ok }) "OK" else "Falla"
            }
        }
    }

    if (status != "OK") {
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Conexion backend",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            status = if (check.ok) "OK" else "ERROR",
            modifier = Modifier.width(74.dp),
        )
        Text(
            text = "${check.name}: ${check.detail}",
            style = MaterialTheme.typography.bodySmall,
            color = if (check.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
