package com.oposicontrol.admin.core.network

/**
 * Fallo de autenticación (login o refresh) contra el proveedor de auth, con un
 * mensaje YA en español y listo para mostrarse tal cual: `AuthScreen` pinta
 * `AppError.message` directamente, sin re-mapear por tipo, así que este mensaje
 * nunca debe llevar texto técnico del proveedor (código de error, JSON crudo,
 * nombre de excepción). Ver `AuthApi.requireAuthSuccess` y
 * `SupabaseAuthErrorDto.toFriendlyMessage`.
 */
class AuthCredentialsException(message: String) : Exception(message)
