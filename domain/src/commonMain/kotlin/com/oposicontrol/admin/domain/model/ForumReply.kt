package com.oposicontrol.admin.domain.model

/** Una respuesta (comentario) real de un hilo de OposiRed. */
data class ForumReply(
    val id: String,
    val authorName: String,
    val content: String,
    val status: String,
    val createdAt: String,
)

/**
 * Página de [ForumReply] en orden natural de lectura (más antiguas primero),
 * paginada por cursor keyset. [nextCursor] null significa que no hay más
 * páginas — nunca se pagina con OFFSET.
 */
data class ForumReplyPage(
    val items: List<ForumReply>,
    val nextCursor: String?,
)
