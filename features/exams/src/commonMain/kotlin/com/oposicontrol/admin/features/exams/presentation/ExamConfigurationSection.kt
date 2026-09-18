package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ButtonSize
import com.oposicontrol.admin.core.ui.components.FieldLabel
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.GhostButton
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.GlassToggleRow
import com.oposicontrol.admin.core.ui.components.Help
import com.oposicontrol.admin.core.ui.components.HelpTopic
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SectionLink
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Cómo funciona este examen.
 *
 * Dos bloques, con el mismo sistema que Información básica —sección con icono,
 * rejilla común de seis columnas, campos opacos, estados con procedencia—:
 *
 *  - **Tipo de examen.** Tres tarjetas, una por tipo canónico (Ráfaga · Razonado ·
 *    Inmersivo), cada una con lo que su especialidad permite en tres estados
 *    —permitido, no permitido, sin comprobar—, y debajo el veredicto de
 *    compatibilidad del tipo elegido (D10) con el `mensaje_limitacion` una sola
 *    vez. Las capacidades vienen de `capacidades_especialidad`, nunca de un `if`
 *    con el nombre de una especialidad.
 *  - **Parámetros.** Duración (vacía = «Sin duración», DEBT-034), número de
 *    preguntas de sólo lectura con la regla D1, y si es oficial.
 *
 * Todo lo de esta pestaña viaja con «Guardar cambios». El precio y los tickets no
 * están: son de Acceso. Lo que se retiró a propósito —selección automática por
 * criterios, «120 requeridas», opciones de comportamiento sin columna— sigue sin
 * estar, por las mismas razones: no existe.
 */
