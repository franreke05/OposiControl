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
 *  1. `POST /v1/auth/admin-access-requests` (publica, plano de auth, pool `bot`): crea la cuenta de Auth con el correo y
 *     la contrasena elegidos, SIN ningun rol y con el correo SIN CONFIRMAR, y guarda la solicitud como `pendiente`. Una
 *     cuenta sin confirmar no obtiene sesion: no puede entrar en el panel NI en la app hasta que se apruebe (nadie ha
 *     comprobado que el correo sea suyo, y asi tampoco sirve para ocupar el correo de otra persona con una sesion valida).
 *  2. `GET /api/v1/admin/solicitudes-acceso` y `POST .../{id}/aprobar|rechazar` (plano admin, pool `control`): SOLO un
 *     superadministrador. Aprobar, en este orden: confirma el correo de la cuenta, concede `admin` (nunca `superadmin`)
 *     por el camino auditado que ya existe ([AdminV1Gateway.syncRoles] -> RPC `admin_sync_roles_audited`, con el actor
 *     del JWT) y marca la solicitud. Rechazar deja la cuenta sin confirmar y sin rol.
 *
 * La contrasena viaja del formulario a este backend (TLS) y de aqui a Supabase Auth: no se registra en logs, no se guarda
 * en ninguna tabla nuestra, no vuelve en ninguna respuesta ni en ningun mensaje de error y nunca va en una URL.
 *
 * La respuesta publica es SIEMPRE la misma (202) exista ya el correo o no: no sirve para enumerar cuentas.
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
internal const val ACTION_ACCESS_REQUEST_EMAIL_CONFIRMED = "solicitud_acceso_email_confirmado"

/** Cuerpo de la solicitud publica. `toString` NO imprime nada: lleva una contrasena. */
@Serializable
internal class AdminAccessRequestBody(
    val nombre: String = "",
    val email: String = "",
    val password: String = "",
    val motivo: String = "",
) {
    override fun toString(): String = "AdminAccessRequestBody(<redactado>)"
}

/** La solicitud ya validada y normalizada. `toString` NO imprime nada: lleva una contrasena. */
internal class AdminAccessSubmission(
    val nombre: String,
    val email: String,
    val password: String,
    val motivo: String,
) {
    override fun toString(): String = "AdminAccessSubmission(<redactado>)"
}

/** Lo que paso al registrar la solicitud. NUNCA sale al cliente: la respuesta publica es la misma en los tres casos. */
internal enum class AdminAccessSubmitOutcome {
    /** Cuenta creada sin rol y solicitud `pendiente` guardada. */
    CREATED,

    /** El correo ya tenia cuenta: no se crea nada ni se toca la cuenta existente. */
    ALREADY_REGISTERED,

    /** Supabase Auth rechazo los datos (4xx que no es duplicado): no se crea nada. */
    REJECTED_BY_AUTH,
}

/** Respuesta publica UNICA (202). */
@Serializable
internal data class AdminAccessRequestAccepted(val estado: String)

@Serializable
data class AdminAccessRequestDto(
    val id: String,
    val usuarioId: String,
    val nombre: String,
    val email: String,
    val motivo: String,
    val estado: String,
    val creadoEn: String,
    val resueltoEn: String? = null,
    val resueltoPor: String? = null,
    val motivoResolucion: String? = null,
)

@Serializable
data class AdminAccessRequestListResponse(val items: List<AdminAccessRequestDto>)

internal interface AdminAccessRequestGateway {
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
     * Confirma el correo de la cuenta de Auth [usuarioId] (`PUT /auth/v1/admin/users/{id}` con `email_confirm: true`). Solo
     * lo llama la APROBACION. Idempotente: confirmar una cuenta ya confirmada la deja igual.
     */
    suspend fun confirmEmail(usuarioId: String)
}

// ---------------------------------------------------------------------------------------------------------------------
// Validacion de la solicitud publica
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Normaliza y valida. Email y contrasena con la MISMA regla que el registro ([validateAuthEmailAndPassword]: email con
 * forma de email y 3..320, contrasena 12..128). Nombre 1..120 puntos de codigo sin caracteres invisibles
 * ([isAcceptableDisplayName]); motivo 1..1000 sin caracteres de control salvo saltos de linea y tabulador.
 */
internal fun AdminAccessRequestBody.validated(): AdminAccessSubmission {
    val cleanEmail = email.trim()
    validateAuthEmailAndPassword(cleanEmail, password)
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
    return AdminAccessSubmission(nombre = cleanNombre, email = cleanEmail.lowercase(), password = password, motivo = cleanMotivo)
}

