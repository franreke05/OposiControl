package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsAssetUpload
import com.oposicontrol.admin.domain.model.NewsListFilter
import com.oposicontrol.admin.domain.model.NewsPage
import com.oposicontrol.admin.domain.model.NewsSummary
import kotlinx.coroutines.flow.Flow

/**
 * Noticias de administracion sobre el contrato canonico (`openapi/news/news.yaml`, D4-01..D4-09).
 *
 * El estado editorial lo decide el servidor: una noticia nace en borrador ([createNews]) y solo cambia de estado con
 * los comandos [publishNews], [scheduleNews] y [archiveNews]. [updateNews] edita el contenido condicionado a la
 * version leida (`If-Match`): si otro editor la cambio entre medias, el servidor responde con precondicion fallida
 * y nada se pisa.
 */
interface NewsRepository {
    /** Ultima lista de resumenes leida (todas las paginas cargadas hasta ahora), mantenida al dia tras cada escritura. */
    fun observeNews(): Flow<List<NewsSummary>>

    /** Primera pagina del listado con [filter]. Sustituye la lista observada. */
    suspend fun refreshNews(filter: NewsListFilter = NewsListFilter()): AppResult<NewsPage>

    /** Pagina siguiente a [cursor] con el mismo [filter]. Se anade a la lista observada. */
    suspend fun loadMoreNews(filter: NewsListFilter, cursor: String): AppResult<NewsPage>

    suspend fun getNews(newsId: String): AppResult<NewsArticle>

    /** Crea un borrador. [idempotencyKey] (UUID) repite la misma respuesta si la peticion se reintenta. */
    suspend fun createNews(article: NewsArticle, idempotencyKey: String): AppResult<NewsArticle>

    /** Edita el contenido de una noticia en borrador o programada, condicionado a [NewsArticle.version]. */
    suspend fun updateNews(article: NewsArticle): AppResult<NewsArticle>

    suspend fun deleteNews(newsId: String): AppResult<Unit>

    suspend fun publishNews(newsId: String): AppResult<NewsArticle>

    /** [publishFromIsoUtc] debe ser un instante futuro en UTC (`2026-10-20T09:00:00Z`). */
    suspend fun scheduleNews(newsId: String, publishFromIsoUtc: String): AppResult<NewsArticle>

    suspend fun archiveNews(newsId: String): AppResult<NewsArticle>

    /**
     * Sube una imagen local (`file://` o `content://`) al bucket de noticias. [onProgress] recibe la fraccion
     * enviada (0..1) en el hilo del cliente HTTP.
     */
    suspend fun uploadNewsAsset(
        localPathOrUri: String,
        idempotencyKey: String,
        onProgress: (Float) -> Unit = {},
    ): AppResult<NewsAssetUpload>
}
