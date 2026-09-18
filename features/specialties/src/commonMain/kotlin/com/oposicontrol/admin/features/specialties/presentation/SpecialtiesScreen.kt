package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.layout.supportsMasterDetail

/**
 * Especialidades, en escritorio.
 *
 * Un workspace de tres columnas —catálogo, especialidad, resumen— y no un
 * listado con un formulario colgando, porque administrar una taxonomía es ir y
 * volver entre una fila y su detalle comparando, y eso pide tener las tres a la
 * vista. Es la misma arquitectura que el workspace de Exámenes, a propósito.
 *
 * Cuatro decisiones estructurales que conviene no deshacer sin querer:
 *
 *  - **Un solo `BoxWithConstraints`**, en la raíz. Todos los anchos de columna
 *    salen de él en dp exactos, desde [specialtiesColumns] y
 *    [SpecialtiesSizing]. Sin puntos de corte propios: el régimen de ventana lo
 *    decide `DesktopWindow`, que es el único que existe en el proyecto.
 *  - **Cristal fuera, opaco dentro.** Catálogo, editor y rail son contenedores
 *    de sección translúcidos; todo lo que lleva un dato o un campo va sobre
 *    superficie opaca.
 *  - **El editor manda.** Si con las tres columnas el centro no llega a 700dp,
 *    se pliega el rail —que sólo resume— y nunca el editor.
 *  - **La pantalla NO añade margen exterior.** Lo pone `DesktopShell`.
 */
@Composable
fun SpecialtiesScreen(
    viewModel: SpecialtiesViewModel,
) {
    val state by viewModel.state.collectAsState()
    SpecialtiesContent(state = state, onIntent = viewModel::dispatch)
}

/**
 * El workspace, por ESTADO.
 *
 * Entra por `state` y `onIntent` y no por el ViewModel para que el banco de
 * pruebas visual pueda renderizar la pantalla real contra estados fabricados,
 * sin levantar Ktor ni Koin. [initialTab] existe por la misma razón que en
 * Exámenes: una pestaña es un estado de vista, y quien compone la pantalla tiene
 * que poder decidir en cuál entra.
 */
@Composable
internal fun SpecialtiesContent(
    state: SpecialtiesState,
    onIntent: (SpecialtiesIntent) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: SpecialtyTabId = SpecialtyTabId.IDENTIDAD,
) {
    // La pestaña se reinicia al cambiar de especialidad a propósito: quien abre
    // otra quiere ver de qué va, no seguir en Impacto porque era donde estaba.
    // Vive en un `rememberSaveable` local y no en el estado del ViewModel por lo
    // mismo que en Exámenes: es estado de vista, no de dominio, y no participa
    // en ninguna escritura.
    var selectedTabId by rememberSaveable(state.selectedId, state.creatingNew) {
        mutableStateOf(initialTab.id)
    }
    val selectedTab = SpecialtyTabId.fromId(selectedTabId)
    val breakdown = (state.exams as? UiAsync.Success)
        ?.value
        ?.let { exams -> specialtyExamBreakdown(exams, state.selectedId) }
        ?.takeIf { state.selectedId != null }
    val breakpoint = DesktopWindow.breakpoint

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val layout = specialtiesColumns(totalWidth, breakpoint.isWide, breakpoint.supportsMasterDetail)

        if (layout.stacked) {
            // Apilado: desplaza la PÁGINA, no cada panel por dentro. Con el
            // reparto por pesos que usa master-detail, el catálogo y las
            // pestañas se comen el alto disponible y al formulario le quedan
            // cero píxeles.
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SpecialtiesSizing.columnGap),
            ) {
                SpecialtiesCatalogPane(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = SpecialtiesSizing.stackedCatalogMaxHeight)
                        .testTag(SPECIALTIES_CATALOG_TAG),
                )
                SpecialtiesWorkspaceTabs(
                    state = state,
                    breakdown = breakdown,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTabId = it.id },
                    onIntent = onIntent,
                    contentWidth = totalWidth,
                    fillsHeight = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                // En este régimen el rail no es una columna: se pone debajo, con
                // su mismo contenido. No se oculta, porque «Antes de guardar» es
                // justo lo que hace falta antes de pulsar el botón que queda al
                // lado.
                SpecialtySummaryPanel(
                    state = state,
                    breakdown = breakdown,
                    modifier = Modifier.fillMaxWidth().testTag(SPECIALTY_RAIL_TAG),
                    // Aquí desplaza la página: el rail NO puede llevar su propio
                    // scroll dentro de otro, o Compose lanza al medirlo.
                    scrolls = false,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(SpecialtiesSizing.columnGap),
            ) {
                SpecialtiesCatalogPane(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier
                        .width(layout.catalogWidth)
                        .fillMaxHeight()
                        .testTag(SPECIALTIES_CATALOG_TAG),
                )
                Box(modifier = Modifier.width(layout.editorWidth).fillMaxHeight()) {
                    SpecialtiesWorkspaceTabs(
                        state = state,
                        breakdown = breakdown,
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTabId = it.id },
                        onIntent = onIntent,
                        contentWidth = layout.editorWidth,
                        fillsHeight = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (layout.showRail) {
                    // Sin `fillMaxHeight()`: no hay nada anclado al fondo de esta
                    // columna, y estirar el cristal hasta el borde inferior sólo
                    // dejaría dos tercios vacíos.
                    SpecialtySummaryPanel(
                        state = state,
                        breakdown = breakdown,
                        modifier = Modifier.width(layout.railWidth).testTag(SPECIALTY_RAIL_TAG),
                    )
                }
            }
        }
    }

    // Los dos confirmables que esta pantalla puede producir. Comparten el
    // mecanismo del ViewModel (`pendingAction`) en vez de tener cada uno su
    // propio estado local: así el mensaje y el «hay algo pendiente» viven en un
    // solo sitio.
    when (state.pendingAction) {
        PendingSpecialtyAction.Archive -> ConfirmDialog(
            // ARCHIVAR, no «Eliminar»: el servidor convierte el DELETE en
            // `activa = false` y la fila se queda en el catálogo.
            title = "Archivar especialidad",
            message = state.pendingMessage,
            confirmLabel = "Archivar",
            dismissLabel = "Seguir aquí",
            // `destructive = false` porque es reversible y no borra nada: se
            // deshace con el interruptor de Visibilidad. El botón rojo que abre
            // este diálogo ya marca la familia; pintar también el confirmar en
            // rojo relleno lo contaría como un borrado, que es precisamente lo
            // que no es.
            destructive = false,
            onConfirm = { onIntent(SpecialtiesIntent.ConfirmPendingAction) },
            onDismiss = { onIntent(SpecialtiesIntent.DismissPendingAction) },
        )
        is PendingSpecialtyAction.Switch -> ConfirmDialog(
            title = "Cambios sin guardar",
            message = state.pendingMessage,
            confirmLabel = "Descartar y abrir",
            dismissLabel = "Seguir aquí",
            // No borra nada del servidor: lo que se pierde es lo escrito en
            // pantalla. Las dos etiquetas lo dicen sin adornos, en vez de un
            // «Aceptar/Cancelar» que obliga a deducir cuál deja las cosas como
            // están.
            destructive = false,
            onConfirm = { onIntent(SpecialtiesIntent.ConfirmPendingAction) },
            onDismiss = { onIntent(SpecialtiesIntent.DismissPendingAction) },
        )
        null -> Unit
    }
}
