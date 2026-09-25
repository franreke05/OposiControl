package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.common.result.map
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack
import com.oposicontrol.admin.domain.repository.MarketCatalogRepository

/**
 * Secciones del Market. Cada una es una tabla distinta y se carga por separado.
 *
 * `EXAMS` va primera: es el escaparate, lo que el usuario final ve al entrar en
 * la tienda, y las otras cuatro son los precios y reglas que lo rodean.
 */
enum class MarketSection { EXAMS, PACKS, SERVICES, PROMOTIONS, POPUPS }

sealed interface MarketIntent {
    data class SelectSection(val section: MarketSection) : MarketIntent
    data object Refresh : MarketIntent
    data class SetExamTypeFilter(val filter: ExamTypeFilter) : MarketIntent
    data class SetExamQuery(val query: String) : MarketIntent
}

data class MarketState(
    val section: MarketSection = MarketSection.EXAMS,
    /** Solo los exámenes PUBLICADOS; `Empty` si no hay ninguno. */
    val exams: UiAsync<List<Exam>> = UiAsync.Idle,
    val examType: ExamTypeFilter = ExamTypeFilter.ALL,
    val examQuery: String = "",
    val packs: UiAsync<List<MarketTicketPack>> = UiAsync.Idle,
    val services: UiAsync<List<MarketService>> = UiAsync.Idle,
    val promotions: UiAsync<List<MarketPromotion>> = UiAsync.Idle,
    val popupRules: UiAsync<List<MarketPopupRule>> = UiAsync.Idle,
)

/**
 * OposiMarket.
 *
 * Carga por sección y no de golpe: son cuatro tablas independientes y cargarlas
 * todas al abrir gastaría cuatro peticiones para enseñar una. Cada sección tiene
 * su propio estado, así que un fallo en promociones no deja la pantalla en blanco.
 */
class MarketViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: MarketCatalogRepository,
    private val examsSource: PublishedExamsSource,
    private val analyticsTracker: AnalyticsTracker,
) : MviViewModel<MarketState, MarketIntent>(
    initialState = MarketState(),
    dispatcherProvider = dispatcherProvider,
) {
    init {
        dispatch(MarketIntent.Refresh)
    }

    override suspend fun handleIntent(intent: MarketIntent) {
        when (intent) {
            is MarketIntent.SelectSection -> {
                updateState { it.copy(section = intent.section) }
                loadSection(intent.section)
            }
            MarketIntent.Refresh -> loadSection(currentState().section, force = true)
            is MarketIntent.SetExamTypeFilter -> updateState { it.copy(examType = intent.filter) }
            is MarketIntent.SetExamQuery -> updateState { it.copy(examQuery = intent.query) }
        }
    }

    /**
     * Carga una sección. Si ya está cargada no se repite salvo refresco explícito:
     * cambiar de pestaña y volver no debería costar una petición cada vez.
     */
    private suspend fun loadSection(section: MarketSection, force: Boolean = false) {
        if (!force && currentState().isLoaded(section)) return
        analyticsTracker.trackScreen("market_${section.name.lowercase()}")
        when (section) {
            MarketSection.EXAMS -> {
                updateState { it.copy(exams = UiAsync.Loading) }
                val result = examsSource.load().map { publishedExams(it) }.toUiAsync()
                updateState { it.copy(exams = result) }
            }
            MarketSection.PACKS -> {
                updateState { it.copy(packs = UiAsync.Loading) }
                val result = repository.getTicketPacks().toUiAsync()
                updateState { it.copy(packs = result) }
            }
            MarketSection.SERVICES -> {
                updateState { it.copy(services = UiAsync.Loading) }
                val result = repository.getServices().toUiAsync()
                updateState { it.copy(services = result) }
            }
            MarketSection.PROMOTIONS -> {
                updateState { it.copy(promotions = UiAsync.Loading) }
                val result = repository.getPromotions().toUiAsync()
                updateState { it.copy(promotions = result) }
            }
            MarketSection.POPUPS -> {
                updateState { it.copy(popupRules = UiAsync.Loading) }
                val result = repository.getPopupRules().toUiAsync()
                updateState { it.copy(popupRules = result) }
            }
        }
    }
}

private fun MarketState.isLoaded(section: MarketSection): Boolean = when (section) {
    MarketSection.EXAMS -> exams is UiAsync.Success || exams is UiAsync.Empty
    MarketSection.PACKS -> packs is UiAsync.Success || packs is UiAsync.Empty
    MarketSection.SERVICES -> services is UiAsync.Success || services is UiAsync.Empty
    MarketSection.PROMOTIONS -> promotions is UiAsync.Success || promotions is UiAsync.Empty
    MarketSection.POPUPS -> popupRules is UiAsync.Success || popupRules is UiAsync.Empty
}

/**
 * Una lista vacía es `Empty`, no `Success(emptyList())`: el Market está
 * aprovisionado pero sin poblar, y la pantalla debe decir "no hay nada
 * configurado" en vez de enseñar una tabla vacía sin explicación.
 */
private fun <T> AppResult<List<T>>.toUiAsync(): UiAsync<List<T>> = when (this) {
    is AppResult.Failure -> UiAsync.Error(error)
    is AppResult.Success -> if (value.isEmpty()) UiAsync.Empty else UiAsync.Success(value)
}
