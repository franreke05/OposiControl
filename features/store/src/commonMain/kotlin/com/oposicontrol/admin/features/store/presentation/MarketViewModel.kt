package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.analytics.AnalyticsTracker
import com.oposicontrol.admin.core.common.coroutine.DispatcherProvider
import com.oposicontrol.admin.core.common.presentation.MviViewModel
import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.common.result.map
import com.oposicontrol.admin.domain.model.Community
import com.oposicontrol.admin.domain.model.Exam
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketPromotionUsesPage
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack
import com.oposicontrol.admin.domain.repository.MarketCatalogRepository
import com.oposicontrol.admin.domain.model.CommercialProduct

/**
 * Secciones del Market. Cada una es una tabla distinta y se carga por separado.
 *
 * `EXAMS` va primera: es el escaparate, lo que el usuario final ve al entrar en
 * la tienda, y las otras cuatro son los precios y reglas que lo rodean.
 */
/**
 * Secciones de la tienda. [PRODUCTS] es de solo consulta (los productos se editan en Catálogo). [PACKS] ya no se ofrece en
 * el panel: un pack de tickets es un producto de tipo «Pack de tickets» en Catálogo, que es lo que lee la app; el motor de
 * la tabla aparte se conserva sin pantalla.
 */
enum class MarketSection { EXAMS, PRODUCTS, PACKS, SERVICES, PROMOTIONS, POPUPS }

sealed interface MarketIntent {
    data class SelectSection(val section: MarketSection) : MarketIntent
    data object Refresh : MarketIntent
    data class SetExamTypeFilter(val filter: ExamTypeFilter) : MarketIntent
    data class SetExamQuery(val query: String) : MarketIntent

    data class Packs(val action: EditorAction<PackDraft>) : MarketIntent
    data class Services(val action: EditorAction<ServiceDraft>) : MarketIntent
    data class Promotions(val action: EditorAction<PromotionDraft>) : MarketIntent
    data class Popups(val action: EditorAction<PopupDraft>) : MarketIntent

    data class SelectPromotionTab(val tab: PromotionTab) : MarketIntent

    /** Pide la primera página de usos de la promoción abierta (también sirve de reintento). */
    data object LoadPromotionUses : MarketIntent
    data object LoadMorePromotionUses : MarketIntent
}

data class MarketState(
    val section: MarketSection = MarketSection.EXAMS,
    /** Solo los exámenes PUBLICADOS; `Empty` si no hay ninguno. */
    val exams: UiAsync<List<Exam>> = UiAsync.Idle,
    /**
     * Comunidades para elegir la ilustración de la portada. Si no se pudieron
     * cargar queda vacía y las portadas usan la ilustración genérica: es solo
     * decoración, no un motivo para no enseñar los exámenes.
     */
    val examCommunities: List<Community> = emptyList(),
    val examType: ExamTypeFilter = ExamTypeFilter.ALL,
    val examQuery: String = "",
    /** Productos del Catálogo, para el escaparate. Solo lectura aquí. */
    val products: UiAsync<List<CommercialProduct>> = UiAsync.Idle,
    val packs: MarketEditor<MarketTicketPack, PackDraft> = MarketEditor(draft = PackDraft()),
    val services: MarketEditor<MarketService, ServiceDraft> = MarketEditor(draft = ServiceDraft()),
    val promotions: MarketEditor<MarketPromotion, PromotionDraft> = MarketEditor(draft = PromotionDraft()),
    val popups: MarketEditor<MarketPopupRule, PopupDraft> = MarketEditor(draft = PopupDraft()),
    val promotionTab: PromotionTab = PromotionTab.PROMOTION,
    /** Usos de la promoción [promotionUsesFor]. `Idle` hasta que se abre la pestaña Usos. */
    val promotionUses: UiAsync<MarketPromotionUsesPage> = UiAsync.Idle,
    val promotionUsesFor: String? = null,
    val loadingMoreUses: Boolean = false,
    /** El último «cargar más» falló: los usos ya traídos se conservan y se ofrece reintentar. */
    val moreUsesFailed: Boolean = false,
)

