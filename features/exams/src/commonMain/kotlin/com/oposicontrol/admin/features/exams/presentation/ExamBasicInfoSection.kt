package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Quién es este examen: identidad, dónde vive y qué portada le corresponde.
 *
 * Tres bloques, cada uno con su icono, su título y su descripción, y ninguno de
 * adorno. Los campos son exactamente los que la tabla `examenes` guarda y el
 * editor sabe escribir:
 *
 *  - **Código** es humano (`codigo_examen`), no el identificador interno. Si se
 *    deja en blanco al crear, el servidor genera uno; si se deja en blanco al
 *    editar, se conserva el que ya tenía. Las dos cosas se dicen en el campo,
 *    porque son dos comportamientos distintos del mismo hueco vacío.
 *  - **Comunidad** es obligatoria (V1): todo examen tiene una, no hay «Nacional» sin comunidad. El identificador
 *    de comunidad no se enseña jamás, sólo su nombre — y cuando el catálogo no
 *    se ha podido leer, tampoco se inventa uno (ver [ExamCommunityPicker]).
 *  - **Portada** es una previsualización automática (`ExamCoverPreview`): se deduce del tipo y de
 *    la comunidad del examen y no se sube, ni se elige, ni se guarda.
 *
 * Lo que NO está, y no por olvido:
 *
 *  - `publicar_desde`. Nunca llegaba a persistirse: no existe la publicación
 *    programada, y un campo que acepta una fecha que nadie va a respetar es peor
 *    que no tenerlo.
 *  - Un contador de caracteres en la descripción. **No hay límite real** (D7):
 *    ni la columna ni el backend imponen uno, así que un «0/500» sería una
 *    restricción inventada que además se leería como oficial.
 *
 * **Por qué sigue usando [FormSection] y no el contenedor de cristal.** El
 * contenido de una pestaña ya vive dentro del `GlassSectionSurface` del marco del
 * editor. Otro contenedor de cristal apilaría dos niveles 1 y el formulario
 * acabaría flotando sobre dos translúcidos superpuestos; `FormSection` resuelve a
 * superficie opaca en escritorio, que es lo que la regla pide para todo lo que
 * lleva un campo (un input siempre más opaco que el cristal que lo contiene). El
 * icono de cabecera se añade a `FormSection` como parámetro opcional, de modo que
 * Configuración —que usa el mismo bloque sin icono— no cambia.
 */
