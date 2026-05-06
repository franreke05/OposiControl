package com.oposicontrol.admin.domain.usecase.users

import com.oposicontrol.admin.domain.repository.UsersRepository

class DeleteUserUseCase(
    private val repository: UsersRepository,
) {
    suspend operator fun invoke(userId: String) = repository.deleteUser(userId)
}