/**
 * OposiMarket.
 *
 * Carga por sección y no de golpe: son cinco lecturas independientes y pedirlas todas al abrir gastaría cinco peticiones
 * para enseñar una. Cada sección tiene su propio estado, así que un fallo en promociones no deja la pantalla en blanco, y
 * un borrador a medias en Packs sigue ahí al volver de Servicios.
 */
class MarketViewModel(
    dispatcherProvider: DispatcherProvider,
    private val repository: MarketCatalogRepository,
    private val examsSource: PublishedExamsSource,
    private val analyticsTracker: AnalyticsTracker,
    /** Productos del Catálogo para el escaparate. Por defecto, ninguno: quien no lo cablea no enseña productos inventados. */
    private val productsSource: suspend () -> AppResult<List<CommercialProduct>> = { AppResult.Success(emptyList()) },
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
            is MarketIntent.Packs -> edit(PackSpec, { it.packs }, { copy(packs = it) }, intent.action, "pack") { repository.saveTicketPack(it) }
            is MarketIntent.Services -> edit(ServiceSpec, { it.services }, { copy(services = it) }, intent.action, "service") { repository.saveService(it) }
            is MarketIntent.Promotions -> {
                val before = currentState().promotions.selectedId
                edit(PromotionSpec, { it.promotions }, { copy(promotions = it) }, intent.action, "promotion") { repository.savePromotion(it) }
                // Los usos son de UNA promoción: al abrir otra, los de la anterior no se enseñan ni un instante.
                if (currentState().promotions.selectedId != before) {
                    updateState { it.copy(promotionTab = PromotionTab.PROMOTION, promotionUses = UiAsync.Idle, promotionUsesFor = null, moreUsesFailed = false) }
                }
            }
            is MarketIntent.Popups -> edit(PopupSpec, { it.popups }, { copy(popups = it) }, intent.action, "popup") { repository.savePopupRule(it) }
            is MarketIntent.SelectPromotionTab -> {
                updateState { it.copy(promotionTab = intent.tab) }
                val state = currentState()
                if (intent.tab == PromotionTab.USES && state.promotionUsesFor != state.promotions.selectedId) loadPromotionUses()
            }
            MarketIntent.LoadPromotionUses -> loadPromotionUses()
            MarketIntent.LoadMorePromotionUses -> loadMorePromotionUses()
        }
    }

    /**
     * Aplica una acción a un catálogo editable. Todo lo que no toca la red lo resuelve [reduce]; el guardado valida, envía
     * y deja la ficha sobre lo que devolvió el servidor.
     */
    private suspend fun <T, D> edit(
        spec: MarketEditorSpec<T, D>,
        get: (MarketState) -> MarketEditor<T, D>,
        set: MarketState.(MarketEditor<T, D>) -> MarketState,
        action: EditorAction<D>,
        analyticsName: String,
        save: suspend (T) -> AppResult<T>,
    ) {
        if (action != EditorAction.Save) {
            updateState { it.set(get(it).reduce(spec, action)) }
            return
        }
        val editor = get(currentState())
        if (!editor.isOpen || editor.actionState is UiAsync.Loading) return
        editor.problem(spec)?.let { problem ->
            updateState { it.set(get(it).saveFailed(AppError.Validation(problem))) }
            return
        }
        val entity = spec.toEntity(editor.draft, editor.selected(spec)) ?: return
        updateState { it.set(get(it).copy(actionState = UiAsync.Loading, notice = null)) }
        when (val result = save(entity)) {
            is AppResult.Failure -> updateState { it.set(get(it).saveFailed(result.error)) }
            is AppResult.Success -> {
                analyticsTracker.trackAction("market_${analyticsName}_save", emptyMap())
                updateState { it.set(get(it).saved(spec, entity, result.value)) }
            }
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
                val loaded = examsSource.load()
                val result = loaded.map { publishedExams(it.exams) }.toUiAsync()
                val communities = (loaded as? AppResult.Success)?.value?.communities.orEmpty()
                updateState { it.copy(exams = result, examCommunities = communities) }
            }
            MarketSection.PRODUCTS -> {
                updateState { it.copy(products = UiAsync.Loading) }
                val loaded = when (val result = productsSource()) {
                    is AppResult.Failure -> UiAsync.Error(result.error)
                    is AppResult.Success -> if (result.value.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)
                }
                updateState { it.copy(products = loaded) }
            }
            MarketSection.PACKS -> {
                updateState { it.copy(packs = it.packs.loading()) }
                val result = repository.getTicketPacks()
                updateState { it.copy(packs = it.packs.loaded(PackSpec, result)) }
            }
            MarketSection.SERVICES -> {
                updateState { it.copy(services = it.services.loading()) }
                val result = repository.getServices()
                updateState { it.copy(services = it.services.loaded(ServiceSpec, result)) }
            }
            MarketSection.PROMOTIONS -> {
                updateState { it.copy(promotions = it.promotions.loading()) }
                val result = repository.getPromotions()
                val before = currentState().promotions.selectedId
                updateState { it.copy(promotions = it.promotions.loaded(PromotionSpec, result)) }
                val state = currentState()
                when {
                    state.promotions.selectedId != before ->
                        updateState { it.copy(promotionTab = PromotionTab.PROMOTION, promotionUses = UiAsync.Idle, promotionUsesFor = null) }
                    // El refresco también pone al día los usos que se estaban mirando.
                    state.promotionTab == PromotionTab.USES -> loadPromotionUses()
                }
            }
            MarketSection.POPUPS -> {
                updateState { it.copy(popups = it.popups.loading()) }
                val result = repository.getPopupRules()
                updateState { it.copy(popups = it.popups.loaded(PopupSpec, result)) }
            }
        }
    }

    private suspend fun loadPromotionUses() {
        val promotionId = currentState().promotions.selectedId ?: return
        updateState { it.copy(promotionUses = UiAsync.Loading, promotionUsesFor = promotionId, loadingMoreUses = false, moreUsesFailed = false) }
        val result = repository.getPromotionUses(promotionId)
        // Si mientras tanto se abrió otra promoción, esta respuesta ya no es de la ficha que se ve.
        if (currentState().promotionUsesFor != promotionId) return
        updateState {
            it.copy(
                promotionUses = when (result) {
                    is AppResult.Failure -> UiAsync.Error(result.error)
                    is AppResult.Success -> if (result.value.items.isEmpty()) UiAsync.Empty else UiAsync.Success(result.value)
                },
            )
        }
    }

    private suspend fun loadMorePromotionUses() {
        val state = currentState()
        val promotionId = state.promotionUsesFor ?: return
        val page = (state.promotionUses as? UiAsync.Success)?.value ?: return
        val cursor = page.nextCursor ?: return
        if (state.loadingMoreUses) return
        updateState { it.copy(loadingMoreUses = true, moreUsesFailed = false) }
        val result = repository.getPromotionUses(promotionId, cursor)
        if (currentState().promotionUsesFor != promotionId) return
        updateState {
            when (result) {
                is AppResult.Failure -> it.copy(loadingMoreUses = false, moreUsesFailed = true)
                is AppResult.Success -> it.copy(
                    loadingMoreUses = false,
                    // Los contadores son del total y vienen en cada página: se toman los más recientes.
                    promotionUses = UiAsync.Success(result.value.copy(items = page.items + result.value.items)),
                )
            }
        }
    }
}

private fun MarketState.isLoaded(section: MarketSection): Boolean = when (section) {
    MarketSection.EXAMS -> exams is UiAsync.Success || exams is UiAsync.Empty
    MarketSection.PRODUCTS -> products is UiAsync.Success || products is UiAsync.Empty
    MarketSection.PACKS -> packs.isLoaded
    MarketSection.SERVICES -> services.isLoaded
    MarketSection.PROMOTIONS -> promotions.isLoaded
    MarketSection.POPUPS -> popups.isLoaded
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
