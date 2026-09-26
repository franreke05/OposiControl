package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.layout.DesktopWindow
import com.oposicontrol.admin.core.ui.layout.supportsMasterDetail
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam

/**
 * Exámenes, en escritorio.
 *
 * Un workspace de tres columnas —catálogo, examen, resumen— y no un listado con
 * un formulario colgando: administrar exámenes es ir y volver entre uno y otro
 * comparando, y eso pide tener los tres a la vista.
 *
 * Cuatro decisiones estructurales que conviene no deshacer sin querer:
 *
 *  - **Un solo `BoxWithConstraints`**, en la raíz. Todos los anchos de columna
 *    salen de él en dp exactos, desde [ExamWorkspaceSizing]. Sin `weight()`
 *    horizontal y sin puntos de corte propios: el régimen de ventana lo decide
 *    [DesktopWindow], que es el único que existe en el proyecto.
 *  - **Cristal fuera, opaco dentro.** Catálogo, cabecera, editor y resumen son
 *    contenedores de sección translúcidos; todo lo que lleva un dato o un campo
 *    va sobre superficie opaca. Con un formulario se trabaja horas.
 *  - **Refrescar no borra el workspace.** `RefreshAll` recarga las listas y deja
 *    intacto el examen abierto y lo que se esté escribiendo. Lo único que enseña
 *    esqueleto es cambiar de examen, y sólo en la parte que cambia.
 *  - **La búsqueda global no se dibuja aquí.** Es decorativa —el repositorio
 *    devuelve siempre lista vacía— y prometería en esta pantalla justo lo que el
 *    buscador local sí hace de verdad. Se apaga desde `AppRoot`, con el mismo
 *    mecanismo que ya usa el panel de escritorio.
 */
@Composable
fun ExamWorkspaceScreen(
    viewModel: ExamsViewModel,
    onOpenQuestionBank: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    ExamWorkspaceContent(
        state = state,
        onIntent = viewModel::dispatch,
        onOpenQuestionBank = onOpenQuestionBank,
    )
}

