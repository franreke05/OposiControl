package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.model.UserDetailSection
import kotlin.math.roundToInt

/**
 * Ficha de solo lectura del usuario seleccionado.
 *
 * Regla que gobierna toda la pantalla: **una sección ausente se dice, no se
 * rellena**. La mayoría de estas tablas están aprovisionadas pero todavía vacías,
 * así que lo habitual es que falten secciones; enseñar ceros haría creer que el
 * usuario tiene racha 0 o nota 0 cuando en realidad no hay dato. Se distingue
 * además "no existe la fila" (NO_DATA) de "no se pudo leer" (NO_DISPONIBLE).
 */
@Composable
fun UserDetailPanel(state: UsersState) {
    AsyncStatePane(
        state = state.detail,
        emptyTitle = "Sin usuario seleccionado",
        emptyMessage = "Elige un usuario de la lista para ver su ficha completa.",
        emptyIcon = AppIcon.USERS,
    ) { detail ->
        // §32: las seis secciones existían ya, pero apiladas. Puestas una debajo
        // de otra son unos setenta campos en una sola columna, y encontrar la
        // racha o el saldo obliga a recorrerla entera. En pestañas, cada vista
        // responde a una pregunta concreta.
        //
        // La selección se recuerda con `rememberSaveable` y clavada al id del
        // usuario: al cambiar de usuario se vuelve a Perfil, porque seguir en
        // "Economía" mirando a otra persona es fácil de no notar.
        var selectedTab by rememberSaveable(detail.profile.userId) {
            mutableStateOf(UserDetailTab.PERFIL.id)
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            DesktopTabs(
                tabs = UserDetailTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
                selectedId = selectedTab,
                onSelect = { selectedTab = it },
            )
            when (selectedTab) {
                UserDetailTab.PERFIL.id -> UserProfileSection(detail)
                UserDetailTab.RENDIMIENTO.id -> UserPerformanceSection(detail)
                UserDetailTab.COMPETICION.id -> UserCompetitiveSection(detail)
                UserDetailTab.ECONOMIA.id -> UserEconomySection(detail)
                UserDetailTab.COMUNIDAD.id -> UserCommunitySection(detail)
                UserDetailTab.DEBILIDADES.id -> UserWeaknessSection(detail)
            }
        }
    }
}

/**
 * Pestañas de la ficha de usuario.
 *
 * El orden va de lo más consultado a lo más específico: quien abre una ficha casi
 * siempre viene a mirar quién es y cómo va, no sus debilidades por tema.
 */
private enum class UserDetailTab(val id: String, val label: String, val icon: AppIcon) {
    PERFIL("perfil", "Perfil", AppIcon.USERS),
    RENDIMIENTO("rendimiento", "Rendimiento", AppIcon.PERFORMANCE),
    COMPETICION("competicion", "Competición", AppIcon.RANKINGS),
    ECONOMIA("economia", "Economía", AppIcon.TICKETS),
    COMUNIDAD("comunidad", "Comunidad", AppIcon.FORUM),
    DEBILIDADES("debilidades", "Debilidades", AppIcon.ANALYTICS),
}

@Composable
private fun UserProfileSection(detail: UserDetail) {
    FormSection(
        title = "Perfil",
        description = "Identidad y datos de onboarding.",
        icon = AppIcon.USERS,
        iconRole = SemanticRole.INFO,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Nombre", detail.profile.displayName)
            Field(USER_ACCOUNT_FIELD_LABEL, detail.profile.username)
            Field("Alta", detail.profile.createdAt)

            // Aquí había un `Field("ID", detail.profile.userId)`. Era el UUID de
            // Supabase presentado como identidad principal del usuario: no
            // identifica a nadie para quien administra, no se puede buscar por él
            // y expone la clave interna en cualquier captura de pantalla.
            //
            // El correo real tampoco está: vive en `auth.users` y esta pantalla no
            // lo consulta. Se dice en vez de dejar el hueco.
            Text(
                text = USER_EMAIL_UNAVAILABLE_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = GlassTheme.tokens.textSecondary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Tono explícito: «invitado» y «registrado» no están en el
                // vocabulario de `statusToneFor`, y por defecto caerían las dos en
                // UNKNOWN. Ninguna de las dos es un problema, así que ninguna se
                // pinta como tal.
                StatusChip(
                    status = if (detail.profile.isGuest) "Invitado" else "Registrado",
                    tone = if (detail.profile.isGuest) StatusTone.NEUTRAL else StatusTone.POSITIVE,
                )
                StatusChip(
                    status = if (detail.profile.onboardingCompleted) "Onboarding completo" else "Onboarding pendiente",
                    tone = if (detail.profile.onboardingCompleted) StatusTone.POSITIVE else StatusTone.ATTENTION,
                )
            }

            val extended = detail.extendedProfile
            when {
                detail.isUnavailable(UserDetailSection.EXTENDED_PROFILE) -> Unavailable()
                extended == null -> NoData("El usuario aún no ha completado el perfil ampliado.")
                else -> {
                    Field("Nombre completo", extended.fullName)
                    Field("Edad", extended.age?.toString())
                    Field("Ciudad", extended.city)
                    Field("Situación laboral", extended.employmentStatus)
                    Field("Centro de trabajo", extended.workplace)
                    Field(
                        "Tiempo disponible",
                        extended.availableStudyMinutes?.let { "$it min/día" },
                    )
                    Field("Franja preferente", extended.preferredStudySlot)
                    Field("Examen objetivo", extended.targetExamDate)
                }
            }
        }
    }
}

