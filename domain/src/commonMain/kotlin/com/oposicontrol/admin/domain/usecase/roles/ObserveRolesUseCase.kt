package com.oposicontrol.admin.domain.usecase.roles

import com.oposicontrol.admin.domain.repository.RolesRepository

class ObserveRolesUseCase(
    private val repository: RolesRepository,
) {
    operator fun invoke() = repository.observeRoles()
}
