package com.oposicontrol.backend

import kotlinx.serialization.Serializable

/**
 * Parámetros de paginación ya saneados.
 *
 * Los valores llegan de la query string, así que no se pueden usar tal cual: un
 * `limit` enorme convertiría el endpoint en una descarga de la tabla entera, y
 * un `offset` negativo es sintaxis inválida para PostgREST. Se recortan en lugar
 * de rechazarse porque una lista es una lectura y un tope silencioso es mejor
 * respuesta que un error para quien sólo se equivocó en un número.
 */
data class AdminPageRequest private constructor(
    val limit: Int,
    val offset: Int,
) {
    companion object {
        const val DEFAULT_LIMIT = 50

        /**
         * Tope duro. Existe para que nadie pueda pedir `?limit=1000000` y arrastrar
         * la tabla completa a memoria a través del backend.
         */
        const val MAX_LIMIT = 200

        fun of(limit: Int?, offset: Int?): AdminPageRequest = AdminPageRequest(
            limit = when {
                limit == null || limit <= 0 -> DEFAULT_LIMIT
                limit > MAX_LIMIT -> MAX_LIMIT
                else -> limit
            },
            offset = offset?.takeIf { it > 0 } ?: 0,
        )
    }
}

/**
 * Una página de usuarios.
 *
 * `total` es nullable a propósito: si el conteo no se puede obtener, se dice que
 * no se sabe en lugar de enviar un 0 que la UI pintaría como "no hay usuarios".
 */
@Serializable
data class AdminUsersPage(
    val data: List<AdminV1UserDto> = emptyList(),
    val total: Int? = null,
    val limit: Int = AdminPageRequest.DEFAULT_LIMIT,
    val offset: Int = 0,
)
