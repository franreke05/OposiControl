package com.oposicontrol.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.UnauthorizedResponse
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.response.ApplicationSendPipeline
import kotlinx.coroutines.launch
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.sentry.Sentry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.InetAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val STORED_QUESTION_PREFIX = "__OPOSICONTROL_QUESTION_V1__\n"
private const val COVER_MAX_BYTES = 10 * 1024 * 1024
private const val RESOURCE_MAX_BYTES = 50 * 1024 * 1024
/** Detalle de fallos upstream: se registra aquí y nunca se devuelve al cliente. */
private val UpstreamFailureLogger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("OC-KTOR-UPSTREAM")

private fun initializeSentry() {
    val dsn = System.getenv("SENTRY_DSN").orEmpty().trim()
    if (dsn.isBlank()) return

    // MASTER2-OBS-005 / MASTER3-OBS-003: SENTRY_ENVIRONMENT explicito, si no [AppEnvironment] (APP_ENV, falla cerrado), que es
    // tambien lo que usa `ObservabilityConfig.environment` para los logs: un solo resolvedor.
    val environment = sentryEnvironment { System.getenv(it) }

    val tracesSampleRate = System.getenv("SENTRY_TRACES_SAMPLE_RATE")
        ?.toDoubleOrNull()
        ?.coerceIn(0.0, 1.0)
        ?: 0.0

    Sentry.init { options ->
        options.dsn = dsn
        options.environment = environment
        options.release = System.getenv("SENTRY_RELEASE")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        options.tracesSampleRate = tracesSampleRate
        // Distingue bot / control / combined en cada evento (el valor ya lo valida ServerProfile.parse al arrancar).
        options.setTag("profile", System.getenv("SERVER_PROFILE").orEmpty().trim().lowercase().ifBlank { "combined" })
        options.isSendDefaultPii = false
        options.isAttachStacktrace = true
        // MASTER-OBS-004: los mensajes de excepcion incluyen fragmentos de cuerpos de error de Supabase (emails,
        // detalles "Key (col)=(valor)", JWT); se depuran antes de salir del proceso.
        options.setBeforeSend { event, _ -> scrubSentryEvent(event) }
        // Los logs de nivel INFO+ se convierten en breadcrumbs (logback `minimumBreadcrumbLevel`): tambien se depuran.
        options.setBeforeBreadcrumb { breadcrumb, _ -> scrubBreadcrumb(breadcrumb) }
    }
}

/** MASTER2-OBS-005: `SENTRY_ENVIRONMENT` explicito, si no `AppEnvironment` (APP_ENV, con su fallback unico). */
internal fun sentryEnvironment(env: (String) -> String?): String =
    env("SENTRY_ENVIRONMENT")?.trim()?.takeIf { it.isNotEmpty() }
        ?: AppEnvironment.resolve(env("APP_ENV"))

/**
 * MASTER-OBS-004 / MASTER2-OBS-004: depura el evento completo (excepciones, mensaje, extras, request y breadcrumbs), no
 * solo excepciones y mensaje.
 */
internal fun scrubSentryEvent(event: io.sentry.SentryEvent): io.sentry.SentryEvent {
    event.exceptions?.forEach { exception -> exception.value = exception.value?.redactSensitiveText() }
    event.message?.let { message ->
        message.message = message.message?.redactSensitiveText()
        message.formatted = message.formatted?.redactSensitiveText()
    }
    event.extras?.toMap()?.forEach { (key, value) ->
        if (value is String) event.setExtra(key, value.redactSensitiveText())
    }
    event.request?.let { request ->
        request.url = request.url?.redactSensitiveText()
        request.queryString = request.queryString?.redactSensitiveText()
        request.data = null
        request.cookies = null
        request.headers = null
    }
    event.breadcrumbs?.forEach { scrubBreadcrumb(it) }
    return event
}

internal fun scrubBreadcrumb(breadcrumb: io.sentry.Breadcrumb): io.sentry.Breadcrumb {
    breadcrumb.message = breadcrumb.message?.redactSensitiveText()
    breadcrumb.data.toMap().forEach { (key, value) ->
        if (value is String) breadcrumb.setData(key, value.redactSensitiveText())
    }
    return breadcrumb
}

fun main() {
    initializeSentry()

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST").orEmpty().ifBlank { "0.0.0.0" }

    // Cierre ordenado ante SIGTERM: `start(wait = true)` registra el shutdown hook y lo ejecuta con estos plazos. Netty deja de
    // aceptar conexiones y espera hasta `shutdownGracePeriod` a que las peticiones en vuelo terminen (hasta
    // `shutdownTimeout` como tope duro); luego `ApplicationStopped` cierra los recursos (Valkey, HttpClient).
    val graceMillis = System.getenv("SHUTDOWN_GRACE_PERIOD_MS")?.toLongOrNull()?.takeIf { it >= 0 } ?: 5_000L
    val timeoutMillis = System.getenv("SHUTDOWN_TIMEOUT_MS")?.toLongOrNull()?.takeIf { it >= graceMillis } ?: 25_000L
    val server = embeddedServer(
        Netty,
        host = host,
        port = port,
        module = Application::module,
    )
    // `shutdownTimeout` acota el cierre de la aplicacion (ApplicationStopped); el drenaje de Netty lo acota `stop(grace, timeout)`.
    server.engineConfig.shutdownGracePeriod = graceMillis
    server.engineConfig.shutdownTimeout = timeoutMillis
    // Hook propio y `start(wait = false)`: el hook de `start(wait = true)` llama a `stop()` con el timeout = grace (valores por
    // defecto de Ktor); aqui el tope duro es `timeoutMillis`. SIGTERM -> deja de aceptar, drena en vuelo, cierra recursos.
    Runtime.getRuntime().addShutdownHook(Thread({ server.stop(graceMillis, timeoutMillis) }, "ktor-graceful-shutdown"))
    server.start(wait = false)
    Thread.currentThread().join()
}

/**
 * Mensaje de arranque cuando un gateway no encuentra su configuración (F-01).
 *
 * Los once mensajes decían "SUPABASE_URL y SUPABASE_SERVICE_ROLE_KEY son
 * obligatorios", y con la configuración canónica —sólo `SUPABASE_SECRET_KEY`—
 * mandaban a quien despliega a definir la variable deprecada. Nombra la canónica
 * y la legacy, y nunca un valor.
 */
internal fun missingSupabaseConfigurationMessage(purpose: String): String =
    "SUPABASE_URL y SUPABASE_SECRET_KEY (o la legacy SUPABASE_SERVICE_ROLE_KEY) son obligatorios para $purpose."

/**
 * Los gateways que NO se pueden construir con [environment] (F-01).
 *
 * Existe para poder probar el arranque con un entorno inyectado —sólo
 * `SUPABASE_SECRET_KEY`, por ejemplo— sin tocar el entorno del proceso: llama a
 * los MISMOS `fromEnvironment` que usa [module]. Una lista vacía significa que
 * [module] no se detendría por la configuración de estos gateways.
 *
 * `SupabaseV1Gateway` y `AdminV1Gateway` no aparecen porque su `fromEnvironment`
 * no admite entorno inyectado (sus ficheros no son de esta fase); los dos
 * resuelven ya la clave con [SupabaseRequestAuthPolicy.secretFromEnvironment], y
 * `SupabaseEnvironmentWiringTest` vigila en las fuentes que ningún fichero vuelva
 * a leer la variable legacy por su cuenta.
 */
internal fun unconfiguredSupabaseGateways(json: Json, environment: (String) -> String?): List<String> =
    listOfNotNull(
        "SupabaseContentGateway".takeIf { SupabaseContentGateway.fromEnvironment(json, environment) == null },
        "SupabaseNewsGateway".takeIf { SupabaseNewsGateway.fromEnvironment(json, environment) == null },
        "SupabaseTagSearchGateway".takeIf { SupabaseTagSearchGateway.fromEnvironment(json, environment) == null },
        "SupabaseAdminGateway".takeIf { SupabaseAdminGateway.fromEnvironment(json, environment) == null },
        "AdminAnalyticsGateway".takeIf { AdminAnalyticsGateway.fromEnvironment(json, environment) == null },
        "AdminRankingsGateway".takeIf { AdminRankingsGateway.fromEnvironment(json, environment) == null },
        "AdminDashboardOverviewGateway".takeIf {
            AdminDashboardOverviewGateway.fromEnvironment(json, environment) == null
        },
        "SupabaseOposiBotsUserAdminGateway".takeIf {
            SupabaseOposiBotsUserAdminGateway.fromEnvironment(json, environment) == null
        },
        "AppV1Gateway".takeIf { AppV1Gateway.fromEnvironment(json, environment) == null },
    )

/**
 * Traducción de excepciones a respuestas HTTP de todo el backend (OposiBot maneja
 * las suyas).
 *
 * Vive fuera de [module] para que las pruebas monten EXACTAMENTE esta
 * configuración y no una copia (`BackendStatusPagesTest`): la redacción, los
 * códigos y las cabeceras son contrato, y una copia en el test puede seguir verde
 * con el original roto.
 *
 * Centralizado (punto 7 de la tarea de observabilidad): cada handler emite como
 * mucho UN evento estratégico, con request_id/duration ya disponibles vía
 * [installRequestCorrelation]; nunca se duplica el mismo error en logs sueltos.
 *
 * Precedencia: `StatusPages` elige el handler de la clase MÁS CERCANA a la de la
 * excepción, no el primero registrado. Por eso `SerializationException`, que
 * extiende `IllegalArgumentException`, tiene su propio handler y no cae en el 400.
 */
/**
 * MASTER5-OBS-004: sujeto de una denegacion para la telemetria: el id canonico ya verificado por
 * [CanonicalIdentityResolver] o, en las superficies `authenticate {}`, el principal si es un uuid
 * ([actorIdFromPrincipalName]: el token estatico no es una persona y queda `null`).
 */
internal fun ApplicationCall.deniedSubjectOrNull(): String? =
    verifiedCanonicalUserIdOrNull() ?: actorIdFromPrincipalName(principal<UserIdPrincipal>()?.name)