@Composable
internal fun ExamWorkspaceContent(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    onOpenQuestionBank: (() -> Unit)? = null,
    /**
     * Con qué pestaña abre.
     *
     * Existe por la misma razón que `initialSection` en OposiMarket o
     * `initialTab` en los hubs: una pestaña es un estado de vista, y quien
     * compone la pantalla tiene que poder decidir en cuál entra. Aquí lo usa el
     * banco de pruebas visual para capturar las seis sin simular clics.
     */
    initialTab: ExamWorkspaceTabId = ExamWorkspaceTabId.BASICA,
) {
    // La pestaña se reinicia al cambiar de examen a propósito: quien abre otro
    // examen quiere ver de qué va, no seguir en Recursos porque era donde estaba.
    var selectedTabId by rememberSaveable(state.selectedExamId) {
        mutableStateOf(initialTab.id)
    }
    // Un guardado rechazado que señala un campo ABRE la pestaña de ese campo.
    //
    // Sin esto, marcar el campo en rojo no serviría de nada la mitad de las veces:
    // el que rechaza `examenes_total_preguntas_check` (`>= 0` desde PW-2; tras M1
    // sólo un negativo escrito fuera de la API lo dispara) está en Configuración y
    // el formulario abre en Información básica, así que la única señal seguiría
    // siendo la línea al pie. El porqué de elegir esto y no una insignia en la
    // pestaña está en [examFieldErrorTab].
    //
    // La clave es la pestaña, no el estado: mientras el error sea el mismo no se
    // vuelve a mover nada, así que cambiar de pestaña a mano después del salto
    // funciona y el usuario conserva el control de la pantalla.
    val tabToReveal = examFieldErrorTab(state)
    LaunchedEffect(tabToReveal) {
        tabToReveal?.let { selectedTabId = it.id }
    }
    val selectedTab = ExamWorkspaceTabId.fromId(selectedTabId)
    val exam = state.selectedExam()
    val questionCount = linkedQuestionCount(state.questions, exam)
    val resourceCount = (state.questions as? UiAsync.Success)
        ?.value
        ?.let { examResourceTotals(it).resourceCount }
    val breakpoint = DesktopWindow.breakpoint

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val layout = examWorkspaceColumns(totalWidth, breakpoint.isWide, breakpoint.supportsMasterDetail)

        if (layout.stacked) {
            // Apilado: desplaza la PÁGINA, no cada panel por dentro.
            //
            // El reparto por `weight` que se usa en master-detail aquí no vale: el
            // catálogo, la cabecera y las pestañas se comían el alto disponible y
            // al formulario le quedaban cero píxeles — en 1080 el editor
            // literalmente no se veía. Con la página desplazando, cada bloque mide
            // lo que necesita y se llega a todo bajando, que es la respuesta
            // correcta a una ventana estrecha y no un defecto que haya que
            // comprimir.
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ExamWorkspaceSizing.columnGap),
            ) {
                ExamCatalogPane(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ExamWorkspaceSizing.stackedCatalogMaxHeight),
                )
                ExamWorkspaceColumn(
                    state = state,
                    exam = exam,
                    questionCount = questionCount,
                    resourceCount = resourceCount,
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTabId = it.id },
                    onIntent = onIntent,
                    onOpenQuestionBank = onOpenQuestionBank,
                    showSummaryStrip = true,
                    contentWidth = totalWidth,
                    fillsHeight = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(ExamWorkspaceSizing.columnGap),
            ) {
                ExamCatalogPane(
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier.width(layout.catalogWidth).fillMaxHeight(),
                )
                Box(modifier = Modifier.width(layout.editorWidth).fillMaxHeight()) {
                    ExamWorkspaceColumn(
                        state = state,
                        exam = exam,
                        questionCount = questionCount,
                        resourceCount = resourceCount,
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTabId = it.id },
                        onIntent = onIntent,
                        onOpenQuestionBank = onOpenQuestionBank,
                        showSummaryStrip = !layout.showSummaryColumn,
                        contentWidth = layout.editorWidth,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (layout.showSummaryColumn) {
                    // Sin `fillMaxHeight()`. Lo copió del panel central, donde sí
                    // está justificado —ahí ancla la barra de guardar al fondo—,
                    // pero aquí no hay nada anclado abajo: sólo estiraba el
                    // cristal hasta el borde inferior y dejaba 586px de 871
                    // vacíos, dos tercios de la columna, en las seis capturas.
                    // Ahora mide lo que miden sus comprobaciones.
                    ExamSummaryPanel(
                        state = state,
                        exam = exam,
                        questionCount = questionCount,
                        communities = state.communities,
                        // Las preguntas ya no son una pestaña del examen: viven en
                        // su propio banco. Si esta pantalla no recibe cómo abrirlo,
                        // el enlace no se pinta en vez de llevar a ninguna parte.
                        onOpenQuestions = onOpenQuestionBank,
                        modifier = Modifier.width(layout.summaryWidth),
                    )
                }
            }
        }
    }

    // Los dos confirmables que esta pantalla puede producir. Comparten el
    // mecanismo del ViewModel (`pendingDeleteTarget`) en vez de tener cada uno su
    // propio estado local: así el mensaje y el «hay algo pendiente» viven en un
    // solo sitio.
    when (state.pendingDeleteTarget) {
        PendingDeleteTarget.ArchiveExam -> ConfirmDialog(
            title = "Archivar examen",
            message = state.pendingDeleteMessage,
            confirmLabel = "Archivar",
            // Reversible, y las etiquetas lo dicen: se puede volver publicándolo.
            dismissLabel = "No archivar",
            onConfirm = { onIntent(ExamsIntent.ConfirmPendingDelete) },
            onDismiss = { onIntent(ExamsIntent.DismissPendingDelete) },
        )
        // La confirmación SERIA. Se diferencia de la de archivar en las tres
        // cosas que se leen antes de pulsar: el título dice «para siempre», el
        // mensaje ([EXAM_DELETE_FOREVER_WARNING]) dice qué desaparece y qué se
        // queda, y las dos etiquetas nombran su consecuencia en vez de
        // «Aceptar/Cancelar» — ahí «Cancelar» sería ambiguo justo donde no se
        // puede dudar: cancelar ¿el borrado, o el diálogo?
        PendingDeleteTarget.DeleteExamForever -> ConfirmDialog(
            title = "Eliminar el examen para siempre",
            message = state.pendingDeleteMessage,
            confirmLabel = "Sí, eliminar",
            dismissLabel = "No eliminar",
            onConfirm = { onIntent(ExamsIntent.ConfirmPendingDelete) },
            onDismiss = { onIntent(ExamsIntent.DismissPendingDelete) },
        )
        // Dos diálogos y no uno, porque son dos operaciones distintas: quitar del
        // examen deshace un vínculo y se puede rehacer desde Preguntas; archivar
        // saca la pregunta del banco entero. El texto del mensaje lo pone el
        // ViewModel, que es donde vive la advertencia de que no se puede saber en
        // cuántos exámenes más va a desaparecer.
        is PendingDeleteTarget.UnlinkQuestion -> ConfirmDialog(
            title = "Quitar del examen",
            message = state.pendingDeleteMessage,
            confirmLabel = "Quitar",
            // No es destructivo: la pregunta sigue en el banco y se puede volver
            // a vincular. Pintarlo en rojo igual que archivar borraría justo la
            // distinción que estos dos diálogos existen para marcar.
            destructive = false,
            onConfirm = { onIntent(ExamsIntent.ConfirmPendingDelete) },
            onDismiss = { onIntent(ExamsIntent.DismissPendingDelete) },
        )
        is PendingDeleteTarget.ArchiveQuestion -> ConfirmDialog(
            title = "Archivar pregunta",
            message = state.pendingDeleteMessage,
            confirmLabel = "Archivar",
            onConfirm = { onIntent(ExamsIntent.ConfirmPendingDelete) },
            onDismiss = { onIntent(ExamsIntent.DismissPendingDelete) },
        )
        // Cambiar de examen con el editor sucio. No borra nada del servidor, así
        // que no se pinta en rojo: lo que se pierde es lo que hay escrito en
        // pantalla, y las dos etiquetas lo dicen sin adornos —«Descartar y abrir»
        // frente a «Seguir aquí»— en vez de un «Aceptar/Cancelar» que obliga a
        // deducir cuál de los dos deja las cosas como están.
        //
        // **Sólo aparece si de verdad hay cambios**: el ViewModel abre directo
        // cuando el editor está limpio. Un diálogo en cada clic del catálogo
        // enseñaría que confirmar no significa nada.
        is PendingDeleteTarget.SwitchExam -> ConfirmDialog(
            title = "Cambios sin guardar",
            message = state.pendingDeleteMessage,
            confirmLabel = "Descartar y abrir",
            dismissLabel = "Seguir aquí",
            destructive = false,
            onConfirm = { onIntent(ExamsIntent.ConfirmPendingDelete) },
            onDismiss = { onIntent(ExamsIntent.DismissPendingDelete) },
        )
        else -> Unit
    }
}

