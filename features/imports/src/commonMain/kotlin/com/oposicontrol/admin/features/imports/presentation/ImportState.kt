package com.oposicontrol.admin.features.imports.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.ContentExamType
import com.oposicontrol.admin.domain.model.ContentImportBatch
import com.oposicontrol.admin.domain.model.ContentImportStagingItem
import com.oposicontrol.admin.domain.model.Exam

enum class ReviewFilter { ALL, LISTA, ADVERTENCIA, ERROR, DUPLICADA }

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

/** Preguntas de la especialidad elegida que además pasan búsqueda/filtro de revisión. */
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

/**
 * El tono de una fila, con el vocabulario VIVO de `estado_parseo` (KI3-5, IMP-18):
 * `advertencia` es un aviso —la fila se publica igual— y no un error; una
 * coincidencia detectada por la base es DUPLICADA aunque el estado diga `ok`.
 */
fun stagingTone(item: ContentImportStagingItem): ReviewFilter = when {
    item.duplicate != null && item.linkQuestionId == null && !item.parseStatus.equals("omitida", ignoreCase = true) -> ReviewFilter.DUPLICADA
    item.parseStatus.equals("omitida", ignoreCase = true) -> ReviewFilter.ADVERTENCIA
    item.parseStatus.equals("advertencia", ignoreCase = true) -> ReviewFilter.ADVERTENCIA
    item.parseStatus.equals("duplicada_candidata", ignoreCase = true) -> ReviewFilter.DUPLICADA
    !item.parseError.isNullOrBlank() -> ReviewFilter.ERROR
    item.parseStatus.equals("ok", ignoreCase = true) || item.parseStatus.equals("aprobada", ignoreCase = true) -> ReviewFilter.LISTA
    else -> ReviewFilter.ERROR
}

data class BatchMetrics(
    val detected: Int,
    val ready: Int,
    val review: Int,
    val errors: Int,
    val duplicates: Int,
)

fun ContentImportBatch.metrics(): BatchMetrics {
    val ready = staging.count { stagingTone(it) == ReviewFilter.LISTA }
    val review = staging.count { stagingTone(it) == ReviewFilter.ADVERTENCIA }
    val errors = staging.count { stagingTone(it) == ReviewFilter.ERROR }
    val duplicates = staging.count { stagingTone(it) == ReviewFilter.DUPLICADA }
    return BatchMetrics(detected = detectedCount, ready = ready, review = review, errors = errors, duplicates = duplicates)
}
