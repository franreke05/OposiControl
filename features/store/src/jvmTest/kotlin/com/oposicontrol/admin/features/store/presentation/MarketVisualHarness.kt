package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.ui.components.DesktopTab
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketPromotionUse
import com.oposicontrol.admin.domain.model.MarketPromotionUsesPage
import com.oposicontrol.admin.domain.model.MarketService
import kotlin.test.Test

/**
 * Banco de pruebas VISUAL de los catálogos editables de OposiMarket (servicios, promociones y pop-ups).
 *
 * Renderiza la pantalla real —el mismo `MarketContent` de producción— contra estados fabricados y escribe PNG en
 * `features/store/build/visual-harness/`. El escaparate de exámenes tiene su propio banco (`PublishedExamsVisualHarness`).
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por hecha y no reescribe ningún PNG.
 */
class MarketVisualHarness {
    private val fx = MarketFixtures

    @Test
    fun capturaServicios() {
        market("market-servicios-1920-light.png", fx.servicios(), height = 1300)
        market("market-servicios-1920-light-sin-precio.png", fx.servicioSinPrecio(), height = 1300)
        market("market-servicios-1920-light-campo-conservado.png", fx.servicioCampoConservado())
    }

    @Test
    fun capturaPromocionesYPopups() {
        market("market-promociones-1920-light.png", fx.promociones(), height = 1400)
        market("market-promociones-1920-light-usos.png", fx.promocionUsos())
        market("market-promociones-1920-light-usos-vacio.png", fx.promocionSinUsos())
        market("market-promociones-1920-light-usos-error.png", fx.promocionUsosCaidos())
        market("market-promociones-1920-light-salir-con-cambios.png", fx.promocionSaliendoConCambios())
        market("market-popups-1920-light.png", fx.popups(), height = 1900)
        market("market-popups-1920-dark.png", fx.popups(), dark = true)
    }

    @Test
    fun capturaLosEstados() {
    }

    @Test
    fun capturaLosAnchos() {
        market("market-promociones-1366-light-usos.png", fx.promocionUsos(), width = 1366, height = 768)
    }

    /** Un EDITOR (servicios, promociones, avisos): desde 2026-10-14 son pestañas de Catálogo. */
    private fun market(file: String, state: MarketState, width: Int = 1920, height: Int = 1080, dark: Boolean = false) {
        renderStoreCapture(StoreCapture(file, width, height, dark), MARKET_HUB) {
            MarketCatalogEditorContent(state = state, section = state.section, onIntent = {}, onOpenUser = {})
        }
    }

    /** El ESCAPARATE: OposiMarket, solo consulta. La fecha va fija para que la captura no dependa del día. */
    private fun showcase(file: String, state: MarketState, dark: Boolean = false) {
        renderStoreCapture(StoreCapture(file, 1920, 1080, dark), MARKET_HUB) {
            MarketContent(state = state, onIntent = {}, onEditInCatalog = {}, today = "2026-10-14")
        }
    }

    @Test
    fun capturaElEscaparate() {
        showcase("escaparate-productos-1920-light.png", fx.escaparate(MarketSection.PRODUCTS))
        showcase("escaparate-productos-1920-dark.png", fx.escaparate(MarketSection.PRODUCTS), dark = true)
        showcase("escaparate-servicios-1920-light.png", fx.escaparate(MarketSection.SERVICES))
        showcase("escaparate-promociones-1920-light.png", fx.escaparate(MarketSection.PROMOTIONS))
        showcase("escaparate-avisos-1920-dark.png", fx.escaparate(MarketSection.POPUPS), dark = true)
        showcase("escaparate-servicios-1920-light-nada-en-vigor.png", fx.escaparateSinNadaEnVigor())
        showcase("escaparate-productos-1920-light-error.png", fx.escaparateCaido())
    }
}

/** Market lleva su propia fila de secciones dentro de la pantalla: el banco no pinta otra encima. */
private val MARKET_HUB = HarnessHub(
    routeId = "commercial.market",
    title = "OposiMarket",
    description = "Escaparate, servicios, promociones y pop-ups.",
    tabs = emptyList<DesktopTab>(),
    selectedTab = "",
)

/** Estados fabricados. Los nombres y cifras son de ejemplo: nada de esto sale de un backend. */
internal object MarketFixtures {
    private val serviceList = listOf(
        MarketService("s1", "protector_racha_7d", "Protector de racha", "Salva la racha un día sin estudiar.", "protector_racha", priceTickets = 30),
        MarketService("s2", "potenciador_x2", "Potenciador ×2", "Doble de puntos durante un rato.", "potenciador", priceTickets = 50, priceCents = 199),
        MarketService("s3", "modo_papel", "Modo papel", null, "modo_papel", priceCents = 299, active = false),
        MarketService("s4", "doble_oportunidad", "Doble oportunidad", null, "doble_oportunidad", priceTickets = 15),
    )

    private val promotionList = listOf(
        MarketPromotion("pr1", "OTONO25", "Vuelta al estudio", "porcentaje", 15.0, validFrom = "2026-10-01T00:00:00Z", validUntil = "2026-11-30T23:59:59Z", uses = 148),
        MarketPromotion("pr2", "BIENVENIDA", "Bienvenida", "tickets_extra", 50.0, uses = 2031),
        MarketPromotion("pr3", "VERANO", "Verano", "cantidad", 5.0, active = false, validUntil = "2026-08-31T23:59:59Z", uses = 0),
    )