/**
 * Cabecera + franja + pestañas + barra de guardar: la columna central completa.
 *
 * En master-detail la reparte [ExamEditorColumnLayout], que garantiza un alto
 * mínimo al formulario y mantiene la barra de guardar anclada (P0-2). Apilada, es
 * un `Column` sin pesos y la que desplaza es la página.
 */
@Composable
private fun ExamWorkspaceColumn(
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
    resourceCount: Int?,
    selectedTab: ExamWorkspaceTabId,
    onSelectTab: (ExamWorkspaceTabId) -> Unit,
    onIntent: (ExamsIntent) -> Unit,
    onOpenQuestionBank: (() -> Unit)?,
    showSummaryStrip: Boolean,
    contentWidth: Dp,
    fillsHeight: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val header: @Composable () -> Unit = {
        ExamWorkspaceHeader(
            state = state,
            exam = exam,
            communities = state.communities,
            questionCount = questionCount,
            modifier = Modifier.testTag(EXAM_HEADER_TAG),
        )
    }
    val strip: (@Composable () -> Unit)? = if (showSummaryStrip) {
        { ExamSummaryStrip(state = state, exam = exam, questionCount = questionCount) }
    } else {
        null
    }

    if (!fillsHeight) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(ExamWorkspaceSizing.columnGap),
        ) {
            header()
            strip?.invoke()
            ExamWorkspaceTabs(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                state = state,
                exam = exam,
                questionCount = questionCount,
                resourceCount = resourceCount,
                onIntent = onIntent,
                onOpenQuestionBank = onOpenQuestionBank,
                // La franja plegada no es el rail: sólo resume en una línea, así
                // que Publicación sí tiene que enseñar «Antes de publicar».
                railVisible = !showSummaryStrip,
                contentWidth = contentWidth,
                fillsHeight = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        return
    }

    ExamEditorColumnLayout(
        resetKey = state.selectedExamId,
        header = header,
        strip = strip,
        frame = { onFormViewportHeight ->
            ExamWorkspaceTabs(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                state = state,
                exam = exam,
                questionCount = questionCount,
                resourceCount = resourceCount,
                onIntent = onIntent,
                onOpenQuestionBank = onOpenQuestionBank,
                railVisible = !showSummaryStrip,
                contentWidth = contentWidth,
                fillsHeight = true,
                onFormViewportHeight = onFormViewportHeight,
                modifier = Modifier.fillMaxSize(),
            )
        },
        modifier = modifier,
    )
}

