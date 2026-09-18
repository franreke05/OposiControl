package com.oposicontrol.backend

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Capacidades por especialidad: qué modos existen realmente para cada oposición.
 *
 * No todas las especialidades tienen el mismo contenido, y OposiBot debe pintar
 * únicamente lo habilitado, así que esto es configuración de producto y no una
 * constante de frontend: si vive en el cliente, habilitar un modo exige
 * recompilar la app.
 *
 * **Abierto por defecto, se restringe a mano.** Una especialidad nace admitiendo
 * los tres modos; se le quita uno cuando esa oposición concreta no lo tenga. El
 * criterio anterior era el contrario —sólo ráfagas, y se habilitaba el resto— y
 * en la práctica no funcionó: como nadie escribió nunca una fila en
 * `capacidades_especialidad`, TODAS las especialidades se servían con los
 * defaults y el editor de exámenes sólo dejaba elegir «Ráfaga». La restricción
 * por especialidad sigue existiendo; lo que cambia es de qué lado empieza.
 *
 * Vocabulario canónico: `rafaga` / `razonado` / `inmersivo`. `guiado` es sólo un
 * alias legacy y no aparece aquí.
 *
 * Respaldo: tabla `public.capacidades_especialidad`, con PK = `especialidad_id` y
 * los cinco flags más un mensaje de limitación mostrable al usuario.
 *
 * ## Por qué cada flag lleva `@EncodeDefault(ALWAYS)`
 *
 * El `Json` con el que responde el servidor (`Application.module()`) no pone
 * `encodeDefaults`, así que kotlinx omite toda propiedad que valga igual que su
 * default. Con los cinco flags en su default, el cuerpo de una especialidad
 * entera se quedaba en `{"especialidadId":"…"}`: ni un booleano.
 *
 * El cliente tiene su propio DTO con sus propios defaults —y NO coinciden con
 * éstos ni hay nada que los sincronice— así que un flag ausente no se lee como
 * "lo que decidió el servidor" sino como el default del cliente. Resultado real:
 * el panel pintaba Razonados e Inmersivos apagados para TODAS las especialidades,
 * y el editor de exámenes bloqueaba esos tipos, sirviéndolos el servidor como
 * abiertos.
 *
 * Se anota el DTO en vez de tocar el `Json` global porque ese `Json` sirve a todos
 * los endpoints del backend: cambiarlo alteraría el cuerpo de cada respuesta de la
 * API por un problema que es de este contrato.
 */
@Serializable
data class AdminEspecialidadCapacidadesDto(
    val especialidadId: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteRafagas: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteRazonados: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteInmersivos: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteDemos: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val permiteOposimarket: Boolean = true,
    val mensajeLimitacion: String? = null,
    val actualizadoEn: String? = null,
    /**
     * `false` cuando la especialidad todavía no tiene fila propia y lo devuelto son
     * los valores por defecto del esquema. Se distingue a propósito de "configurada
     * con todo desactivado": el admin necesita saber si alguien ya decidió esto.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val configurado: Boolean = true,
)

/** Fila tal cual vive en Supabase (snake_case). */
@Serializable
internal data class CapacidadesEspecialidadRow(
    @SerialName("especialidad_id") val especialidadId: String = "",
    @SerialName("permite_rafagas") val permiteRafagas: Boolean = true,
    @SerialName("permite_razonados") val permiteRazonados: Boolean = true,
    @SerialName("permite_inmersivos") val permiteInmersivos: Boolean = true,
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
 *
 * Los dos lados se mueven juntos: la migración que abre `permite_razonados` y
 * `permite_inmersivos` en la tabla es
 * `supabase/migrations/20260914090000_capacidades_especialidad_default_all_modes.sql`.
 */
internal fun defaultCapacidades(especialidadId: String) = AdminEspecialidadCapacidadesDto(
    especialidadId = especialidadId,
    permiteRafagas = true,
    permiteRazonados = true,
    permiteInmersivos = true,
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
