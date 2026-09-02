package com.oposicontrol.admin.features.rankings.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oposicontrol.admin.core.ui.components.AsyncStatePane
import com.oposicontrol.admin.core.ui.components.FormSection
import com.oposicontrol.admin.core.ui.components.StatusChip
import com.oposicontrol.admin.domain.model.RankingSection
import com.oposicontrol.admin.domain.model.UserStanding

/**
 * Ficha competitiva de un opositor: solo lectura.
 *
 * Regla que gobierna el panel entero: **un hueco se dice, no se rellena.** Un
 * usuario sin estado competitivo todavía no compite (NO_DATA); una sección que
 * no se pudo leer es otra cosa (NO_DISPONIBLE). Poner ceros haría creer que
 * alguien tiene nota 0 o va el primero cuando lo que ocurre es que no hay dato,
 * y sobre eso se acabarían tomando decisiones.
 */
@Composable
fun UserStandingPanel(state: RankingsState) {
    FormSection(
        title = "Ficha competitiva",
        description = "Liga, ventana de cálculo, nota media, evolución y posiciones. Solo lectura.",
    ) {
        AsyncStatePane(
            state.standing,
            emptyMessage = "Selecciona un opositor para ver su posición.",
        ) { standing ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                IdentityBlock(standing)
                CompetitiveBlock(standing)
            }
        }
    }
}

@Composable
private fun IdentityBlock(standing: UserStanding) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Field("Opositor", standing.label)
        Field("ID", standing.userId)
        if (standing.isUnavailable(RankingSection.PERFIL)) Unavailable("el perfil")
    }
}

@Composable
private fun CompetitiveBlock(standing: UserStanding) {
    val competitive = standing.competitive
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            standing.isUnavailable(RankingSection.COMPETITIVO) -> Unavailable("el estado competitivo")

            competitive == null -> NoData(
                "El opositor todavía no compite: el estado competitivo se crea al alcanzar " +
                    "el mínimo de preguntas de la primera liga.",
            )

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(standing.league?.code ?: competitive.leagueCode ?: "sin-liga")
                    if (standing.isUnavailable(RankingSection.CATALOGO_LIGAS)) StatusChip("liga-incompleta")
                }
                Field("Liga", standing.league?.name ?: competitive.leagueName)
                // La ventana no es un detalle: dice sobre cuántas preguntas está
                // calculada la nota, y sin ella la cifra no se puede interpretar.
                Field("Ventana de cálculo", formatWindow(competitive.calculationWindow))
                Field("Preguntas válidas", formatCount(competitive.rankingEligibleQuestions))
                Field("Nota media", formatScoreOrNoData(competitive.currentAverage))
                Field("Nota de referencia", formatScoreOrNoData(competitive.evolutionReference))
                // Signo siempre explícito: "0,8" y "-0,8" se leen igual de rápido
                // y significan lo contrario.
                Field("Evolución", formatSignedScoreOrNoData(competitive.evolution))
                // Sin este contador no se sabe si la evolución es de hace dos
                // preguntas o lleva congelada media liga.
                Field(
                    "Preguntas desde la última evolución",
                    formatCount(competitive.questionsSinceLastEvolution),
                )
                Field("Posición en liga", formatPosition(competitive.leagueRank))
                Field("Posición global", formatPosition(competitive.globalRank))
                Field("Percentil global", formatPercentile(competitive.globalPercentile))
                // Etiquetado obligatorio: es una comparativa interna de la app, no
                // una probabilidad de obtener plaza.
                Text(
                    text = PERCENTILE_DISCLAIMER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field("Actualizado", competitive.updatedAt)

                standing.league?.let { league ->
                    leagueWindowWarning(league)?.let { warning ->
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(230.dp),
        )
        Text(
            // Un campo vacío se marca; dejarlo en blanco se confunde con un valor
            // legítimamente vacío.
            text = value?.takeIf { it.isNotBlank() } ?: NO_DATA_TEXT,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (value.isNullOrBlank()) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

@Composable
private fun NoData(reason: String) {
    Text(
        text = "$NO_DATA_TEXT — $reason",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Unavailable(what: String) {
    Text(
        text = "$NOT_AVAILABLE_TEXT — no se pudo leer $what.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
