package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.data.local.NewsEditorDraftLocalDataSource
import com.oposicontrol.admin.data.local.NewsEditorDraftSnapshot
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
import com.oposicontrol.admin.domain.model.NewsLayer
import com.oposicontrol.admin.domain.model.NewsListFilter
import com.oposicontrol.admin.domain.model.NewsPage
import com.oposicontrol.admin.domain.model.NewsSummary
import com.oposicontrol.admin.domain.repository.NewsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal const val NEWS_ESP = "3c4a1b2d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
internal const val NEWS_TIPO = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"

internal fun titleLayer(id: String = "l-title", content: String = "Convocatoria TCAE") =
    NewsLayer(id, "Título", "titulo", content, 48, 42, 460, 92, 100, "sans", "#FFFFFF", visible = true, sortOrder = 0)

internal fun article(
    id: String,
    title: String,
    status: String = "draft",
    version: String = "2026-10-08T12:00:00Z",
    publishFrom: String = "",
    layers: List<NewsLayer> = listOf(titleLayer()),
) = NewsArticle(
    id = id, title = title, specialty = NEWS_ESP, type = NEWS_TIPO, backgroundType = "SKY", backgroundPath = null,
    status = status, publishFrom = publishFrom, text = "Texto", imagePath = null, layers = layers, version = version,
)

/**
 * Doble del contrato canonico con ESTADO: versiones que avanzan, 412 con version caducada, comandos de estado,
 * filtros recordados y fallos configurables. Reproduce las reglas del servidor que el ViewModel debe respetar.
 */
internal class FakeNewsRepository(initial: List<NewsArticle> = listOf(article("news-1", "Convocatoria TCAE"), article("news-2", "Aviso SAS"))) : NewsRepository {
    val articles = linkedMapOf<String, NewsArticle>().apply { initial.forEach { put(it.id, it) } }
    private val flow = MutableStateFlow(summaries())
    private var tick = 0

    var failRefresh: AppError? = null
    var failGet: AppError? = null
    var failSave: AppError? = null
    var failUpload: AppError? = null
    val refreshFilters = mutableListOf<NewsListFilter>()
    val calls = mutableListOf<String>()
    var lastIdempotencyKey = ""
    var lastSaved: NewsArticle? = null
    var nextCursor: String? = null

    private fun summaries(): List<NewsSummary> = articles.values.map {
        NewsSummary(it.id, it.title, it.status, it.specialty, it.type, it.publishFrom.ifBlank { null }, it.publishedAt, it.imageUrl, it.version)
    }

    private fun emit() {
        flow.value = summaries()
    }

    private fun nextVersion(): String = "2026-10-08T12:00:${(++tick).toString().padStart(2, '0')}Z"

    override fun observeNews(): Flow<List<NewsSummary>> = flow

    override suspend fun refreshNews(filter: NewsListFilter): AppResult<NewsPage> {
        calls += "refresh"
        refreshFilters += filter
        failRefresh?.let { return AppResult.Failure(it) }
        emit()
        return AppResult.Success(NewsPage(summaries(), nextCursor))
    }

    override suspend fun loadMoreNews(filter: NewsListFilter, cursor: String): AppResult<NewsPage> {
        calls += "more:$cursor"
        return AppResult.Success(NewsPage(emptyList(), null))
    }

