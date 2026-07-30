package com.oposicontrol.backend

import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
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
    REFRESH(60, 30, 300),
    LOGOUT(60, 30, 300),
    ME(120, 60, 60),
    PROFILE(20, 10, 300),
    RECOVER(8, 3, 3_600),
    RESET(10, 5, 900),
    GOOGLE(30, 10, 300),
    APPLE(30, 10, 300),
}

internal data class OposiBotsRateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0,
)

internal interface OposiBotsAuthRateLimiter : AutoCloseable {
    suspend fun checkIp(operation: OposiBotsAuthOperation, clientIp: String): OposiBotsRateLimitDecision
    suspend fun checkSubject(operation: OposiBotsAuthOperation, subject: String): OposiBotsRateLimitDecision
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
    ) = consume("ip", operation, clientIp, operation.ipLimit)

    override suspend fun checkSubject(
        operation: OposiBotsAuthOperation,
        subject: String,
    ) = consume("subject", operation, subject, operation.subjectLimit)

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

internal class ValkeyOposiBotsAuthRateLimiter(
    url: String,
    private val hasher: RateLimitKeyHasher,
) : OposiBotsAuthRateLimiter {
    private val client = RedisClient.create(url)
    private val connection = client.connect()

    override suspend fun checkIp(
        operation: OposiBotsAuthOperation,
        clientIp: String,
    ) = consume("ip", operation, clientIp, operation.ipLimit)

    override suspend fun checkSubject(
        operation: OposiBotsAuthOperation,
        subject: String,
    ) = consume("subject", operation, subject, operation.subjectLimit)

    private suspend fun consume(
        scope: String,
        operation: OposiBotsAuthOperation,
        rawKey: String,
        limit: Int,
    ): OposiBotsRateLimitDecision = withContext(Dispatchers.IO) {
        val key = "oposibots:auth:$scope:${operation.name.lowercase()}:${hasher.hash(rawKey)}"
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
    }

    override fun close() {
        connection.close()
        client.shutdown()
    }

    companion object {
        private const val RATE_LIMIT_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            local ttl = redis.call('PTTL', KEYS[1])
            if count > tonumber(ARGV[1]) then return {0, ttl} end
            return {1, ttl}
        """
    }
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
