package com.oposicontrol.admin.domain.usecase.users

import com.oposicontrol.admin.domain.repository.UsersRepository

class ObserveUsersUseCase(
    private val repository: UsersRepository,
) {
    operator fun invoke() = repository.observeUsers()
}
