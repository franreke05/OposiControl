package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
import kotlinx.coroutines.flow.Flow

interface NewsRepository {
    fun observeNews(): Flow<List<NewsArticle>>
    suspend fun refreshNews(): AppResult<List<NewsArticle>>
    suspend fun upsertNews(article: NewsArticle): AppResult<NewsArticle>
    suspend fun deleteNews(newsId: String): AppResult<Unit>
    suspend fun uploadNewsAsset(localPathOrUri: String): AppResult<NewsAssetUpload>
}
