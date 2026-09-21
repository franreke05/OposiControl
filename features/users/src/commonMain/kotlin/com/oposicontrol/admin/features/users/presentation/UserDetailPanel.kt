package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AdminTable
import com.oposicontrol.admin.core.ui.components.AdminTableColumn
import com.oposicontrol.admin.core.ui.components.AdminTableRow
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.model.UserDetailSection
import com.oposicontrol.admin.domain.model.UserExtendedProfile
import kotlin.math.roundToInt

/**
 * La ficha del usuario seleccionado: cabecera (identidad, acciones, métricas y
 * pestañas) y, debajo, el contenido de la pestaña abierta.
 *
 * Regla que gobierna toda la pantalla: **una sección ausente se dice, no se
 * rellena**. La mayoría de estas tablas están aprovisionadas pero todavía vacías,
 * así que lo habitual es que falten secciones; enseñar ceros haría creer que el
 * usuario tiene racha 0 o nota 0 cuando en realidad no hay dato. Se distingue
 * además "no existe la fila" (`NO_DATA`) de "no se pudo leer" (`NO_DISPONIBLE`), y
 * cada tarjeta lo dice por su cuenta: que falle una sección no tumba la ficha.
 *
 * La selección de pestaña se recuerda clavada al id del usuario: al cambiar de
 * usuario se vuelve a Perfil, porque seguir en «Economía» mirando a otra persona
 * es fácil de no notar.
 */
@Composable
internal fun UserDetailWorkspace(
    state: UsersState,
    onIntent: (UsersIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val user = state.selectedUser ?: return
    var selectedTab by rememberSaveable(user.id) { mutableStateOf(UserDetailTab.PERFIL.id) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        UserSummaryCard(
            state = state,
            user = user,
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            onIntent = onIntent,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val twoColumns = maxWidth >= TWO_COLUMN_MIN_WIDTH
            // Las tarjetas de una sola pieza (Competición, Economía, Comunidad) reparten sus
            // filas en dos columnas cuando hay ancho de sobra.
            val rowColumns = if (maxWidth >= 900.dp) 2 else 1
            when (selectedTab) {
                UserDetailTab.PERFIL.id -> UserProfileTab(state, onIntent, twoColumns)
                else -> AsyncStatePane(
                    state = state.detail,
                    emptyTitle = "Sin usuario seleccionado",
                    emptyMessage = "Elige un usuario de la lista para ver su ficha completa.",
                    emptyIcon = AppIcon.USERS,
                ) { detail ->
                    when (selectedTab) {
                        UserDetailTab.RENDIMIENTO.id -> UserPerformanceTab(detail, twoColumns)
                        UserDetailTab.COMPETICION.id -> UserCompetitiveCard(detail, rowColumns)
                        UserDetailTab.ECONOMIA.id -> UserEconomyTab(detail, rowColumns)
                        UserDetailTab.COMUNIDAD.id -> UserCommunityCard(detail, rowColumns)
                        UserDetailTab.DEBILIDADES.id -> UserWeaknessCard(detail)
                    }
                }
            }
        }
    }
}

/** Ancho del contenido a partir del cual las tarjetas de la ficha se reparten en dos columnas. */
private val TWO_COLUMN_MIN_WIDTH = 920.dp

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

// ---------------------------------------------------------------------------
// Cabecera: identidad, acciones, métricas y pestañas
// ---------------------------------------------------------------------------

@Composable
private fun UserSummaryCard(
    state: UsersState,
    user: User,
    selectedTab: String,
    onSelectTab: (String) -> Unit,
    onIntent: (UsersIntent) -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            UserHero(state = state, user = user, onIntent = onIntent)
            UserKpiStrip(kpis = userKpis(state.detail))
            // Segundo nivel de navegación: las pestañas de la persona. Distinto de las
            // globales (Usuarios · Competición · Accesos) porque cuelga de esta tarjeta.
            DesktopTabs(
                tabs = UserDetailTab.entries.map { DesktopTab(it.id, it.label, it.icon) },
                selectedId = selectedTab,
                onSelect = onSelectTab,
                variant = DesktopTabsVariant.WORKSPACE,
            )
        }
    }
}

