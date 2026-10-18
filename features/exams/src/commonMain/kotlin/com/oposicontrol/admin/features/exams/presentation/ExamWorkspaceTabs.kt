package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.core.ui.components.DesktopTabs
import com.oposicontrol.admin.core.ui.components.DesktopTabsVariant
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Exam

/**
 * Las seis vistas del examen.
 *
 * Cada una contesta una pregunta distinta y por eso son pestañas y no un
 * formulario de setenta campos en una columna: qué examen es esto (básica), cómo
 * funciona (configuración), qué contiene (preguntas), con qué se apoya
 * (recursos), quién puede abrirlo (acceso) y si está vivo (publicación).
 */
internal enum class ExamWorkspaceTabId(
    val id: String,
    val label: String,
    val icon: AppIcon,
    /**
     * Etiqueta corta para cuando la fila no cabe entera.
     *
     * Sólo dos pestañas la necesitan y las dos la aguantan sin perder sentido:
     * el contenido de «Información básica» sigue siendo información, y el de
     * «Acceso y monetización» es, literalmente, el acceso. Se acorta la palabra
     * accesoria, nunca la que identifica la pestaña — «Acceso» sigue diciendo de
     * qué va; «Monetización» a secas, no.
     */
    val shortLabel: String = label,
) {
    BASICA("basica", "Información básica", AppIcon.EXAMS, shortLabel = "Información"),
    CONFIGURACION("configuracion", "Configuración", AppIcon.SETTINGS),
    ACCESO("acceso", "Acceso y monetización", AppIcon.TICKETS, shortLabel = "Acceso"),
    PUBLICACION("publicacion", "Publicación", AppIcon.CHECK),
    ;

    companion object {
        fun fromId(id: String): ExamWorkspaceTabId = entries.firstOrNull { it.id == id } ?: BASICA
    }
}

/**
 * Marco del editor: pestañas arriba, contenido que desplaza en medio, acciones
 * abajo.
 *
 * Guardar no desplaza con el formulario a propósito. Un editor de varias pestañas
 * que esconde su propio botón de guardar al bajar es el motivo por el que la
 * gente pierde cambios; que la barra viva fuera del scroll cuesta 40dp y los
 * vale.
 *
 * **El formulario enseña que desplaza** (P2-5). El resumen llevaba su barra de
 * desplazamiento y el editor no: con Portada bajo el pliegue, nada decía que
 * hubiera un tercer bloque. Ahora lleva la misma `DesktopVerticalScrollbar`, que
 * sólo aparece cuando hay algo debajo, con el mismo hueco reservado.
 *
 * El contenedor es cristal —nivel 1— y todo lo que va dentro es opaco. Esa es la
 * regla del sistema y aquí importa más que en ningún otro sitio: con un
 * formulario se trabaja durante horas, y un campo de texto translúcido sobre un
 * degradado cambia de color según dónde caiga en la pantalla.
 */
@Composable
internal fun ExamWorkspaceTabs(
    selectedTab: ExamWorkspaceTabId,
    onSelectTab: (ExamWorkspaceTabId) -> Unit,
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
    resourceCount: Int?,
    onIntent: (ExamsIntent) -> Unit,
    onOpenQuestionBank: (() -> Unit)?,
    /**
     * Si la columna de resumen está a la vista.
     *
     * Lo usa Publicación para no repetir «Antes de publicar»: esa lista es la del
     * rail, y con el rail delante estaría dos veces en la misma pantalla.
     */
    railVisible: Boolean,
    contentWidth: Dp,
    /**
     * Si este marco tiene un alto propio que rellenar.
     *
     * `true` en master-detail: el editor recibe un alto exacto de
     * [ExamEditorColumnLayout], el contenido desplaza por dentro y la barra de
     * guardar queda anclada al pie.
     *
     * `false` cuando las columnas se apilan. Ahí el alto lo reparte la página, y
     * un `weight(1f)` dentro de un `Column` que ya no tiene sobrante deja el
     * formulario en CERO píxeles: era exactamente lo que pasaba en 1080 — se veían
     * catálogo, cabecera y pestañas, y del formulario no quedaba ni una línea. En
     * ese régimen el contenido mide lo que mide y es la página la que desplaza.
     */
    fillsHeight: Boolean = true,
    /**
     * El alto del área del formulario cada vez que cambia, en px.
     *
     * Lo usa [ExamEditorColumnLayout] para saber cuánto gasta este marco FUERA
     * del formulario —pestañas, barra de guardar, huecos— sin copiar esas medidas
     * en una constante que el primer cambio de un componente dejaría desfasada.
     */
    onFormViewportHeight: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    GlassSectionSurface(
        modifier = modifier,
        shape = Radius.xl,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Cuando la fila de pestañas no cabe, se recorta en dos pasos y en este
        // orden: primero los iconos, después las dos etiquetas que tienen forma
        // corta.
        //
        // Medido sobre las capturas: con icono la fila completa mide ~849dp y
        // vive dentro de 40dp de padding; el editor de un 1440 da ~818 y el de
        // un 1366 ~754, así que en los dos «Publicación» se salía del borde.
        // El umbral está en [ExamWorkspaceTabsSizing.tabIconsMinWidth]. `DesktopTabs` desplaza en horizontal y la pestaña seguía siendo
        // alcanzable, pero una pestaña que no se ve es una pestaña que no existe
        // para quien abre la pantalla por primera vez. Sin iconos baja a ~670 y
        // con las etiquetas cortas a ~560, que entra con holgura.
        //
        // El icono se sacrifica antes que la palabra porque la palabra es la que
        // identifica la pestaña.
        val compactTabs = contentWidth < ExamWorkspaceTabsSizing.tabIconsMinWidth
        DesktopTabs(
            tabs = ExamWorkspaceTabId.entries.map { tab ->
                DesktopTab(
                    id = tab.id,
                    label = if (compactTabs) tab.shortLabel else tab.label,
                    icon = tab.icon.takeUnless { compactTabs },
                    // Ninguna de las cuatro pestañas cuenta nada: las preguntas y
                    // sus recursos se gestionan en el banco de Preguntas, no aquí.
                    // El recuento del examen sigue estando donde importa —cabecera
                    // y resumen—, que es donde se lee sin abrir una pestaña.
                    badge = null,
                )
            },
            selectedId = selectedTab.id,
            onSelect = { onSelectTab(ExamWorkspaceTabId.fromId(it)) },
            // Las pestañas SON la estructura de este workspace, no un índice al
            // margen: con el tratamiento sobrio se leían como navegación
            // secundaria suelta encima del formulario.
            variant = DesktopTabsVariant.WORKSPACE,
        )

        if (fillsHeight) {
            val formScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(EXAM_EDITOR_FORM_TAG)
                    .onSizeChanged { onFormViewportHeight?.invoke(it.height) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(formScroll)
                        // Hueco para la barra: sin él, el pulgar se dibujaría encima
                        // del borde redondeado de las secciones.
                        .padding(end = ExamWorkspaceSizing.scrollbarGutter),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    ExamWorkspaceTabContent(
                        selectedTab = selectedTab,
                        onSelectTab = onSelectTab,
                        state = state,
                        exam = exam,
                        questionCount = questionCount,
                        onIntent = onIntent,
                        onOpenQuestionBank = onOpenQuestionBank,
                        railVisible = railVisible,
                        contentWidth = contentWidth,
                    )
                }
                DesktopVerticalScrollbar(
                    scrollState = formScroll,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(EXAM_EDITOR_FORM_TAG),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ExamWorkspaceTabContent(
                    selectedTab = selectedTab,
                    onSelectTab = onSelectTab,
                    state = state,
                    exam = exam,
                    questionCount = questionCount,
                    onIntent = onIntent,
                    onOpenQuestionBank = onOpenQuestionBank,
                    railVisible = railVisible,
                    contentWidth = contentWidth,
                )
            }
        }

        ExamSaveBar(state = state, exam = exam, onIntent = onIntent, modifier = Modifier.testTag(EXAM_SAVE_BAR_TAG))
    }
}

