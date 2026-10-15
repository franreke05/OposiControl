package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.model.UserDetail
import kotlinx.coroutines.flow.Flow

interface UsersRepository {
    /**
     * Censo de usuarios.
     *
     * Emite [AppResult] y no una lista pelada porque `List<User>` no puede
     * expresar un fallo: el origen convertía cualquier error en `emptyList()`, así
     * que una caída del backend llegaba a la UI indistinguible de «no hay ningún
     * usuario registrado». Es la invariante `0 != error`.
     */
    fun observeUsers(): Flow<AppResult<List<User>>>

    /**
     * Solo quien tiene el rol [roleCode], filtrado en el servidor: no descarga el censo para enseñar tres filas. Por
     * defecto devuelve el censo entero (un doble de prueba no tiene por qué filtrar); quien llama no debe dar por hecho
     * que todas las filas tienen el rol.
     */
    fun observeUsersWithRole(roleCode: String): Flow<AppResult<List<User>>> = observeUsers()

    /**
     * Busqueda de personas en el servidor (`q` de D1-22: prefijo de username o nombre), UNA pagina de [limit] filas. Por
     * defecto dice que no puede: un doble de prueba no busca.
     */
    suspend fun searchUsers(query: String, limit: Int): AppResult<List<User>> =
        AppResult.Failure(AppError.Validation("Este entorno no puede buscar usuarios."))

    suspend fun getUser(userId: String): AppResult<User>
    /**
     * [previous] es la fila tal y como la vio el editor antes de tocarla. Con ella el guardado solo escribe lo que cambio
     * (MASTER5-API-009): una edicion de nombre no repone el conjunto de roles (posiblemente viejo) ni al reves. `null` =
     * desconocido: se escribe todo.
     */
    suspend fun upsertUser(user: User, previous: User? = null): AppResult<User>

    /** Ficha completa: perfil, rendimiento, competitivo, economía y comunidad. */
    suspend fun getUserDetail(userId: String): AppResult<UserDetail>
}