/** Texto libre de varias lineas: se admiten `\n`, `\r` y `\t`; ningun otro caracter de control ni de formato invisible. */
internal fun isAcceptableFreeText(text: String): Boolean =
    isAcceptableDisplayName(text.filterNot { it == '\n' || it == '\r' || it == '\t' })

// ---------------------------------------------------------------------------------------------------------------------
// Ruta publica (plano de auth)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * `POST /v1/auth/admin-access-requests`. Vive bajo `/v1/auth/` porque es el prefijo publico que el borde ya enruta al pool
 * `bot` (`ops/caddy/Caddyfile.production`, matcher `@bot`, prefijo `/v1/auth/`); el borde es deny-by-default.
 *
 * Mismo sobre que el resto del plano ([authEndpoint]): limite por IP ANTES de leer el cuerpo (429 + `Retry-After`),
 * cuerpo JSON acotado a 16 KB, `Cache-Control: no-store` y errores con el sobre canonico. El tope por email es SILENCIOSO
 * (misma 202, sin llamar a Supabase), igual que el del registro: un tercero que nombra el correo de otro no provoca un 429.
 *
 * [gateway] `null` = sin configuracion de Supabase: la ruta existe y falla cerrada (503).
 */
internal fun Route.adminAccessRequestPublicRoute(runtime: OposiBotsAuthRuntime, gateway: AdminAccessRequestGateway?) {
    post("/v1/auth/admin-access-requests") {
        call.authEndpoint(runtime, OposiBotsAuthOperation.ADMIN_ACCESS_REQUEST) {
            val requests = gateway ?: throw OposiBotsAuthProblem.unavailable()
            val submission = receiveAdminAccessRequestBody(runtime.json).validated()
            val emailRef = adminAccessEmailRef(submission.email)
            if (runtime.rateLimiter.checkSubject(OposiBotsAuthOperation.ADMIN_ACCESS_REQUEST, submission.email).allowed) {
                val outcome = requests.submit(submission)
                // Solo al log, con el correo reducido a un prefijo de su hash: el cliente recibe lo mismo en todos los casos.
                AccessRequestLogger.info("admin access request outcome={} emailRef={}", outcome.name, emailRef)
            } else {
                AccessRequestLogger.info("admin access request subject limit reached: silent success emailRef={}", emailRef)
            }
            respond(HttpStatusCode.Accepted, AdminAccessRequestAccepted(estado = "recibida"))
        }
    }
}

/** Prefijo SHA-256 del correo en minusculas: correlaciona lineas de log sin escribir el correo. */
internal fun adminAccessEmailRef(email: String): String =
    sha256Hex(email.trim().lowercase().toByteArray(StandardCharsets.UTF_8)).take(12)

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
                // Aprobar dos veces es idempotente: misma ficha, sin volver a tocar los roles (si despues alguien le
                // retiro `admin` a proposito, repetir la aprobacion no se lo devuelve).
                ADMIN_ACCESS_ESTADO_APROBADA -> return@post call.respond(current)
                ADMIN_ACCESS_ESTADO_RECHAZADA -> throw CanonicalErrorCode.CONFLICT.exception("La solicitud ya fue rechazada.")
            }
            // Tres pasos, cada uno repetible. Si uno falla, la solicitud sigue `pendiente` y volver a aprobar la completa:
            // 1) Confirmar el correo de la cuenta (hasta aqui no podia obtener sesion en ningun sitio). Confirmar una
            //    cuenta ya confirmada no cambia nada.
            requests.confirmEmail(current.usuarioId)
            withUpstreamIo { auditEmailConfirmed(roles, current) }
            // 2) El rol, por el camino auditado. Si ya lo tiene (reintento), no se vuelve a sincronizar.
            withUpstreamIo { grantAdminRole(roles, current.usuarioId, actorId) }
            // 3) La solicitud, condicionada a que siga pendiente.
            val resolved = requests.resolve(id, ADMIN_ACCESS_ESTADO_APROBADA, actorId, motivo = null)
                ?: requests.find(id)?.takeIf { it.estado == ADMIN_ACCESS_ESTADO_APROBADA }
                ?: throw CanonicalErrorCode.CONFLICT.exception("Otro superadministrador resolvio la solicitud mientras tanto.")
            withUpstreamIo { auditResolution(roles, ACTION_ACCESS_REQUEST_APPROVED, resolved, emailConfirmed = true) }
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
                ADMIN_ACCESS_ESTADO_RECHAZADA -> return@post call.respond(current)
                // Una aprobada ya concedio el rol: retirarlo es una decision aparte, en la ficha del usuario.
                ADMIN_ACCESS_ESTADO_APROBADA -> throw CanonicalErrorCode.CONFLICT.exception(
                    "La solicitud ya fue aprobada. Para retirar el acceso, cambia los roles del usuario.",
                )
            }
            val resolved = requests.resolve(id, ADMIN_ACCESS_ESTADO_RECHAZADA, actorId, motivo)
                ?: requests.find(id)?.takeIf { it.estado == ADMIN_ACCESS_ESTADO_RECHAZADA }
                ?: throw CanonicalErrorCode.CONFLICT.exception("Otro superadministrador resolvio la solicitud mientras tanto.")
            withUpstreamIo { auditResolution(roles, ACTION_ACCESS_REQUEST_REJECTED, resolved) }
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

