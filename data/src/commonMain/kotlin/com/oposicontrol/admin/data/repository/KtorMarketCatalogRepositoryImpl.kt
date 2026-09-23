package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.MarketPopupRule
import com.oposicontrol.admin.domain.model.MarketPromotion
import com.oposicontrol.admin.domain.model.MarketService
import com.oposicontrol.admin.domain.model.MarketTicketPack
import com.oposicontrol.admin.domain.repository.MarketCatalogRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OposiMarket a través del límite canónico de Ktor.
 *
 * Nada de Supabase directo: son datos comerciales y de dinero, y las mutaciones
 * privilegiadas viven detrás de Ktor con la service role.
 */
class KtorMarketCatalogRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : MarketCatalogRepository {

    override suspend fun getTicketPacks(): AppResult<List<MarketTicketPack>> = adapter.safeCall {
        getList<PackDto, PacksResponse>("$MARKET/packs").map { it.toDomain() }
    }

    override suspend fun saveTicketPack(pack: MarketTicketPack): AppResult<MarketTicketPack> = adapter.safeCall {
        post<PackResponse>("$MARKET/packs", pack.toDto()).data.toDomain()
    }

    override suspend fun getServices(): AppResult<List<MarketService>> = adapter.safeCall {
        getList<ServiceDto, ServicesResponse>("$MARKET/servicios").map { it.toDomain() }
    }

    override suspend fun saveService(service: MarketService): AppResult<MarketService> = adapter.safeCall {
        post<ServiceResponse>("$MARKET/servicios", service.toDto()).data.toDomain()
    }

    override suspend fun getPromotions(): AppResult<List<MarketPromotion>> = adapter.safeCall {
        getList<PromotionDto, PromotionsResponse>("$MARKET/promociones").map { it.toDomain() }
    }

    override suspend fun savePromotion(promotion: MarketPromotion): AppResult<MarketPromotion> = adapter.safeCall {
        post<PromotionResponse>("$MARKET/promociones", promotion.toDto()).data.toDomain()
    }

    override suspend fun getPopupRules(): AppResult<List<MarketPopupRule>> = adapter.safeCall {
        getList<PopupRuleDto, PopupRulesResponse>("$MARKET/popups").map { it.toDomain() }
    }

    override suspend fun savePopupRule(rule: MarketPopupRule): AppResult<MarketPopupRule> = adapter.safeCall {
        post<PopupRuleResponse>("$MARKET/popups", rule.toDto()).data.toDomain()
    }

    private suspend inline fun <reified T, reified R : ListEnvelope<T>> getList(endpoint: String): List<T> {
        val response = client.get(BackendRoutes.url(appConfig.baseUrl, endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        return response.body<R>().data
    }

    private suspend inline fun <reified R> post(endpoint: String, body: Any): R {
        val response = client.post(BackendRoutes.url(appConfig.baseUrl, endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(body)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        return response.body<R>()
    }

    suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
    }

    companion object {
        const val MARKET = "/api/v1/admin/market"
    }
}

/** Envoltura común `{ "data": [...] }` de las respuestas de listado. */
interface ListEnvelope<T> {
    val data: List<T>
}

@Serializable
data class PacksResponse(override val data: List<PackDto> = emptyList()) : ListEnvelope<PackDto>

@Serializable
data class PackResponse(val data: PackDto)

@Serializable
data class ServicesResponse(override val data: List<ServiceDto> = emptyList()) : ListEnvelope<ServiceDto>

@Serializable
data class ServiceResponse(val data: ServiceDto)

@Serializable
data class PromotionsResponse(override val data: List<PromotionDto> = emptyList()) : ListEnvelope<PromotionDto>

@Serializable
data class PromotionResponse(val data: PromotionDto)

@Serializable
data class PopupRulesResponse(override val data: List<PopupRuleDto> = emptyList()) : ListEnvelope<PopupRuleDto>

@Serializable
data class PopupRuleResponse(val data: PopupRuleDto)

@Serializable
data class PackDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    @SerialName("cantidad_tickets") val cantidadTickets: Int = 0,
    @SerialName("precio_centimos") val precioCentimos: Int = 0,
    val moneda: String = "EUR",
    val activo: Boolean = true,
    val posicion: Int = 1,
)

@Serializable
data class ServiceDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    @SerialName("tipo_servicio") val tipoServicio: String = "",
    @SerialName("precio_tickets") val precioTickets: Int? = null,
    @SerialName("precio_centimos") val precioCentimos: Int? = null,
    val activo: Boolean = true,
)

@Serializable
data class PromotionDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    @SerialName("tipo_descuento") val tipoDescuento: String = "",
    val valor: Double = 0.0,
    val activo: Boolean = true,
    @SerialName("valido_desde") val validoDesde: String? = null,
    @SerialName("valido_hasta") val validoHasta: String? = null,
)

