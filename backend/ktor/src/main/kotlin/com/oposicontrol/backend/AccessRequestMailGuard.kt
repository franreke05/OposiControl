package com.oposicontrol.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import org.slf4j.MDC

// =====================================================================
// Guarda de correo y puerta de sesion de las solicitudes de acceso de administrador.
//
// B1 (2026-10-13): `POST /v1/auth/admin-access-requests` (AdminAccessRequests.kt) crea una cuenta de Auth SIN CONFIRMAR y
// una fila `solicitudes_acceso_admin` en `pendiente`. La promesa es «no entra en ningun sitio hasta que un
// superadministrador la apruebe». Las tres rutas de consumidor que ENVIAN correo (`/v1/auth/register`,
// `/v1/auth/email/resend`, `/v1/auth/recover`) y el UNICO punto donde se entregan sesiones (`enforceAccountGate`) la
// consultan.
//
// Plan del propietario 2026-10-14 (propiedad del correo y suplantacion). Una solicitud esta VERIFICADA cuando
// `correo_verificado_en` no es nulo (quien la pidio introdujo el codigo de 6 cifras que llego a ese buzon). Una solicitud
// NO verificada CADUCA una hora despues de `creado_en` ([ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS]) o queda AGOTADA tras
// [ADMIN_ACCESS_VERIFY_MAX_FAILURES] codigos erroneos (`intentos_verificacion`).
//
//  - VERIFICADA y no `aprobada` -> la ruta de correo contesta su exito normal SIN llamar a GoTrue, y la puerta de sesion
//    deniega (403 FORBIDDEN, revocacion global). Ademas la cuenta esta BANEADA en GoTrue desde la verificacion (M1).
//  - NO verificada -> no reserva el correo frente al titular, con estos matices (revision de seguridad 2026-10-14):
//      * `register` la libera SIEMPRE (caducada o no). `email/resend` y `recover` solo si esta CADUCADA o AGOTADA, o si su
//        cuenta ya esta CONFIRMADA (eso solo lo hace el titular del buzon o de la identidad) (M4): un tercero que nombra el
//        correo de un solicitante no puede borrarle la solicitud viva; mientras siga viva esas dos rutas contestan su
//        exito normal sin llamar a GoTrue (retenido). El titular real libera el correo al instante con `register`.
//      * La regla de liberacion ([releaseUnverifiedAccount], H1): cuenta con roles -> nunca se toca (retiene + WARN);
//        cuenta NUNCA confirmada, o confirmada y BANEADA (revision #3 M1) -> se borra (la fila en cascada); cuenta CONFIRMADA sin roles (el titular pulso un enlace
//        o enlazo Google/Apple sobre la cuenta que creo otro) -> se NEUTRALIZA la credencial del creador (contrasena
//        aleatoria del servidor y revocacion de todas sus sesiones), se borra SOLO la fila y la cuenta queda para el
//        titular del buzon (que la recupera con `recover`). En todos los casos se audita `solicitud_acceso_caducada`.
//      * Puerta de sesion ([AccessRequestSessionGrant]): sesion de CONTRASENA (o refresh) -> la credencial es la del
//        creador de la solicitud, no la del titular del buzon: se neutraliza igual, se borra la fila y se DENIEGA (403,
//        revocacion global). Sesion de OAuth o de enlace/codigo de correo -> es el titular: segunda revision de seguridad
//        (2026-10-14, DO_NOT_SHIP), ANTES de entregarla se neutraliza la credencial del creador (las filas anteriores a este
//        cambio la llevan), se revocan sus demas sesiones (`logout?scope=others` con la sesion recien acuñada), se borra la
//        fila y se entrega; si algo de eso falla, 503 y revocacion global.
//      * M-1: la puerta DECIDE sin escribir ([AccessRequestSessionGate.decide], dentro de la lectura acotada y reintentada)
//        y LIMPIA despues, una sola vez y sin poder cancelarse ([AccessRequestSessionGate.cleanUp]).
//  - Desde 2026-10-14 la cuenta de una solicitud nace con una contrasena ALEATORIA del servidor: la del panel la elige quien
//    demuestra el buzon, al verificar el codigo (AdminAccessRequests.kt).
//  - `aprobada` o sin fila -> no retiene.
//
// Normalizacion: el correo canonico unico ([canonicalAuthEmail]), el mismo con el que se guarda la columna `email`.
//
// Cualquier fallo de las lecturas o de las escrituras (red, 5xx, cuerpo ilegible) FALLA CERRADO con el 503
// `UPSTREAM_UNAVAILABLE` canonico del plano de auth; nunca se convierte en «no hay solicitud». El 503 no depende del correo.
// =====================================================================

