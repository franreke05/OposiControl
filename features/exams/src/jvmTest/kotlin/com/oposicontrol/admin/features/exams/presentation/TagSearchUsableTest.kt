package com.oposicontrol.admin.features.exams.presentation

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Tag
import com.oposicontrol.admin.domain.model.TagSearchQuery
import com.oposicontrol.admin.domain.model.TaggedSearchResult
import com.oposicontrol.admin.domain.repository.TagSearchRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tag-search panel (and its "Reindexar" action) is only offered for a repository with a real backend. */
class TagSearchUsableTest {
    private fun repo(available: Boolean) = object : TagSearchRepository {
        override val isAvailable = available
        override suspend fun findTags(query: String): AppResult<List<Tag>> = AppResult.Success(emptyList())
        override suspend fun search(query: TagSearchQuery): AppResult<List<TaggedSearchResult>> = AppResult.Success(emptyList())
        override suspend fun reindex(): AppResult<Unit> = AppResult.Success(Unit)
    }

    @Test
    fun nullAndUnavailableRepositoriesAreNotUsable() {
        assertFalse(tagSearchUsable(null))
        assertFalse(tagSearchUsable(repo(available = false)))
    }

    @Test
    fun availableRepositoryIsUsable() {
        assertTrue(tagSearchUsable(repo(available = true)))
    }
}
