package com.oposicontrol.admin.domain.model

data class Tag(
    val id: String,
    val name: String,
    val slug: String,
    val origin: String,
    /** `false` = desactivada en el catálogo: no se ofrece al etiquetar preguntas. */
    val active: Boolean = true,
)

data class TagSearchQuery(
    val text: String = "",
    val tagSlugs: List<String> = emptyList(),
    val section: String? = null,
)

data class TaggedSearchResult(
    val entityType: String,
    val entityId: String,
    val section: String,
    val title: String,
    val summary: String,
    val tags: List<Tag>,
    val updatedAt: String,
)