/** Tiempo de vida de una solicitud NO verificada (desde `creado_en`). Decision del propietario, 2026-10-14. */
internal const val ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS = 3_600L

/**
 * Codigos erroneos que aguanta una solicitud sin verificar (`intentos_verificacion`). Al llegar aqui queda AGOTADA: se
 * libera con la misma regla que una caducada y quien la pidio tiene que volver a solicitar el acceso (revision M2).
 */
internal const val ADMIN_ACCESS_VERIFY_MAX_FAILURES = 5

/** Como mucho tantas filas por correo en la lectura de la guarda (`usuario_id` es UNIQUE; el correo, en la practica, tambien). */
internal const val ACCESS_REQUEST_HOLD_LOOKUP_LIMIT = 10

/**
 * `true` = hay una solicitud de acceso que sigue reteniendo ese correo: la ruta NO debe pedir a GoTrue que envie nada.
 * [operation] = la ruta que pregunta (`REGISTER`, `RESEND` o `RECOVER`): `REGISTER` libera cualquier solicitud sin verificar,
 * las otras dos solo las caducadas o agotadas (M4).
 */
internal fun interface AccessRequestMailGuard {
    suspend fun holdsMail(normalizedEmail: String, operation: OposiBotsAuthOperation): Boolean
}

/**
 * Como se acuño la sesion que la puerta esta a punto de entregar (H1). Decide que hace la puerta con una solicitud SIN
 * verificar: [trustsMailboxOwner] = la sesion la obtuvo el titular del buzon o de la identidad.
 */
internal enum class AccessRequestSessionGrant(val trustsMailboxOwner: Boolean) {
    /** `grant_type=password`: la contrasena puede ser la que fijo el creador de la solicitud. */
    PASSWORD(false),

    /**
     * `grant_type=refresh_token`: hereda la credencial que acuño la sesion original, que la puerta no conoce (pudo acuñarse
     * directamente contra GoTrue con la contrasena del creador). Se trata como [PASSWORD] (deny-by-default).
     */
    REFRESH(false),

    /** `grant_type=id_token` (Google, Apple): el titular de la identidad. */
    OAUTH(true),

    /** `/verify` con `token_hash` (enlace del correo de confirmacion): el titular del buzon. */
    EMAIL_LINK(true),
}

/**
 * Lo que la puerta decide con UNA lectura y SIN efectos (revision M-1, 2026-10-14). La limpieza que haga falta va en la
 * propia decision y la ejecuta quien llama ([AccessRequestSessionGate.cleanUp]) DESPUES de que la lectura haya salido bien,
 * fuera del bucle de reintentos y de los limites por intento, y sin poder cancelarse a medias.
 */
internal sealed interface AccessRequestGateDecision {
    /** Sin solicitud que lo impida: la sesion se entrega. */
    data object Allow : AccessRequestGateDecision

    /**
     * Se deniega SIEMPRE (403 y revocacion global). [neutralise] = solicitud sin verificar cuya credencial (la que fijo el
     * creador) hay que dejar inservible y cuya fila hay que borrar; `null` = no se toca nada (verificada, o cuenta con roles).
     */
    data class Deny(val neutralise: AccessRequestReservation? = null) : AccessRequestGateDecision

