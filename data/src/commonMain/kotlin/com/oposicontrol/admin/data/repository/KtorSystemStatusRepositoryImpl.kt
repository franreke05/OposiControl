package com.oposicontrol.admin.data.repository

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.core.network.NetworkResultAdapter
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.data.remote.api.BackendRoutes
import com.oposicontrol.admin.data.remote.api.adminAuthHeaders
import com.oposicontrol.admin.domain.model.AdminSessionInfo
import com.oposicontrol.admin.domain.model.DeployedVersion
import com.oposicontrol.admin.domain.model.SupabaseProjectionBasis
import com.oposicontrol.admin.domain.model.SupabaseQuota
import com.oposicontrol.admin.domain.model.SupabaseQuotaTarget
import com.oposicontrol.admin.domain.model.SystemAlert
import com.oposicontrol.admin.domain.model.SystemMetric
import com.oposicontrol.admin.domain.model.SystemReplica
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

    /**
     * Los roles salen de aquí y no de la sesión guardada en el equipo: aquella lleva los del día en que se inició sesión
     * y la escribe el propio cliente. Un 403 (un token que no es de una persona) llega como error de autorización, no
     * como una sesión vacía.
     */
    override suspend fun loadSession(): AppResult<AdminSessionInfo> = adapter.safeCall {
        val response = client.get(BackendRoutes.url(appConfig.baseUrl, SESSION_ENDPOINT)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
            expectSuccess = false
        }
        if (!response.status.isSuccess()) {
            error("backend ${response.status.value} $SESSION_ENDPOINT: ${response.bodyAsText().take(240)}")
        }
        response.body<AdminSessionDto>().toSessionInfo()
    }

    companion object {
        val ENDPOINT = BackendRoutes.ADMIN_V1_ESTADO_SISTEMA
        val SESSION_ENDPOINT = BackendRoutes.ADMIN_V1_SESION
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
 * Todo lo que puede faltar es nullable y sin relleno: el backend declara en
 * `notInstrumented` lo que no ha podido leer, y un `0` de CPU o una lista vacía
 * de réplicas se leerían como "medido y está a cero", que es lo contrario de la
 * verdad.
 *
 * `workerStatus`, `mobileCrashes24h` y `queueDepth` ya no se declaran: el backend
 * dejó de enviarlos porque no existe nada que medir, y un backend anterior que
 * aún los mande se ignora (`ignoreUnknownKeys`).
 */
@Serializable
data class SystemStatusDto(
    val overallStatus: String = "",
    val ktorStatus: String = "",
    val supabaseStatus: String = "",
    val cpuPercent: Double? = null,
    val ramPercent: Double? = null,
    val supabaseDiskPercent: Double? = null,
    val apiLatencyMs: Long? = null,
    val errors24h: Long? = null,
    val updatedAt: String = "",
    val notInstrumented: List<String> = emptyList(),
    val replicas: List<SystemReplicaDto>? = null,
    val alertas: List<SystemAlertDto>? = null,
    val cuotaSupabase: SupabaseQuotaDto? = null,
    val version: SystemVersionDto? = null,
    val fuente: SystemSourceDto? = null,
)

@Serializable
data class SystemReplicaDto(
    val nombre: String = "",
    val perfil: String? = null,
    val arriba: Boolean = false,
    val veSupabase: Boolean? = null,
    val version: String? = null,
    val arrancadaEn: String? = null,
)

@Serializable
data class SystemAlertDto(
    val nombre: String = "",
    val severidad: String? = null,
    val resumen: String? = null,
    val replica: String? = null,
)

@Serializable
data class SupabaseQuotaDto(
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
    val proyeccionMensualBytes: Long? = null,
    val proyeccionBase: String? = null,
    val limiteMensualBytes: Long? = null,
    val porDestino: List<SupabaseQuotaTargetDto> = emptyList(),
)

@Serializable
data class SupabaseQuotaTargetDto(
    val destino: String = "",
    val bytes24h: Long? = null,
    val bytes7d: Long? = null,
)

@Serializable
data class SystemVersionDto(
    val sha: String? = null,
    val replicasDifieren: Boolean = false,
)

@Serializable
data class SystemSourceDto(
    val prometheusResponde: Boolean? = null,
)

/** `GET /api/v1/admin/sesion`. Sin envoltorio `data`: el backend responde el objeto tal cual. */
@Serializable
data class AdminSessionDto(
    val usuarioId: String = "",
    val nombre: String? = null,
    val roles: List<String> = emptyList(),
    val esSuperadmin: Boolean = false,
    val esAdmin: Boolean = false,
)

internal fun SystemStatusDto.toDomain() = SystemStatus(
    overallStatus = overallStatus.toSystemStatusLevel(),
    ktorStatus = ktorStatus.toSystemStatusLevel(),
    supabaseStatus = supabaseStatus.toSystemStatusLevel(),
    cpuPercent = cpuPercent,
    ramPercent = ramPercent,
    supabaseDiskPercent = supabaseDiskPercent,
    apiLatencyMs = apiLatencyMs,
    errors24h = errors24h,
    updatedAt = updatedAt.toInstantOrNull(),
    // Una fila sin nombre no identifica a ninguna réplica: se descarta antes que pintar una fila en blanco.
    replicas = replicas?.filter { it.nombre.isNotBlank() }?.map { replica ->
        SystemReplica(
            name = replica.nombre,
            profile = replica.perfil?.takeIf { it.isNotBlank() },
            up = replica.arriba,
            seesSupabase = replica.veSupabase,
            version = replica.version?.takeIf { it.isNotBlank() },
            startedAt = replica.arrancadaEn.toInstantOrNull(),
        )
    },
    alerts = alertas?.filter { it.nombre.isNotBlank() }?.map { alert ->
        SystemAlert(
            name = alert.nombre,
            severity = alert.severidad?.takeIf { it.isNotBlank() },
            summary = alert.resumen?.takeIf { it.isNotBlank() },
            replica = alert.replica?.takeIf { it.isNotBlank() },
        )
    },
    supabaseQuota = cuotaSupabase?.let { quota ->
        SupabaseQuota(
            bytes24h = quota.bytes24h,
            bytes7d = quota.bytes7d,
            projectedMonthlyBytes = quota.proyeccionMensualBytes,
            projectionBasis = when (quota.proyeccionBase) {
                "7d" -> SupabaseProjectionBasis.SEVEN_DAYS
                "24h" -> SupabaseProjectionBasis.LAST_DAY
                else -> null
            },
            // Un límite que no es positivo no es un límite: la barra dividiría entre cero.
            monthlyLimitBytes = quota.limiteMensualBytes?.takeIf { it > 0 },
            byTarget = quota.porDestino.filter { it.destino.isNotBlank() }
                .map { SupabaseQuotaTarget(it.destino, it.bytes24h, it.bytes7d) },
        )
    },
    deployedVersion = version?.let { DeployedVersion(sha = it.sha?.takeIf(String::isNotBlank), replicasDiffer = it.replicasDifieren) },
    prometheusResponding = fuente?.prometheusResponde,
    // Una clave que este cliente no conoce se ignora: no puede señalar una métrica que la pantalla no pinta.
    notInstrumented = notInstrumented.mapNotNull(NOT_INSTRUMENTED_KEYS::get).toSet(),
)

/** Las claves de `SystemStatusNotInstrumented` del backend que corresponden a una métrica de esta pantalla. */
private val NOT_INSTRUMENTED_KEYS: Map<String, SystemMetric> = mapOf(
    "cpuPercent" to SystemMetric.CPU,
    "ramPercent" to SystemMetric.MEMORY,
    "supabaseDiskPercent" to SystemMetric.SUPABASE_DISK,
    "apiLatencyMs" to SystemMetric.API_LATENCY,
    "errors24h" to SystemMetric.API_ERRORS,
)

/** Sin marca de tiempo, o con una que no se entiende, no se inventa una. */
private fun String?.toInstantOrNull(): Instant? =
    this?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun AdminSessionDto.toSessionInfo() = AdminSessionInfo(
    userId = usuarioId,
    displayName = nombre?.trim()?.takeIf { it.isNotEmpty() },
    roles = roles.map { it.trim() }.filter { it.isNotEmpty() },
    isSuperAdmin = esSuperadmin,
    isAdmin = esAdmin,
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
