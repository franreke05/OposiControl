package com.oposicontrol.admin.domain.usecase.auth

import com.oposicontrol.admin.domain.repository.AuthRepository

class ObserveSessionUseCase(
    private val repository: AuthRepository,
) {
    operator fun invoke() = repository.observeSession()
}