@Composable
internal fun ExamConfigurationSection(
    state: ExamsState,
    onIntent: (ExamsIntent) -> Unit,
    /** Cómo abrir el banco de Preguntas, o `null` si esta pantalla no lo sabe. */
    onOpenQuestions: (() -> Unit)?,
    onOpenBasicInfo: () -> Unit,
    contentWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val exam = state.selectedExam()
    val choices = examTypeChoices(state)
    val compatibility = examTypeCompatibilityPanel(state)
    val specialty = specialtyLabel(state.editorSpecialtyId, state.specialties, exam)
    val questionCount = examQuestionCountField(state)
    // El campo culpable, si el rechazo del guardado apunta a uno de esta pestaña.
    // `examenes_total_preguntas_check` exige `>= 0` desde PW-2 y, desde M1, ninguna
    // escritura de la API llega a la columna: sólo un negativo escrito por otra vía
    // lo dispararía. Si ocurre, el 23514 genérico no decía qué campo: lo dice el
    // campo mismo, en rojo, y no sólo el pie.
    val questionCountError = examFieldError(state)?.takeIf { it.field == ExamField.QUESTION_COUNT }
    // Tres tarjetas en fila cuando cada una conserva sitio para su descripción en
    // tres líneas; por debajo se apilan. El ancho llega del único
    // BoxWithConstraints de la pantalla: aquí no se vuelve a medir nada.
    val threeUp = contentWidth >= ExamConfigurationSizing.threeUpMinWidth
    val chooseType: (ExamTypeChoice) -> Unit = { choice ->
        onIntent(ExamsIntent.UpdateTypeExam(choice.option.wireValue))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // -------------------------------------------------------------------
        // 1 · Tipo de examen + compatibilidad con la especialidad
        //
        // El veredicto va DENTRO del bloque y justo debajo de las tarjetas: es la
        // explicación de por qué alguna está apagada, y separarlo obligaba a bajar
        // para enterarse del motivo de algo que se ve lo primero.
        // -------------------------------------------------------------------
        FormSection(
            title = "Tipo de examen",
            description = "Cómo se sirve el examen. La especialidad decide qué tipos se pueden elegir.",
            help = Help.tipoExamen,
            icon = AppIcon.LAYERS,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (threeUp) {
                    // Mismo hueco que la rejilla de campos (`Spacing.md`) y tres
                    // pesos iguales: los bordes caen en las columnas 2 y 4 de la
                    // rejilla de seis, como los tres campos de Ámbito.
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        choices.forEach { choice ->
                            ExamTypeCard(
                                choice = choice,
                                onClick = { chooseType(choice) },
                                fillHeight = true,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        choices.forEach { choice ->
                            ExamTypeCard(
                                choice = choice,
                                onClick = { chooseType(choice) },
                                fillHeight = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                ExamTypeCompatibilityBlock(
                    panel = compatibility,
                    specialty = specialty,
                    // Volver a pedir la MISMA especialidad recarga sus capacidades
                    // sin reajustar el tipo (`changed == false` en el ViewModel):
                    // es el reintento que ya existía, no un intent nuevo.
                    onRetry = { onIntent(ExamsIntent.UpdateSpecialtyId(state.editorSpecialtyId)) },
                    onOpenBasicInfo = onOpenBasicInfo,
                )
            }
        }

        // -------------------------------------------------------------------
        // 2 · Parámetros
        // -------------------------------------------------------------------
        FormSection(
            title = "Parámetros",
            description = "Duración, número de preguntas y si corresponde a una convocatoria real.",
            icon = AppIcon.CLOCK,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                ExamFieldRow(
                    {
                        LabeledField(
                            label = "Duración (minutos)",
                            help = Help.duracionExamen,
                            value = state.editorDurationMinutes,
                            onValueChange = { value -> onIntent(ExamsIntent.UpdateDurationMinutes(value)) },
                            placeholder = EXAM_DURATION_PLACEHOLDER,
                            supportingText = examDurationSupport(state.editorDurationMinutes),
                            modifier = it,
                            maxWidth = Dp.Unspecified,
                        )
                    },
                    {
                        ExamReadOnlyField(
                            label = ExamField.QUESTION_COUNT.label,
                            help = Help.numeroPreguntas,
                            value = questionCount,
                            supportingText = questionCountError?.message
                                ?: examQuestionCountSupport(state, questionCount),
                            isError = questionCountError != null,
                            // Las preguntas viven en su propio banco, no en una
                            // pestaña del examen. En un examen nuevo todavía no hay
                            // nada que ver, y si esta pantalla no sabe abrir el
                            // banco no se pinta enlace: mejor ninguno que uno que
                            // no lleva a ninguna parte.
                            linkText = "Ir al banco de Preguntas"
                                .takeIf { state.selectedExamId != null && onOpenQuestions != null },
                            onLink = onOpenQuestions ?: {},
                            modifier = it,
                        )
                    },
                )
                GlassToggleRow(
                    title = "Examen oficial",
                    description = "Corresponde a una convocatoria real ya celebrada, no a un simulacro.",
                    checked = state.editorIsOfficial,
                    onCheckedChange = { onIntent(ExamsIntent.ToggleIsOfficial(it)) },
                )
            }
        }
    }
}

/**
 * Una tarjeta de tipo.
 *
 * Apagada NO es lo mismo que prohibida: la píldora de abajo dice cuál de las tres
 * cosas pasa —permitido, no permitido o sin comprobar— con icono y palabra. Sólo se
 * pulsa un tipo permitido que no sea ya el elegido: con las capacidades sin
 * comprobar, ninguna —tampoco la elegida, que se sigue viendo—, porque el guardado
 * rechaza un tipo cuya validez no ha podido verificar.
 *
 * El elegido se marca con relleno, borde coral y un check coral; el texto sigue
 * en `textPrimary` (el coral como texto no llega a 4,5:1 en claro).
 */
@Composable
private fun ExamTypeCard(
    choice: ExamTypeChoice,
    onClick: () -> Unit,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = GlassTheme.tokens
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val colors = examTypeCardColors(choice.selected, choice.permission, hovered && choice.selectable, tokens)
    val background by animateColorAsState(targetValue = colors.background, animationSpec = tween(140))
    val borderColor by animateColorAsState(targetValue = colors.border, animationSpec = tween(140))
    val option = choice.option

    Column(
        modifier = modifier
            .clip(Radius.card)
            .background(background)
            .border(1.dp, borderColor, Radius.card)
            .then(
                if (choice.selectable) {
                    Modifier
                        .hoverable(interactionSource)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics {
                selected = choice.selected
                contentDescription = examTypeCardDescription(choice)
            }
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // El tinte semántico sólo en un tipo permitido: apagado a neutro es
            // parte del mensaje de «esto no se puede elegir aquí».
            SemanticIconContainer(
                icon = option.icon,
                role = if (choice.permission == ExamTypePermission.ALLOWED) option.role else SemanticRole.NEUTRAL,
                size = Sizing.iconButton,
                iconSize = Sizing.iconLarge,
            )
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.title,
                modifier = Modifier.weight(1f),
            )
            if (choice.selected) {
                AppIconImage(AppIcon.CHECK, size = Sizing.iconLarge, tint = colors.selectedMark)
            }
        }
        Text(
            text = option.description,
            style = MaterialTheme.typography.bodySmall,
            color = colors.description,
        )
        // En fila, las tres tarjetas miden lo mismo y la píldora cae al pie de
        // las tres a la misma altura. Apiladas no hay alto que repartir.
        if (fillHeight) Spacer(modifier = Modifier.weight(1f))
        ExamTypePermissionPill(choice.permission)
    }
}

/** «Permitido», «No permitido» o «Sin comprobar»: marca Y palabra. */
@Composable
private fun ExamTypePermissionPill(permission: ExamTypePermission) {
    val colors = examTypePermissionPillColors(permission, GlassTheme.tokens)
    Row(
        modifier = Modifier
            .clip(Radius.pill)
            .background(colors.background)
            .border(1.dp, colors.border, Radius.pill)
            .padding(horizontal = Spacing.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        AppIconImage(examTypePermissionIcon(permission), size = 12.dp, tint = colors.icon)
        Text(
            text = examTypePermissionLabel(permission),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
        )
    }
}

/**
 * El veredicto de compatibilidad del tipo elegido (D10), con su porqué.
 *
 * Icono y familia salen de [examTypeCompatibilityIcon] —la misma decisión que la
 * tarjeta del resumen—: `CHECK` sólo si está permitido, `WARNING` si no, si el tipo
 * no se reconoce o si no se ha podido comprobar, y neutro mientras no hay veredicto.
 */
@Composable
private fun ExamTypeCompatibilityBlock(
    panel: ExamTypeCompatibilityPanel,
    specialty: ExamFieldLabel,
    onRetry: () -> Unit,
    onOpenBasicInfo: () -> Unit,
) {
    val tokens = GlassTheme.tokens
    val (icon, role) = examTypeCompatibilityIcon(panel.case)
    ExamSolidPanel(
        padding = PaddingValues(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SemanticIconContainer(
                icon = icon,
                role = role,
                size = Sizing.iconButtonSmall,
                iconSize = 16.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = panel.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                panel.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = examCheckDetailColor(panel.status, tokens),
                    )
                }
                panel.note?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.textSecondary,
                    )
                }
                // Sin especialidad la etiqueta ya lo dice; repetir «Especialidad:
                // Sin especialidad» debajo no añade nada.
                if (!panel.asksForSpecialty) {
                    val style = examFieldValueStyle(specialty.origin, tokens)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Text(
                            text = "Especialidad:",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                        )
                        Text(
                            text = specialty.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = style.weight,
                            color = style.color,
                        )
                    }
                }
            }
            when {
                panel.canRetry -> GhostButton(text = "Reintentar", size = ButtonSize.COMPACT, onClick = onRetry)
                panel.asksForSpecialty -> SectionLink(text = "Ir a Información básica", onClick = onOpenBasicInfo)
            }
        }
    }
}

