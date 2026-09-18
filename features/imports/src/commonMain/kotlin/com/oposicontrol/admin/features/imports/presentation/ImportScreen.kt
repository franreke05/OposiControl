package com.oposicontrol.admin.features.imports.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.AppIconImage
import com.oposicontrol.admin.core.ui.components.ConfirmDialog
import com.oposicontrol.admin.core.ui.components.ErrorStateCard
import com.oposicontrol.admin.core.ui.components.GlassSurface
import com.oposicontrol.admin.core.ui.components.PrimaryButton
import com.oposicontrol.admin.core.ui.components.SecondaryButton
import com.oposicontrol.admin.core.ui.components.humanMessage
import com.oposicontrol.admin.core.ui.theme.GlassTheme
import com.oposicontrol.admin.core.ui.theme.Radius
import com.oposicontrol.admin.core.ui.theme.Spacing

@Composable
fun ImportScreen(viewModel: ImportViewModel) {
    val state by viewModel.state.collectAsState()
    val pickPdf = rememberPdfPickerLauncher { picked -> viewModel.dispatch(ImportIntent.PdfPicked(picked)) }
    ImportContent(
        state = state,
        onIntent = { viewModel.dispatch(it) },
        onPickPdf = pickPdf,
    )
}

/**
 * El workspace de Importación.
 *
 * Entra por estado y no por ViewModel a propósito: así el banco de pruebas visual
 * renderiza la pantalla real contra un `ImportState` fabricado, sin Koin ni Ktor.
 *
 * **El workspace evoluciona.** Sin lote se ve el flujo de entrada —tipo, archivo,
 * historial—. Con un lote abierto, las tarjetas de tipo desaparecen y su sitio lo
 * ocupa la revisión: seguir enseñando tres tarjetas gigantes después de subir el
 * PDF era ruido sobre la única tarea que queda por hacer.
 */
@Composable
internal fun ImportContent(
    state: ImportState,
    onIntent: (ImportIntent) -> Unit,
    onPickPdf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val twoColumns = maxWidth >= ImportSizing.twoColumnMinWidth
        val flowWidth = if (twoColumns) maxWidth - ImportSizing.railWidth - Spacing.md else maxWidth
        val batch = (state.activeBatch as? UiAsync.Success)?.value

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            ImportStageBar(state)

            if (twoColumns) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        ImportFlow(state, onIntent, onPickPdf, flowWidth, detailBesideTable = true)
                    }
                    ImportBatchSummary(
                        state = state,
                        batch = batch,
                        onIntent = onIntent,
                        modifier = Modifier.width(ImportSizing.railWidth),
                    )
                }
            } else {
                ImportFlow(state, onIntent, onPickPdf, flowWidth, detailBesideTable = false)
                ImportBatchSummary(state = state, batch = batch, onIntent = onIntent)
            }
        }
    }

    if (state.showHowItWorks) {
        ImportHowItWorksDialog(onDismiss = { onIntent(ImportIntent.ToggleHowItWorks) })
    }
    if (state.showPublishConfirm) {
        // IM3: el examen está publicado. No se publica nada hasta que el
        // administrador lo confirme aquí; la segunda llamada viaja con su respuesta.
        ConfirmDialog(
            title = "El examen está publicado",
            message = IMPORT_PUBLISHED_EXAM_CONFIRMATION,
            confirmLabel = "Publicar igualmente",
            onConfirm = { onIntent(ImportIntent.ConfirmPublishOnPublishedExam) },
            onDismiss = { onIntent(ImportIntent.DismissPublishConfirm) },
        )
    }
}

/** El aviso que el servidor exige confirmar antes de publicar sobre un examen ya visible. */
const val IMPORT_PUBLISHED_EXAM_CONFIRMATION: String =
    "Las preguntas de este lote se verán al instante en la app, en el examen tal y como está publicado. " +
        "Si prefieres revisarlo antes, despublica el examen y vuelve a publicar el lote después."

@Composable
private fun ImportFlow(
    state: ImportState,
    onIntent: (ImportIntent) -> Unit,
    onPickPdf: () -> Unit,
    availableWidth: Dp,
    detailBesideTable: Boolean,
) {
    when (val active = state.activeBatch) {
        is UiAsync.Loading -> ImportBusyCard("Abriendo el lote…")
        is UiAsync.Error -> ErrorStateCard(
            message = active.error.humanMessage(),
            title = "No se ha podido abrir el lote",
            onRetry = { onIntent(ImportIntent.CloseBatch) },
        )
        is UiAsync.Success -> ImportReviewPane(
            state = state,
            batch = active.value,
            onIntent = onIntent,
            detailBesideTable = detailBesideTable,
        )
        // Sin lote: el flujo de entrada.
        is UiAsync.Idle, is UiAsync.Empty -> {
            ImportTypeSelector(state, onIntent, availableWidth = availableWidth)
            ImportFileStep(state, onIntent, onPickPdf)
            ImportHistoryPane(state, onIntent)
        }
    }
}

@Composable
private fun ImportBusyCard(text: String) {
    val tokens = GlassTheme.tokens
    Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        AppIconImage(AppIcon.CLOCK, size = 14.dp, tint = tokens.textSecondary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.textSecondary)
    }
}

@Composable
private fun ImportHowItWorksDialog(onDismiss: () -> Unit) {
    val tokens = GlassTheme.tokens
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.width(420.dp),
            shape = Radius.lg,
            tint = tokens.surfaceSolid,
            strokeColor = tokens.border,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "¿Cómo funciona?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.textPrimary,
                )
                Text(
                    text = "1. Elige el tipo y el examen destino del lote.\n" +
                        "2. Sube un PDF con texto seleccionable: el servidor lo analiza y crea el lote.\n" +
                        "3. Revisa las filas, corrige lo que haga falta y omite lo que no quieras.\n" +
                        "4. Publica el lote de forma explícita.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                )
                Text(
                    text = IMPORT_NOT_ATOMIC_NOTICE,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End)) {
                    SecondaryButton(text = "Cerrar", onClick = onDismiss)
                    PrimaryButton(text = "Entendido", onClick = onDismiss)
                }
            }
        }
    }
}

private object ImportSizing {
    /**
     * Ancho de CONTENIDO a partir del cual el rail de guía cabe al lado.
     *
     * Medido como en Preguntas: a 1366 el contenido —ventana menos sidebar menos
     * márgenes— ronda los 1080dp, y con el rail de 340 el flujo se queda en ~720,
     * que sigue sosteniendo las tres tarjetas de tipo. Por debajo se apila.
     */
    val twoColumnMinWidth: Dp = 1040.dp

    /** Ancho del rail: cabe la guía de campos sin partir palabras. */
    val railWidth: Dp = 340.dp
}
