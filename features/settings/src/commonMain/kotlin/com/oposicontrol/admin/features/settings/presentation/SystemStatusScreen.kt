package com.oposicontrol.admin.features.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.ErrorStateCard
import com.oposicontrol.admin.core.ui.components.LoadingStateCard
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.WorkspaceEqualRow
import com.oposicontrol.admin.core.ui.components.WorkspaceFact
import com.oposicontrol.admin.core.ui.components.WorkspaceFacts
import com.oposicontrol.admin.core.ui.components.WorkspaceInfoLines
import com.oposicontrol.admin.core.ui.components.WorkspaceLayout
import com.oposicontrol.admin.core.ui.components.WorkspaceMetaLine
import com.oposicontrol.admin.core.ui.components.WorkspaceNotice
import com.oposicontrol.admin.core.ui.components.WorkspaceNoticeTone
import com.oposicontrol.admin.core.ui.components.WorkspacePanel
import com.oposicontrol.admin.core.ui.components.WorkspacePill
import com.oposicontrol.admin.core.ui.components.WorkspaceRail
import com.oposicontrol.admin.core.ui.components.WorkspaceSection
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.components.isRetryable
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.layout.supportsMasterDetail
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.SupabaseQuota
import com.oposicontrol.admin.domain.model.SystemAlert
import com.oposicontrol.admin.domain.model.SystemReplica
import com.oposicontrol.admin.domain.model.SystemStatus
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

internal const val SYSTEM_STATUS_SUMMARY_TAG: String = "system-status-summary"
internal const val SYSTEM_STATUS_DETAIL_TAG: String = "system-status-detail"
internal const val SYSTEM_STATUS_RAIL_TAG: String = "system-status-rail"
internal const val SYSTEM_STATUS_REFRESH_TAG: String = "system-status-refresh"

/** Cada cuánto se recalculan los «hace…» mientras la pantalla está abierta. No lee nada del servidor. */
private val ELAPSED_REFRESH = 30.seconds

/**
 * Estado del sistema, en escritorio.
 *
 * El mismo workspace de tres columnas que Accesos u OposiTickets, aquí de solo lectura: a la izquierda el resumen, la
 * versión y la sesión; en el centro réplicas, alertas, cuota y rendimiento; a la derecha qué hacer cuando algo falla.
 * Nada de esta pantalla actúa sobre la infraestructura: informa, y dice dónde mirar.
 */
@Composable
fun SystemStatusScreen(
    viewModel: SystemStatusViewModel,
) {
    val state by viewModel.state.collectAsState()
    // Los «hace 3 min» se quedarían clavados en el instante de la lectura: el reloj de la pantalla avanza solo.
    val now by produceState(initialValue = Clock.System.now()) {
        while (true) {
            delay(ELAPSED_REFRESH)
            value = Clock.System.now()
        }
    }
    SystemStatusContent(state = state, onIntent = viewModel::dispatch, now = now)
}

/**
 * El workspace, por ESTADO: el banco de pruebas visual lo renderiza contra estados fabricados, sin Ktor ni Koin. [now]
 * llega de fuera por lo mismo: con un reloj fijo las capturas son reproducibles.
 */
