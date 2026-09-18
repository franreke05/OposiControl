package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.SectionLink
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam

/**
 * La columna de revisión: ficha, compatibilidad, pendientes e información.
 *
 * Cuatro bloques y no uno, y cada uno responde a una pregunta distinta:
 *
 *  - **Resumen del examen**: ¿qué es este examen, campo a campo? Repite datos de
 *    la cabecera a propósito: allí se leen de un vistazo y en horizontal, aquí se
 *    comparan en forma de tabla. Un valor que no es un dato —cargando, sin
 *    catálogo, vínculo roto— se pinta distinto de un valor y lleva su porqué.
 *  - **Compatibilidad del tipo**: la única regla real de la pantalla (D10).
 *  - **Antes de publicar**: sólo cuando hay algo pendiente. Un bloque permanente
 *    lleno de marcas verdes entrena a no mirarlo. El título lo pidió el usuario;
 *    la honestidad va en el detalle de cada fila, que nunca afirma que el
 *    servidor exija nada para publicar (D9).
 *  - **Información**: de dónde salen las cosas que esta pestaña no deja tocar,
 *    sin deícticos y sin prometer lo que no funciona (D11).
 *
 * **La columna desplaza, y se ve que desplaza.** En 1920×1080 con un examen nuevo
 * la tarjeta «Información» quedaba cortada en el pliegue sin ningún indicio de
 * que hubiera más; ahora lleva la barra de desplazamiento de escritorio del
 * sistema (`DesktopVerticalScrollbar`), que sólo aparece cuando hay algo debajo.
 *
 * Los títulos de estos bloques usan el mismo estilo que los títulos de sección
 * del editor, no uno mayor: el resumen acompaña al formulario, no lo domina (§36).
 */
@Composable
internal fun ExamSummaryPanel(
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
    communities: UiAsync<List<Community>>,
    /** Cómo abrir el banco de Preguntas, o `null` si esta pantalla no lo sabe. */
    onOpenQuestions: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val checks = examChecks(state, exam, questionCount)
    val typeCheck = checks.firstOrNull { it.id == ExamCheckId.TYPE_ALLOWED }
    // Sin lo transitorio (P2-6): «contando preguntas» sólo, no hace aparecer la
    // tarjeta. Aparecía y desaparecía en cada apertura y en cada guardado, y el
    // resto del rail saltaba ~190dp arriba y abajo.
    val problems = examPublishPendingChecks(checks)
    val compatibility = examTypeCompatibility(state)
    val scroll = rememberScrollState()

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                // Hueco para la barra: sin él, el pulgar se dibujaría encima del
                // borde redondeado de las tarjetas.
                .padding(end = ExamWorkspaceSizing.scrollbarGutter),
            verticalArrangement = Arrangement.spacedBy(ExamWorkspaceSizing.columnGap),
        ) {
            // --- 1 · Resumen --------------------------------------------------
            ExamWorkspaceSection(
                icon = AppIcon.ANALYTICS,
                role = SemanticRole.INSIGHT,
                title = "Resumen del examen",
            ) {
                ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    examSummaryFacts(state, exam, questionCount, communities).chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            pair.forEach { fact -> ExamSummaryFactCell(fact, Modifier.weight(1f)) }
                            // Fila impar: la última celda queda sola y no se estira.
                            if (pair.size == 1) Column(modifier = Modifier.weight(1f)) {}
                        }
                    }
                }
            }

            // --- 2 · Compatibilidad del tipo ----------------------------------
            if (typeCheck != null) {
                val (icon, role) = examTypeCompatibilityIcon(compatibility.case)
                ExamWorkspaceSection(
                    icon = icon,
                    role = role,
                    title = "Compatibilidad del tipo",
                ) {
                    ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        ExamCheckRow(typeCheck)
                    }
                }
            }

            // --- 3 · Antes de publicar ----------------------------------------
            if (problems.isNotEmpty()) {
                ExamWorkspaceSection(
                    icon = AppIcon.WARNING,
                    role = SemanticRole.WARNING,
                    title = EXAM_PENDING_TITLE,
                ) {
                    ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        problems.forEach { ExamCheckRow(it) }
                    }
                }
            }

            // --- 4 · Información ----------------------------------------------
            ExamWorkspaceSection(
                icon = AppIcon.INFO,
                role = SemanticRole.INFO,
                title = "Información",
            ) {
                ExamSolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    examInformationLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                        )
                    }
                    // Sin destino cableado no hay enlace: un «Ver preguntas» que no
                    // lleva a ninguna parte es peor que no ofrecerlo.
                    if (onOpenQuestions != null) {
                        SectionLink(text = "Ir al banco de Preguntas", onClick = onOpenQuestions)
                    }
                }
            }
        }
        DesktopVerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * Una celda de la ficha: etiqueta, valor y, si el valor no es un dato, su porqué.
 *
 * Un «Cargando…», un «No disponible» o un «12 · sin confirmar» no se pintan como
 * un valor: van sin peso, en el color de su procedencia, con un icono pequeño que
 * dice qué clase de ausencia es y con el detalle debajo. Antes el único
 * distintivo era el peso tipográfico, y en una rejilla de nueve datos eso no se
 * ve.
 */
