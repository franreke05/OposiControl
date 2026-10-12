package com.oposicontrol.admin.features.users.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.color
import com.oposicontrol.admin.core.ui.components.tint
import com.oposicontrol.admin.core.ui.layout.desktopBreakpointFor
import com.oposicontrol.admin.core.ui.layout.supportsMasterDetail
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.AccountStatus
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.model.User
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import com.oposicontrol.admin.core.ui.theme.OposiMotion
import com.oposicontrol.admin.core.ui.theme.motionTween
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.graphics.compositeOver

/**
 * Piezas VISUALES de la pantalla de Usuarios.
 *
 * Nada de aquí lee ni escribe estado de negocio: reciben lo que hay que pintar y
 * devuelven el clic. Las superficies y las cabeceras de bloque son las del sistema
 * de diseño (`FormSection`); aquí sólo vive lo que ese sistema no tiene —el avatar,
 * la fila de usuario, la fila de ficha y la banda de métricas—.
 */

// ---------------------------------------------------------------------------
// Master–detail
// ---------------------------------------------------------------------------

/**
 * Lista a la izquierda (~35 %), ficha a la derecha (~65 %).
 *
 * Usa el mismo régimen de ventana que `CrudSplitLayout` —por debajo del umbral de
 * master-detail se apila, como hasta ahora—, pero con el reparto de la referencia:
 * la ficha es donde se trabaja y lleva claramente más peso que el catálogo.
 * En modo apilado la lista tiene un alto propio (no se queda con toda la pantalla y
 * deja la ficha fuera de alcance).
 */
@Composable
internal fun UsersSplit(
    modifier: Modifier = Modifier,
    listPane: @Composable () -> Unit,
    /** [embedded] es `true` cuando la pantalla apila y desplaza en bloque: la ficha no debe desplazar por su cuenta. */
    detailPane: @Composable (embedded: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val breakpoint = desktopBreakpointFor(maxWidth)
        if (!breakpoint.supportsMasterDetail) {
            // Apilado: un único scroll para toda la pantalla, la lista con alto propio y la
            // ficha debajo a su altura natural. Con dos scrolls anidados la ficha quedaba
            // en una ventana de cien píxeles.
            val pageScroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(pageScroll),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Box(modifier = Modifier.fillMaxWidth().height(STACKED_LIST_HEIGHT)) { listPane() }
                    Box(modifier = Modifier.fillMaxWidth()) { detailPane(true) }
                }
                DesktopVerticalScrollbar(pageScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        } else {
            val listWeight = if (breakpoint.isWide) 0.35f else 0.38f
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Box(
                    modifier = Modifier
                        .weight(listWeight)
                        .widthIn(min = 300.dp, max = 640.dp)
                        .fillMaxSize(),
                ) { listPane() }
                Box(modifier = Modifier.weight(1f - listWeight).fillMaxSize()) { detailPane(false) }
            }
        }
    }
}

private val STACKED_LIST_HEIGHT = 420.dp

// ---------------------------------------------------------------------------
// Avatar
// ---------------------------------------------------------------------------

/**
 * La TINTA de una familia semántica: el tono que llega a 4,5:1 sobre su propio tinte.
 * `SemanticRole.color()` es el tono base (de relleno) y en claro se queda en ~2,8:1 para
 * el verde y en ~3,6:1 para el azul.
 */
@Composable
internal fun userRoleInk(role: SemanticRole): Color {
    val tokens = GlassTheme.tokens
    return when (role) {
        SemanticRole.SUCCESS -> tokens.successInk
        SemanticRole.INFO -> tokens.infoInk
        SemanticRole.DANGER -> tokens.dangerInk
        SemanticRole.WARNING, SemanticRole.COMMERCE -> tokens.warningInk
        SemanticRole.BRAND -> tokens.accentInk
        SemanticRole.NEUTRAL -> tokens.textSecondary
        SemanticRole.INSIGHT -> role.color()
    }
}

/** Círculo con las iniciales. El color sólo distingue filas: no significa estado ni rol. */
@Composable
internal fun UserAvatar(
    userId: String,
    displayName: String,
    accountName: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val role = userAvatarRole(userId)
    val ink = userRoleInk(role)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(role.tint(alpha = 0.12f))
            .border(1.dp, ink.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = userInitials(displayName, accountName),
            style = if (size >= 48.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ink,
        )
    }
}

// ---------------------------------------------------------------------------
// Pastillas
// ---------------------------------------------------------------------------

/** El significado de una pastilla: qué familia de color le toca. */
internal enum class UserPillTone { POSITIVE, NEUTRAL, INFO, ADMIN, ATTENTION }

/** Tono de la pastilla del estado de cuenta: activa en positivo; bloqueada y eliminación pendiente piden atención. */
internal fun userAccountStatusTone(status: AccountStatus): UserPillTone = when (status) {
    AccountStatus.ACTIVE -> UserPillTone.POSITIVE
    AccountStatus.BLOCKED, AccountStatus.DELETION_PENDING -> UserPillTone.ATTENTION
    AccountStatus.UNKNOWN -> UserPillTone.NEUTRAL
}

