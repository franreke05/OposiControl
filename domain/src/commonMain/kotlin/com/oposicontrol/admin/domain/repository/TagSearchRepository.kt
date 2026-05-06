package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.TagSearchQuery
import com.oposicontrol.admin.domain.model.TaggedSearchResult

interface TagSearchRepository {
    suspend fun findTags(query: String): AppResult<List<Tag>>
    suspend fun search(query: TagSearchQuery): AppResult<List<TaggedSearchResult>>
    suspend fun reindex(): AppResult<Unit>
}
