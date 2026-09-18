package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.EmptyStateCard
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusDotChip
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/** Etiquetas de prueba del editor. Sólo semántica: no se pintan. */
internal const val SPECIALTY_EDITOR_FORM_TAG: String = "specialty-editor-form"
internal const val SPECIALTY_EDITOR_HEADER_TAG: String = "specialty-editor-header"

/**
 * Las tres vistas de una especialidad.
 *
 * Cada una contesta una pregunta distinta, y por eso son pestañas y no una
 * columna de veinte campos: qué especialidad es ésta y si se ve (Identidad), qué
 * puede existir dentro (Tipos de examen) y qué hay ya dentro (Impacto).
 */
internal enum class SpecialtyTabId(
    val id: String,
    val label: String,
    val icon: AppIcon,
) {
    IDENTIDAD("identidad", "Identidad", AppIcon.LAYERS),
    TIPOS("tipos", "Tipos de examen", AppIcon.QUESTIONS),
    IMPACTO("impacto", "Impacto", AppIcon.ANALYTICS),
    ;

    companion object {
        fun fromId(id: String): SpecialtyTabId = entries.firstOrNull { it.id == id } ?: IDENTIDAD
    }
}

/**
 * El marco del editor: cabecera, pestañas, formulario que desplaza y acciones
 * abajo.
 *
 * Guardar no desplaza con el formulario a propósito. Un editor de varias
 * pestañas que esconde su propio botón de guardar al bajar es el motivo por el
 * que la gente pierde cambios; que la barra viva fuera del scroll cuesta 40dp y
 * los vale — y aquí más, porque esa barra es además lo único que dice que hay
 * DOS escrituras.
 *
 * El contenedor es cristal —nivel 1— y todo lo que va dentro es opaco. Con un
 * formulario se trabaja durante horas, y un campo translúcido sobre un degradado
 * cambia de color según dónde caiga en la pantalla.
 *
 * A diferencia de Exámenes, la cabecera de la especialidad vive DENTRO de este
 * mismo cristal en vez de ser una sección aparte con su propio plegado: son un
 * nombre, un estado y dos cifras —una línea— y sacarlos a un contenedor propio
 * sólo gastaría 70dp de alto en una ventana de 768 sin añadir nada que el rail no
 * diga ya.
 */
@Composable
internal fun SpecialtiesWorkspaceTabs(
    state: SpecialtiesState,
    breakdown: SpecialtyExamBreakdown?,
    selectedTab: SpecialtyTabId,
    onSelectTab: (SpecialtyTabId) -> Unit,
    onIntent: (SpecialtiesIntent) -> Unit,
    contentWidth: Dp,
    /**
     * Si este marco tiene un alto propio que rellenar.
     *
     * `true` en master-detail: el editor recibe el alto de la columna, el
     * contenido desplaza por dentro y la barra de guardar queda anclada al pie.
     *
     * `false` cuando las columnas se apilan. Ahí el alto lo reparte la página, y
     * un `weight(1f)` sobre un `Column` que ya no tiene sobrante dejaría el
     * formulario en CERO píxeles.
     */
    fillsHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    GlassSectionSurface(
        modifier = modifier,
        shape = Radius.xl,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SpecialtyEditorHeader(
            state = state,
            breakdown = breakdown,
            modifier = Modifier.testTag(SPECIALTY_EDITOR_HEADER_TAG),
        )

        // Cuando la fila de pestañas no cabe, se sacrifica el ICONO y nunca la
        // palabra: la palabra es la que identifica la pestaña. Las tres
        // etiquetas son cortas y no tienen forma abreviada que no pierda
        // sentido —«Tipos» a secas no dice de qué tipos—, así que el recorte se
        // queda en el icono.
        val compactTabs = contentWidth < SpecialtiesSizing.tabIconsMinWidth
        DesktopTabs(
            tabs = SpecialtyTabId.entries.map { tab ->
                DesktopTab(
                    id = tab.id,
                    label = tab.label,
                    icon = tab.icon.takeUnless { compactTabs },
                    // Sólo Impacto cuenta algo, y sólo cuando el recuento es un
                    // dato: un badge sobre «Contando…» o sobre un fallo de
                    // lectura sería un cero inventado.
                    badge = if (tab == SpecialtyTabId.IMPACTO) {
                        breakdown?.total?.takeIf { it > 0 }?.toString()
                    } else {
                        null
                    },
                )
            },
            selectedId = selectedTab.id,
            onSelect = { onSelectTab(SpecialtyTabId.fromId(it)) },
            // Las pestañas SON la estructura de este workspace, no un índice al
            // margen.
            variant = DesktopTabsVariant.WORKSPACE,
        )

        if (fillsHeight) {
            val formScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(SPECIALTY_EDITOR_FORM_TAG),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(formScroll)
                        // Hueco para la barra: sin él el pulgar se dibujaría
                        // encima del borde redondeado de las secciones.
                        .padding(end = SpecialtiesSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    SpecialtyTabContent(state, breakdown, selectedTab, contentWidth, onIntent)
                }
                DesktopVerticalScrollbar(
                    scrollState = formScroll,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(SPECIALTY_EDITOR_FORM_TAG),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                SpecialtyTabContent(state, breakdown, selectedTab, contentWidth, onIntent)
            }
        }

        SpecialtySaveBar(
            state = state,
            onIntent = onIntent,
            modifier = Modifier.testTag(SPECIALTY_SAVE_BAR_TAG),
        )
    }
}

