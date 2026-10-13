package com.oposicontrol.admin.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshSessionRequestDto(
    val refreshToken: String,
)

@Serializable
data class SessionDto(
    val userId: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val roleIds: List<String>,
)

@Serializable
data class SupabaseAuthResponseDto(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresInSeconds: Long,
    val user: SupabaseUserDto,
)

@Serializable
data class SupabaseUserDto(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata")
    val userMetadata: JsonObject = JsonObject(emptyMap()),
)

/**
 * Forma de error de Supabase Auth (GoTrue). Todos los campos opcionales a
 * propósito: distintas versiones de GoTrue devuelven formas distintas
 * (`error`/`error_description` en unas, `error_code`/`msg` en otras) y no
 * asumimos cuál usa este proyecto en concreto — con [ignoreUnknownKeys] ya
 * activado globalmente, decodifica cualquiera de las dos sin fallar.
 */
@Serializable
data class SupabaseAuthErrorDto(
    val error: String? = null,
    @SerialName("error_description")
    val errorDescription: String? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    val msg: String? = null,
)

/**
 * Lo que ve quien inicia sesion con una cuenta todavia sin confirmar. En este panel eso es, casi siempre, una solicitud de
 * acceso que un superadministrador aun no ha aprobado (la aprobacion es la que confirma la cuenta). Supabase solo da esta
 * respuesta a quien acierta la contrasena, asi que el mensaje no revela nada que el inicio de sesion no revelara ya.
 */
const val ACCOUNT_NOT_ACTIVATED_MESSAGE: String =
    "Esta cuenta todavía no está activada. Si has solicitado acceso, un superadministrador tiene que aprobarlo antes de que puedas entrar."

/**
 * Traduce el error real de Supabase a un mensaje humano en español. Nunca
 * reenvía el texto/código crudo del proveedor: solo distingue un puñado de
 * categorías conocidas y, si no reconoce ninguna, cae en un mensaje genérico
 * seguro — nunca en el JSON de error ni en el nombre de una excepción.
 */
fun SupabaseAuthErrorDto.toFriendlyMessage(isRefresh: Boolean): String {
    val code = (errorCode ?: error).orEmpty().lowercase()
    val sessionExpired = "Tu sesión ha caducado. Vuelve a iniciar sesión."
    // Cuenta sin confirmar. Va ANTES que la rama de credenciales: las versiones de GoTrue que no envian `error_code`
    // lo dicen como `invalid_grant` + «Email not confirmed», y caeria en «contraseña incorrecta», que es falso.
    val notConfirmed = code.contains("email_not_confirmed") ||
        listOfNotNull(errorDescription, msg).any { it.contains("not confirmed", ignoreCase = true) }
    return when {
        notConfirmed && !isRefresh -> ACCOUNT_NOT_ACTIVATED_MESSAGE
        code.contains("invalid") || code.contains("grant") || code.contains("credentials") ->
            if (isRefresh) sessionExpired else "Usuario o contraseña incorrectos."
        code.contains("too_many") || code.contains("rate") -> "Demasiados intentos. Espera un momento y vuelve a intentarlo."
        isRefresh -> sessionExpired
        else -> "No se pudo iniciar sesión. Inténtalo de nuevo."
    }
}

fun SupabaseAuthResponseDto.toSessionDto(): SessionDto {
    val displayName = user.userMetadata["display_name"]?.jsonPrimitive?.contentOrNull
        ?: user.userMetadata["name"]?.jsonPrimitive?.contentOrNull
        ?: user.email
        ?: "Administrador"
    return SessionDto(
        userId = user.id,
        displayName = displayName,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = (Clock.System.now() + expiresInSeconds.seconds).toString(),
        roleIds = listOf("admin"),
    )
}
