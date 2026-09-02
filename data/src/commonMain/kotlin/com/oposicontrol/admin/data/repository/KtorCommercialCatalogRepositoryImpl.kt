package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository
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
import kotlinx.serialization.Serializable

/**
 * Catálogo comercial a través del límite canónico de Ktor.
 *
 * No habla con Supabase directamente a propósito: son datos de dinero, y las
 * mutaciones privilegiadas viven detrás de Ktor con la service role, nunca en el
 * cliente.
 */
class KtorCommercialCatalogRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : CommercialCatalogRepository {

    override suspend fun getProducts(): AppResult<List<CommercialProduct>> = adapter.safeCall {
        val endpoint = ADMIN_V1_COMERCIAL_PRODUCTOS
        val response = client.get(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<ProductosResponse>().data.map { it.toDomain() }
    }

    override suspend fun saveProduct(product: CommercialProduct): AppResult<CommercialProduct> = adapter.safeCall {
        val endpoint = ADMIN_V1_COMERCIAL_PRODUCTOS
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(product.toDto())
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<ProductoResponse>().data.toDomain()
    }

    override suspend fun saveTariff(productId: String, tariff: CommercialTariff): AppResult<CommercialTariff> =
        adapter.safeCall {
            val endpoint = "$ADMIN_V1_COMERCIAL_PRODUCTOS/$productId/tarifas"
            val response = client.post(url(endpoint)) {
                adminAuthHeaders(appConfig, sessionLocalDataSource)
                contentType(ContentType.Application.Json)
                setBody(tariff.toDto(productId))
                expectSuccess = false
            }
            response.requireSuccess(endpoint)
            response.body<TarifaResponse>().data.toDomain()
        }

    private fun url(endpoint: String) = BackendRoutes.url(appConfig.baseUrl, endpoint)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
    }

    private companion object {
        const val ADMIN_V1_COMERCIAL_PRODUCTOS = "/api/v1/admin/comercial/productos"
    }
}

@Serializable
private data class ProductosResponse(val data: List<ProductoComercialDto> = emptyList())

@Serializable
private data class ProductoResponse(val data: ProductoComercialDto)

@Serializable
private data class TarifaResponse(val data: TarifaProductoDto)

@Serializable
private data class ProductoComercialDto(
    val id: String = "",
    val codigo: String = "",
    val tipoProducto: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    val planId: String? = null,
    val precioCentimos: Int? = null,
    val moneda: String = "EUR",
    val cantidadTickets: Int? = null,
    val activo: Boolean = true,
    val visible: Boolean = true,
    val destacado: Boolean = false,
    val etiquetaPromocional: String? = null,
    val posicion: Int = 1,
    val tarifas: List<TarifaProductoDto> = emptyList(),
)

@Serializable
private data class TarifaProductoDto(
    val id: String = "",
    val productoId: String = "",
    val periodoFacturacion: String = "",
    val fasePrecio: String = "",
    val precioCentimos: Int = 0,
    val moneda: String = "EUR",
    val activo: Boolean = true,
    val visible: Boolean = true,
    val posicion: Int = 1,
)

private fun ProductoComercialDto.toDomain() = CommercialProduct(
    id = id,
    code = codigo,
    productType = tipoProducto,
    name = nombre,
    description = descripcion,
    planId = planId,
    priceCents = precioCentimos,
    currency = moneda,
    ticketAmount = cantidadTickets,
    active = activo,
    visible = visible,
    highlighted = destacado,
    promotionalLabel = etiquetaPromocional,
    position = posicion,
    tariffs = tarifas.map { it.toDomain() },
)

private fun TarifaProductoDto.toDomain() = CommercialTariff(
    id = id,
    productId = productoId,
    billingPeriod = periodoFacturacion,
    pricePhase = fasePrecio,
    priceCents = precioCentimos,
    currency = moneda,
    active = activo,
    visible = visible,
    position = posicion,
)

private fun CommercialProduct.toDto() = ProductoComercialDto(
    id = id,
    codigo = code,
    tipoProducto = productType,
    nombre = name,
    descripcion = description,
    planId = planId,
    precioCentimos = priceCents,
    moneda = currency,
    cantidadTickets = ticketAmount,
    activo = active,
    visible = visible,
    destacado = highlighted,
    etiquetaPromocional = promotionalLabel,
    posicion = position,
)

private fun CommercialTariff.toDto(productId: String) = TarifaProductoDto(
    id = id,
    productoId = productId,
    periodoFacturacion = billingPeriod,
    fasePrecio = pricePhase,
    precioCentimos = priceCents,
    moneda = currency,
    activo = active,
    visible = visible,
    posicion = position,
)
