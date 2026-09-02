package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ficha completa de un usuario para el panel de escritorio.
 *
 * Se sirve como un único agregado en vez de obligar a la UI a encadenar ocho
 * peticiones: es una pantalla de detalle, siempre las necesita todas, y hacerlo
 * en el backend evita un round-trip por sección desde el cliente.
 *
 * **Cada sección es nullable a propósito.** Un usuario puede no tener perfil
 * ampliado, ni estado competitivo, ni cartera. `null` significa "esa fila no
 * existe", que no es lo mismo que "existe y está a cero": la UI pinta NO_DATA en
 * el primer caso y un cero real en el segundo. Rellenar con ceros aquí volvería a
 * introducir el problema que ya se corrigió en el dashboard.
 */
@Serializable
data class AdminUserDetailDto(
    val perfil: AdminUserProfileDto,
    val detalle: AdminUserProfileDetailDto? = null,
    val rendimiento: AdminUserPerformanceDto? = null,
    val competitivo: AdminUserCompetitiveDto? = null,
    val racha: AdminUserStreakDto? = null,
    val economia: AdminUserEconomyDto? = null,
    val suscripciones: List<AdminUserSubscriptionDto> = emptyList(),
    val opositester: AdminUserTesterStateDto? = null,
    val debilidades: List<AdminUserWeaknessDto> = emptyList(),
    /**
     * Secciones que no se pudieron leer (a diferencia de "no existe la fila").
     * La UI debe distinguir un hueco honesto de un dato ausente.
     */
    val seccionesNoDisponibles: List<String> = emptyList(),
)

@Serializable
data class AdminUserProfileDto(
    @SerialName("usuario_id") val usuarioId: String = "",
    @SerialName("nombre_mostrar") val nombreMostrar: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("especialidad_principal_id") val especialidadPrincipalId: String? = null,
    @SerialName("comunidad_principal_id") val comunidadPrincipalId: String? = null,
    @SerialName("es_invitado") val esInvitado: Boolean = false,
    @SerialName("onboarding_completado") val onboardingCompletado: Boolean = false,
    @SerialName("creado_en") val creadoEn: String? = null,
)

/** Perfil ampliado (`perfiles_usuario_detalle`). Se rellena tras el onboarding. */
@Serializable
data class AdminUserProfileDetailDto(
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
data class AdminUserPerformanceDto(
    @SerialName("examenes_finalizados") val examenesFinalizados: Int = 0,
    @SerialName("preguntas_respondidas") val preguntasRespondidas: Int = 0,
    @SerialName("respuestas_correctas") val respuestasCorrectas: Int = 0,
    @SerialName("porcentaje_acierto") val porcentajeAcierto: Double = 0.0,
    @SerialName("puntos_totales") val puntosTotales: Int = 0,
    val nivel: Int = 1,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

/**
 * Estado competitivo: nota media, evolución, liga y ranking.
 *
 * `evolucionNota` sólo se recalcula cada bloque de preguntas nuevas, así que
 * `preguntasDesdeUltimaEvolucion` es parte del dato: sin él no se puede saber si
 * la cifra mostrada es reciente o lleva tiempo congelada.
 *
 * `percentilGlobal` es un indicador comparativo **interno** de la app. No es una
 * probabilidad oficial de obtener plaza y no debe presentarse como tal.
 */
@Serializable
data class AdminUserCompetitiveDto(
    @SerialName("liga_nivel_id") val ligaNivelId: String? = null,
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
data class AdminUserStreakDto(
    @SerialName("racha_actual_dias") val rachaActualDias: Int = 0,
    @SerialName("mejor_racha_dias") val mejorRachaDias: Int = 0,
    @SerialName("ultima_actividad_fecha") val ultimaActividadFecha: String? = null,
)

@Serializable
data class AdminUserEconomyDto(
    @SerialName("saldo_tickets") val saldoTickets: Int = 0,
    @SerialName("es_premium") val esPremium: Boolean = false,
    @SerialName("estado_suscripcion") val estadoSuscripcion: String? = null,
    @SerialName("producto_codigo") val productoCodigo: String? = null,
    val proveedor: String? = null,
    @SerialName("renovacion_en") val renovacionEn: String? = null,
    @SerialName("fin_en") val finEn: String? = null,
)

@Serializable
data class AdminUserSubscriptionDto(
    val id: String = "",
    val proveedor: String = "",
    @SerialName("producto_codigo") val productoCodigo: String = "",
    val estado: String = "",
    @SerialName("inicio_en") val inicioEn: String? = null,
    @SerialName("fin_en") val finEn: String? = null,
    @SerialName("renovacion_en") val renovacionEn: String? = null,
)

@Serializable
data class AdminUserTesterStateDto(
    @SerialName("reportes_enviados") val reportesEnviados: Int = 0,
    @SerialName("reportes_validados") val reportesValidados: Int = 0,
    @SerialName("tickets_ganados_reportes") val ticketsGanadosReportes: Int = 0,
    @SerialName("nivel_opositester") val nivelOpositester: String? = null,
)

/** Fila de `v_usuario_debilidades_etiqueta`: dominio por etiqueta temática. */
@Serializable
data class AdminUserWeaknessDto(
    @SerialName("etiqueta_id") val etiquetaId: String = "",
    val etiqueta: String = "",
    @SerialName("preguntas_respondidas") val preguntasRespondidas: Int = 0,
    @SerialName("respuestas_correctas") val respuestasCorrectas: Int = 0,
    @SerialName("respuestas_falladas") val respuestasFalladas: Int = 0,
    @SerialName("porcentaje_acierto") val porcentajeAcierto: Double = 0.0,
    @SerialName("nivel_dominio") val nivelDominio: String? = null,
    @SerialName("ultima_practica_en") val ultimaPracticaEn: String? = null,
)

/** Claves de sección para [AdminUserDetailDto.seccionesNoDisponibles]. */
object AdminUserDetailSection {
    const val DETALLE = "detalle"
    const val RENDIMIENTO = "rendimiento"
    const val COMPETITIVO = "competitivo"
    const val RACHA = "racha"
    const val ECONOMIA = "economia"
    const val SUSCRIPCIONES = "suscripciones"
    const val OPOSITESTER = "opositester"
    const val DEBILIDADES = "debilidades"
}

/**
 * Cuántas debilidades devolver.
 *
 * Acotado porque un usuario con muchas etiquetas practicadas produciría una lista
 * larga que la ficha no puede mostrar de forma útil; el orden lo pone la consulta
 * (peor porcentaje primero), así que el recorte deja justo lo accionable.
 */
const val ADMIN_USER_WEAKNESSES_LIMIT = 15