    /**
     * Sesion del titular del buzon o de la identidad (OAuth, enlace) sobre una solicitud SIN verificar y sin roles: se
     * entrega SOLO si antes se neutraliza la credencial del creador, se revocan sus demas sesiones (`scope=others` con la
     * sesion recien acuñada) y se borra la fila. Si algo de eso falla: se deniega (503).
     */
    data class AllowAfterCleanup(val row: AccessRequestReservation) : AccessRequestGateDecision
}

/**
 * La puerta de sesion de las solicitudes de acceso. No tiene valor por defecto en ningun constructor de produccion: quien
 * monta el gateway decide explicitamente que puerta pone.
 */
internal fun interface AccessRequestSessionGate {
    /** SOLO lecturas: se puede repetir y cancelar sin dejar nada a medias. Un fallo de lectura lanza (falla cerrado). */
    suspend fun decide(userId: String, grant: AccessRequestSessionGrant): AccessRequestGateDecision

    /**
     * Ejecuta la limpieza de [decision]. `true` = la sesion puede entregarse. [revokeOtherSessions] revoca las demas
     * sesiones del usuario con la sesion recien acuñada (`logout?scope=others`) y lanza si no lo consigue. Lanza si algo
     * falla; quien llama lo convierte en denegacion. Por defecto (puertas sin efectos) solo [AccessRequestGateDecision.Allow]
     * entrega la sesion.
     */
    suspend fun cleanUp(decision: AccessRequestGateDecision, grant: AccessRequestSessionGrant, revokeOtherSessions: suspend () -> Unit): Boolean =
        decision == AccessRequestGateDecision.Allow
}

/** Lo que la guarda necesita de una solicitud NO aprobada. */
internal data class AccessRequestReservation(
    val id: String,
    val usuarioId: String,
    val estado: String,
    val correoVerificadoEn: String?,
    val creadoEn: String?,
    val intentosVerificacion: Int = 0,
) {
    val verified: Boolean get() = correoVerificadoEn != null

    /** Sin verificar y con [ADMIN_ACCESS_VERIFY_MAX_FAILURES] codigos erroneos o mas: ya no admite verificar. */
    val failedOut: Boolean get() = !verified && intentosVerificacion >= ADMIN_ACCESS_VERIFY_MAX_FAILURES

    /**
     * Caducada = sin verificar y con mas de una hora desde `creado_en`. Un `creado_en` ausente o ilegible cuenta como
     * caducado (deny-by-default: nunca alarga la vida de una solicitud que no se puede fechar).
     */
    fun isExpired(now: Instant): Boolean {
        if (verified) return false
        val created = creadoEn?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return true
        return !created.plusSeconds(ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS).isAfter(now)
    }

    /** Sin verificar y ya sin vida (caducada o agotada): cualquiera puede liberarla (M4). */
    fun isDead(now: Instant): Boolean = !verified && (isExpired(now) || failedOut)
}

/**
 * Estado de la cuenta de Auth de una solicitud (`GET /auth/v1/admin/users/{id}`). [banned] = `banned_until` en el futuro
 * (o presente e ilegible: deny-by-default). Revision #3 M1: una cuenta baneada no admite el inicio con contrasena que hace
 * la neutralizacion, asi que la regla de liberacion la BORRA en vez de neutralizarla.
 */
internal data class AuthAccountState(val emailConfirmed: Boolean, val banned: Boolean = false)

/**
 * Lecturas y escrituras de la guarda, con la clave de servicio. La implementa [SupabaseAdminAccessRequestGateway]. Toda
 * lectura que falla LANZA (la guarda falla cerrado); ningun metodo devuelve un «no» por un error.
 */
internal interface AccessRequestReservationStore {
    /** Solicitudes NO aprobadas de ese correo canonico (como mucho [ACCESS_REQUEST_HOLD_LOOKUP_LIMIT]). */
    suspend fun nonApprovedByEmail(email: String): List<AccessRequestReservation>