/**
 * Un campo de SÓLO LECTURA con la forma del campo inerte de Información básica:
 * misma etiqueta, superficie hundida sin cursor y el valor pintado según su
 * procedencia ([examFieldValueColor]) —un «Contando preguntas…» o un «Sin contar»
 * no se pintan como un dato—.
 *
 * `LabeledField(readOnly = true)` no sirve aquí: pinta todo valor en
 * `textSecondary` y un vacío como «—», que en el hueco de un número se lee como
 * cero.
 *
 * [isError] lo marca cuando un rechazo del guardado apunta a ESTE campo. Es el
 * caso de «Número de preguntas» con `examenes_total_preguntas_check` (`>= 0` desde
 * PW-2; tras M1 la API no escribe la columna, así que sólo un negativo llegado por
 * otra vía lo dispara): un campo que el administrador no puede corregir
 * escribiendo en él, y precisamente por eso el que más necesita que se le señale.
 */
@Composable
private fun ExamReadOnlyField(
    label: String,
    value: ExamFieldLabel,
    supportingText: String,
    modifier: Modifier = Modifier,
    help: HelpTopic? = null,
    linkText: String? = null,
    onLink: () -> Unit = {},
    isError: Boolean = false,
) {
    val tokens = GlassTheme.tokens
    val colors = examReadOnlyFieldColors(isError, tokens)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        FieldLabel(label = label, help = help)
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = Radius.md,
            tint = colors.fill,
            strokeColor = colors.border,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = Sizing.inputHeight)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = examFieldValueColor(value.origin, tokens),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (linkText != null) {
                    SectionLink(text = linkText, onClick = onLink)
                }
            }
        }
        Text(
            text = supportingText,
            style = MaterialTheme.typography.bodySmall,
            color = colors.support,
        )
    }
}

private object ExamConfigurationSizing {
    /**
     * Ancho de editor a partir del cual las tres tarjetas de tipo caben en fila.
     *
     * **780 y no 640, medido sobre la captura de 1366.** Con 640 el editor de un
     * portátil de 1366 (≈754dp) entraba en tres columnas de ~239dp, y ahí no cabe
     * lo que una tarjeta tiene que decir: la descripción del tipo y su permiso.
     * 780 deja columnas de ~250dp cuando se activa, y por debajo apila — que en una
     * fila estrecha se lee mejor de todos modos.
     */
    val threeUpMinWidth: Dp = 780.dp
}
