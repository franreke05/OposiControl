package com.oposicontrol.backend

import io.lettuce.core.ClientOptions
import io.lettuce.core.protocol.ProtocolVersion
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SocketOptions
import io.lettuce.core.TimeoutOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

internal enum class OposiBotsAuthOperation(
    val ipLimit: Int,
    val subjectLimit: Int,
    val windowSeconds: Long,
) {
    REGISTER(6, 3, 3_600),
    RESEND(6, 3, 3_600),
    EMAIL_EXCHANGE(20, 8, 900),
    LOGIN(30, 8, 300),

    /**
     * MASTER2-AUTH-002: tope POR CUENTA (email solo) del login, ademas del de (email, IP). Alto a proposito: desde
     * una sola IP el cupo (email, IP) de [LOGIN] (8/300 s = 24/900 s) nunca lo alcanza, asi que un tercero no bloquea
     * al titular sin varias IPs; pero un ataque distribuido (rotando IPs o prefijos IPv6) ya no tiene cupo ilimitado
     * por cuenta. Solo se usa como limite por sujeto (el de IP no se consulta con esta operacion).
     */
    LOGIN_ACCOUNT(30, 50, 900),
    REFRESH(60, 30, 300),
    LOGOUT(60, 30, 300),
    ME(120, 60, 60),
    PROFILE(20, 10, 300),
    RECOVER(8, 3, 3_600),
    RESET(10, 5, 900),
    GOOGLE(30, 10, 300),
    APPLE(30, 10, 300),

    // SLICE 1 (SEC-008): rutas canonicas de perfil. Limite por usuario (JWT sub); el de IP solo cuenta en las publicas.
    PROFILE_WRITE(60, 20, 300),
    LEGAL_LIST(60, 60, 300),
    ACCOUNT_DELETION(10, 3, 3_600),

    // SLICE 3 (SEC-008): runtime de examen. Limite por usuario (JWT sub); el de IP no se consulta.
    /** D2-03: abrir o reanudar un intento. */
    ATTEMPT_START(60, 30, 300),

    /** D2-09: una respuesta por pregunta (hasta 200 por intento) mas reintentos y el vaciado de la cola offline. */
    ATTEMPT_ANSWER(1_200, 600, 60),

    /** D2-10 / D2-11: cerrar (finalizar o abandonar) un intento. */
    ATTEMPT_CLOSE(60, 30, 300),

    /** D2-65: paginas de la revision. */
    ATTEMPT_REVIEW(240, 120, 60),

    /**
     * Solicitud PUBLICA de acceso de administrador (`POST /v1/auth/admin-access-requests`, AdminAccessRequests.kt). Crea una
     * cuenta de Auth, asi que es tan estricta como [REGISTER]: pocas por IP y hora, y un tope SILENCIOSO por email.
     */
    ADMIN_ACCESS_REQUEST(5, 3, 3_600),

    /**
     * `POST /v1/auth/admin-access-requests/verify` (AdminAccessRequests.kt): el codigo de 6 cifras que demuestra el buzon.
     * Sujeto = (correo canonico, IP de cliente), como el login (M2): un tercero que agota el cupo desde SU IP no bloquea al
     * solicitante desde la suya. El tope DIARIO por correo ([ADMIN_ACCESS_VERIFY_DAILY]) y el contador de fallos por
     * solicitud (`intentos_verificacion`, 5 = solicitud agotada) acotan la fuerza bruta distribuida; el cupo por IP frena el
     * barrido de correos.
     */
    ADMIN_ACCESS_VERIFY(10, 5, 900),

    /** `POST /v1/auth/admin-access-requests/resend-code`: reenvia el codigo. Tan estricta como [RESEND]; tope por correo SILENCIOSO. */
    ADMIN_ACCESS_RESEND_CODE(6, 3, 3_600),

    /**
     * M2: techo DIARIO por correo (solo email, sin IP) de `/verify`, ademas de [ADMIN_ACCESS_VERIFY]. Rotar IPs ya no da
     * intentos ilimitados contra el codigo de una cuenta. Solo se usa como limite por sujeto (el de IP no se consulta).
     */
    ADMIN_ACCESS_VERIFY_DAILY(10, 20, 86_400),

    /** DEBT-151: mutaciones de /api/v1/admin por administrador (limite real configurable: ADMIN_MUTATION_RATE_LIMIT_PER_MINUTE). */
    ADMIN_MUTATION(120, 120, 60),
}

internal data class OposiBotsRateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0,
)