internal fun Application.installBackendStatusPages() {
    // Sobre canonico (SLICE 0): cualquier `ErrorDto` que responda una ruta
    // directamente —sin pasar por una excepcion— sale tambien con `requestId`, el
    // mismo valor que la cabecera `X-Request-Id`.
    installErrorEnvelopeRequestId()
    install(StatusPages) {
        exception<AppUnauthorized> { call, cause ->
            logStrategicEvent(
                StrategicEvent.AUTH_LOGIN_FAILED,
                requestId = call.requestIdOrNull(),
                operation = call.request.path(),
                result = "unauthorized",
                durationMs = call.elapsedMsOrNull(),
                errorType = cause::class.simpleName,
            )
            // El mensaje de la excepcion NO se publica: puede llevar el texto del
            // verificador JWT. El 401 dice siempre lo mismo.
            call.respondErrorEnvelope(CanonicalErrorCode.UNAUTHENTICATED)
        }
        exception<AppForbidden> { call, cause ->
            logStrategicEvent(
                StrategicEvent.AUTHORIZATION_DENIED,
                requestId = call.requestIdOrNull(),
                operation = call.request.path(),
                result = "forbidden",
                durationMs = call.elapsedMsOrNull(),
                errorType = cause::class.simpleName,
                // MASTER5-OBS-004: el sujeto (uuid del principal ya autenticado; nunca el token estatico ni un nombre).
                userId = call.deniedSubjectOrNull(),
            )
            call.respondErrorEnvelope(
                CanonicalErrorCode.FORBIDDEN,
                message = cause.message ?: CanonicalErrorCode.FORBIDDEN.defaultMessage,
            )
        }
        exception<OposiBotsUserAdminNotFoundException> { call, cause ->
            call.respondErrorEnvelope(
                CanonicalErrorCode.NOT_FOUND,
                message = cause.message ?: "Usuario no encontrado.",
            )
        }
        exception<BackendDomainException> { call, cause ->
            // Un rechazo TRANSITORIO de la base de datos (bloqueo, deadlock,
            // statement timeout) lleva `Retry-After`: dice al cliente que reintentar
            // tiene sentido y cuándo. Es un problema de infraestructura, no una regla
            // de negocio, y por eso —sólo éste— deja evento en el log.
            // MASTER-OBS-003: TODO 5xx de dominio (502 UPSTREAM_CONTRACT_ERROR, 503...) deja evento en el log con el
            // requestId, el codigo y la causa redactada; antes solo los que llevaban Retry-After.
            if (cause.status.value >= 500) {
                val requestId = call.ensureRequestId()
                logStrategicEvent(
                    // MASTER4-API-002: un INTERNAL de dominio (p. ej. la contencion agotada de `updateUsuario`) NO es un
                    // fallo del upstream: se cuenta como el resto de 500 INTERNAL del servidor, no como SUPABASE_REQUEST_FAILED.
                    if (cause.errorCode == CanonicalErrorCode.INTERNAL.name) StrategicEvent.UNCAUGHT_EXCEPTION else StrategicEvent.SUPABASE_REQUEST_FAILED,
                    requestId = requestId,
                    operation = call.request.path(),
                    result = cause.status.value.toString(),
                    durationMs = call.elapsedMsOrNull(),
                    errorType = cause.errorCode,
                )
                UpstreamFailureLogger.warn(
                    "domain failure {} status={} on {} (requestId={}): {}",
                    cause.errorCode,
                    cause.status.value,
                    call.request.path(),
                    requestId,
                    (cause.cause ?: cause).redactedMessage("Upstream failure"),
                )
            } else if (cause.status == HttpStatusCode.Unauthorized || cause.status == HttpStatusCode.Forbidden) {
                // MASTER5-OBS-004: las denegaciones canonicas (UNAUTHENTICATED, STALE_AUTH, REAUTH_FAILED, FORBIDDEN,
                // ACCOUNT_BLOCKED, ACCOUNT_DELETION_PENDING, PROFILE_MISSING...) dejan evento con el codigo como
                // `error_type` y el sujeto verificado si lo hay; nunca el mensaje.
                logStrategicEvent(
                    if (cause.status == HttpStatusCode.Unauthorized) StrategicEvent.AUTH_LOGIN_FAILED else StrategicEvent.AUTHORIZATION_DENIED,
                    requestId = call.ensureRequestId(),
                    operation = call.request.path(),
                    result = cause.status.value.toString(),
                    durationMs = call.elapsedMsOrNull(),
                    errorType = cause.errorCode,
                    userId = call.deniedSubjectOrNull(),
                )
            }
            cause.retryAfterSeconds?.let { seconds ->
                call.response.header(HttpHeaders.RetryAfter, seconds.toString())
            }
            // `code` es `errorCode` tal cual (ya es UPPER_SNAKE), salvo los
            // transitorios de la base, que son el generico DB_BUSY. `error` legacy
            // = `errorCode.lowercase()`, como hasta ahora: OC lo lee por regex.
            val code = if (cause.errorCode in TRANSIENT_DB_ERROR_CODES) CanonicalErrorCode.DB_BUSY.name else cause.errorCode
            call.respondErrorEnvelope(
                status = cause.status,
                code = code,
                message = if (cause.status.value >= 500 && "Referencia" !in cause.message) {
                    "${cause.message} Referencia: ${call.ensureRequestId()}"
                } else {
                    cause.message
                },
                details = cause.details,
                legacyError = cause.errorCode.lowercase(),
            )
        }
        // Un cuerpo JSON mal formado llega como BadRequestException, que NO extiende
        // IllegalArgumentException: sin este handler caía en el catch-all y cada
        // payload inválido de cualquier endpoint devolvía un 500 con el nombre de
        // la clase interna esperada. Es un 400 del cliente, y no se filtra el DTO.
        exception<BadRequestException> { call, _ ->
            call.respondErrorEnvelope(CanonicalErrorCode.MALFORMED_BODY)
        }
        // Ktor responde 415 sin cuerpo cuando ningun conversor acepta el Content-Type.
        status(HttpStatusCode.UnsupportedMediaType) { call, _ ->
            call.respondErrorEnvelope(CanonicalErrorCode.UNSUPPORTED_MEDIA_TYPE)
        }
        // MASTER2-API-004: una ruta inexistente (o un metodo no soportado) responde en Ktor un 404/405 DESNUDO, sin sobre ni
        // requestId. Solo se sustituye el contenido "de estado" (`HttpStatusCodeContent`); un 404 que una ruta ya responde
        // con su propio cuerpo (p. ej. `ErrorDto("not_found", ...)`) no se toca.
        status(HttpStatusCode.NotFound) { _ ->
            if (content !is HttpStatusCodeContent) return@status
            call.respondErrorEnvelope(CanonicalErrorCode.NOT_FOUND)
        }
        status(HttpStatusCode.MethodNotAllowed) { _ ->
            if (content !is HttpStatusCodeContent) return@status
            call.respondErrorEnvelope(
                HttpStatusCode.MethodNotAllowed,
                "METHOD_NOT_ALLOWED",
                "El metodo HTTP no esta admitido en este recurso.",
            )
        }
        // MASTER-API-018: el challenge del proveedor `bearer` de Ktor (sin token o con un token que no
        // verifica en `gateway-auth`) responde un 401 DESNUDO: sin sobre, sin requestId y sin traza. Solo
        // se sustituye ESE contenido (`UnauthorizedResponse`); un 401 que una ruta ya responde con su
        // propio cuerpo no se toca.
        status(HttpStatusCode.Unauthorized) { _ ->
            if (content !is UnauthorizedResponse) return@status
            logStrategicEvent(
                StrategicEvent.AUTH_LOGIN_FAILED,
                requestId = call.ensureRequestId(),
                operation = call.request.path(),
                result = "unauthorized",
                durationMs = call.elapsedMsOrNull(),
                errorType = "BearerChallenge",
            )
            call.respondErrorEnvelope(CanonicalErrorCode.UNAUTHENTICATED)
        }
        exception<CannotTransformContentToTypeException> { call, _ ->
            call.respondErrorEnvelope(CanonicalErrorCode.UNSUPPORTED_MEDIA_TYPE)
        }
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respondErrorEnvelope(CanonicalErrorCode.UNSUPPORTED_MEDIA_TYPE)
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respondErrorEnvelope(CanonicalErrorCode.PAYLOAD_TOO_LARGE)
        }
        // F-08. `SerializationException` extiende `IllegalArgumentException`, así que
        // una respuesta de Supabase que no casaba con el DTO —columna renombrada,
        // tipo inesperado— salía como 400 "petición inválida" con el mensaje de
        // kotlinx dentro: nombres de clase, de campos y un fragmento del JSON del
        // upstream. No es un error del cliente sino del contrato con el servicio de
        // datos: 502, redactado y con referencia, como cualquier otro fallo upstream.
        // Los cuerpos de PETICIÓN mal formados no pasan por aquí: Ktor los convierte
        // en `BadRequestException` (handler de arriba).
        exception<SerializationException> { call, cause ->
            val requestId = call.ensureRequestId()
            logStrategicEvent(
                StrategicEvent.SUPABASE_REQUEST_FAILED,
                requestId = requestId,
                operation = call.request.path(),
                result = "contract_error",
                durationMs = call.elapsedMsOrNull(),
                errorType = cause::class.simpleName,
            )
            UpstreamFailureLogger.warn(
                "upstream contract failure on {} (requestId={}): {}",
                call.request.path(),
                requestId,
                cause.redactedMessage("Upstream contract error"),
            )
            call.respondErrorEnvelope(
                CanonicalErrorCode.UPSTREAM_CONTRACT_ERROR,
                message = "No se pudo interpretar la respuesta del servicio de datos. Referencia: $requestId",
            )
        }
        // RB-1-05: el mensaje solo se refleja si lo escribio NUESTRO codigo
        // (`require`/`throw IllegalArgumentException("...")` en com.oposicontrol.*).
        // Una IAE que nace en la JDK o en una libreria (UUID.fromString,
        // NumberFormatException...) trae texto con eco de la entrada del cliente:
        // mensaje generico.
        exception<IllegalArgumentException> { call, cause ->
            call.respondErrorEnvelope(
                CanonicalErrorCode.VALIDATION,
                message = if (cause.isThrownByBackendCode()) cause.safeBackendMessage("Invalid request") else CanonicalErrorCode.VALIDATION.defaultMessage,
                // SLICE 1 (D1-23/24): un id de ruta que no es UUID lleva `details.issues[{field, NON_UUID}]`.
                details = (cause as? NonUuidPathException)?.let { ErrorDetails(issues = listOf(ErrorIssue(it.field, "NON_UUID"))) },
            )
        }
        // Fallo del upstream (Supabase/PostgREST): 502 UPSTREAM_ERROR, o 504
        // UPSTREAM_TIMEOUT si fue un timeout. Antes era un 500
        // `backend_supabase_error`; el adaptador de OC trata todos los 5xx igual.
        exception<IllegalStateException> { call, cause ->
            val isTimeout = cause.looksLikeUpstreamTimeout()
            val requestId = call.ensureRequestId()
            logStrategicEvent(
                if (isTimeout) StrategicEvent.UPSTREAM_TIMEOUT else StrategicEvent.SUPABASE_REQUEST_FAILED,
                requestId = requestId,
                operation = call.request.path(),
                result = "error",
                durationMs = call.elapsedMsOrNull(),
                errorType = cause::class.simpleName,
            )
            // El detalle del upstream se registra, no se devuelve. Los errores de
            // PostgREST incluyen un campo `hint` que sugiere nombres de tablas
            // reales ("Perhaps you meant the table 'public.servicios'"), y esta
            // ruta es alcanzable sin autenticar: era un oráculo de esquema. El
            // request id permite correlacionar con el log sin filtrar nada.
            UpstreamFailureLogger.warn(
                "upstream failure on {} (requestId={}): {}",
                call.request.path(),
                requestId,
                cause.redactedMessage("Backend error"),
            )
            val code = if (isTimeout) CanonicalErrorCode.UPSTREAM_TIMEOUT else CanonicalErrorCode.UPSTREAM_ERROR
            call.respondErrorEnvelope(
                code,
                message = "${code.defaultMessage} Referencia: $requestId",
            )
        }
        exception<Throwable> { call, cause ->
            val requestId = call.ensureRequestId()
            logStrategicEvent(
                StrategicEvent.UNCAUGHT_EXCEPTION,
                requestId = requestId,
                operation = call.request.path(),
                result = "error",
                durationMs = call.elapsedMsOrNull(),
                errorType = cause::class.simpleName,
            )
            Sentry.withScope { scope ->
                scope.setTag("request_id", requestId)

                scope.setTag(
                    "operation",
                    call.request.path(),
                )

                call.elapsedMsOrNull()?.let {
                    scope.setExtra("duration_ms", it.toString())
                }

                Sentry.captureException(cause)
            }
            // El detalle va al log, nunca al cuerpo. Antes se respondía
            // "${'$'}{cause::class.simpleName}: ..." y eso publicaba nombres de clase
            // Kotlin/Java a cualquiera capaz de provocar un 500: el mismo oráculo
            // que el handler de arriba ya había cerrado para los fallos upstream,
            // abierto aquí para todo lo demás. El request id es lo que permite
            // correlacionar con el log sin filtrar nada.
            UpstreamFailureLogger.warn(
                "uncaught exception on {} (requestId={}): {}: {}",
                call.request.path(),
                requestId,
                cause::class.simpleName,
                cause.redactedMessage("Unexpected backend error", 200),
            )
            call.respondErrorEnvelope(
                CanonicalErrorCode.INTERNAL,
                message = "${CanonicalErrorCode.INTERNAL.defaultMessage} Referencia: $requestId",
            )
        }
    }
}

fun Application.module() {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = false
    }
    val serverProfile = ServerProfile.parse(System.getenv("SERVER_PROFILE"))
    val consumerOnly = serverProfile == ServerProfile.CONSUMER
    // `control` no monta ninguna ruta de consumidor: no construye el runtime de auth (ni exige Valkey/GoTrue). `bot` y
    // `consumer` lo exigen; `combined` lo monta solo si esta configurado (historico).
    val authRuntime = if (serverProfile == ServerProfile.CONTROL) {
        null
    } else {
        OposiBotsAuthRuntime.fromEnvironment(json, required = consumerOnly || serverProfile == ServerProfile.BOT)
    }
    // MASTER-ARCH-008: cierre ordenado de los recursos del runtime de auth (Valkey, HttpClient) al parar.
    authRuntime?.let { runtime -> monitor.subscribe(ApplicationStopped) { runtime.close() } }
    installBackendPrometheusMetrics()
    if (consumerOnly) {
        configureConsumerAuthOnly(authRuntime ?: error("Auth consumer no configurado."))
        return
    }
    // Los gateways de administracion solo se construyen (y exigen configuracion) si el perfil monta rutas admin.
    fun <T : Any> adminOnly(purpose: String, build: () -> T?): T? =
        if (serverProfile.mountsControl) build() ?: error(missingSupabaseConfigurationMessage(purpose)) else null
    val gateway = adminOnly("servir contenido desde Ktor") { SupabaseContentGateway.fromEnvironment(json) }
    val newsGateway = adminOnly("servir noticias desde Ktor") { SupabaseNewsGateway.fromEnvironment(json) }
    val tagSearchGateway = adminOnly("servir busqueda desde Ktor") { SupabaseTagSearchGateway.fromEnvironment(json) }
    val adminGateway = adminOnly("servir admin desde Ktor") { SupabaseAdminGateway.fromEnvironment(json) }
    val adminAnalyticsGateway = adminOnly("servir analitica desde Ktor") { AdminAnalyticsGateway.fromEnvironment(json) }
    val adminRankingsGateway = adminOnly("servir rankings desde Ktor") { AdminRankingsGateway.fromEnvironment(json) }
    val adminDashboardOverviewGateway = adminOnly("servir el dashboard agregado desde Ktor") {
        AdminDashboardOverviewGateway.fromEnvironment(json)
    }
    val v1Gateway = SupabaseV1Gateway.fromEnvironment(json)
        ?: error(missingSupabaseConfigurationMessage("servir API V1 desde Ktor"))
    val adminV1Gateway = adminOnly("servir admin API V1 desde Ktor") { AdminV1Gateway.fromEnvironment(json) }
    val oposiBotsUserAdminGateway = adminOnly("servir la administracion de usuarios de OposiBots") {
        SupabaseOposiBotsUserAdminGateway.fromEnvironment(json)
    }
    val appV1Gateway = AppV1Gateway.fromEnvironment(json)
        ?: error(missingSupabaseConfigurationMessage("servir app API V1 desde Ktor"))
    val supabaseUrl = System.getenv("SUPABASE_URL")?.trimEnd('/').orEmpty()
    val jwtVerifier = SupabaseJwtVerifier(supabaseUrl, json)
    // MASTER6-AUTH-008: precarga del JWKS de mejor esfuerzo, fuera del arranque (pool IO, acotada por JWKS_FETCH_TIMEOUT):
    // la primera rafaga de peticiones ya no encuentra la cache vacia.
    if (supabaseUrl.isNotBlank()) launch(kotlinx.coroutines.Dispatchers.IO) { jwtVerifier.warmUp() }
    val appEnv = AppEnvironment.fromSystemEnvironment()
    val allowDevUser = allowsDevBypass(
        appEnv,
        System.getenv("OPOSICONTROL_ALLOW_DEV_USER")?.toBooleanStrictOrNull() ?: false,
    )
    val canonicalIdentityResolver = if (serverProfile.mountsBot) {
        PostgrestCanonicalProfileDirectory.fromEnvironment()?.let {
            CanonicalIdentityResolver(jwtVerifier::subFrom, it)
        }
    } else {
        null
    }
    val catalogRuntime = canonicalIdentityResolver?.let { CatalogRuntime.fromEnvironment(it) }
    // SLICE 7: lecturas de cartera (D6-01, D6-02), solo lectura, misma credencial de servicio.
    val walletRuntime = canonicalIdentityResolver?.let { WalletRuntime.fromEnvironment(it) }
    // FA15: lecturas de consumidor (D7-05, D5-01, D5-16), solo lectura, misma credencial de servicio.
    val appReadsRuntime = canonicalIdentityResolver?.let { AppReadsRuntime.fromEnvironment(it) }
    // FA11 (D4-01..D4-11): noticias canonicas. UN servicio compartido con `/content/noticias*`; la auditoria usa el actor del JWT.
    val newsRuntime = adminOnly("servir noticias canonicas desde Ktor") {
        val idempotency = PostgrestIdempotencyStore.fromEnvironment() ?: return@adminOnly null
        val news = newsGateway ?: return@adminOnly null
        val search = tagSearchGateway ?: return@adminOnly null
        val audit = adminV1Gateway ?: return@adminOnly null
        NewsRuntime(
            NewsService(
                gateway = news,
                audit = NewsAuditSink { accion, tabla, registroId, antes, despues -> audit.registrarAuditoria(accion, tabla, registroId, antes, despues) },
                onIndexed = search::indexNoticia,
                onRemoved = { search.remove("noticia", it) },
            ),
            idempotency,
        )
    }
    val routingDeps = CombinedRoutingDeps(
        contentGateway = gateway,
        newsGateway = newsGateway,
        tagSearchGateway = tagSearchGateway,
        adminGateway = adminGateway,
        adminAnalyticsGateway = adminAnalyticsGateway,
        adminRankingsGateway = adminRankingsGateway,
        adminDashboardOverviewGateway = adminDashboardOverviewGateway,
        v1Gateway = v1Gateway,
        adminV1Gateway = adminV1Gateway,
        oposiBotsUserAdminGateway = oposiBotsUserAdminGateway,
        appV1Gateway = appV1Gateway,
        jwtVerifier = jwtVerifier,
        allowDevUser = allowDevUser,
        appEnv = appEnv,
        authRuntime = authRuntime,
        identityResolver = canonicalIdentityResolver,
        catalogRuntime = catalogRuntime,
        walletRuntime = walletRuntime,
        appReadsRuntime = appReadsRuntime,
        newsRuntime = newsRuntime,
        // SLICE 3: mismo resolver y MISMA politica de acceso (el servicio de catalogo) que SLICE 2.
        attemptRuntime = canonicalIdentityResolver?.let { resolver ->
            catalogRuntime?.let { AttemptRuntime.fromEnvironment(resolver, it.catalog, authRuntime?.rateLimiter) }
        },
    )
    configureServerProfile(serverProfile, json, routingDeps)
}

