package com.oposicontrol.admin.features.dashboard.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusDotChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.components.color
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.layout.kpiColumns
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.AnalyticsMetric
import com.oposicontrol.admin.domain.model.DashboardActivityEntry
import com.oposicontrol.admin.domain.model.DashboardBlock
import com.oposicontrol.admin.domain.model.DashboardBlockReport
import com.oposicontrol.admin.domain.model.DashboardHealth
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import kotlinx.coroutines.delay
import kotlin.time.Instant
import kotlin.time.Clock

/**
 * Panel de escritorio.
 *
 * Cinco franjas, de arriba abajo, en el orden en que se necesitan: si el sistema
 * está en pie, qué trabajo espera, cómo se empieza ese trabajo, cómo va el
 * producto y, al final, el contexto (catálogo, cronología y economía).
 *
 * Cuatro decisiones que se ven en pantalla y conviene no deshacer sin querer:
 *
 *  - **Un solo `BoxWithConstraints`**, en la raíz. Todo el ancho de columna se
 *    calcula a partir de él en dp exactos; no hay `weight()` ni fracciones. El
 *    panel anterior anidaba varios y además definía su propia escala de
 *    ventana (720/1039 dp) ignorando los tokens.
 *  - **Cristal fuera, opaco dentro**. Los contenedores de sección son
 *    `GlassSectionSurface` —nivel 1, translúcido, deja ver los halos del fondo— y
 *    toda superficie que lleva una cifra es `surfaceSolid` con borde de 1dp
 *    —nivel 2—. Es la regla del propio Design System: un dato sobre cristal se lee
 *    peor y en un panel hay muchos a la vez.
 *  - **El color lo pone el rol, no la pantalla.** Cada icono va dentro de un
 *    [SemanticIconContainer] con su [SemanticRole]; aquí no se escribe ni un
 *    color. Antes los seis atajos eran coral y el color no distinguía nada.
 *  - **Nada que el backend no dé**. Ni sparklines, ni deltas contra el periodo
 *    anterior, ni selector de ventana temporal, ni ingresos: no existe ninguna
 *    de esas cuatro cosas en el contrato, y dibujarlas obligaría a inventarlas.
 */
@Composable
fun DashboardV2Screen(
    viewModel: DashboardV2ViewModel,
    onOpen: (DashboardV2Destination) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var now by remember { mutableStateOf(Clock.System.now()) }

    // Sólo la franja de sistema se refresca sola. El resto son conteos exactos
    // contra Supabase y repetirlos cada minuto costaría mucho para cambiar poco:
    // el catálogo no se mueve solo. Lo que sí se mueve solo es si Ktor sigue en
    // pie, y ése es justo el dato que caduca en segundos.
    LaunchedEffect(Unit) {
        while (true) {
            delay(SYSTEM_REFRESH_MILLIS)
            viewModel.dispatch(DashboardV2Intent.RefreshBlock(DashboardBlock.SYSTEM))
        }
    }

    // El reloj de «hace 12 min» avanza sin volver a pedir nada: los instantes de
    // los eventos son fijos y reales; lo único que envejece es el ahora.
    LaunchedEffect(Unit) {
        while (true) {
            delay(RELATIVE_CLOCK_MILLIS)
            now = Clock.System.now()
        }
    }

    DashboardV2Content(
        state = state,
        now = now,
        onRefreshBlock = { viewModel.dispatch(DashboardV2Intent.RefreshBlock(it)) },
        onOpen = onOpen,
    )
}