/** El contenido de la pestaña abierta, o el estado vacío si no hay nada abierto. */
@Composable
private fun SpecialtyTabContent(
    state: SpecialtiesState,
    breakdown: SpecialtyExamBreakdown?,
    selectedTab: SpecialtyTabId,
    contentWidth: Dp,
    onIntent: (SpecialtiesIntent) -> Unit,
) {
    if (state.selectedId == null && !state.creatingNew) {
        EmptyStateCard(
            title = "Sin especialidad abierta",
            message = "Elige una especialidad del catálogo para ver y editar su identidad, sus tipos de " +
                "examen y lo que tiene dentro.",
            icon = AppIcon.LAYERS,
            action = {
                PrimaryButton(
                    text = "Nueva especialidad",
                    onClick = { onIntent(SpecialtiesIntent.SwitchSpecialty(null)) },
                )
            },
        )
        return
    }
    when (selectedTab) {
        SpecialtyTabId.IDENTIDAD -> SpecialtyIdentitySection(state = state, onIntent = onIntent)
        SpecialtyTabId.TIPOS -> SpecialtyExamTypesSection(
            state = state,
            breakdown = breakdown,
            contentWidth = contentWidth,
            onIntent = onIntent,
        )
        SpecialtyTabId.IMPACTO -> SpecialtyImpactSection(
            state = state,
            breakdown = breakdown,
            contentWidth = contentWidth,
            onIntent = onIntent,
        )
    }
}

/**
 * Cabecera del editor: quién está abierto, en qué estado y con cuánto detrás.
 *
 * Va sobre el cristal de la sección, así que sus colores salen de
 * [specialtyGlassText] y el chip de estado es el de píldora
 * (`StatusDotChip`), no el de superficie opaca: el tinte de un `StatusChip` sobre
 * un fondo translúcido se sumaría al degradado y daría un color distinto en cada
 * punto de la ventana.
 */
@Composable
private fun SpecialtyEditorHeader(
    state: SpecialtiesState,
    breakdown: SpecialtyExamBreakdown?,
    modifier: Modifier = Modifier,
) {
    val palette = specialtyGlassText(GlassTheme.tokens)
    val loaded = state.selectedSpecialty()
    val title = when {
        state.creatingNew -> "Nueva especialidad"
        state.selectedId == null -> "Especialidades"
        else -> state.editorName.ifBlank { "Especialidad sin nombre" }
    }
    val subtitle = when {
        state.creatingNew -> "El código y el slug los genera el servidor al crearla."
        state.selectedId == null -> "Ninguna especialidad abierta."
        // El catálogo es la fuente de la especialidad abierta: si no está, se
        // dice, en vez de dejar el hueco y que se lea como «sin descripción».
        loaded == null -> "No está en el catálogo cargado."
        else -> specialtyRowDescription(loaded) ?: "Sin descripción"
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        SemanticIconContainer(
            icon = AppIcon.LAYERS,
            role = if (state.creatingNew) SemanticRole.BRAND else SemanticRole.INFO,
            size = Sizing.iconButton,
            iconSize = Sizing.iconLarge,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.selectedId != null || state.creatingNew) {
            StatusDotChip(
                label = specialtyStatusLabel(state.editorIsActive),
                tone = specialtyStatusTone(state.editorIsActive),
            )
            SpecialtyGlassPill(
                text = when {
                    breakdown == null -> "Exámenes: sin contar"
                    else -> specialtyExamCountLabel(breakdown.total)
                },
                icon = AppIcon.EXAMS,
            )
        }
    }
}
