package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DangerOutlineButton
import com.oposicontrol.admin.core.ui.components.FormFeedback
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.LabeledField
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.Spacing

/**
 * Identidad: qué especialidad es ésta y si el catálogo público la enseña.
 *
 * Los cuatro campos que el panel edita de verdad —nombre, descripción, orden y
 * `activa`— y nada más. Lo que NO está, está fuera a propósito:
 *
 *  - **Código y slug** los genera el servidor y no hay campo para editarlos.
 *  - **«Visible en catálogo»** no existe como campo propio: sólo hay `activa`, y
 *    pintarlo como dos filas distintas duplicaría el mismo interruptor.
 *  - **Fechas**: el contrato de especialidad no las devuelve; el mapeo las
 *    fabrica con la hora de la lectura. Enseñarlas sería inventarlas.
 *  - **Icono, comunidades y etiquetas**: no hay relación. `comunidad_id` cuelga
 *    del examen y las etiquetas de la pregunta.
 *
 * Los dos avisos de esta pestaña son reales y verificados: renombrar reescribe
 * el slug público que consume la app, y archivar no apaga los exámenes ya
 * publicados.
 */
@Composable
internal fun SpecialtyIdentitySection(
    state: SpecialtiesState,
    onIntent: (SpecialtiesIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = state.selectedSpecialty()
    val previousName = loaded?.name.orEmpty()
    val renaming = previousName.isNotEmpty() &&
        state.editorName.isNotBlank() &&
        state.editorName.trim() != previousName

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        FormSection(
            title = "Identificación",
            description = "Cómo se llama la especialidad y en qué orden aparece.",
            icon = AppIcon.LAYERS,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.field)) {
                LabeledField(
                    label = "Nombre",
                    value = state.editorName,
                    onValueChange = { onIntent(SpecialtiesIntent.UpdateName(it)) },
                    placeholder = "Ej. Auxiliar Administrativo",
                    required = true,
                    supportingText = "Es el único campo obligatorio. El servidor deriva de él el slug público.",
                )
                if (renaming) {
                    // El aviso que la pantalla anterior no daba. Verificado:
                    // `AdminV1Routes` recalcula el slug en cada PATCH que trae
                    // nombre, y ese slug se expone a la app.
                    SpecialtyNotice(
                        tone = SpecialtyNoticeTone.WARNING,
                        title = "Cambiar el nombre reescribe el identificador público",
                        detail = "El slug pasará de «${specialtySlugOf(previousName)}» a " +
                            "«${specialtySlugOf(state.editorName)}». Cualquier enlace que use el anterior " +
                            "deja de resolver.",
                    )
                }
                LabeledField(
                    label = "Descripción",
                    value = state.editorDescription,
                    onValueChange = { onIntent(SpecialtiesIntent.UpdateDescription(it)) },
                    placeholder = "Para qué sirve esta especialidad dentro del catálogo",
                    singleLine = false,
                    minLines = 3,
                    supportingText = "Si se deja en blanco, la lectura la rellena con el slug: " +
                        "conviene escribir algo aunque sea corto.",
                )
                LabeledField(
                    label = "Orden",
                    value = state.editorSortOrder,
                    onValueChange = { onIntent(SpecialtiesIntent.UpdateSortOrder(it)) },
                    placeholder = "0",
                    supportingText = "Número entero. Ordena esta lista de menor a mayor; no lo consume la app.",
                )
            }
        }

        FormSection(
            title = "Visibilidad",
            description = "Si la especialidad aparece en el catálogo público.",
            icon = AppIcon.VIEW,
            iconRole = SemanticRole.INFO,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SpecialtyToggleRow(
                    title = if (state.editorIsActive) "Activa" else "Archivada",
                    // El copy anterior —«Solo las activas quedan visibles para
                    // contenido nuevo»— era falso en las dos mitades: nada impide
                    // crear contenido en una inactiva, y desactivarla no retira sus
                    // exámenes. Esto dice lo que `activa` hace de verdad: filtra
                    // `GET /v1/especialidades`, y sólo eso.
                    description = "Activa aparece en el catálogo público de la app. Archivada desaparece de " +
                        "ese catálogo, y sus exámenes publicados se siguen sirviendo.",
                    checked = state.editorIsActive,
                    onCheckedChange = { onIntent(SpecialtiesIntent.ToggleActive(it)) },
                )
                if (!state.editorIsActive) {
                    SpecialtyNotice(
                        tone = SpecialtyNoticeTone.WARNING,
                        title = "Archivada no es apagada",
                        detail = "`activa = false` sólo filtra el listado de especialidades de la app. " +
                            "Las rutas que sirven exámenes no lo consultan, así que un examen publicado de " +
                            "esta especialidad sigue abriéndose.",
                    )
                }
            }
        }

        // -------------------------------------------------------------------
        // Zona de peligro
        //
        // Se llama ARCHIVAR y no «eliminar» porque no elimina: el backend
        // convierte el DELETE en un `PATCH {activa:false}`, la fila se queda y
        // el listado no filtra por `activa`, así que la especialidad sigue en el
        // catálogo de esta misma pantalla marcada como archivada.
        //
        // Convive con el interruptor de arriba, y la diferencia entre los dos es
        // real y está dicha: sólo el archivado registra auditoría.
        // -------------------------------------------------------------------
        if (state.selectedId != null) {
            FormSection(
                title = "Archivar especialidad",
                description = "La acción destructiva que esta pantalla ofrece de verdad.",
                icon = AppIcon.AUDIT,
                iconRole = SemanticRole.DANGER,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SpecialtyNotice(
                        tone = SpecialtyNoticeTone.DANGER,
                        title = "No hay borrado de especialidades",
                        detail = "Archivar escribe `activa = false`, igual que el interruptor de arriba: " +
                            "la fila permanece y seguirá apareciendo en este catálogo como archivada. " +
                            "La única diferencia con el interruptor es que archivar sí queda registrado " +
                            "en auditoría.",
                    )
                    DangerOutlineButton(
                        text = "Archivar especialidad",
                        enabled = state.editorIsActive,
                        onClick = { onIntent(SpecialtiesIntent.RequestArchive) },
                    )
                    if (!state.editorIsActive) {
                        SpecialtyMetaLine(
                            label = "Estado",
                            value = "Ya está archivada. Para devolverla al catálogo, usa el interruptor de Visibilidad.",
                        )
                    }
                }
            }
        }

        FormFeedback(
            state = state.actionState,
            loadingText = "Escribiendo en especialidades…",
            // Decía «Especialidad sincronizada», que no significa nada y además
            // se enseñaba también al archivar, donde era un éxito falso: no se
            // había sincronizado nada, se había desactivado una fila.
            successText = "Identidad guardada en especialidades.",
        )
    }
}
