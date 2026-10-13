package com.oposicontrol.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.MDC

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
//
// PUERTA DE SESION (revision de seguridad independiente, 2026-10-13). Retener el correo no basta para garantizar «una
// cuenta de solicitud de acceso no obtiene sesion de consumidor sin aprobacion»: la cuenta puede quedar confirmada por
// otra via (un proveedor OAuth con el mismo correo, un enlace emitido antes de desplegar la guarda, una carrera con el
// alta de la solicitud). Por eso [AccessRequestSessionGate] se consulta ademas en el UNICO punto donde se entregan
// sesiones (`enforceAccountGate`, OposiBotsAuthGateway.kt): por ID DE USUARIO (`usuario_id` es UNIQUE en la tabla), con
// el mismo filtro `estado=neq.aprobada` y el mismo fallo cerrado. Una fila = sesion revocada en global y 403 FORBIDDEN.
// =====================================================================

/** `true` = hay una solicitud de acceso no aprobada para ese correo: la ruta NO debe pedir a GoTrue que envie nada. */
internal fun interface AccessRequestMailGuard {
    suspend fun holdsMail(normalizedEmail: String): Boolean
}

/**
 * `true` = ese usuario tiene una solicitud de acceso no aprobada: NO se le entrega ninguna sesion de consumidor. No tiene
 * valor por defecto en ningun constructor de produccion: quien monta el gateway decide explicitamente que puerta pone.
 */
internal fun interface AccessRequestSessionGate {
    suspend fun deniesSession(userId: String): Boolean
}

@Serializable
private data class AccessRequestEstadoRow(val estado: String = "")

/**
 * Lectura PostgREST con la clave de servicio (la tabla tiene RLS sin politicas). [upstream] es el GET bloqueante del
 * transporte ([SupabaseRequestTransport.get]). [emailRef] es el seudonimo CON CLAVE del correo para los logs
 * ([authLogEmailRef]); el correo nunca se escribe.
 *
 * Una sola instancia sirve las dos lecturas (por correo para el envio de correo, por usuario para la entrega de sesion)
 * sobre el mismo transporte.
 */
internal class PostgrestAccessRequestMailGuard(
    private val upstream: (String) -> String,
    private val emailRef: (String) -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AccessRequestMailGuard, AccessRequestSessionGate {
    override suspend fun holdsMail(normalizedEmail: String): Boolean {
        val email = normalizedEmail.trim().lowercase()
        // Las rutas ya validaron la forma (misma regla: 3..320 y ningun espacio, tampoco los Unicode). Si aqui llega otra
        // cosa es el mismo 400 de identificador invalido que da la ruta y no se compone ninguna consulta: depende solo de
        // la forma del correo, nunca de que exista una solicitud, asi que no revela nada.
        if (email.length !in 3..320 || email.any(Char::isWhitespace)) {
            throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
        }
        val rows = try {
            val body = withUpstreamIo { upstream(accessRequestHoldPath(email)) }
            json.decodeFromString(ListSerializer(AccessRequestEstadoRow.serializer()), body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Solo la clase: el mensaje de un error de PostgREST puede reflejar el valor filtrado (el correo).
            MailGuardLogger.warn(
                "access request mail guard lookup failed: failing closed emailRef={} error={}",
                emailRef(email),
                failure::class.simpleName,
            )
            throw OposiBotsAuthProblem.unavailable()
        }
        return rows.isNotEmpty()
    }

    /**
     * La lectura de la puerta de sesion. Corre con [runInterruptible]: el refresh acota su puerta con un limite por
     * intento y un plazo global (MASTER3-AUTH-005), y un GET bloqueante dentro de un `withContext` normal no se entera de
     * la cancelacion hasta agotar el timeout del cliente HTTP. Asi el hilo se interrumpe y la peticion se abandona.
     */
    override suspend fun deniesSession(userId: String): Boolean {
        val rows = try {
            // El id sale del `sub` de la sesion recien acuñada; si no es un UUID no se compone ninguna consulta (falla cerrado).
            require(userId.isUuid()) { "userId" }
            val correlation = MDC.getCopyOfContextMap()
            val body = runInterruptible(Dispatchers.IO) {
                runWithMdcContext(correlation) { upstream(accessRequestSessionPath(userId)) }
            }
            json.decodeFromString(ListSerializer(AccessRequestEstadoRow.serializer()), body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // El transporte envuelve la interrupcion en su propio error: si fue una cancelacion, sigue siendolo.
            currentCoroutineContext().ensureActive()
            MailGuardLogger.warn("access request session gate lookup failed: failing closed error={}", failure::class.simpleName)
            throw OposiBotsAuthProblem.unavailable()
        }
        return rows.isNotEmpty()
    }
}

/** Una fila basta: cualquier solicitud de ese correo que no este `aprobada`. El correo va urlencoded, nunca crudo. */
internal fun accessRequestHoldPath(normalizedEmail: String): String =
    "/rest/v1/$ADMIN_ACCESS_TABLE?select=estado&email=eq.${encodeQuery(normalizedEmail)}" +
        "&estado=neq.$ADMIN_ACCESS_ESTADO_APROBADA&limit=1"

/** La misma pregunta por usuario (`usuario_id` UNIQUE: como mucho una fila). [userId] llega ya validado como UUID. */
internal fun accessRequestSessionPath(userId: String): String =
    "/rest/v1/$ADMIN_ACCESS_TABLE?select=estado&usuario_id=eq.${userId.lowercase()}" +
        "&estado=neq.$ADMIN_ACCESS_ESTADO_APROBADA&limit=1"

/**
 * Seudonimo del correo para los logs del plano de auth: HMAC-SHA-256 con el secreto del limitador
 * (`AUTH_RATE_LIMIT_HMAC_SECRET`, [RateLimitKeyHasher]) en vez de un SHA-256 sin clave: sin ese secreto, un correo
 * candidato ya no se puede comprobar contra los logs. El prefijo lo separa de las claves del propio limitador.
 */
internal fun OposiBotsAuthConfiguration.authLogEmailRef(email: String): String =
    RateLimitKeyHasher(rateLimitHmacSecret).hash("auth-log-email-ref:" + email.trim().lowercase()).take(12)

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
            configuration.authLogEmailRef(normalizedEmail),
        )
    }
    return held
}

private val MailGuardLogger = LoggerFactory.getLogger("OC-KTOR-AUTH")
