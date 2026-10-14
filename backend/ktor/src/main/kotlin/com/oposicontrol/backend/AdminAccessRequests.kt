package com.oposicontrol.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory

/**
 * Solicitudes de acceso de administrador.
 *
 * Solo `admin` y `superadmin` entran en OposiControl. Quien no lo es puede PEDIRLO desde la pantalla de acceso:
 *
 *  1. `POST /v1/auth/admin-access-requests` (`{nombre, email, motivo}`; publica, plano de auth, pool `bot`): crea la cuenta
 *     de Auth con el correo y una contrasena ALEATORIA del servidor (revision 2026-10-14: nunca una elegida por quien
 *     pide, que aun no ha demostrado el buzon), SIN ningun rol y con el correo SIN CONFIRMAR, guarda la solicitud como `pendiente` y pide a
 *     Supabase Auth el correo de CODIGO (`POST /auth/v1/otp {email, create_user:false}`: plantilla «Magic Link», que el
 *     propietario configura con SOLO `{{ .Token }}` y el texto de la solicitud de acceso, sin enlace a la app). Revision de
 *     seguridad H1 (2026-10-14): ya NO se usa el correo «Confirm signup» (lleva el enlace del consumidor, que confirmaba la
 *     cuenta del solicitante sin pasar por aqui).
 *  2. `POST /v1/auth/admin-access-requests/verify` (`{email, code, password}`): quien pidio el acceso demuestra que el buzon
 *     es suyo con ese codigo (`POST /auth/v1/verify {type:"email", email, token}`) y elige su contrasena. La sesion que GoTrue
 *     entrega al verificar se revoca al instante y no sale nunca; la cuenta se BANEA en GoTrue (M1: asi una contrasena contra
 *     GoTrue directo no da un JWT valido mientras no se apruebe), se fija la contrasena elegida y se marca
 *     `correo_verificado_en`. Cada codigo erroneo suma `intentos_verificacion`;
 *     al quinto la solicitud se libera y hay que volver a pedirla (M2). `POST .../resend-code` reenvia el codigo.
 *  3. `GET /api/v1/admin/solicitudes-acceso` y `POST .../{id}/aprobar|rechazar` (plano admin, pool `control`): SOLO un
 *     superadministrador. Aprobar EXIGE la solicitud verificada (`correo_verificado_en`) Y la cuenta con el correo
 *     confirmado en Supabase Auth (doble comprobacion); entonces (L1) marca la solicitud `aprobada` CONDICIONADA a seguir
 *     `pendiente`, levanta el baneo y concede `admin` (nunca `superadmin`) por el camino auditado que ya existe
 *     ([AdminV1Gateway.syncRoles] -> RPC `admin_sync_roles_audited`, con el actor del JWT). Si el desbaneo o el rol fallan, la
 *     marca se revierte a `pendiente` (condicionada) y responde 503 (revision #3: antes del desbaneo se purgan las sesiones;
 *     si el re-baneo de la reversion falla, la marca se queda `aprobada` para que repetir la aprobacion la reanude). Aprobar ya NO confirma ningun correo. Rechazar borra la
 *     cuenta.
 *
 * Una solicitud NO verificada caduca una hora despues de crearse y no reserva el correo (AccessRequestMailGuard.kt).
 *
 * La contrasena viaja del formulario a este backend (TLS) y de aqui a Supabase Auth: no se registra en logs, no se guarda
 * en ninguna tabla nuestra, no vuelve en ninguna respuesta ni en ningun mensaje de error y nunca va en una URL. Lo mismo el
 * codigo de verificacion y la sesion de la verificacion.
 *
 * Las respuestas publicas no sirven para enumerar cuentas: el alta y el reenvio contestan SIEMPRE 202; la verificacion
 * contesta 204 o UN UNICO 400 (codigo erroneo o caducado, correo desconocido o sin solicitud pendiente, todo igual).
 */

// ---------------------------------------------------------------------------------------------------------------------
// Contrato
// ---------------------------------------------------------------------------------------------------------------------

internal const val ADMIN_ACCESS_NOMBRE_MAX = 120
internal const val ADMIN_ACCESS_MOTIVO_MAX = 1_000
internal const val ADMIN_ACCESS_MOTIVO_RESOLUCION_MAX = 500
internal const val ADMIN_ACCESS_LIST_DEFAULT_LIMIT = 100
internal const val ADMIN_ACCESS_LIST_MAX_LIMIT = 200

internal const val ADMIN_ACCESS_ESTADO_PENDIENTE = "pendiente"
internal const val ADMIN_ACCESS_ESTADO_APROBADA = "aprobada"
internal const val ADMIN_ACCESS_ESTADO_RECHAZADA = "rechazada"
internal val ADMIN_ACCESS_ESTADOS: Set<String> =
    setOf(ADMIN_ACCESS_ESTADO_PENDIENTE, ADMIN_ACCESS_ESTADO_APROBADA, ADMIN_ACCESS_ESTADO_RECHAZADA)

internal const val ADMIN_ACCESS_TABLE = "solicitudes_acceso_admin"
internal const val ACTION_ACCESS_REQUEST_APPROVED = "solicitud_acceso_aprobada"
internal const val ACTION_ACCESS_REQUEST_REJECTED = "solicitud_acceso_rechazada"
internal const val ACTION_ACCESS_REQUEST_ACCOUNT_DELETED = "solicitud_acceso_cuenta_eliminada"

/** El solicitante demostro el buzon con el codigo (plano publico: sin actor). */
internal const val ACTION_ACCESS_REQUEST_EMAIL_VERIFIED = "solicitud_acceso_email_verificado"

/** Una solicitud sin verificar dejo de reservar el correo (titular, caducidad o sesion): plano publico, sin actor. */
internal const val ACTION_ACCESS_REQUEST_EXPIRED = "solicitud_acceso_caducada"

/** L1: la aprobacion se marco pero el desbaneo o el rol fallaron; la marca se intento revertir a `pendiente`. */
internal const val ACTION_ACCESS_REQUEST_APPROVAL_REVERTED = "solicitud_acceso_aprobacion_revertida"

/** M1: duracion del baneo de una cuenta verificada y no aprobada (100 anos: GoTrue no tiene «indefinido»). */
internal const val ADMIN_ACCESS_BAN_DURATION = "876000h"

/** M1: valor de GoTrue que levanta el baneo. */
internal const val ADMIN_ACCESS_UNBAN = "none"

/** Revision #3 L1: RPC (solo `service_role`) que borra las sesiones de Auth de una cuenta (`20261020100000`). */
internal const val ADMIN_PURGE_USER_SESSIONS_RPC = "admin_purge_user_sessions"

/**
 * Revision #3 M1: estados con los que GoTrue rechaza la CONTRASENA en `PUT /auth/v1/admin/users/{id} {password}` (400/422,
 * p. ej. `weak_password`). Cualquier otro (401/403 de la clave, 404 cuenta ausente, 429, 5xx) es una caida: 503.
 */
private val ADMIN_ACCESS_PASSWORD_REJECTED_STATUSES = setOf(400, 422)

/** El codigo de verificacion: exactamente 6 cifras ASCII. */
private val ADMIN_ACCESS_CODE_REGEX = Regex("[0-9]{6}")

/**
 * Cuerpo de la solicitud publica: `{nombre, email, motivo}`. Revision de seguridad 2026-10-14 (DO_NOT_SHIP): ya NO lleva
 * contrasena; la cuenta se crea con una aleatoria del servidor y quien pidio el acceso elige la suya al verificar el codigo
 * (cuando ya demostro el buzon). Un campo `password` de un cliente antiguo se IGNORA, como cualquier campo desconocido del
 * plano de auth (`Json { ignoreUnknownKeys = true }` de [OposiBotsAuthRuntime.json]): nunca llega a ningun sitio.
 */
@Serializable
internal class AdminAccessRequestBody(
    val nombre: String = "",
    val email: String = "",
    val motivo: String = "",
) {
    override fun toString(): String = "AdminAccessRequestBody(<redactado>)"
}

/**
 * Cuerpo de `POST /v1/auth/admin-access-requests/verify`: `{email, code, password}`. [password] es la contrasena con la que
 * quien pidio el acceso entrara al panel (regla de contrasena nueva, 12..72 ASCII imprimible). `toString` NO imprime nada.
 */
@Serializable
internal class AdminAccessVerifyBody(
    val email: String = "",
    val code: String = "",
    val password: String = "",
) {
    override fun toString(): String = "AdminAccessVerifyBody(<redactado>)"
}

/** Cuerpo de `POST /v1/auth/admin-access-requests/resend-code`. */
@Serializable
internal class AdminAccessResendCodeBody(val email: String = "")

/** La solicitud ya validada y normalizada (sin contrasena). `toString` NO imprime nada: lleva datos personales. */
internal class AdminAccessSubmission(
    val nombre: String,
    val email: String,
    val motivo: String,
) {
    override fun toString(): String = "AdminAccessSubmission(<redactado>)"
}

/** Lo que paso al registrar la solicitud. NUNCA sale al cliente: la respuesta publica es la misma en todos los casos. */
internal enum class AdminAccessSubmitOutcome {
    /** Cuenta creada sin rol y solicitud `pendiente` guardada (y se pide el correo con el codigo). */
    CREATED,

    /** El correo ya tenia cuenta: no se crea nada ni se toca la cuenta existente. */
    ALREADY_REGISTERED,

    /** Supabase Auth rechazo los datos (4xx que no es duplicado): no se crea nada. */
    REJECTED_BY_AUTH,

    /** Ya hay una solicitud viva para ese correo (verificada, o sin verificar y sin caducar): no se crea nada. */
    ALREADY_REQUESTED,
}

/** Lo que devuelve GoTrue al verificar un codigo valido. Nunca sale de este fichero (la sesion se revoca al instante). */
internal class VerifiedSignup(val userId: String?, val accessToken: String?) {
    override fun toString(): String = "VerifiedSignup(<redactado>)"
}

/** Respuesta publica UNICA (202) del alta y del reenvio del codigo. */
@Serializable
internal data class AdminAccessRequestAccepted(val estado: String)

/**
 * Una solicitud en el panel. `correoVerificado` y `caducada` se calculan al leer: `caducada` = sin verificar, todavia
 * `pendiente` y con mas de una hora desde `creadoEn` ([ADMIN_ACCESS_UNVERIFIED_TTL_SECONDS]). Solo se puede aprobar una
 * solicitud con `correoVerificado = true` (y la cuenta confirmada en Supabase Auth).
 */
@Serializable
data class AdminAccessRequestDto(
    val id: String,
    val usuarioId: String,
    val nombre: String,
    val email: String,
    val motivo: String,
    val estado: String,
    val creadoEn: String,
    val correoVerificado: Boolean,
    val caducada: Boolean,
    val correoVerificadoEn: String? = null,
    val resueltoEn: String? = null,
    val resueltoPor: String? = null,
    val motivoResolucion: String? = null,
)

@Serializable
data class AdminAccessRequestListResponse(val items: List<AdminAccessRequestDto>)

internal interface AdminAccessRequestGateway : AccessRequestReservationStore {
    /** Crea la cuenta de Auth (sin rol) y la solicitud `pendiente` como UNA unidad; ver [SupabaseAdminAccessRequestGateway]. */
    suspend fun submit(submission: AdminAccessSubmission): AdminAccessSubmitOutcome

