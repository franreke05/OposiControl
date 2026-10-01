package com.oposicontrol.admin.data.inventory

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.data.repository.KtorNewsRepositoryImpl
import com.oposicontrol.admin.data.storage.PlatformFileReader
import com.oposicontrol.admin.domain.model.NewsArticle
import java.nio.file.Files

private const val FILE = "data/src/commonMain/kotlin/com/oposicontrol/admin/data/repository/KtorNewsRepositoryImpl.kt"
private const val ROUTE = "/content/noticias"

private fun Env.repo() = KtorNewsRepositoryImpl(client, appConfig, session, PlatformFileReader(AppPlatformContext()), adapter)

private fun article(id: String) = NewsArticle(
    id = id, title = "t", specialty = "s", type = "t", backgroundType = "SKY", backgroundPath = null,
    status = "draft", publishFrom = "2026-01-01", text = "x", imagePath = null, layers = emptyList(),
)

/** Filas del registrador (R4-REC-B): KtorNewsRepositoryImpl (5 sitios). */
internal val ROWS_B_NEWS_REPO: List<CallRow> = listOf(
    CallRow(FILE, "refreshNews", get(ROUTE)) { repo().refreshNews() },
    CallRow(FILE, "upsertNews(create)", post(ROUTE)) { repo().upsertNews(article("")) },
    CallRow(FILE, "upsertNews(update)", put(ROUTE)) { repo().upsertNews(article("ID0")) },
    CallRow(FILE, "deleteNews", delete("$ROUTE/{}")) { repo().deleteNews("ID0") },
    CallRow(FILE, "uploadNewsAsset", post("$ROUTE/assets")) {
        // el lector real exige un fichero local con extension de imagen para declarar el MIME
        val png = Files.createTempFile("news-asset", ".png").toFile()
        try {
            png.writeBytes(byteArrayOf(1, 2, 3))
            repo().uploadNewsAsset(png.absolutePath)
        } finally {
            png.delete()
        }
    },
)
