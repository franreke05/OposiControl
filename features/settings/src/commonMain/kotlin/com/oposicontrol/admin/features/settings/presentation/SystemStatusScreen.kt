package com.oposicontrol.admin.features.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.GlassActionButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import oposicontrol.core.ui.generated.resources.Res
import oposicontrol.core.ui.generated.resources.ic_filter
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

@Composable
fun SystemStatusScreen(
    viewModel: SystemStatusViewModel,
) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val uiState = state.uiState) {
            SystemStatusUiState.Loading -> LoadingStateCard(rows = 5)
            SystemStatusUiState.NotConnected -> NotConnectedContent(
                onRefresh = { viewModel.dispatch(SystemStatusIntent.Refresh) },
            )
            is SystemStatusUiState.Content -> StatusContent(uiState.value, banner = null)
            is SystemStatusUiState.Degraded -> StatusContent(
                uiState.value,
                banner = "Uno o más servicios monitorizados presentan incidencias.",
            )
            is SystemStatusUiState.PartialData -> StatusContent(
                uiState.value,
                banner = "Algunas métricas todavía no están disponibles.",
            )
            is SystemStatusUiState.Error -> ErrorContent(
                message = uiState.message,
                onRetry = { viewModel.dispatch(SystemStatusIntent.Refresh) },
            )
        }
    }
}

@Composable
private fun NotConnectedContent(
    onRefresh: () -> Unit,
) {
    StatusHero(
        level = SystemStatusLevel.UNKNOWN,
        title = "Resumen operativo",
        message = "Datos de monitorización todavía no conectados.",
        supporting = "Esta pantalla está preparada para recibir un resumen seguro desde Ktor. No se muestran métricas inventadas.",
    )
    ServicesCard(
        services = listOf(
            "Backend Ktor" to SystemStatusLevel.UNKNOWN,
            "Supabase" to SystemStatusLevel.UNKNOWN,
            "Worker" to SystemStatusLevel.UNKNOWN,
        ),
    )
    MetricsCard(
        metrics = listOf(
            "CPU" to "No disponible",
            "RAM" to "No disponible",
            "Disco Supabase" to "No disponible",
            "Latencia API" to "No disponible",
            "Errores 24h" to "No disponible",
            "Crashes móviles" to "No disponible",
            "Cola de trabajos" to "No disponible",
        ),
        updatedAt = "No disponible",
    )
    GlassActionButton(
        text = "Reintentar conexión",
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh,
    )
}

@Composable
private fun StatusContent(
    status: SystemStatusUi,
    banner: String?,
) {
    StatusHero(
        level = status.overallStatus,
        title = "Resumen operativo",
        message = if (status.overallStatus == SystemStatusLevel.OPERATIVE) {
            "Los servicios monitorizados están funcionando con normalidad."
        } else {
            "Revisa el estado resumido de los servicios y métricas disponibles."
        },
        supporting = banner,
    )
    ServicesCard(
        services = listOf(
            "Backend Ktor" to status.ktorStatus,
            "Supabase" to status.supabaseStatus,
            "Worker" to status.workerStatus,
        ),
    )
    MetricsCard(
        metrics = listOf(
            "CPU" to status.cpuPercent.toPercentLabel(),
            "RAM" to status.ramPercent.toPercentLabel(),
            // `toPercentLabel()` devuelve `null` para un `null`, y `MetricRow` lo
            // pinta como "No disponible": un disco sin medir no se muestra 0 %.
            "Disco Supabase" to status.supabaseDiskPercent.toPercentLabel(),
            "Latencia API" to status.apiLatencyMs?.let { "$it ms" },
            "Errores 24h" to status.errors24h?.toString(),
            "Crashes móviles" to status.mobileCrashes24h?.toString(),
            "Cola de trabajos" to status.queueDepth?.toString(),
        ),
        updatedAt = status.updatedAtLabel,
    )
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    StatusHero(
        level = SystemStatusLevel.DOWN,
        title = "No se pudo cargar el estado",
        message = message.ifBlank { "No se ha podido obtener el resumen operativo." },
        supporting = "No se muestran detalles técnicos internos.",
    )
    GlassActionButton(
        text = "Reintentar",
        modifier = Modifier.fillMaxWidth(),
        onClick = onRetry,
    )
}