    /** La solicitud NO aprobada de ese usuario (`usuario_id` UNIQUE: como mucho una). */
    suspend fun nonApprovedByUser(userId: String): AccessRequestReservation?

    /** `null` = la cuenta de Auth no existe (404). */
    suspend fun authAccount(usuarioId: String): AuthAccountState?

    /** `true` = la cuenta tiene al menos una fila en `roles_usuario`. */
    suspend fun hasAnyRole(usuarioId: String): Boolean

    /** Borra la cuenta de Auth (`DELETE /auth/v1/admin/users/{id}`); su solicitud se va en cascada. Idempotente (404 = ya no esta). */
    suspend fun deleteAccount(usuarioId: String)

    /** Borra la fila SOLO si sigue sin verificar y no aprobada. `true` = se borro una fila. */
    suspend fun deleteUnverifiedRequest(id: String): Boolean

    /**
     * H1: deja inservible la contrasena que fijo quien creo la solicitud. `PUT /auth/v1/admin/users/{id}` con una contrasena
     * ALEATORIA generada aqui (nunca se registra ni sale). Con [revokeSessions], ademas se revocan TODAS las sesiones y
     * refresh tokens de la cuenta (inicio con esa contrasena nueva + `logout?scope=global`). Lanza si algo falla.
     */
    suspend fun neutraliseCredential(usuarioId: String, revokeSessions: Boolean)

    /** Una fila de `auditoria_log` SIN actor (plano publico: no hay persona autenticada). Lanza si no se puede escribir. */
    suspend fun audit(accion: String, registroId: String, despues: JsonObject)
}

/** Por que una solicitud sin verificar deja de reservar el correo (va a la auditoria, nunca al cliente). Solo se anade al final. */
internal enum class AccessRequestReleaseMotive(val wire: String) {
    /** El titular del correo se registra, pide el reenvio o recupera la contrasena. */
    MAILBOX_OWNER("reclamada_por_el_titular_del_correo"),

    /** Una nueva solicitud para el mismo correo encuentra la anterior caducada. */
    EXPIRED("caducada_sin_verificar"),

    /** El titular obtuvo una sesion de OAuth o de enlace de correo (la cuenta ya estaba confirmada): solo se borra la fila. */
    SESSION_OWNER("sesion_del_titular_de_la_cuenta"),

    /** Se agotaron los intentos de codigo ([ADMIN_ACCESS_VERIFY_MAX_FAILURES]). */
    VERIFY_ATTEMPTS_EXHAUSTED("intentos_de_verificacion_agotados"),

    /** Una sesion de CONTRASENA (o refresh) sobre una cuenta confirmada con la solicitud sin verificar (H1). */
    SESSION_PASSWORD_GRANT("sesion_de_contrasena_sobre_solicitud_sin_verificar"),
}

/** `motivo` de la auditoria cuando la cuenta se conserva y se neutraliza la credencial del creador de la solicitud (H1). */
internal const val ACCESS_REQUEST_CREDENTIAL_NEUTRALISED_MOTIVE = "credencial_neutralizada"

/**
 * La regla de las rutas de correo (y de una nueva solicitud ante una caducada o agotada): `true` = la solicitud ya NO
 * reserva el correo (auditado); `false` = sigue reteniendo.
 *
 *  - Cuenta con ALGUN rol -> nunca se toca: retiene (WARN).
 *  - Cuenta que ya no existe -> no se toca (la FK en cascada no deberia dejar la fila): retiene (WARN).
 *  - Cuenta NUNCA confirmada -> se borra (la fila se va en cascada).
 *  - Cuenta CONFIRMADA y BANEADA sin roles (revision #3 M1) -> se borra igual. Solo llega aqui una verificacion que baneo la
 *    cuenta y fallo antes de marcar la fila: la confirmo nuestro OTP, nadie tiene una credencial utilizable (la contrasena es
 *    la aleatoria del servidor o la que se estaba fijando) y GoTrue no deja iniciar sesion a una cuenta baneada, asi que la
 *    neutralizacion (que inicia sesion para revocar) fallaria SIEMPRE y el correo quedaria con 503 para siempre.
 *  - Cuenta CONFIRMADA sin roles (H1) -> se neutraliza la credencial del creador (contrasena aleatoria y revocacion global),
 *    se borra SOLO la fila y la cuenta queda para el titular del buzon. Si la fila ya no se puede borrar (alguien la
 *    verifico entre medias), decide la fila de ahora.
 *
 * Los fallos de lectura y de escritura se propagan (quien llama falla cerrado). La auditoria va DESPUES y es de mejor
 * esfuerzo: si falla, queda un ERROR con los ids.
 */
