package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.TagSearchQuery
import com.oposicontrol.admin.domain.model.TaggedSearchResult

/**
 * Búsqueda por etiquetas. [isAvailable] es false cuando no hay backend real detrás: la UI no debe ofrecer buscar ni
 * reindexar, y las tres operaciones devuelven [AppResult.Failure] en vez de un éxito vacío.
 */
interface TagSearchRepository {
    val isAvailable: Boolean
    suspend fun findTags(query: String): AppResult<List<Tag>>
    suspend fun search(query: TagSearchQuery): AppResult<List<TaggedSearchResult>>
    suspend fun reindex(): AppResult<Unit>
}
