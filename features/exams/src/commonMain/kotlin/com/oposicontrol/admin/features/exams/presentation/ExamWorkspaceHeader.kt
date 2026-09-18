package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.oposicontrol.admin.core.ui.components.GlassSectionSurface
import com.oposicontrol.admin.core.ui.components.SemanticIconContainer
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.core.ui.components.StatusTone
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.GlassTokens
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Sizing
import com.oposicontrol.admin.core.ui.theme.Spacing
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam

/**
 * Quién es este examen.
 *
 * Tres franjas, de más identidad a más detalle: icono y título con su
 * descripción, luego la fila de metadatos que distingue un examen de otro, y
 * abajo los dos datos de ámbito como par etiqueta/valor.
 *
 * **Cada dato, una sola vez** (D12). La comunidad salía dos veces —como píldora y
 * como par etiqueta/valor— y la píldora se ha quitado: el ámbito vive en los
 * pares, con su etiqueta encima. Especialidad y comunidad no son atributos de la
 * misma clase que «Ráfaga» o «45 min»: son la respuesta a *dónde vive* este
 * examen, y «TCAE» y «Nacional» sueltos entre seis píldoras no dicen de qué son.
 *
 * **Examen nuevo.** Título «Nuevo examen», y la fila de metadatos dice los
 * valores con los que el editor está de verdad —«Sin guardar», el tipo, «Sin
 * preguntas vinculadas», «Sin duración»—, porque son los que se guardarían si se
 * pulsara «Crear examen» ahora mismo. Lo que NUNCA dice es un código: el servidor
 * lo genera al crear y predecirlo sería inventarlo ([examHeaderCode]).
 *
 * «Sin duración» y no «Sin límite de tiempo»: ninguna ruta aplica
 * `duracion_minutos` como límite y la app no la recibe, así que un «límite» sería
 * una afirmación de comportamiento sin verificar (D12).
 *
 * Lo que NO lleva son acciones. Publicar, despublicar y archivar cambian el
 * estado del examen y viven en la pestaña que trata de eso. Tampoco lleva `id`,
 * `especialidad_id`, `comunidad_id`, `portada_storage_path`, `sortOrder` ni
 * `deletedAt`: pintarlos sería enseñar implementación o un dato fabricado.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExamWorkspaceHeader(
    state: ExamsState,
    exam: Exam?,
    communities: UiAsync<List<Community>>,
    questionCount: Int?,
    modifier: Modifier = Modifier,
) {
    val glassText = examGlassText(GlassTheme.tokens)
    val isNew = state.selectedExamId == null

    GlassSectionSurface(
        modifier = modifier,
        shape = Radius.xl,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // El mismo contenedor de icono que abre cada franja del panel de
            // escritorio. Es lo que hace que esta cabecera pese como una cabecera
            // en vez de como un párrafo suelto sobre cristal.
            SemanticIconContainer(
                icon = AppIcon.EXAMS,
                role = SemanticRole.INFO,
                size = ExamHeaderSizing.iconChip,
                iconSize = Sizing.iconLarge,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // El título del examen es lo primero que se lee de la pantalla
                // (§36): el estilo más grande de todo el editor y del resumen.
                Text(
                    text = if (isNew) "Nuevo examen" else state.editorTitle.ifBlank { "Examen sin título" },
                    style = MaterialTheme.typography.titleLarge,
                    color = glassText.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        isNew -> "Completa la información básica y la configuración para crear el examen."
                        state.editorDescription.isBlank() -> "Sin descripción."
                        else -> state.editorDescription
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = glassText.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // La fila de metadatos se pinta SIEMPRE, y en un examen nuevo dice el
        // estado real por defecto en vez de callarse: esos valores no están
        // ausentes, son los que se guardarían ahora mismo.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            examHeaderCode(state, exam)?.let { code ->
                Text(
                    text = code,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = glassText.secondary,
                )
            }
            if (exam != null) {
                StatusChip(status = examStatusLabel(exam.status), tone = examStatusTone(exam.status))
            } else {
                StatusChip(status = EXAM_UNSAVED_STATUS, tone = StatusTone.UNKNOWN)
            }
            examHeaderChips(state, exam).forEach { ExamMetaChip(it) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            ExamHeaderFact("Especialidad", specialtyLabel(state.editorSpecialtyId, state.specialties, exam))
            ExamHeaderFact("Comunidad", communityLabel(state.editorCommunityId, communities))
        }
    }
}

/**
 * Par etiqueta/valor del ámbito. Etiqueta pequeña arriba, dato con peso debajo.
 *
 * **El peso depende de si es un dato.** «Cargando…» y «No disponible» son estados
 * del catálogo, no valores del examen, y pintarlos en SemiBold junto a un nombre
 * de especialidad real hace que se lean como la misma clase de cosa. Van en
 * `textSecondary` y sin peso, que es como se lee una nota al margen.
 */
@Composable
private fun ExamHeaderFact(label: String, value: ExamFieldLabel) {
    val tokens = GlassTheme.tokens
    Column(
        modifier = Modifier.width(ExamHeaderSizing.factWidth),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = examGlassText(tokens).secondary,
        )
        val valueStyle = examFieldValueStyle(value.origin, tokens)
        Text(
            text = value.text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = valueStyle.weight,
            color = valueStyle.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Píldora de metadato.
 *
 * Mismos tokens de cristal que `StatusDotChip`, sin el punto y sin el juicio de
 * valor: ninguno de estos datos dice si algo va bien o mal.
 *
 * **El texto se pinta con [examFieldValueStyle], la misma función que la ficha
 * del resumen** (P2-9). La píldora tenía su propia regla —valor O ausencia en
 * `textPrimary`— y por eso «Sin duración» salía como un dato en la cabecera y
 * como una ausencia en el resumen de al lado. Ahora una ausencia legítima («Sin
 * duración», «Sin preguntas vinculadas») y un recuento «sin confirmar» se leen
 * igual aquí que allí: en `textSecondary` y sin peso.
 */
@Composable
private fun ExamMetaChip(label: ExamFieldLabel) {
    val tokens = GlassTheme.tokens
    val valueStyle = examFieldValueStyle(label.origin, tokens)
    Text(
        text = label.text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = valueStyle.weight,
        color = valueStyle.color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(Radius.pill)
            .background(examGlassPillFill(tokens))
            .border(1.dp, tokens.sectionGlassStroke, Radius.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private object ExamHeaderSizing {
    /** Contenedor de icono de cabecera. Mayor que el de una sección: esto abre la pantalla. */
    val iconChip = 44.dp

    /** Ancho de un par etiqueta/valor. Sostiene «Comunidad autónoma» y un nombre largo. */
    val factWidth = 180.dp
}
