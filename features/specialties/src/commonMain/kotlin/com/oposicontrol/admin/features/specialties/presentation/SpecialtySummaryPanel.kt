package com.oposicontrol.admin.features.specialties.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.DesktopVerticalScrollbar
import com.oposicontrol.admin.core.ui.components.SemanticRole
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Spacing

/** Etiqueta de prueba del rail. Sólo semántica: no se pinta. */
internal const val SPECIALTY_RAIL_TAG: String = "specialty-rail"

/**
 * La columna de revisión: vista rápida, pendientes e información.
 *
 * Tres bloques y no uno, y cada uno responde a una pregunta distinta:
 *
 *  - **Vista rápida**: qué es esta especialidad ahora mismo, en cuatro datos.
 *    Repite lo que el formulario ya enseña, y a propósito: aquí se comparan en
 *    forma de tabla, allí se editan. Un valor que no es un dato —cargando, no
 *    disponible— se pinta distinto de un valor y lleva su porqué.
 *  - **Antes de guardar**: sólo cuando hay algo pendiente. Un bloque permanente
 *    lleno de marcas verdes entrena a no mirarlo.
 *  - **Información**: qué hace de verdad lo que esta pantalla no deja ver, sin
 *    prometer lo que no funciona.
 *
 * **Sin `fillMaxHeight()`.** No hay nada anclado al fondo de esta columna, y
 * estirar el cristal hasta el borde inferior sólo dejaría dos tercios vacíos en
 * cada captura. Mide lo que miden sus bloques, y desplaza —con su barra— cuando
 * no caben.
 *
 * **Cuando la columna se pliega no hay franja de repuesto, y no hace falta.**
 * Exámenes necesita `ExamSummaryStrip` porque su rail lleva datos que no están
 * en ninguna pestaña. Aquí las nueve comprobaciones de [specialtyPendingChecks]
 * tienen todas su gemela dentro del formulario: los tres bloqueos los dice la
 * barra de guardado (y el campo obligatorio su asterisco), el slug reescrito y
 * la especialidad archivada tienen su aviso en Identidad, los tipos sin
 * configurar y el mensaje de limitación lo tienen en Tipos de examen, los tipos
 * apagados con exámenes lo tienen en su tarjeta y en Impacto, y las dos
 * escrituras pendientes las enumera la propia barra. Plegar el rail a 1600dp
 * —donde el editor gana 300dp— no esconde ninguna advertencia.
 */
@Composable
internal fun SpecialtySummaryPanel(
    state: SpecialtiesState,
    breakdown: SpecialtyExamBreakdown?,
    modifier: Modifier = Modifier,
    /**
     * Si esta columna desplaza por dentro.
     *
     * `true` cuando es la tercera columna del master-detail: tiene un alto
     * acotado y su contenido puede no caber.
     *
     * `false` cuando las columnas se apilan y el rail pasa a ser un bloque más
     * de la página. Ahí el alto es infinito porque la página es la que desplaza,
     * y un `verticalScroll` dentro de otro `verticalScroll` no es un defecto
     * estético: Compose lanza `IllegalStateException` al medirlo.
     */
    scrolls: Boolean = true,
) {
    val tokens = GlassTheme.tokens
    val facts = specialtyQuickFacts(state, breakdown)
    val checks = specialtyPendingChecks(state, breakdown)
    val scroll = rememberScrollState()

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrolls) Modifier.verticalScroll(scroll) else Modifier)
                .padding(end = if (scrolls) SpecialtiesSizing.scrollbarGutter else 0.dp),
            verticalArrangement = Arrangement.spacedBy(SpecialtiesSizing.columnGap),
        ) {
            // --- 1 · Vista rápida --------------------------------------------
            SpecialtiesSection(
                icon = AppIcon.ANALYTICS,
                role = SemanticRole.INSIGHT,
                title = "Vista rápida",
            ) {
                SpecialtySolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    facts.chunked(2).forEach { pair ->
                        val cells = buildList<@Composable (Modifier) -> Unit> {
                            pair.forEach { fact ->
                                add { cellModifier -> SpecialtyFactCell(fact, cellModifier) }
                            }
                            // Fila impar: la celda que falta se reserva vacía en
                            // vez de dejar que la última se estire al doble.
                            if (pair.size == 1) add { cellModifier -> Box(modifier = cellModifier) }
                        }
                        SpecialtyEqualRow(content = cells, gap = Spacing.sm, matchHeights = true)
                    }
                }
            }

            // --- 2 · Antes de guardar ----------------------------------------
            if (checks.isNotEmpty()) {
                SpecialtiesSection(
                    icon = AppIcon.WARNING,
                    role = SemanticRole.WARNING,
                    title = SPECIALTY_PENDING_TITLE,
                ) {
                    SpecialtySolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        checks.forEach { SpecialtyCheckRow(it) }
                    }
                }
            }

            // --- 3 · Información ---------------------------------------------
            SpecialtiesSection(
                icon = AppIcon.INFO,
                role = SemanticRole.INFO,
                title = "Información",
            ) {
                SpecialtySolidPanel(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    specialtyInformationLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                        )
                    }
                }
            }
        }
        if (scrolls) {
            DesktopVerticalScrollbar(
                scrollState = scroll,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/**
 * Una celda de la vista rápida: etiqueta, valor y, si el valor no es un dato, su
 * porqué.
 *
 * Un «Contando…» o un «No disponible» no se pintan como un valor: van sin peso,
 * en el color de su procedencia, con un icono pequeño que dice qué clase de
 * ausencia es y con el detalle debajo.
 */
@Composable
private fun SpecialtyFactCell(fact: SpecialtyFact, modifier: Modifier = Modifier) {
    val tokens = GlassTheme.tokens
    val style = specialtyFactStyle(fact.origin, tokens)
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
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            specialtyFactOriginIcon(fact.origin)?.let { icon ->
                AppIconImage(icon = icon, size = 12.dp, tint = style.color)
            }
            Text(
                text = fact.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (style.emphasised) FontWeight.SemiBold else FontWeight.Normal,
                color = style.color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        fact.detail?.let { detail ->
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
