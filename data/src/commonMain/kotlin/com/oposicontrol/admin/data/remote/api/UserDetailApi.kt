package com.oposicontrol.admin.data.remote.api

import com.oposicontrol.admin.core.common.config.AppConfig
import com.oposicontrol.admin.data.local.SessionLocalDataSource
import com.oposicontrol.admin.domain.model.AccountStatus
import com.oposicontrol.admin.domain.model.UserCompetitiveState
import com.oposicontrol.admin.domain.model.UserDetail
import com.oposicontrol.admin.domain.model.UserEconomy
import com.oposicontrol.admin.domain.model.UserExtendedProfile
import com.oposicontrol.admin.domain.model.UserPerformance
import com.oposicontrol.admin.domain.model.UserProfile
import com.oposicontrol.admin.domain.model.UserStreak
import com.oposicontrol.admin.domain.model.UserSubscription
import com.oposicontrol.admin.domain.model.UserTagWeakness
import com.oposicontrol.admin.domain.model.UserTesterState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ficha completa de usuario desde `/api/v1/admin/usuarios/{id}/detalle`.
 *
 * El backend compone el agregado; aquí sólo se traduce. Las secciones ausentes
 * llegan como `null` y se conservan como `null` — convertirlas en objetos vacíos
 * haría indistinguible "no existe" de "existe y está a cero".
 */
class UserDetailApi(
    private val client: HttpClient,
    private val appConfig: AppConfig,
    private val sessionLocalDataSource: SessionLocalDataSource,
) {
    suspend fun getUserDetail(userId: String): UserDetail {
        val endpoint = "${BackendRoutes.ADMIN_V1_USUARIOS}/$userId/detalle"
        val response = client.get(BackendRoutes.url(appConfig.baseUrl, endpoint)) {
            adminAuthHeaders(appConfig, sessionLocalDataSource)
        }
        if (!response.status.isSuccess()) {
            error("backend ${response.status.value} $endpoint: ${response.bodyAsText().take(240)}")
        }
        return response.body<UserDetailEnvelope>().data.toDomain()
    }
}

@Serializable
internal data class UserDetailEnvelope(val data: UserDetailDto)

@Serializable
internal data class UserDetailDto(
    val perfil: UserProfileDto,
    val detalle: UserExtendedProfileDto? = null,
    val rendimiento: UserPerformanceDto? = null,
    val competitivo: UserCompetitiveDto? = null,
    val racha: UserStreakDto? = null,
    val economia: UserEconomyDto? = null,
    val suscripciones: List<UserSubscriptionDto> = emptyList(),
    val opositester: UserTesterDto? = null,
    val debilidades: List<UserWeaknessDto> = emptyList(),
    val seccionesNoDisponibles: List<String> = emptyList(),
)

@Serializable
internal data class UserProfileDto(
    @SerialName("usuario_id") val usuarioId: String = "",
    /** D1-24: `perfil` es un AdminUser canonico; `id`, `fullName`, `onboardingCompleted` y `createdAt` mandan sobre el legacy. */
    val id: String? = null,
    val fullName: String? = null,
    val onboardingCompleted: Boolean? = null,
    val createdAt: String? = null,
    @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
    val username: String? = null,
    @SerialName("especialidad_principal_id") val especialidadPrincipalId: String? = null,
    @SerialName("comunidad_principal_id") val comunidadPrincipalId: String? = null,
    // MASTER5-OC-004: nullable a proposito. Un booleano ausente NO es false: defaultear a false fabricaba
    // "Onboarding: Pendiente" cuando el backend solo enviaba el campo canonico (o ninguno).
    @SerialName("es_invitado") val esInvitado: Boolean? = null,
    @SerialName("onboarding_completado") val onboardingCompletado: Boolean? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
    // D1-24: `perfil` es un AdminUser; `accountStatus` manda, `estado_cuenta` es el legacy. Ausente o desconocido = UNKNOWN.
    val accountStatus: String? = null,
    @SerialName("estado_cuenta") val estadoCuenta: String? = null,
    val authProviders: List<String> = emptyList(),
)

