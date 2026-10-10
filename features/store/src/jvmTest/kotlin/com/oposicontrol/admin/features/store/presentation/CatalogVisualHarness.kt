package com.oposicontrol.admin.features.store.presentation

import com.oposicontrol.admin.core.common.presentation.UiAsync
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff
import com.oposicontrol.admin.domain.model.PlanBenefit
import kotlin.test.Test

/**
 * Banco de pruebas VISUAL del Catálogo (Productos y Planes).
 *
 * Renderiza las pantallas reales —los mismos `CommercialCatalogContent` y `CommercialPlansContent` de producción— contra
 * estados fabricados y escribe PNG en `features/store/build/visual-harness/`. Sirve para revisar jerarquía, densidad, tema
 * y responsive sin levantar Ktor ni tocar producción.
 *
 * Se ejecuta con `--rerun`; sin él Gradle da la tarea por hecha y no reescribe ningún PNG.
 */
class CatalogVisualHarness {
    private val fx = CatalogFixtures

    @Test
    fun capturaProductos() {
        products("catalogo-productos-1920-light.png", fx.packAbierto())
        products("catalogo-productos-1920-dark.png", fx.packAbierto(), dark = true)
        products("catalogo-productos-1920-light-cambios.png", fx.packConCambios())
        products("catalogo-productos-1920-light-invalido.png", fx.packInvalido())
        products("catalogo-productos-1920-light-nuevo.png", fx.productoNuevo())
        products("catalogo-productos-1920-light-suscripcion.png", fx.suscripcionAbierta(), height = 1500)
        products("catalogo-productos-1920-light-tarifas.png", fx.tarifas())
        products("catalogo-productos-1920-light-tarifa-edicion.png", fx.tarifaEnEdicion(), height = 1500)
        products("catalogo-productos-1920-light-salir-con-cambios.png", fx.saliendoConCambios())
        products("catalogo-productos-1920-light-error-guardado.png", fx.errorAlGuardar())
        products("catalogo-productos-1920-light-guardado.png", fx.guardado())
        products("catalogo-productos-1920-light-actualizando.png", fx.packAbierto().copy(refreshing = true))
    }

    @Test
    fun capturaLosEstadosDeProductos() {
        products("catalogo-productos-1920-light-cargando.png", fx.cargando())
        products("catalogo-productos-1920-light-vacio.png", fx.vacio())
        products("catalogo-productos-1920-light-error.png", fx.caido())
        products("catalogo-productos-1920-light-sin-resultados.png", fx.sinResultados())
        products("catalogo-productos-1920-light-paginas.png", fx.muchasPaginas())
    }

    @Test
    fun capturaPlanes() {
        plans("catalogo-planes-1920-light.png", fx.planAbierto())
        plans("catalogo-planes-1920-dark.png", fx.planAbierto(), dark = true)
        plans("catalogo-planes-1920-light-beneficios.png", fx.beneficios())
        plans("catalogo-planes-1920-light-beneficio-edicion.png", fx.beneficioEnEdicion(), height = 1400)
        plans("catalogo-planes-1920-light-beneficio-nuevo.png", fx.beneficioNuevo(), height = 1500)
        plans("catalogo-planes-1920-light-nuevo.png", fx.planNuevo())
        plans("catalogo-planes-1920-light-visible-inactivo.png", fx.planVisibleInactivo(), height = 1300)
        plans("catalogo-planes-1920-light-cargando.png", fx.planesCargando())
        plans("catalogo-planes-1920-light-vacio.png", fx.planesVacio())
        plans("catalogo-planes-1920-light-error.png", fx.planesCaidos())
    }

    @Test
    fun capturaLosAnchos() {
        products("catalogo-productos-1600-light.png", fx.packAbierto(), width = 1600, height = 900)
        products("catalogo-productos-1366-light.png", fx.packAbierto(), width = 1366, height = 768)
        products("catalogo-productos-1080-light.png", fx.packAbierto(), width = 1080, height = 2400)
        plans("catalogo-planes-1366-light.png", fx.beneficios(), width = 1366, height = 768)
        plans("catalogo-planes-1080-light.png", fx.planAbierto(), width = 1080, height = 2200)
    }

    private fun products(file: String, state: CommercialCatalogState, width: Int = 1920, height: Int = 1080, dark: Boolean = false) {
        renderStoreCapture(StoreCapture(file, width, height, dark), catalogHub("products")) {
            CommercialCatalogContent(state = state, onIntent = {})
        }
    }

    private fun plans(file: String, state: CommercialPlansState, width: Int = 1920, height: Int = 1080, dark: Boolean = false) {
        renderStoreCapture(StoreCapture(file, width, height, dark), catalogHub("plans")) {
            CommercialPlansContent(state = state, onIntent = {})
        }
    }
}

/** Estados fabricados. Los nombres y cifras son de ejemplo: nada de esto sale de un backend. */
internal object CatalogFixtures {
    private const val PLAN_RAZONADO = "aaaaaaaa-0000-4000-8000-000000000002"
    private const val PACK = "bbbbbbbb-0000-4000-8000-000000000001"
    private const val SUB = "bbbbbbbb-0000-4000-8000-000000000004"

