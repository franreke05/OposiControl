package com.oposicontrol.admin.features.news.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.domain.model.NewsValidationReport
import com.oposicontrol.admin.features.news.editor.NEWS_CANVAS_DEFAULT_HEIGHT
import com.oposicontrol.admin.features.news.editor.NEWS_CANVAS_DEFAULT_WIDTH

/**
 * Estado de la pantalla de Noticias.
 *
 * Los campos `editor*` son el DOCUMENTO abierto (lo que viaja al servidor) mas lo que el servidor sabe de el
 * ([editorVersion], [editorPublishedAt], las URL publicas). El resto es vista: seleccion, pestana, filtros, red.
 * El editor movil de `features/exams` lee una parte de estos campos por su nombre: se anade, no se renombra.
 */
data class NewsState(
    val specialties: UiAsync<List<NewsCatalogOption>> = UiAsync.Loading,
    val newsTypes: UiAsync<List<NewsCatalogOption>> = UiAsync.Loading,
    /** Estado de la ULTIMA lectura de la biblioteca: cargando, error (sin filas) o leida. */
    val libraryState: UiAsync<Unit> = UiAsync.Loading,
    /** Las filas de la biblioteca (resumenes del servidor mas, si la hay, la noticia nueva sin guardar). */
    val news: UiAsync<List<NewsItem>> = UiAsync.Loading,
    val publishedNews: UiAsync<List<NewsItem>> = UiAsync.Loading,
    val localNews: UiAsync<List<NewsItem>> = UiAsync.Loading,
    /** Refresco con filas ya en pantalla: la lista se queda y la cabecera dice «Actualizando…». */
    val libraryRefreshing: Boolean = false,
    /** El ultimo refresco fallo pero hay filas anteriores: se ensenan con un aviso, no se borran. */
    val libraryRefreshFailed: Boolean = false,
    val libraryNextCursor: String? = null,
    val libraryLoadingMore: Boolean = false,
    val searchQuery: String = "",
    val statusFilter: String = "all",
    val specialtyFilter: String = "all",
    val typeFilter: String = "all",
    val selectedId: String? = null,
    /** Lectura de la ficha abierta: `Loading` mientras llega, `Error` con reintento, `Success` con la ficha puesta. */
    val recordState: UiAsync<Unit> = UiAsync.Idle,
    val editorTitle: String = "",
    val editorBackgroundType: String = "SKY",
    val editorBackgroundPath: String = "",
    val editorBackgroundUrl: String = "",
    val editorSpecialty: String = "",
    val editorType: String = "",
    val editorText: String = "",
    val editorImagePath: String = "",
    val editorImageUrl: String = "",
    val editorStatus: String = "draft",
    val editorPublishFrom: String = "",
    val editorPublishedAt: String = "",
    /** Version de concurrencia (ETag) de la ficha leida; vacia en una noticia nueva. */
    val editorVersion: String = "",
    val editorCanvasWidth: Int = NEWS_CANVAS_DEFAULT_WIDTH,
    val editorCanvasHeight: Int = NEWS_CANVAS_DEFAULT_HEIGHT,
    val editorBackgroundAssetStatus: String = "linked",
    val editorImageAssetStatus: String = "linked",
    val editorLayers: List<NewsLayerDraft> = emptyList(),
    val editorTab: NewsEditorTab = NewsEditorTab.CONTENT,
    val selectedTarget: NewsEditorSelection = NewsEditorSelection.ARTICLE,
    val selectedLayerId: String? = null,
    val canvasFullscreen: Boolean = false,
    val validationReport: NewsValidationReport? = null,
    /** Operacion remota en curso o acabada ([actionKind] dice cual). */
    val actionState: UiAsync<Unit> = UiAsync.Idle,
    val actionKind: NewsAction? = null,
    /** El servidor rechazo la escritura porque la noticia cambio o ya no es editable: hay que recargarla. */
    val actionConflict: Boolean = false,
    val isDirty: Boolean = false,
    val actionMessage: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Subidas de imagen en marcha o fallidas, por destino. */
    val uploads: Map<String, NewsUploadState> = emptyMap(),
    /** Navegacion retenida porque hay cambios sin guardar: la pantalla pregunta antes de ejecutarla. */
    val pendingIntent: NewsIntent? = null,
    /** Accion destructiva o irreversible que espera confirmacion. */
    val confirmIntent: NewsIntent? = null,
    /** La ficha abierta lleva cambios recuperados del borrador local de este equipo. */
    val recoveredDraft: Boolean = false,
    /** La relectura silenciosa de la ficha abierta tras un refresco fallo: lo que se ve puede no ser lo ultimo. */
    val recordReloadFailed: Boolean = false,
    /** Capas que el servidor devolvio y no se pudieron leer: no se pintan y se perderian al guardar. */
    val editorDroppedLayers: Int = 0,
)