@Serializable
data class PopupRuleDto(
    val id: String = "",
    val codigo: String = "",
    @SerialName("evento_disparador") val eventoDisparador: String = "",
    val titulo: String = "",
    val mensaje: String = "",
    @SerialName("cta_principal") val ctaPrincipal: String? = null,
    val pantalla: String? = null,
    val momento: String? = null,
    @SerialName("requiere_plan_minimo") val requierePlanMinimo: String? = null,
    @SerialName("tipo_examen_objetivo") val tipoExamenObjetivo: String? = null,
    @SerialName("frecuencia_minima_horas") val frecuenciaMinimaHoras: Int? = null,
    val prioridad: Int = 0,
    val activo: Boolean = true,
)

private fun PackDto.toDomain() = MarketTicketPack(
    id = id,
    code = codigo,
    name = nombre,
    description = descripcion,
    ticketAmount = cantidadTickets,
    priceCents = precioCentimos,
    currency = moneda,
    active = activo,
    position = posicion,
)

private fun MarketTicketPack.toDto() = PackDto(
    id = id,
    codigo = code,
    nombre = name,
    descripcion = description,
    cantidadTickets = ticketAmount,
    precioCentimos = priceCents,
    moneda = currency,
    activo = active,
    posicion = position,
)

private fun ServiceDto.toDomain() = MarketService(
    id = id,
    code = codigo,
    name = nombre,
    description = descripcion,
    serviceType = tipoServicio,
    priceTickets = precioTickets,
    priceCents = precioCentimos,
    active = activo,
)

private fun MarketService.toDto() = ServiceDto(
    id = id,
    codigo = code,
    nombre = name,
    descripcion = description,
    tipoServicio = serviceType,
    precioTickets = priceTickets,
    precioCentimos = priceCents,
    activo = active,
)

private fun PromotionDto.toDomain() = MarketPromotion(
    id = id,
    code = codigo,
    name = nombre,
    discountType = tipoDescuento,
    value = valor,
    active = activo,
    validFrom = validoDesde,
    validUntil = validoHasta,
)

private fun MarketPromotion.toDto() = PromotionDto(
    id = id,
    codigo = code,
    nombre = name,
    tipoDescuento = discountType,
    valor = value,
    activo = active,
    validoDesde = validFrom,
    validoHasta = validUntil,
)

private fun PopupRuleDto.toDomain() = MarketPopupRule(
    id = id,
    code = codigo,
    trigger = eventoDisparador,
    title = titulo,
    message = mensaje,
    primaryCta = ctaPrincipal,
    screen = pantalla,
    moment = momento,
    minimumPlan = requierePlanMinimo,
    targetExamType = tipoExamenObjetivo,
    minimumFrequencyHours = frecuenciaMinimaHoras,
    priority = prioridad,
    active = activo,
)

private fun MarketPopupRule.toDto() = PopupRuleDto(
    id = id,
    codigo = code,
    eventoDisparador = trigger,
    titulo = title,
    mensaje = message,
    ctaPrincipal = primaryCta,
    pantalla = screen,
    momento = moment,
    requierePlanMinimo = minimumPlan,
    tipoExamenObjetivo = targetExamType,
    frecuenciaMinimaHoras = minimumFrequencyHours,
    prioridad = priority,
    activo = active,
)
