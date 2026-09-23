package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.ForumReplyPage
import com.oposicontrol.admin.domain.model.ForumThreadDetail
import com.oposicontrol.admin.domain.model.ForumThreadPage

/** Supervisión de actividad real de OposiRed (hilos), independiente de
 * [ModerationRepository] (que gestiona el ESTADO de los informes, no el
 * contenido del foro en sí). */
interface ForumRepository {
    /**
     * Lista hilos reales, más nuevos primero. [cursor] null pide la primera
     * página; en caso contrario continúa exactamente donde terminó la página
     * cuyo [ForumThreadPage.nextCursor] se pasa aquí. [reportedOnly] filtra a
     * hilos con un reporte pendiente real (sobre el hilo o sobre alguno de sus
     * comentarios); [categoryId] filtra por categoría real si se indica.
     */
    suspend fun listThreads(
        cursor: String?,
        reportedOnly: Boolean,
        categoryId: String? = null,
        pageSize: Int = 20,
    ): AppResult<ForumThreadPage>

    /** Detalle completo de un hilo (contenido sin truncar + conteo real de
     * reportes pendientes asociados). */
    suspend fun getThread(threadId: String): AppResult<ForumThreadDetail>

    /** Respuestas de un hilo en orden natural de lectura (más antiguas
     * primero), paginadas por cursor keyset. */
    suspend fun listReplies(
        threadId: String,
        cursor: String?,
        pageSize: Int = 20,
    ): AppResult<ForumReplyPage>
}
