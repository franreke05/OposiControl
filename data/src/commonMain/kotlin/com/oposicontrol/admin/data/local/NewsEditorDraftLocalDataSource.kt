package com.oposicontrol.admin.data.local

import com.oposicontrol.admin.core.database.AdminDatabase

interface NewsEditorDraftLocalDataSource {
    fun readDraft(): NewsEditorDraftSnapshot?
    fun saveDraft(snapshot: NewsEditorDraftSnapshot)
    fun clearDraft()
}

class SqlDelightNewsEditorDraftLocalDataSource(
    private val database: AdminDatabase,
) : NewsEditorDraftLocalDataSource {
    override fun readDraft(): NewsEditorDraftSnapshot? {
        return database.newsEditorDraftQueries.selectDraft(
            mapper = { _, newsId, title, textContent, specialty, type, backgroundType, backgroundPath, imagePath, status, layersJson, canvasWidth, canvasHeight ->
                NewsEditorDraftSnapshot(
                    newsId = newsId,
                    title = title,
                    textContent = textContent,
                    specialty = specialty,
                    type = type,
                    backgroundType = backgroundType,
                    backgroundPath = backgroundPath,
                    imagePath = imagePath,
                    status = status,
                    layersJson = layersJson,
                    canvasWidth = canvasWidth.toInt(),
                    canvasHeight = canvasHeight.toInt(),
                )
            },
        ).executeAsOneOrNull()
    }

    override fun saveDraft(snapshot: NewsEditorDraftSnapshot) {
        database.newsEditorDraftQueries.upsertDraft(
            news_id = snapshot.newsId,
            title = snapshot.title,
            text_content = snapshot.textContent,
            specialty = snapshot.specialty,
            type = snapshot.type,
            background_type = snapshot.backgroundType,
            background_path = snapshot.backgroundPath,
            image_path = snapshot.imagePath,
            status = snapshot.status,
            layers_json = snapshot.layersJson,
            canvas_width = snapshot.canvasWidth.toLong(),
            canvas_height = snapshot.canvasHeight.toLong(),
        )
    }

    override fun clearDraft() {
        database.newsEditorDraftQueries.clearDraft()
    }
}

data class NewsEditorDraftSnapshot(
    val newsId: String,
    val title: String,
    val textContent: String,
    val specialty: String,
    val type: String,
    val backgroundType: String,
    val backgroundPath: String,
    val imagePath: String,
    val status: String,
    val layersJson: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
)
