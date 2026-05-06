package com.oposicontrol.admin.domain.usecase.auth

import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import com.oposicontrol.admin.domain.model.AuthCredentials
import com.oposicontrol.admin.domain.model.Session
import com.oposicontrol.admin.domain.repository.AuthRepository

class LoginUseCase(
    private val repository: AuthRepository,
) {
    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    suspend operator fun invoke(credentials: AuthCredentials): AppResult<Session> {
        if (credentials.email.isBlank()) {
            return AppResult.Failure(AppError.Validation("El email es obligatorio."))
        }
        if (!emailRegex.matches(credentials.email)) {
            return AppResult.Failure(AppError.Validation("Introduce un email valido."))
        }
        if (credentials.password.length < 8) {
            return AppResult.Failure(AppError.Validation("La contrasena debe tener al menos 8 caracteres."))
        }
        return repository.login(credentials)
    }
}