    private val popupList = listOf(
        MarketPopupRule(
            id = "r1",
            code = "sin_tickets_bloqueo",
            trigger = "sin_tickets",
            title = "¿Te quedas sin OposiTickets?",
            message = "Con un pack sigues practicando sin esperar a mañana.",
            primaryCta = "Ver packs",
            secondaryCta = "Ahora no",
            screen = "examen",
            moment = "bloqueo",
            minimumPlan = null,
            targetExamType = "inmersivo",
            minimumFrequencyHours = 24,
            priority = 80,
        ),
        MarketPopupRule("r2", "home_razonado", "abre_home", "Prueba el plan Razonado", "Explicaciones en cada pregunta.", screen = "home", moment = "home", minimumFrequencyHours = 72, priority = 50),
        MarketPopupRule("r3", "fin_rafaga", "fin_examen", "Buen ritmo", "Sube de plan y desbloquea más ráfagas.", moment = "despues", targetExamType = "rafaga", minimumFrequencyHours = 48, priority = 40, active = false),
    )

    private val uses = MarketPromotionUsesPage(
        items = listOf(
            MarketPromotionUse("11111111-1111-4111-8111-111111111111", "c1", 150, "2026-10-09T14:32:10Z", generatedPurchase = true, renewedAfter = true),
            MarketPromotionUse("22222222-2222-4222-8222-222222222222", "c2", 300, "2026-10-08T09:12:44Z", generatedPurchase = true, renewedAfter = null),
            MarketPromotionUse("33333333-3333-4333-8333-333333333333", null, null, "2026-10-07T21:03:00Z", generatedPurchase = false, renewedAfter = false),
            MarketPromotionUse("44444444-4444-4444-8444-444444444444", "c4", 150, "2026-10-06T07:45:19Z", generatedPurchase = true, renewedAfter = false),
        ),
        nextCursor = "siguiente",
        uses = 148,
        conversions = 121,
        renewals = 64,
    )

    private fun <T, D> editor(spec: MarketEditorSpec<T, D>, items: List<T>) =
        MarketEditor<T, D>(draft = spec.emptyDraft).loaded(spec, AppResult.Success(items))

    fun servicios() = MarketState(section = MarketSection.SERVICES, services = editor(ServiceSpec, serviceList).reduce(ServiceSpec, EditorAction.Open("s2")))

    fun servicioSinPrecio() = servicios().let { state ->
        state.copy(services = state.services.reduce(ServiceSpec, EditorAction.UpdateDraft(state.services.draft.copy(priceTickets = "", price = ""))))
    }

    fun servicioCampoConservado() = servicios().let { state ->
        val sent = serviceList[1].copy(description = null, priceCents = null)
        state.copy(services = state.services.saved(ServiceSpec, sent = sent, saved = serviceList[1]))
    }

    fun promociones() = MarketState(section = MarketSection.PROMOTIONS, promotions = editor(PromotionSpec, promotionList))

    fun promocionUsos() = promociones().copy(promotionTab = PromotionTab.USES, promotionUses = UiAsync.Success(uses), promotionUsesFor = "pr1")

    fun promocionSinUsos() = promociones().let { state ->
        state.copy(
            promotions = state.promotions.reduce(PromotionSpec, EditorAction.Open("pr3")),
            promotionTab = PromotionTab.USES,
            promotionUses = UiAsync.Empty,
            promotionUsesFor = "pr3",
        )
    }

    fun promocionUsosCaidos() = promocionUsos().copy(promotionUses = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")))

    fun promocionSaliendoConCambios() = promociones().let { state ->
        val editing = state.promotions.reduce(PromotionSpec, EditorAction.UpdateDraft(state.promotions.draft.copy(value = "20")))
        state.copy(promotions = editing.reduce(PromotionSpec, EditorAction.Open("pr2")))
    }

    fun popups() = MarketState(section = MarketSection.POPUPS, popups = editor(PopupSpec, popupList))

    private val productList = listOf(
        com.oposicontrol.admin.domain.model.CommercialProduct("c1", "pack_500", "pack_tickets", "500 OposiTickets", priceCents = 499, ticketAmount = 500, position = 1),
        com.oposicontrol.admin.domain.model.CommercialProduct("c2", "pack_2600", "pack_tickets", "2.600 OposiTickets", priceCents = 1999, ticketAmount = 2600, highlighted = true, position = 2),
        com.oposicontrol.admin.domain.model.CommercialProduct("c3", "sub_razonado", "suscripcion", "Suscripción Razonado", position = 3),
        com.oposicontrol.admin.domain.model.CommercialProduct("c4", "pack_oculto", "pack_tickets", "Pack oculto", priceCents = 99, ticketAmount = 50, visible = false, position = 4),
    )

    /** Todo cargado, abierto en [section]: lo que no está en vigor (inactivo, oculto, caducado) no debe verse. */
    fun escaparate(section: MarketSection) = MarketState(
        section = section,
        products = UiAsync.Success(productList),
        services = editor(ServiceSpec, serviceList),
        promotions = editor(PromotionSpec, promotionList),
        popups = editor(PopupSpec, popupList),
    )

    fun escaparateSinNadaEnVigor() = escaparate(MarketSection.SERVICES).let { state ->
        state.copy(services = editor(ServiceSpec, serviceList.map { it.copy(active = false) }))
    }

    fun escaparateCaido() = MarketState(
        section = MarketSection.PRODUCTS,
        products = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")),
    )

    fun cargando() = MarketState(section = MarketSection.SERVICES, services = MarketEditor<MarketService, ServiceDraft>(draft = ServiceDraft()).loading())
}