@Composable
private fun ExamSummaryFactCell(fact: ExamSummaryFact, modifier: Modifier) {
    val tokens = GlassTheme.tokens
    val value = fact.value
    val valueStyle = examFieldValueStyle(value.origin, tokens)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = fact.label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.textSecondary,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            examFieldOriginIcon(value.origin)?.let { icon ->
                AppIconImage(icon = icon, size = 12.dp, tint = valueStyle.color)
            }
            Text(
                text = value.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = valueStyle.weight,
                color = valueStyle.color,
                // Dos líneas: las etiquetas honestas —«Especialidad no
                // encontrada»— miden el doble que un nombre y con una sola salían
                // cortadas en media columna de 300dp.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        value.detail?.takeUnless { value.isValue }?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Un par etiqueta/valor de la ficha lateral, con la procedencia de su valor. */
internal data class ExamSummaryFact(val label: String, val value: ExamFieldLabel)

/** Atajo para los datos que salen directamente del editor y no de un catálogo. */
private fun fact(label: String, value: String) =
    ExamSummaryFact(label, ExamFieldLabel(value, ExamFieldOrigin.VALUE))

/**
 * Los nueve datos de la ficha, en el orden en que se leen por columnas.
 *
 * Todos salen del editor o del examen guardado, y ninguno se estima. Los que
 * pueden no saberse —código de un examen nuevo, recuento, especialidad, comunidad,
 * duración y año vacíos— llevan su procedencia, para que un hueco no se lea como
 * un valor: «Se genera solo», «Sin duración» y «Sin año» son ausencias legítimas
 * (ABSENT), «Cargando…» es un estado del catálogo y «12 · sin confirmar» es una
 * cifra provisional.
 */
internal fun examSummaryFacts(
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
    communities: UiAsync<List<Community>>,
): List<ExamSummaryFact> = listOf(
    fact("Estado", exam?.let { examStatusLabel(it.status) } ?: EXAM_UNSAVED_STATUS),
    fact("Tipo", ExamTypeOption.labelOf(state.editorTypeExam)),
    ExamSummaryFact("Código", examSummaryCode(state, exam)),
    ExamSummaryFact("Preguntas", linkedQuestionCountLabel(state.questions, provisionalQuestionCount(state, exam))),
    ExamSummaryFact("Especialidad", specialtyLabel(state.editorSpecialtyId, state.specialties, exam)),
    ExamSummaryFact("Duración", examDurationLabel(state)),
    ExamSummaryFact("Comunidad", communityLabel(state.editorCommunityId, communities)),
    ExamSummaryFact("Año", examYearLabel(state)),
    fact("Oficial", if (state.editorIsOfficial) "Sí" else "No"),
)

/**
 * La misma información cuando no hay una tercera columna donde ponerla: UNA
 * línea de alto fijo, y el detalle en una capa que se abre encima.
 *
 * **Por qué ya no se despliega** (P0-2, P1-1). La franja pintaba todas las
 * comprobaciones pendientes, una fila por cada una, encima del marco del editor,
 * y el marco se quedaba con lo que sobrara. En 1366×768 un examen nuevo tiene
 * cuatro pendientes y el formulario medía 0px —«Crear examen» asomaba 13 de sus
 * 40px—; en uno existente, la primera tecla añadía «Cambios sin guardar» y la
 * franja pasaba de 40 a 130px, 101px menos de formulario. Justo lo contrario de
 * lo que la barra de guardar fija existe para evitar.
 *
 * Ahora mide siempre [ExamWorkspaceSizing.summaryStripHeight]: un punto con el
 * peor estado, una frase que nombra lo pendiente —«Antes de publicar: título,
 * especialidad, tipo y preguntas.»— y «Ver detalle». La franja entera es el
 * objetivo de clic. El detalle son las mismas filas que la tarjeta del resumen,
 * en un menú anclado debajo: se superpone, no empuja. Mientras sólo se está
 * contando o comprobando, la línea lo dice y no ofrece detalle (P2-6).
 *
 * Por qué hace falta en 1600 y no sólo en 1366: con 1600 de ventana, catálogo
 * (330) + resumen (300) + dos huecos dejan el editor en 622dp, por debajo de su
 * suelo de 700 (`ExamWorkspaceSizing.editorMinWidth`: la fila de pestañas no
 * cabe en menos), así que el resumen se pliega a esta franja también en WIDE.
 */
@Composable
internal fun ExamSummaryStrip(
    state: ExamsState,
    exam: Exam?,
    questionCount: Int?,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val glassText = examGlassText(tokens)
    val line = examSummaryStripLine(examChecks(state, exam, questionCount))
    val hasDetail = line.details.isNotEmpty()
    var open by remember { mutableStateOf(false) }
    var stripWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Box(modifier = modifier.fillMaxWidth().testTag(EXAM_SUMMARY_STRIP_TAG)) {
        GlassSectionSurface(
            modifier = Modifier
                .fillMaxWidth()
                .height(ExamWorkspaceSizing.summaryStripHeight)
                .onSizeChanged { stripWidth = it.width },
            shape = Radius.xl,
            contentPadding = PaddingValues(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .then(
                        if (hasDetail) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClickLabel = EXAM_STRIP_DETAIL_LABEL) { open = !open }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(Radius.pill)
                        .background(line.status?.let { examCheckDotColor(it, tokens) } ?: tokens.textSecondary),
                )
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasDetail) glassText.primary else glassText.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasDetail) {
                    Text(
                        text = EXAM_STRIP_DETAIL_LABEL,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = glassText.primary,
                    )
                    AppIconImage(
                        icon = AppIcon.CHEVRON_DOWN,
                        size = 14.dp,
                        tint = glassText.secondary,
                        modifier = Modifier.rotate(if (open) 180f else 0f),
                    )
                }
            }
        }
        // La capa del detalle. Un menú anclado a la franja: se abre encima del
        // formulario sin moverlo, se cierra al pulsar fuera o con Escape, y su
        // fondo es la misma superficie sólida de la tarjeta del resumen, así que
        // sus filas se leen exactamente igual que allí (`ExamCheckContrastTest`).
        DropdownMenu(
            expanded = open && hasDetail,
            onDismissRequest = { open = false },
            modifier = Modifier.width(with(density) { stripWidth.toDp() }),
            containerColor = examSummaryDetailContainer(tokens),
            border = BorderStroke(1.dp, tokens.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    text = EXAM_PENDING_TITLE,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.textPrimary,
                )
                line.details.forEach { check -> ExamCheckRow(check) }
            }
        }
    }
}

