package com.oposicontrol.admin.domain.usecase.roles

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.Role
import com.oposicontrol.admin.domain.repository.RolesRepository

class UpsertRoleUseCase(
    private val repository: RolesRepository,
) {
    suspend operator fun invoke(role: Role): AppResult<Role> {
        if (role.name.isBlank()) {
            return AppResult.Failure(AppError.Validation("El rol necesita un nombre."))
        }
        return repository.upsertRole(role)
    }
}