@Composable
internal fun ExamBasicInfoSection(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val exam = state.selectedExam()
    val isNew = state.selectedExamId == null
    val retry = { onIntent(ExamsIntent.RefreshAll) }
    // El campo culpable, si el que señala el rechazo (o el propio editor) vive en
    // esta pestaña. Una línea al pie del formulario no dice QUÉ campo falla.
    val codeError = examFieldError(state)?.takeIf { it.field == ExamField.CODE }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // -------------------------------------------------------------------
        // 1 · Identificación
        // -------------------------------------------------------------------
        FormSection(
            title = "Identificación",
            description = "Define el título y la descripción principal del examen.",
            icon = AppIcon.EXAMS,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                ExamFieldRow(
                    {
                        LabeledField(
                            label = "Título",
                            required = true,
                            value = state.editorTitle,
                            onValueChange = { onIntent(ExamsIntent.UpdateTitle(it)) },
                            placeholder = "Ej. Simulacro Andalucía 2026",
                            modifier = it,
                            maxWidth = Dp.Unspecified,
                        )
                    },
                    {
                        LabeledField(
                            label = ExamField.CODE.label,
                            help = Help.codigoExamen,
                            value = state.editorCode,
                            onValueChange = { value -> onIntent(ExamsIntent.UpdateCode(value)) },
                            placeholder = examCodePlaceholder(state, exam),
                            // Con un código repetido, el apoyo dice con qué examen
                            // choca: donde se está escribiendo, y no sólo en el
                            // resumen, que en 1366 está plegado en la franja. El
                            // mensaje sale de `examFieldError`, que ya reúne el
                            // choque que detecta el editor y el rechazo del
                            // servidor (`EXAM_CODE_DUPLICATE`) en un solo sitio.
                            supportingText = codeError?.message ?: examCodeSupport(state),
                            isError = codeError != null,
                            modifier = it,
                            maxWidth = Dp.Unspecified,
                        )
                    },
                    spans = ExamBasicInfoSizing.identitySpans,
                )
                LabeledField(
                    label = "Descripción",
                    value = state.editorDescription,
                    onValueChange = { onIntent(ExamsIntent.UpdateDescription(it)) },
                    placeholder = "Explicación breve de qué cubre este examen",
                    singleLine = false,
                    minLines = ExamBasicInfoSizing.descriptionMinLines,
                    // A lo ancho de la fila de arriba: la descripción es el texto
                    // más largo del bloque y cortarla a 680dp dejaba su borde
                    // derecho desalineado con el del código.
                    maxWidth = Dp.Unspecified,
                )
            }
        }

        // -------------------------------------------------------------------
        // 2 · Ámbito y clasificación
        //
        // Los tres campos que sitúan el examen, a ancho igual. La especialidad
        // gobierna además qué tipos se pueden elegir en Configuración, y por eso
        // va la primera.
        //
        // Los dos selectores resuelven sus propios estados de carga y de fallo
        // DENTRO de la rejilla, cada uno en el hueco de su campo: un error de
        // catálogo no puede desmontar la fila ni convertirse en un párrafo rojo a
        // todo lo ancho, y tampoco puede esconderse.
        // -------------------------------------------------------------------
        FormSection(
            title = "Ámbito y clasificación",
            description = "Define la especialidad, el ámbito geográfico y el año del examen.",
            icon = AppIcon.LOCATION,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                ExamFieldRow(
                    {
                        ExamSpecialtyPicker(
                            state = state,
                            exam = exam,
                            onSelected = { value -> onIntent(ExamsIntent.UpdateSpecialtyId(value)) },
                            onRetry = retry,
                            modifier = it,
                        )
                    },
                    {
                        ExamCommunityPicker(
                            communities = state.communities,
                            selectedId = state.editorCommunityId,
                            isNew = isNew,
                            onSelected = { value -> onIntent(ExamsIntent.UpdateCommunityId(value)) },
                            isError = examFieldError(state)?.field == ExamField.COMMUNITY,
                            onRetry = retry,
                            modifier = it,
                        )
                    },
                    {
                        LabeledField(
                            label = "Año",
                            value = state.editorYear,
                            onValueChange = { value -> onIntent(ExamsIntent.UpdateYear(value)) },
                            // «Sin año», y no un «2026» gris: un placeholder con forma
                            // de valor se leía como el año del examen mientras la ficha
                            // del resumen decía «Sin año» — la misma pantalla
                            // contradiciéndose. Es el mismo texto que la ficha (D6).
                            placeholder = EXAM_NO_YEAR,
                            modifier = it,
                            maxWidth = Dp.Unspecified,
                        )
                    },
                    spans = ExamBasicInfoSizing.scopeSpans,
                )
                // El porqué de un selector sin catálogo, debajo de la fila y no
                // dentro de su celda: las tres celdas miden lo mismo (P2-1).
                ExamPickerNotices(
                    listOfNotNull(
                        specialtyPickerNotice(state.specialties, isNew = isNew),
                        communityPickerNotice(state.editorCommunityId, state.communities, isNew = isNew),
                    ),
                )
            }
        }

        // -------------------------------------------------------------------
        // 3 · Portada
        // -------------------------------------------------------------------
        FormSection(
            title = "Portada",
            description = EXAM_COVER_SUBTITLE,
            icon = AppIcon.ASSETS,
            iconRole = SemanticRole.INFO,
        ) {
            ExamCoverPreview(state = state)
        }
    }
}

private object ExamBasicInfoSizing {
    /**
     * Título 4 columnas : código 2, sobre la rejilla común de seis (P2-2).
     *
     * No es un número estético: el título es el texto que identifica el examen y
     * los reales miden 30-50 caracteres («TCAE · Simulacro Andalucía convocatoria
     * 2026»); el código humano mide 12 («EX-2026-0001»). A partes iguales el
     * título se elidía y el código flotaba en media fila vacía. Y en columnas de
     * rejilla, no en pesos: así el borde del código coincide con el del año de la
     * fila de Ámbito.
     */
    val identitySpans: List<Int> = listOf(4, 2)

    /** Especialidad 2 + Comunidad 2 + Año 2: los tres a igual ancho y en la misma rejilla. */
    val scopeSpans: List<Int> = listOf(2, 2, 2)

    /** Tres líneas: lo justo para que el hueco se lea como un área de texto y no como un input. */
    const val descriptionMinLines: Int = 3
}