@Composable
internal fun SystemStatusContent(
    state: SystemStatusState,
    onIntent: (SystemStatusIntent) -> Unit,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    // Apilado (ventana estrecha) el hueco del catálogo tiene altura máxima: ahí solo va el resumen, y versión y sesión
    // bajan al final de la columna central.
    val stacked = !DesktopWindow.breakpoint.supportsMasterDetail
    // `WorkspaceLayout` oculta el rail cuando no cabe junto a la columna central. «Qué hacer si…» no puede desaparecer
    // justo en un portátil, así que cuando el rail no se compone ese bloque se pinta al final de la columna central.
    var railComposed by remember { mutableStateOf(false) }
    WorkspaceLayout(
        modifier = modifier,
        catalog = { paneModifier ->
            WorkspaceRail(modifier = paneModifier.testTag(SYSTEM_STATUS_SUMMARY_TAG), scrolls = !stacked) {
                SummarySection(state, onIntent, now)
                if (!stacked) {
                    VersionSection(state)
                    SessionSection(state)
                }
            }
        },
        editor = { paneModifier, _, fillsHeight ->
            WorkspaceRail(modifier = paneModifier.testTag(SYSTEM_STATUS_DETAIL_TAG), scrolls = fillsHeight) {
                when (val uiState = state.uiState) {
                    SystemStatusUiState.Loading -> LoadingStateCard(rows = 8)
                    SystemStatusUiState.NotConnected -> EmptyStateCard(
                        title = "Sin conexión con el servidor",
                        message = "Este panel no tiene configurado un servidor al que preguntar por el estado. No se muestran métricas inventadas.",
                        icon = AppIcon.STATUS,
                    )
                    is SystemStatusUiState.Error -> ErrorStateCard(
                        title = "No se ha podido leer el estado del sistema",
                        message = uiState.error.humanMessage(),
                        technicalDetail = uiState.error.message.takeIf { it.isNotBlank() && it != uiState.error.humanMessage() },
                        onRetry = { onIntent(SystemStatusIntent.Refresh) }.takeIf { uiState.error.isRetryable() },
                    )
                    is SystemStatusUiState.Content -> {
                        ReplicasSection(uiState.status, now)
                        AlertsSection(uiState.status)
                        QuotaSection(uiState.status)
                        PerformanceSection(uiState.status)
                    }
                }
                if (stacked) {
                    VersionSection(state)
                    SessionSection(state)
                }
                if (!railComposed) GuidanceSection()
            }
        },
        rail = { paneModifier, scrolls ->
            DisposableEffect(Unit) {
                railComposed = true
                onDispose { railComposed = false }
            }
            WorkspaceRail(modifier = paneModifier.testTag(SYSTEM_STATUS_RAIL_TAG), scrolls = scrolls) { GuidanceSection() }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Resumen
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun SummarySection(state: SystemStatusState, onIntent: (SystemStatusIntent) -> Unit, now: Instant) {
    val tokens = GlassTheme.tokens
    val uiState = state.uiState
    // Sin una lectura en la mano el resumen NUNCA es verde: se dice que no se sabe, y por qué.
    val overview = when (uiState) {
        is SystemStatusUiState.Content -> systemOverview(uiState.status)
        SystemStatusUiState.Loading -> SystemOverview(
            level = SystemStatusLevel.UNKNOWN,
            label = "Leyendo",
            headline = "Leyendo el estado del sistema…",
            detail = "Se está preguntando al servidor por sus réplicas, sus alertas y su consumo.",
        )
        SystemStatusUiState.NotConnected -> SystemOverview(
            level = SystemStatusLevel.UNKNOWN,
            label = "Sin conexión",
            headline = "El panel no está conectado a un servidor.",
            detail = "Sin servidor no hay estado que mostrar.",
        )
        is SystemStatusUiState.Error -> SystemOverview(
            level = SystemStatusLevel.UNKNOWN,
            label = "Sin lectura",
            headline = "No se ha podido leer el estado.",
            detail = "El panel no ha conseguido preguntar al servidor, así que no se sabe cómo está. Pulsa «Actualizar» para volver a intentarlo.",
        )
    }
    val status = (uiState as? SystemStatusUiState.Content)?.status
    WorkspaceSection(
        icon = AppIcon.STATUS,
        role = overview.level.semanticRole(),
        title = "Resumen",
        subtitle = "Cómo está el sistema ahora mismo.",
    ) {
        WorkspacePanel {
            StatusChip(status = overview.label, tone = overview.level.tone())
            Text(text = overview.headline, style = MaterialTheme.typography.titleMedium, color = tokens.textPrimary)
            if (overview.detail.isNotBlank()) {
                Text(text = overview.detail, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            // Mientras se lee no es que el dato «no esté disponible»: es que aún no ha llegado.
            val pending = if (uiState == SystemStatusUiState.Loading) "Leyendo…" else UNAVAILABLE
            WorkspaceMetaLine("Servidor", status?.ktorStatus?.label() ?: pending)
            WorkspaceMetaLine("Supabase", status?.supabaseStatus?.label() ?: pending)
            WorkspaceMetaLine(
                "Última lectura",
                when (uiState) {
                    is SystemStatusUiState.Content -> elapsedLabel(uiState.readAt, now) ?: UNAVAILABLE
                    SystemStatusUiState.Loading -> "En curso"
                    SystemStatusUiState.NotConnected -> "Ninguna"
                    is SystemStatusUiState.Error -> "No se ha completado"
                },
            )
        }
        // Siempre a la vista, no solo cuando algo falla: quien mira un estado quiere poder pedir uno más reciente.
        SecondaryButton(
            text = "Actualizar",
            size = ButtonSize.COMPACT,
            modifier = Modifier.fillMaxWidth().testTag(SYSTEM_STATUS_REFRESH_TAG),
            loading = state.refreshing,
            onClick = { onIntent(SystemStatusIntent.Refresh) },
            icon = { AppIconImage(AppIcon.REFRESH, size = 14.dp) },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Réplicas
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun ReplicasSection(status: SystemStatus, now: Instant) {
    val replicas = status.replicas
    WorkspaceSection(
        icon = AppIcon.LAYERS,
        role = when {
            replicas == null -> SemanticRole.NEUTRAL
            replicas.all { it.up } -> SemanticRole.SUCCESS
            else -> SemanticRole.DANGER
        },
        title = "Réplicas",
        subtitle = "Cada copia del servidor que atiende a la app o a este panel.",
        action = replicas?.let { list -> { WorkspacePill(text = "${list.count { it.up }} de ${list.size} en pie") } },
    ) {
        if (replicas == null) {
            UnreadNotice(status, missing = "No hay datos de las réplicas")
        } else {
            replicas.forEach { replica -> ReplicaRow(replica, now) }
        }
    }
}

/** Cuatro celdas iguales, las mismas en todas las filas: se lee como una tabla sin serlo. */
@Composable
private fun ReplicaRow(replica: SystemReplica, now: Instant) {
    val tokens = GlassTheme.tokens
    WorkspacePanel(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Réplica ${replica.name}: ${replicaStateLabel(replica)}. ${replicaSupabaseLabel(replica)}."
        },
        borderColor = if (replica.up) null else tokens.danger.copy(alpha = 0.45f),
    ) {
        WorkspaceEqualRow(columns = 4, gap = Spacing.sm) {
            ReplicaCell(label = replicaProfileLabel(replica.profile) ?: "Réplica") {
                Text(
                    text = replica.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
            }
            ReplicaCell(label = "Estado") {
                StatusChip(status = replicaStateLabel(replica), tone = if (replica.up) StatusTone.POSITIVE else StatusTone.NEGATIVE)
            }
            ReplicaCell(label = "Supabase") {
                Text(
                    text = replicaSupabaseLabel(replica),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (replica.seesSupabase == false) tokens.dangerInk else tokens.textPrimary,
                )
            }
            ReplicaCell(label = "Versión") {
                Text(text = replicaVersionLabel(replica), style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
                Text(text = replicaStartedLabel(replica, now), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
    }
}

@Composable
private fun ReplicaCell(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = GlassTheme.tokens.textSecondary)
        content()
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Alertas
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun AlertsSection(status: SystemStatus) {
    val alerts = status.alerts
    WorkspaceSection(
        icon = AppIcon.WARNING,
        role = when {
            alerts == null -> SemanticRole.NEUTRAL
            alerts.isEmpty() -> SemanticRole.SUCCESS
            alerts.any(::alertIsCritical) -> SemanticRole.DANGER
            else -> SemanticRole.WARNING
        },
        title = "Alertas activas",
        subtitle = "Las reglas de vigilancia que se están disparando ahora.",
        action = alerts?.takeIf { it.isNotEmpty() }?.let { list -> { WorkspacePill(text = list.size.toString()) } },
    ) {
        when {
            alerts == null -> UnreadNotice(status, missing = "No hay datos de las alertas")
            alerts.isEmpty() -> CalmLine("Ninguna alerta activa.")
            else -> alerts.forEach { alert -> AlertRow(alert) }
        }
    }
}

@Composable
private fun AlertRow(alert: SystemAlert) {
    WorkspaceNotice(
        tone = if (alertIsCritical(alert)) WorkspaceNoticeTone.DANGER else WorkspaceNoticeTone.WARNING,
        title = alert.name,
        detail = listOfNotNull(alertSeverityLabel(alert), alertDetail(alert)).joinToString(" · "),
    )
}

/** «No pasa nada» dicho con su icono, para que un bloque tranquilo no parezca un bloque vacío. */
@Composable
private fun CalmLine(text: String) {
    val tokens = GlassTheme.tokens
    WorkspacePanel {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            AppIconImage(AppIcon.CHECK, size = 14.dp, tint = tokens.successInk)
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = tokens.textPrimary)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Cuota de Supabase
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun QuotaSection(status: SystemStatus) {
    val quota = status.supabaseQuota
    val usage = quota?.let { quotaUsage(it.projectedMonthlyBytes, it.monthlyLimitBytes) }
    WorkspaceSection(
        icon = AppIcon.ANALYTICS,
        role = when (usage?.tone) {
            QuotaTone.OVER -> SemanticRole.DANGER
            QuotaTone.WARNING -> SemanticRole.WARNING
            QuotaTone.OK -> SemanticRole.INFO
            null -> SemanticRole.NEUTRAL
        },
        title = "Cuota de Supabase",
        subtitle = "Datos que Supabase envía al servidor. El plan incluye una cantidad al mes.",
    ) {
        if (quota == null) {
            UnreadNotice(status, missing = "No hay datos del consumo")
        } else {
            WorkspaceFacts(
                listOf(
                    WorkspaceFact("Últimas 24 horas", quota.bytes24h.toBytesLabel(), emphasised = quota.bytes24h != null),
                    WorkspaceFact("Últimos 7 días", quota.bytes7d.toBytesLabel(), emphasised = quota.bytes7d != null),
                ),
            )
            QuotaProjection(quota, usage)
            if (quota.byTarget.isNotEmpty()) {
                WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = "Por destino",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassTheme.tokens.textSecondary,
                    )
                    quota.byTarget.forEach { target ->
                        WorkspaceMetaLine(
                            label = quotaTargetLabel(target.target),
                            value = "${target.bytes24h.toBytesLabel()} en 24 h · ${target.bytes7d.toBytesLabel()} en 7 días",
                        )
                    }
                }
            }
            WorkspaceNotice(tone = WorkspaceNoticeTone.INFO, title = "Esta cifra es un mínimo", detail = QUOTA_MINIMUM_NOTICE)
        }
    }
}

@Composable
private fun QuotaProjection(quota: SupabaseQuota, usage: QuotaUsage?) {
    val tokens = GlassTheme.tokens
    WorkspacePanel(
        borderColor = when (usage?.tone) {
            QuotaTone.OVER -> tokens.danger.copy(alpha = 0.45f)
            QuotaTone.WARNING -> tokens.warning.copy(alpha = 0.45f)
            else -> null
        },
    ) {
        Text(text = "Proyección a 30 días", style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
        Text(
            text = quotaProjectionLabel(quota),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.textPrimary,
        )
        // Sin proyección o sin límite no hay barra: una barra vacía se leería como «consumo cero».
        if (usage != null) {
            val fill = when (usage.tone) {
                QuotaTone.OK -> tokens.success
                QuotaTone.WARNING -> tokens.warning
                QuotaTone.OVER -> tokens.danger
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(QUOTA_BAR_HEIGHT)
                    .clip(Radius.pill)
                    .background(tokens.surfaceMuted)
                    .drawBehind { drawRect(color = fill, size = Size(size.width * usage.barFraction, size.height)) }
                    .semantics { contentDescription = "Proyección: ${usage.percent} % del límite mensual" },
            )
            when (usage.tone) {
                QuotaTone.OVER -> Text(
                    text = "A este ritmo se supera el límite del mes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.dangerInk,
                )
                QuotaTone.WARNING -> Text(
                    text = "A este ritmo el mes acaba cerca del límite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.warningInk,
                )
                QuotaTone.OK -> Unit
            }
        }
        Text(text = quotaBasisLabel(quota.projectionBasis), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
    }
}

private val QUOTA_BAR_HEIGHT = 10.dp

// ---------------------------------------------------------------------------------------------------------------------
// Rendimiento
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun PerformanceSection(status: SystemStatus) {
    WorkspaceSection(
        icon = AppIcon.PERFORMANCE,
        role = SemanticRole.INFO,
        title = "Rendimiento",
        subtitle = "Carga del servidor y respuesta de la API.",
    ) {
        WorkspaceFacts(
            performanceRows(status).map { row ->
                WorkspaceFact(label = row.label, value = row.value, detail = row.detail, emphasised = row.measured)
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Versión desplegada
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun VersionSection(state: SystemStatusState) {
    val status = (state.uiState as? SystemStatusUiState.Content)?.status
    val version = status?.deployedVersion
    WorkspaceSection(
        icon = AppIcon.UPLOAD,
        role = if (version?.replicasDiffer == true) SemanticRole.WARNING else SemanticRole.NEUTRAL,
        title = "Versión desplegada",
        subtitle = "El código que corre en el servidor.",
    ) {
        when {
            status == null -> WorkspaceInfoLines(listOf("Se verá cuando haya una lectura del estado."))
            version == null -> UnreadNotice(status, missing = "No hay datos de la versión")
            else -> {
                WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    WorkspaceMetaLine("Versión", version.sha ?: "Ninguna réplica la publica")
                    WorkspaceMetaLine(
                        "Réplicas",
                        when {
                            version.replicasDiffer -> "Con versiones distintas"
                            version.sha == null -> UNAVAILABLE
                            else -> "Todas en la misma"
                        },
                    )
                }
                if (version.replicasDiffer) {
                    WorkspaceNotice(
                        tone = WorkspaceNoticeTone.WARNING,
                        title = "Despliegue a medias",
                        detail = "Las réplicas no corren todas la misma versión. Mira en «Réplicas» cuál se ha quedado atrás.",
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Esta sesión
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun SessionSection(state: SystemStatusState) {
    WorkspaceSection(
        icon = AppIcon.ROLES,
        role = SemanticRole.INFO,
        title = "Esta sesión",
        subtitle = "Con qué cuenta y desde qué panel estás mirando.",
    ) {
        val session = state.session
        WorkspacePanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            when (session) {
                is UiAsync.Success -> {
                    WorkspaceMetaLine("Cuenta", session.value.displayName ?: "Sin nombre visible")
                    WorkspaceMetaLine("Roles", sessionRolesLabel(session.value))
                }
                // En curso no es un esqueleto: son dos líneas que dicen que se está preguntando.
                UiAsync.Loading, UiAsync.Idle -> {
                    WorkspaceMetaLine("Cuenta", "Consultando…")
                    WorkspaceMetaLine("Roles", "Consultando…")
                }
                UiAsync.Empty, is UiAsync.Error -> {
                    WorkspaceMetaLine("Cuenta", UNAVAILABLE)
                    WorkspaceMetaLine("Roles", UNAVAILABLE)
                }
            }
            WorkspaceMetaLine("Entorno", state.environmentLabel.ifBlank { UNAVAILABLE })
            WorkspaceMetaLine("Versión del panel", state.panelVersion.ifBlank { UNAVAILABLE })
        }
        if (session is UiAsync.Error) {
            WorkspaceNotice(
                tone = WorkspaceNoticeTone.WARNING,
                title = "No se han podido leer tus roles",
                detail = session.error.humanMessage(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Qué hacer si…
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun GuidanceSection() {
    WorkspaceSection(
        icon = AppIcon.HELP,
        role = SemanticRole.NEUTRAL,
        title = "Qué hacer si…",
        subtitle = "Desde el panel no se toca la infraestructura.",
    ) {
        WorkspaceInfoLines(systemStatusGuidance)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Piezas compartidas
// ---------------------------------------------------------------------------------------------------------------------

/** Un bloque que no se ha podido leer lo DICE, con su causa: nunca queda en blanco ni a cero. */
@Composable
private fun UnreadNotice(status: SystemStatus, missing: String) {
    if (status.prometheusResponding == false) {
        WorkspaceNotice(
            tone = WorkspaceNoticeTone.WARNING,
            title = PROMETHEUS_SILENT,
            detail = "$missing. No significa que algo vaya mal: significa que no se puede comprobar.",
        )
    } else {
        WorkspaceNotice(
            tone = WorkspaceNoticeTone.INFO,
            title = missing,
            detail = "El servidor no ha enviado esta información. Puede que todavía no se esté midiendo en este despliegue.",
        )
    }
}

private fun SystemStatusLevel.tone(): StatusTone = when (this) {
    SystemStatusLevel.OPERATIVE -> StatusTone.POSITIVE
    SystemStatusLevel.DEGRADED -> StatusTone.ATTENTION
    SystemStatusLevel.DOWN -> StatusTone.NEGATIVE
    SystemStatusLevel.UNKNOWN -> StatusTone.UNKNOWN
}

private fun SystemStatusLevel.semanticRole(): SemanticRole = when (this) {
    SystemStatusLevel.OPERATIVE -> SemanticRole.SUCCESS
    SystemStatusLevel.DEGRADED -> SemanticRole.WARNING
    SystemStatusLevel.DOWN -> SemanticRole.DANGER
    SystemStatusLevel.UNKNOWN -> SemanticRole.NEUTRAL
}
