package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.ui.components.AppIcon
import com.oposicontrol.admin.core.ui.components.DesktopShell
import com.oposicontrol.admin.core.ui.components.ShellNavigationGroup
import com.oposicontrol.admin.core.ui.components.ShellNavigationItem
import kotlin.test.Test

/** Evidencia del movimiento de Catalogo y OposiMarket. Se ejecuta con `--rerun`; escribe en `features/store/build/motion-evidence/`. */
class StoreMotionEvidence {
    private val catalog = CatalogFixtures
    private val market = MarketFixtures

    @Test
    fun catalogoAbrirOtroProducto() {
        // openSeq cambia al abrir otro registro: el cuerpo de la ficha entra.
        val before = catalog.packAbierto()
        val states = listOf(before, catalog.suscripcionAbierta().copy(openSeq = before.openSeq + 1))
        motionFilmstrip("catalogo-otro-producto") { step ->
            DesktopShell(
                currentRouteId = "commercial.catalog",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Catálogo",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                CommercialCatalogContent(state = states[step], onIntent = {})
            }
        }
    }

    @Test
    fun catalogoGuardado() {
        // Guardar: la barra pasa de «cambios sin guardar» a «guardado» con un fundido; el formulario NO vuelve a entrar.
        val states = listOf(catalog.packConCambios(), catalog.guardado())
        motionFilmstrip("catalogo-guardado") { step ->
            DesktopShell(
                currentRouteId = "commercial.catalog",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Catálogo",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                CommercialCatalogContent(state = states[step], onIntent = {})
            }
        }
    }

    @Test
    fun catalogoPestana() {
        val before = catalog.suscripcionAbierta()
        val states = listOf(before, catalog.tarifas())
        motionFilmstrip("catalogo-pestana-tarifas") { step ->
            DesktopShell(
                currentRouteId = "commercial.catalog",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "Catálogo",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                CommercialCatalogContent(state = states[step], onIntent = {})
            }
        }
    }

    @Test
    fun marketCargaYSeccion() {
        // Cargando -> datos: la cara entra una vez. Los cuatro editores comparten ficha y, con ella, movimiento.
        val states = listOf(market.cargando(), market.packs())
        motionFilmstrip("market-cargando-a-packs") { step ->
            DesktopShell(
                currentRouteId = "commercial.market",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "OposiMarket",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                MarketContent(state = states[step], onIntent = {}, onOpenUser = {})
            }
        }
    }

    @Test
    fun marketOtraSeccion() {
        val states = listOf(market.packs(), market.servicios())
        motionFilmstrip("market-packs-a-servicios") { step ->
            DesktopShell(
                currentRouteId = "commercial.market",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "OposiMarket",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                MarketContent(state = states[step], onIntent = {}, onOpenUser = {})
            }
        }
    }

    @Test
    fun marketPromocionesYAvisos() {
        val states = listOf(market.promociones(), market.popups())
        motionFilmstrip("market-promociones-a-avisos") { step ->
            DesktopShell(
                currentRouteId = "commercial.market",
                groups = EVIDENCE_SIDEBAR,
                onRouteSelected = {},
                environmentLabel = "Entorno production",
                title = "OposiMarket",
                description = "Evidencia de movimiento.",
                globalSearch = {},
            ) {
                MarketContent(state = states[step], onIntent = {}, onOpenUser = {})
            }
        }
    }
}

private val EVIDENCE_SIDEBAR = listOf(
    ShellNavigationGroup("content", "Contenido", listOf(ShellNavigationItem("content.exams", "Exámenes", AppIcon.EXAMS.key), ShellNavigationItem("content.questions", "Preguntas", AppIcon.QUESTIONS.key))),
    ShellNavigationGroup("users", "Usuarios", listOf(ShellNavigationItem("users", "Usuarios", AppIcon.USERS.key))),
    ShellNavigationGroup("economy", "Monetización", listOf(ShellNavigationItem("commercial.catalog", "Catálogo", AppIcon.CATALOG.key), ShellNavigationItem("commercial.market", "OposiMarket", AppIcon.MARKET.key))),
)