@Composable
private fun StatusHero(
    level: SystemStatusLevel,
    title: String,
    message: String,
    supporting: String?,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        tint = heroTint(level),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .backgroundCircle(heroColor(level).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_filter),
                        contentDescription = null,
                        tint = heroColor(level),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = GlassTheme.tokens.textPrimary,
                    )
                    Text(
                        text = "Estado del sistema",
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.textSecondary,
                    )
                }
                StatusBadge(level)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = GlassTheme.tokens.textPrimary,
            )
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GlassTheme.tokens.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun ServicesCard(
    services: List<Pair<String, SystemStatusLevel>>,
) {
    SectionCard(title = "SERVICIOS") {
        services.forEachIndexed { index, (label, level) ->
            if (index > 0) Spacer(modifier = Modifier.size(4.dp))
            ServiceRow(label = label, level = level)
        }
    }
}

@Composable
private fun MetricsCard(
    metrics: List<Pair<String, String? >>,
    updatedAt: String,
) {
    SectionCard(title = "RENDIMIENTO E INCIDENCIAS") {
        metrics.forEachIndexed { index, (label, value) ->
            if (index > 0) Spacer(modifier = Modifier.size(4.dp))
            MetricRow(label = label, value = value ?: "No disponible")
        }
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = "Última actualización",
            style = MaterialTheme.typography.labelMedium,
            color = GlassTheme.tokens.textSecondary,
        )
        Text(
            text = updatedAt,
            style = MaterialTheme.typography.bodyMedium,
            color = GlassTheme.tokens.textPrimary,
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = GlassTheme.tokens.textSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun ServiceRow(
    label: String,
    level: SystemStatusLevel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .semantics { contentDescription = "$label: ${level.label()}" }
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = GlassTheme.tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        StatusBadge(level)
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = GlassTheme.tokens.textPrimary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = if (value == "No disponible") GlassTheme.tokens.textSecondary else GlassTheme.tokens.textPrimary,
        )
    }
}

@Composable
private fun StatusBadge(level: SystemStatusLevel) {
    Text(
        text = level.label(),
        modifier = Modifier
            .defaultMinSize(minHeight = 30.dp)
            .backgroundCircle(heroColor(level).copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = heroColor(level),
        fontWeight = FontWeight.SemiBold,
    )
}

private fun SystemStatusLevel.label(): String = when (this) {
    SystemStatusLevel.OPERATIVE -> "Operativo"
    SystemStatusLevel.DEGRADED -> "Degradado"
    SystemStatusLevel.DOWN -> "Incidencia"
    SystemStatusLevel.UNKNOWN -> "No conectado"
}

@Composable
private fun heroColor(level: SystemStatusLevel): Color = when (level) {
    SystemStatusLevel.OPERATIVE -> GlassTheme.tokens.success
    SystemStatusLevel.DEGRADED -> GlassTheme.tokens.warning
    SystemStatusLevel.DOWN -> MaterialTheme.colorScheme.error
    SystemStatusLevel.UNKNOWN -> GlassTheme.tokens.textSecondary
}

@Composable
private fun heroTint(level: SystemStatusLevel): Color = when (level) {
    SystemStatusLevel.OPERATIVE -> GlassTheme.tokens.success.copy(alpha = 0.07f)
    SystemStatusLevel.DEGRADED -> GlassTheme.tokens.warning.copy(alpha = 0.08f)
    SystemStatusLevel.DOWN -> MaterialTheme.colorScheme.error.copy(alpha = 0.06f)
    SystemStatusLevel.UNKNOWN -> GlassTheme.tokens.glassFill
}

private fun Modifier.backgroundCircle(color: Color): Modifier =
    this.background(color = color, shape = CircleShape)

/** Conserva `null` como ausencia y presenta un cero medido como `0 %`. */
internal fun Double?.toPercentLabel(): String? = this?.let { value ->
    val tenths = (value * 10).roundToInt()
    if (tenths % 10 == 0) {
        "${tenths / 10} %"
    } else {
        "${tenths / 10}.${kotlin.math.abs(tenths % 10)} %"
    }
}