    /** Pendientes primero; despues las resueltas, la mas reciente arriba. [estado] `null` = todas. */
    suspend fun list(estado: String?, limit: Int, offset: Int): List<AdminAccessRequestDto>

    suspend fun find(id: String): AdminAccessRequestDto?

    /**
     * Pasa una solicitud `pendiente` a [estado] (`aprobada` / `rechazada`). `null` = ya no estaba pendiente (o no existe):
     * la escritura esta CONDICIONADA al estado, asi que dos revisores a la vez no se pisan.
     */
    suspend fun resolve(id: String, estado: String, actorId: String, motivo: String?): AdminAccessRequestDto?

    /**
     * H1: pide a GoTrue el correo con el CODIGO de la solicitud de acceso: `POST /auth/v1/otp {email, create_user:false}`
     * (plantilla «Magic Link», solo `{{ .Token }}`), con la clave de servicio. NUNCA el correo «Confirm signup» (su enlace es
     * el del consumidor). Es un camino INTERNO: no pasa por la guarda de correo de las rutas de consumidor. Un 4xx (p. ej.
     * 429: GoTrue limita a un correo por minuto) se registra y no lanza; red o 5xx lanzan.
     */
    suspend fun sendAccessCode(email: String)

    /**
     * Comprueba el codigo con GoTrue (`POST /auth/v1/verify {type:"email", email, token}`). `null` = rechazado (4xx distinto
     * de 429: codigo erroneo, caducado o ya usado). 429 = 503 con `Retry-After`. Red o 5xx lanzan. Si lo acepta, GoTrue
     * confirma el correo y devuelve una sesion: quien llama la revoca con [revokeSession] y no la entrega nunca.
     */
    suspend fun verifyAccessCode(email: String, code: String): VerifiedSignup?

    /** Revoca TODAS las sesiones del usuario de [accessToken] (`POST /auth/v1/logout?scope=global`). Mejor esfuerzo: `true` = hecho. */
    suspend fun revokeSession(accessToken: String): Boolean

    /**
     * `correo_verificado_en = now()` CONDICIONADO a: esa fila, ese usuario, `pendiente` y todavia sin verificar. `null` = no
     * se cambio ninguna fila (otra peticion la verifico antes, o ya no esta pendiente).
     */
    suspend fun markEmailVerified(id: String, usuarioId: String): AdminAccessRequestDto?

    /**
     * M1: banea ([banned] = `true`, [ADMIN_ACCESS_BAN_DURATION]) o levanta el baneo ([ADMIN_ACCESS_UNBAN]) de la cuenta con
     * `PUT /auth/v1/admin/users/{id} {ban_duration}`. Lanza si GoTrue no lo confirma (2xx).
     */
    suspend fun setBanned(usuarioId: String, banned: Boolean)

    /**
     * Fija la contrasena que eligio quien demostro el buzon (`PUT /auth/v1/admin/users/{id} {password}`), solo al verificar
     * el codigo. Lanza si GoTrue no lo confirma (2xx). La contrasena nunca se registra ni sale en un error.
     */
    suspend fun setPassword(usuarioId: String, password: String)

    /**
     * Revision #3 L1: borra TODAS las sesiones de Auth de la cuenta (y sus refresh tokens) con la RPC
     * [ADMIN_PURGE_USER_SESSIONS_RPC] (migracion `20261020100000`, solo `service_role`). Un baneo de GoTrue no borra
     * `auth.sessions`: sin esto, una sesion acuñada antes del baneo reviviria al desbanear. Devuelve cuantas sesiones borro.
     * Lanza si no se puede confirmar (quien llama falla cerrado).
     */
    suspend fun purgeSessions(usuarioId: String): Int

    /**
     * M2: suma un codigo erroneo a una solicitud viva sin verificar (`intentos_verificacion + 1`, con concurrencia optimista
     * sobre el valor leido). Devuelve el contador nuevo, o `null` si la fila ya no esta viva (verificada, resuelta o borrada).
     */
    suspend fun recordFailedVerification(reservation: AccessRequestReservation): Int?

    /**
     * L1: deshace una aprobacion cuyo desbaneo o rol fallo: vuelve a `pendiente` CONDICIONADO a que siga `aprobada` y
     * resuelta por [actorId]. `null` = no se cambio ninguna fila.
     */
    suspend fun revertApproval(id: String, actorId: String): AdminAccessRequestDto?
}

// ---------------------------------------------------------------------------------------------------------------------
// Validacion de la solicitud publica
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Normaliza y valida. Email con la MISMA regla que el registro ([requireCanonicalAuthEmail]: correo canonico unico con forma
 * de email y 3..320). Nombre 1..120 puntos de codigo sin caracteres invisibles ([isAcceptableDisplayName]); motivo 1..1000
 * sin caracteres de control salvo saltos de linea y tabulador. La contrasena ya no va aqui: se elige al verificar.
 */
internal fun AdminAccessRequestBody.validated(): AdminAccessSubmission {
    val cleanEmail = requireCanonicalAuthEmail(email)
    val cleanNombre = nombre.trim()
    val cleanMotivo = motivo.trim()
    val issues = buildList {
        when {
            cleanNombre.isEmpty() -> add(ErrorIssue("nombre", CanonicalIssue.REQUIRED))
            cleanNombre.codePointCount(0, cleanNombre.length) > ADMIN_ACCESS_NOMBRE_MAX -> add(ErrorIssue("nombre", CanonicalIssue.OUT_OF_RANGE))
            !isAcceptableDisplayName(cleanNombre) -> add(ErrorIssue("nombre", CanonicalIssue.INVALID_FORMAT))
        }
        when {
            cleanMotivo.isEmpty() -> add(ErrorIssue("motivo", CanonicalIssue.REQUIRED))
            cleanMotivo.codePointCount(0, cleanMotivo.length) > ADMIN_ACCESS_MOTIVO_MAX -> add(ErrorIssue("motivo", CanonicalIssue.OUT_OF_RANGE))
            !isAcceptableFreeText(cleanMotivo) -> add(ErrorIssue("motivo", CanonicalIssue.INVALID_FORMAT))
        }
    }
    if (issues.isNotEmpty()) throw validationError(*issues.toTypedArray())
    return AdminAccessSubmission(nombre = cleanNombre, email = cleanEmail, motivo = cleanMotivo)
}

/** Texto libre de varias lineas: se admiten `\n`, `\r` y `\t`; ningun otro caracter de control ni de formato invisible. */
internal fun isAcceptableFreeText(text: String): Boolean =
    isAcceptableDisplayName(text.filterNot { it == '\n' || it == '\r' || it == '\t' })

/**
 * El UNICO 400 de la verificacion: codigo con otra forma, erroneo, caducado o ya usado, correo sin solicitud pendiente sin
 * verificar, solicitud caducada... todo sale igual (`VALIDATION` con la incidencia `INVALID_CREDENTIAL` sobre el campo
 * `credential`), asi que la respuesta no dice cual de ellos fue.
 */
internal fun adminAccessInvalidCode(): OposiBotsAuthProblem = OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_CREDENTIAL)

// ---------------------------------------------------------------------------------------------------------------------
// Rutas publicas (plano de auth)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * `POST /v1/auth/admin-access-requests` y sus dos rutas hermanas (`/verify`, `/resend-code`). Viven bajo `/v1/auth/` porque
 * es el prefijo publico que el borde ya enruta al pool `bot` (`ops/caddy/Caddyfile.production`, matcher `@bot`, prefijo
 * `/v1/auth/`); el borde es deny-by-default y no hace falta tocarlo.
 *
 * Mismo sobre que el resto del plano ([authEndpoint]): limite por IP ANTES de leer el cuerpo (429 + `Retry-After`),
 * cuerpo JSON acotado a 16 KB, `Cache-Control: no-store` y errores con el sobre canonico. El tope por email del alta y del
 * reenvio es SILENCIOSO (misma 202, sin llamar a Supabase): un tercero que nombra el correo de otro no provoca un 429. Los
 * de la verificacion devuelven 429 (son el freno de la fuerza bruta del codigo): (correo, IP) cada 15 min y un techo
 * diario por correo (M2).
 *
 * [gateway] `null` = sin configuracion de Supabase: las rutas existen y fallan cerradas (503).
 */
internal fun Route.adminAccessRequestPublicRoute(runtime: OposiBotsAuthRuntime, gateway: AdminAccessRequestGateway?) {
    post("/v1/auth/admin-access-requests") {
        call.authEndpoint(runtime, OposiBotsAuthOperation.ADMIN_ACCESS_REQUEST) {
            val requests = gateway ?: throw OposiBotsAuthProblem.unavailable()
            val submission = receiveAdminAccessRequestBody(runtime.json).validated()
            val emailRef = runtime.configuration.authLogEmailRef(submission.email)
            if (runtime.rateLimiter.checkSubject(OposiBotsAuthOperation.ADMIN_ACCESS_REQUEST, submission.email).allowed) {
                val outcome = submitClearingDead(requests, submission, emailRef)
                if (outcome == AdminAccessSubmitOutcome.CREATED) sendCodeBestEffort(requests, submission.email, emailRef, "submit")
                // Solo al log, con el seudonimo con clave del correo: el cliente recibe lo mismo en todos los casos.
                AccessRequestLogger.info("admin access request outcome={} emailRef={}", outcome.name, emailRef)
            } else {
                AccessRequestLogger.info("admin access request subject limit reached: silent success emailRef={}", emailRef)
            }
            respond(HttpStatusCode.Accepted, AdminAccessRequestAccepted(estado = "recibida"))
        }
    }
    post("/v1/auth/admin-access-requests/verify") {
        call.authEndpoint(runtime, OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY) {
            val requests = gateway ?: throw OposiBotsAuthProblem.unavailable()
            val body = receiveAdminAccessVerifyBody(runtime.json)
            // La contrasena nueva se valida ANTES de todo (limites y GoTrue): su 400 es de campo (`password`) y distinto del
            // 400 unico del codigo, pero depende solo de la forma de lo enviado, no de que exista ninguna solicitud.
            validateAuthEmailAndPassword(body.email, body.password)
            val email = requireCanonicalAuthEmail(body.email)
            // Los limites van ANTES de mirar el codigo: tambien cuentan los intentos con un codigo mal formado. M2: la clave
            // de 15 min es (correo, IP), como el login; el techo diario es solo por correo.
            requireAuthSubjectLimit(
                runtime,
                OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY,
                loginSubjectKey(email, trustedClientIp(runtime.configuration)),
            )
            requireAuthSubjectLimit(runtime, OposiBotsAuthOperation.ADMIN_ACCESS_VERIFY_DAILY, email)
            val code = body.code.trim()
            if (!ADMIN_ACCESS_CODE_REGEX.matches(code)) throw adminAccessInvalidCode()
            verifyAccessRequestEmail(requests, email, code, body.password, runtime.configuration.authLogEmailRef(email))
            respond(HttpStatusCode.NoContent)
        }
    }
    post("/v1/auth/admin-access-requests/resend-code") {
        call.authEndpoint(runtime, OposiBotsAuthOperation.ADMIN_ACCESS_RESEND_CODE) {
            val requests = gateway ?: throw OposiBotsAuthProblem.unavailable()
            val email = requireCanonicalAuthEmail(receiveAdminAccessResendCodeBody(runtime.json).email)
            val emailRef = runtime.configuration.authLogEmailRef(email)
            if (runtime.rateLimiter.checkSubject(OposiBotsAuthOperation.ADMIN_ACCESS_RESEND_CODE, email).allowed) {
                // Solo hay reenvio si hay una solicitud pendiente, SIN verificar y SIN caducar para ese correo. La lectura
                // falla cerrada (503, no depende del correo); el envio, una vez decidido, nunca cambia la respuesta.
                val now = Instant.now()
                val live = requests.nonApprovedByEmail(email).any { it.isLiveUnverified(now) }
                if (live) sendCodeBestEffort(requests, email, emailRef, "resend-code")
                AccessRequestLogger.info("admin access request resend-code resent={} emailRef={}", live, emailRef)
            } else {
                AccessRequestLogger.info("admin access request resend-code subject limit reached: silent success emailRef={}", emailRef)
            }
            respond(HttpStatusCode.Accepted, AdminAccessRequestAccepted(estado = "recibida"))
        }
    }
}

