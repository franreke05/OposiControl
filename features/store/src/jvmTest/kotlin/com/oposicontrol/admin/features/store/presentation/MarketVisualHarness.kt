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
import com.oposicontrol.admin.domain.model.MarketTicketPack
import kotlin.test.Test

/**
 * Banco de pruebas VISUAL de los catálogos editables de OposiMarket (packs, servicios, promociones y pop-ups).
 *
 * Renderiza la pantalla real —el mismo `MarketContent` de producción— contra estados fabricados y escribe PNG en
 * `features/store/build/visual-harness/`. El escaparate de exámenes tiene su propio banco (`PublishedExamsVisualHarness`).
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por hecha y no reescribe ningún PNG.
 */
class MarketVisualHarness {
    private val fx = MarketFixtures

    @Test
    fun capturaPacksYServicios() {
        market("market-packs-1920-light.png", fx.packs())
        market("market-packs-1920-dark.png", fx.packs(), dark = true)
        market("market-packs-1920-light-nuevo.png", fx.packNuevo())
        market("market-packs-1920-light-invalido.png", fx.packInvalido())
        market("market-servicios-1920-light.png", fx.servicios(), height = 1300)
        market("market-servicios-1920-light-sin-precio.png", fx.servicioSinPrecio(), height = 1300)
        market("market-servicios-1920-light-campo-conservado.png", fx.servicioCampoConservado(), height = 1500)
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
        market("market-packs-1920-light-cargando.png", fx.cargando())
        market("market-packs-1920-light-vacio.png", fx.vacio())
        market("market-packs-1920-light-error.png", fx.caido())
        market("market-packs-1920-light-sin-resultados.png", fx.sinResultados())
        market("market-packs-1920-light-actualizando.png", fx.actualizando())
        market("market-packs-1920-light-refresco-fallido.png", fx.refrescoFallido())
        market("market-packs-1920-light-error-guardado.png", fx.errorAlGuardar(), height = 1500)
    }

    @Test
    fun capturaLosAnchos() {
        market("market-packs-1600-light.png", fx.packs(), width = 1600, height = 900)
        market("market-packs-1366-light.png", fx.packs(), width = 1366, height = 768)
        market("market-packs-1080-light.png", fx.packs(), width = 1080, height = 2200)
        market("market-promociones-1366-light-usos.png", fx.promocionUsos(), width = 1366, height = 768)
    }

    private fun market(file: String, state: MarketState, width: Int = 1920, height: Int = 1080, dark: Boolean = false) {
        renderStoreCapture(StoreCapture(file, width, height, dark), MARKET_HUB) {
            MarketContent(state = state, onIntent = {}, onOpenUser = {})
        }
    }
}

/** Market lleva su propia fila de secciones dentro de la pantalla: el banco no pinta otra encima. */
private val MARKET_HUB = HarnessHub(
    routeId = "commercial.market",
    title = "OposiMarket",
    description = "Escaparate, packs, servicios, promociones y pop-ups.",
    tabs = emptyList<DesktopTab>(),
    selectedTab = "",
)

/** Estados fabricados. Los nombres y cifras son de ejemplo: nada de esto sale de un backend. */
internal object MarketFixtures {
    private val packList = listOf(
        MarketTicketPack("p1", "pack_500", "500 OposiTickets", "Para empezar.", 500, 499, position = 1),
        MarketTicketPack("p2", "pack_1200", "1.200 OposiTickets", null, 1200, 999, position = 2),
        MarketTicketPack("p3", "pack_2600", "2.600 OposiTickets", "El que más compensa.", 2600, 1999, position = 3),
        MarketTicketPack("p4", "pack_navidad", "Pack de Navidad", null, 800, 599, active = false, position = 4),
    )

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

    fun packs() = MarketState(section = MarketSection.PACKS, packs = editor(PackSpec, packList).reduce(PackSpec, EditorAction.Open("p3")))

    fun packNuevo() = packs().let { state ->
        state.copy(
            packs = state.packs.reduce(PackSpec, EditorAction.Open(null))
                .reduce(PackSpec, EditorAction.UpdateDraft(PackDraft(code = "pack_5000", name = "5.000 OposiTickets", ticketAmount = "5000", price = "34,99", position = "5"))),
        )
    }

    fun packInvalido() = packs().let { state ->
        state.copy(packs = state.packs.reduce(PackSpec, EditorAction.UpdateDraft(state.packs.draft.copy(ticketAmount = "0"))))
    }

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

    fun cargando() = MarketState(section = MarketSection.PACKS, packs = MarketEditor<MarketTicketPack, PackDraft>(draft = PackDraft()).loading())

    fun vacio() = MarketState(section = MarketSection.PACKS, packs = editor(PackSpec, emptyList()))

    fun caido() = MarketState(
        section = MarketSection.PACKS,
        packs = MarketEditor<MarketTicketPack, PackDraft>(draft = PackDraft()).loaded(PackSpec, AppResult.Failure(AppError.Network("No se pudo contactar con el servidor."))),
    )

    fun sinResultados() = packs().let { state ->
        state.copy(packs = state.packs.reduce(PackSpec, EditorAction.UpdateSearch("zaragoza")).reduce(PackSpec, EditorAction.UpdateFilter(FILTER_INACTIVE)))
    }

    fun actualizando() = packs().let { it.copy(packs = it.packs.loading()) }

    fun refrescoFallido() = packs().let {
        it.copy(packs = it.packs.loading().loaded(PackSpec, AppResult.Failure(AppError.Network("No se pudo contactar con el servidor."))))
    }

    fun errorAlGuardar() = packs().let { state ->
        val editing = state.packs.reduce(PackSpec, EditorAction.UpdateDraft(state.packs.draft.copy(price = "17,99")))
        state.copy(packs = editing.saveFailed(AppError.Network("No se pudo contactar con el servidor.")))
    }
}