@Serializable
internal data class UserExtendedProfileDto(
    @SerialName("nombre_completo") val nombreCompleto: String? = null,
    val edad: Int? = null,
    val ciudad: String? = null,
    @SerialName("estado_laboral") val estadoLaboral: String? = null,
    @SerialName("centro_trabajo") val centroTrabajo: String? = null,
    @SerialName("tiempo_disponible_estudio_minutos") val tiempoDisponibleEstudioMinutos: Int? = null,
    @SerialName("franja_preferente_estudio") val franjaPreferenteEstudio: String? = null,
    @SerialName("fecha_examen_objetivo") val fechaExamenObjetivo: String? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

@Serializable
internal data class UserPerformanceDto(
    @SerialName("examenes_finalizados") val examenesFinalizados: Int = 0,
    @SerialName("preguntas_respondidas") val preguntasRespondidas: Int = 0,
    @SerialName("respuestas_correctas") val respuestasCorrectas: Int = 0,
    @SerialName("porcentaje_acierto") val porcentajeAcierto: Double = 0.0,
    @SerialName("puntos_totales") val puntosTotales: Int = 0,
    val nivel: Int = 1,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

@Serializable
internal data class UserCompetitiveDto(
    val ligaCodigo: String? = null,
    val ligaNombre: String? = null,
    @SerialName("ventana_calculo") val ventanaCalculo: Int? = null,
    @SerialName("preguntas_validas_ranking") val preguntasValidasRanking: Int? = null,
    @SerialName("nota_media_actual") val notaMediaActual: Double? = null,
    @SerialName("nota_referencia_evolucion") val notaReferenciaEvolucion: Double? = null,
    @SerialName("evolucion_nota") val evolucionNota: Double? = null,
    @SerialName("preguntas_desde_ultima_evolucion") val preguntasDesdeUltimaEvolucion: Int? = null,
    @SerialName("ranking_liga_posicion") val rankingLigaPosicion: Int? = null,
    @SerialName("ranking_global_posicion") val rankingGlobalPosicion: Int? = null,
    @SerialName("percentil_global") val percentilGlobal: Double? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

@Serializable
internal data class UserStreakDto(
    @SerialName("racha_actual_dias") val rachaActualDias: Int = 0,
    @SerialName("mejor_racha_dias") val mejorRachaDias: Int = 0,
    @SerialName("ultima_actividad_fecha") val ultimaActividadFecha: String? = null,
)

@Serializable
internal data class UserEconomyDto(
    @SerialName("saldo_tickets") val saldoTickets: Int = 0,
    @SerialName("es_premium") val esPremium: Boolean = false,
    @SerialName("estado_suscripcion") val estadoSuscripcion: String? = null,
    @SerialName("producto_codigo") val productoCodigo: String? = null,
    val proveedor: String? = null,
    @SerialName("renovacion_en") val renovacionEn: String? = null,
    @SerialName("fin_en") val finEn: String? = null,
)

@Serializable
internal data class UserSubscriptionDto(
    val id: String = "",
    val proveedor: String = "",
    @SerialName("producto_codigo") val productoCodigo: String = "",
    val estado: String = "",
    @SerialName("inicio_en") val inicioEn: String? = null,
    @SerialName("fin_en") val finEn: String? = null,
    @SerialName("renovacion_en") val renovacionEn: String? = null,
)

@Serializable
internal data class UserTesterDto(
    @SerialName("reportes_enviados") val reportesEnviados: Int = 0,
    @SerialName("reportes_validados") val reportesValidados: Int = 0,
    @SerialName("tickets_ganados_reportes") val ticketsGanadosReportes: Int = 0,
    @SerialName("nivel_opositester") val nivelOpositester: String? = null,
)

@Serializable
internal data class UserWeaknessDto(
    @SerialName("etiqueta_id") val etiquetaId: String = "",
    val etiqueta: String = "",
    @SerialName("preguntas_respondidas") val preguntasRespondidas: Int = 0,
    @SerialName("respuestas_correctas") val respuestasCorrectas: Int = 0,
    @SerialName("respuestas_falladas") val respuestasFalladas: Int = 0,
    @SerialName("porcentaje_acierto") val porcentajeAcierto: Double = 0.0,
    @SerialName("nivel_dominio") val nivelDominio: String? = null,
    @SerialName("ultima_practica_en") val ultimaPracticaEn: String? = null,
)

internal fun UserDetailDto.toDomain() = UserDetail(
    profile = UserProfile(
        // Canonico antes que legacy (comentario de UserProfileDto); un valor en blanco no tapa al otro (MASTER6-QUALITY-006).
        userId = perfil.id?.ifBlank { null } ?: perfil.usuarioId,
        displayName = perfil.fullName?.ifBlank { null } ?: perfil.nombreMostrar,
        username = perfil.username,
        specialtyId = perfil.especialidadPrincipalId,
        communityId = perfil.comunidadPrincipalId,
        isGuest = perfil.esInvitado,
        onboardingCompleted = perfil.onboardingCompleted ?: perfil.onboardingCompletado,
        createdAt = perfil.createdAt?.ifBlank { null } ?: perfil.creadoEn?.ifBlank { null },
        // Un accountStatus en blanco es ausente: no debe tapar el fallback legacy.
        accountStatus = AccountStatus.fromWire(perfil.accountStatus?.ifBlank { null } ?: perfil.estadoCuenta?.ifBlank { null }),
        authProviders = perfil.authProviders,
    ),
    extendedProfile = detalle?.let {
        UserExtendedProfile(
            fullName = it.nombreCompleto,
            age = it.edad,
            city = it.ciudad,
            employmentStatus = it.estadoLaboral,
            workplace = it.centroTrabajo,
            availableStudyMinutes = it.tiempoDisponibleEstudioMinutos,
            preferredStudySlot = it.franjaPreferenteEstudio,
            targetExamDate = it.fechaExamenObjetivo,
            updatedAt = it.actualizadoEn,
        )
    },
    performance = rendimiento?.let {
        UserPerformance(
            finishedExams = it.examenesFinalizados,
            answeredQuestions = it.preguntasRespondidas,
            correctAnswers = it.respuestasCorrectas,
            successRate = it.porcentajeAcierto,
            totalPoints = it.puntosTotales,
            level = it.nivel,
            updatedAt = it.actualizadoEn,
        )
    },
    competitive = competitivo?.let {
        UserCompetitiveState(
            leagueCode = it.ligaCodigo,
            leagueName = it.ligaNombre,
            calculationWindow = it.ventanaCalculo,
            rankingEligibleQuestions = it.preguntasValidasRanking,
            currentAverage = it.notaMediaActual,
            evolutionReference = it.notaReferenciaEvolucion,
            evolution = it.evolucionNota,
            questionsSinceLastEvolution = it.preguntasDesdeUltimaEvolucion,
            leagueRank = it.rankingLigaPosicion,
            globalRank = it.rankingGlobalPosicion,
            globalPercentile = it.percentilGlobal,
            updatedAt = it.actualizadoEn,
        )
    },
    streak = racha?.let {
        UserStreak(
            currentDays = it.rachaActualDias,
            bestDays = it.mejorRachaDias,
            lastActivityDate = it.ultimaActividadFecha,
        )
    },
    economy = economia?.let {
        UserEconomy(
            ticketBalance = it.saldoTickets,
            isPremium = it.esPremium,
            subscriptionStatus = it.estadoSuscripcion,
            productCode = it.productoCodigo,
            provider = it.proveedor,
            renewsAt = it.renovacionEn,
            endsAt = it.finEn,
        )
    },
    subscriptions = suscripciones.map {
        UserSubscription(
            id = it.id,
            provider = it.proveedor,
            productCode = it.productoCodigo,
            status = it.estado,
            startedAt = it.inicioEn,
            endsAt = it.finEn,
            renewsAt = it.renovacionEn,
        )
    },
    testerState = opositester?.let {
        UserTesterState(
            reportsSubmitted = it.reportesEnviados,
            reportsValidated = it.reportesValidados,
            ticketsEarned = it.ticketsGanadosReportes,
            level = it.nivelOpositester,
        )
    },
    weaknesses = debilidades.map {
        UserTagWeakness(
            tagId = it.etiquetaId,
            tag = it.etiqueta,
            answered = it.preguntasRespondidas,
            correct = it.respuestasCorrectas,
            failed = it.respuestasFalladas,
            successRate = it.porcentajeAcierto,
            masteryLevel = it.nivelDominio,
            lastPracticedAt = it.ultimaPracticaEn,
        )
    },
    unavailableSections = seccionesNoDisponibles.toSet(),
)