/** La acción de la franja que abre el detalle. */
internal const val EXAM_STRIP_DETAIL_LABEL: String = "Ver detalle"

/**
 * El fondo de la capa del detalle de la franja: `surfaceSolid`, el mismo que la
 * tarjeta «Antes de publicar» del resumen. Es una función para que el test de
 * contraste mida sus filas sobre el fondo que de verdad llevan.
 */
internal fun examSummaryDetailContainer(tokens: GlassTokens): Color = tokens.surfaceSolid

/** Una comprobación: la misma fila en el resumen, en la franja y en Publicación. */
@Composable
internal fun ExamCheckRow(check: ExamCheck) {
    ExamStatusRow(status = check.status, label = check.label, detail = check.detail)
}

/**
 * Punto de estado + etiqueta + detalle. Es la fila de [ExamCheckRow] sin exigir un
 * [ExamCheckId]: la usan también los avisos y el resultado de Publicación, que no
 * son comprobaciones. Mismos colores, ya medidos en `ExamCheckContrastTest`.
 */
@Composable
internal fun ExamStatusRow(status: ExamCheckStatus, label: String, detail: String?) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.Top,
    ) {
        // Punto de color + palabra. El color nunca va solo: el estado se lee
        // también en el texto de la etiqueta y del detalle, que es lo que hace
        // que la columna funcione para quien no separa rojo de verde.
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(7.dp)
                .clip(Radius.pill)
                .background(examCheckDotColor(status, tokens)),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = tokens.textPrimary,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = examCheckDetailColor(status, tokens),
                )
            }
        }
    }
}

/**
 * Icono y familia de la tarjeta de compatibilidad (§5, D10).
 *
 * `CHECK` sólo si el tipo está permitido; `WARNING` si no lo está, si el tipo no
 * se reconoce o si las capacidades no se han podido consultar. Sin especialidad o
 * mientras se comprueba no hay veredicto que dar, y el icono lo dice: neutro.
 */
internal fun examTypeCompatibilityIcon(case: ExamTypeCompatibilityCase): Pair<AppIcon, SemanticRole> = when (case) {
    ExamTypeCompatibilityCase.ALLOWED -> AppIcon.CHECK to SemanticRole.SUCCESS
    ExamTypeCompatibilityCase.NOT_ALLOWED,
    ExamTypeCompatibilityCase.UNKNOWN_TYPE,
    ExamTypeCompatibilityCase.UNAVAILABLE,
    -> AppIcon.WARNING to SemanticRole.WARNING
    ExamTypeCompatibilityCase.NO_SPECIALTY,
    ExamTypeCompatibilityCase.CHECKING,
    -> AppIcon.INFO to SemanticRole.NEUTRAL
}

