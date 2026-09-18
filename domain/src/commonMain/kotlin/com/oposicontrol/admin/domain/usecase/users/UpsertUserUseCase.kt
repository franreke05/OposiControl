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
        // Aquí había una validación de email, y era doblemente incorrecta.
        //
        // `User.email` no contiene un email: el backend rellena ese campo con
        // `perfiles_usuario.username`, porque esa tabla no tiene columna de correo
        // —el real vive en `auth.users` y el panel no lo lee—. Exigir una arroba
        // sobre un nombre de usuario hacía que guardar el NOMBRE de cualquiera
        // cuyo username no pareciese un correo fallara siempre, con un mensaje que
        // señalaba a un campo que el usuario no podía arreglar.
        //
        // Y aunque el dato fuera un correo, el `PATCH` de usuario sólo escribe
        // `nombre_mostrar`: validar un campo que el backend descarta sólo sirve
        // para bloquear la única edición que sí persiste.
        return repository.upsertUser(user)
    }
}
