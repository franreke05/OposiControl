package com.oposicontrol.admin.domain.model

/**
 * Una página de [ForumThread] paginada por cursor keyset. [nextCursor] es
 * opaco (no asumir formato) y `null` significa que no hay más páginas —
 * nunca se pagina con OFFSET para no degradar con volumen alto.
 */
data class ForumThreadPage(
    val items: List<ForumThread>,
    val nextCursor: String?,
)
