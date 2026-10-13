package com.oposicontrol.admin.domain.repository

import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Role
import kotlinx.coroutines.flow.Flow

/**
 * Catálogo de roles, de SOLO LECTURA: los roles no se crean ni se editan desde el panel (viven en `roles_app`).
 *
 * Emite [AppResult] y no una lista pelada por la misma razón que `UsersRepository.observeUsers`: una lista no puede
 * expresar un fallo. El origen convertía cualquier error en `emptyList()`, y un catálogo vacío significa otra cosa: sin
 * él no se sabe qué rol concede acceso al panel, así que una caída de red se leía como «nadie es administrador».
 */
interface RolesRepository {
    fun observeRoles(): Flow<AppResult<List<Role>>>
}