/**
 * Plugins + rutas de un perfil NO `consumer`. Extraido de [module] SIN cambiar plugins ni orden para que las pruebas
 * (inventario por perfil, statelessness) monten exactamente el cableado de produccion con gateways de prueba.
 */
internal fun Application.configureServerProfile(profile: ServerProfile, json: Json, deps: CombinedRoutingDeps) {
    require(profile != ServerProfile.CONSUMER) { "consumer se configura con configureConsumerAuthOnly" }
    install(ContentNegotiation) {
        json(json)
    }
    // Observability foundation (Observability.kt): request correlation, logs
    // estrategicos, health/readiness. No toca OposiBot -- ver KDoc del archivo.
    installRequestCorrelation()
    installBackendStatusPages()
    // MASTER2-SEC-002 / MASTER3-OBS-003: tope global de cuerpo (no multipart), explicito aqui (ya no es un efecto lateral de
    // `installBackendStatusPages()`); el 413 sale con el sobre canonico de StatusPages.
    installRequestBodyLimit()
    // `gateway-auth` solo lo usan las rutas admin: `bot` no lo monta ni lo necesita.
    if (profile.mountsControl) {
        install(Authentication) {
            gatewayBearerAuth(deps.appEnv, deps.jwtVerifier::subFrom)
        }
    }
    routing { installProfileRoutes(profile, deps) }
}

/**
 * Dependencias del arbol de rutas de los perfiles `bot`, `control` y `combined`. Extraido de [module] SIN cambiar rutas,
 * plugins ni orden para que el inventario (`RouteInventoryTest`, `RouteProfileGateTest`) recorra el arbol REAL con
 * gateways de prueba, en vez de una copia a mano.
 *
 * Los gateways de administracion son nulos cuando el perfil no los monta (`bot`); [installControlRoutes] los exige.
 */
internal class CombinedRoutingDeps(
    val contentGateway: SupabaseContentGateway?,
    val newsGateway: SupabaseNewsGateway?,
    val tagSearchGateway: SupabaseTagSearchGateway?,
    val adminGateway: SupabaseAdminGateway?,
    val adminAnalyticsGateway: AdminAnalyticsGateway?,
    val adminRankingsGateway: AdminRankingsGateway?,
    val adminDashboardOverviewGateway: AdminDashboardOverviewGateway?,
    val v1Gateway: SupabaseV1Gateway,
    val adminV1Gateway: AdminV1Gateway?,
    val oposiBotsUserAdminGateway: OposiBotsUserAdminGateway?,
    val appV1Gateway: AppV1Gateway,
    val jwtVerifier: SupabaseJwtVerifier,
    val allowDevUser: Boolean,
    val appEnv: String,
    val authRuntime: OposiBotsAuthRuntime?,
    val identityResolver: CanonicalIdentityResolver?,
    /** Sonda de dependencia critica de `/health/ready` (Supabase). Inyectable para probar el 503; produccion usa la real. */
    val readinessCheck: () -> String = ::checkSupabaseReachable,
    /** SLICE 2 (D2-01/02/13, D7-02). `null` = sin resolver de identidad: las rutas existen y fallan cerrado (500). */
    internal val catalogRuntime: CatalogRuntime? = null,
    /** SLICE 7 (D6-01, D6-02). `null` = sin resolver de identidad: las rutas existen y fallan cerrado (500). */
    internal val walletRuntime: WalletRuntime? = null,
    /** SLICE 3 (D2-03, D2-07..D2-12, D2-65). `null` = sin resolver de identidad: las rutas existen y fallan cerrado (500). */
    internal val attemptRuntime: AttemptRuntime? = null,
    /** FA11 (D4-01..D4-11). `null` = sin runtime: las rutas canonicas existen y fallan cerrado (500); el CRUD legacy usa un servicio propio. */
    internal val newsRuntime: NewsRuntime? = null,
    /** FA15 (D7-05, D5-01, D5-16). `null` = sin resolver de identidad: las rutas existen y fallan cerrado (500). */
    internal val appReadsRuntime: AppReadsRuntime? = null,
)

/** Perfil historico: todo. Conjunto de rutas identico al de siempre. */
internal fun Route.installCombinedRoutes(deps: CombinedRoutingDeps) = installProfileRoutes(ServerProfile.COMBINED, deps)

/**
 * Monta las rutas de [profile] (matriz: `ops/inventory/route-profiles.tsv`), agrupando los instaladores existentes:
 * infra (HEALTH+METRICS) y SHARED en todos; BOT (AUTH + `/api/v1/app/...`) en `bot`/`combined`; CONTROL (todo lo tras
 * `gateway-auth`) en `control`/`combined`. El orden de montaje de `combined` es el historico.
 */
internal fun Route.installProfileRoutes(profile: ServerProfile, deps: CombinedRoutingDeps) {
    require(profile != ServerProfile.CONSUMER) { "consumer se configura con configureConsumerAuthOnly" }
    installInfraRoutes(deps)
    installSharedRoutes(deps)
    if (profile.mountsBot) installBotRoutes(deps)
    if (profile.mountsControl) installControlRoutes(deps, includeInternal = profile == ServerProfile.COMBINED)
}

/** HEALTH + METRICS: presentes en todos los perfiles. `/health/ready` refleja la dependencia critica real (Supabase). */
internal fun Route.installInfraRoutes(deps: CombinedRoutingDeps) {
    prometheusMetricsRoute()
    legacyHealthRoute(
        appEnv = System.getenv("APP_ENV"),
        restartFlag = System.getenv("OPOSICONTROL_ENABLE_BACKEND_RESTART"),
    )
    // /health/live y /health/ready (Observability.kt): mas precisos que
    // /health (que se deja igual, por compatibilidad con lo que ya lo
    // consulta). Publicos a proposito, igual que /health -- un balanceador
    // u orquestador no puede mandar el Bearer de gateway-auth.
    observabilityHealthRoutes(deps.readinessCheck)
}

/** SHARED: lecturas publicas `/v1/...` (sin auth). Las consumen OposiBot y el diagnostico de OposiControl. */
internal fun Route.installSharedRoutes(deps: CombinedRoutingDeps) {
    v1Routes(deps.v1Gateway)
}

/** BOT + AUTH: `/api/v1/app/...` (JWT de usuario en el handler) y, con runtime de auth, `/v1/auth/...` + perfil canonico. */
internal fun Route.installBotRoutes(deps: CombinedRoutingDeps) = with(deps) {
    appV1Routes(
        appV1Gateway,
        jwtVerifier,
        allowDevUser,
        identityResolver = identityResolver,
    )
    // SLICE 2: catalogo y derechos del consumidor (D2-01, D2-02, D2-13, D7-02).
    catalogRoutes(catalogRuntime)
    // SLICE 7: cartera del consumidor, solo lectura (D6-01, D6-02).
    walletRoutes(walletRuntime)
    // FA15: historial de compras y suscripciones, categorias de OposiRed y contadores de OposiTester (D7-05, D5-01, D5-16).
    appReadsRoutes(appReadsRuntime)
    // SLICE 3: runtime de examen del consumidor (D2-03, D2-07, D2-08, D2-09, D2-10, D2-11, D2-12, D2-65).
    attemptRoutes(attemptRuntime)
    authRuntime?.let {
        oposiBotsAuthRoutes(it)
        // SLICE 1: perfil / onboarding / documentos legales / borrado de cuenta canonicos (JWT propio, sin gateway-auth).
        canonicalProfileRoutes(it)
    }
}

private fun <T : Any> T?.requiredForControl(name: String): T =
    this ?: error("El perfil con rutas admin necesita $name.")