/** Pastilla pequeña de la fila y de la cabecera de la ficha. El texto lleva el dato; el color acompaña. */
@Composable
internal fun UserPill(text: String, tone: UserPillTone, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    // El fondo se compone SOBRE la superficie sólida y sale opaco: así el contraste del
    // texto no depende de lo que haya detrás (una fila seleccionada está tintada de azul).
    val base = tokens.surfaceSolid
    val (ink, fill) = when (tone) {
        UserPillTone.POSITIVE -> tokens.successInk to tokens.success.copy(alpha = 0.10f).compositeOver(base)
        UserPillTone.NEUTRAL -> tokens.textSecondary to tokens.surfaceMuted
        UserPillTone.INFO -> tokens.infoInk to tokens.info.copy(alpha = 0.10f).compositeOver(base)
        UserPillTone.ADMIN -> tokens.warningInk to tokens.warning.copy(alpha = 0.12f).compositeOver(base)
        UserPillTone.ATTENTION -> tokens.warningInk to tokens.warning.copy(alpha = 0.10f).compositeOver(base)
    }
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------------------
// Fila de la lista
// ---------------------------------------------------------------------------

/**
 * Una fila del catálogo: tarjeta compacta, no una fila de tabla.
 *
 * Avatar, nombre y cuenta a la izquierda; a la derecha el tipo de cuenta, el rol y
 * el alta. La fila seleccionada se distingue por borde y tinte, sin saturar.
 */
@Composable
internal fun UserRowCard(
    user: User,
    selected: Boolean,
    rolesById: Map<String, Role>,
    adminRoleIds: Set<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val shape = RoundedCornerShape(12.dp)
    val name = userDisplayName(user)
    val kind = userAccountKind(user)
    val population = userPopulation(user, adminRoleIds)
    val roleNames = user.roleIds.map { roleLabel(it, rolesById) }
    val roleText = when {
        roleNames.isEmpty() -> "Sin rol"
        roleNames.size == 1 -> roleNames.first()
        else -> "${roleNames.first()} +${roleNames.size - 1}"
    }
    // Mismos colores de reposo que antes; lo que cambia es que se llega a ellos con un fundido corto, y que la fila
    // responde al puntero como las de los demas catalogos.
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = when {
            selected -> tokens.info.copy(alpha = 0.09f)
            hovered -> tokens.surfaceHover
            else -> tokens.surfaceSolid
        },
        animationSpec = motionTween(OposiMotion.Fast),
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) tokens.info else tokens.border,
        animationSpec = motionTween(OposiMotion.Fast),
    )
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    // Con la lista estrecha (STANDARD bajo) la pastilla del tipo de cuenta se va a la línea de
    // la cuenta: si no, el nombre se queda con lo que sobra y sale «Luc…».
    val narrow = maxWidth < 520.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor), shape)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, role = SemanticsRole.Button, onClickLabel = "Ver ficha de $name", onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(userId = user.id, displayName = user.fullName, accountName = userAccountName(user), size = 40.dp)
        Column(modifier = Modifier.weight(1f).widthIn(min = 110.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (narrow) "${userAccountName(user)} · ${userAccountKindLabel(kind)} · ${userAccountStatusLabel(user.accountStatus)}" else userAccountName(user),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!narrow) {
                    UserPill(
                        text = userAccountKindLabel(kind),
                        tone = if (kind == UserAccountKind.REGISTRADO) UserPillTone.POSITIVE else UserPillTone.NEUTRAL,
                    )
                    UserPill(
                        text = userAccountStatusLabel(user.accountStatus),
                        tone = userAccountStatusTone(user.accountStatus),
                    )
                }
                UserPill(
                    text = roleText,
                    tone = if (population == UserPopulation.ADMINISTRACION) UserPillTone.ADMIN else UserPillTone.INFO,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconImage(AppIcon.CLOCK, size = 12.dp, tint = tokens.textSecondary)
                Text(
                    text = "Alta ${userSignupDate(user)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.textSecondary,
                    maxLines = 1,
                )
            }
        }
        AppIconImage(AppIcon.CHEVRON_RIGHT, size = 16.dp, tint = tokens.textSecondary)
    }
    }
}

// ---------------------------------------------------------------------------
// Filas de la ficha
// ---------------------------------------------------------------------------

/** Línea de 1dp entre filas. Suave: separa sin partir la tarjeta. */
@Composable
internal fun UserRowDivider() {
    // `divider` es blanco al 20 %: en claro no se ve. `border` sí se ve en los dos temas.
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(GlassTheme.tokens.border.copy(alpha = 0.7f)))
}

/**
 * Un dato de la ficha: etiqueta a la izquierda, valor a la derecha.
 *
 * Un valor vacío se marca con [NO_DATA_TEXT]; no se deja en blanco, que se
 * confunde con un valor legítimamente vacío.
 */