/** El contenido de la pestaña abierta. */
@Composable
private fun ExamWorkspaceTabContent(
    selectedTab: ExamWorkspaceTabId,
    onSelectTab: (ExamWorkspaceTabId) -> Unit,
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
    onIntent: (ExamsIntent) -> Unit,
    onOpenQuestionBank: (() -> Unit)?,
    railVisible: Boolean,
    contentWidth: Dp,
) {
    when (selectedTab) {
        ExamWorkspaceTabId.BASICA -> ExamBasicInfoSection(state, onIntent)
        ExamWorkspaceTabId.CONFIGURACION -> {
            ExamConfigurationSection(
                state = state,
                onIntent = onIntent,
                // El banco de Preguntas, no una pestaña de este examen. Si la pantalla
                // no recibe cómo abrirlo, Configuración no pinta el enlace.
                onOpenQuestions = onOpenQuestionBank,
                onOpenBasicInfo = { onSelectTab(ExamWorkspaceTabId.BASICA) },
                contentWidth = contentWidth,
            )
            // Reglas de puntuación y su historial: solo con un examen ya guardado y si la app provee el ViewModel.
            if (exam != null && exam.id.isNotBlank()) ExamGradingRulesHost(examId = exam.id, isOfficial = exam.isOfficial)
            // Anuladas y reservas de las preguntas: solo en un examen oficial ya guardado.
            if (exam != null && exam.id.isNotBlank() && exam.isOfficial) ExamOfficialQuestionsHost(examId = exam.id)
        }
        ExamWorkspaceTabId.ACCESO -> ExamAccessSection(
            state = state,
            exam = exam,
            onIntent = onIntent,
            onOpenPublication = { onSelectTab(ExamWorkspaceTabId.PUBLICACION) },
            contentWidth = contentWidth,
        )
        ExamWorkspaceTabId.PUBLICACION -> ExamPublicationSection(
            state = state,
            exam = exam,
            questionCount = questionCount,
            onIntent = onIntent,
            onOpenAccess = { onSelectTab(ExamWorkspaceTabId.ACCESO) },
            railVisible = railVisible,
        )
    }
}

private object ExamWorkspaceTabsSizing {
    /**
     * Ancho de EDITOR por debajo del cual la fila de pestañas pierde sus iconos y
     * pasa a las etiquetas cortas.
     *
     * **650, re-medido con cuatro pestañas.** El valor anterior (900) se midió
     * cuando eran seis: la fila completa ocupaba ~849dp y pedía un editor de
     * ~889, así que 1440 y 1366 caían a la fila compacta. Al retirar Preguntas y
     * Recursos la fila completa —«Información básica · Configuración · Acceso y
     * monetización · Publicación», con icono y la activa en SemiBold— baja a
     * ~590dp sobre la captura de 1920, y con los 20dp de padding a cada lado pide
     * un editor de ~630. Mantener 900 dejaba 1366 sin iconos teniendo ~740dp de
     * editor: se recortaba por una medida que ya no existía.
     *
     * Con 650, 1366 y 1440 recuperan la fila completa y sólo el régimen apilado
     * —donde el editor baja de 650— usa las etiquetas cortas, que es para lo que
     * se hicieron.
     */
    val tabIconsMinWidth: Dp = 650.dp
}
