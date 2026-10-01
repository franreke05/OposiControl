package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.TagSearchQuery
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** CLEAN-TAGSEARCH-FAKE: no backend behind it, so no operation may report success (an empty Success read as "no results"). */
class KtorTagSearchRepositoryImplTest {
    private val repository = KtorTagSearchRepositoryImpl()

    @Test
    fun declaresItselfUnavailable() {
        assertFalse(repository.isAvailable)
    }

    @Test
    fun noOperationReportsSuccess() = runTest {
        val results = listOf(
            repository.findTags("x"),
            repository.search(TagSearchQuery(text = "x", tagSlugs = emptyList(), section = null)),
            repository.reindex(),
        )
        results.forEach { result ->
            val failure = assertIs<AppResult.Failure>(result)
            assertEquals(KtorTagSearchRepositoryImpl.UNAVAILABLE_MESSAGE, (failure.error as AppError.NotFound).message)
        }
    }
}
