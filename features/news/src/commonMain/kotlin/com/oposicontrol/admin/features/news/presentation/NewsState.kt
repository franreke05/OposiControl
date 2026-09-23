package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.NewsValidationReport

data class NewsState(
    val specialties: UiAsync<List<NewsCatalogOption>> = UiAsync.Loading,
    val newsTypes: UiAsync<List<NewsCatalogOption>> = UiAsync.Loading,
    val libraryState: UiAsync<Unit> = UiAsync.Loading,
    val news: UiAsync<List<NewsItem>> = UiAsync.Loading,
    val publishedNews: UiAsync<List<NewsItem>> = UiAsync.Loading,
    val localNews: UiAsync<List<NewsItem>> = UiAsync.Loading,
    val searchQuery: String = "",
    val statusFilter: String = "all",
    val specialtyFilter: String = "all",
    val typeFilter: String = "all",
    val selectedId: String? = null,
    val editorTitle: String = "",
    val editorBackgroundType: String = "A",
    val editorBackgroundPath: String = "",
    val editorSpecialty: String = "",
    val editorType: String = "",
    val editorText: String = "",
    val editorImagePath: String = "",
    val editorStatus: String = "draft",
    val editorPublishFrom: String = "",
    val editorCanvasWidth: Int = 800,
    val editorCanvasHeight: Int = 500,
    val editorBackgroundAssetStatus: String = "linked",
    val editorImageAssetStatus: String = "linked",
    val editorLayers: List<NewsLayerDraft> = emptyList(),
    val selectedTarget: NewsEditorSelection = NewsEditorSelection.ARTICLE,
    val selectedLayerId: String? = null,
    val canvasFullscreen: Boolean = false,
    val validationReport: NewsValidationReport? = null,
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    val isDirty: Boolean = false,
    val actionMessage: String = "",
)