/** Pendiente, sin verificar, sin caducar y sin agotar: la unica solicitud que admite verificar o reenviar el codigo. */
private fun AccessRequestReservation.isLiveUnverified(now: Instant): Boolean =
    estado == ADMIN_ACCESS_ESTADO_PENDIENTE && !verified && !isDead(now)

/** El motivo de auditoria al liberar una solicitud muerta: agotada (M2) o caducada. */
private fun AccessRequestReservation.deathMotive(): AccessRequestReleaseMotive =
    if (failedOut) AccessRequestReleaseMotive.VERIFY_ATTEMPTS_EXHAUSTED else AccessRequestReleaseMotive.EXPIRED

/**
 * Antes de crear nada se mira si el correo ya tiene una solicitud no aprobada. Verificada, o sin verificar y VIVA: no se
 * crea nada (misma 202). Sin verificar y MUERTA (caducada o agotada): se limpia con la regla de la guarda
 * ([releaseUnverifiedAccount]) y se sigue con el alta; si no se pudo limpiar, tampoco se crea nada.
 */
private suspend fun submitClearingDead(
    requests: AdminAccessRequestGateway,
    submission: AdminAccessSubmission,
    emailRef: String,
): AdminAccessSubmitOutcome {
    val now = Instant.now()
    val blocked = requests.nonApprovedByEmail(submission.email).any { row ->
        row.verified || !row.isDead(now) ||
            !requests.releaseUnverifiedAccount(row, row.deathMotive(), now, emailRef)
    }
    return if (blocked) AdminAccessSubmitOutcome.ALREADY_REQUESTED else requests.submit(submission)
}

/** El correo con el codigo. Un fallo NO cambia la respuesta (202): queda un WARN y el solicitante puede pedir el reenvio. */
private suspend fun sendCodeBestEffort(requests: AdminAccessRequestGateway, email: String, emailRef: String, step: String) {
    try {
        requests.sendAccessCode(email)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AccessRequestLogger.warn(
            "admin access request code mail failed step={} emailRef={} error={}",
            step,
            emailRef,
            failure::class.simpleName,
        )
    }
}

/**
 * La verificacion. Orden:
 *  1. La solicitud: pendiente, sin verificar, sin caducar y sin agotar para ese correo. Si no la hay, el 400 unico SIN
 *     llamar a GoTrue (esta ruta no sirve para confirmar cuentas de consumidor).
 *  2. GoTrue comprueba el codigo. Rechazo = el 400 unico, y antes se suma el fallo a la solicitud (M2); al quinto la
 *     solicitud se libera con la regla de la guarda y hay que volver a pedirla (la respuesta sigue siendo el 400 unico).
 *  3. La sesion que GoTrue acaba de entregar se revoca en global ANTES de nada mas, pase lo que pase despues. Nunca sale.
 *  4. El usuario de esa sesion tiene que ser el de la solicitud; si no, el 400 unico (y un WARN).
 *  5. M1: la cuenta se BANEA en GoTrue antes de marcarla. Una cuenta verificada y no aprobada nunca obtiene un JWT de GoTrue.
 *     Revision #3 L1: justo despues se PURGAN sus sesiones ([AdminAccessRequestGateway.purgeSessions]); el baneo no las
 *     borra y revivirian al desbanear. Si la purga falla: 503 y sin marcar.
 *  6. Revision 2026-10-14 (DO_NOT_SHIP): se fija la contrasena que eligio quien demostro el buzon
 *     (`PUT /auth/v1/admin/users/{id} {password}`). Hasta aqui la cuenta solo tenia la aleatoria del servidor del alta (o,
 *     en filas anteriores a este cambio, la del creador, que asi queda sustituida). Si el baneo o la contrasena fallan: 503 y
 *     la solicitud sigue SIN verificar; la cuenta queda confirmada con una contrasena que nadie conoce (nadie puede entrar
 *     con contrasena) y el solicitante pide otro codigo con `resend-code` y repite. Revision #3 M1: si GoTrue RECHAZA la
 *     contrasena (400/422, p. ej. `weak_password`) la respuesta es el 400 de campo `password`/`INVALID_PASSWORD`, sin sumar
 *     intentos. Si la cuenta queda baneada y sin marcar, la regla de liberacion la BORRA (no puede neutralizarla).
 *  7. `correo_verificado_en` condicionado; auditoria `solicitud_acceso_email_verificado` (mejor esfuerzo).
 */
private suspend fun verifyAccessRequestEmail(
    requests: AdminAccessRequestGateway,
    email: String,
    code: String,
    password: String,
    emailRef: String,
) {
    val now = Instant.now()
    val row = requests.nonApprovedByEmail(email).firstOrNull { it.isLiveUnverified(now) } ?: throw adminAccessInvalidCode()
    val verified = requests.verifyAccessCode(email, code) ?: run {
        recordFailedAttempt(requests, row, now, emailRef)
        throw adminAccessInvalidCode()
    }
    verified.accessToken?.takeIf(String::isNotBlank)?.let { token ->
        if (!requests.revokeSession(token)) {
            AccessRequestLogger.warn("admin access request verify: session revoke failed requestRowId={} emailRef={}", row.id, emailRef)
        }
    } ?: AccessRequestLogger.warn(
        "admin access request verify: GoTrue returned no session to revoke (sessions purged after the ban) requestRowId={} emailRef={}",
        row.id,
        emailRef,
    )
    if (verified.userId?.lowercase() != row.usuarioId.lowercase()) {
        AccessRequestLogger.warn("admin access request verify: verified user does not match the request requestRowId={} emailRef={}", row.id, emailRef)
        throw adminAccessInvalidCode()
    }
    // M1: baneada ANTES de quedar verificada. GoTrue acaba de confirmar el correo; sin el baneo, una contrasena contra GoTrue
    // directo (clave anonima) daria un JWT que `/api/v1/app/*` acepta mientras la solicitud no esta aprobada.
    // Revision #3 L1: el baneo no borra `auth.sessions`; se purgan SIEMPRE despues del baneo (haya devuelto GoTrue sesion o
    // no, y aunque su logout haya fallado). Si la purga falla: 503 y la solicitud sigue sin verificar (falla cerrado).
    var step = "ban"
    try {
        requests.setBanned(row.usuarioId, banned = true)
        step = "purge"
        requests.purgeSessions(row.usuarioId)
        step = "password"
        requests.setPassword(row.usuarioId, password)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: SupabaseHttpStatusException) {
        // Revision #3 M1: GoTrue rechaza la contrasena elegida (p. ej. 422 `weak_password`, politica del proyecto mas estricta
        // que la nuestra): es culpa de lo enviado, no una caida. Mismo 400 de CAMPO que la validacion local; no cuenta como
        // codigo erroneo. El codigo ya se consumio: el solicitante pide otro con `resend-code` y repite con otra contrasena.
        if (step == "password" && rejected.status in ADMIN_ACCESS_PASSWORD_REJECTED_STATUSES) {
            AccessRequestLogger.warn(
                "admin access request verify: chosen password rejected by auth, request left unverified requestRowId={} status={} errorCode={}",
                row.id,
                rejected.status,
                rejected.goTrueErrorCode?.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }?.take(64) ?: "-",
            )
            throw OposiBotsAuthProblem.validation(OposiBotsValidationIssue.INVALID_PASSWORD)
        }
        AccessRequestLogger.error(
            "admin access request verify: {} failed, request left unverified requestRowId={} authUserId={} status={}",
            step,
            row.id,
            row.usuarioId,
            rejected.status,
        )
        throw OposiBotsAuthProblem.unavailable()
    } catch (failure: Exception) {
        AccessRequestLogger.error(
            "admin access request verify: {} failed, request left unverified requestRowId={} authUserId={} error={}",
            step,
            row.id,
            row.usuarioId,
            failure::class.simpleName,
        )
        throw OposiBotsAuthProblem.unavailable()
    }
    val marked = requests.markEmailVerified(row.id, row.usuarioId)
    if (marked == null) {
        // Nada cambio: otra peticion con el mismo codigo la verifico a la vez (correcto) o dejo de estar pendiente.
        val current = requests.nonApprovedByUser(row.usuarioId)
        if (current?.verified != true) throw adminAccessInvalidCode()
        return
    }
    val requestId: String? = org.slf4j.MDC.get(MDC_REQUEST_ID)
    try {
        requests.audit(
            ACTION_ACCESS_REQUEST_EMAIL_VERIFIED,
            row.id,
            buildJsonObject {
                put("usuarioId", row.usuarioId)
                put("correoVerificado", true)
                requestId?.let { put("requestId", it) }
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AccessRequestLogger.error(
            "admin access request verify audit failed requestRowId={} authUserId={} error={}",
            row.id,
            row.usuarioId,
            failure::class.simpleName,
        )
    }
    AccessRequestLogger.info("admin access request email verified requestRowId={} emailRef={}", row.id, emailRef)
}

/**
 * M2: un codigo erroneo para una solicitud viva. Suma el fallo (si la escritura falla: 503, falla cerrado, el contador no
 * puede saltarse). Al llegar a [ADMIN_ACCESS_VERIFY_MAX_FAILURES] la solicitud se libera con la regla de la guarda (como
 * una caducada); si esa limpieza falla queda AGOTADA igualmente (ya no admite verificar) y la siguiente solicitud, el
 * registro o una recuperacion la limpian. La respuesta al cliente es siempre el 400 unico.
 */
private suspend fun recordFailedAttempt(requests: AdminAccessRequestGateway, row: AccessRequestReservation, now: Instant, emailRef: String) {
    val attempts = try {
        requests.recordFailedVerification(row)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AccessRequestLogger.warn(
            "admin access request verify: failed-attempt counter not written requestRowId={} error={}",
            row.id,
            failure::class.simpleName,
        )
        throw OposiBotsAuthProblem.unavailable()
    } ?: return
    if (attempts < ADMIN_ACCESS_VERIFY_MAX_FAILURES) return
    AccessRequestLogger.warn("admin access request verify: attempts exhausted requestRowId={} attempts={} emailRef={}", row.id, attempts, emailRef)
    try {
        requests.releaseUnverifiedAccount(row.copy(intentosVerificacion = attempts), AccessRequestReleaseMotive.VERIFY_ATTEMPTS_EXHAUSTED, now, emailRef)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AccessRequestLogger.warn(
            "admin access request verify: exhausted request not released (left failed-out) requestRowId={} error={}",
            row.id,
            failure::class.simpleName,
        )
    }
}

/**
 * Prefijo SHA-256 SIN clave del correo canonico. Solo lo usa el gateway cuando se construye sin el secreto del limitador
 * (`AUTH_RATE_LIMIT_HMAC_SECRET` ausente); las rutas usan siempre el seudonimo con clave ([authLogEmailRef]).
 */
internal fun adminAccessEmailRef(email: String): String =
    sha256Hex((canonicalAuthEmail(email) ?: email.trim()).toByteArray(StandardCharsets.UTF_8)).take(12)

// ---------------------------------------------------------------------------------------------------------------------
// Rutas de revision (plano admin, solo superadmin)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Van DENTRO del bloque `authenticate("gateway-auth")` de [installControlRoutes] (401 sin credencial, 403 sin rol admin
 * activo) y despues de [installAdminAuditActor]. Encima, las tres exigen SUPERADMIN ([requireSuperadminActor]): un admin
 * normal recibe 403 `SUPERADMIN_REQUIRED` antes de leer nada. El cliente decide si ensena la pestana con ESA respuesta.
 *
 * [gateway] `null` = sin configuracion: las rutas existen y fallan cerradas (500), como el resto de runtimes canonicos.
 */
