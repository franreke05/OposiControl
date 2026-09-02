package com.oposicontrol.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Capacidades por especialidad: qué modos existen realmente para cada oposición.
 *
 * No todas las especialidades tienen el mismo contenido. Enfermería tiene ráfagas,
 * razonados e inmersivos; otras sólo ráfagas. OposiBot debe pintar únicamente lo
 * habilitado, así que esto es configuración de producto, no una constante de
 * frontend: si vive en el cliente, habilitar un modo exige recompilar la app.
 *
 * Vocabulario canónico: `rafaga` / `razonado` / `inmersivo`. `guiado` es sólo un
 * alias legacy y no aparece aquí.
 *
 * Respaldo: tabla `public.capacidades_especialidad`, con PK = `especialidad_id` y
 * los cinco flags más un mensaje de limitación mostrable al usuario.
 */
@Serializable
data class AdminEspecialidadCapacidadesDto(
    val especialidadId: String = "",
    val permiteRafagas: Boolean = true,
    val permiteRazonados: Boolean = false,
    val permiteInmersivos: Boolean = false,
    val permiteDemos: Boolean = true,
    val permiteOposimarket: Boolean = true,
    val mensajeLimitacion: String? = null,
    val actualizadoEn: String? = null,
    /**
     * `false` cuando la especialidad todavía no tiene fila propia y lo devuelto son
     * los valores por defecto del esquema. Se distingue a propósito de "configurada
     * con todo desactivado": el admin necesita saber si alguien ya decidió esto.
     */
    val configurado: Boolean = true,
)

/** Fila tal cual vive en Supabase (snake_case). */
@Serializable
internal data class CapacidadesEspecialidadRow(
    @SerialName("especialidad_id") val especialidadId: String = "",
    @SerialName("permite_rafagas") val permiteRafagas: Boolean = true,
    @SerialName("permite_razonados") val permiteRazonados: Boolean = false,
    @SerialName("permite_inmersivos") val permiteInmersivos: Boolean = false,
    @SerialName("permite_demos") val permiteDemos: Boolean = true,
    @SerialName("permite_oposimarket") val permiteOposimarket: Boolean = true,
    @SerialName("mensaje_limitacion") val mensajeLimitacion: String? = null,
    @SerialName("actualizado_en") val actualizadoEn: String? = null,
)

internal fun CapacidadesEspecialidadRow.toDto() = AdminEspecialidadCapacidadesDto(
    especialidadId = especialidadId,
    permiteRafagas = permiteRafagas,
    permiteRazonados = permiteRazonados,
    permiteInmersivos = permiteInmersivos,
    permiteDemos = permiteDemos,
    permiteOposimarket = permiteOposimarket,
    mensajeLimitacion = mensajeLimitacion,
    actualizadoEn = actualizadoEn,
    configurado = true,
)

/**
 * Valores por defecto del esquema para una especialidad aún sin fila.
 *
 * Coinciden con los `default` de la tabla a propósito: si el admin guarda sin
 * tocar nada, la fila creada es idéntica a lo que ya se estaba sirviendo, así que
 * "abrir y guardar" nunca cambia en silencio lo que ve el usuario final.
 */
internal fun defaultCapacidades(especialidadId: String) = AdminEspecialidadCapacidadesDto(
    especialidadId = especialidadId,
    permiteRafagas = true,
    permiteRazonados = false,
    permiteInmersivos = false,
    permiteDemos = true,
    permiteOposimarket = true,
    mensajeLimitacion = null,
    actualizadoEn = null,
    configurado = false,
)

internal fun AdminEspecialidadCapacidadesDto.toRow(especialidadId: String) = CapacidadesEspecialidadRow(
    especialidadId = especialidadId,
    permiteRafagas = permiteRafagas,
    permiteRazonados = permiteRazonados,
    permiteInmersivos = permiteInmersivos,
    permiteDemos = permiteDemos,
    permiteOposimarket = permiteOposimarket,
    mensajeLimitacion = mensajeLimitacion?.takeIf { it.isNotBlank() },
)

/**
 * Modos de examen habilitados, en vocabulario canónico, para una configuración.
 *
 * Es lo que OposiBot necesita para decidir qué pintar, y lo que el panel usa para
 * resumir la especialidad en una línea sin repetir la traducción flag→modo en
 * cada sitio.
 */
internal fun AdminEspecialidadCapacidadesDto.modosHabilitados(): List<String> = buildList {
    if (permiteRafagas) add("rafaga")
    if (permiteRazonados) add("razonado")
    if (permiteInmersivos) add("inmersivo")
}

/**
 * Una especialidad sin ningún modo de examen deja al usuario dentro de una
 * pantalla sin nada que hacer. Se exige un mensaje que explique por qué, porque
 * la prioridad de UX declarada es que el usuario entienda siempre qué puede hacer
 * y por qué algo está bloqueado.
 */
internal fun AdminEspecialidadCapacidadesDto.validated(): AdminEspecialidadCapacidadesDto {
    if (modosHabilitados().isEmpty() && mensajeLimitacion.isNullOrBlank()) {
        throw BackendDomainException(
            "SPECIALTY_NO_MODES_WITHOUT_MESSAGE",
            io.ktor.http.HttpStatusCode.UnprocessableEntity,
            "Una especialidad sin ningún modo de examen habilitado necesita un mensaje de " +
                "limitación que explique al usuario por qué no hay contenido disponible.",
        )
    }
    return this
}
