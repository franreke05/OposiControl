package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.ContentImportBatch

/** El código con el que el servidor pide confirmar que las preguntas se verán al instante (examen ya publicado). */
internal const val CONTENT_UPLOAD_EXAM_PUBLISHED_CONFIRMATION_CODE = "IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED"

/** Rótulo del botón de la revisión móvil: la RPC publica el LOTE (filas `ok` y `aprobada`), no «sólo las aprobadas». */
internal const val CONTENT_UPLOAD_PUBLISH_LABEL = "Publicar lote"

/**
 * Lo que la revisión compacta necesita saber para decidir si se puede publicar.
 *
 * La RPC `admin_import_lote_preguntas` publica las filas `ok` **y** `aprobada`; las `omitida` se
 * saltan y las `error`/`advertencia` sin decidir la bloquean. Antes esta pantalla contaba las `ok`
 * como «requieren revisión» y sólo activaba el botón con `aprobada > 0`: un lote limpio (todo
 * `ok`) no se podía publicar desde el móvil.
 */
internal data class ContentUploadPublishSummary(
    val ok: Int,
    val approved: Int,
    val blocking: Int,
) {
    /** Filas que la publicación crea: `ok` + `aprobada`. */
    val publishable: Int get() = ok + approved

    fun canPublish(batchStatus: String, busy: Boolean): Boolean =
        publishable > 0 && blocking == 0 && !batchStatus.trim().equals("importado", ignoreCase = true) && !busy

    /** Cuántas se publicarán, o por qué no se puede, sin decir «sólo aprobadas». */
    fun note(): String = when {
        blocking > 0 ->
            "Resuelve ${plural(blocking, "la fila con error o advertencia", "las filas con error o advertencia")} " +
                "(guárdala y apruébala o sáltala) antes de publicar."
        publishable == 0 -> "No hay preguntas para publicar: todas están saltadas."
        else -> "Se ${if (publishable == 1) "publicará 1 pregunta" else "publicarán $publishable preguntas"} " +
            "($ok listas y $approved aprobadas); las saltadas no se publican."
    }
}

internal fun contentUploadPublishSummary(batch: ContentImportBatch): ContentUploadPublishSummary {
    val ok = batch.staging.count { it.parseStatus == "ok" && it.parseError.isNullOrBlank() }
    val approved = batch.staging.count { it.parseStatus == "aprobada" }
    val blocking = batch.staging.count {
        it.parseStatus == "error" || it.parseStatus == "advertencia" ||
            (it.parseStatus != "aprobada" && it.parseStatus != "omitida" && !it.parseError.isNullOrBlank())
    }
    return ContentUploadPublishSummary(ok = ok, approved = approved, blocking = blocking)
}

/**
 * Esta pantalla no tiene diálogo de confirmación: si el examen ya está publicado la RPC responde 409
 * `IMPORT_EXAM_PUBLISHED_CONFIRMATION_REQUIRED` y aquí se dice CLARO qué hacer, en vez de enseñar
 * el aviso genérico del servidor. La confirmación vive en la pantalla de escritorio (Importaciones).
 */
internal fun AppError.asPublishError(): AppError =
    if (this is AppError.Validation && code.equals(CONTENT_UPLOAD_EXAM_PUBLISHED_CONFIRMATION_CODE, ignoreCase = true)) {
        AppError.Validation(
            message = "El examen ya está publicado: las preguntas se verían al instante y hay que confirmarlo. " +
                "Confirma la publicación desde el escritorio (Importaciones); el lote no ha cambiado.",
            code = code,
        )
    } else {
        this
    }

private fun plural(n: Int, one: String, many: String): String = if (n == 1) one else many
