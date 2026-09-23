package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.SystemStatus
import com.oposicontrol.admin.domain.model.SystemStatusAvailability
import com.oposicontrol.admin.domain.model.SystemStatusLevel
import com.oposicontrol.admin.domain.repository.SystemStatusRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.koin.core.scope.Scope

/**
 * Estado del sistema desde `GET /api/v1/admin/estado-sistema`.
 *
 * El endpoint existía y funcionaba desde antes, pero la pantalla seguía
 * inyectando [NotConnectedSystemStatusRepository] y por tanto respondía siempre
 * "sin conectar" aunque el backend tuviera la respuesta lista. Este adaptador
 * cierra ese hueco; el repositorio no conectado **no** se retira, porque sigue
 * siendo la respuesta correcta para un despliegue sin backend.
 *
 * La agregación entera vive en Ktor (logs / health → agregación → panel): aquí
 * no se calcula ningún nivel, sólo se traduce el vocabulario del cable al del
 * dominio. Un nivel que este cliente no reconozca cae en
 * [SystemStatusLevel.UNKNOWN] y jamás en `OPERATIVE`: un panel que pinta verde
 * por no entender la respuesta se mira y se cree.
 */
class KtorSystemStatusRepositoryImpl(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
    private val adapter: NetworkResultAdapter,
) : SystemStatusRepository {

    override suspend fun loadStatus(): SystemStatusAvailability {
        val result = adapter.safeCall {
            val response = client.get(BackendRoutes.url(appConfig.baseUrl, ENDPOINT)) {
                adminAuthHeaders(appConfig, sessionLocalDataSource)
                expectSuccess = false
            }
            if (!response.status.isSuccess()) {
                error("backend ${response.status.value} $ENDPOINT: ${response.bodyAsText().take(240)}")
            }
            response.body<SystemStatusResponse>().data.toDomain()
        }
        return when (result) {
            // No se degrada a NotConnected: "no he podido preguntar" y "aquí no
            // hay observabilidad" son dos cosas distintas y la pantalla las pinta
            // distinto. Confundirlas escondería una caída real tras un mensaje de
            // configuración pendiente.
            is AppResult.Failure -> SystemStatusAvailability.Failure(result.error)
            is AppResult.Success -> SystemStatusAvailability.Available(result.value)
        }
    }

    companion object {
        val ENDPOINT = BackendRoutes.ADMIN_V1_ESTADO_SISTEMA
    }
}

/**
 * Construye el adaptador resolviendo sus dependencias del grafo de `dataModule`.
 *
 * Existe para que el módulo de la pantalla pueda bindearlo sin tener en su
 * classpath `HttpClient` ni `NetworkResultAdapter`: son detalles de la capa de
 * datos, y arrastrarlos al `build.gradle.kts` de una feature sólo para poder
 * escribir cuatro `get()` sería pagar una dependencia por una firma.
 */
fun Scope.ktorSystemStatusRepository(): SystemStatusRepository =
    KtorSystemStatusRepositoryImpl(get(), get(), get(), get())

@Serializable
data class SystemStatusResponse(val data: SystemStatusDto)

/**
 * Todo lo numérico es nullable y sin relleno: el backend declara en
 * `notInstrumented` lo que todavía no tiene fuente, y un `0` de CPU o de crashes
 * se leería como "medido y está a cero", que es lo contrario de la verdad.
 */
@Serializable
data class SystemStatusDto(
    val overallStatus: String = "",
    val ktorStatus: String = "",
    val supabaseStatus: String = "",
    val workerStatus: String = "",
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    val supabaseDiskPercent: Double? = null,
    val apiLatencyMs: Long? = null,
    val errors24h: Long? = null,
    val mobileCrashes24h: Int? = null,
    val queueDepth: Int? = null,
    val updatedAt: String = "",
    val notInstrumented: List<String> = emptyList(),
)

internal fun SystemStatusDto.toDomain() = SystemStatus(
    overallStatus = overallStatus.toSystemStatusLevel(),
    ktorStatus = ktorStatus.toSystemStatusLevel(),
    supabaseStatus = supabaseStatus.toSystemStatusLevel(),
    workerStatus = workerStatus.toSystemStatusLevel(),
    cpuPercent = cpuPercent,
    ramPercent = ramPercent,
    supabaseDiskPercent = supabaseDiskPercent,
    apiLatencyMs = apiLatencyMs,
    errors24h = errors24h,
    mobileCrashes24h = mobileCrashes24h,
    queueDepth = queueDepth,
    updatedAt = updatedAt.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
)

/**
 * Un nivel que este cliente no conoce es desconocido, nunca operativo.
 *
 * `internal` y no `private` porque la pantalla de Estado y el panel de Inicio
 * tienen que leer EXACTAMENTE el mismo vocabulario. Antes eran dos copias
 * idénticas en este mismo paquete, y `KtorDashboardOverviewRepositoryImplTest`
 * .healthKeepsTheSameLevelVocabularyAsTheSystemStatusScreen existía sólo para
 * vigilar que no divergieran. Con una única función el test ya no vigila una
 * copia: comprueba la función que de verdad se usa en los dos sitios.
 */
internal fun String.toSystemStatusLevel(): SystemStatusLevel =
    SystemStatusLevel.entries.firstOrNull { it.name == trim().uppercase() } ?: SystemStatusLevel.UNKNOWN
