package com.oposicontrol.backend

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

// =====================================================================
// Guarda de correo de las solicitudes de acceso de administrador (plan del propietario, 2026-10-13, B1).
//
// `POST /v1/auth/admin-access-requests` (AdminAccessRequests.kt) crea una cuenta de Auth SIN CONFIRMAR y una fila
// `solicitudes_acceso_admin` en `pendiente`. La promesa es «no entra en ningun sitio hasta que un superadministrador la
// apruebe». Las tres rutas de consumidor que ENVIAN correo (`/v1/auth/register`, `/v1/auth/email/resend`,
// `/v1/auth/recover`) no distinguian esa cuenta de un alta normal: quien pidio acceso podia pedirse el correo, confirmar
// la cuenta el mismo y obtener una sesion de consumidor sin que nadie aprobara nada.
//
// Regla: si el correo normalizado tiene una solicitud cuyo `estado` NO es `aprobada`, la ruta contesta EXACTAMENTE su
// exito normal (202 / 204) y NO llama a GoTrue. Es el mismo patron que el tope silencioso por email (MASTER-SEC-003): la
// respuesta no revela que exista una solicitud.
//
//  - `pendiente`  -> bloquea.
//  - `rechazada`  -> bloquea. En origin/main el rechazo marca la fila y DESPUES borra la cuenta de Auth (la fila se va con
//                    ella, FK ON DELETE CASCADE): lo normal es que no quede fila y el correo vuelva a ser libre. Pero si ese
//                    borrado falla (o la cuenta tiene un rol de administracion y no se borra), queda una fila `rechazada`
//                    con su cuenta sin confirmar; mientras exista, sigue bloqueada.
//  - `aprobada`   -> no bloquea (la aprobacion ya confirmo el correo: es una cuenta normal).
//  - sin fila     -> no bloquea.
//  - cualquier otro valor (hoy imposible por el CHECK de la tabla) -> bloquea (deny-by-default: el filtro es `neq.aprobada`).
//
// Normalizacion: `trim()` + `lowercase()`, la MISMA con la que [AdminAccessRequestBody.validated] guarda la columna `email`
// y la que usan las rutas de auth para su clave de limite. La comparacion es por igualdad exacta sobre esa forma.
//
// Fallo de la lectura (red, 5xx, cuerpo ilegible): FALLA CERRADO con el 503 `UPSTREAM_UNAVAILABLE` canonico del plano de
// auth; nunca se convierte en «no hay solicitud». El 503 no depende del correo, asi que tampoco revela nada.
// =====================================================================

/** `true` = hay una solicitud de acceso no aprobada para ese correo: la ruta NO debe pedir a GoTrue que envie nada. */
internal fun interface AccessRequestMailGuard {
    suspend fun holdsMail(normalizedEmail: String): Boolean

    companion object {
        /**
         * Sin lectura: nunca bloquea. SOLO para pruebas que montan [OposiBotsAuthRuntime] a mano y no ejercitan la guarda;
         * produccion construye siempre la real en [OposiBotsAuthRuntime.fromEnvironment].
         */
        val NoAccessRequests = AccessRequestMailGuard { false }
    }
}

@Serializable
private data class AccessRequestEstadoRow(val estado: String = "")

/**
 * Lectura PostgREST con la clave de servicio (la tabla tiene RLS sin politicas). [upstream] es el GET bloqueante del
 * transporte ([SupabaseRequestTransport.get]); se ejecuta dentro de [withUpstreamIo].
 */
internal class PostgrestAccessRequestMailGuard(
    private val upstream: (String) -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AccessRequestMailGuard {
    override suspend fun holdsMail(normalizedEmail: String): Boolean {
        val email = normalizedEmail.trim().lowercase()
        val rows = try {
            // Las rutas ya validaron la forma; si aqui llega otra cosa no se compone ninguna consulta (falla cerrado).
            require(email.length in 3..320 && email.none(Char::isWhitespace)) { "email" }
            val body = withUpstreamIo { upstream(accessRequestHoldPath(email)) }
            json.decodeFromString(ListSerializer(AccessRequestEstadoRow.serializer()), body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Solo la clase: el mensaje de un error de PostgREST puede reflejar el valor filtrado (el correo).
            MailGuardLogger.warn(
                "access request mail guard lookup failed: failing closed emailRef={} error={}",
                adminAccessEmailRef(email),
                failure::class.simpleName,
            )
            throw OposiBotsAuthProblem.unavailable()
        }
        return rows.isNotEmpty()
    }
}

/** Una fila basta: cualquier solicitud de ese correo que no este `aprobada`. El correo va urlencoded, nunca crudo. */
internal fun accessRequestHoldPath(normalizedEmail: String): String =
    "/rest/v1/$ADMIN_ACCESS_TABLE?select=estado&email=eq.${encodeQuery(normalizedEmail)}" +
        "&estado=neq.$ADMIN_ACCESS_ESTADO_APROBADA&limit=1"

/**
 * Lo que llaman las tres rutas, DESPUES del tope silencioso por email (asi la lectura tambien queda acotada por el) y
 * ANTES del gateway. `true` = retenido: la ruta responde su exito normal sin llamar a GoTrue.
 */
internal suspend fun OposiBotsAuthRuntime.accessRequestHoldsMail(operation: OposiBotsAuthOperation, normalizedEmail: String): Boolean {
    val held = accessRequestMailGuard.holdsMail(normalizedEmail)
    if (held) {
        MailGuardLogger.info(
            "auth operation={} held by a non-approved admin access request: silent success emailRef={}",
            operation.name,
            adminAccessEmailRef(normalizedEmail),
        )
    }
    return held
}

private val MailGuardLogger = LoggerFactory.getLogger("OC-KTOR-AUTH")