internal suspend fun AccessRequestReservationStore.releaseUnverifiedAccount(
    reservation: AccessRequestReservation,
    motive: AccessRequestReleaseMotive,
    now: Instant,
    emailRef: String,
): Boolean {
    if (reservation.verified) return false
    val account = authAccount(reservation.usuarioId)
    if (account == null) {
        ReservationLogger.warn(
            "unverified access request kept: its auth account no longer exists requestRowId={} emailRef={}",
            reservation.id,
            emailRef,
        )
        return false
    }
    if (hasAnyRole(reservation.usuarioId)) {
        ReservationLogger.warn(
            "unverified access request kept: its auth account has roles, never touched requestRowId={} authUserId={} emailRef={}",
            reservation.id,
            reservation.usuarioId,
            emailRef,
        )
        return false
    }
    if (!account.emailConfirmed || account.banned) {
        deleteAccount(reservation.usuarioId)
        auditRelease(reservation, motive, now, accountDeleted = true, accountBanned = account.banned)
        ReservationLogger.info(
            "unverified access request released motive={} requestRowId={} authUserId={} emailRef={} (account deleted, confirmed={} banned={})",
            motive.wire,
            reservation.id,
            reservation.usuarioId,
            emailRef,
            account.emailConfirmed,
            account.banned,
        )
        return true
    }
    return neutraliseAndDropRow(reservation, motive, now, emailRef, revokeSessions = true)
}

/**
 * H1: cuenta CONFIRMADA (por el titular del buzon) sobre una solicitud que nadie verifico. Primero la credencial (asi un
 * fallo deja la fila y el reintento lo repite todo), despues la fila condicionada y al final la auditoria.
 * `true` = la fila ya no esta; `false` = la fila sigue (alguien la verifico a la vez): retiene.
 */
private suspend fun AccessRequestReservationStore.neutraliseAndDropRow(
    reservation: AccessRequestReservation,
    motive: AccessRequestReleaseMotive,
    now: Instant,
    emailRef: String,
    revokeSessions: Boolean,
): Boolean {
    neutraliseCredential(reservation.usuarioId, revokeSessions)
    if (!deleteUnverifiedRequest(reservation.id)) {
        val current = nonApprovedByUser(reservation.usuarioId)
        if (current != null) {
            ReservationLogger.warn(
                "unverified access request changed while its credential was neutralised: kept requestRowId={} authUserId={} emailRef={}",
                reservation.id,
                reservation.usuarioId,
                emailRef,
            )
            return false
        }
    }
    auditRelease(reservation, motive, now, accountDeleted = false, credentialNeutralised = true)
    ReservationLogger.warn(
        "unverified access request released motive={} requestRowId={} authUserId={} emailRef={} " +
            "(confirmed account kept, creator credential neutralised)",
        motive.wire,
        reservation.id,
        reservation.usuarioId,
        emailRef,
    )
    return true
}