@Composable
internal fun DashboardV2Content(
    state: DashboardV2State,
    now: Instant,
    onRefreshBlock: (DashboardBlock) -> Unit,
    onOpen: (DashboardV2Destination) -> Unit,
) {
    val breakpoint = DesktopWindow.breakpoint
    // El único BoxWithConstraints de la pantalla. El margen exterior no se añade
    // aquí: `DesktopShell` ya envuelve el slot de contenido en
    // `breakpoint.screenPadding`, y repetirlo lo duplicaría.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Se captura una vez: dentro del `Column` el receptor implícito ya no es
        // el de `BoxWithConstraints`, y ésta es la única medida de la que sale
        // todo el ancho de columna de la pantalla.
        val contentWidth = maxWidth

        /*
         * Viewport BAJO. Se mide el alto, no el ancho, porque el problema es de alto.
         *
         * En 1366×768 —el portátil más común— la barra superior y el encabezado de
         * página se llevan 185dp y quedan 551 de contenido. Con ellos no cabe
         * «Pulso del producto», y una pantalla de inicio en la que hay que hacer
         * scroll para ver la primera métrica del producto no está haciendo su
         * trabajo. En 1600×900 y en 1920×1080 sobra sitio y no hay nada que
         * decidir.
         *
         * Lo que se retira es el SUBTÍTULO de cada sección, no altura de tarjeta.
         * Son 20dp por sección y hay cinco: 60dp por encima del Pulso, más de lo
         * que se saca limando cinco medidas distintas, y sin dejar nada
         * microscópico. Y es lo que menos cuesta perder: «Elementos que requieren
         * revisión o acción» debajo de «Necesita tu atención» explica un título que
         * no necesita explicación.
         *
         * Es una decisión de diseño responsive, no un apaño: el panel conserva su
         * forma completa en cuanto hay alto para sostenerla.
         */
        val shortViewport = maxHeight < DashboardV2Sizing.shortViewportHeight
        val sectionGap = if (shortViewport) Spacing.sm else DashboardV2Sizing.sectionGap
        val gridWidth = contentWidth - DashboardV2Sizing.sectionHorizontalPadding * 2
        val kpiColumns = breakpoint.kpiColumns
        // Cuatro tarjetas de atención en una fila, salvo en ventana estrecha,
        // donde se parten en dos filas de dos: cinco columnas para cuatro
        // tarjetas dejaría un hueco, y una sola fila de cuatro en COMPACT las
        // aplastaría por debajo de lo legible.
        val attentionColumns = if (breakpoint.isCompact) 2 else 4
        val quickColumns = if (breakpoint.isCompact) 3 else 6
        val bottomColumns = if (breakpoint.isCompact) 1 else 3

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(sectionGap),
        ) {
            SystemStrip(
                state = state.block(DashboardBlock.SYSTEM),
                now = now,
                stripWidth = contentWidth,
                onRetry = { onRefreshBlock(DashboardBlock.SYSTEM) },
                onOpen = { onOpen(DashboardV2Destination.SYSTEM_DIAGNOSTICS) },
            )

            AttentionSection(
                state = state.block(DashboardBlock.ATTENTION),
                columnWidth = columnWidth(gridWidth, attentionColumns, DashboardV2Sizing.cardGap),
                columns = attentionColumns,
                showSubtitle = !shortViewport,
                onRetry = { onRefreshBlock(DashboardBlock.ATTENTION) },
                onOpen = onOpen,
            )

            QuickActionsSection(
                columnWidth = columnWidth(gridWidth, quickColumns, DashboardV2Sizing.cardGap),
                columns = quickColumns,
                showSubtitle = !shortViewport,
                onOpen = onOpen,
            )

            PulseSection(
                state = state.block(DashboardBlock.PULSE),
                gridWidth = gridWidth,
                columns = kpiColumns,
                showSubtitle = !shortViewport,
                onRetry = { onRefreshBlock(DashboardBlock.PULSE) },
                onOpen = onOpen,
            )

            BottomRow(
                state = state,
                now = now,
                columnWidth = columnWidth(contentWidth, bottomColumns, DashboardV2Sizing.cardGap),
                columns = bottomColumns,
                // El mínimo de altura existe SÓLO para que los tres paneles de la
                // fila queden alineados. Con una sola columna no alinea nada: lo
                // único que hace es estirar tres paneles de 4, 5 y 3 filas hasta
                // 300dp cada uno y regalar 900dp de scroll.
                panelMinHeight = if (bottomColumns > 1) DashboardV2Sizing.bottomPanelHeight else Dp.Unspecified,
                showSubtitle = !shortViewport,
                onRefreshBlock = onRefreshBlock,
                onOpen = onOpen,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 1 · Estado del sistema
// ---------------------------------------------------------------------------

/**
 * Franja fina, no tarjeta.
 *
 * Es lo primero que se mira y lo que menos sitio debe ocupar: en un día normal
 * dice «todo bien» y no merece un tercio de la pantalla. Sus estados de carga y
 * error se resuelven en línea, dentro de la propia franja, y no con
 * [AsyncStatePane]: el esqueleto de una tarjeta mide 66dp y esta barra mide 64,
 * así que usarlo daría un salto de layout cada minuto al refrescarse.
 *
 * Lo que cambia respecto a la versión anterior es sólo el lenguaje: contenedor de
 * cristal en vez de barra plana, icono dentro de un contenedor squircle azul en
 * vez de un icono suelto de 18dp, y píldoras con punto de estado en vez de chips
 * cuadrados. La estructura —grupo izquierdo acotado, acciones a la derecha— se
 * conserva intacta porque es lo que garantiza que «Ver estado» no desaparezca, y
 * eso está medido en `DashboardV2SystemStripUiTest`.
 */
@Composable
private fun SystemStrip(
    state: UiAsync<DashboardBlockReport>,
    now: Instant,
    stripWidth: Dp,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    // La reserva depende del estado porque el grupo de acciones cambia de tamaño:
    // en error lleva «Reintentar» además de «Ver estado». Reservar siempre lo del
    // error apretaría el grupo izquierdo 136dp de más en el caso normal, que es el
    // 99% del tiempo, y elidiría «Actualizado hace…» sin ninguna necesidad.
    val leftMaxWidth = systemStripLeftMaxWidth(stripWidth, showsRetry = state is UiAsync.Error)
    val tokens = GlassTheme.tokens
    GlassSectionSurface(
        shape = Radius.xl,
        contentPadding = PaddingValues(
            horizontal = DashboardV2Sizing.sectionHorizontalPadding,
            vertical = Spacing.sm,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DashboardV2Sizing.systemStripHeight),
            verticalAlignment = Alignment.CenterVertically,
            // SpaceBetween y no un espaciador elástico: no hay `weight()` en esta
            // pantalla, ni siquiera para empujar algo a la derecha.
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val health = (state as? UiAsync.Success)?.value?.health
            Row(
                // La cota es obligatoria, no decorativa. `SpaceBetween` mide al
                // primer hijo con TODO el ancho disponible y al segundo con lo que
                // sobre: un grupo izquierdo sin tope se come el grupo de acciones
                // entero y «Ver estado» —y en error también «Reintentar», el único
                // reintento manual de esta franja— dejan de dibujarse.
                modifier = Modifier.widthIn(max = leftMaxWidth),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // Azul y no el color de la salud: el estado ya lo dicen las
                // píldoras, con punto Y palabra. Repetirlo aquí haría que la
                // franja entera cambiase de color por un servicio degradado, que
                // es exactamente el grado de alarma que este bloque no debe tener.
                SemanticIconContainer(
                    icon = AppIcon.STATUS,
                    role = SemanticRole.INFO,
                    size = DashboardV2Sizing.stripIconChip,
                    iconSize = Sizing.iconLarge,
                    contentDescription = "Estado del sistema",
                )
                Text(
                    text = "Estado del sistema",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    // Sin esto el texto se recorta a hachazo en vez de elidir.
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    health != null -> SystemHealthChips(health = health, now = now)
                    state is UiAsync.Error -> Text(
                        text = state.error.humanMessage(),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text(
                        text = "Comprobando…",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (state is UiAsync.Error) {
                    GhostButton(
                        text = "Reintentar",
                        onClick = onRetry,
                        size = ButtonSize.COMPACT,
                        icon = { AppIconImage(AppIcon.REFRESH, size = 14.dp) },
                    )
                }
                DashboardLink(text = "Ver estado", onClick = onOpen)
            }
        }
    }
}

/**
 * Las dos píldoras de salud y, si hay sitio, sus dos refinamientos.
 *
 * El orden importa y es de menor a mayor sacrificable: píldoras, latencia, reloj.
 * El `Row` padre está acotado (ver [systemStripLeftMaxWidth]), así que cuando no
 * cabe todo es el reloj el que se elide primero y las píldoras —lo único que
 * contesta «¿está en pie?»— las que sobreviven.
 */
@Composable
private fun SystemHealthChips(health: DashboardHealth, now: Instant) {
    val tokens = GlassTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        StatusDotChip(label = "Ktor ${health.ktor.label()}", tone = health.ktor.tone())
        StatusDotChip(label = "Supabase ${health.supabase.label()}", tone = health.supabase.tone())
        // Medición real: Ktor cronometra su lectura mínima contra Supabase y con
        // ella decide OPERATIVO o DEGRADADO. Se nombra por lo que es —el tiempo
        // que tarda Supabase en contestar a esa sonda— y NO como «latencia de la
        // API», que sigue sin instrumentar. `null` no deja hueco ni cero: la
        // línea entera desaparece, y como el Row usa `spacedBy`, tampoco queda
        // separación de más.
        health.supabaseLatencyMs?.let { millis ->
            Text(
                text = "responde en $millis ms",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    contentDescription = "Supabase respondió a la sonda de estado en $millis milisegundos"
                },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            AppIconImage(icon = AppIcon.CLOCK, size = 13.dp, tint = tokens.textSecondary)
            Text(
                // `updatedAt` es el momento en que Ktor compuso el estado, no el de
                // esta lectura. Si no llegó, se dice — no se sustituye por «ahora».
                text = health.updatedAt
                    ?.let { "Actualizado ${dashboardRelativeTime(it, now)}" }
                    ?: "Sin marca de tiempo",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 2 · Necesita tu atención
// ---------------------------------------------------------------------------

@Composable
private fun AttentionSection(
    state: UiAsync<DashboardBlockReport>,
    columnWidth: Dp,
    columns: Int,
    showSubtitle: Boolean,
    onRetry: () -> Unit,
    onOpen: (DashboardV2Destination) -> Unit,
) {
    DashboardSection(
        icon = AppIcon.WARNING,
        role = SemanticRole.WARNING,
        title = "Necesita tu atención",
        subtitle = "Elementos que requieren revisión o acción",
        showSubtitle = showSubtitle,
        action = {
            DashboardLink(
                text = "Revisar todos",
                onClick = { onOpen(DashboardV2Destination.MODERATION_QUEUE) },
            )
        },
    ) {
        AsyncStatePane(
            state = state,
            emptyTitle = "Nada pendiente",
            emptyMessage = "No hay contadores de trabajo pendiente en esta franja.",
            emptyIcon = AppIcon.CHECK,
            loadingRows = 3,
            onRetry = onRetry,
        ) { report ->
            val metrics = report.renderable
            DashboardGrid(
                itemCount = metrics.size,
                columns = columns,
                itemWidth = columnWidth,
                gap = DashboardV2Sizing.cardGap,
            ) { index ->
                AttentionCard(metric = metrics[index], onOpen = onOpen)
            }
        }
    }
}

/**
 * Tarjeta de atención: icono grande y cifra en la misma línea, nombre, matiz y
 * **su propio enlace**.
 *
 * No la cubre `SummaryCard`: aquélla es una tarjeta de KPI de tres líneas de
 * texto, sin icono ni acción. Un contador de trabajo pendiente sin forma de
 * abrirlo enseña el problema y deja a quien lo lee buscándolo por el menú, así
 * que el enlace no es adorno.
 *
 * **Icono arriba, cifra debajo.** En la ronda anterior los puse en la misma fila
 * para ahorrar 24dp de alto, y fue un mal cambio: una revisión visual contra la
 * referencia lo señaló como el único punto donde la tarjeta se lee como «ese
 * diseño con un componente fuera de plantilla». Los 24dp se han buscado en el
 * cromo de las secciones, que no tiene forma reconocible que romper.
 *
 * **Con la cifra a cero la composición no cambia**: misma tarjeta, mismo sitio,
 * mismo alto; se apagan color, severidad y llamada a la acción, y el matiz pasa a
 * decir «Todo al día.». Ver [dashboardAttentionIsCleared] para el porqué.
 */
@Composable
private fun AttentionCard(
    metric: AnalyticsMetric,
    onOpen: (DashboardV2Destination) -> Unit,
) {
    val tokens = GlassTheme.tokens
    val copy = dashboardAttentionCopy(metric.key)
    val cell = dashboardMetricCell(metric)
    val cleared = dashboardAttentionIsCleared(cell)
    val role = if (cleared) SemanticRole.NEUTRAL else copy.role
    val destination = copy.destination

    DashboardCardSurface(
        minHeight = DashboardV2Sizing.attentionCardHeight,
        onClick = destination?.let { { onOpen(it) } },
        // Tinte semántico MUY sutil y sólo en el borde: teñir la tarjeta entera
        // pondría cuatro rectángulos de color a la vez y ninguno destacaría.
        accent = role.color().takeUnless { cleared },
    ) {
        SemanticIconContainer(
            icon = copy.icon,
            role = role,
            size = DashboardV2Sizing.cardIconChip,
            iconSize = DashboardV2Sizing.cardIconSize,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        MetricValueText(cell = cell, muted = cleared)
        Text(
            text = copy.title,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (cleared) copy.clearedSupporting else copy.supporting,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Sin trabajo pendiente no hay llamada a la acción: «Revisar reportes»
        // sobre un cero manda a una cola vacía. La tarjeta sigue siendo pulsable
        // —quien quiera entrar, entra— pero deja de pedirlo.
        if (!cleared && copy.actionLabel != null && destination != null) {
            Spacer(modifier = Modifier.height(Spacing.xxs))
            DashboardLink(
                text = copy.actionLabel,
                onClick = { onOpen(destination) },
                modifier = Modifier.padding(start = 0.dp),
                // Único enlace del panel que pisa superficie OPACA, no cristal:
                // aquí el coral sí llega al 4,5:1 de texto normal (4,6:1 medido)
                // y es donde la llamada a la acción debe destacar.
                textColor = GlassTheme.tokens.accentInk,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 3 · Acciones rápidas
// ---------------------------------------------------------------------------

/**
 * Seis atajos, todos a rutas verificadas de escritorio.
 *
 * Ninguno pasa por `AdminSection`: el panel anterior navegaba con aquel enum
 * heredado y por eso algunos atajos aterrizaban en pantallas que ya no eran las
 * canónicas. Aquí cada destino es un `AppRoute` que `AppRoot` resuelve.
 */
@Composable
private fun QuickActionsSection(
    columnWidth: Dp,
    columns: Int,
    showSubtitle: Boolean,
    onOpen: (DashboardV2Destination) -> Unit,
) {
    DashboardSection(
        // `ADD` y no `EVENTS`: el icono de actividad es casi el mismo trazo que
        // el de la franja de sistema y las dos cabeceras se confundían a simple
        // vista. Un «+» dice atajo sin ambigüedad.
        icon = AppIcon.ADD,
        role = SemanticRole.BRAND,
        title = "Acciones rápidas",
        subtitle = "Accesos directos a las tareas más habituales",
        showSubtitle = showSubtitle,
    ) {
        DashboardGrid(
            itemCount = QUICK_ACTIONS.size,
            columns = columns,
            itemWidth = columnWidth,
            gap = DashboardV2Sizing.cardGap,
        ) { index ->
            val action = QUICK_ACTIONS[index]
            QuickActionTile(
                icon = action.icon,
                role = action.role,
                label = action.label,
                onClick = { onOpen(action.destination) },
            )
        }
    }
}

private data class QuickAction(
    val label: String,
    val icon: AppIcon,
    val role: SemanticRole,
    val destination: DashboardV2Destination,
)

/**
 * Los seis atajos y su familia de color.
 *
 * El rol se declara aquí, junto al destino, y no se deduce del icono: «Revisar
 * reportes» y «Revisar OposiTester» comparten forma —los dos son una bandeja de
 * cosas que revisar— y aun así uno es moderación y el otro análisis. Sólo quien
 * escribe la lista lo sabe.
 */
private val QUICK_ACTIONS = listOf(
    QuickAction("Crear examen", AppIcon.EXAMS, SemanticRole.SUCCESS, DashboardV2Destination.EXAMS),
    QuickAction("Importar preguntas", AppIcon.IMPORT, SemanticRole.INFO, DashboardV2Destination.IMPORTS),
    QuickAction("Revisar reportes", AppIcon.REPORTS, SemanticRole.DANGER, DashboardV2Destination.MODERATION_QUEUE),
    // Ámbar y no violeta, por dos motivos que apuntan al mismo sitio: el violeta
    // se repetía con «Analytics» dos casillas más allá, y la tarjeta de atención
    // de OposiTester ya es WARNING. Ahora el atajo y su tarjeta son del mismo
    // color, que es lo que hace que se reconozcan como la misma cosa.
    QuickAction("Revisar OposiTester", AppIcon.TESTER, SemanticRole.WARNING, DashboardV2Destination.OPOSITESTER_LIST),
    QuickAction("Usuarios", AppIcon.USERS, SemanticRole.INFO, DashboardV2Destination.USERS),
    // «Analytics», con el nombre que usa la barra lateral. Había tres nombres para
    // un mismo destino —«Analytics» en la barra, «Analítica» aquí y «Ver
    // Analytics» en el enlace del Pulso— y el que sobraba era el mío.
    QuickAction("Analytics", AppIcon.ANALYTICS, SemanticRole.INSIGHT, DashboardV2Destination.ANALYTICS),
)

@Composable
private fun QuickActionTile(
    icon: AppIcon,
    role: SemanticRole,
    label: String,
    onClick: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    DashboardCardSurface(
        minHeight = DashboardV2Sizing.quickActionTileHeight,
        onClick = onClick,
        horizontalAlignment = Alignment.CenterHorizontally,
        accent = role.color(),
    ) {
        SemanticIconContainer(
            icon = icon,
            role = role,
            size = DashboardV2Sizing.cardIconChip,
            iconSize = DashboardV2Sizing.cardIconSize,
            // Círculo, no squircle: es lo que distingue un ATAJO de una tarjeta de
            // dato. Las de atención y las de KPI llevan squircle porque acompañan
            // a una cifra; éstas no tienen cifra y su contenedor es el botón.
            shape = Radius.pill,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------
// 4 · Pulso del producto
// ---------------------------------------------------------------------------

/**
 * KPIs de 30 días, sin sparkline y sin delta.
 *
 * Los dos se omiten por el mismo motivo y no por espacio: no existe serie
 * temporal ni comparativa con el periodo anterior en ningún endpoint, así que
 * cualquier línea o cualquier `↑+12%` que apareciera aquí estaría inventado.
 * Tampoco hay selector de ventana: los 30 días están cocidos en cada consulta
 * del backend y un desplegable prometería un soporte que no existe. El subtítulo
 * de la franja ya dice cuál es la ventana.
 *
 * Lo que sí se hace es **reequilibrar**. El contrato trae cuatro métricas y
 * `kpiColumns` da cinco columnas en ventana ancha: pintar cuatro tarjetas en una
 * rejilla de cinco dejaba un hueco a la derecha que se leía como «falta algo».
 * Las columnas se acotan al número de métricas, así que las cuatro reparten el
 * ancho entero y cada tarjeta gana ~90dp — que es justo lo que necesita una
 * tarjeta sin gráfico para no parecer vacía.
 */
@Composable
private fun PulseSection(
    state: UiAsync<DashboardBlockReport>,
    gridWidth: Dp,
    columns: Int,
    showSubtitle: Boolean,
    onRetry: () -> Unit,
    onOpen: (DashboardV2Destination) -> Unit,
) {
    DashboardSection(
        icon = AppIcon.PERFORMANCE,
        role = SemanticRole.INSIGHT,
        title = "Pulso del producto",
        subtitle = "Métricas clave de los últimos 30 días",
        showSubtitle = showSubtitle,
        action = {
            DashboardLink(
                text = "Ver Analytics",
                onClick = { onOpen(DashboardV2Destination.ANALYTICS) },
            )
        },
    ) {
        AsyncStatePane(
            state = state,
            emptyTitle = "Sin métricas de producto",
            emptyMessage = "Esta franja no trae ninguna métrica todavía.",
            emptyIcon = AppIcon.ANALYTICS,
            loadingRows = 2,
            onRetry = onRetry,
        ) { report ->
            val metrics = report.renderable
            val resolvedColumns = columns.coerceAtMost(metrics.size).coerceAtLeast(1)
            DashboardGrid(
                itemCount = metrics.size,
                columns = resolvedColumns,
                itemWidth = columnWidth(gridWidth, resolvedColumns, DashboardV2Sizing.cardGap),
                gap = DashboardV2Sizing.cardGap,
            ) { index ->
                PulseCard(metric = metrics[index])
            }
        }
    }
}

/** Icono con tinte a la izquierda; etiqueta, cifra y apoyo a la derecha. */
@Composable
private fun PulseCard(metric: AnalyticsMetric) {
    val tokens = GlassTheme.tokens
    val copy = dashboardMetricCopy(metric.key)
    val cell = dashboardMetricCell(metric)

    DashboardCardSurface(minHeight = DashboardV2Sizing.pulseCardHeight) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SemanticIconContainer(
                icon = dashboardMetricIcon(metric.key),
                role = dashboardMetricRole(metric.key),
                size = DashboardV2Sizing.cardIconChip,
                iconSize = DashboardV2Sizing.cardIconSize,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MetricValueText(cell = cell, muted = false)
                if (copy.supporting.isNotBlank()) {
                    Text(
                        text = copy.supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 5 · Contenido · Actividad · Economía
// ---------------------------------------------------------------------------

@Composable
private fun BottomRow(
    state: DashboardV2State,
    now: Instant,
    columnWidth: Dp,
    columns: Int,
    panelMinHeight: Dp,
    showSubtitle: Boolean,
    onRefreshBlock: (DashboardBlock) -> Unit,
    onOpen: (DashboardV2Destination) -> Unit,
) {
    DashboardGrid(
        itemCount = 3,
        columns = columns,
        itemWidth = columnWidth,
        gap = DashboardV2Sizing.cardGap,
    ) { index ->
        when (index) {
            0 -> ContentStatusPanel(
                state = state.block(DashboardBlock.CONTENT),
                onRetry = { onRefreshBlock(DashboardBlock.CONTENT) },
                minHeight = panelMinHeight,
                showSubtitle = showSubtitle,
                onOpen = onOpen,
            )
            1 -> ActivityPanel(
                state = state.block(DashboardBlock.ACTIVITY),
                now = now,
                onRetry = { onRefreshBlock(DashboardBlock.ACTIVITY) },
                minHeight = panelMinHeight,
                showSubtitle = showSubtitle,
                onOpen = onOpen,
            )
            else -> EconomyPanel(
                state = state.block(DashboardBlock.ECONOMY),
                onRetry = { onRefreshBlock(DashboardBlock.ECONOMY) },
                onOpen = onOpen,
                minHeight = panelMinHeight,
                showSubtitle = showSubtitle,
            )
        }
    }
}

@Composable
private fun ContentStatusPanel(
    state: UiAsync<DashboardBlockReport>,
    onRetry: () -> Unit,
    onOpen: (DashboardV2Destination) -> Unit,
    minHeight: Dp,
    showSubtitle: Boolean,
) {
    DashboardSection(
        icon = AppIcon.LAYERS,
        role = SemanticRole.INFO,
        title = "Estado de contenido",
        subtitle = "Volumen del catálogo",
        showSubtitle = showSubtitle,
        dense = true,
        modifier = Modifier.heightIn(min = minHeight),
        // Era la única de las tres columnas sin enlace superior, y esa asimetría
        // no la justificaba nada. Que la primera fila lleve además su chevron a
        // Exámenes NO es el caso de «Recursos»: allí eran dos filas contiguas con
        // el mismo gesto y el mismo destino, indistinguibles; aquí son dos
        // altitudes distintas —la sección y el item— y el rótulo lo dice.
        action = {
            DashboardLink(
                text = "Ver contenido",
                onClick = { onOpen(DashboardV2Destination.EXAMS) },
            )
        },
    ) {
        AsyncStatePane(
            state = state,
            emptyTitle = "Sin catálogo",
            emptyMessage = "No hay conteos de contenido en esta franja.",
            emptyIcon = AppIcon.LAYERS,
            loadingRows = 4,
            onRetry = onRetry,
        ) { report ->
            SolidList(minHeight = listMinHeight(minHeight)) {
                report.renderable.forEachIndexed { index, metric ->
                    if (index > 0) Hairline()
                    val destination = dashboardContentDestination(metric.key)
                    StatRow(
                        icon = dashboardMetricIcon(metric.key),
                        role = dashboardMetricRole(metric.key),
                        label = dashboardMetricCopy(metric.key).title,
                        value = dashboardMetricText(metric) ?: SIN_DATO,
                        muted = dashboardMetricCell(metric) is DashboardMetricCell.NoData,
                        onClick = destination?.let { { onOpen(it) } },
                    )
                }
            }
        }
    }
}

/**
 * Economía con lo que hoy se puede contar de verdad.
 *
 * No hay ingresos, ni nuevas suscripciones del periodo, ni conversión a premium:
 * las tres exigen un `SUM` o una comparativa que PostgREST no expresa, y el
 * contrato las declara `NOT_INSTRUMENTED` precisamente para que el hueco tenga
 * nombre. `renderable` las deja fuera; no se pintan como «0 €».
 */
@Composable
private fun EconomyPanel(
    state: UiAsync<DashboardBlockReport>,
    onRetry: () -> Unit,
    onOpen: (DashboardV2Destination) -> Unit,
    minHeight: Dp,
    showSubtitle: Boolean,
) {
    DashboardSection(
        icon = AppIcon.PLANS,
        role = SemanticRole.COMMERCE,
        title = "Economía",
        subtitle = "Suscripciones y catálogo comercial",
        showSubtitle = showSubtitle,
        dense = true,
        modifier = Modifier.heightIn(min = minHeight),
        action = {
            DashboardLink(
                text = "Ver suscripciones",
                onClick = { onOpen(DashboardV2Destination.SUBSCRIPTIONS) },
            )
        },
    ) {
        AsyncStatePane(
            state = state,
            emptyTitle = "Sin datos comerciales",
            emptyMessage = "No hay métricas de economía en esta franja.",
            emptyIcon = AppIcon.PLANS,
            loadingRows = 3,
            onRetry = onRetry,
        ) { report ->
            SolidList(minHeight = listMinHeight(minHeight)) {
                report.renderable.forEachIndexed { index, metric ->
                    if (index > 0) Hairline()
                    StatRow(
                        icon = dashboardMetricIcon(metric.key),
                        role = dashboardMetricRole(metric.key),
                        label = dashboardMetricCopy(metric.key).title,
                        value = dashboardMetricText(metric) ?: SIN_DATO,
                        muted = dashboardMetricCell(metric) is DashboardMetricCell.NoData,
                        onClick = null,
                    )
                }
            }
        }
    }
}

/**
 * Cronología real de `auditoria_log`.
 *
 * Cada fila lleva la fecha del hecho, no la de la lectura, así que «hace 12 min»
 * significa doce minutos. El panel anterior derivaba tres eventos del estado
 * actual y les ponía a los tres `Clock.System.now()`: se rejuvenecían solos en
 * cada refresco y siempre decían haber ocurrido a la vez.
 */
@Composable
private fun ActivityPanel(
    state: UiAsync<DashboardBlockReport>,
    now: Instant,
    onRetry: () -> Unit,
    onOpen: (DashboardV2Destination) -> Unit,
    minHeight: Dp,
    showSubtitle: Boolean,
) {
    DashboardSection(
        icon = AppIcon.AUDIT,
        role = SemanticRole.INSIGHT,
        title = "Actividad reciente",
        subtitle = "Últimas acciones registradas",
        showSubtitle = showSubtitle,
        dense = true,
        modifier = Modifier.heightIn(min = minHeight),
        action = {
            DashboardLink(
                text = "Ver toda",
                onClick = { onOpen(DashboardV2Destination.AUDIT) },
            )
        },
    ) {
        AsyncStatePane(
            state = state,
            emptyTitle = "Sin actividad",
            emptyMessage = "No hay acciones registradas todavía.",
            emptyIcon = AppIcon.AUDIT,
            loadingRows = 4,
            onRetry = onRetry,
        ) { report ->
            val dropped = report.droppedActivityEntries
            if (report.activity.isEmpty()) {
                // Con descartes, «no hay acciones registradas» sería falso: hubo
                // filas y no se pudieron situar en el tiempo.
                Text(
                    text = dashboardActivityEmptyMessage(dropped),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlassTheme.tokens.textSecondary,
                )
            } else {
                val rows = report.activity.take(ACTIVITY_ROWS)
                SolidList(minHeight = listMinHeight(minHeight)) {
                    rows.forEachIndexed { index, entry ->
                        ActivityRow(
                            entry = entry,
                            now = now,
                            isFirst = index == 0,
                            isLast = index == rows.lastIndex,
                        )
                    }
                }
                // El backend lee 8 filas para que este `take(5)` siga llenándose
                // aunque alguna se descarte; lo que se descartó se dice, no se
                // deduce del hueco.
                dashboardActivityDroppedNote(dropped)?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = GlassTheme.tokens.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Una fila de cronología.
 *
 * El hilo vertical se dibuja en el propio canalillo del punto y se recorta en la
 * primera y en la última fila, para que no salga por arriba ni por abajo del
 * bloque. Es 1dp del color de borde: tiene que sugerir continuidad, no dibujar
 * una tabla.
 *
 * El color del punto sale de [dashboardActivityTone], que lee la ACCIÓN real:
 * crear y publicar en verde, archivar y despublicar en rojo, y todo lo que no se
 * reconoce en neutral —nunca en verde por descarte—.
 */
@Composable
private fun ActivityRow(
    entry: DashboardActivityEntry,
    now: Instant,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val tokens = GlassTheme.tokens
    val tone = dashboardActivityTone(entry.action)
    val thread = tokens.border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashboardV2Sizing.listRowHeight)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .width(DashboardV2Sizing.timelineGutter)
                    .height(DashboardV2Sizing.listRowHeight)
                    .drawBehind {
                        val x = size.width / 2f
                        val top = if (isFirst) size.height / 2f else 0f
                        val bottom = if (isLast) size.height / 2f else size.height
                        drawLine(
                            color = thread,
                            start = Offset(x, top),
                            end = Offset(x, bottom),
                            strokeWidth = 1f,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(DashboardV2Sizing.timelineDot)
                        .clip(Radius.pill)
                        .background(tone.dotColor()),
                )
            }
            AppIconImage(
                icon = dashboardActivityIcon(entry.targetTable),
                size = 15.dp,
                tint = tokens.textSecondary,
            )
            Text(
                text = dashboardActivityLabel(entry.action),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(DashboardV2Sizing.activityLabelWidth),
            )
        }
        Text(
            text = dashboardRelativeTime(entry.occurredAt, now),
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textSecondary,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// Piezas comunes
// ---------------------------------------------------------------------------

/**
 * Contenedor de sección: el único sitio donde el panel es cristal.
 *
 * `SectionCard` no vale aquí por tres cosas concretas: no tiene subtítulo (y las
 * cinco franjas lo llevan), fija sus paddings a 22/18dp fuera de la escala de
 * `Spacing`, y en escritorio resuelve a superficie opaca — que es justo lo
 * contrario de lo que un contenedor de sección debe ser cuando lo que va dentro
 * ya es opaco.
 *
 * [dense] es la diferencia entre una franja mayor y uno de los tres paneles de la
 * fila inferior: el mismo componente, con el título un peldaño por debajo. Un
 * `titleLarge` de 19sp dentro de una columna de 500dp compite con las propias
 * filas de datos.
 */
@Composable
private fun DashboardSection(
    icon: AppIcon,
    role: SemanticRole,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    /**
     * Se apaga cuando el viewport es bajo — ver `shortViewport` en
     * `DashboardV2Content`. El subtítulo se pasa igualmente y no desaparece del
     * código: la sección sigue sabiendo lo que dice de sí misma, sólo que en 768px
     * de alto no lo dibuja.
     */
    showSubtitle: Boolean = true,
    dense: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    GlassSectionSurface(
        modifier = modifier,
        shape = Radius.xl,
        contentPadding = PaddingValues(
            horizontal = DashboardV2Sizing.sectionHorizontalPadding,
            vertical = DashboardV2Sizing.sectionVerticalPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(DashboardV2Sizing.sectionHeaderGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                SemanticIconContainer(
                    icon = icon,
                    role = role,
                    size = if (dense) DashboardV2Sizing.sectionIconChipDense else DashboardV2Sizing.sectionIconChip,
                    iconSize = if (dense) Sizing.icon else Sizing.iconLarge,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = if (dense) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        color = tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showSubtitle) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            action?.invoke()
        }
        content()
    }
}

/**
 * Rejilla de N columnas en dp exactos.
 *
 * Sin `weight()` ni `fillMaxWidth(fraction)`: el ancho de columna se calcula una
 * vez, a partir del único `BoxWithConstraints` de la pantalla, y cada celda lo
 * recibe como medida. Así una fila de cuatro y otra de seis comparten rejilla en
 * lugar de repartirse el espacio cada una a su manera.
 */
@Composable
private fun DashboardGrid(
    itemCount: Int,
    columns: Int,
    itemWidth: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
    item: @Composable (Int) -> Unit,
) {
    if (itemCount == 0) return
    val rows = (itemCount + columns - 1) / columns
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(columns) { column ->
                    val index = row * columns + column
                    if (index < itemCount) {
                        Box(modifier = Modifier.width(itemWidth)) { item(index) }
                    }
                }
            }
        }
    }
}

/**
 * Superficie opaca para todo lo que lleva una cifra. Nivel 2 de cristal.
 *
 * Aquí viven hover, foco y cursor de mano: son los tres avisos que en escritorio
 * llegan ANTES del clic, y el panel anterior los perdía porque cada tarjeta se
 * dibujaba a mano con `clickable(indication = null)`.
 *
 * [accent] tiñe el borde —y sólo el borde— con la familia de la tarjeta, al 26%.
 * A esa opacidad, sobre blanco, el rim se lee como un matiz y no como un marco de
 * color; el hover lo sube al 55%, que es todo el realce que hay. Nada de escalar,
 * nada de elevar: en una rejilla de seis, un tile que crece empuja a los demás.
 */
@Composable
private fun DashboardCardSurface(
    minHeight: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val interactive = onClick != null
    val shape = DashboardV2Sizing.cardShape

    val background by animateColorAsState(
        targetValue = when {
            interactive && hovered -> tokens.surfaceHover
            else -> tokens.surfaceSolid
        },
        animationSpec = tween(140),
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            interactive && focused -> tokens.focusRing
            accent != null && interactive && hovered -> accent.copy(alpha = 0.55f)
            accent != null -> accent.copy(alpha = 0.26f)
            else -> tokens.border
        },
        animationSpec = tween(140),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(background)
            .border(if (interactive && focused) 2.dp else 1.dp, borderColor, shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .hoverable(interactionSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(Spacing.md),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/**
 * Bloque opaco que agrupa una lista de filas con cifra. Un solo borde, no uno por fila.
 *
 * [minHeight] existe para que la lista llegue al fondo de su panel en la rejilla
 * inferior — ver [DashboardV2Sizing.bottomListHeight]. Con una sola columna se
 * pasa sin especificar y la lista mide lo que midan sus filas.
 */
@Composable
private fun SolidList(
    minHeight: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = GlassTheme.tokens
    val shape = DashboardV2Sizing.cardShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(shape)
            .background(tokens.surfaceSolid)
            .border(1.dp, tokens.border, shape),
        content = content,
    )
}

@Composable
private fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GlassTheme.tokens.border),
    )
}

@Composable
private fun StatRow(
    icon: AppIcon,
    role: SemanticRole,
    label: String,
    value: String,
    muted: Boolean,
    onClick: (() -> Unit)?,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val interactive = onClick != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashboardV2Sizing.listRowHeight)
            .background(if (interactive && hovered) tokens.surfaceHover else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier
                        .hoverable(interactionSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .onFocusChanged { focused = it.isFocused }
                        .border(
                            width = if (focused) 2.dp else 0.dp,
                            color = if (focused) tokens.focusRing else Color.Transparent,
                            shape = Radius.sm,
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            AppIconImage(icon = icon, size = 16.dp, tint = role.color())
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(DashboardV2Sizing.statLabelWidth),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (muted) tokens.textSecondary else tokens.textPrimary,
                maxLines = 1,
            )
            // Chevron SÓLO donde hay navegación verificada. Hoy lo llevan
            // Exámenes, Preguntas e Importaciones; Recursos no, porque su ruta
            // resuelve a la misma pantalla que Preguntas.
            if (interactive) {
                AppIconImage(
                    icon = AppIcon.CHEVRON_RIGHT,
                    size = 14.dp,
                    tint = tokens.accentInk,
                    contentDescription = "Abrir $label",
                )
            }
        }
    }
}

/**
 * La cifra de una tarjeta: el elemento dominante de su tarjeta, a 27sp.
 *
 * `NO_DATA` se pinta como «Sin dato» en color secundario y con el mismo hueco:
 * la tarjeta sigue ocupando su sitio porque reintentar puede arreglarla y quien
 * mira necesita saber que ese número le falta. Un cero, en cambio, es un dato y
 * se pinta como cualquier otro número — sólo que [muted] le quita el peso de
 * color cuando ese cero significa «no hay nada pendiente».
 */
@Composable
private fun MetricValueText(cell: DashboardMetricCell, muted: Boolean) {
    val tokens = GlassTheme.tokens
    val (text, color) = when (cell) {
        is DashboardMetricCell.Number ->
            cell.text to if (muted) tokens.textSecondary else tokens.textPrimary
        DashboardMetricCell.NoData -> SIN_DATO to tokens.textSecondary
        // `renderable` ya filtra las NOT_INSTRUMENTED antes de llegar aquí; esta
        // rama existe para que el `when` sea exhaustivo sin inventar un texto.
        DashboardMetricCell.Hidden -> return
    }
    Text(
        text = text,
        style = if (cell is DashboardMetricCell.NoData) {
            // «Sin dato» a 27sp ocupa el ancho de la tarjeta y grita más que
            // cualquier cifra real. Es una ausencia: se dice, no se proclama.
            MaterialTheme.typography.titleLarge
        } else {
            MaterialTheme.typography.headlineMedium
        },
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Enlace de acción con chevron a la derecha.
 *
 * No lo cubre `GhostButton`: allí el icono va SIEMPRE delante del texto, y lo que
 * la referencia pide —y lo que se lee como enlace y no como botón— es «Revisar
 * reportes →».
 *
 * **Dos colores de texto, y no por capricho.** `accentInk` (#C2521F) da 4,6:1
 * sobre superficie sólida pero ~4,2:1 sobre el cristal de una sección, por debajo
 * del 4,5:1 que exige un texto normal. Durante la pasada visual se oscureció el
 * token a #B04A1B para unificar el color, y **se revirtió por decisión de marca**:
 * `accentInk` es el relleno del botón primario de toda la aplicación, móvil
 * incluido, y un rediseño de una pantalla no justifica moverlo.
 *
 * Así que la excepción sigue viva y está medida en `ContrastTest`: sobre cristal
 * el texto va en `textPrimary` y el coral se queda en el chevron, que es un
 * elemento de interfaz y le basta con 3:1. Por eso [textColor] tiene por defecto
 * el caso de cristal —seis de los siete enlaces del panel son cabeceras de
 * sección— y el enlace de dentro de una tarjeta, que sí pisa superficie opaca,
 * pide el coral explícitamente.
 */
@Composable
private fun DashboardLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = GlassTheme.tokens.textPrimary,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(Radius.sm)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) tokens.focusRing else Color.Transparent,
                shape = Radius.sm,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
            .semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            textDecoration = if (hovered) TextDecoration.Underline else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        AppIconImage(icon = AppIcon.CHEVRON_RIGHT, size = 14.dp, tint = tokens.accentInk)
    }
}

// ---------------------------------------------------------------------------
// Medidas y colores
// ---------------------------------------------------------------------------

/**
 * Todas las medidas del panel, en un sitio.
 *
 * Las que existen como token salen del token. Las alturas de tarjeta no tienen
 * token —el Design System no define ninguna— así que se declaran aquí, en dp
 * exactos, y siempre como mínimo (`heightIn`) y no como alto fijo: un alto fijo
 * recorta el texto en cuanto alguien sube la escala del sistema.
 *
 * Los mínimos están calculados contra el presupuesto vertical de una pantalla de
 * 1080: cabecera de página 113dp + barra superior 60dp dejan 875dp de contenido,
 * y las cuatro franjas superiores suman 776dp, así que la rejilla inferior asoma
 * y el resto se alcanza con un solo gesto de rueda.
 */
private object DashboardV2Sizing {
    /** Padding lateral del contenedor de sección. */
    val sectionHorizontalPadding: Dp = Spacing.lg

    /**
     * Padding vertical del contenedor de sección.
     *
     * Menor que el lateral a propósito, y ahora bastante menor. La pantalla apila
     * cinco contenedores, así que cada dp de aquí se paga diez veces; el lateral
     * se paga dos y por eso se queda en 20. Bajar de 16 a 12 devuelve 40dp del
     * presupuesto vertical sin tocar ni una tarjeta, que es exactamente donde hay
     * que buscarlos: el cromo no tiene forma reconocible que romper.
     */
    val sectionVerticalPadding: Dp = Spacing.sm

    /** Separación entre franjas. */
    val sectionGap: Dp = Spacing.md

    /** Hueco entre la cabecera de una sección y lo que va dentro. */
    val sectionHeaderGap: Dp = 10.dp

    /**
     * Por debajo de este alto de contenido, la pantalla retira los subtítulos.
     *
     * 620 no es redondo: es el alto que hace falta para que «Pulso del producto»
     * y su primera fila de métricas queden por encima del corte con los
     * subtítulos puestos. Un portátil de 768px deja 551dp de contenido y cae del
     * lado corto; uno de 900px deja 683 y no.
     */
    val shortViewportHeight: Dp = 620.dp

    /** Hueco entre tarjetas de una misma rejilla. */
    val cardGap: Dp = Spacing.sm

    val systemStripHeight: Dp = Sizing.buttonNormal

    /**
     * Sitio reservado para «Ver estado», que está SIEMPRE.
     *
     * Medido, no estimado: el enlace ocupa 87dp exactos
     * (`DashboardV2SystemStripUiTest` lo comprueba). 104dp deja margen para el
     * hueco y para que el redondeo de fuentes no se lo coma.
     */
    val systemStripLinkReserve: Dp = 104.dp

    /**
     * Reserva cuando además está «Reintentar», es decir sólo en estado de error.
     *
     * No se reserva esto siempre: serían 136dp de más apretando al grupo
     * izquierdo el 99% del tiempo, y elidiría «Actualizado hace…» sin ninguna
     * necesidad.
     */
    val systemStripActionsReserve: Dp = 240.dp

    /** Suelo del grupo izquierdo. Puramente defensivo: con el mínimo de ventana nunca se alcanza. */
    val systemStripLeftMinWidth: Dp = 120.dp

    /** Contenedor de icono de la franja de sistema. */
    val stripIconChip: Dp = Sizing.iconButton

    /** Contenedor de icono de una cabecera de sección mayor y de una densa. */
    val sectionIconChip: Dp = Sizing.iconButton
    val sectionIconChipDense: Dp = Sizing.iconButtonSmall

    /** Contenedor de icono de una tarjeta: atención, atajo y KPI comparten medida. */
    val cardIconChip: Dp = 44.dp
    val cardIconSize: Dp = 22.dp

    val listRowHeight: Dp = 52.dp
    val timelineGutter: Dp = Spacing.md
    val timelineDot: Dp = 9.dp

    /** Icono, cifra, título, matiz y enlace, apilados. */
    val attentionCardHeight: Dp = 184.dp

    /** Contenedor de icono + etiqueta de hasta dos líneas. */
    val quickActionTileHeight: Dp = 92.dp

    /**
     * Icono a la izquierda; etiqueta, cifra y apoyo a la derecha.
     *
     * 108 y no 96 para que el mínimo VINCULE siempre. Con 96 mandaba el contenido,
     * y el contenido no mide lo mismo en las cuatro: «Sin dato» se pinta un peldaño
     * tipográfico por debajo de una cifra —a propósito, para que una ausencia no
     * grite más que un dato— así que su tarjeta salía 10px más corta que sus tres
     * hermanas y el borde inferior de la fila quedaba dentado. Por encima del
     * contenido más alto (103dp), las cuatro miden lo mismo pase lo que pase.
     */
    val pulseCardHeight: Dp = 108.dp

    /**
     * Alto mínimo de los tres paneles inferiores, para que la fila quede alineada.
     *
     * 356 y no 300 porque es lo que mide el más alto de los tres: cabecera 48dp +
     * 12 de hueco + cinco filas de cronología de 52dp + 32 de padding. Con 300, la
     * cronología desbordaba su mínimo y los tres paneles terminaban a alturas
     * distintas — que es justo lo que este mínimo existe para evitar. La rejilla
     * alinea por arriba, así que el desnivel se veía todo abajo.
     */
    val bottomPanelHeight: Dp = 356.dp

    /**
     * Cromo de un panel inferior: cabecera densa (48) + hueco (10) + padding (24).
     *
     * Existe para poder restarlo y saber cuánto le queda a la lista.
     */
    val bottomPanelChrome: Dp = 82.dp

    /**
     * Alto mínimo del bloque blanco de filas dentro de un panel inferior.
     *
     * Es la corrección de un defecto medido: con 4, 5 y 3 filas y los tres paneles
     * igualados a 356dp, sobraba cristal por debajo de la lista —76px en
     * Contenido, 129 en Economía y **183 en Actividad con dos filas**, que es el
     * caso real de hoy—. Ese hueco no se leía como aire sino como separación entre
     * dos superficies, es decir como si el panel tuviera algo más abajo y faltara.
     *
     * Estirando la lista hasta el fondo el panel vuelve a ser una sola superficie
     * con espacio de sobra dentro, que es lo que es. Se calcula y no se escribe a
     * mano para que siga cuadrando si cambia [bottomPanelHeight].
     */
    val bottomListHeight: Dp = bottomPanelHeight - bottomPanelChrome

    /**
     * Ancho del texto en una fila de lista, con la cifra alineada a la derecha.
     *
     * 170dp es lo que cabe en la columna más estrecha en que estas listas se
     * dibujan a tres columnas: 1366 con sidebar desplegada da 350dp de columna, y
     * de ahí salen 40 de padding de panel, 24 de padding de fila, 24 de icono y
     * hueco y ~70 de cifra y chevron. Más ancho elidiría la etiqueta ahí para
     * ganar un hueco que en 1920 ya sobra.
     */
    val statLabelWidth: Dp = 170.dp
    val activityLabelWidth: Dp = 170.dp

    /** El radio de tarjeta de dato del sistema. */
    val cardShape: Shape = Radius.card
}

@Composable
private fun StatusTone.dotColor(): Color = when (this) {
    StatusTone.POSITIVE -> GlassTheme.tokens.success
    StatusTone.ATTENTION -> GlassTheme.tokens.warning
    StatusTone.NEGATIVE -> GlassTheme.tokens.danger
    StatusTone.NEUTRAL -> GlassTheme.tokens.info
    StatusTone.UNKNOWN -> GlassTheme.tokens.textSecondary
}

/** `UNKNOWN` no se pinta en verde por descarte: se pinta como lo que es. */
private fun SystemStatusLevel.tone(): StatusTone = when (this) {
    SystemStatusLevel.OPERATIVE -> StatusTone.POSITIVE
    SystemStatusLevel.DEGRADED -> StatusTone.ATTENTION
    SystemStatusLevel.DOWN -> StatusTone.NEGATIVE
    SystemStatusLevel.UNKNOWN -> StatusTone.UNKNOWN
}

private fun SystemStatusLevel.label(): String = when (this) {
    SystemStatusLevel.OPERATIVE -> "operativo"
    SystemStatusLevel.DEGRADED -> "degradado"
    SystemStatusLevel.DOWN -> "caído"
    SystemStatusLevel.UNKNOWN -> "desconocido"
}

private fun columnWidth(available: Dp, columns: Int, gap: Dp): Dp =
    (available - gap * (columns - 1)) / columns

/**
 * Cuánto tiene que medir la lista de un panel inferior para llegar a su fondo.
 *
 * Va atada al mínimo del panel: si éste no lo tiene —una sola columna, donde
 * igualar alturas no alinea nada— la lista tampoco, y mide lo que midan sus filas.
 */
private fun listMinHeight(panelMinHeight: Dp): Dp =
    if (panelMinHeight.isSpecified) {
        panelMinHeight - DashboardV2Sizing.bottomPanelChrome
    } else {
        Dp.Unspecified
    }

/**
 * Cuánto puede ocupar el grupo izquierdo de la franja de sistema.
 *
 * Todo lo que no sea el padding horizontal ni la reserva de las acciones. Sale
 * del único `BoxWithConstraints` de la pantalla, en dp exactos: **el problema que
 * arregla no era la falta de `weight()` sino la falta de un máximo.**
 *
 * `Arrangement.SpaceBetween` mide al primer hijo con TODO el ancho disponible y
 * al segundo con lo que sobre. Medido sobre la versión sin cota: con 700dp de
 * contenido «Ver estado» baja de 87 a 69dp, con 640dp a 9dp, y a partir de 600dp
 * **desaparece** (0dp). Que hoy no se alcance —el suelo real del panel son
 * 756dp— es una holgura de ~116dp que cualquier cadena más larga (un «degradado»
 * en vez de «operativo», una latencia de cuatro cifras) se come. Esto convierte
 * esa holgura implícita en una garantía declarada.
 */
internal fun systemStripLeftMaxWidth(contentWidth: Dp, showsRetry: Boolean): Dp {
    val reserve = if (showsRetry) {
        DashboardV2Sizing.systemStripActionsReserve
    } else {
        DashboardV2Sizing.systemStripLinkReserve
    }
    return (contentWidth - DashboardV2Sizing.sectionHorizontalPadding * 2 - reserve)
        .coerceAtLeast(DashboardV2Sizing.systemStripLeftMinWidth)
}

private const val SYSTEM_REFRESH_MILLIS = 60_000L
private const val RELATIVE_CLOCK_MILLIS = 30_000L
private const val ACTIVITY_ROWS = 5