internal interface OposiBotsAuthRateLimiter : AutoCloseable {
    suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String): OposiBotsRateLimitDecision
    suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String): OposiBotsRateLimitDecision

    /** Como [checkSubject] pero con tope explicito (configurable). Por defecto ignora [limit] y usa el de la operacion. */
    suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String, limit: Int): OposiBotsRateLimitDecision =
        checkSubject(operation, subject)
    override fun close() = Unit
}

internal object OposiBotsAuthRateLimiterFactory {
    fun create(configuration: OposiBotsAuthConfiguration): OposiBotsAuthRateLimiter {
        val hasher = RateLimitKeyHasher(configuration.rateLimitHmacSecret)
        return when (configuration.rateLimitMode) {
            "valkey" -> ValkeyOposiBotsAuthRateLimiter(configuration.valkeyUrl!!, hasher)
            "memory" -> InMemoryOposiBotsAuthRateLimiter(hasher = hasher)
            else -> error("AUTH_RATE_LIMIT_MODE no soportado.")
        }
    }
}

internal class InMemoryOposiBotsAuthRateLimiter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hasher: RateLimitKeyHasher = RateLimitKeyHasher("test-only-rate-limit-secret-32chars"),
) : OposiBotsAuthRateLimiter {
    private val windows = ConcurrentHashMap<String, RateWindow>()

    override suspend fun checkIp(
        operation: OposiBotsAuthOperation,
        clientIp: String,
    ) = consume("ip", operation, rateLimitClientBucket(clientIp), operation.ipLimit)

    override suspend fun checkSubject(
        operation: OposiBotsAuthOperation,
        subject: String,
    ) = consume("subject", operation, subject, operation.subjectLimit)

    override suspend fun checkSubject(
        operation: OposiBotsAuthOperation,
        subject: String,
        limit: Int,
    ) = consume("subject", operation, subject, limit)

    private fun consume(
        scope: String,
        operation: OposiBotsAuthOperation,
        rawKey: String,
        limit: Int,
    ): OposiBotsRateLimitDecision {
        val now = nowMillis()
        val key = "$scope:${operation.name}:${hasher.hash(rawKey)}"
        var decision = OposiBotsRateLimitDecision(false, operation.windowSeconds)
        windows.compute(key) { _, current ->
            val active = current?.takeIf { it.expiresAtMillis > now }
            val next = if (active == null) {
                RateWindow(1, now + operation.windowSeconds * 1_000)
            } else {
                active.copy(count = active.count + 1)
            }
            decision = OposiBotsRateLimitDecision(
                allowed = next.count <= limit,
                retryAfterSeconds = ceil((next.expiresAtMillis - now).coerceAtLeast(1) / 1_000.0).toLong(),
            )
            next
        }
        if (windows.size > 50_000) {
            windows.entries.removeIf { it.value.expiresAtMillis <= now }
        }
        return decision
    }

    private data class RateWindow(val count: Int, val expiresAtMillis: Long)
}

/**
 * MASTER-ARCH-007: el cliente sincrono de Lettuce corre en `Dispatchers.IO`; sin tiempos limite un Valkey degradado
 * bloqueaba hilos del pool durante los 60 s por defecto de Lettuce y, como TODA ruta canonica pasa por el limitador,
 * dejaba sin hilos al resto del servicio. Ahora la conexion y cada comando tienen un tope corto y cualquier fallo se
 * traduce a 503 UPSTREAM_UNAVAILABLE con Retry-After (FALLA CERRADO: sin limitador no se atiende la peticion).
 */
