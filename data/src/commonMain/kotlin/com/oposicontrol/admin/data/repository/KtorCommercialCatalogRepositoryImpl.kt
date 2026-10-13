package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.CommercialPlan
import com.oposicontrol.admin.domain.model.CommercialProduct
import com.oposicontrol.admin.domain.model.CommercialTariff
import com.oposicontrol.admin.domain.model.PlanBenefit
import com.oposicontrol.admin.domain.repository.CommercialCatalogRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.ktor.client.request.delete

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
        allPages(ADMIN_V1_COMERCIAL_PRODUCTOS) { response -> response.body<ProductosResponse>().let { CatalogSlice(it.rows(), it.total) } }
            .map { it.toDomain() }
    }

    override suspend fun getPlans(): AppResult<List<CommercialPlan>> = adapter.safeCall {
        allPages(ADMIN_V1_PLANES) { response -> response.body<PlanesResponse>().let { CatalogSlice(it.rows(), it.total) } }
            .map { it.toDomain() }
    }

    override suspend fun savePlan(plan: CommercialPlan): AppResult<CommercialPlan> = adapter.safeCall {
        val endpoint = ADMIN_V1_PLANES
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(plan.toDto())
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<PlanResponse>().data.toDomain()
    }

    override suspend fun savePlanBenefit(planId: String, benefit: PlanBenefit): AppResult<PlanBenefit> = adapter.safeCall {
        val endpoint = "$ADMIN_V1_PLANES/$planId/beneficios"
        val response = client.post(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            contentType(ContentType.Application.Json)
            setBody(benefit.toDto(planId))
            expectSuccess = false
        }
        response.requireSuccess(endpoint)
        response.body<PlanBenefitResponse>().data.toDomain()
    }

    /**
     * Recorre una lectura de catálogo página a página (`limit`/`offset`) hasta el `total` que declara el servidor.
     *
     * Sin `total` (un backend anterior a la paginación) se para cuando una página viene incompleta. [MAX_ITEMS] es un tope
     * de seguridad: un catálogo comercial con miles de filas sería un error de datos, no algo que traer entero.
     */
    private suspend fun <T> allPages(endpoint: String, read: suspend (HttpResponse) -> CatalogSlice<T>): List<T> {
        val out = mutableListOf<T>()
        while (out.size < MAX_ITEMS) {
            val response = client.get(url(endpoint)) {
                adminAuthHeaders(appConfig, sessionLocalDataSource)
                parameter("limit", PAGE_SIZE)
                parameter("offset", out.size)
                expectSuccess = false
            }
            response.requireSuccess(endpoint)
            val slice = read(response)
            out += slice.items
            val total = slice.total
            val done = slice.items.isEmpty() || (if (total != null) out.size >= total else slice.items.size < PAGE_SIZE)
            if (done) break
        }
        return out
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

    override suspend fun deleteProduct(productId: String): AppResult<Unit> = adapter.safeCall {
        val endpoint = "$ADMIN_V1_COMERCIAL_PRODUCTOS/$productId"
        client.delete(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }.requireSuccess(endpoint)
    }

    override suspend fun deleteTariff(productId: String, tariffId: String): AppResult<Unit> = adapter.safeCall {
        val endpoint = "$ADMIN_V1_COMERCIAL_PRODUCTOS/$productId/tarifas/$tariffId"
        client.delete(url(endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }.requireSuccess(endpoint)
    }

    private fun url(endpoint: String) = BackendRoutes.url(appConfig.baseUrl, endpoint)

    private suspend fun HttpResponse.requireSuccess(endpoint: String) {
        if (!status.isSuccess()) {
            error("backend ${status.value} $endpoint: ${bodyAsText().take(240)}")
        }
    }

    private companion object {
        const val ADMIN_V1_COMERCIAL_PRODUCTOS = "/api/v1/admin/comercial/productos"
        const val ADMIN_V1_PLANES = "/api/v1/admin/planes"

        /** Máximo que admite el backend por página (`AdminCatalogQueries.MAX_LIMIT`). */
        const val PAGE_SIZE = 100
        const val MAX_ITEMS = 5_000
    }
}

private class CatalogSlice<T>(val items: List<T>, val total: Int?)

/** Página OFFSET del backend. `data` es el duplicado antiguo de `items`: se lee el que venga relleno. */
@Serializable
private data class ProductosResponse(
    val items: List<ProductoComercialDto> = emptyList(),
    val data: List<ProductoComercialDto> = emptyList(),
    val total: Int? = null,
) {
    fun rows(): List<ProductoComercialDto> = items.ifEmpty { data }
}

@Serializable
private data class PlanesResponse(
    val items: List<PlanDto> = emptyList(),
    val data: List<PlanDto> = emptyList(),
    val total: Int? = null,
) {
    fun rows(): List<PlanDto> = items.ifEmpty { data }
}

@Serializable
private data class PlanResponse(val data: PlanDto)

@Serializable
private data class PlanBenefitResponse(val data: PlanBenefitDto)

@Serializable
private data class PlanDto(
    val id: String = "",
    val codigo: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    val nivel: Int = 0,
    val activo: Boolean = true,
    val visible: Boolean = true,
    val posicion: Int = 1,
    val beneficios: List<PlanBenefitDto> = emptyList(),
)

/** Nombres de campo en snake_case: así los emite y los espera el backend para los beneficios. */
@Serializable
private data class PlanBenefitDto(
    val id: String = "",
    @SerialName("plan_id") val planId: String = "",
    @SerialName("codigo_beneficio") val codigoBeneficio: String = "",
    @SerialName("valor_boolean") val valorBoolean: Boolean? = null,
    @SerialName("valor_integer") val valorInteger: Int? = null,
    @SerialName("valor_numeric") val valorNumeric: Double? = null,
    @SerialName("valor_text") val valorText: String? = null,
)

private fun PlanDto.toDomain() = CommercialPlan(
    id = id,
    code = codigo,
    name = nombre,
    description = descripcion,
    level = nivel,
    active = activo,
    visible = visible,
    position = posicion,
    benefits = beneficios.map { it.toDomain() },
)

private fun PlanBenefitDto.toDomain() = PlanBenefit(
    id = id,
    planId = planId,
    code = codigoBeneficio,
    booleanValue = valorBoolean,
    integerValue = valorInteger,
    numericValue = valorNumeric,
    textValue = valorText,
)

/** Los beneficios no viajan en el guardado del plan: tienen su propia ruta. */
private fun CommercialPlan.toDto() = PlanDto(
    id = id,
    codigo = code,
    nombre = name,
    descripcion = description,
    nivel = level,
    activo = active,
    visible = visible,
    posicion = position,
)

private fun PlanBenefit.toDto(planId: String) = PlanBenefitDto(
    id = id,
    planId = planId,
    codigoBeneficio = code,
    valorBoolean = booleanValue,
    valorInteger = integerValue,
    valorNumeric = numericValue,
    valorText = textValue,
)

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