/** Fila de auditoria de la resolucion (el actor lo pone [AdminAuditActor]). Sin nombre, correo ni motivo de la persona. */
private fun auditResolution(roles: AdminV1Gateway, accion: String, resolved: AdminAccessRequestDto, emailConfirmed: Boolean = false) {
    val requestId: String? = org.slf4j.MDC.get(MDC_REQUEST_ID)
    roles.registrarAuditoria(
        accion,
        ADMIN_ACCESS_TABLE,
        resolved.id,
        antes = buildJsonObject { put("estado", ADMIN_ACCESS_ESTADO_PENDIENTE) },
        despues = buildJsonObject {
            put("estado", resolved.estado)
            put("usuarioId", resolved.usuarioId)
            // Solo al aprobar: el correo de la cuenta se confirmo en esta aprobacion (o ya lo estaba de un intento previo).
            if (emailConfirmed) put("emailConfirmado", true)
            requestId?.let { put("requestId", it) }
        },
    )
}

/**
 * Fila propia de la CONFIRMACION del correo, escrita en cuanto ocurre: si un paso posterior de la aprobacion falla, queda
 * constancia de quien dejo la cuenta en condiciones de iniciar sesion aunque la solicitud siga `pendiente`. Sin PII.
 */
private fun auditEmailConfirmed(roles: AdminV1Gateway, request: AdminAccessRequestDto) {
    val requestId: String? = org.slf4j.MDC.get(MDC_REQUEST_ID)
    roles.registrarAuditoria(
        ACTION_ACCESS_REQUEST_EMAIL_CONFIRMED,
        ADMIN_ACCESS_TABLE,
        request.id,
        despues = buildJsonObject {
            put("usuarioId", request.usuarioId)
            put("emailConfirmado", true)
            requestId?.let { put("requestId", it) }
        },
    )
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
    "id,usuario_id,nombre,email,motivo,estado,creado_en,resuelto_en,resuelto_por,motivo_resolucion"

@Serializable
private data class AccessRequestRow(
    val id: String,
    @SerialName("usuario_id") val usuarioId: String,
    val nombre: String? = null,
    val email: String? = null,
    val motivo: String? = null,
    val estado: String,
    @SerialName("creado_en") val creadoEn: String? = null,
    @SerialName("resuelto_en") val resueltoEn: String? = null,
    @SerialName("resuelto_por") val resueltoPor: String? = null,
    @SerialName("motivo_resolucion") val motivoResolucion: String? = null,
) {
    fun toDto() = AdminAccessRequestDto(
        id = id,
        usuarioId = usuarioId,
        nombre = nombre.orEmpty(),
        email = email.orEmpty(),
        motivo = motivo.orEmpty(),
        estado = estado,
        creadoEn = creadoEn.orEmpty(),
        resueltoEn = resueltoEn,
        resueltoPor = resueltoPor,
        motivoResolucion = motivoResolucion,
    )
}

@Serializable
private data class AccessAuthUserWire(val id: String)

@Serializable
private data class AccessAuthUserListWire(val users: List<AccessAuthUserListItemWire> = emptyList())

@Serializable
private data class AccessAuthUserListItemWire(
    val id: String,
    val email: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Mismo margen de reloj que [SupabaseOposiBotsUserAdminGateway] al reconciliar un alta de Auth de resultado ambiguo. */
private const val ACCESS_LOST_CREATE_CLOCK_SKEW_MS = 5_000L

private val AccessRequestLogger = LoggerFactory.getLogger("OC-KTOR-ACCESS-REQUESTS")

/**
 * Con la clave de servicio (la tabla tiene RLS sin politicas y los permisos revocados a `anon`/`authenticated`).
 *
 * El alta reutiliza el patron de [SupabaseOposiBotsUserAdminGateway.createUser]: `POST /auth/v1/admin/users` y, si la
 * segunda escritura falla, se BORRA la identidad recien creada (nunca queda una cuenta fantasma sin solicitud), todo como
 * una unidad NO cancelable; un resultado ambiguo del POST (transporte, timeout, 5xx) se reconcilia buscando por correo.
 *
 * Los mensajes de error de este gateway llevan metodo, ruta y estado, NUNCA el cuerpo enviado ni el recibido.
 */
internal class SupabaseAdminAccessRequestGateway(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val json: Json,
    private val client: HttpClient = defaultAccessRequestHttpClient(),
) : AdminAccessRequestGateway {

    override suspend fun submit(submission: AdminAccessSubmission): AdminAccessSubmitOutcome =
        withContext(NonCancellable) { submitAtomically(submission) }

    private suspend fun submitAtomically(submission: AdminAccessSubmission): AdminAccessSubmitOutcome {
        val emailRef = adminAccessEmailRef(submission.email)
        val authBody = json.encodeToString(
            buildJsonObject {
                put("email", submission.email)
                put("password", submission.password)
                // SIN confirmar: nadie ha comprobado que el correo sea de quien lo escribe, asi que la cuenta no puede
                // obtener sesion en ningun sitio (ni en el panel ni en la app) hasta que un superadministrador apruebe la
                // solicitud, que es quien la confirma. El alta por la API de administracion no envia ningun correo (ni
                // de confirmacion ni de invitacion): eso solo lo hacen `/signup`, `/invite` y `/resend`, que aqui no se llaman.
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
        val authUserId = runCatching { json.decodeFromString<AccessAuthUserWire>(created).id }.getOrNull()?.takeIf { it.isUuid() }
        if (authUserId == null) {
            // 2xx ilegible: la identidad existe pero no sabemos su id. Mismo tratamiento que una respuesta perdida.
            reconcileLostCreate(submission.email, startedAt)
            throw IllegalStateException("Supabase Auth respondio al alta sin un id de usuario valido.")
        }
        val row = json.encodeToString(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("usuario_id", authUserId)
                        put("nombre", submission.nombre)
                        put("email", submission.email)
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
        val emailRef = adminAccessEmailRef(email)
        val notBefore = startedAt.minusMillis(ACCESS_LOST_CREATE_CLOCK_SKEW_MS)
        val ghostId = try {
            val path = "/auth/v1/admin/users?filter=${URLEncoder.encode(email, StandardCharsets.UTF_8)}&per_page=50"
            val matches = json.decodeFromString<AccessAuthUserListWire>(request(HttpMethod.Get, path, null)).users
                .filter { it.email?.trim()?.equals(email, ignoreCase = true) == true }
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

    override suspend fun confirmEmail(usuarioId: String) {
        val safeId = usuarioId.validatedUuid("usuarioId").lowercase()
        request(HttpMethod.Put, "/auth/v1/admin/users/$safeId", json.encodeToString(buildJsonObject { put("email_confirm", true) }))
    }

    override suspend fun list(estado: String?, limit: Int, offset: Int): List<AdminAccessRequestDto> {
        val filter = estado?.takeIf { it in ADMIN_ACCESS_ESTADOS }?.let { "&estado=eq.$it" }.orEmpty()
        // `resuelto_en` es nulo solo en las pendientes (CHECK de la tabla): `nullsfirst` las pone arriba.
        val path = "/rest/v1/$ADMIN_ACCESS_TABLE?select=$ACCESS_REQUEST_COLUMNS$filter" +
            "&order=resuelto_en.desc.nullsfirst,creado_en.desc,id.asc" +
            "&limit=${limit.coerceIn(1, ADMIN_ACCESS_LIST_MAX_LIMIT)}&offset=${offset.coerceAtLeast(0)}"
        return request(HttpMethod.Get, path, null).decodeList<AccessRequestRow>().map { it.toDto() }
    }

    override suspend fun find(id: String): AdminAccessRequestDto? {
        val safeId = id.validatedUuid("id")
        return request(HttpMethod.Get, "/rest/v1/$ADMIN_ACCESS_TABLE?select=$ACCESS_REQUEST_COLUMNS&id=eq.$safeId&limit=1", null)
            .decodeList<AccessRequestRow>().firstOrNull()?.toDto()
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
        ).decodeList<AccessRequestRow>().firstOrNull()?.toDto()
    }

    private suspend fun request(method: HttpMethod, path: String, body: String?, prefer: String? = null): String {
        val target = "${method.value} ${path.substringBefore('?')}"
        val response = try {
            client.request("$supabaseUrl$path") {
                this.method = method
                SupabaseRequestAuthPolicy(serviceRoleKey).elevatedHeaders().forEach { (name, value) -> header(name, value) }
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
        /** La clave la resuelve [SupabaseRequestAuthPolicy.secretFromEnvironment] (`SUPABASE_SECRET_KEY`, con la legacy de respaldo). */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): AdminAccessRequestGateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseAdminAccessRequestGateway(url, serviceRoleKey, json)
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
