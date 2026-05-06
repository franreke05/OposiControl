package com.oposicontrol.admin.domain.usecase.users

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.User
import com.oposicontrol.admin.domain.repository.UsersRepository

class UpsertUserUseCase(
    private val repository: UsersRepository,
) {
    suspend operator fun invoke(user: User): AppResult<User> {
        if (user.fullName.isBlank()) {
            return AppResult.Failure(AppError.Validation("El nombre del usuario no puede estar vacio."))
        }
        if ("@" !in user.email) {
            return AppResult.Failure(AppError.Validation("El email del usuario no es valido."))
        }
        return repository.upsertUser(user)
    }
}