    override suspend fun getNews(newsId: String): AppResult<NewsArticle> {
        calls += "get:$newsId"
        failGet?.let { return AppResult.Failure(it) }
        return articles[newsId]?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.NotFound("La noticia no existe."))
    }

    override suspend fun createNews(article: NewsArticle, idempotencyKey: String): AppResult<NewsArticle> {
        calls += "create"
        lastIdempotencyKey = idempotencyKey
        failSave?.let { return AppResult.Failure(it) }
        val saved = article.copy(id = "news-${articles.size + 1}", status = "draft", version = nextVersion())
        articles[saved.id] = saved
        lastSaved = saved
        emit()
        return AppResult.Success(saved)
    }

    override suspend fun updateNews(article: NewsArticle): AppResult<NewsArticle> {
        calls += "update:${article.id}"
        failSave?.let { return AppResult.Failure(it) }
        val current = articles[article.id] ?: return AppResult.Failure(AppError.NotFound("La noticia no existe."))
        if (current.status !in setOf("draft", "scheduled")) return AppResult.Failure(AppError.Validation("Solo se pueden editar las noticias en borrador o programadas.", "news_not_editable"))
        if (current.version != article.version) return AppResult.Failure(AppError.Validation("El recurso ha cambiado desde la ultima lectura.", "precondition_failed"))
        val saved = article.copy(status = current.status, publishFrom = current.publishFrom, publishedAt = current.publishedAt, version = nextVersion())
        articles[saved.id] = saved
        lastSaved = saved
        emit()
        return AppResult.Success(saved)
    }

    override suspend fun deleteNews(newsId: String): AppResult<Unit> {
        calls += "delete:$newsId"
        articles.remove(newsId)
        emit()
        return AppResult.Success(Unit)
    }

    override suspend fun publishNews(newsId: String): AppResult<NewsArticle> {
        calls += "publish:$newsId"
        val current = articles[newsId] ?: return AppResult.Failure(AppError.NotFound("La noticia no existe."))
        if (current.status == "archived") return AppResult.Failure(AppError.Validation("La noticia no puede pasar a ese estado.", "invalid_transition"))
        val saved = current.copy(status = "published", publishedAt = current.publishedAt ?: "2026-10-08T13:00:00Z", publishFrom = "", version = nextVersion())
        articles[newsId] = saved
        emit()
        return AppResult.Success(saved)
    }

    override suspend fun scheduleNews(newsId: String, publishFromIsoUtc: String): AppResult<NewsArticle> {
        calls += "schedule:$newsId:$publishFromIsoUtc"
        val current = articles[newsId] ?: return AppResult.Failure(AppError.NotFound("La noticia no existe."))
        val saved = current.copy(status = "scheduled", publishFrom = publishFromIsoUtc, version = nextVersion())
        articles[newsId] = saved
        emit()
        return AppResult.Success(saved)
    }

    override suspend fun archiveNews(newsId: String): AppResult<NewsArticle> {
        calls += "archive:$newsId"
        val current = articles[newsId] ?: return AppResult.Failure(AppError.NotFound("La noticia no existe."))
        val saved = current.copy(status = "archived", version = nextVersion())
        articles[newsId] = saved
        emit()
        return AppResult.Success(saved)
    }

    override suspend fun uploadNewsAsset(localPathOrUri: String, idempotencyKey: String, onProgress: (Float) -> Unit): AppResult<NewsAssetUpload> {
        calls += "upload:${localPathOrUri.substringAfterLast('/')}"
        lastIdempotencyKey = idempotencyKey
        onProgress(0.5f)
        failUpload?.let { return AppResult.Failure(it) }
        onProgress(1f)
        val name = localPathOrUri.substringAfterLast('/')
        val path = "news/8f14e45f-ceea-467a-9a3e-1b2c3d4e5f60/$name"
        return AppResult.Success(NewsAssetUpload(path, "https://cdn.oposicontrol.local/$path", "image/png", 3))
    }

    /** Simula a otro editor: cambia la fila en el servidor sin pasar por este cliente. */
    fun changeOnServer(id: String, title: String) {
        val current = articles.getValue(id)
        articles[id] = current.copy(title = title, version = nextVersion())
        emit()
    }
}

internal class InMemoryNewsEditorDraftLocalDataSource(initial: NewsEditorDraftSnapshot? = null) : NewsEditorDraftLocalDataSource {
    var snapshot: NewsEditorDraftSnapshot? = initial
    var saves = 0

    override fun readDraft(): NewsEditorDraftSnapshot? = snapshot

    override fun saveDraft(snapshot: NewsEditorDraftSnapshot) {
        this.snapshot = snapshot
        saves += 1
    }

    override fun clearDraft() {
        snapshot = null
    }
}