@Composable
private fun UserPerformanceSection(detail: UserDetail) {
    FormSection(
        title = "Rendimiento",
        description = "Actividad acumulada de estudio.",
        help = Help.notaMedia,
        icon = AppIcon.PERFORMANCE,
        iconRole = SemanticRole.INSIGHT,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val performance = detail.performance
            when {
                detail.isUnavailable(UserDetailSection.PERFORMANCE) -> Unavailable()
                performance == null -> NoData("Sin estadísticas registradas todavía.")
                else -> {
                    Field("Exámenes finalizados", performance.finishedExams.toString())
                    Field("Preguntas respondidas", performance.answeredQuestions.toString())
                    Field("Aciertos", performance.correctAnswers.toString())
                    // No hay columna de falladas: se deriva de respondidas - correctas.
                    Field("Fallos", performance.failedAnswers.toString())
                    Field("Porcentaje de acierto", "${performance.successRate.roundToInt()}%")
                    Field("Nivel", performance.level.toString())
                }
            }

            val streak = detail.streak
            when {
                detail.isUnavailable(UserDetailSection.STREAK) -> Unavailable()
                streak == null -> NoData("Sin racha registrada.")
                else -> {
                    Field("Racha actual", "${streak.currentDays} días")
                    Field("Mejor racha", "${streak.bestDays} días")
                    Field("Última actividad", streak.lastActivityDate)
                }
            }
        }
    }
}