private suspend fun AccessRequestReservationStore.auditRelease(
    reservation: AccessRequestReservation,
    motive: AccessRequestReleaseMotive,
    now: Instant,
    accountDeleted: Boolean,
    credentialNeutralised: Boolean = false,
    accountBanned: Boolean = false,
) {
    val requestId: String? = MDC.get(MDC_REQUEST_ID)
    try {
        audit(
            ACTION_ACCESS_REQUEST_EXPIRED,
            reservation.id,
            buildJsonObject {
                put("usuarioId", reservation.usuarioId)
                put("estado", reservation.estado)
                if (credentialNeutralised) {
                    put("motivo", ACCESS_REQUEST_CREDENTIAL_NEUTRALISED_MOTIVE)
                    put("origen", motive.wire)
                } else {
                    put("motivo", motive.wire)
                }
                put("correoVerificado", false)
                put("caducada", reservation.isExpired(now))
                put("intentosVerificacion", reservation.intentosVerificacion)
                put("cuentaEliminada", accountDeleted)
                put("credencialNeutralizada", credentialNeutralised)
                if (accountBanned) put("cuentaBaneada", true)
                requestId?.let { put("requestId", it) }
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ReservationLogger.error(
            "access request release audit failed accion={} requestRowId={} authUserId={} error={}",
            ACTION_ACCESS_REQUEST_EXPIRED,
            reservation.id,
            reservation.usuarioId,
            failure::class.simpleName,
        )
    }
}

/**
 * La guarda real. [store] habla con PostgREST y con la API de administracion de GoTrue con la clave de servicio. [emailRef]
 * es el seudonimo CON CLAVE del correo para los logs ([authLogEmailRef]); el correo nunca se escribe.
 *
 * Una sola instancia sirve las dos preguntas (por correo para el envio de correo, por usuario para la entrega de sesion).
 * Las lecturas son `suspend` y cancelables: el refresh acota su puerta con un limite por intento y un plazo global
 * (MASTER3-AUTH-005) y la cancelacion corta la peticion en curso.
 */
internal class PostgrestAccessRequestMailGuard(
    private val store: AccessRequestReservationStore,
    private val emailRef: (String) -> String,
    private val clock: () -> Instant = Instant::now,
) : AccessRequestMailGuard, AccessRequestSessionGate, AutoCloseable {
    override suspend fun holdsMail(normalizedEmail: String, operation: OposiBotsAuthOperation): Boolean {
        // Las rutas ya validaron la forma (misma regla canonica). Si aqui llega otra cosa es el mismo 400 de identificador
        // invalido que da la ruta y no se compone ninguna consulta: depende solo de la forma del correo, no revela nada.
        val email = canonicalAuthEmail(normalizedEmail)?.takeIf(::isValidAuthEmailShape)
            ?: throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_IDENTIFIER)
        val ref = emailRef(email)
        // M4: solo el registro libera una solicitud viva; reenvio y recuperacion, solo las caducadas o agotadas.
        val releasesLiveRequests = operation == OposiBotsAuthOperation.REGISTER
        return failClosed({ "access request mail guard lookup failed: failing closed emailRef=$ref error=$it" }) {
            val now = clock()
            store.nonApprovedByEmail(email).any { row ->
                when {
                    row.verified -> true
                    // M4: una solicitud VIVA solo la libera el registro... o una cuenta que el titular ya confirmo por otra via
                    // (enlace u OAuth: un tercero no puede confirmarla), para que su `recover` salga sin esperar a la caducidad.
                    !releasesLiveRequests && !row.isDead(now) && store.authAccount(row.usuarioId)?.emailConfirmed != true -> true
                    else -> !store.releaseUnverifiedAccount(row, AccessRequestReleaseMotive.MAILBOX_OWNER, now, ref)
                }
            }
        }
    }

    /**
     * La decision, SOLO con lecturas (M-1): la llama la puerta dentro de su lectura acotada y reintentada, asi que repetirla
     * o cortarla a medias no puede dejar nada hecho. Toda escritura va en [cleanUp].
     *
     *  - sin fila -> [AccessRequestGateDecision.Allow];
     *  - verificada y no aprobada -> [AccessRequestGateDecision.Deny] sin limpieza;
     *  - sin verificar con roles -> Deny sin tocar nada (nunca se toca una cuenta con roles);
     *  - sin verificar, sesion de CONTRASENA o refresh (H1: la contrasena puede ser la del creador) -> Deny con neutralizacion;
     *  - sin verificar, sesion de OAuth o enlace (el titular) -> [AccessRequestGateDecision.AllowAfterCleanup].
     */
    override suspend fun decide(userId: String, grant: AccessRequestSessionGrant): AccessRequestGateDecision =
        failClosed({ "access request session gate lookup failed: failing closed error=$it" }) {
            // El id sale del `sub` de la sesion recien acuñada; si no es un UUID no se compone ninguna consulta (falla cerrado).
            require(userId.isUuid()) { "userId" }
            val row = store.nonApprovedByUser(userId)
            when {
                row == null -> AccessRequestGateDecision.Allow
                row.verified -> AccessRequestGateDecision.Deny()
                store.hasAnyRole(row.usuarioId) -> {
                    ReservationLogger.warn(
                        "session gate denied a {} session on an unverified access request whose account has roles (never touched) requestRowId={} authUserId={}",
                        grant.name,
                        row.id,
                        row.usuarioId,
                    )
                    AccessRequestGateDecision.Deny()
                }
                !grant.trustsMailboxOwner -> AccessRequestGateDecision.Deny(neutralise = row)
                else -> AccessRequestGateDecision.AllowAfterCleanup(row)
            }
        }

    /**
     * Las escrituras de la decision. Quien llama la ejecuta UNA vez, despues de la lectura y sin poder cancelarla a medias.
     *
     *  - Deny con neutralizacion (H1): contrasena aleatoria del servidor (sin revocar aqui: la puerta revoca en global con la
     *    sesion recien acuñada), fila borrada y auditoria. Devuelve `false` siempre (se deniega pase lo que pase).
     *  - AllowAfterCleanup: 1) contrasena aleatoria (la que fijo el creador, en filas anteriores a este cambio, deja de valer);
     *    2) revocacion de las DEMAS sesiones con la recien acuñada (`scope=others`: conserva la del titular); 3) borrado
     *    condicionado de la fila; 4) auditoria. Si la fila ya no se puede borrar porque alguien la verifico entre medias,
     *    decide la fila de ahora (deny-by-default). Cualquier fallo lanza y quien llama deniega (503).
     */
    override suspend fun cleanUp(
        decision: AccessRequestGateDecision,
        grant: AccessRequestSessionGrant,
        revokeOtherSessions: suspend () -> Unit,
    ): Boolean = when (decision) {
        AccessRequestGateDecision.Allow -> true
        is AccessRequestGateDecision.Deny -> {
            decision.neutralise?.let { row ->
                store.neutraliseAndDropRow(row, AccessRequestReleaseMotive.SESSION_PASSWORD_GRANT, clock(), "-", revokeSessions = false)
                ReservationLogger.warn(
                    "session gate denied a {} session on an unverified access request requestRowId={} authUserId={} (credential neutralised)",
                    grant.name,
                    row.id,
                    row.usuarioId,
                )
            }
            false
        }
        is AccessRequestGateDecision.AllowAfterCleanup -> {
            val row = decision.row
            store.neutraliseCredential(row.usuarioId, revokeSessions = false)
            revokeOtherSessions()
            val allowed = if (store.deleteUnverifiedRequest(row.id)) {
                true
            } else {
                // No se borro nada: alguien la verifico (o la cambio) entre la lectura y el borrado. Decide la fila de AHORA.
                store.nonApprovedByUser(row.usuarioId) == null
            }
            if (allowed) {
                store.auditRelease(row, AccessRequestReleaseMotive.SESSION_OWNER, clock(), accountDeleted = false, credentialNeutralised = true)
                ReservationLogger.warn(
                    "unverified access request row removed by the session gate requestRowId={} authUserId={} grant={} " +
                        "(creator credential neutralised, other sessions revoked, session allowed)",
                    row.id,
                    row.usuarioId,
                    grant.name,
                )
            }
            allowed
        }
    }

    private suspend fun <T> failClosed(message: (String?) -> String, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (problem: OposiBotsAuthProblem) {
        throw problem
    } catch (failure: Exception) {
        // El transporte puede envolver una cancelacion en su propio error: si fue una cancelacion, sigue siendolo.
        currentCoroutineContext().ensureActive()
        // Solo la clase: el mensaje de un error de PostgREST puede reflejar el valor filtrado (el correo).
        ReservationLogger.warn(message(failure::class.simpleName))
        throw OposiBotsAuthProblem.unavailable()
    }

    override fun close() {
        (store as? AutoCloseable)?.close()
    }
}

/** Columnas que lee la guarda. */
internal const val ACCESS_REQUEST_RESERVATION_COLUMNS = "id,usuario_id,estado,correo_verificado_en,creado_en,intentos_verificacion"

@Serializable
internal data class AccessRequestReservationRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    val estado: String,
    @SerialName("correo_verificado_en") val correoVerificadoEn: String? = null,
    @SerialName("creado_en") val creadoEn: String? = null,
    @SerialName("intentos_verificacion") val intentosVerificacion: Int = 0,
) {
    fun toReservation() = AccessRequestReservation(id, usuarioId, estado, correoVerificadoEn, creadoEn, intentosVerificacion)
}

/** Las solicitudes no aprobadas de un correo. El correo va urlencoded, nunca crudo. */
internal fun accessRequestHoldPath(normalizedEmail: String): String =
    "/rest/v1/$ADMIN_ACCESS_TABLE?select=$ACCESS_REQUEST_RESERVATION_COLUMNS&email=eq.${encodeQuery(normalizedEmail)}" +
        "&estado=neq.$ADMIN_ACCESS_ESTADO_APROBADA&order=creado_en.asc&limit=$ACCESS_REQUEST_HOLD_LOOKUP_LIMIT"

/** La misma pregunta por usuario (`usuario_id` UNIQUE: como mucho una fila). [userId] llega ya validado como UUID. */
internal fun accessRequestSessionPath(userId: String): String =
    "/rest/v1/$ADMIN_ACCESS_TABLE?select=$ACCESS_REQUEST_RESERVATION_COLUMNS&usuario_id=eq.${userId.lowercase()}" +
        "&estado=neq.$ADMIN_ACCESS_ESTADO_APROBADA&limit=1"

/**
 * Seudonimo del correo para los logs del plano de auth: HMAC-SHA-256 con el secreto del limitador
 * (`AUTH_RATE_LIMIT_HMAC_SECRET`, [RateLimitKeyHasher]) en vez de un SHA-256 sin clave: sin ese secreto, un correo
 * candidato ya no se puede comprobar contra los logs. El prefijo lo separa de las claves del propio limitador. El correo
 * entra en su forma canonica ([canonicalAuthEmail]).
 */
internal fun OposiBotsAuthConfiguration.authLogEmailRef(email: String): String = authLogEmailRef(rateLimitHmacSecret, email)

internal fun authLogEmailRef(secret: String, email: String): String =
    RateLimitKeyHasher(secret).hash("auth-log-email-ref:" + (canonicalAuthEmail(email) ?: email.trim())).take(12)

/**
 * Lo que llaman las tres rutas, DESPUES del tope silencioso por email (asi la lectura tambien queda acotada por el) y
 * ANTES del gateway. `true` = retenido: la ruta responde su exito normal sin llamar a GoTrue.
 */
internal suspend fun OposiBotsAuthRuntime.accessRequestHoldsMail(operation: OposiBotsAuthOperation, normalizedEmail: String): Boolean {
    val held = accessRequestMailGuard.holdsMail(normalizedEmail, operation)
    if (held) {
        ReservationLogger.info(
            "auth operation={} held by a non-approved admin access request: silent success emailRef={}",
            operation.name,
            configuration.authLogEmailRef(normalizedEmail),
        )
    }
    return held
}

private val ReservationLogger = LoggerFactory.getLogger("OC-KTOR-AUTH")