    val plans: List<CommercialPlan> = listOf(
        CommercialPlan("aaaaaaaa-0000-4000-8000-000000000000", "gratuito", "Gratuito", "Acceso básico a la app.", 0, position = 1),
        CommercialPlan("aaaaaaaa-0000-4000-8000-000000000001", "rafagas", "Ráfagas", "Tests cortos sin límite.", 1, position = 2),
        CommercialPlan(
            id = PLAN_RAZONADO,
            code = "razonado",
            name = "Razonado",
            description = "Explicaciones razonadas en cada pregunta.",
            level = 2,
            position = 3,
            benefits = listOf(
                PlanBenefit("b1", PLAN_RAZONADO, "descuento_market", numericValue = 0.15),
                PlanBenefit("b2", PLAN_RAZONADO, "examenes_mensuales", integerValue = 10),
                PlanBenefit("b3", PLAN_RAZONADO, "explicaciones_razonadas", booleanValue = true),
                PlanBenefit("b4", PLAN_RAZONADO, "insignia", textValue = "Razonador"),
                PlanBenefit("b5", PLAN_RAZONADO, "sin_publicidad", booleanValue = false),
            ),
        ),
        CommercialPlan("aaaaaaaa-0000-4000-8000-000000000003", "inmersivo", "Inmersivo", null, 3, active = true, visible = false, position = 4),
    )

    private val tariffs = listOf(
        CommercialTariff("t1", SUB, "mensual", "first_year", 299, position = 1),
        CommercialTariff("t2", SUB, "mensual", "renewal", 499, position = 2),
        CommercialTariff("t3", SUB, "anual", "standard", 3990, active = false, visible = false, position = 3),
    )

    val products: List<CommercialProduct> = listOf(
        CommercialProduct(PACK, "pack_2600", "pack_tickets", "2.600 OposiTickets", "El pack que más compensa.", priceCents = 1999, ticketAmount = 2600, highlighted = true, promotionalLabel = "Mejor valor", position = 3),
        CommercialProduct("bbbbbbbb-0000-4000-8000-000000000002", "pack_500", "pack_tickets", "500 OposiTickets", priceCents = 499, ticketAmount = 500, position = 1),
        CommercialProduct("bbbbbbbb-0000-4000-8000-000000000003", "pack_1200", "pack_tickets", "1.200 OposiTickets", priceCents = 999, ticketAmount = 1200, visible = false, position = 2),
        CommercialProduct(SUB, "sub_razonado", "suscripcion", "Suscripción Razonado", "Plan Razonado con renovación automática.", planId = PLAN_RAZONADO, position = 4, tariffs = tariffs),
        CommercialProduct("bbbbbbbb-0000-4000-8000-000000000005", "solucionario_madrid_2027", "compra_examen", "Solucionario Madrid 2027", priceCents = 300, active = false, position = 5),
        CommercialProduct("bbbbbbbb-0000-4000-8000-000000000006", "revision_examen", "servicio", "Revisión de examen", priceCents = 1500, position = 6),
    )

    private fun base() = CommercialCatalogState(
        products = UiAsync.Success(products),
        plans = plans,
        selectedId = PACK,
        draft = products.first().toDraft(),
    )

    fun packAbierto() = base()

    fun packConCambios() = base().let { it.copy(draft = it.draft.copy(price = "17,99", promotionalLabel = "Oferta de otoño")) }

    fun packInvalido() = base().let { it.copy(draft = it.draft.copy(price = "17,999")) }

    fun productoNuevo() = base().copy(selectedId = null, creatingNew = true, draft = ProductDraft(code = "pack_5000", name = "5.000 OposiTickets", type = "pack_tickets", price = "34,99", ticketAmount = "5000"))

    fun suscripcionAbierta() = base().copy(selectedId = SUB, draft = products[3].toDraft())

    fun tarifas() = suscripcionAbierta().copy(tab = ProductTab.TARIFFS)

    fun tarifaEnEdicion() = tarifas().copy(tariffDraft = tariffs[1].toDraft().copy(price = "5,49"))

    fun saliendoConCambios() = packConCambios().copy(pendingOpen = PendingOpen(SUB))

    fun errorAlGuardar() = packConCambios().copy(actionState = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")))

    fun guardado() = base().copy(actionState = UiAsync.Success(Unit))

    fun cargando() = CommercialCatalogState()

    fun vacio() = CommercialCatalogState(products = UiAsync.Empty)

    fun caido() = CommercialCatalogState(products = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")))

    fun sinResultados() = base().copy(searchQuery = "zaragoza", typeFilter = "servicio")

    fun muchasPaginas(): CommercialCatalogState {
        val many = List(230) { index ->
            products[index % products.size].copy(id = "p$index", code = "producto_${index + 1}", name = "Producto de ejemplo ${index + 1}")
        }
        return CommercialCatalogState(products = UiAsync.Success(many), plans = plans, selectedId = "p12", draft = many[12].toDraft(), pageIndex = 1)
    }

    private fun plansBase() = CommercialPlansState(
        plans = UiAsync.Success(plans),
        selectedId = PLAN_RAZONADO,
        draft = plans[2].toDraft(),
    )

    fun planAbierto() = plansBase()

    fun beneficios() = plansBase().copy(tab = PlanTab.BENEFITS)

    fun beneficioEnEdicion() = beneficios().copy(benefitDraft = plans[2].benefits[1].toDraft().copy(text = "15"))

    fun beneficioNuevo() = beneficios().copy(benefitDraft = BenefitDraft(code = "simulacros_extra", kind = BenefitKind.INTEGER, text = "3"))

    fun planNuevo() = plansBase().copy(selectedId = null, creatingNew = true, draft = PlanDraft(code = "premium", name = "Premium", level = 3, position = "5"))

    fun planVisibleInactivo() = plansBase().let { it.copy(draft = it.draft.copy(active = false, visible = true)) }

    fun planesCargando() = CommercialPlansState()

    fun planesVacio() = CommercialPlansState(plans = UiAsync.Empty)

    fun planesCaidos() = CommercialPlansState(plans = UiAsync.Error(AppError.Network("No se pudo contactar con el servidor.")))
}
