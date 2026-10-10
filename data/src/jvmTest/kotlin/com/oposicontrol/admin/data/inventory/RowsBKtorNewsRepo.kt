package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.data.repository.KtorNewsRepositoryImpl
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.NewsArticle
import com.oposicontrol.admin.domain.model.NewsListFilter
import java.nio.file.Files

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorNewsRepositoryImpl.kt"
private const val ROUTE = "/api/v1/admin/noticias"
private const val KEY = "6f1c2a3e-1111-4111-8111-aaaaaaaaaaa1"

private fun Env.repo() = KtorNewsRepositoryImpl(client, appConfig, session, PlatformFileReader(AppPlatformContext()), adapter)

private fun article(id: String, version: String = "") = NewsArticle(
    id = id, title = "t", specialty = "s", type = "t", backgroundType = "SKY", backgroundPath = null,
    status = "draft", publishFrom = "", text = "x", imagePath = null, layers = emptyList(), version = version,
)

/** Todos los filtros a la vez: la fila tiene que grabar cada `parameter("x"` que la funcion puede enviar. */
private val FULL_FILTER = NewsListFilter(status = "draft", specialtyId = "esp", typeId = "tipo", query = "q")

/** Filas del registrador (R4-REC-B): KtorNewsRepositoryImpl sobre el contrato canonico D4-01..D4-09 (10 sitios). */
internal val ROWS_B_NEWS_REPO: List<CallRow> = listOf(
    CallRow(FILE, "refreshNews", get("$ROUTE?especialidadId&estado&limit&q&tipoNoticiaId")) { repo().refreshNews(FULL_FILTER) },
    CallRow(FILE, "loadMoreNews", get("$ROUTE?cursor&especialidadId&estado&limit&q&tipoNoticiaId")) { repo().loadMoreNews(FULL_FILTER, "c1") },
    CallRow(FILE, "getNews", get("$ROUTE/{}")) { repo().getNews("ID0") },
    CallRow(FILE, "createNews", post(ROUTE)) { repo().createNews(article(""), KEY) },
    CallRow(FILE, "updateNews", patch("$ROUTE/{}")) { repo().updateNews(article("ID0", version = "2026-10-08T12:00:00Z")) },
    CallRow(FILE, "deleteNews", delete("$ROUTE/{}")) { repo().deleteNews("ID0") },
    // Los tres comandos de estado comparten el ayudante privado `command`: un sitio, tres puntos de entrada.
    CallRow(
        FILE, "publishNews",
        listOf(post("$ROUTE/{}/publicar"), post("$ROUTE/{}/programar"), post("$ROUTE/{}/archivar")),
        covers = setOf("publishNews", "scheduleNews", "archiveNews"), site = "command",
    ) {
        repo().publishNews("ID0")
        repo().scheduleNews("ID0", "2026-12-01T09:00:00Z")
        repo().archiveNews("ID0")
    },
    CallRow(FILE, "uploadNewsAsset", post("$ROUTE/assets")) {
        // el lector real exige un fichero local con extension de imagen para declarar el MIME
        val png = Files.createTempFile("news-asset", ".png").toFile()
        try {
            png.writeBytes(byteArrayOf(1, 2, 3))
            repo().uploadNewsAsset(png.absolutePath, KEY)
        } finally {
            png.delete()
        }
    },
)
