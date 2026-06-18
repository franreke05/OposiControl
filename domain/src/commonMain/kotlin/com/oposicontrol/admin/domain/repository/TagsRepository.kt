package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/**
 * CRUD de etiquetas (tabla `tag`). Separado de [TagSearchRepository], que se
 * ocupa solo de busqueda/indexacion, para mantener responsabilidades limpias.
 */
interface TagsRepository {
    fun observeTags(): Flow<List<Tag>>
    suspend fun refreshTags(): AppResult<List<Tag>>
    suspend fun upsertTag(tag: Tag): AppResult<Tag>
    suspend fun deleteTag(tagId: String): AppResult<Unit>
}