/**
 * El examen abierto, resuelto sobre la lista SIN FILTRAR.
 *
 * Nunca sobre `exams`: ésa es la lista que la búsqueda y el filtro de estado ya
 * han recortado, y con ella el examen abierto desaparecía en cuanto se escribía
 * algo que no lo incluyera. La consecuencia no era cosmética — la cabecera perdía
 * su chip de estado, Publicación decía «Guarda el examen para poder publicarlo»
 * a un examen publicado y retiraba sus tres acciones, y el aviso de «Cambios sin
 * guardar» se evaporaba justo cuando había cambios sin guardar.
 *
 * Es la misma fuente sobre la que `selectExam` lo abrió.
 */
internal fun ExamsState.selectedExam(): Exam? =
    catalogExams.firstOrNull { it.id == selectedExamId }

/**
 * El reparto de la pantalla en columnas.
 *
 * Función pura y por eso comprobable: el error típico de un master-detail de tres
 * columnas es que el editor se quede sin sitio en una ventana intermedia, y eso
 * es aritmética, no algo que haya que descubrir mirando la pantalla.
 *
 * La regla es una: **el editor manda**. Si con las tres columnas no llega a su
 * ancho mínimo, la que se pliega es la del resumen —que tiene una forma
 * horizontal equivalente— y nunca el editor.
 */
internal data class ExamWorkspaceColumns(
    val catalogWidth: Dp,
    val editorWidth: Dp,
    val summaryWidth: Dp,
    val showSummaryColumn: Boolean,
    val stacked: Boolean,
)

internal fun examWorkspaceColumns(
    totalWidth: Dp,
    isWide: Boolean,
    supportsMasterDetail: Boolean,
): ExamWorkspaceColumns {
    if (!supportsMasterDetail) {
        return ExamWorkspaceColumns(
            catalogWidth = totalWidth,
            editorWidth = totalWidth,
            summaryWidth = 0.dp,
            showSummaryColumn = false,
            stacked = true,
        )
    }
    val gap = ExamWorkspaceSizing.columnGap
    val catalogWidth = if (isWide) ExamWorkspaceSizing.catalogWide else ExamWorkspaceSizing.catalogStandard
    val summaryWidth = ExamWorkspaceSizing.summaryWide
    val withSummary = totalWidth - catalogWidth - summaryWidth - gap * 2
    val showSummaryColumn = isWide && withSummary >= ExamWorkspaceSizing.editorMinWidth
    return ExamWorkspaceColumns(
        catalogWidth = catalogWidth,
        editorWidth = if (showSummaryColumn) withSummary else totalWidth - catalogWidth - gap,
        summaryWidth = summaryWidth,
        showSummaryColumn = showSummaryColumn,
        stacked = false,
    )
}
