package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam

/**
 * El estado de PARSEO de una fila, agrupado para filtrar. La anulación por el tribunal
 * NO es una categoría de este eje: es un dato de la pregunta en el examen y va aparte
 * (`estado ok + anulada` es válido).
 */
enum class ReviewFilter { ALL, LISTA, ADVERTENCIA, OMITIDA, IMPORTADA, ERROR, DUPLICADA }

data class ImportState(
    val exams: UiAsync<List<Exam>> = UiAsync.Loading,
    val batches: UiAsync<List<ContentImportBatch>> = UiAsync.Loading,

    val selectedType: ContentExamType = ContentExamType.RAFAGA,
    val selectedExamId: String? = null,
    val pickedPdfPath: String = "",
    val pickedPdfName: String = "",
    val pickedPdfSizeBytes: Long = 0,

    val activeBatch: UiAsync<ContentImportBatch> = UiAsync.Idle,
    val reviewFilter: ReviewFilter = ReviewFilter.ALL,
    val reviewSearch: String = "",
    val selectedStagingId: String? = null,
    val editDraft: ContentImportStagingItem? = null,

    val actionState: UiAsync<String> = UiAsync.Idle,
    val showHowItWorks: Boolean = false,
    /** El servidor pidió confirmar: el examen está publicado y las preguntas se verán al instante. */
    val showPublishConfirm: Boolean = false,
) {
    /** Sobre la lista COMPLETA (ráfagas incluidas, KI0-1): filtrar antes dejaba el selector vacío para Ráfaga. */
    val examsForSelectedType: List<Exam>
        get() = (exams as? UiAsync.Success)?.value.orEmpty().filter { it.typeExam == selectedType.apiValue }
}

/** Filas del lote activo que además pasan búsqueda/filtro de revisión. */
fun ImportState.visibleStaging(): List<ContentImportStagingItem> {
    val staging = (activeBatch as? UiAsync.Success)?.value?.staging.orEmpty()
    val query = reviewSearch.trim()
    return staging
        .filter { item ->
            reviewFilter == ReviewFilter.ALL || stagingTone(item) == reviewFilter
        }
        .filter { item ->
            query.isBlank() || item.title.contains(query, ignoreCase = true) || item.id.contains(query, ignoreCase = true)
        }
        .sortedBy { it.position }
}

/** El estado de parseo tal y como lo manda la base, comparado sin mayúsculas. */
private fun ContentImportStagingItem.isStatus(vararg values: String): Boolean =
    values.any { parseStatus.trim().equals(it, ignoreCase = true) }

/**
 * ¿La fila va vinculada a una pregunta que ya existe?
 *
 * Igual que la RPC: el vínculo sólo cuenta en filas `ok`/`aprobada`. Una fila omitida
 * o importada con un puntero viejo no está «vinculada» a efectos de nada.
 */
fun stagingIsLinked(item: ContentImportStagingItem): Boolean =
    !item.linkQuestionId.isNullOrBlank() && item.isStatus("ok", "aprobada")

/**
 * El tono de una fila, con el vocabulario VIVO de `estado_parseo`:
 * `ok | advertencia | error | importada | aprobada | omitida`.
 *
 *  - `importada` ya está en el examen: tono propio, NUNCA error.
 *  - `omitida` es una decisión del revisor y `advertencia` una duda del parser que
 *    exige aprobar u omitir: son dos cosas distintas y no comparten tono.
 *  - Una coincidencia detectada por la base es DUPLICADA aunque el estado diga `ok`,
 *    mientras la fila no esté vinculada, omitida ni importada.
 *  - La anulación NO cambia el tono: es un dato de la pregunta en el examen.
 */
fun stagingTone(item: ContentImportStagingItem): ReviewFilter = when {
    item.isStatus("importada") -> ReviewFilter.IMPORTADA
    item.isStatus("omitida") -> ReviewFilter.OMITIDA
    item.duplicate != null && !stagingIsLinked(item) -> ReviewFilter.DUPLICADA
    item.isStatus("advertencia") -> ReviewFilter.ADVERTENCIA
    item.isStatus("duplicada_candidata") -> ReviewFilter.DUPLICADA
    !item.parseError.isNullOrBlank() -> ReviewFilter.ERROR
    item.isStatus("ok", "aprobada") -> ReviewFilter.LISTA
    else -> ReviewFilter.ERROR
}

data class BatchMetrics(
    val detected: Int,
    val ready: Int,
    /** `advertencia`: hay que aprobarlas u omitirlas antes de publicar. */
    val warnings: Int,
    val skipped: Int,
    val imported: Int,
    val errors: Int,
    val duplicates: Int,
    /** Filas anuladas por el tribunal (ortogonal al estado de parseo). */
    val annulled: Int,
)

fun ContentImportBatch.metrics(): BatchMetrics {
    val tones = staging.map { stagingTone(it) }
    return BatchMetrics(
        detected = detectedCount,
        ready = tones.count { it == ReviewFilter.LISTA },
        warnings = tones.count { it == ReviewFilter.ADVERTENCIA },
        skipped = tones.count { it == ReviewFilter.OMITIDA },
        imported = tones.count { it == ReviewFilter.IMPORTADA },
        errors = tones.count { it == ReviewFilter.ERROR },
        duplicates = tones.count { it == ReviewFilter.DUPLICADA },
        annulled = staging.count { it.annulled },
    )
}
