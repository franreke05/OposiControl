package com.oposicontrol.admin.domain.usecase.auth

import com.oposicontrol.admin.domain.repository.AuthRepository

class RefreshSessionUseCase(
    private val repository: AuthRepository,
) {
    suspend operator fun invoke() = repository.refreshSession()
}