/** El icono pequeño que acompaña a un valor que no es un dato. Un valor no lleva ninguno. */
internal fun examFieldOriginIcon(origin: ExamFieldOrigin): AppIcon? = when (origin) {
    ExamFieldOrigin.VALUE, ExamFieldOrigin.ABSENT -> null
    ExamFieldOrigin.PENDING -> AppIcon.CLOCK
    ExamFieldOrigin.UNAVAILABLE, ExamFieldOrigin.UNRESOLVED -> AppIcon.WARNING
}

// ---------------------------------------------------------------------------
// Colores del resumen.
//
// Funciones y no un `when` dentro del composable para que `ExamCheckContrastTest`
// mida EXACTAMENTE lo que se pinta, con la paleta viva de los dos temas. Un test
// de pares de tokens no protege esto: si alguien vuelve a pintar el detalle en el
// color del estado, ningún par de tokens cambia.
// ---------------------------------------------------------------------------

/**
 * El color de un valor según su procedencia: el dato en `textPrimary`, un vínculo
 * roto en `danger` —eso sí es un problema del examen— y el resto (ausencia
 * legítima, catálogo caído, cargando) en `textSecondary`, como una nota al margen.
 *
 * Lo usan la ficha, la cabecera y los campos inertes de los selectores, que es
 * lo que hace que un mismo estado se lea igual en los tres sitios.
 */
internal fun examFieldValueColor(origin: ExamFieldOrigin, tokens: GlassTokens): Color = when (origin) {
    ExamFieldOrigin.VALUE -> tokens.textPrimary
    ExamFieldOrigin.UNRESOLVED -> tokens.danger
    ExamFieldOrigin.ABSENT,
    ExamFieldOrigin.UNAVAILABLE,
    ExamFieldOrigin.PENDING,
    -> tokens.textSecondary
}

/** Cómo se pinta un valor derivado: su color y su peso. */
internal data class ExamFieldValueStyle(val color: Color, val weight: FontWeight)

/**
 * El tratamiento COMPLETO de un valor según su procedencia —color y peso—, y el
 * mismo en todos los sitios donde se pinta ese valor: la ficha del resumen, el
 * par etiqueta/valor de la cabecera y las píldoras de metadatos de la cabecera.
 *
 * Existe porque el mismo dato llevaba dos tratamientos (P2-9): «Sin duración»
 * salía en `textPrimary` en la píldora de la cabecera y en `textSecondary` en el
 * resumen. La píldora tenía su propia regla —«si es valor o ausencia, texto
 * principal»— y el resumen la de la procedencia. Una ausencia legítima no es un
 * dato y no se pinta como uno, esté donde esté.
 */
internal fun examFieldValueStyle(origin: ExamFieldOrigin, tokens: GlassTokens): ExamFieldValueStyle =
    ExamFieldValueStyle(
        color = examFieldValueColor(origin, tokens),
        weight = if (origin == ExamFieldOrigin.VALUE) FontWeight.SemiBold else FontWeight.Normal,
    )

/**
 * El punto de 7dp: el que porta el estado en color.
 *
 * Es un elemento de interfaz, así que pide 3:1 sobre `surfaceSolid`. El más
 * justo es el verde de éxito del tema claro, con 3,32:1.
 */
internal fun examCheckDotColor(status: ExamCheckStatus, tokens: GlassTokens): Color = when (status) {
    ExamCheckStatus.OK -> tokens.success
    ExamCheckStatus.BLOCKING -> tokens.danger
    ExamCheckStatus.WARNING -> tokens.warning
    ExamCheckStatus.UNKNOWN -> tokens.textSecondary
}

/**
 * El texto de detalle: `textSecondary`, sea cual sea el estado.
 *
 * Se pintaba en el color del estado. Para un aviso eso era `warning` sobre
 * `surfaceSolid`: 4,40:1 en el tema claro con `bodySmall` de 13sp, que es texto
 * normal y pide 4,5:1. El detalle explica; el estado ya lo dicen el punto de
 * color y la etiqueta en SemiBold. En `textSecondary` da 6,24:1 en claro y
 * 5,66:1 en oscuro. `danger` sí llegaba (5,34:1 / 4,60:1), pero se unifica: el
 * detalle es una sola clase de texto, y el color de estado vive en el punto.
 *
 * El `when` es exhaustivo a propósito: quien añada un estado tiene que decidir
 * aquí su color, y el test lo medirá sin que nadie se acuerde de añadirlo.
 */
internal fun examCheckDetailColor(status: ExamCheckStatus, tokens: GlassTokens): Color = when (status) {
    ExamCheckStatus.OK,
    ExamCheckStatus.BLOCKING,
    ExamCheckStatus.WARNING,
    ExamCheckStatus.UNKNOWN,
    -> tokens.textSecondary
}