@Composable
internal fun UserInfoRow(label: String, value: String?, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val missing = value.isNullOrBlank()
    UserInfoRowSlot(label = label, modifier = modifier) {
        Text(
            text = if (missing) NO_DATA_TEXT else value.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (missing) FontWeight.Normal else FontWeight.Medium,
            color = if (missing) tokens.textSecondary else tokens.textPrimary,
        )
    }
}

/** Una fila cuyo valor es cualquier cosa —una pastilla, un campo editable—. */
@Composable
internal fun UserInfoRowSlot(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val stacked = maxWidth < 360.dp
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
                content()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    modifier = Modifier.weight(0.4f),
                )
                Column(modifier = Modifier.weight(0.6f), content = content)
            }
        }
    }
}

/**
 * Filas de una tarjeta, con su divisor entre cada dos.
 *
 * Con [columns] = 2 las filas se reparten en dos columnas, en el mismo orden de
 * lectura: en una tarjeta de mil píxeles de ancho, etiqueta y valor a 600px de
 * distancia no se leen como un mismo dato.
 */
@Composable
internal fun UserInfoRows(rows: List<Pair<String, String?>>, columns: Int = 1) {
    if (columns >= 2 && rows.size > 1) {
        val split = (rows.size + 1) / 2
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Box(modifier = Modifier.weight(1f)) { UserInfoRowsColumn(rows.take(split)) }
            Box(modifier = Modifier.weight(1f)) { UserInfoRowsColumn(rows.drop(split)) }
        }
    } else {
        UserInfoRowsColumn(rows)
    }
}

@Composable
private fun UserInfoRowsColumn(rows: List<Pair<String, String?>>) {
    Column {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) UserRowDivider()
            UserInfoRow(label = label, value = value)
        }
    }
}

// ---------------------------------------------------------------------------
// Estados parciales dentro de una tarjeta
// ---------------------------------------------------------------------------

/** La sección se leyó y no tiene fila. */
@Composable
internal fun UserNoData(reason: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppIconImage(AppIcon.INFO, size = 14.dp, tint = GlassTheme.tokens.textSecondary, modifier = Modifier.padding(top = 2.dp))
        Text(
            text = "$NO_DATA_TEXT — $reason",
            style = MaterialTheme.typography.bodySmall,
            color = GlassTheme.tokens.textSecondary,
        )
    }
}

/** La sección no se pudo leer. Es un fallo, no una ausencia: se pinta como tal. */
@Composable
internal fun UserUnavailable(modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppIconImage(AppIcon.WARNING, size = 14.dp, tint = tokens.dangerInk, modifier = Modifier.padding(top = 2.dp))
        Text(
            text = "$NOT_AVAILABLE_TEXT — no se pudo leer esta sección.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.dangerInk,
        )
    }
}

/** La ficha todavía se está cargando: una línea discreta, no un bloque vacío. */
@Composable
internal fun UserLoadingNote(modifier: Modifier = Modifier) {
    Text(
        text = "Cargando la ficha…",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = GlassTheme.tokens.textSecondary,
    )
}

// ---------------------------------------------------------------------------
// Banda de métricas
// ---------------------------------------------------------------------------

/**
 * La banda de mini-tarjetas bajo la cabecera de la ficha.
 *
 * Todas del mismo alto y con el mismo ritmo. Con poco ancho pasa de una fila de
 * cinco a filas de tres y dos, en vez de comprimir las cifras hasta cortarlas.
 */
@Composable
internal fun UserKpiStrip(kpis: List<UserKpi>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val perRow = when {
            maxWidth >= 900.dp -> 5
            maxWidth >= 480.dp -> 3
            else -> 2
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kpis.chunked(perRow).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { kpi -> UserKpiCard(kpi, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun UserKpiCard(kpi: UserKpi, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val shape = RoundedCornerShape(12.dp)
    val accent = userRoleInk(kpi.role)
    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${kpi.label}: ${kpiSpokenValue(kpi)}"
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(kpi.role.tint(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            AppIconImage(kpi.icon, size = 18.dp, tint = accent)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = kpi.label,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (kpi.state) {
                UserKpiState.DATA -> Text(
                    text = kpi.value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                UserKpiState.NO_DATA -> Text(
                    text = NO_DATA_TEXT,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = tokens.textSecondary,
                )
                UserKpiState.UNAVAILABLE -> Text(
                    text = NOT_AVAILABLE_TEXT,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = tokens.dangerInk,
                )
                UserKpiState.LOADING -> Text(
                    text = "···",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.textSecondary,
                )
            }
            if (kpi.state == UserKpiState.DATA) {
                kpi.caption?.let { caption ->
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Lo que lee un lector de pantalla de una métrica: el valor, o el motivo por el que no hay. */
internal fun kpiSpokenValue(kpi: UserKpi): String = when (kpi.state) {
    UserKpiState.DATA -> listOfNotNull(kpi.value, kpi.caption).joinToString(", ")
    UserKpiState.NO_DATA -> "sin dato"
    UserKpiState.UNAVAILABLE -> "no disponible"
    UserKpiState.LOADING -> "cargando"
}