@Composable
private fun UserCompetitiveSection(detail: UserDetail) {
    FormSection(
        title = "Liga y ranking",
        description = "Nota media móvil, evolución y posición.",
        // De las tres, «evolución» es la que más se malinterpreta: no es la nota,
        // es la diferencia contra la referencia anterior.
        help = Help.evolucion,
        icon = AppIcon.RANKINGS,
        iconRole = SemanticRole.INSIGHT,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val competitive = detail.competitive
            when {
                detail.isUnavailable(UserDetailSection.COMPETITIVE) -> Unavailable()
                competitive == null -> NoData(
                    "El usuario todavía no compite: el estado competitivo se crea al alcanzar " +
                        "el mínimo de preguntas de la primera liga.",
                )
                else -> {
                    Field("Liga", competitive.leagueName ?: competitive.leagueCode)
                    Field(
                        "Ventana de cálculo",
                        competitive.calculationWindow?.let { "últimas $it preguntas" },
                    )
                    Field("Preguntas válidas", competitive.rankingEligibleQuestions?.toString())
                    Field("Nota media", competitive.currentAverage?.let { formatScore(it) })
                    Field("Evolución", competitive.evolution?.let { formatSignedScore(it) })
                    // Sin este contador no se sabe si la evolución es reciente o
                    // lleva tiempo congelada esperando al siguiente bloque.
                    Field(
                        "Preguntas desde la última evolución",
                        competitive.questionsSinceLastEvolution?.toString(),
                    )
                    Field("Posición en liga", competitive.leagueRank?.toString())
                    Field("Posición global", competitive.globalRank?.toString())
                    competitive.globalPercentile?.let { percentile ->
                        Field("Percentil global", "${percentile.roundToInt()}%")
                        // Etiquetado explícito: es una comparativa interna de la app,
                        // no una probabilidad oficial de obtener plaza.
                        Text(
                            text = "Indicador comparativo interno frente a otros usuarios. " +
                                "No es una probabilidad oficial de obtener plaza.",
                            style = MaterialTheme.typography.bodySmall,
                            color = GlassTheme.tokens.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserEconomySection(detail: UserDetail) {
    FormSection(
        title = "Economía",
        description = "Saldo, plan y suscripciones.",
        help = Help.saldo,
        icon = AppIcon.TICKETS,
        iconRole = SemanticRole.COMMERCE,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val economy = detail.economy
            when {
                detail.isUnavailable(UserDetailSection.ECONOMY) -> Unavailable()
                economy == null -> NoData("Sin cartera ni estado premium registrados.")
                else -> {
                    Field("Saldo OposiTickets", economy.ticketBalance.toString())
                    Field("Plan", economy.productCode ?: "gratuito")
                    Field("Estado", economy.subscriptionStatus)
                    Field("Proveedor", economy.provider)
                    Field("Renovación", economy.renewsAt)
                    Field("Fin", economy.endsAt)
                }
            }

            if (detail.isUnavailable(UserDetailSection.SUBSCRIPTIONS)) {
                Unavailable()
            } else if (detail.subscriptions.isNotEmpty()) {
                Text("Historial de suscripciones", style = MaterialTheme.typography.labelMedium)
                AdminTable(
                    // Alto acotado a propósito: `AdminTable` monta un `LazyColumn`
                    // y la columna de detalle desplaza. Un hijo perezoso sin techo
                    // dentro de un padre desplazable se mide con altura infinita y
                    // revienta en tiempo de medición.
                    modifier = Modifier.heightIn(max = TABLE_MAX_HEIGHT),
                    columns = listOf(
                        AdminTableColumn("Producto", 1.0f),
                        AdminTableColumn("Estado", 0.7f),
                        AdminTableColumn("Proveedor", 0.7f),
                        AdminTableColumn("Inicio", 0.9f),
                    ),
                    rows = detail.subscriptions.map { subscription ->
                        AdminTableRow(
                            key = subscription.id,
                            cells = listOf(
                                subscription.productCode,
                                subscription.status,
                                subscription.provider,
                                subscription.startedAt.orEmpty(),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun UserCommunitySection(detail: UserDetail) {
    FormSection(
        title = "Comunidad",
        description = "Aportaciones como OposiTester.",
        icon = AppIcon.TESTER,
        iconRole = SemanticRole.INSIGHT,
    ) {
        val tester = detail.testerState
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                detail.isUnavailable(UserDetailSection.TESTER) -> Unavailable()
                tester == null -> NoData("El usuario aún no ha enviado reportes.")
                else -> {
                    Field("Reportes enviados", tester.reportsSubmitted.toString())
                    Field("Reportes validados", tester.reportsValidated.toString())
                    Field("OposiTickets ganados", tester.ticketsEarned.toString())
                    Field("Nivel", tester.level)
                }
            }
        }
    }
}

@Composable
private fun UserWeaknessSection(detail: UserDetail) {
    FormSection(
        title = "Debilidades por etiqueta",
        description = "Peor porcentaje de acierto primero.",
        icon = AppIcon.ANALYTICS,
        iconRole = SemanticRole.INSIGHT,
    ) {
        when {
            detail.isUnavailable(UserDetailSection.WEAKNESSES) -> Unavailable()
            detail.weaknesses.isEmpty() -> NoData("Sin práctica suficiente por etiqueta.")
            else -> AdminTable(
                // Ver la nota de «Historial de suscripciones»: mismo motivo.
                modifier = Modifier.heightIn(max = TABLE_MAX_HEIGHT),
                columns = listOf(
                    AdminTableColumn("Etiqueta", 1.2f),
                    AdminTableColumn("Respondidas", 0.7f, numeric = true),
                    AdminTableColumn("Fallos", 0.6f, numeric = true),
                    AdminTableColumn("Acierto", 0.6f, numeric = true),
                    AdminTableColumn("Dominio", 0.7f),
                ),
                rows = detail.weaknesses.map { weakness ->
                    AdminTableRow(
                        key = weakness.tagId,
                        cells = listOf(
                            weakness.tag,
                            weakness.answered.toString(),
                            weakness.failed.toString(),
                            "${weakness.successRate.roundToInt()}%",
                            weakness.masteryLevel.orEmpty(),
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = GlassTheme.tokens.textSecondary,
            modifier = Modifier.width(190.dp),
        )
        Text(
            // Un campo vacío se marca; no se deja en blanco, que se confunde con
            // un valor legítimamente vacío.
            text = value?.takeIf { it.isNotBlank() } ?: NO_DATA_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = GlassTheme.tokens.textPrimary,
            fontWeight = if (value.isNullOrBlank()) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

@Composable
private fun NoData(reason: String) {
    Text(
        text = "$NO_DATA_TEXT — $reason",
        style = MaterialTheme.typography.bodySmall,
        color = GlassTheme.tokens.textSecondary,
    )
}

@Composable
private fun Unavailable() {
    Text(
        text = "$NOT_AVAILABLE_TEXT — no se pudo leer esta sección.",
        style = MaterialTheme.typography.bodySmall,
        color = GlassTheme.tokens.danger,
    )
}

private const val NO_DATA_TEXT = "NO_DATA"
private const val NOT_AVAILABLE_TEXT = "NO_DISPONIBLE"

/**
 * Techo de las tablas de la ficha.
 *
 * Son listas cortas —suscripciones de un usuario, sus etiquetas peor dominadas—,
 * así que el tope rara vez se alcanza; existe para que el `LazyColumn` interno
 * tenga una altura finita dentro de la columna que desplaza.
 */
private val TABLE_MAX_HEIGHT = 320.dp

/** Nota sobre 10 con un decimal, como se presenta al usuario final. */
internal fun formatScore(score: Double): String {
    val tenths = (score * 10).roundToInt()
    return "${tenths / 10},${tenths % 10}"
}

/** Evolución con signo explícito: "+0,8" se lee distinto que "0,8". */
internal fun formatSignedScore(delta: Double): String {
    val tenths = (delta * 10).roundToInt()
    val sign = if (tenths >= 0) "+" else "-"
    val absolute = if (tenths < 0) -tenths else tenths
    return "$sign${absolute / 10},${absolute % 10}"
}