@Composable
private fun UserHero(state: UsersState, user: User, onIntent: (UsersIntent) -> Unit) {
    val tokens = GlassTheme.tokens
    val name = userDisplayName(user)
    val kind = userAccountKind(user)
    val population = userPopulation(user, state.adminRoleIds)
    val loaded = (state.detail as? UiAsync.Success)?.value
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val actionsBelow = maxWidth < 940.dp
        val identity: @Composable (Modifier) -> Unit = { mod ->
            Row(
                modifier = mod,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(userId = user.id, displayName = user.fullName, accountName = userAccountName(user), size = 64.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = userAccountName(user),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        UserPill(
                            text = userAccountKindLabel(kind),
                            tone = if (kind == UserAccountKind.REGISTRADO) UserPillTone.POSITIVE else UserPillTone.NEUTRAL,
                        )
                        UserPill(
                            text = userPopulationLabel(population),
                            tone = if (population == UserPopulation.ADMINISTRACION) UserPillTone.ADMIN else UserPillTone.INFO,
                        )
                        user.roleIds.take(3).forEach { roleId ->
                            UserPill(
                                text = roleLabel(roleId, state.rolesById),
                                tone = if (roleId in state.adminRoleIds) UserPillTone.ADMIN else UserPillTone.INFO,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIconImage(AppIcon.CLOCK, size = 13.dp, tint = tokens.textSecondary)
                            Text(
                                text = "Alta ${userSignupDate(user)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.textSecondary,
                            )
                        }
                        loaded?.let { detail ->
                            detail.streak?.lastActivityDate?.takeIf { it.isNotBlank() }?.let { last ->
                                Text(
                                    text = "Última actividad $last",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
        val actions: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(
                    text = "Conceder OposiTickets",
                    onClick = { onIntent(UsersIntent.OpenTicketsDialog) },
                    size = ButtonSize.COMPACT,
                    icon = { AppIconImage(AppIcon.TICKETS, size = 14.dp) },
                )
                SecondaryButton(
                    text = "Registrar advertencia",
                    onClick = { onIntent(UsersIntent.OpenWarningDialog) },
                    size = ButtonSize.COMPACT,
                    icon = { AppIconImage(AppIcon.WARNING, size = 14.dp) },
                )
            }
        }
        if (actionsBelow) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                identity(Modifier.fillMaxWidth())
                actions()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.Top,
            ) {
                identity(Modifier.weight(1f))
                actions()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pestaña Perfil
// ---------------------------------------------------------------------------

@Composable
private fun UserProfileTab(state: UsersState, onIntent: (UsersIntent) -> Unit, twoColumns: Boolean) {
    val detail = state.detail.asDetail()
    UserColumns(
        twoColumns = twoColumns,
        leftWeight = 1.3f,
        left = {
            UserBasicInfoCard(state = state, detail = detail, onIntent = onIntent)
            UserRolesCard(state = state, onIntent = onIntent)
            UserSaveCard(state = state, onIntent = onIntent)
        },
        right = {
            UserLocationCard(detail)
            UserStudyProfileCard(detail)
            UserPlanCard(detail)
            UserActionsCard(state = state, onIntent = onIntent)
        },
    )
}

@Composable
private fun UserLocationCard(detail: UiAsyncDetail) {
    FormSection(
        title = "Ubicación",
        description = "Dónde vive y trabaja el usuario.",
        icon = AppIcon.LOCATION,
        iconRole = SemanticRole.INFO,
    ) {
        UserSectionBody(detail) { d ->
            ExtendedProfileRows(d) { extended ->
                listOf(
                    "Ciudad" to extended.city,
                    "Situación laboral" to extended.employmentStatus,
                    "Centro de trabajo" to extended.workplace,
                )
            }
        }
    }
}

@Composable
private fun UserStudyProfileCard(detail: UiAsyncDetail) {
    FormSection(
        title = "Perfil ampliado",
        description = "Datos de onboarding y preparación.",
        icon = AppIcon.PERFORMANCE,
        iconRole = SemanticRole.INSIGHT,
    ) {
        UserSectionBody(detail) { d ->
            ExtendedProfileRows(d) { extended ->
                listOf(
                    "Nombre completo" to extended.fullName,
                    "Edad" to extended.age?.toString(),
                    "Tiempo disponible" to extended.availableStudyMinutes?.let { "$it min/día" },
                    "Franja preferente" to extended.preferredStudySlot,
                    "Examen objetivo" to extended.targetExamDate,
                )
            }
        }
    }
}

@Composable
private fun ExtendedProfileRows(detail: UserDetail, rows: (UserExtendedProfile) -> List<Pair<String, String?>>) {
    val extended = detail.extendedProfile
    when {
        detail.isUnavailable(UserDetailSection.EXTENDED_PROFILE) -> UserUnavailable()
        extended == null -> UserNoData("El usuario aún no ha completado el perfil ampliado.")
        else -> UserInfoRows(rows(extended))
    }
}

@Composable
private fun UserPlanCard(detail: UiAsyncDetail) {
    FormSection(
        title = "Plan actual",
        description = "Suscripción y estado del plan.",
        icon = AppIcon.PLANS,
        iconRole = SemanticRole.COMMERCE,
    ) {
        UserSectionBody(detail) { d ->
            val economy = d.economy
            when {
                d.isUnavailable(UserDetailSection.ECONOMY) -> UserUnavailable()
                economy == null -> UserNoData("Sin cartera ni estado premium registrados.")
                else -> UserInfoRows(
                    listOf(
                        "Plan" to (economy.productCode ?: "gratuito"),
                        "Estado" to economy.subscriptionStatus,
                        "Proveedor" to economy.provider,
                        "Renovación" to economy.renewsAt,
                        "Fin" to economy.endsAt,
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Resto de pestañas
// ---------------------------------------------------------------------------

@Composable
private fun UserPerformanceTab(detail: UserDetail, twoColumns: Boolean) {
    UserColumns(
        twoColumns = twoColumns,
        leftWeight = 1f,
        left = {
            FormSection(
                title = "Rendimiento",
                description = "Actividad acumulada de estudio.",
                help = Help.notaMedia,
                icon = AppIcon.PERFORMANCE,
                iconRole = SemanticRole.INSIGHT,
            ) {
                val performance = detail.performance
                when {
                    detail.isUnavailable(UserDetailSection.PERFORMANCE) -> UserUnavailable()
                    performance == null -> UserNoData("Sin estadísticas registradas todavía.")
                    else -> UserInfoRows(
                        listOf(
                            "Exámenes finalizados" to performance.finishedExams.toString(),
                            "Preguntas respondidas" to performance.answeredQuestions.toString(),
                            "Aciertos" to performance.correctAnswers.toString(),
                            // No hay columna de falladas: se deriva de respondidas - correctas.
                            "Fallos" to performance.failedAnswers.toString(),
                            "Porcentaje de acierto" to "${performance.successRate.roundToInt()}%",
                            "Nivel" to performance.level.toString(),
                        ),
                    )
                }
            }
        },
        right = {
            FormSection(
                title = "Racha",
                description = "Días seguidos de estudio.",
                icon = AppIcon.CLOCK,
                iconRole = SemanticRole.DANGER,
            ) {
                val streak = detail.streak
                when {
                    detail.isUnavailable(UserDetailSection.STREAK) -> UserUnavailable()
                    streak == null -> UserNoData("Sin racha registrada.")
                    else -> UserInfoRows(
                        listOf(
                            "Racha actual" to "${streak.currentDays} días",
                            "Mejor racha" to "${streak.bestDays} días",
                            "Última actividad" to streak.lastActivityDate,
                        ),
                    )
                }
            }
        },
    )
}

@Composable
private fun UserCompetitiveCard(detail: UserDetail, rowColumns: Int) {
    FormSection(
        title = "Liga y ranking",
        description = "Nota media móvil, evolución y posición.",
        // De las tres, «evolución» es la que más se malinterpreta: no es la nota,
        // es la diferencia contra la referencia anterior.
        help = Help.evolucion,
        icon = AppIcon.RANKINGS,
        iconRole = SemanticRole.COMMERCE,
    ) {
        val competitive = detail.competitive
        when {
            detail.isUnavailable(UserDetailSection.COMPETITIVE) -> UserUnavailable()
            competitive == null -> UserNoData(
                "El usuario todavía no compite: el estado competitivo se crea al alcanzar " +
                    "el mínimo de preguntas de la primera liga.",
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                UserInfoRows(
                    listOf(
                        "Liga" to (competitive.leagueName ?: competitive.leagueCode),
                        "Ventana de cálculo" to competitive.calculationWindow?.let { "últimas $it preguntas" },
                        "Preguntas válidas" to competitive.rankingEligibleQuestions?.toString(),
                        "Nota media" to competitive.currentAverage?.let { formatScore(it) },
                        "Evolución" to competitive.evolution?.let { formatSignedScore(it) },
                        // Sin este contador no se sabe si la evolución es reciente o lleva
                        // tiempo congelada esperando al siguiente bloque.
                        "Preguntas desde la última evolución" to competitive.questionsSinceLastEvolution?.toString(),
                        "Posición en liga" to competitive.leagueRank?.toString(),
                        "Posición global" to competitive.globalRank?.toString(),
                    ) + listOfNotNull(
                        competitive.globalPercentile?.let { "Percentil global" to "${it.roundToInt()}%" },
                    ),
                    columns = rowColumns,
                )
                if (competitive.globalPercentile != null) {
                    // Etiquetado explícito: es una comparativa interna de la app, no una
                    // probabilidad oficial de obtener plaza.
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

@Composable
private fun UserEconomyTab(detail: UserDetail, rowColumns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        FormSection(
            title = "Economía",
            description = "Saldo, plan y suscripciones.",
            help = Help.saldo,
            icon = AppIcon.TICKETS,
            iconRole = SemanticRole.COMMERCE,
        ) {
            val economy = detail.economy
            when {
                detail.isUnavailable(UserDetailSection.ECONOMY) -> UserUnavailable()
                economy == null -> UserNoData("Sin cartera ni estado premium registrados.")
                else -> UserInfoRows(
                    listOf(
                        "Saldo OposiTickets" to economy.ticketBalance.toString(),
                        "Plan" to (economy.productCode ?: "gratuito"),
                        "Estado" to economy.subscriptionStatus,
                        "Proveedor" to economy.provider,
                        "Renovación" to economy.renewsAt,
                        "Fin" to economy.endsAt,
                    ),
                    columns = rowColumns,
                )
            }
        }

        if (detail.isUnavailable(UserDetailSection.SUBSCRIPTIONS)) {
            FormSection(
                title = "Historial de suscripciones",
                icon = AppIcon.PLANS,
                iconRole = SemanticRole.COMMERCE,
            ) { UserUnavailable() }
        } else if (detail.subscriptions.isNotEmpty()) {
            FormSection(
                title = "Historial de suscripciones",
                icon = AppIcon.PLANS,
                iconRole = SemanticRole.COMMERCE,
            ) {
                AdminTable(
                    // Alto acotado a propósito: `AdminTable` monta un `LazyColumn` y la
                    // columna de detalle desplaza. Un hijo perezoso sin techo dentro de
                    // un padre desplazable se mide con altura infinita y revienta en
                    // tiempo de medición.
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
private fun UserCommunityCard(detail: UserDetail, rowColumns: Int) {
    FormSection(
        title = "Comunidad",
        description = "Aportaciones como OposiTester.",
        icon = AppIcon.TESTER,
        iconRole = SemanticRole.INSIGHT,
    ) {
        val tester = detail.testerState
        when {
            detail.isUnavailable(UserDetailSection.TESTER) -> UserUnavailable()
            tester == null -> UserNoData("El usuario aún no ha enviado reportes.")
            else -> UserInfoRows(
                listOf(
                    "Reportes enviados" to tester.reportsSubmitted.toString(),
                    "Reportes validados" to tester.reportsValidated.toString(),
                    "OposiTickets ganados" to tester.ticketsEarned.toString(),
                    "Nivel" to tester.level,
                ),
                columns = rowColumns,
            )
        }
    }
}

@Composable
private fun UserWeaknessCard(detail: UserDetail) {
    FormSection(
        title = "Debilidades por etiqueta",
        description = "Peor porcentaje de acierto primero.",
        icon = AppIcon.ANALYTICS,
        iconRole = SemanticRole.INSIGHT,
    ) {
        when {
            detail.isUnavailable(UserDetailSection.WEAKNESSES) -> UserUnavailable()
            detail.weaknesses.isEmpty() -> UserNoData("Sin práctica suficiente por etiqueta.")
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

// ---------------------------------------------------------------------------
// Andamiaje
// ---------------------------------------------------------------------------

/** Dos columnas de tarjetas con pesos, o una sola apilada cuando no hay ancho. */
@Composable
private fun UserColumns(
    twoColumns: Boolean,
    leftWeight: Float,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    if (twoColumns) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(leftWeight), verticalArrangement = Arrangement.spacedBy(Spacing.md)) { left() }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) { right() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            left()
            right()
        }
    }
}

/** El cuerpo de una tarjeta que depende de la ficha: dice si carga, si falló o si no hay ficha. */
@Composable
private fun UserSectionBody(detail: UiAsyncDetail, content: @Composable (UserDetail) -> Unit) {
    when (detail) {
        is UiAsyncDetail.Loaded -> content(detail.detail)
        UiAsyncDetail.Loading -> UserLoadingNote()
        UiAsyncDetail.Failed -> UserUnavailable()
        UiAsyncDetail.Missing -> UserNoData("No hay ficha de este usuario.")
    }
}

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
