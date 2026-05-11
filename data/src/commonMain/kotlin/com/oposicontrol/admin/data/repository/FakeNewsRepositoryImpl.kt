package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.id.randomUuid
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
import com.oposicontrol.admin.domain.repository.NewsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeNewsRepositoryImpl : NewsRepository {
    private val news = MutableStateFlow<List<NewsArticle>>(emptyList())

    override fun observeNews(): Flow<List<NewsArticle>> = news.asStateFlow()

    override suspend fun refreshNews(): AppResult<List<NewsArticle>> = AppResult.Success(news.value)

    override suspend fun upsertNews(article: NewsArticle): AppResult<NewsArticle> {
        val saved = article.copy(id = article.id.ifBlank { randomUuid() })
        news.value = listOf(saved) + news.value.filterNot { it.id == saved.id }
        return AppResult.Success(saved)
    }

    override suspend fun deleteNews(newsId: String): AppResult<Unit> {
        news.value = news.value.filterNot { it.id == newsId }
        return AppResult.Success(Unit)
    }

    override suspend fun uploadNewsAsset(localPathOrUri: String): AppResult<NewsAssetUpload> {
        val fileName = localPathOrUri.substringAfterLast('/').ifBlank { "${randomUuid()}.png" }
        val path = "news-assets/local/$fileName"
        return AppResult.Success(NewsAssetUpload(path = path, publicUrl = path, mimeType = "image/png"))
    }

}