internal class ValkeyOposiBotsAuthRateLimiter(
    url: String,
    private val hasher: RateLimitKeyHasher,
    connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
    private val commandTimeout: Duration = DEFAULT_COMMAND_TIMEOUT,
) : OposiBotsAuthRateLimiter {
    private val client = RedisClient.create(url).apply {
        setDefaultTimeout(commandTimeout)
        options = ClientOptions.builder()
            .protocolVersion(ProtocolVersion.RESP2)
            .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
            .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
            .build()
    }
    private val connection = client.connect()

    override suspend fun checkIp(
        operation: OposiBotsAuthOperation,
        clientIp: String,
    ) = consume("ip", operation, rateLimitClientBucket(clientIp), operation.ipLimit)

    override suspend fun checkSubject(
        operation: OposiBotsAuthOperation,
        subject: String,
    ) = consume("subject", operation, subject, operation.subjectLimit)

    override suspend fun checkSubject(
        operation: OposiBotsAuthOperation,
        subject: String,
        limit: Int,
    ) = consume("subject", operation, subject, limit)

    private suspend fun consume(
        scope: String,
        operation: OposiBotsAuthOperation,
        rawKey: String,
        limit: Int,
    ): OposiBotsRateLimitDecision = withContext(Dispatchers.IO) {
        val key = "oposibots:auth:$scope:${operation.name.lowercase()}:${hasher.hash(rawKey)}"
        try {
            val result = connection.sync().eval<List<Any>>(
                RATE_LIMIT_SCRIPT,
                ScriptOutputType.MULTI,
                arrayOf(key),
                limit.toString(),
                (operation.windowSeconds * 1_000).toString(),
            )
            val allowed = (result[0] as Number).toLong() == 1L
            val retryMillis = (result[1] as Number).toLong().coerceAtLeast(1)
            OposiBotsRateLimitDecision(allowed, ceil(retryMillis / 1_000.0).toLong())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RedisException) {
            // Incluye RedisCommandTimeoutException y RedisConnectionException.
            Logger.warn("rate limiter unavailable operation={} type={}", operation.name, failure::class.simpleName)
            throw CanonicalErrorCode.UPSTREAM_UNAVAILABLE.exception(retryAfterSeconds = UNAVAILABLE_RETRY_AFTER_SECONDS, cause = failure)
        }
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    companion object {
        val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(1)
        val DEFAULT_COMMAND_TIMEOUT: Duration = Duration.ofSeconds(2)
        private const val UNAVAILABLE_RETRY_AFTER_SECONDS = 5
        private val Logger = org.slf4j.LoggerFactory.getLogger("OC-KTOR-RATE-LIMITER")
        private const val RATE_LIMIT_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if count > tonumber(ARGV[1]) then return {0, ttl} end
            return {1, ttl}
        """
    }
}

/**
 * MASTER2-AUTH-002: clave de limite de un cliente. Una IPv6 cuenta por su prefijo /64 (lo minimo que se asigna a un
 * suscriptor: rotar direcciones dentro de el ya no da cupos nuevos); una IPv4 mapeada (`::ffff:a.b.c.d`) cuenta como
 * su IPv4; una IPv4 queda igual. Sin DNS: parseo propio del literal; lo que no es un literal valido se usa tal cual.
 */
internal fun rateLimitClientBucket(clientIp: String): String {
    val literal = normalizePeerAddress(clientIp)
    if (':' !in literal) return literal
    val groups = expandIpv6(literal) ?: return literal
    if (groups.subList(0, 5).all { it == 0 } && groups[5] == 0xffff) {
        return "${groups[6] shr 8}.${groups[6] and 0xff}.${groups[7] shr 8}.${groups[7] and 0xff}"
    }
    return groups.take(4).joinToString(":") { it.toString(16) } + "::/64"
}

/** Los 8 grupos de 16 bits de una IPv6 literal (con `::` y cola IPv4 opcionales), o `null` si no es valida. */
private fun expandIpv6(literal: String): List<Int>? {
    var text = literal
    val tail = mutableListOf<Int>()
    val lastColon = text.lastIndexOf(':')
    if ('.' in text.substring(lastColon + 1)) {
        val octets = text.substring(lastColon + 1).split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.toIntOrNull() !in 0..255 }) return null
        val v = octets.map(String::toInt)
        tail += (v[0] shl 8) or v[1]
        tail += (v[2] shl 8) or v[3]
        // "...:a.b.c.d" -> "..." (se quita el ':' separador salvo que forme parte de un '::').
        text = text.substring(0, lastColon + 1).let { if (it.endsWith("::")) it else it.removeSuffix(":") }
    }
    val halves = text.split("::")
    if (halves.size > 2) return null
    fun groupsOf(part: String): List<Int>? =
        if (part.isEmpty()) emptyList() else part.split(':').map { g ->
            if (g.isEmpty() || g.length > 4 || !g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            g.toInt(16)
        }
    val head = groupsOf(halves[0]) ?: return null
    val rest = if (halves.size == 2) groupsOf(halves[1]) ?: return null else emptyList()
    val explicit = head.size + rest.size + tail.size
    val groups = if (halves.size == 2) {
        if (explicit > 7) return null
        head + List(8 - explicit) { 0 } + rest + tail
    } else {
        if (explicit != 8) return null
        head + tail
    }
    return groups
}

internal class RateLimitKeyHasher(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")

    fun hash(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