/** CONTROL: toda la superficie admin / contenido / legacy admin, tras `authenticate("gateway-auth")`. */
internal fun Route.installControlRoutes(deps: CombinedRoutingDeps, includeInternal: Boolean): Unit = with(deps) {
    val contentGateway = deps.contentGateway.requiredForControl("contentGateway")
    val newsGateway = deps.newsGateway.requiredForControl("newsGateway")
    val tagSearchGateway = deps.tagSearchGateway.requiredForControl("tagSearchGateway")
    val adminGateway = deps.adminGateway.requiredForControl("adminGateway")
    val adminAnalyticsGateway = deps.adminAnalyticsGateway.requiredForControl("adminAnalyticsGateway")
    val adminRankingsGateway = deps.adminRankingsGateway.requiredForControl("adminRankingsGateway")
    val adminDashboardOverviewGateway = deps.adminDashboardOverviewGateway.requiredForControl("adminDashboardOverviewGateway")
    val adminV1Gateway = deps.adminV1Gateway.requiredForControl("adminV1Gateway")
    val oposiBotsUserAdminGateway = deps.oposiBotsUserAdminGateway.requiredForControl("oposiBotsUserAdminGateway")
    val gateway = contentGateway
    run {
        authenticate("gateway-auth") {
            // Confina el token estático a superficies legacy locales y exige rol
            // admin a cualquier JWT. Sustituye a un intercept en fase Plugins que
            // nunca llegaba a rechazar nada (ver AdminAuthorizationPlugin).
            val environmentName = appEnv
            install(AdminAuthorizationPlugin) {
                // `this.` explícito: sin él, `appEnv` resuelve al val externo del
                // módulo en vez de a la propiedad de configuración del plugin.
                this.appEnv = environmentName
                // MASTER-AUTH-002: rol admin Y cuenta `activa` (deny-by-default).
                this.hasAdminRole = { appV1Gateway.hasActiveAdminRole(it) }
            }
            // MASTER-OBS-002: el `sub` del JWT como actor de toda auditoría administrativa.
            installAdminAuditActor()
            adminRoutes(adminGateway, adminV1Gateway)
            adminV1Routes(adminV1Gateway)
            // FA11: noticias canonicas de administracion (D4-01..D4-11), mismo servicio que `/content/noticias*`.
            adminNewsRoutes(deps.newsRuntime)
            adminRankingsRoutes(adminRankingsGateway)
            adminAnalyticsRoutes(adminAnalyticsGateway)
            adminDashboardOverviewRoutes(adminDashboardOverviewGateway)
            // MASTER-AUTH-001: legacy public.usuario; sin borrar cuentas de administración y con auditoría obligatoria.
            oposiBotsUserAdminRoutes(
                oposiBotsUserAdminGateway,
                OposiBotsUserAdminPolicy(
                    isAdministrative = { authUserId -> withUpstreamIo { appV1Gateway.hasAdminRole(authUserId) } },
                    audit = { accion, authUserId, detalle ->
                        val deletion = accion.endsWith("_eliminado")
                        withUpstreamIo {
                            adminV1Gateway.registrarAuditoria(
                                accion,
                                "usuario",
                                authUserId,
                                antes = detalle.takeIf { deletion },
                                despues = detalle.takeUnless { deletion },
                                required = true,
                            )
                        }
                    },
                ),
            )
            // INTERNAL (dev-only): reinicio del proceso. Solo en `combined`; `bot`/`control` no lo montan.
            if (includeInternal) installDevRestartRoutes()
            route("/content") {
                route("/especialidades") {
                    get { call.respond(gateway.listEspecialidades()) }
                    post {
                        val saved = gateway.saveEspecialidad(call.receive())
                        tagSearchGateway.indexEspecialidad(saved)
                        call.respond(HttpStatusCode.Created, saved)
                    }
                    put {
                        val saved = gateway.saveEspecialidad(call.receive())
                        tagSearchGateway.indexEspecialidad(saved)
                        call.respond(saved)
                    }
                    delete("/{especialidadId}") {
                        val id = call.requiredPath("especialidadId")
                        gateway.deleteEspecialidad(id)
                        tagSearchGateway.remove("especialidad", id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                route("/etiquetas") {
                    get { call.respond(gateway.listEtiquetas()) }
                    post { call.respond(HttpStatusCode.Created, gateway.saveEtiqueta(call.receive())) }
                    put { call.respond(gateway.saveEtiqueta(call.receive())) }
                    delete("/{etiquetaId}") {
                        gateway.deleteEtiqueta(call.requiredPath("etiquetaId"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                route("/comunidades") {
                    get { call.respond(gateway.listComunidades()) }
                    post { call.respond(HttpStatusCode.Created, gateway.saveComunidad(call.receive())) }
                    put { call.respond(gateway.saveComunidad(call.receive())) }
                    delete("/{comunidadId}") {
                        gateway.deleteComunidad(call.requiredPath("comunidadId"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                route("/solucionarios") {
                    get { call.respond(gateway.listSolucionarios()) }
                    post {
                        val saved = gateway.saveSolucionario(call.receive())
                        tagSearchGateway.indexSolucionario(saved)
                        call.respond(HttpStatusCode.Created, saved)
                    }
                    put {
                        val saved = gateway.saveSolucionario(call.receive())
                        tagSearchGateway.indexSolucionario(saved)
                        call.respond(saved)
                    }
                    delete("/{solucionarioId}") {
                        val id = call.requiredPath("solucionarioId")
                        gateway.deleteSolucionario(id)
                        tagSearchGateway.remove("examen", id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/{solucionarioId}/portada") {
                        call.respondBackendSupabaseResult("/content/solucionarios/{id}/portada") {
                            val saved = gateway.uploadPortada(
                                solucionarioId = call.requiredPath("solucionarioId"),
                                file = call.receiveSingleFile(
                                    maxBytes = COVER_MAX_BYTES,
                                    requiredMimePrefix = "image/",
                                    defaultFileName = "cover",
                                ),
                            )
                            runCatching { tagSearchGateway.indexSolucionario(saved) }
                                .onFailure { error ->
                                    System.err.println("[ktor] optional tag index skipped on cover upload: ${error.safeBackendMessage("index error", 160)}")
                                }
                            saved
                        }
                    }
                    delete("/{solucionarioId}/portada") {
                        call.respondBackendSupabaseResult("/content/solucionarios/{id}/portada") {
                            val saved = gateway.clearPortada(call.requiredPath("solucionarioId"))
                            runCatching { tagSearchGateway.indexSolucionario(saved) }
                                .onFailure { error ->
                                    System.err.println("[ktor] optional tag index skipped on cover clear: ${error.safeBackendMessage("index error", 160)}")
                                }
                            saved
                        }
                    }
                    get("/{solucionarioId}/preguntas") {
                        call.respond(gateway.listPreguntas(call.requiredPath("solucionarioId")))
                    }
                    post("/{solucionarioId}/preguntas") {
                        val saved = gateway.savePregunta(call.requiredPath("solucionarioId"), call.receive())
                        tagSearchGateway.indexPregunta(saved)
                        call.respond(
                            HttpStatusCode.Created,
                            saved,
                        )
                    }
                    put("/{solucionarioId}/preguntas") {
                        val saved = gateway.savePregunta(call.requiredPath("solucionarioId"), call.receive())
                        tagSearchGateway.indexPregunta(saved)
                        call.respond(saved)
                    }
                    delete("/{solucionarioId}/preguntas/{preguntaId}") {
                        val id = call.requiredPath("preguntaId")
                        gateway.deletePregunta(call.requiredPath("solucionarioId"), id)
                        tagSearchGateway.remove("pregunta", id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/{solucionarioId}/preguntas/order") {
                        call.respond(gateway.reorderPreguntas(call.requiredPath("solucionarioId"), call.receive()))
                    }
                }
                route("/rafaga") {
                    get { call.respond(gateway.listRafagas()) }
                    post {
                        call.respond(HttpStatusCode.Created, gateway.saveRafaga(call.receive()))
                    }
                    put {
                        call.respond(gateway.saveRafaga(call.receive()))
                    }
                    delete("/{rafagaId}") {
                        gateway.deleteRafaga(call.requiredPath("rafagaId"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/{rafagaId}/preguntas") {
                        call.respond(gateway.listRafagaPreguntas(call.requiredPath("rafagaId")))
                    }
                    post("/{rafagaId}/preguntas") {
                        call.respond(
                            HttpStatusCode.Created,
                            gateway.saveRafagaPregunta(call.requiredPath("rafagaId"), call.receive()),
                        )
                    }
                    put("/{rafagaId}/preguntas") {
                        call.respond(gateway.saveRafagaPregunta(call.requiredPath("rafagaId"), call.receive()))
                    }
                    delete("/{rafagaId}/preguntas/{preguntaId}") {
                        gateway.deleteRafagaPregunta(call.requiredPath("rafagaId"), call.requiredPath("preguntaId"))
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/{rafagaId}/preguntas/order") {
                        call.respond(gateway.reorderRafagaPreguntas(call.requiredPath("rafagaId"), call.receive()))
                    }
                }
                route("/preguntas") {
                    post("/{preguntaId}/recursos/{resourceId}/upload") {
                        call.respondBackendSupabaseResult("/content/preguntas/{id}/recursos/{id}/upload") {
                            val saved = gateway.uploadRecurso(
                                preguntaId = call.requiredPath("preguntaId"),
                                resourceId = call.requiredPath("resourceId"),
                                tipoRecurso = call.request.queryParameters["tipoRecurso"].orEmpty(),
                                titulo = call.request.queryParameters["titulo"].orEmpty(),
                                orden = call.request.queryParameters["orden"]?.toIntOrNull() ?: 0,
                                file = call.receiveSingleFile(
                                    maxBytes = RESOURCE_MAX_BYTES,
                                    requiredMimePrefix = null,
                                    defaultFileName = "resource",
                                ),
                            )
                            tagSearchGateway.indexRecurso(saved)
                            saved
                        }
                    }
                    post("/{preguntaId}/recursos") {
                        val saved = gateway.saveRecurso(call.requiredPath("preguntaId"), call.receive())
                        tagSearchGateway.indexRecurso(saved)
                        call.respond(
                            HttpStatusCode.Created,
                            saved,
                        )
                    }
                    delete("/{preguntaId}/recursos/{resourceId}") {
                        val id = call.requiredPath("resourceId")
                        gateway.deleteRecurso(
                            preguntaId = call.requiredPath("preguntaId"),
                            resourceId = id,
                        )
                        tagSearchGateway.remove("recurso_pregunta", id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/{preguntaId}/recursos/order") {
                        call.respond(gateway.reorderRecursos(call.requiredPath("preguntaId"), call.receive()))
                    }
                }
                newsContentRoutes(
                    newsGateway = newsGateway,
                    onIndexed = tagSearchGateway::indexNoticia,
                    onRemoved = { tagSearchGateway.remove("noticia", it) },
                    news = deps.newsRuntime?.service ?: NewsService(
                        newsGateway,
                        onIndexed = tagSearchGateway::indexNoticia,
                        onRemoved = { tagSearchGateway.remove("noticia", it) },
                    ),
                )
            }
            get("/tags") {
                call.respondBackendSupabaseResult("/tags") {
                    tagSearchGateway.listTags(call.request.queryParameters["q"].orEmpty())
                }
            }
            get("/search") {
                call.respondBackendSupabaseResult("/search") {
                    tagSearchGateway.search(
                        query = call.request.queryParameters["q"].orEmpty(),
                        tagSlugs = call.request.queryParameters["tags"].orEmpty()
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotBlank() },
                        section = call.request.queryParameters["section"],
                    )
                }
            }
            post("/search/reindex") {
                call.respond(tagSearchGateway.reindexAll())
            }
        }
    }
}

internal fun Application.configureConsumerAuthOnly(runtime: OposiBotsAuthRuntime) {
    install(ContentNegotiation) {
        json(runtime.json)
    }
    // SLICE 1: las rutas canonicas de perfil responden con el sobre canonico (StatusPages) y X-Request-Id.
    installRequestCorrelation()
    installBackendStatusPages()
    installRequestBodyLimit()
    routing {
        prometheusMetricsRoute()
        healthLiveRoute()
        oposiBotsAuthRoutes(runtime)
        canonicalProfileRoutes(runtime)
    }
}

/**
 * El proveedor `gateway-auth` de producción, extraído para que las pruebas monten EXACTAMENTE
 * este cableado (MASTER-API-018) junto con [installBackendStatusPages].
 */
internal fun AuthenticationConfig.gatewayBearerAuth(
    appEnv: String,
    verify: (String) -> String,
    expectedStaticToken: () -> String = { System.getenv("OPOSICONTROL_BACKEND_TOKEN").orEmpty() },
) {
    bearer("gateway-auth") {
        authenticate { credentials ->
            gatewayAuthPrincipal(credentials.token, appEnv, verify, expectedStaticToken = expectedStaticToken())
        }
    }
}

internal fun allowsDevBypass(appEnv: String, enabled: Boolean): Boolean {
    // MASTER2-SEC-001: un unico criterio de "desarrollo" (AppEnvironment), fail-closed.
    return enabled && AppEnvironment.isDevelopment(appEnv)
}

/** MASTER-SEC-013: comparacion en tiempo constante de secretos (UTF-8). */
internal fun constantTimeEquals(provided: String, expected: String): Boolean =
    java.security.MessageDigest.isEqual(
        provided.toByteArray(StandardCharsets.UTF_8),
        expected.toByteArray(StandardCharsets.UTF_8),
    )

/**
 * La IDENTIDAD de una llamada a `gateway-auth`: el token estático, un JWT verificado,
 * o nadie.
 *
 * Sólo establece identidad. La autorización —confinar el token estático a las
 * superficies legacy locales y exigir rol admin— vive en `AdminAuthorizationPlugin`,
 * que corre en `AuthenticationChecked` y sí puede distinguir un 401 de un 403.
 *
 * ### Por qué es una función y no la lambda que era (P1-1)
 *
 * Estaba escrita dentro de `authenticate { … }` en `module()`, y ahí no hay forma de
 * probarla: `module()` exige el entorno entero de Supabase para llegar a construirse.
 * Lo que hay que poder probar es concretamente que la verificación del JWT ocurre
 * FUERA del hilo de la petición ([verifiedJwtPrincipalOrNull]): la primera vez, con un
 * `kid` nuevo o tras una rotación, descarga el JWKS con una petición bloqueante, y
 * esto corre en el pipeline de CADA llamada autenticada.
 *
 * Los dos valores de entorno se leen en cada llamada, como antes: son parámetros con
 * default, y un default se evalúa en la llamada, no al cargar la clase.
 */
internal suspend fun gatewayAuthPrincipal(
    token: String,
    appEnv: String,
    verify: (String) -> String,
    expectedStaticToken: String = System.getenv("OPOSICONTROL_BACKEND_TOKEN").orEmpty(),
    allowDevToken: Boolean = allowsDevBypass(
        appEnv,
        System.getenv("OPOSICONTROL_ALLOW_DEV_TOKEN")?.toBooleanStrictOrNull() ?: false,
    ),
): UserIdPrincipal? {
    val staticTokenAccepted = expectedStaticToken.isNotBlank() &&
        (constantTimeEquals(token, expectedStaticToken) || (allowDevToken && constantTimeEquals(token, "dev-local")))
    if (staticTokenAccepted) return UserIdPrincipal(STATIC_TOKEN_PRINCIPAL)
    return verifiedJwtPrincipalOrNull(token, verify)
}

/**
 * ¿La llamada viene de la propia máquina, en un entorno de desarrollo?
 *
 * Es la única barrera de red del token estático, así que se decide **sólo con la
 * dirección literal del socket**. Dos reglas que no deben relajarse:
 *
 * 1. **Nunca un nombre de host.** `origin.remoteHost` hace resolución inversa
 *    sobre el peer, y ese nombre lo controla quien conecta: con un PTR propio que
 *    apunte a un dominio cuyo registro A sea `127.0.0.1`, una petición remota se
 *    resolvía como loopback y abría la superficie legacy desde Internet. Aquí se
 *    parsea la IP tal cual, sin `getByName` y sin ninguna consulta DNS.
 * 2. **Sólo loopback.** Antes también se aceptaba `isSiteLocalAddress` y los
 *    prefijos `fc`/`fd`, es decir toda la RFC1918: cualquier equipo de la misma
 *    red llegaba a `/admin/roles` o a `/admin/backend/restart` con un token que
 *    además viaja compilado en la app Android. "Desarrollo local" es esta
 *    máquina, no la red entera.
 *
 * Detrás de un proxy o un túnel, el peer TCP es siempre loopback: por eso el
 * token estático no puede ser la única defensa de nada sensible.
 */
internal fun isLocalDevelopmentPeer(appEnv: String, remoteHost: String): Boolean {
    if (!AppEnvironment.isDevelopment(appEnv)) return false
    val literal = remoteHost.substringBefore('%').trim('[', ']')
    if (literal.isEmpty()) return false
    // Un nombre de host (no una IP literal) se rechaza: sólo se confía en lo que
    // no depende de DNS.
    if (!literal.all { it.isDigit() || it == '.' || it == ':' || (it in "abcdefABCDEF") }) return false
    return literal == "::1" ||
        literal == "0:0:0:0:0:0:0:1" ||
        literal.startsWith("127.") ||
        // IPv4 mapeada en IPv6, como la entrega Netty en algunos stacks duales.
        literal.startsWith("::ffff:127.", ignoreCase = true)
}

/**
 * MASTER2-SEC-001 / SEC-ALLOWDEVUSER: el reinicio (lanza powershell/gradlew) solo se habilita en un entorno EXPLICITO de
 * desarrollo. `OPOSICONTROL_ENABLE_BACKEND_RESTART` solo puede APAGARLO ahi (`false`); fuera de desarrollo (produccion,
 * staging, vacio o desconocido) `true` ya NO lo enciende (antes el interruptor explicito ganaba al entorno: fail-open).
 */
internal fun backendRestartEnabled(appEnv: String?, explicitFlag: String?): Boolean =
    AppEnvironment.isDevelopment(appEnv) && (explicitFlag?.trim()?.toBooleanStrictOrNull() ?: true)

/**
 * `/health` legado (publico). SEC-ALLOWDEVUSER: `restartEnabled` solo se publica en un entorno EXPLICITO de desarrollo;
 * fuera de el la clave no existe (ningun cliente la lee y era una divulgacion publica de la superficie de reinicio).
 */
internal fun legacyHealthPayload(appEnv: String?, restartFlag: String?): Map<String, String> = buildMap {
    put("status", "ok")
    put("build", "20260512-no-legacy-columns")
    if (AppEnvironment.isDevelopment(appEnv)) {
        put("restartEnabled", backendRestartEnabled(appEnv, restartFlag).toString())
    }
}

internal fun Route.legacyHealthRoute(appEnv: String?, restartFlag: String?) {
    get("/health") { call.respond(legacyHealthPayload(appEnv, restartFlag)) }
}

internal fun isLegacyStaticTokenPath(path: String): Boolean =
    path == "/admin" || path.startsWith("/admin/") ||
        path == "/content" || path.startsWith("/content/")

fun io.ktor.server.application.ApplicationCall.requiredPath(name: String): String {
    return parameters[name]?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing path parameter $name")
}

/**
 * El sobre canonico de error (SLICE 0, `specs.errors`): `{code!, message!,
 * requestId!, details?}` + campos LEGACY aditivos.
 *
 * - `code`: UPPER_SNAKE estable. Si una ruta legacy construye `ErrorDto(error =
 *   "bad_request", ...)`, `code` sale de [canonicalCodeForLegacyError].
 * - `requestId`: lo rellena [installErrorEnvelopeRequestId] con el mismo valor
 *   que la cabecera `X-Request-Id` cuando la ruta no lo pone.
 * - `error` (legacy, deprecated): `code.lowercase()` en lo canonico; OC lo lee
 *   por regex. En /v1/auth es el nombre del enum `OposiBotsErrorCode`.
 * - `issues` (legacy, deprecated): solo /v1/auth (OB `AuthErrorWire`).
 *
 * No hay un segundo DTO de error: este es el unico cuerpo non-2xx JSON.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ErrorDto(
    val error: String,
    val message: String,
    @EncodeDefault val code: String = canonicalCodeForLegacyError(error),
    val requestId: String? = null,
    val details: ErrorDetails? = null,
    val issues: List<String>? = null,
) {
    companion object {
        /** Un error canonico: `error` legacy = `code.lowercase()` salvo que se indique otro. */
        fun canonical(
            code: String,
            message: String,
            requestId: String?,
            details: ErrorDetails? = null,
            legacyError: String = code.lowercase(),
        ): ErrorDto = ErrorDto(
            error = legacyError,
            message = message,
            code = code,
            requestId = requestId,
            details = details,
        )
    }
}

/**
 * Rellena `requestId` en cualquier [ErrorDto] que salga sin el (rutas legacy que
 * responden `ErrorDto` a mano). Mismo valor que la cabecera `X-Request-Id`.
 */
internal fun Application.installErrorEnvelopeRequestId() {
    sendPipeline.intercept(ApplicationSendPipeline.Before) { message ->
        if (message is ErrorDto && message.requestId == null) {
            proceedWith(message.copy(requestId = call.ensureRequestId()))
        }
    }
}

/** Responde el sobre canonico con [code] del catalogo. */
internal suspend fun ApplicationCall.respondErrorEnvelope(
    code: CanonicalErrorCode,
    message: String = code.defaultMessage,
    details: ErrorDetails? = null,
    legacyError: String = code.name.lowercase(),
) = respondErrorEnvelope(code.status, code.name, message, details, legacyError)

/**
 * Responde el sobre canonico. Un 401 lleva `WWW-Authenticate: Bearer`; el
 * `requestId` del cuerpo es SIEMPRE el de la cabecera `X-Request-Id`.
 */
internal suspend fun ApplicationCall.respondErrorEnvelope(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: ErrorDetails? = null,
    legacyError: String = code.lowercase(),
) {
    val requestId = ensureRequestId()
    if (status == HttpStatusCode.Unauthorized && response.headers[HttpHeaders.WWWAuthenticate] == null) {
        response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    }
    respond(status, ErrorDto.canonical(code, message, requestId, details, legacyError))
}

private class BackendRestartRequest(
    private val projectRoot: File,
    private val ngrokHost: String? = null,
) {
    fun schedule() {
        thread(name = "oposicontrol-backend-restart", isDaemon = false) {
            Thread.sleep(750)
            restartNgrokIfNeeded()
            startBackend()
            Thread.sleep(250)
            exitProcess(0)
        }
    }

    private fun restartNgrokIfNeeded() {
        val host = ngrokHost ?: return
        val script = File(projectRoot, "scripts/restart-ngrok-dev.ps1")
        if (!script.exists()) {
            System.err.println("[ktor] ngrok restart skipped: ${script.path} not found.")
            return
        }
        val command = if (isWindows()) {
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.absolutePath,
                "-Domain",
                host,
                "-Port",
                "8080",
            )
        } else {
            listOf("pwsh", "-NoProfile", "-File", script.absolutePath, "-Domain", host, "-Port", "8080")
        }
        runCatching {
            ProcessBuilder(command)
                .directory(projectRoot)
                .redirectOutput(File(projectRoot, "ngrok-restart.out.log"))
                .redirectError(File(projectRoot, "ngrok-restart.err.log"))
                .start()
            Thread.sleep(1_500)
        }.onFailure { error ->
            System.err.println("[ktor] ngrok restart failed: ${error.safeBackendMessage("ngrok restart error", 180)}")
        }
    }

    private fun startBackend() {
        val script = File(projectRoot, "scripts/run-backend-dev.ps1")
        val command = if (script.exists() && isWindows()) {
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.absolutePath,
            )
        } else if (script.exists()) {
            listOf("pwsh", "-NoProfile", "-File", script.absolutePath)
        } else if (isWindows()) {
            listOf("cmd", "/c", "gradlew.bat", "--no-daemon", ":backend:ktor:run")
        } else {
            listOf("./gradlew", "--no-daemon", ":backend:ktor:run")
        }
        ProcessBuilder(command)
            .directory(projectRoot)
            .redirectOutput(File(projectRoot, "backend-restart.out.log"))
            .redirectError(File(projectRoot, "backend-restart.err.log"))
            .start()
    }

    companion object {
        fun fromEnvironment(ngrokHost: String? = null): BackendRestartRequest? {
            if (!isEnabled()) return null
            return findProjectRoot(File(System.getProperty("user.dir")))
                ?.let { BackendRestartRequest(projectRoot = it, ngrokHost = ngrokHost) }
        }

        fun isEnabled(): Boolean = backendRestartEnabled(
            appEnv = System.getenv("APP_ENV"),
            explicitFlag = System.getenv("OPOSICONTROL_ENABLE_BACKEND_RESTART"),
        )

        private fun findProjectRoot(start: File): File? {
            var current: File? = start.absoluteFile
            while (current != null) {
                if (File(current, "settings.gradle.kts").exists() && File(current, "gradlew.bat").exists()) {
                    return current
                }
                current = current.parentFile
            }
            return null
        }

        private fun isWindows(): Boolean {
            return System.getProperty("os.name").contains("Windows", ignoreCase = true)
        }
    }
}

private fun ApplicationCall.ngrokHostForRestart(): String? {
    val forwardedHost = request.headers["X-Forwarded-Host"]
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    val host = (forwardedHost ?: request.host())
        .substringBefore(':')
        .trim()
        .lowercase()
    return host.takeIf {
        it.endsWith(".ngrok-free.dev") ||
            it.endsWith(".ngrok.app") ||
            it.endsWith(".ngrok.io")
    }
}

internal data class BackendUploadedFile(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

private suspend inline fun <reified T : Any> ApplicationCall.respondBackendSupabaseResult(
    routeName: String,
    block: () -> T,
) {
    // SLICE 0 (sobre canonico): antes este `try` respondia su propio 500 con el
    // mensaje del upstream dentro (`safeBackendMessage`), saltandose la
    // redaccion de `StatusPages`. Ahora solo registra y RELANZA: el cuerpo lo
    // decide `installBackendStatusPages()` como en cualquier otra ruta.
    try {
        respond(block())
    } catch (cause: Throwable) {
        if (cause is kotlinx.coroutines.CancellationException) throw cause
        System.err.println("[ktor] $routeName failure ${cause::class.simpleName}: ${cause.safeBackendMessage("Supabase backend error")}")
        throw cause
    }
}

// internal (no private): Observability.kt reutiliza esta redaccion
// (Bearer/apikey + truncado) para logs estrategicos en vez de duplicarla.
/** ¿La excepcion se lanzo desde codigo del backend (primer frame en `com.oposicontrol.`)? Sin traza -> `false`. */
internal fun Throwable.isThrownByBackendCode(): Boolean =
    stackTrace.firstOrNull()?.className?.startsWith("com.oposicontrol.") == true

private val BEARER_PATTERN = Regex("Bearer\\s+[A-Za-z0-9._~+/-]+=*")
private val APIKEY_PATTERN = Regex("apikey[=:]\\s*[A-Za-z0-9._~+/-]+=*")
private val JWT_PATTERN = Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*")
// Acotados (MASTER2-OBS-004): un texto largo sin '@' ya no da backtracking cuadratico.
private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,255}\\.[A-Za-z]{2,24}")
// MASTER2-OBS-002: la columna puede ser una expresion con parentesis anidados (`Key (lower(username::text))=(bob)`) y el
// valor acaba en el `)` seguido de espacio, fin o puntuacion.
private val PG_KEY_DETAIL_PATTERN = Regex("Key \\((.{1,300}?)\\)=\\((.{0,500}?)\\)(?=\\s|$|[.,;])")
private val PG_FAILING_ROW_PATTERN = Regex("Failing row contains \\([^\\n]{0,2000}\\)")
private val SB_KEY_PATTERN = Regex("sb_(?:secret|publishable)_[A-Za-z0-9_-]{8,}")
private val TOKEN_FIELD_PATTERN = Regex("(?i)(refresh_token|access_token|id_token)([\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9._~+/-]{8,}")

/**
 * MASTER-OBS-004: depuracion de texto que puede llegar a un log o a Sentry: credenciales (Bearer, apikey, JWT) y PII que
 * PostgREST refleja en sus errores (emails y los detalles `Key (columna)=(valor)` de las violaciones de unicidad).
 */
internal fun String.redactSensitiveText(): String = this
    .replace(BEARER_PATTERN, "Bearer [redacted]")
    .replace(APIKEY_PATTERN, "apikey=[redacted]")
    .replace(JWT_PATTERN, "[redacted-jwt]")
    .replace(PG_KEY_DETAIL_PATTERN) { "Key (${it.groupValues[1]})=([redacted])" }
    .replace(PG_FAILING_ROW_PATTERN, "Failing row contains ([redacted])")
    .replace(SB_KEY_PATTERN, "[redacted-key]")
    .replace(TOKEN_FIELD_PATTERN) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
    .replace(EMAIL_PATTERN, "[redacted-email]")

internal fun Throwable.safeBackendMessage(defaultMessage: String, maxLength: Int = 300): String {
    return message
        ?.redactSensitiveText()
        ?.take(maxLength)
        ?.ifBlank { null }
        ?: defaultMessage
}

private suspend fun ApplicationCall.receiveSingleFile(
    maxBytes: Int,
    requiredMimePrefix: String?,
    defaultFileName: String,
): BackendUploadedFile {
    var uploaded: BackendUploadedFile? = null
    val multipart = receiveMultipart()
    var part = multipart.readPart()
    while (part != null) {
        try {
            if (part is PartData.FileItem && uploaded == null) {
                val bytes = part.provider().readRemaining(maxBytes.toLong() + 1).readByteArray()
                require(bytes.size <= maxBytes) { "El archivo supera el limite permitido." }
                val mimeType = part.contentType?.toString().orEmpty().ifBlank { "application/octet-stream" }
                if (requiredMimePrefix != null) {
                    // P2-9: el mensaje NO repite el MIME que mandó el cliente. Ese texto
                    // lo controla quien sube el archivo, y un 400 que lo devuelve tal
                    // cual es un eco a un cuerpo que se publica.
                    require(mimeType.startsWith(requiredMimePrefix)) { "Tipo de archivo no permitido." }
                }
                uploaded = BackendUploadedFile(
                    fileName = part.originalFileName?.sanitizeStorageSegment()?.ifBlank { null } ?: defaultFileName,
                    mimeType = mimeType,
                    bytes = bytes,
                )
            }
        } finally {
            part.dispose.invoke()
        }
        part = multipart.readPart()
    }
    return uploaded ?: throw IllegalArgumentException("Falta el campo multipart file.")
}

@Serializable
data class SolucionarioDto(
    val id: String = "",
    val titulo: String,
    val especialidadId: String,
    val descripcion: String = "",
    val precioTickets: Int = 0,
    val estado: String = "draft",
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val orden: Int = 0,
    val portadaUrl: String? = null,
    val portadaStoragePath: String? = null,
    val portadaMimeType: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
)

@Serializable
data class PreguntaDto(
    val id: String = "",
    val solucionarioId: String = "",
    val titulo: String = "",
    val enunciado: String,
    val orden: Int = 0,
    val opciones: List<OpcionDto> = emptyList(),
    val recursos: List<RecursoPreguntaDto> = emptyList(),
)

@Serializable
data class RafagaDto(
    val id: String = "",
    val titulo: String,
    val especialidadId: String,
    val precioTickets: Int = 0,
    val estado: String = "draft",
    val publicarDesde: String? = null,
    val publishedAt: String? = null,
    val questionCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
)

@Serializable
data class RafagaPreguntaDto(
    val id: String = "",
    val rafagaId: String = "",
    val enunciado: String,
    val orden: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
    val deletedAt: String? = null,
)

@Serializable
data class OpcionDto(
    val id: String = "",
    val label: String,
    val contenido: String,
    val esCorrecta: Boolean,
    val orden: Int,
)

@Serializable
data class RecursoPreguntaDto(
    val id: String = "",
    val preguntaId: String = "",
    val tipoRecurso: String,
    val titulo: String = "",
    val url: String = "",
    val storagePath: String? = null,
    val publicUrl: String? = null,
    val mimeType: String? = null,
    val orden: Int = 0,
)

@Serializable
data class OrderDto(val ids: List<String>)

@Serializable
data class EspecialidadDto(
    val id: String = "",
    val nombre: String,
    val slug: String = "",
    val activa: Boolean = true,
    val linkedExams: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class EtiquetaDto(
    val id: String = "",
    val nombre: String,
    val slug: String = "",
    val origen: String = "manual",
)

@Serializable
data class ComunidadDto(
    val id: String = "",
    val nombre: String,
    val codigo: String = "",
    val slug: String = "",
    val activa: Boolean = true,
    val orden: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
)

internal class SupabaseContentGateway private constructor(
    private val supabaseUrl: String,
    private val serviceRoleKey: String,
    private val examResourcesBucket: String,
    private val json: Json,
    private val client: HttpClient = newUpstreamHttpClient(),
) {
    @Volatile
    private var storageBucketChecked = false

    fun listEspecialidades(): List<EspecialidadDto> {
        val rows = request("GET", "/rest/v1/especialidad?select=*&order=nombre.asc", null)
            .decodeList<EspecialidadRow>()
        val linkedCounts = request("GET", "/rest/v1/examen?select=id,especialidad_id", null)
            .decodeList<ExamenCountRow>()
            .groupingBy { it.especialidadId.toString() }
            .eachCount()
        return rows.map { it.toDto(linkedCounts[it.id?.toString().orEmpty()] ?: 0) }
    }

    fun saveEspecialidad(input: EspecialidadDto): EspecialidadDto {
        require(input.nombre.isNotBlank()) { "La especialidad necesita nombre." }
        val saved = request(
            "POST",
            "/rest/v1/especialidad?on_conflict=id",
            json.encodeToString(listOf(input.toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<EspecialidadRow>().first()
        return saved.toDto(linkedExams = 0)
    }

    fun deleteEspecialidad(especialidadId: String) {
        request("DELETE", "/rest/v1/especialidad?id=eq.${especialidadId.toIntId()}", null)
    }

    fun listEtiquetas(): List<EtiquetaDto> {
        return request("GET", "/rest/v1/etiquetas?select=*&order=nombre.asc", null)
            .decodeList<EtiquetaRow>()
            .map { it.toDto() }
    }

    fun saveEtiqueta(input: EtiquetaDto): EtiquetaDto {
        require(input.nombre.isNotBlank()) { "La etiqueta necesita nombre." }
        val saved = request(
            "POST",
            "/rest/v1/etiquetas?on_conflict=id",
            json.encodeToString(listOf(input.toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<EtiquetaRow>().first()
        return saved.toDto()
    }

    fun deleteEtiqueta(etiquetaId: String) {
        request("DELETE", "/rest/v1/etiquetas?id=eq.$etiquetaId", null)
    }

    fun listComunidades(): List<ComunidadDto> {
        return request("GET", "/rest/v1/comunidades_autonomas?select=*&order=posicion.asc,nombre.asc", null)
            .decodeList<ComunidadRow>()
            .map { it.toDto() }
    }

    fun saveComunidad(input: ComunidadDto): ComunidadDto {
        require(input.nombre.isNotBlank()) { "La comunidad necesita nombre." }
        val saved = request(
            "POST",
            "/rest/v1/comunidades_autonomas?on_conflict=id",
            json.encodeToString(listOf(input.toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<ComunidadRow>().first()
        return saved.toDto()
    }

    fun deleteComunidad(comunidadId: String) {
        request("DELETE", "/rest/v1/comunidades_autonomas?id=eq.$comunidadId", null)
    }

    fun listSolucionarios(): List<SolucionarioDto> {
        val specialties = listEspecialidades().associateBy { it.id }
        val counts = request("GET", "/rest/v1/pregunta?select=id,examen_id", null)
            .decodeList<PreguntaCountRow>()
            .groupingBy { it.examenId.toString() }
            .eachCount()
        return request("GET", "/rest/v1/examen?select=*&order=id.desc", null)
            .decodeList<ExamenRow>()
            .map { it.toDto(specialties[it.especialidadId.toString()]?.nombre.orEmpty(), counts[it.id?.toString().orEmpty()] ?: 0) }
    }

    fun listRafagas(): List<RafagaDto> {
        val counts = request("GET", "/rest/v1/rafaga_pregunta?select=id,rafaga_id", null)
            .decodeList<RafagaPreguntaCountRow>()
            .groupingBy { it.rafagaId.toString() }
            .eachCount()
        return request("GET", "/rest/v1/rafaga?select=*&order=id.desc", null)
            .decodeList<RafagaRow>()
            .map { it.toDto(counts[it.id?.toString().orEmpty()] ?: 0) }
    }

    fun saveSolucionario(input: SolucionarioDto): SolucionarioDto {
        require(input.titulo.isNotBlank()) { "El solucionario necesita un nombre." }
        require(input.especialidadId.isNotBlank()) { "El solucionario necesita una especialidad." }
        require(input.estado in setOf("draft", "published", "archived")) { "Estado de solucionario no valido." }
        if (input.estado == "published") {
            require(!input.portadaUrl.isNullOrBlank() || !input.portadaStoragePath.isNullOrBlank()) {
                "Anade una portada antes de publicar el examen."
            }
        }
        require(input.precioTickets >= 0) { "El precio en tickets no puede ser negativo." }
        val saved = request(
            "POST",
            "/rest/v1/examen?on_conflict=id",
            json.encodeToString(listOf(input.normalizedForSave().toRow())),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<ExamenRow>().first()
        return saved.toDto("", 0)
    }

    fun saveRafaga(input: RafagaDto): RafagaDto {
        require(input.titulo.isNotBlank()) { "La rafaga necesita un nombre." }
        require(input.especialidadId.isNotBlank()) { "La rafaga necesita una especialidad." }
        require(input.estado in setOf("draft", "published", "archived")) { "Estado de rafaga no valido." }
        require(input.precioTickets >= 0) { "El precio en tickets no puede ser negativo." }
        val normalized = input.normalizedForSave()
        val saved = if (normalized.id.isBlank()) {
            request(
                "POST",
                "/rest/v1/rafaga",
                json.encodeToString(listOf(normalized.toRow())),
                prefer = "return=representation",
            ).decodeList<RafagaRow>().first()
        } else {
            request(
                "PATCH",
                "/rest/v1/rafaga?id=eq.${normalized.id.toIntId()}",
                json.encodeToString(normalized.toRow()),
                prefer = "return=representation",
            ).decodeList<RafagaRow>().first()
        }
        return saved.toDto(questionCount = 0)
    }

    /**
     * Portada legacy: RETIRADA (M6).
     *
     * Estas dos funciones escribían `portada_url` en `public.examen`, y esa columna ya
     * no existe: M6 dejó la portada en `portada_storage_path` + `portada_mime_type`,
     * con un CHECK que además exige la ruta `exam-covers/<examen_id>/<uuid>.<ext>` —que
     * esto tampoco componía—. Tal cual estaban, la subida **primero escribía el objeto
     * en Storage** y después fallaba el PATCH por columna inexistente: un 500 y un
     * fichero huérfano pagando almacenamiento, en cada intento.
     *
     * No se «arregla» apuntándolas a las columnas nuevas porque no hay a quién servir:
     * la portada de escritorio va por `POST /api/v1/admin/examenes/{id}/portada`
     * (K5, tabla `examenes`) y estas rutas de `/content` no tienen consumidor. Un error
     * de dominio constante dice exactamente eso, y no toca ni Storage ni la base.
     */
    fun uploadPortada(solucionarioId: String, file: BackendUploadedFile): SolucionarioDto =
        throw legacyCoverRetired()

    fun clearPortada(solucionarioId: String): SolucionarioDto = throw legacyCoverRetired()

    private fun legacyCoverRetired() = BackendDomainException(
        "LEGACY_EXAM_COVER_RETIRED",
        HttpStatusCode.Gone,
        "La portada de este catálogo antiguo ya no se gestiona por aquí. Usa la pantalla de Exámenes.",
    )

    fun deleteSolucionario(solucionarioId: String) {
        val examId = solucionarioId.toIntId()
        val activeExam = request(
            "GET",
            "/rest/v1/examen?select=portada_storage_path&id=eq.$examId&limit=1",
            null,
        ).decodeList<ExamenCoverPathRow>().firstOrNull()
            ?: throw IllegalArgumentException("El solucionario no existe en la base de datos.")
        val coverPath = activeExam.portadaStoragePath
        val questions = request(
            "GET",
            "/rest/v1/pregunta?select=id&examen_id=eq.$examId",
            null,
        ).decodeList<PreguntaIdRow>()
        val questionIds = questions.mapNotNull { it.id }
        val resourcePaths = if (questionIds.isEmpty()) {
            emptyList()
        } else {
            val idsFilter = questionIds.joinToString(",")
            request(
                "GET",
                "/rest/v1/recurso_pregunta?select=storage_path&pregunta_id=in.($idsFilter)",
                null,
            ).decodeList<RecursoStoragePathRow>().mapNotNull { it.storagePath?.takeIf(String::isNotBlank) }
        }
        if (questionIds.isNotEmpty()) {
            val idsFilter = questionIds.joinToString(",")
            request(
                "DELETE",
                "/rest/v1/recurso_pregunta?pregunta_id=in.($idsFilter)",
                null,
            )
            requestIfRelationExists(
                "DELETE",
                "/rest/v1/opcion_pregunta?pregunta_id=in.($idsFilter)",
                null,
            )
            request(
                "DELETE",
                "/rest/v1/pregunta?id=in.($idsFilter)",
                null,
            )
        }
        request("DELETE", "/rest/v1/examen?id=eq.$examId", null)
        requestIfRelationExists(
            "DELETE",
            "/rest/v1/acceso_examen_usuario?examen_id=eq.$examId",
            null,
        )
        (listOfNotNull(coverPath) + resourcePaths).distinct().forEach(::storageDelete)
    }

    fun deleteRafaga(rafagaId: String) {
        val numericRafagaId = rafagaId.toIntId()
        val activeRafaga = request(
            "GET",
            "/rest/v1/rafaga?select=id&id=eq.$numericRafagaId&limit=1",
            null,
        ).decodeList<IdRow>().firstOrNull()
            ?: throw IllegalArgumentException("La rafaga no existe en la base de datos.")
        request(
            "DELETE",
            "/rest/v1/rafaga_pregunta?rafaga_id=eq.$numericRafagaId",
            null,
        )
        request("DELETE", "/rest/v1/rafaga?id=eq.$numericRafagaId", null)
    }

    fun listPreguntas(solucionarioId: String): List<PreguntaDto> {
        val questions = request(
            "GET",
            "/rest/v1/pregunta?select=*&examen_id=eq.${solucionarioId.toIntId()}&order=orden.asc",
            null,
        ).decodeList<PreguntaRow>()
        if (questions.isEmpty()) return emptyList()
        val ids = questions.mapNotNull { it.id }.joinToString(",")
        val resources = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=*&pregunta_id=in.($ids)&order=id.asc",
            null,
        ).decodeList<RecursoPreguntaRow>().groupBy { it.preguntaId }
        return questions.map { row -> row.toDto(resources[row.id].orEmpty().map { it.toDto() }) }
    }

    fun listRafagaPreguntas(rafagaId: String): List<RafagaPreguntaDto> {
        return request(
            "GET",
            "/rest/v1/rafaga_pregunta?select=*&rafaga_id=eq.${rafagaId.toIntId()}&order=orden.asc",
            null,
        ).decodeList<RafagaPreguntaRow>().map { it.toDto() }
    }

    fun savePregunta(solucionarioId: String, input: PreguntaDto): PreguntaDto {
        validatePregunta(input)
        val saved = request(
            "POST",
            "/rest/v1/pregunta?on_conflict=id",
            json.encodeToString(listOf(input.toRow(solucionarioId, json))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<PreguntaRow>().first()
        val savedId = saved.id ?: error("Supabase no devolvio id de pregunta.")
        replaceResources(savedId, input.recursos)
        return saved.toDto(input.recursos.map { it.copy(preguntaId = savedId.toString()) })
    }

    fun saveRafagaPregunta(rafagaId: String, input: RafagaPreguntaDto): RafagaPreguntaDto {
        require(input.enunciado.isNotBlank()) { "La pregunta de rafaga necesita enunciado." }
        val saved = request(
            "POST",
            "/rest/v1/rafaga_pregunta?on_conflict=id",
            json.encodeToString(listOf(input.toRow(rafagaId))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<RafagaPreguntaRow>().first()
        return saved.toDto()
    }

    fun deletePregunta(solucionarioId: String, preguntaId: String) {
        val resourcePaths = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=storage_path&pregunta_id=eq.${preguntaId.toIntId()}",
            null,
        ).decodeList<RecursoStoragePathRow>().mapNotNull { it.storagePath?.takeIf(String::isNotBlank) }
        request("DELETE", "/rest/v1/recurso_pregunta?pregunta_id=eq.${preguntaId.toIntId()}", null)
        requestIfRelationExists("DELETE", "/rest/v1/opcion_pregunta?pregunta_id=eq.${preguntaId.toIntId()}", null)
        request("DELETE", "/rest/v1/pregunta?id=eq.${preguntaId.toIntId()}&examen_id=eq.${solucionarioId.toIntId()}", null)
        resourcePaths.distinct().forEach(::storageDelete)
    }

    fun deleteRafagaPregunta(rafagaId: String, preguntaId: String) {
        request("DELETE", "/rest/v1/rafaga_pregunta?id=eq.${preguntaId.toIntId()}&rafaga_id=eq.${rafagaId.toIntId()}", null)
    }

    fun reorderPreguntas(solucionarioId: String, order: OrderDto): List<PreguntaDto> {
        val current = listPreguntas(solucionarioId)
        require(order.ids.toSet() == current.map { it.id }.toSet()) { "El orden debe contener las mismas preguntas." }
        val byId = current.associateBy { it.id }
        val reordered = order.ids.mapIndexed { index, id -> byId.getValue(id).copy(orden = index) }
        val rows = reordered.map { it.toRow(solucionarioId, json) }
        request(
            "POST",
            "/rest/v1/pregunta?on_conflict=id",
            json.encodeToString(rows),
            prefer = "resolution=merge-duplicates,return=minimal",
        )
        return reordered
    }

    fun reorderRafagaPreguntas(rafagaId: String, order: OrderDto): List<RafagaPreguntaDto> {
        val current = listRafagaPreguntas(rafagaId)
        require(order.ids.toSet() == current.map { it.id }.toSet()) { "El orden debe contener las mismas preguntas." }
        val byId = current.associateBy { it.id }
        val reordered = order.ids.mapIndexed { index, id -> byId.getValue(id).copy(orden = index) }
        request(
            "POST",
            "/rest/v1/rafaga_pregunta?on_conflict=id",
            json.encodeToString(reordered.map { it.toRow(rafagaId) }),
            prefer = "resolution=merge-duplicates,return=minimal",
        )
        return reordered
    }

    fun saveRecurso(preguntaId: String, input: RecursoPreguntaDto): RecursoPreguntaDto {
        validateRecurso(input)
        val saved = request(
            "POST",
            "/rest/v1/recurso_pregunta?on_conflict=id",
            json.encodeToString(listOf(input.toRow(preguntaId))),
            prefer = "resolution=merge-duplicates,return=representation",
        ).decodeList<RecursoPreguntaRow>().first()
        return saved.toDto()
    }

    fun uploadRecurso(
        preguntaId: String,
        resourceId: String,
        tipoRecurso: String,
        titulo: String,
        orden: Int,
        file: BackendUploadedFile,
    ): RecursoPreguntaDto {
        val databaseType = tipoRecurso.toDatabaseResourceType()
        validateResourceMime(databaseType, file.mimeType)
        val objectPath = "questions/${preguntaId.toIntId()}/${resourceId.sanitizeStorageSegment()}-${file.fileName}"
        storageUpload(objectPath, file.mimeType, file.bytes)
        val publicUrl = storagePublicUrl(objectPath)
        return saveRecurso(
            preguntaId = preguntaId,
            input = RecursoPreguntaDto(
                id = resourceId,
                preguntaId = preguntaId,
                tipoRecurso = databaseType,
                titulo = titulo.ifBlank { file.fileName },
                url = publicUrl,
                storagePath = objectPath,
                publicUrl = publicUrl,
                mimeType = file.mimeType,
                orden = orden,
            ),
        )
    }

    fun deleteRecurso(preguntaId: String, resourceId: String) {
        val resource = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=*&id=eq.${resourceId.toIntId()}&pregunta_id=eq.${preguntaId.toIntId()}&limit=1",
            null,
        ).decodeList<RecursoPreguntaRow>().firstOrNull()
            ?: throw IllegalArgumentException("El recurso no existe.")
        request(
            "DELETE",
            "/rest/v1/recurso_pregunta?id=eq.${resourceId.toIntId()}&pregunta_id=eq.${preguntaId.toIntId()}",
            null,
        )
        resource.storagePath?.takeIf { it.isNotBlank() }?.let(::storageDelete)
    }

    fun reorderRecursos(preguntaId: String, order: OrderDto): List<RecursoPreguntaDto> {
        val current = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=*&pregunta_id=eq.${preguntaId.toIntId()}&order=id.asc",
            null,
        ).decodeList<RecursoPreguntaRow>().map { it.toDto() }
        require(order.ids.toSet() == current.map { it.id }.toSet()) { "El orden debe contener los mismos recursos." }
        val byId = current.associateBy { it.id }
        val reordered = order.ids.mapIndexed { index, id -> byId.getValue(id).copy(orden = index) }
        request(
            "POST",
            "/rest/v1/recurso_pregunta?on_conflict=id",
            json.encodeToString(reordered.map { it.toRow(preguntaId) }),
            prefer = "resolution=merge-duplicates,return=minimal",
        )
        return reordered
    }

    private fun replaceResources(questionId: Int, resources: List<RecursoPreguntaDto>) {
        val previousStoragePaths = request(
            "GET",
            "/rest/v1/recurso_pregunta?select=storage_path&pregunta_id=eq.$questionId",
            null,
        ).decodeList<RecursoStoragePathRow>().mapNotNull { it.storagePath?.takeIf(String::isNotBlank) }
        val nextStoragePaths = resources.mapNotNull { it.storagePath?.takeIf(String::isNotBlank) }.toSet()
        request("DELETE", "/rest/v1/recurso_pregunta?pregunta_id=eq.$questionId", null)
        if (resources.isNotEmpty()) {
            request(
                "POST",
                "/rest/v1/recurso_pregunta",
                json.encodeToString(resources.map { it.toRow(questionId.toString()) }),
                prefer = "return=minimal",
            )
        }
        previousStoragePaths.filterNot { it in nextStoragePaths }.distinct().forEach(::storageDelete)
    }

    private fun validatePregunta(pregunta: PreguntaDto) {
        require(pregunta.enunciado.isNotBlank()) { "La pregunta necesita enunciado." }
        if (pregunta.opciones.isNotEmpty()) {
            require(pregunta.opciones.size == 4) { "Una pregunta tipo test debe tener exactamente 4 opciones." }
            require(pregunta.opciones.count { it.esCorrecta } == 1) { "Una pregunta tipo test debe tener exactamente una opcion correcta." }
        }
    }

    private fun validateRecurso(recurso: RecursoPreguntaDto) {
        require(recurso.tipoRecurso in setOf("imagen", "audio", "video", "documento", "texto")) { "Tipo de recurso no valido." }
        require(recurso.url.isNotBlank() || !recurso.storagePath.isNullOrBlank() || !recurso.publicUrl.isNullOrBlank()) {
            "El recurso necesita una URL o ruta de archivo."
        }
    }

    private fun validateResourceMime(tipoRecurso: String, mimeType: String) {
        val valid = when (tipoRecurso) {
            "imagen" -> mimeType.startsWith("image/")
            "audio" -> mimeType.startsWith("audio/")
            "video" -> mimeType.startsWith("video/")
            "texto" -> mimeType.startsWith("text/")
            "documento" -> mimeType.startsWith("application/") || mimeType.startsWith("text/")
            else -> false
        }
        require(valid) { "Tipo MIME $mimeType no permitido para $tipoRecurso." }
    }

    private fun storageUpload(objectPath: String, mimeType: String, bytes: ByteArray) {
        ensureStorageBucketExists()
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        val path = "/storage/v1/object/$examResourcesBucket/$encodedPath"
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
            .header("Content-Type", mimeType)
            .header("x-upsert", "true")
        val response = try {
            client.send(
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Supabase Storage network error on upload: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Supabase Storage upload -> ${response.statusCode()}: ${response.body().take(300)}")
        }
    }

    private fun storageDelete(objectPath: String) {
        ensureStorageBucketExists()
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        val path = "/storage/v1/object/$examResourcesBucket/$encodedPath"
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
        val response = try {
            client.send(builder.DELETE().build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException("Supabase Storage network error on object delete: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            throw IllegalStateException("Supabase Storage object delete -> ${response.statusCode()}: ${response.body().take(300)}")
        }
    }

    /**
     * Crea el bucket de recursos si no existe. **PRIVADO**, y eso no es un detalle.
     *
     * Este es el SEGUNDO camino que puede crear `exam-resources`: el otro vive en
     * `SupabaseRequestTransport.storageEnsureBucket`, y allí ya se corrigió. Si
     * sólo se arregla uno, en un entorno nuevo gana el que llegue primero y el
     * material de las preguntas inmersivas —audio, vídeo, imágenes— puede nacer
     * servido públicamente a cualquiera con la URL. Hoy no explota porque el
     * bucket ya existe en el entorno vivo y Storage responde 409 sin tocar nada;
     * la bomba es de despliegue, no de ejecución.
     *
     * Ojo a la consecuencia que esto NO arregla: `storagePublicUrl` compone
     * `/object/public/…`, que sobre un bucket privado da 404. La portada de
     * examen sigue dependiendo de que su bucket sea público. Servir recursos
     * privados necesita URL firmada, que aún no existe (EXAM-008).
     */
    private fun ensureStorageBucketExists() {
        if (storageBucketChecked) return
        val bucketId = examResourcesBucket.toStorageBucketId()
        val body = """{"id":"$bucketId","name":"$bucketId","public":false}"""
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl/storage/v1/bucket"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
            .header("Content-Type", "application/json")
        val response = try {
            client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw IllegalStateException("Supabase Storage bucket check network error: ${error::class.simpleName}: ${error.message}", error)
        }
        if (response.statusCode() in 200..299 || response.statusCode() == 409) {
            storageBucketChecked = true
            return
        }
        throw IllegalStateException("Supabase Storage bucket check -> ${response.statusCode()}: ${response.body().take(300)}")
    }

    private fun storagePublicUrl(objectPath: String): String {
        val encodedPath = objectPath.split('/').joinToString("/") { it.urlEncode() }
        return "$supabaseUrl/storage/v1/object/public/$examResourcesBucket/$encodedPath"
    }

    private fun request(method: String, path: String, body: String?, prefer: String? = null): String {
        val builder = HttpRequest.newBuilder(URI.create("$supabaseUrl$path"))
            .timeout(UPSTREAM_REQUEST_TIMEOUT)
            .supabaseElevatedAuth(serviceRoleKey)
            .header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val response = try {
            client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw IllegalStateException("Supabase network error on $method ${path.substringBefore('?')}: ${e::class.simpleName}: ${e.message}", e)
        }
        if (response.statusCode() !in 200..299) {
            val preview = response.body().take(300)
            throw IllegalStateException("Supabase $method ${path.substringBefore('?')} -> ${response.statusCode()}: $preview")
        }
        return response.body()
    }

    private fun requestIfRelationExists(method: String, path: String, body: String?, prefer: String? = null) {
        try {
            request(method, path, body, prefer)
        } catch (error: IllegalStateException) {
            val message = error.message.orEmpty()
            val missingRelation = listOf("PGRST205", "42P01", "42703", "Could not find the table", "relation", "column").any {
                message.contains(it, ignoreCase = true)
            }
            if (!missingRelation) throw error
            System.err.println("[ktor] optional Supabase relation skipped on ${path.substringBefore('?')}: ${message.take(160)}")
        }
    }

    private inline fun <reified T> String.decodeList(): List<T> = json.decodeFromString(this)

    companion object {
        /**
         * F-01/F-02: la clave la resuelve [SupabaseRequestAuthPolicy.secretFromEnvironment]
         * (`SUPABASE_SECRET_KEY`, con la legacy como fallback) y las cabeceras las
         * pone [supabaseElevatedAuth]: `Bearer` sólo si la clave tiene forma de JWT.
         * Leía sólo `SUPABASE_SERVICE_ROLE_KEY` y firmaba `Bearer` sin condición, así
         * que con la configuración canónica el backend entero no arrancaba.
         */
        fun fromEnvironment(json: Json, environment: (String) -> String? = System::getenv): SupabaseContentGateway? {
            val url = environment("SUPABASE_URL")?.trimEnd('/').orEmpty()
            val serviceRoleKey = SupabaseRequestAuthPolicy.secretFromEnvironment(environment)
            val bucket = environment("SUPABASE_BUCKET_EXAM_RESOURCES").orEmpty().ifBlank { "exam-resources" }
            return if (url.isBlank() || serviceRoleKey.isBlank()) null else SupabaseContentGateway(url, serviceRoleKey, bucket, json)
        }
    }
}

@Serializable
private data class EspecialidadRow(
    val id: Int? = null,
    val nombre: String,
    val slug: String = "",
    val activa: Boolean = true,
)

@Serializable
private data class ExamenRow(
    val id: Int? = null,
    val titulo: String,
    @kotlinx.serialization.SerialName("precio_tickets") val precioTickets: Int = 0,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Int,
    val estado: String = "draft",
    @kotlinx.serialization.SerialName("publicar_desde") val publicarDesde: String? = null,
    @kotlinx.serialization.SerialName("published_at") val publishedAt: String? = null,
    @kotlinx.serialization.SerialName("portada_url") val portadaUrl: String? = null,
    @kotlinx.serialization.SerialName("portada_storage_path") val portadaStoragePath: String? = null,
    @kotlinx.serialization.SerialName("portada_mime_type") val portadaMimeType: String? = null,
)

@Serializable
private data class ExamenCountRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Int,
)

@Serializable
private data class ExamenCoverPathRow(
    @kotlinx.serialization.SerialName("portada_storage_path") val portadaStoragePath: String? = null,
)

@Serializable
private data class PreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("examen_id") val examenId: Int,
    val enunciado: String,
    val orden: Int = 0,
)

@Serializable
private data class PreguntaIdRow(
    val id: Int? = null,
)

@Serializable
private data class IdRow(
    val id: Int? = null,
)

@Serializable
private data class PreguntaCountRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("examen_id") val examenId: Int,
)

@Serializable
private data class RafagaRow(
    val id: Int? = null,
    val titulo: String,
    @kotlinx.serialization.SerialName("precio_tickets") val precioTickets: Int = 0,
    @kotlinx.serialization.SerialName("especialidad_id") val especialidadId: Int,
    val estado: String = "draft",
)

@Serializable
private data class RafagaPreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("rafaga_id") val rafagaId: Int,
    val enunciado: String,
    val orden: Int = 0,
)

@Serializable
private data class RafagaPreguntaCountRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("rafaga_id") val rafagaId: Int,
)

@Serializable
private data class RecursoPreguntaRow(
    val id: Int? = null,
    @kotlinx.serialization.SerialName("pregunta_id") val preguntaId: Int,
    @kotlinx.serialization.SerialName("tipo_recurso") val tipoRecurso: String,
    val url: String,
    val titulo: String? = null,
    @kotlinx.serialization.SerialName("storage_path") val storagePath: String? = null,
    @kotlinx.serialization.SerialName("public_url") val publicUrl: String? = null,
    @kotlinx.serialization.SerialName("mime_type") val mimeType: String? = null,
    val orden: Int = 0,
)

@Serializable
private data class RecursoStoragePathRow(
    @kotlinx.serialization.SerialName("storage_path") val storagePath: String? = null,
)

@Serializable
private data class StoredQuestion(
    val titulo: String = "",
    val enunciado: String = "",
    val opciones: List<OpcionDto> = emptyList(),
)

private fun EspecialidadRow.toDto(linkedExams: Int) = EspecialidadDto(
    id = id?.toString().orEmpty(),
    nombre = nombre,
    slug = slug,
    activa = activa,
    linkedExams = linkedExams,
    createdAt = "",
    updatedAt = "",
)

private fun EspecialidadDto.toRow() = EspecialidadRow(
    id = id.toIntOrNull(),
    nombre = nombre,
    slug = slug.ifBlank { nombre.toSlug() },
    activa = activa,
)

// Tabla real `etiquetas`: id uuid, nombre, slug, descripcion, color_hex, activa, creado_en, actualizado_en.
@Serializable
private data class EtiquetaRow(
    val id: String? = null,
    val nombre: String,
    val slug: String = "",
)

private fun EtiquetaRow.toDto() = EtiquetaDto(
    id = id.orEmpty(),
    nombre = nombre,
    slug = slug,
    origen = "manual",
)

private fun EtiquetaDto.toRow() = EtiquetaRow(
    id = id.ifBlank { java.util.UUID.randomUUID().toString() },
    nombre = nombre,
    slug = slug.ifBlank { nombre.toSlug() },
)

// Tabla real `comunidades_autonomas`: id uuid, codigo, nombre, slug, activa, posicion, creado_en, actualizado_en.
@Serializable
private data class ComunidadRow(
    val id: String? = null,
    val codigo: String = "",
    val nombre: String,
    val slug: String = "",
    val activa: Boolean = true,
    val posicion: Int = 0,
)

private fun ComunidadRow.toDto() = ComunidadDto(
    id = id.orEmpty(),
    nombre = nombre,
    codigo = codigo,
    slug = slug,
    activa = activa,
    orden = posicion,
)

private fun ComunidadDto.toRow() = ComunidadRow(
    id = id.ifBlank { java.util.UUID.randomUUID().toString() },
    codigo = codigo,
    nombre = nombre,
    slug = slug.ifBlank { nombre.toSlug() },
    activa = activa,
    posicion = orden,
)

private fun ExamenRow.toDto(specialtyName: String, questionCount: Int) = SolucionarioDto(
    id = id?.toString().orEmpty(),
    titulo = titulo,
    especialidadId = especialidadId.toString(),
    descripcion = specialtyName,
    precioTickets = precioTickets,
    estado = estado,
    publicarDesde = publicarDesde,
    publishedAt = publishedAt,
    orden = id ?: 0,
    portadaUrl = portadaUrl,
    portadaStoragePath = portadaStoragePath,
    portadaMimeType = portadaMimeType,
    createdAt = "",
    updatedAt = "",
    deletedAt = null,
)

private fun SolucionarioDto.toRow() = ExamenRow(
    id = id.toIntOrNull(),
    titulo = titulo,
    precioTickets = precioTickets,
    especialidadId = especialidadId.toIntOrNull() ?: 1,
    estado = estado,
    publicarDesde = publicarDesde,
    publishedAt = publishedAt,
    portadaUrl = portadaUrl,
    portadaStoragePath = portadaStoragePath,
    portadaMimeType = portadaMimeType,
)

private fun RafagaRow.toDto(questionCount: Int) = RafagaDto(
    id = id?.toString().orEmpty(),
    titulo = titulo,
    especialidadId = especialidadId.toString(),
    precioTickets = precioTickets,
    estado = estado,
    publicarDesde = null,
    publishedAt = null,
    questionCount = questionCount,
    createdAt = "",
    updatedAt = "",
    deletedAt = null,
)

private fun RafagaDto.toRow() = RafagaRow(
    id = id.toIntOrNull(),
    titulo = titulo,
    precioTickets = precioTickets,
    especialidadId = especialidadId.toIntOrNull() ?: 1,
    estado = estado,
)

private fun RafagaDto.normalizedForSave(): RafagaDto {
    val now = java.time.Instant.now().toString()
    return copy(
        publicarDesde = publicarDesde?.takeIf { it.isNotBlank() },
        publishedAt = when {
            estado == "published" && publishedAt.isNullOrBlank() -> now
            estado == "published" -> publishedAt
            else -> publishedAt?.takeIf { it.isNotBlank() }
        },
    )
}

private fun SolucionarioDto.normalizedForSave(): SolucionarioDto {
    val now = java.time.Instant.now().toString()
    return copy(
        publicarDesde = publicarDesde?.takeIf { it.isNotBlank() },
        publishedAt = when {
            estado == "published" && publishedAt.isNullOrBlank() -> now
            estado == "published" -> publishedAt
            else -> publishedAt?.takeIf { it.isNotBlank() }
        },
    )
}

private fun PreguntaRow.toDto(resources: List<RecursoPreguntaDto>): PreguntaDto {
    val stored = enunciado.decodeStoredQuestion()
    return PreguntaDto(
        id = id?.toString().orEmpty(),
        solucionarioId = examenId.toString(),
        titulo = stored.titulo.ifBlank { "Pregunta ${orden + 1}" },
        enunciado = stored.enunciado,
        orden = orden,
        opciones = stored.opciones,
        recursos = resources,
    )
}

private fun PreguntaDto.toRow(examId: String, json: Json) = PreguntaRow(
    id = id.toIntOrNull(),
    examenId = examId.toIntOrNull() ?: solucionarioId.toIntOrNull() ?: 1,
    enunciado = STORED_QUESTION_PREFIX + json.encodeToString(StoredQuestion(titulo = titulo, enunciado = enunciado, opciones = opciones)),
    orden = orden,
)

private fun RafagaPreguntaRow.toDto() = RafagaPreguntaDto(
    id = id?.toString().orEmpty(),
    rafagaId = rafagaId.toString(),
    enunciado = enunciado,
    orden = orden,
    createdAt = "",
    updatedAt = "",
    deletedAt = null,
)

private fun RafagaPreguntaDto.toRow(parentRafagaId: String) = RafagaPreguntaRow(
    id = id.toIntOrNull(),
    rafagaId = parentRafagaId.toIntOrNull() ?: rafagaId.toIntOrNull() ?: 1,
    enunciado = enunciado,
    orden = orden,
)

private fun String.decodeStoredQuestion(): StoredQuestion {
    if (!startsWith(STORED_QUESTION_PREFIX)) return StoredQuestion(enunciado = this)
    return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<StoredQuestion>(removePrefix(STORED_QUESTION_PREFIX)) }
        .getOrElse { StoredQuestion(enunciado = this) }
}

private fun RecursoPreguntaRow.toDto() = RecursoPreguntaDto(
    id = id?.toString().orEmpty(),
    preguntaId = preguntaId.toString(),
    tipoRecurso = tipoRecurso,
    titulo = titulo ?: publicUrl?.substringAfterLast('/') ?: storagePath?.substringAfterLast('/') ?: url.substringAfterLast('/').ifBlank { "Recurso" },
    url = url,
    storagePath = storagePath ?: url.takeUnless { it.startsWith("http://") || it.startsWith("https://") },
    publicUrl = publicUrl ?: url.takeIf { it.startsWith("http://") || it.startsWith("https://") },
    mimeType = mimeType,
    orden = orden,
)

private fun RecursoPreguntaDto.toRow(questionId: String) = RecursoPreguntaRow(
    id = id.toIntOrNull(),
    preguntaId = questionId.toIntOrNull() ?: preguntaId.toIntOrNull() ?: 1,
    tipoRecurso = tipoRecurso.toDatabaseResourceType(),
    url = publicUrl ?: storagePath ?: url,
    titulo = titulo,
    storagePath = storagePath,
    publicUrl = publicUrl,
    mimeType = mimeType,
    orden = orden,
)

private fun String.toDatabaseResourceType(): String {
    return when (lowercase()) {
        "image" -> "imagen"
        "document" -> "documento"
        "text" -> "texto"
        else -> lowercase()
    }
}

private fun String.toSlug(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "item" }

private fun String.toIntId(): Int = toIntOrNull() ?: throw IllegalArgumentException("Id no valido.")

private fun String.toStorageBucketId(): String {
    require(matches(Regex("[A-Za-z0-9._-]+"))) { "Bucket de storage no valido." }
    return this
}

// `isMissingCoverColumnError` se retiró con la portada legacy: existía para convertir
// «falta la columna portada_url» en un mensaje que pedía ejecutar un SQL, y esa columna
// ya no va a volver (M6). Un detector de esquema ausente sólo tiene sentido mientras
// alguien espera que el esquema aparezca.

private fun String.sanitizeStorageSegment(): String {
    return lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "file" }
}

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

/**
 * Rutas de `/content/noticias`.
 *
 * Extraídas de `Application.module()` tal cual, sin cambiar una línea de su
 * cuerpo: estaban inline dentro de un `routing { }` de trescientas líneas y por
 * eso NO se podían montar en un test. Un CRUD que solo se puede probar
 * arrancando la aplicación entera contra Supabase no se prueba nunca.
 *
 * El `authenticate("gateway-auth")` se queda FUERA, en quien llama, para que el
 * test pueda instalar su propio proveedor sin que la ruta lo sepa. La ruta no
 * decide quién entra; eso es de la aplicación.
 */
internal fun Route.newsContentRoutes(
    newsGateway: NewsGateway,
    onIndexed: (NoticiaDto) -> Unit,
    onRemoved: (String) -> Unit,
    // Servicio COMPARTIDO con `/api/v1/admin/noticias*`: las reglas (estado, contenido, auditoria, indexado) viven ahi; estas
    // rutas solo traducen el cable legacy (NoticiaDto) y devuelven la misma forma de siempre.
    news: NewsService = NewsService(newsGateway, onIndexed = onIndexed, onRemoved = onRemoved),
) {
    route("/noticias") {
        get {
            call.respond(
                news.listLegacy(
                    limit = sanitizeNewsLimit(call.request.queryParameters["limit"]),
                    offset = sanitizeNewsOffset(call.request.queryParameters["offset"]),
                ),
            )
        }.legacyRoute(LegacyEndpoints.EP_111)
        // Releer una noticia sin arrastrar la biblioteca entera.
        get("/{noticiaId}") {
            val noticia = news.findLegacy(call.requiredPath("noticiaId"))
                ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    ErrorDto(error = "noticia_no_encontrada", message = "La noticia no existe."),
                )
            call.respond(noticia)
        }
        post("/assets") {
            call.respondBackendSupabaseResult("/content/noticias/assets") {
                val file = call.receiveSingleFile(
                    maxBytes = COVER_MAX_BYTES,
                    requiredMimePrefix = "image/",
                    defaultFileName = "news-asset",
                )
                news.uploadAssetLegacy(
                    NewsUploadedFile(
                        fileName = file.fileName,
                        mimeType = file.mimeType,
                        bytes = file.bytes,
                    ),
                )
            }
        }.legacyRoute(LegacyEndpoints.EP_200)
        post {
            val saved = news.saveLegacy(call.receive())
            call.respond(HttpStatusCode.Created, saved)
        }.legacyRoute(LegacyEndpoints.EP_199)
        put {
            val saved = news.saveLegacy(call.receive())
            call.respond(saved)
        }.legacyRoute(LegacyEndpoints.EP_229)
        delete("/{noticiaId}") {
            news.deleteLegacy(call.requiredPath("noticiaId"))
            call.respond(HttpStatusCode.NoContent)
        }.legacyRoute(LegacyEndpoints.EP_015)
    }
}

/** INTERNAL: reinicio de proceso solo para desarrollo (guardado por `gateway-auth` y `OPOSICONTROL_ENABLE_BACKEND_RESTART`). Handlers sin cambios. */
private fun Route.installDevRestartRoutes() {
    post("/admin/backend/restart") {
        val restart = BackendRestartRequest.fromEnvironment()
        if (restart == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorDto(
                    error = "backend_restart_disabled",
                    message = "Reinicio backend desactivado. Define OPOSICONTROL_ENABLE_BACKEND_RESTART=true en desarrollo.",
                ),
            )
        } else {
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "restarting"))
            restart.schedule()
        }
    }
    post("/admin/dev/restart-connection") {
        val restart = BackendRestartRequest.fromEnvironment(ngrokHost = call.ngrokHostForRestart())
        if (restart == null) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorDto(
                    error = "backend_restart_disabled",
                    message = "Reconexion desactivada. Define OPOSICONTROL_ENABLE_BACKEND_RESTART=true en desarrollo.",
                ),
            )
        } else {
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "reconnecting"))
            restart.schedule()
        }
    }
}
