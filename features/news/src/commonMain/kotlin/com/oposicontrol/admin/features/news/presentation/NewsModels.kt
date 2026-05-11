package com.oposicontrol.admin.features.news.presentation

data class NewsItem(
    val id: String,
    val title: String,
    val specialty: String,
    val type: String,
    val backgroundType: String,
    val backgroundPath: String? = null,
    val status: String,
    val publishFrom: String,
    val text: String,
    val imagePath: String? = null,
    val layers: List<NewsLayerDraft> = emptyList(),
    val isLocalDraft: Boolean = false,
)

data class NewsLayerDraft(
    val id: String,
    val name: String,
    val kind: String,
    val content: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val opacity: Int,
    val fontFamily: String = "sans",
    val textColor: String = "",
    val visible: Boolean,
    val locked: Boolean = false,
    val sortOrder: Int,
    val rotationDegrees: Int = 0,
)

enum class NewsEditorSelection {
    ARTICLE,
    BACKGROUND,
    LAYER,
}