internal fun Route.adminAccessRequestRoutes(gateway: AdminAccessRequestGateway?, roles: AdminV1Gateway) {
    route("/api/v1/admin/solicitudes-acceso") {
        get {
            call.requireSuperadminActor(roles)
            val requests = gateway.orMisconfigured()
            val params = call.request.queryParameters
            val estado = params["estado"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            if (estado != null && estado !in ADMIN_ACCESS_ESTADOS) throw validationError(ErrorIssue("estado", CanonicalIssue.INVALID_FORMAT))
            val limit = (params["limit"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { value -> value >= 1 } ?: throw validationError(ErrorIssue("limit", CanonicalIssue.OUT_OF_RANGE))
            } ?: ADMIN_ACCESS_LIST_DEFAULT_LIMIT).coerceAtMost(ADMIN_ACCESS_LIST_MAX_LIMIT)
            val offset = params["offset"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { value -> value >= 0 } ?: throw validationError(ErrorIssue("offset", CanonicalIssue.OUT_OF_RANGE))
            } ?: 0
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(AdminAccessRequestListResponse(requests.list(estado, limit, offset)))
        }
        post("/{id}/aprobar") {
            val actorId = call.requireSuperadminActor(roles)
            val requests = gateway.orMisconfigured()
            val id = call.accessRequestPathId()
            val current = requests.find(id) ?: throw accessRequestNotFound()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            when (current.estado) {
                // Aprobar dos veces es idempotente: misma ficha. Revision L-a (2026-10-14): si la cuenta se quedo SIN rol de
                // administracion (una aprobacion cuyo desbaneo o rol fallo y cuya reversion tampoco pudo hacerse), repetirla
                // termina el trabajo (desbaneo y rol, auditado), con las mismas exigencias que la primera vez.
                ADMIN_ACCESS_ESTADO_APROBADA -> {
                    resumeStuckApproval(requests, roles, current, actorId)
                    return@post call.respond(current)
                }
                ADMIN_ACCESS_ESTADO_RECHAZADA -> throw CanonicalErrorCode.CONFLICT.exception("La solicitud ya fue rechazada.")
            }
            // 1) Propiedad del correo, dos veces (plan del propietario 2026-10-14). Aprobar ya NO confirma ningun correo:
            //    a) la solicitud esta verificada (quien la pidio introdujo el codigo que llego a ese buzon). Una sin verificar
            //       no se aprueba, caducada o no;
            //    b) y Supabase Auth dice que la cuenta tiene el correo confirmado (la verificacion la confirma). Si no, o si la
            //       cuenta ya no existe, tampoco.
            if (!current.correoVerificado) {
                throw CanonicalErrorCode.CONFLICT.exception(
                    if (current.caducada) {
                        "La solicitud caducó sin que se verificara el correo. Quien la pidió tiene que volver a solicitar el acceso."
                    } else {
                        "La solicitud todavía no tiene el correo verificado. Quien la pidió tiene que introducir el código que recibió."
                    },
                )
            }
            val account = requests.authAccount(current.usuarioId)
            if (account?.emailConfirmed != true) {
                AccessRequestLogger.warn(
                    "admin access approve refused: verified request but auth account not confirmed requestRowId={} accountExists={}",
                    current.id,
                    account != null,
                )
                throw CanonicalErrorCode.CONFLICT.exception(
                    "La cuenta de la solicitud no tiene el correo confirmado en Supabase Auth. No se puede aprobar.",
                )
            }
            // 2) L1: PRIMERO la marca, condicionada a que siga pendiente. Asi aprobar y rechazar a la vez nunca dejan `admin`
            //    en una solicitud rechazada: si gano el rechazo, aqui no se marca nada y no se concede ningun rol.
            val resolved = requests.resolve(id, ADMIN_ACCESS_ESTADO_APROBADA, actorId, motivo = null)
                ?: run {
                    val now = requests.find(id)
                    // Otro superadministrador la aprobo a la vez: el (desbaneo y el rol) son cosa suya; misma ficha.
                    if (now?.estado == ADMIN_ACCESS_ESTADO_APROBADA) return@post call.respond(now)
                    throw CanonicalErrorCode.CONFLICT.exception("Otro superadministrador resolvio la solicitud mientras tanto.")
                }
            // 3) M1: se levanta el baneo de la verificacion ANTES de conceder el rol; 4) el rol, por el camino auditado. Si
            //    algo falla, se revierte la marca (y el desbaneo) y la aprobacion falla con 503: se puede reintentar.
            //    Revision #3 L1: justo ANTES del desbaneo se purgan las sesiones de la cuenta (las acuñadas antes del baneo
            //    revivirian al levantarlo); si la purga falla no se desbanea (falla cerrado, 503, se revierte la marca).
            var unbanned = false
            var step = "purga"
            try {
                requests.purgeSessions(current.usuarioId)
                step = "desbaneo"
                requests.setBanned(current.usuarioId, banned = false)
                unbanned = true
                step = "rol"
                withUpstreamIo { grantAdminRole(roles, current.usuarioId, actorId) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { revertFailedApproval(requests, roles, resolved, actorId, unbanned, step = step) }
                throw cancelled
            } catch (failure: Exception) {
                withContext(NonCancellable) { revertFailedApproval(requests, roles, resolved, actorId, unbanned, step = step) }
                AccessRequestLogger.error(
                    "admin access approve failed after marking: requestRowId={} step={} error={}",
                    resolved.id,
                    step,
                    failure::class.simpleName,
                )
                // Un rechazo de dominio del cambio de roles (p. ej. 409 VERSION_CONFLICT) se conserva; el resto es 503.
                if (failure is BackendDomainException && failure.status.value < 500) throw failure
                throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = failure)
            }
            withUpstreamIo { auditResolution(roles, ACTION_ACCESS_REQUEST_APPROVED, resolved, verifiedEmail = true) }
            call.respond(resolved)
        }
        post("/{id}/rechazar") {
            val actorId = call.requireSuperadminActor(roles)
            val requests = gateway.orMisconfigured()
            val id = call.accessRequestPathId()
            val motivo = call.rejectionReason()
            val current = requests.find(id) ?: throw accessRequestNotFound()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            when (current.estado) {
                // Ya rechazada pero la cuenta sigue ahi (rechazo anterior a este cambio, o su borrado fallo): repetir el
                // rechazo termina el trabajo.
                ADMIN_ACCESS_ESTADO_RECHAZADA -> {
                    removeRejectedAccount(requests, roles, current)
                    return@post call.respond(current)
                }
                // Una aprobada ya concedio el rol: retirarlo es una decision aparte, en la ficha del usuario.
                ADMIN_ACCESS_ESTADO_APROBADA -> throw CanonicalErrorCode.CONFLICT.exception(
                    "La solicitud ya fue aprobada. Para retirar el acceso, cambia los roles del usuario.",
                )
            }
            val resolved = requests.resolve(id, ADMIN_ACCESS_ESTADO_RECHAZADA, actorId, motivo)
                ?: requests.find(id)?.takeIf { it.estado == ADMIN_ACCESS_ESTADO_RECHAZADA }
                ?: throw CanonicalErrorCode.CONFLICT.exception("Otro superadministrador resolvio la solicitud mientras tanto.")
            withUpstreamIo { auditResolution(roles, ACTION_ACCESS_REQUEST_REJECTED, resolved) }
            // Rechazar es «esta persona no entra»: la cuenta que se creo al pedir acceso no puede quedarse (decision del
            // propietario, 2026-10-13). Va DESPUES de marcar y auditar: si el borrado falla, la solicitud ya consta como
            // rechazada y repetir el rechazo lo reintenta.
            removeRejectedAccount(requests, roles, resolved)
            call.respond(resolved)
        }
    }
}

private fun AdminAccessRequestGateway?.orMisconfigured(): AdminAccessRequestGateway = this ?: throw CanonicalErrorCode.INTERNAL.exception()

private fun accessRequestNotFound(): BackendDomainException = CanonicalErrorCode.NOT_FOUND.exception("La solicitud no existe.")

/** Id de ruta en forma canonica, en minusculas. Otra cosa: 400 VALIDATION, sin tocar la base. */
private fun ApplicationCall.accessRequestPathId(): String {
    val value = parameters["id"].orEmpty().trim()
    if (!value.isUuid()) throw validationError(ErrorIssue("id", CanonicalIssue.INVALID_FORMAT))
    return value.lowercase()
}

/**
 * El actor (`sub` del JWT) tiene que ser SUPERADMIN: se leen sus roles en la base en cada llamada (nunca del token ni del
 * cliente). Sin actor (token estatico: no es una persona) o sin `superadmin`: 403 `SUPERADMIN_REQUIRED`, el mismo codigo
 * que usa la sincronizacion de roles (D1-26). Una lectura que falla PROPAGA: nunca se convierte en un «si».
 */
private suspend fun ApplicationCall.requireSuperadminActor(roles: AdminV1Gateway): String {
    val actorId = actorIdFromPrincipalName(principal<UserIdPrincipal>()?.name)
        ?: throw CanonicalErrorCode.SUPERADMIN_REQUIRED.exception()
    val isSuperadmin = withUpstreamIo {
        val codeById = roles.listRoles().associate { it.id to it.code }
        CanonicalAuthorization.hasSuperAdmin(roles.roleIdsOf(actorId).mapNotNull(codeById::get))
    }
    if (!isSuperadmin) throw CanonicalErrorCode.SUPERADMIN_REQUIRED.exception()
    return actorId
}

/**
 * Concede `admin` (y solo `admin`) a [usuarioId] conservando los roles que ya tuviera. El rol concedido NO viene de la
 * peticion: se busca por codigo en el catalogo. Pasa por [AdminV1Gateway.syncRoles], que repite la comprobacion de
 * superadmin del actor, escribe la auditoria de intencion y de resultado y llama a `admin_sync_roles_audited` con el actor
 * y con los roles esperados (si otro los cambio entre tanto: 409 VERSION_CONFLICT, sin escribir).
 *
 * Si la cuenta ya tiene un rol de administracion no hay nada que conceder (y jamas se degrada a un superadmin).
 */
private fun grantAdminRole(roles: AdminV1Gateway, usuarioId: String, actorId: String) {
    val catalog = roles.listRoles()
    val codeById = catalog.associate { it.id to it.code }
    val adminRoleId = catalog.firstOrNull { it.code.trim().lowercase() == CanonicalRole.ADMIN.code }?.id
        ?: throw CanonicalErrorCode.INTERNAL.exception(cause = IllegalStateException("roles_app no tiene el rol admin"))
    val current = roles.roleIdsOf(usuarioId)
    if (CanonicalAuthorization.hasAdmin(current.mapNotNull(codeById::get))) return
    roles.syncRoles(usuarioId, current + adminRoleId, actorId = actorId, expectedRoleIds = current)
}

/**
 * L-a: una solicitud YA `aprobada` cuya cuenta no tiene ningun rol de administracion (la aprobacion marco la fila pero el
 * desbaneo o el rol fallaron y la reversion a `pendiente` tampoco pudo hacerse: «arreglar a mano»). Repetir la aprobacion
 * lo termina de forma idempotente: desbaneo y `admin` por el camino auditado ([grantAdminRole]), con la fila de auditoria
 * `solicitud_acceso_aprobada` (`reanudada: true`). Mismas exigencias que la primera vez: solo superadmin (lo comprobo la
 * ruta), `correo_verificado_en` y (revision #3 c) la cuenta con el correo confirmado en Supabase Auth (sin ellos: 409).
 * Las sesiones se purgan justo antes del desbaneo (revision #3 L1; si la purga falla, 503 sin desbanear). Con un rol de
 * administracion ya puesto no hace nada. Si el rol falla tras
 * desbanear, se vuelve a banear (mejor esfuerzo) y la respuesta es 503 (o el rechazo de dominio del cambio de roles).
 */
private suspend fun resumeStuckApproval(
    requests: AdminAccessRequestGateway,
    roles: AdminV1Gateway,
    current: AdminAccessRequestDto,
    actorId: String,
) {
    val hasAdminRole = withUpstreamIo {
        val codeById = roles.listRoles().associate { it.id to it.code }
        CanonicalAuthorization.hasAdmin(roles.roleIdsOf(current.usuarioId).mapNotNull(codeById::get))
    }
    if (hasAdminRole) return
    if (!current.correoVerificado) {
        throw CanonicalErrorCode.CONFLICT.exception(
            "La solicitud consta como aprobada pero no tiene el correo verificado. No se concede el acceso.",
        )
    }
    // Revision #3 (c): la misma doble comprobacion que la primera aprobacion: Supabase Auth tiene que decir que la cuenta
    // existe y tiene el correo confirmado. Si no, 409 y no se toca nada.
    val account = requests.authAccount(current.usuarioId)
    if (account?.emailConfirmed != true) {
        AccessRequestLogger.warn(
            "admin access approve resume refused: auth account not confirmed requestRowId={} accountExists={}",
            current.id,
            account != null,
        )
        throw CanonicalErrorCode.CONFLICT.exception(
            "La cuenta de la solicitud no tiene el correo confirmado en Supabase Auth. No se puede aprobar.",
        )
    }
    var unbanned = false
    var step = "purge"
    try {
        // Revision #3 L1: purga de sesiones justo antes del desbaneo; si falla, no se desbanea (503).
        requests.purgeSessions(current.usuarioId)
        step = "unban"
        requests.setBanned(current.usuarioId, banned = false)
        unbanned = true
        step = "role"
        withUpstreamIo { grantAdminRole(roles, current.usuarioId, actorId) }
    } catch (failure: Exception) {
        if (unbanned) {
            withContext(NonCancellable) {
                try {
                    requests.setBanned(current.usuarioId, banned = true)
                } catch (rebanFailure: Exception) {
                    AccessRequestLogger.error(
                        "admin access approve resume: re-ban failed requestRowId={} authUserId={} error={}",
                        current.id,
                        current.usuarioId,
                        rebanFailure::class.simpleName,
                    )
                }
            }
        }
        if (failure is CancellationException) throw failure
        AccessRequestLogger.error(
            "admin access approve resume failed requestRowId={} step={} error={}",
            current.id,
            step,
            failure::class.simpleName,
        )
        if (failure is BackendDomainException && failure.status.value < 500) throw failure
        throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(cause = failure)
    }
    val requestId: String? = org.slf4j.MDC.get(MDC_REQUEST_ID)
    withUpstreamIo {
        roles.registrarAuditoria(
            ACTION_ACCESS_REQUEST_APPROVED,
            ADMIN_ACCESS_TABLE,
            current.id,
            antes = buildJsonObject {
                put("estado", ADMIN_ACCESS_ESTADO_APROBADA)
                put("rolAdmin", false)
            },
            despues = buildJsonObject {
                put("estado", ADMIN_ACCESS_ESTADO_APROBADA)
                put("usuarioId", current.usuarioId)
                put("correoVerificado", true)
                put("reanudada", true)
                requestId?.let { put("requestId", it) }
            },
        )
    }
    AccessRequestLogger.warn("admin access approve resumed: stuck approval finished requestRowId={} authUserId={}", current.id, current.usuarioId)
}

/**
 * Borra la cuenta de una solicitud RECHAZADA. La cuenta nacio sin confirmar y sin rol, asi que en general no tiene nada
 * colgando; aun asi se comprueba antes que no tenga un rol de administracion (alguien pudo darselo a mano entre la solicitud
 * y el rechazo): en ese caso NO se borra y se dice.
 */
private suspend fun removeRejectedAccount(requests: AdminAccessRequestGateway, roles: AdminV1Gateway, request: AdminAccessRequestDto) {
    val hasAdminRole = withUpstreamIo {
        val codeById = roles.listRoles().associate { it.id to it.code }
        CanonicalAuthorization.hasAdmin(roles.roleIdsOf(request.usuarioId).mapNotNull(codeById::get))
    }
    if (hasAdminRole) {
        throw CanonicalErrorCode.CONFLICT.exception(
            "La solicitud queda rechazada, pero su cuenta tiene un rol de administración y no se ha borrado. Retírale el rol antes.",
        )
    }
    requests.deleteAccount(request.usuarioId)
    val requestId: String? = org.slf4j.MDC.get(MDC_REQUEST_ID)
    withUpstreamIo {
        roles.registrarAuditoria(
            ACTION_ACCESS_REQUEST_ACCOUNT_DELETED,
            ADMIN_ACCESS_TABLE,
            request.id,
            antes = buildJsonObject { put("usuarioId", request.usuarioId) },
            despues = buildJsonObject {
                put("cuentaEliminada", true)
                requestId?.let { put("requestId", it) }
            },
        )
    }
}

/**
 * Fila de auditoria de la resolucion (el actor lo pone [AdminAuditActor]). Sin nombre, correo ni motivo de la persona. Al
 * aprobar, `correoVerificado: true` deja constancia de que se aprobo una solicitud con el buzon demostrado (la aprobacion ya
 * no confirma ningun correo: no se escribe `emailConfirmado`).
 */
private fun auditResolution(roles: AdminV1Gateway, accion: String, resolved: AdminAccessRequestDto, verifiedEmail: Boolean = false) {
    val requestId: String? = org.slf4j.MDC.get(MDC_REQUEST_ID)
    roles.registrarAuditoria(
        accion,
        ADMIN_ACCESS_TABLE,
        resolved.id,
        antes = buildJsonObject { put("estado", ADMIN_ACCESS_ESTADO_PENDIENTE) },
        despues = buildJsonObject {
            put("estado", resolved.estado)
            put("usuarioId", resolved.usuarioId)
            if (verifiedEmail) put("correoVerificado", true)
            requestId?.let { put("requestId", it) }
        },
    )
}

/**
 * L1: la marca `aprobada` ya esta puesta pero el desbaneo o el rol fallaron. Todo de mejor esfuerzo (el error original es
 * el que sale): si ya se desbaneo, se vuelve a banear (sin rol, una cuenta desbaneada daria un JWT de GoTrue directo); la
 * marca vuelve a `pendiente` CONDICIONADA (sigue `aprobada` y la resolvio este actor); y se audita el intento con el
 * resultado. Si la reversion no se puede hacer queda un ERROR con los ids para arreglarlo a mano. Revision #3 L2: si el
 * re-baneo falla, la marca NO se revierte (se queda `aprobada` sin rol, `revertida:false` con el motivo): asi la siguiente
 * aprobacion la reanuda en vez de dejar una cuenta verificada, desbaneada y `pendiente`.
 */
private suspend fun revertFailedApproval(
    requests: AdminAccessRequestGateway,
    roles: AdminV1Gateway,
    resolved: AdminAccessRequestDto,
    actorId: String,
    unbanned: Boolean,
    step: String,
) {
    var rebanned: Boolean? = null
    if (unbanned) {
        rebanned = try {
            requests.setBanned(resolved.usuarioId, banned = true)
            true
        } catch (failure: Exception) {
            AccessRequestLogger.error(
                "admin access approve revert: re-ban failed requestRowId={} authUserId={} error={}",
                resolved.id,
                resolved.usuarioId,
                failure::class.simpleName,
            )
            false
        }
    }
    // Revision #3 L2: si el re-baneo FALLO, la cuenta esta desbaneada y sin rol. Volver a `pendiente` la dejaria fuera del
    // camino de reanudacion (solo una `aprobada` sin rol se reanuda) con la cuenta verificada y desbaneada. Se deja
    // `aprobada` (sin rol): repetir la aprobacion la termina (purga, desbaneo y rol) por [resumeStuckApproval].
    val keptApprovedForResume = rebanned == false
    val reverted = if (keptApprovedForResume) {
        AccessRequestLogger.error(
            "admin access approve revert: re-ban failed, request kept approved without its role so that approving again resumes it requestRowId={} authUserId={}",
            resolved.id,
            resolved.usuarioId,
        )
        false
    } else {
        try {
            requests.revertApproval(resolved.id, actorId) != null
        } catch (failure: Exception) {
            AccessRequestLogger.error(
                "admin access approve revert: request could not be set back to pending requestRowId={} error={}",
                resolved.id,
                failure::class.simpleName,
            )
            false
        }
    }
    if (!reverted && !keptApprovedForResume) {
        AccessRequestLogger.error(
            "admin access approve revert: request left approved without its role, fix by hand requestRowId={} authUserId={}",
            resolved.id,
            resolved.usuarioId,
        )
    }
    val requestId: String? = org.slf4j.MDC.get(MDC_REQUEST_ID)
    try {
        withUpstreamIo {
            roles.registrarAuditoria(
                ACTION_ACCESS_REQUEST_APPROVAL_REVERTED,
                ADMIN_ACCESS_TABLE,
                resolved.id,
                antes = buildJsonObject { put("estado", ADMIN_ACCESS_ESTADO_APROBADA) },
                despues = buildJsonObject {
                    put("estado", if (reverted) ADMIN_ACCESS_ESTADO_PENDIENTE else ADMIN_ACCESS_ESTADO_APROBADA)
                    put("usuarioId", resolved.usuarioId)
                    put("pasoFallido", step)
                    put("revertida", reverted)
                    rebanned?.let { put("rebaneada", it) }
                    if (keptApprovedForResume) put("motivoNoRevertida", "rebaneo_fallido_pendiente_de_reanudar")
                    requestId?.let { put("requestId", it) }
                },
            )
        }
    } catch (failure: Exception) {
        AccessRequestLogger.error(
            "admin access approve revert audit failed requestRowId={} error={}",
            resolved.id,
            failure::class.simpleName,
        )
    }
}

/** Cuerpo OPCIONAL de rechazar: `{"motivo": "..."}` (<= 500). Sin cuerpo, `{}` o `motivo` nulo/vacio = sin motivo. */
private suspend fun ApplicationCall.rejectionReason(): String? {
    val raw = canonicalRawBody().also { requireJsonContentIfBody(it) }
    if (raw.isEmpty()) return null
    val body = try {
        Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: throw CanonicalErrorCode.MALFORMED_BODY.exception()
    val value = when (val element = body["motivo"]) {
        null, JsonNull -> return null
        is JsonPrimitive -> element.takeIf { it.isString }?.content
        else -> null
    } ?: throw validationError(ErrorIssue("motivo", CanonicalIssue.INVALID_FORMAT))
    val motivo = value.trim()
    if (motivo.isEmpty()) return null
    if (motivo.codePointCount(0, motivo.length) > ADMIN_ACCESS_MOTIVO_RESOLUCION_MAX) throw validationError(ErrorIssue("motivo", CanonicalIssue.OUT_OF_RANGE))
    if (!isAcceptableFreeText(motivo)) throw validationError(ErrorIssue("motivo", CanonicalIssue.INVALID_FORMAT))
    return motivo
}

// ---------------------------------------------------------------------------------------------------------------------
// Gateway de Supabase
// ---------------------------------------------------------------------------------------------------------------------

private const val ACCESS_REQUEST_COLUMNS =
    "id,usuario_id,nombre,email,motivo,estado,creado_en,correo_verificado_en,resuelto_en,resuelto_por,motivo_resolucion"

@Serializable
private data class AccessRequestRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    val nombre: String? = null,
    val email: String? = null,
    val motivo: String? = null,
    val estado: String,
    @SerialName("creado_en") val creadoEn: String? = null,
    @SerialName("correo_verificado_en") val correoVerificadoEn: String? = null,
    @SerialName("resuelto_en") val resueltoEn: String? = null,
    @SerialName("resuelto_por") val resueltoPor: String? = null,
    @SerialName("motivo_resolucion") val motivoResolucion: String? = null,
) {
    fun toDto(now: Instant): AdminAccessRequestDto {
        val reservation = AccessRequestReservation(id, usuarioId, estado, correoVerificadoEn, creadoEn)
        return AdminAccessRequestDto(
            id = id,
            usuarioId = usuarioId,
            nombre = nombre.orEmpty(),
            email = email.orEmpty(),
            motivo = motivo.orEmpty(),
            estado = estado,
            creadoEn = creadoEn.orEmpty(),
            correoVerificado = reservation.verified,
            // Solo una PENDIENTE caduca: una resuelta ya no espera nada.
            caducada = estado == ADMIN_ACCESS_ESTADO_PENDIENTE && reservation.isExpired(now),
            correoVerificadoEn = correoVerificadoEn,
            resueltoEn = resueltoEn,
            resueltoPor = resueltoPor,
            motivoResolucion = motivoResolucion,
        )
    }
}

@Serializable
private data class AccessAuthUserWire(val id: String, val email: String? = null)

@Serializable
private data class AccessAuthUserStateWire(
    val id: String? = null,
    @SerialName("email_confirmed_at") val emailConfirmedAt: String? = null,
    @SerialName("banned_until") val bannedUntil: String? = null,
)

@Serializable
private data class AccessAuthUserListWire(val users: List<AccessAuthUserListItemWire> = emptyList())

@Serializable
private data class AccessAuthUserListItemWire(
    val id: String,
    val email: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class AccessVerifyUserWire(val id: String? = null)

@Serializable
private data class AccessVerifySessionWire(
    @SerialName("access_token") val accessToken: String? = null,
    val user: AccessVerifyUserWire? = null,
    // Algunas respuestas de GoTrue traen el usuario en la raiz.
    val id: String? = null,
)

/** Mismo margen de reloj que [SupabaseOposiBotsUserAdminGateway] al reconciliar un alta de Auth de resultado ambiguo. */
private const val ACCESS_LOST_CREATE_CLOCK_SKEW_MS = 5_000L

private val AccessRequestLogger = LoggerFactory.getLogger("OC-KTOR-ACCESS-REQUESTS")

/** Reintentos de la escritura optimista de `intentos_verificacion` antes de rendirse (falla cerrado). */
private const val ACCESS_REQUEST_COUNTER_ATTEMPTS = 5

private val ServerPasswordRandom = java.security.SecureRandom()

/** H1: 256 bits de SecureRandom en base64url sin relleno (43 caracteres ASCII imprimibles, < 72 bytes de bcrypt). */
internal fun randomServerPassword(): String {
    val bytes = ByteArray(32).also(ServerPasswordRandom::nextBytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Con la clave de servicio (la tabla tiene RLS sin politicas y los permisos revocados a `anon`/`authenticated`).
 *
 * El alta reutiliza el patron de [SupabaseOposiBotsUserAdminGateway.createUser]: `POST /auth/v1/admin/users` y, si la
 * segunda escritura falla, se BORRA la identidad recien creada (nunca queda una cuenta fantasma sin solicitud), todo como
 * una unidad NO cancelable; un resultado ambiguo del POST (transporte, timeout, 5xx) se reconcilia buscando por correo.
 *
 * Es tambien el [AccessRequestReservationStore] de la guarda de correo y de la puerta de sesion (AccessRequestMailGuard.kt).
 *
 * Los mensajes de error de este gateway llevan metodo, ruta y estado, NUNCA el cuerpo enviado ni el recibido. [emailRef]
 * es el seudonimo del correo para sus logs (con clave cuando hay secreto del limitador).
 */
internal class SupabaseAdminAccessRequestGateway(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = defaultAccessRequestHttpClient(),
    private val emailRef: (String) -> String = ::adminAccessEmailRef,
    private val clock: () -> Instant = Instant::now,
) : AdminAccessRequestGateway, AutoCloseable {

    override fun close() {
        client.close()
    }

    override suspend fun submit(submission: AdminAccessSubmission): AdminAccessSubmitOutcome =
        withContext(NonCancellable) { submitAtomically(submission) }

    private suspend fun submitAtomically(submission: AdminAccessSubmission): AdminAccessSubmitOutcome {
        val emailRef = emailRef(submission.email)
        val authBody = json.encodeToString(
            buildJsonObject {
                put("email", submission.email)
                // Revision 2026-10-14 (DO_NOT_SHIP): contrasena ALEATORIA del servidor, nunca la del solicitante. GoTrue es
                // accesible directamente con la clave anonima: si alguien confirmara esta cuenta por un enlace (plantilla
                // «Confirm signup» via `/otp` o `/resend`), una contrasena elegida por el creador sobreviviria. Con esta,
                // nadie puede entrar con contrasena hasta que quien demuestre el buzon elija la suya al verificar el codigo.
                put("password", randomServerPassword())
                // SIN confirmar: nadie ha comprobado todavia que el correo sea de quien lo escribe. Lo demuestra despues con
                // el codigo que le llega ([sendAccessCode] + [verifyAccessCode]); la aprobacion NUNCA confirma el correo.
                // El alta por la API de administracion no envia ningun correo por si misma.
                put("email_confirm", false)
                // `handle_new_auth_user` copia `nombre` al perfil (`perfiles_usuario.nombre_mostrar`).
                put("user_metadata", buildJsonObject { put("nombre", submission.nombre) })
            },
        )
        val startedAt = Instant.now()
        val created = try {
            request(HttpMethod.Post, "/auth/v1/admin/users", authBody)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: SupabaseHttpStatusException) {
            if (rejected.status in setOf(400, 409, 422)) {
                // Rechazo causado por los DATOS: no se creo nada. Ni duplicado ni rechazo salen al cliente.
                return if (rejected.status == 409 || rejected.goTrueErrorCode in GOTRUE_DUPLICATE_ERROR_CODES) {
                    AdminAccessSubmitOutcome.ALREADY_REGISTERED
                } else {
                    AccessRequestLogger.warn("admin access request rejected by auth emailRef={} errorCode={}", emailRef, rejected.goTrueErrorCode ?: "-")
                    AdminAccessSubmitOutcome.REJECTED_BY_AUTH
                }
            }
            if (rejected.status >= 500) reconcileLostCreate(submission.email, startedAt)
            throw rejected
        } catch (lost: IllegalStateException) {
            reconcileLostCreate(submission.email, startedAt)
            throw lost
        }
        val createdUser = runCatching { json.decodeFromString<AccessAuthUserWire>(created) }.getOrNull()
        val authUserId = createdUser?.id?.takeIf { it.isUuid() }
        if (authUserId == null) {
            // 2xx ilegible: la identidad existe pero no sabemos su id. Mismo tratamiento que una respuesta perdida.
            reconcileLostCreate(submission.email, startedAt)
            throw IllegalStateException("Supabase Auth respondio al alta sin un id de usuario valido.")
        }
        // L2: la fila guarda el correo TAL COMO LO GUARDO GoTrue (su minusculado), no como llego en el cuerpo. La guarda, la
        // verificacion y el reenvio buscan la columna con el correo canonico, asi que los dos TIENEN que coincidir: con un
        // correo ASCII siempre coinciden; si GoTrue lo escribio distinto (mayusculas no ASCII: su minusculado no es el
        // nuestro) la fila seria invisible para esas busquedas, asi que no se guarda: se borra la cuenta recien creada y el
        // resultado es un rechazo (misma 202). Una respuesta sin correo se trata como el canonico.
        val storedEmail = createdUser.email?.trim()?.takeIf(String::isNotEmpty) ?: submission.email
        if (storedEmail != submission.email) {
            AccessRequestLogger.warn("admin access request: GoTrue stored a different e-mail spelling than the canonical one, request discarded emailRef={}", emailRef)
            try {
                request(HttpMethod.Delete, "/auth/v1/admin/users/$authUserId", null)
            } catch (compensation: Exception) {
                throw IllegalStateException(
                    "La identidad de Supabase Auth $authUserId creada para una solicitud descartada no pudo eliminarse " +
                        "(${compensation::class.simpleName}). Borrar ese auth_user_id a mano.",
                    compensation,
                )
            }
            return AdminAccessSubmitOutcome.REJECTED_BY_AUTH
        }
        val row = json.encodeToString(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("usuario_id", authUserId)
                        put("nombre", submission.nombre)
                        put("email", storedEmail)
                        put("motivo", submission.motivo)
                    },
                )
            },
        )
        try {
            request(HttpMethod.Post, "/rest/v1/$ADMIN_ACCESS_TABLE", row, prefer = "return=minimal")
        } catch (failure: Exception) {
            // Compensacion: sin solicitud no puede quedar la cuenta (nadie podria aprobarla ni volver a pedirla).
            try {
                request(HttpMethod.Delete, "/auth/v1/admin/users/$authUserId", null)
            } catch (compensation: Exception) {
                throw IllegalStateException(
                    "La solicitud de acceso NO pudo guardarse y la identidad de Supabase Auth $authUserId creada para ella " +
                        "tampoco pudo eliminarse (${compensation::class.simpleName}). Borrar ese auth_user_id a mano.",
                    compensation,
                )
            }
            throw failure
        }
        return AdminAccessSubmitOutcome.CREATED
    }

    /**
     * El POST de Auth fallo con resultado AMBIGUO: la identidad pudo quedar creada sin que conozcamos su id. Se busca por
     * correo y se borra SOLO si todo cuadra (deny-by-default): exactamente una identidad con el correo exacto, creada en o
     * despues de [startedAt] (menos el margen de reloj), con id uuid, sin solicitud y sin ningun rol. Cualquier otra cosa,
     * o un fallo de la busqueda, no borra nada (WARN). El llamador relanza siempre el error original.
     */
    private suspend fun reconcileLostCreate(email: String, startedAt: Instant) {
        val emailRef = emailRef(email)
        val notBefore = startedAt.minusMillis(ACCESS_LOST_CREATE_CLOCK_SKEW_MS)
        val ghostId = try {
            val path = "/auth/v1/admin/users?filter=${URLEncoder.encode(email, StandardCharsets.UTF_8)}&per_page=50"
            // L8: misma regla canonica (solo A-Z se minuscula), nunca `equals(ignoreCase)` (minusculado Unicode).
            val target = canonicalAuthEmail(email) ?: email
            val matches = json.decodeFromString<AccessAuthUserListWire>(request(HttpMethod.Get, path, null)).users
                .filter { user -> user.email?.let(::canonicalAuthEmail) == target }
            val candidate = matches.singleOrNull()
            val createdAt = candidate?.createdAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            if (candidate == null || !candidate.id.isUuid() || createdAt == null || createdAt.isBefore(notBefore)) {
                AccessRequestLogger.warn("lost auth create: no reconcilable identity emailRef={} exactMatches={} (nothing deleted)", emailRef, matches.size)
                return
            }
            val id = candidate.id.lowercase()
            val hasRequest = request(HttpMethod.Get, "/rest/v1/$ADMIN_ACCESS_TABLE?select=id&usuario_id=eq.$id&limit=1", null)
                .decodeList<JsonObject>().isNotEmpty()
            val hasRoles = request(HttpMethod.Get, "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq.$id&limit=1", null)
                .decodeList<JsonObject>().isNotEmpty()
            if (hasRequest || hasRoles) {
                AccessRequestLogger.warn("lost auth create: identity already linked emailRef={} authUserId={} (nothing deleted)", emailRef, id)
                return
            }
            id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (lookupFailure: Exception) {
            AccessRequestLogger.warn("lost auth create: lookup failed emailRef={} error={} (nothing deleted)", emailRef, lookupFailure::class.simpleName)
            return
        }
        try {
            request(HttpMethod.Delete, "/auth/v1/admin/users/$ghostId", null)
            AccessRequestLogger.warn("lost auth create: ghost identity deleted emailRef={} authUserId={}", emailRef, ghostId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (gone: SupabaseHttpStatusException) {
            if (gone.status != 404) throw ghostDeletionFailed(ghostId, gone)
        } catch (deletionFailure: Exception) {
            throw ghostDeletionFailed(ghostId, deletionFailure)
        }
    }

    private fun ghostDeletionFailed(authUserId: String, cause: Exception) = IllegalStateException(
        "La solicitud de acceso fallo con resultado ambiguo y la identidad de Supabase Auth $authUserId creada por ella " +
            "no pudo eliminarse (${cause::class.simpleName}). Borrar ese auth_user_id a mano.",
        cause,
    )

    // ---------- correo y codigo (GoTrue) ----------

    /**
     * H1: `POST /auth/v1/otp {email, create_user:false}`. `create_user:false` = GoTrue nunca crea una cuenta por esta via
     * (si no existe, 422 `otp_disabled`, que se registra como cualquier 4xx). La plantilla que sale es «Magic Link» (solo
     * `{{ .Token }}`). SIN VERIFICAR contra GoTrue real (DEBT-118): para una cuenta SIN CONFIRMAR, GoTrue puede encaminar el
     * OTP por su flujo de alta (plantilla «Confirm signup»); la prueba manual de DEBT-118 lo tiene que descartar.
     */
    override suspend fun sendAccessCode(email: String) {
        val body = json.encodeToString(
            buildJsonObject {
                put("email", email)
                put("create_user", false)
            },
        )
        try {
            request(HttpMethod.Post, "/auth/v1/otp", body)
        } catch (rejected: SupabaseHttpStatusException) {
            if (rejected.status >= 500) throw rejected
            // 4xx: 429 (un correo por minuto y usuario), captcha, proveedor de correo... No sale ningun correo; la
            // respuesta publica no cambia. Solo el estado y el `error_code` reducido a ASCII, nunca el mensaje de GoTrue.
            AccessRequestLogger.warn(
                "admin access request code mail rejected by upstream status={} error_code={} emailRef={}",
                rejected.status,
                rejected.goTrueErrorCode?.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }?.take(64) ?: "-",
                emailRef(email),
            )
        }
    }

    /**
     * `type: "email"`: es el tipo que GoTrue documenta para verificar un OTP de correo (`verifyOtp({email, token, type:
     * 'email'})`); `magiclink` esta obsoleto para esto. Segun el codigo de GoTrue, `email` comprueba el token tanto de la
     * columna de confirmacion (flujo de alta) como de la de enlace magico, asi que vale para los dos caminos que puede
     * tomar [sendAccessCode]. SIN VERIFICAR contra GoTrue real (DEBT-118).
     */
    override suspend fun verifyAccessCode(email: String, code: String): VerifiedSignup? {
        val body = json.encodeToString(
            buildJsonObject {
                put("type", "email")
                put("email", email)
                put("token", code)
            },
        )
        val text = try {
            request(HttpMethod.Post, "/auth/v1/verify", body)
        } catch (rejected: SupabaseHttpStatusException) {
            if (rejected.status >= 500) throw rejected
            // El 429 de GoTrue es SU limite (por IP: la del backend, compartida por todos, DEBT-120), no el de este cliente:
            // 503 UPSTREAM_UNAVAILABLE con Retry-After, no un 429 que culpa al solicitante. No cuenta como codigo erroneo.
            if (rejected.status == 429) {
                throw OposiBotsAuthProblem(HttpStatusCode.ServiceUnavailable, OposiBotsErrorCode.TemporarilyUnavailable, retryAfterSeconds = 60)
            }
            return null
        }
        val session = runCatching { json.decodeFromString<AccessVerifySessionWire>(text) }.getOrNull()
        return VerifiedSignup(userId = session?.user?.id ?: session?.id, accessToken = session?.accessToken)
    }

    override suspend fun revokeSession(accessToken: String): Boolean = withContext(NonCancellable) {
        try {
            request(HttpMethod.Post, "/auth/v1/logout?scope=global", null, bearer = accessToken)
            true
        } catch (gone: SupabaseHttpStatusException) {
            // 401/404: la sesion ya no existe, que es el resultado buscado.
            gone.status == 401 || gone.status == 404
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
    }

    override suspend fun markEmailVerified(id: String, usuarioId: String): AdminAccessRequestDto? {
        val safeId = id.validatedUuid("id")
        val safeUser = usuarioId.validatedUuid("usuarioId").lowercase()
        val body = json.encodeToString(buildJsonObject { put("correo_verificado_en", clock().toString()) })
        return request(
            HttpMethod.Patch,
            "/rest/v1/$ADMIN_ACCESS_TABLE?id=eq.$safeId&usuario_id=eq.$safeUser" +
                "&estado=eq.$ADMIN_ACCESS_ESTADO_PENDIENTE&correo_verificado_en=is.null",
            body,
            prefer = "return=representation",
        ).decodeList<AccessRequestRow>().firstOrNull()?.toDto(clock())
    }

    override suspend fun setBanned(usuarioId: String, banned: Boolean) {
        val safeId = usuarioId.validatedUuid("usuarioId").lowercase()
        val body = json.encodeToString(buildJsonObject { put("ban_duration", if (banned) ADMIN_ACCESS_BAN_DURATION else ADMIN_ACCESS_UNBAN) })
        request(HttpMethod.Put, "/auth/v1/admin/users/$safeId", body)
    }

    override suspend fun setPassword(usuarioId: String, password: String) {
        val safeId = usuarioId.validatedUuid("usuarioId").lowercase()
        request(HttpMethod.Put, "/auth/v1/admin/users/$safeId", json.encodeToString(buildJsonObject { put("password", password) }))
    }

    override suspend fun recordFailedVerification(reservation: AccessRequestReservation): Int? {
        val safeId = reservation.id.validatedUuid("id")
        var seen = reservation.intentosVerificacion
        repeat(ACCESS_REQUEST_COUNTER_ATTEMPTS) {
            val next = seen + 1
            val updated = request(
                HttpMethod.Patch,
                "/rest/v1/$ADMIN_ACCESS_TABLE?id=eq.$safeId&estado=eq.$ADMIN_ACCESS_ESTADO_PENDIENTE" +
                    "&correo_verificado_en=is.null&intentos_verificacion=eq.$seen",
                json.encodeToString(buildJsonObject { put("intentos_verificacion", next) }),
                prefer = "return=representation",
            ).decodeList<JsonObject>()
            if (updated.isNotEmpty()) return next
            // Otro intento escribio antes (o la fila dejo de estar viva): se relee y se reintenta sobre el valor de ahora.
            val current = request(
                HttpMethod.Get,
                "/rest/v1/$ADMIN_ACCESS_TABLE?select=$ACCESS_REQUEST_RESERVATION_COLUMNS&id=eq.$safeId" +
                    "&estado=eq.$ADMIN_ACCESS_ESTADO_PENDIENTE&correo_verificado_en=is.null&limit=1",
                null,
            ).decodeList<AccessRequestReservationRow>().firstOrNull() ?: return null
            seen = current.intentosVerificacion
        }
        throw IllegalStateException("intentos_verificacion: demasiada contencion en $ADMIN_ACCESS_TABLE $safeId")
    }

    override suspend fun revertApproval(id: String, actorId: String): AdminAccessRequestDto? {
        val safeId = id.validatedUuid("id")
        val safeActor = actorId.validatedUuid("actorId").lowercase()
        val body = json.encodeToString(
            buildJsonObject {
                put("estado", ADMIN_ACCESS_ESTADO_PENDIENTE)
                put("resuelto_en", JsonNull)
                put("resuelto_por", JsonNull)
                put("motivo_resolucion", JsonNull)
            },
        )
        return request(
            HttpMethod.Patch,
            "/rest/v1/$ADMIN_ACCESS_TABLE?id=eq.$safeId&estado=eq.$ADMIN_ACCESS_ESTADO_APROBADA&resuelto_por=eq.$safeActor",
            body,
            prefer = "return=representation",
        ).decodeList<AccessRequestRow>().firstOrNull()?.toDto(clock())
    }

    override suspend fun neutraliseCredential(usuarioId: String, revokeSessions: Boolean) {
        val safeId = usuarioId.validatedUuid("usuarioId").lowercase()
        // Contrasena del servidor: 32 bytes de SecureRandom en base64url (43 caracteres ASCII, 256 bits). Nunca se registra,
        // nunca sale de esta funcion y no se guarda en ningun sitio.
        val password = randomServerPassword()
        val updated = request(HttpMethod.Put, "/auth/v1/admin/users/$safeId", json.encodeToString(buildJsonObject { put("password", password) }))
        if (!revokeSessions) return
        // Las sesiones y refresh tokens que el creador ya tuviera (p. ej. acuñados contra GoTrue directo) sobreviven a un
        // cambio de contrasena por la API de administracion: se inicia sesion con la contrasena NUEVA y se cierra en global.
        val email = runCatching { json.decodeFromString<AccessAuthUserListItemWire>(updated).email }.getOrNull()?.takeIf(String::isNotBlank)
            ?: checkNotNull(
                runCatching { json.decodeFromString<AccessAuthUserListItemWire>(request(HttpMethod.Get, "/auth/v1/admin/users/$safeId", null)).email }
                    .getOrNull()?.takeIf(String::isNotBlank),
            ) { "Supabase Auth no devolvio el correo de la cuenta neutralizada" }
        val session = request(
            HttpMethod.Post,
            "/auth/v1/token?grant_type=password",
            json.encodeToString(
                buildJsonObject {
                    put("email", email)
                    put("password", password)
                },
            ),
        )
        val token = runCatching { json.decodeFromString<AccessVerifySessionWire>(session).accessToken }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Supabase Auth no devolvio sesion para revocar las de la cuenta neutralizada")
        check(revokeSession(token)) { "No se pudieron revocar las sesiones de la cuenta neutralizada" }
    }

    // ---------- guarda de correo y puerta de sesion ----------

    override suspend fun nonApprovedByEmail(email: String): List<AccessRequestReservation> =
        request(HttpMethod.Get, accessRequestHoldPath(email), null).decodeList<AccessRequestReservationRow>().map { it.toReservation() }

    override suspend fun nonApprovedByUser(userId: String): AccessRequestReservation? {
        require(userId.isUuid()) { "userId" }
        return request(HttpMethod.Get, accessRequestSessionPath(userId), null)
            .decodeList<AccessRequestReservationRow>().firstOrNull()?.toReservation()
    }

    override suspend fun authAccount(usuarioId: String): AuthAccountState? {
        val safeId = usuarioId.validatedUuid("usuarioId").lowercase()
        val text = try {
            request(HttpMethod.Get, "/auth/v1/admin/users/$safeId", null)
        } catch (missing: SupabaseHttpStatusException) {
            if (missing.status == 404) return null
            throw missing
        }
        val user = json.decodeFromString<AccessAuthUserStateWire>(text)
        // Una respuesta de otro usuario no es esta cuenta: falla cerrado.
        check(user.id?.lowercase() == safeId) { "Supabase Auth devolvio otro usuario" }
        // Revision #3 M1: baneada = `banned_until` en el futuro. Presente pero ilegible cuenta como baneada (deny-by-default:
        // la regla de liberacion borra en vez de intentar una neutralizacion que GoTrue rechazaria).
        val banned = user.bannedUntil?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()?.isAfter(clock()) ?: true
        } ?: false
        return AuthAccountState(emailConfirmed = !user.emailConfirmedAt.isNullOrBlank(), banned = banned)
    }

    override suspend fun purgeSessions(usuarioId: String): Int {
        val safeId = usuarioId.validatedUuid("usuarioId").lowercase()
        val text = request(
            HttpMethod.Post,
            "/rest/v1/rpc/$ADMIN_PURGE_USER_SESSIONS_RPC",
            json.encodeToString(buildJsonObject { put("p_user_id", safeId) }),
        )
        // La RPC devuelve un entero (sesiones borradas). Una respuesta ilegible no es un exito: falla cerrado.
        return checkNotNull(text.trim().toIntOrNull()) { "$ADMIN_PURGE_USER_SESSIONS_RPC devolvio una respuesta ilegible" }
    }

    override suspend fun hasAnyRole(usuarioId: String): Boolean {
        val safeId = usuarioId.validatedUuid("usuarioId").lowercase()
        return request(HttpMethod.Get, "/rest/v1/roles_usuario?select=rol_id&usuario_id=eq.$safeId&limit=1", null)
            .decodeList<JsonObject>().isNotEmpty()
    }

    override suspend fun deleteAccount(usuarioId: String) {
        val safeId = usuarioId.validatedUuid("usuarioId")
        try {
            request(HttpMethod.Delete, "/auth/v1/admin/users/$safeId", null)
        } catch (missing: SupabaseHttpStatusException) {
            // Ya no existe: es justo el resultado pedido.
            if (missing.status != 404) throw missing
        }
    }

    override suspend fun deleteUnverifiedRequest(id: String): Boolean {
        val safeId = id.validatedUuid("id")
        return request(
            HttpMethod.Delete,
            "/rest/v1/$ADMIN_ACCESS_TABLE?id=eq.$safeId&correo_verificado_en=is.null&estado=neq.$ADMIN_ACCESS_ESTADO_APROBADA",
            null,
            prefer = "return=representation",
        ).decodeList<JsonObject>().isNotEmpty()
    }

    override suspend fun audit(accion: String, registroId: String, despues: JsonObject) {
        val row = json.encodeToString(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("accion", accion)
                        put("tabla_afectada", ADMIN_ACCESS_TABLE)
                        put("registro_id", registroId.validatedUuid("registroId"))
                        put("despues", despues)
                    },
                )
            },
        )
        request(HttpMethod.Post, "/rest/v1/auditoria_log", row, prefer = "return=minimal")
    }

    // ---------- lecturas del panel ----------

    override suspend fun list(estado: String?, limit: Int, offset: Int): List<AdminAccessRequestDto> {
        val filter = estado?.takeIf { it in ADMIN_ACCESS_ESTADOS }?.let { "&estado=eq.$it" }.orEmpty()
        // `resuelto_en` es nulo solo en las pendientes (CHECK de la tabla): `nullsfirst` las pone arriba.
        val path = "/rest/v1/$ADMIN_ACCESS_TABLE?select=$ACCESS_REQUEST_COLUMNS$filter" +
            "&order=resuelto_en.desc.nullsfirst,creado_en.desc,id.asc" +
            "&limit=${limit.coerceIn(1, ADMIN_ACCESS_LIST_MAX_LIMIT)}&offset=${offset.coerceAtLeast(0)}"
        val now = clock()
        return request(HttpMethod.Get, path, null).decodeList<AccessRequestRow>().map { it.toDto(now) }
    }

    override suspend fun find(id: String): AdminAccessRequestDto? {
        val safeId = id.validatedUuid("id")
        return request(HttpMethod.Get, "/rest/v1/$ADMIN_ACCESS_TABLE?select=$ACCESS_REQUEST_COLUMNS&id=eq.$safeId&limit=1", null)
            .decodeList<AccessRequestRow>().firstOrNull()?.toDto(clock())
    }

    override suspend fun resolve(id: String, estado: String, actorId: String, motivo: String?): AdminAccessRequestDto? {
        require(estado == ADMIN_ACCESS_ESTADO_APROBADA || estado == ADMIN_ACCESS_ESTADO_RECHAZADA) { "estado de resolucion invalido" }
        val safeId = id.validatedUuid("id")
        val body = json.encodeToString(
            buildJsonObject {
                put("estado", estado)
                put("resuelto_en", Instant.now().toString())
                put("resuelto_por", actorId.validatedUuid("actorId"))
                put("motivo_resolucion", motivo?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
        // Condicionada a `pendiente`: 0 filas = otro la resolvio antes (o no existe); decide el llamador.
        return request(
            HttpMethod.Patch,
            "/rest/v1/$ADMIN_ACCESS_TABLE?id=eq.$safeId&estado=eq.$ADMIN_ACCESS_ESTADO_PENDIENTE",
            body,
            prefer = "return=representation",
        ).decodeList<AccessRequestRow>().firstOrNull()?.toDto(clock())
    }

    /**
     * [bearer] = token de USUARIO para el `Authorization` (solo el logout de la sesion de verificacion); sin el, las
     * cabeceras elevadas de la clave de servicio.
     */
    private suspend fun request(method: HttpMethod, path: String, body: String?, prefer: String? = null, bearer: String? = null): String {
        val target = "${method.value} ${path.substringBefore('?')}"
        val response = try {
            client.request("$supabaseUrl$path") {
                this.method = method
                val policy = SupabaseRequestAuthPolicy(serviceRoleKey)
                val headers = if (bearer != null) policy.userScopedHeaders(bearer) else policy.elevatedHeaders()
                headers.forEach { (name, value) -> header(name, value) }
                header(HttpHeaders.Accept, ContentType.Application.Json)
                prefer?.let { header("Prefer", it) }
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Sin el mensaje de la causa: algunos motores incluyen la peticion. La clase basta para diagnosticar.
            throw IllegalStateException("Supabase network error on $target: ${error::class.simpleName}")
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val errorCode = runCatching {
                val element = json.parseToJsonElement(text).jsonObject
                (element["error_code"] ?: element["code"])?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw SupabaseHttpStatusException(
                response.status.value,
                "Supabase $target -> ${response.status.value} (${errorCode ?: "sin codigo"})",
                goTrueErrorCode = errorCode.takeIf { path.startsWith("/auth/") },
            )
        }
        return text
    }

    private inline fun <reified T> String.decodeList(): List<T> =
        if (isBlank()) emptyList() else json.decodeFromString(this)

    companion object {
        /**
         * La clave la resuelve [SupabaseRequestAuthPolicy.secretFromEnvironment] (`SUPABASE_SECRET_KEY`, con la legacy de
         * respaldo). El seudonimo de log usa `AUTH_RATE_LIMIT_HMAC_SECRET` (el mismo que [authLogEmailRef]) si existe.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): AdminAccessRequestGateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            if (url.isBlank() || serviceRoleKey.isBlank()) return null
            val logSecret = environment("AUTH_RATE_LIMIT_HMAC_SECRET")?.takeIf { it.length >= 32 }
            // L6: fuera de desarrollo el seudonimo de los logs SIEMPRE lleva clave. Sin el secreto el arranque falla con un
            // mensaje claro; el prefijo SHA-256 sin clave queda solo para desarrollo.
            check(logSecret != null || AppEnvironment.isDevelopment(environment("APP_ENV"))) {
                "AUTH_RATE_LIMIT_HMAC_SECRET (al menos 32 caracteres) es obligatorio fuera de desarrollo: " +
                    "es la clave del seudonimo de correo de los logs de las solicitudes de acceso."
            }
            val emailRef: (String) -> String = logSecret?.let { secret -> { email: String -> authLogEmailRef(secret, email) } } ?: ::adminAccessEmailRef
            return SupabaseAdminAccessRequestGateway(url, serviceRoleKey, json, emailRef = emailRef)
        }
    }
}

private fun defaultAccessRequestHttpClient(): HttpClient = HttpClient(Java) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}
