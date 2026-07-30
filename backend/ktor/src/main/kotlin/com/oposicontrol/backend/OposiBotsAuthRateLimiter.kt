package com.oposicontrol.backend

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal enum class OposiBotsAuthOperation {
    LOGIN,
    REGISTER,
    REGISTER_PROFILE,
    RECOVER,
    RESET_PASSWORD,
    OAUTH_GOOGLE,
    OAUTH_APPLE,
}

internal fun interface OposiBotsAuthRateLimiter {
    fun allow(
        operation: OposiBotsAuthOperation,
        remoteHost: String,
        subject: String?,
    ): Boolean
}

/**
 * Bounded in-memory limiter for the single-instance VPS deployment.
 *
 * Keys are SHA-256 fingerprints, so identifiers, reset tokens, and provider
 * credentials never remain in memory or logs as plain text.
 */
internal class InMemoryOposiBotsAuthRateLimiter(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val policies: Map<OposiBotsAuthOperation, OposiBotsAuthRateLimitPolicy> = DEFAULT_POLICIES,
) : OposiBotsAuthRateLimiter {
    private val windows = ConcurrentHashMap<String, RateWindow>()
    private val mutationLock = Any()
    private var checksSincePrune = 0

    override fun allow(
        operation: OposiBotsAuthOperation,
        remoteHost: String,
        subject: String?,
    ): Boolean = synchronized(mutationLock) {
        val policy = policies.getValue(operation)
        val now = nowMillis()
        val ipAllowed = increment(
            key = "ip:${operation.name}:${remoteHost.sha256Fingerprint()}",
            limit = policy.ipLimit,
            windowMillis = policy.windowMillis,
            now = now,
        )
        val subjectAllowed = subject
            ?.takeIf(String::isNotBlank)
            ?.let { rawSubject ->
                increment(
                    key = "subject:${operation.name}:${rawSubject.sha256Fingerprint()}",
                    limit = policy.subjectLimit,
                    windowMillis = policy.windowMillis,
                    now = now,
                )
            }
            ?: true

        checksSincePrune += 1
        if (checksSincePrune >= PRUNE_EVERY_CHECKS) {
            pruneExpired(now)
            checksSincePrune = 0
        }
        ipAllowed && subjectAllowed
    }

    private fun increment(
        key: String,
        limit: Int,
        windowMillis: Long,
        now: Long,
    ): Boolean {
        val current = windows[key]
        if (current == null && windows.size >= MAX_TRACKED_WINDOWS) {
            pruneExpired(now)
            if (windows.size >= MAX_TRACKED_WINDOWS) return false
        }
        val next = if (current == null || current.expiresAtMillis <= now) {
            RateWindow(count = 1, expiresAtMillis = now + windowMillis)
        } else {
            current.copy(count = current.count + 1)
        }
        windows[key] = next
        return next.count <= limit
    }

    private fun pruneExpired(now: Long) {
        windows.entries.removeIf { (_, window) -> window.expiresAtMillis <= now }
    }

    companion object {
        private const val PRUNE_EVERY_CHECKS = 256
        private const val MAX_TRACKED_WINDOWS = 50_000
        private const val FIVE_MINUTES_MILLIS = 5 * 60 * 1_000L
        private const val FIFTEEN_MINUTES_MILLIS = 15 * 60 * 1_000L
        private const val ONE_HOUR_MILLIS = 60 * 60 * 1_000L

        val DEFAULT_POLICIES: Map<OposiBotsAuthOperation, OposiBotsAuthRateLimitPolicy> = mapOf(
            OposiBotsAuthOperation.LOGIN to OposiBotsAuthRateLimitPolicy(
                ipLimit = 30,
                subjectLimit = 8,
                windowMillis = FIVE_MINUTES_MILLIS,
            ),
            OposiBotsAuthOperation.REGISTER to OposiBotsAuthRateLimitPolicy(
                ipLimit = 10,
                subjectLimit = 4,
                windowMillis = ONE_HOUR_MILLIS,
            ),
            OposiBotsAuthOperation.REGISTER_PROFILE to OposiBotsAuthRateLimitPolicy(
                ipLimit = 30,
                subjectLimit = 10,
                windowMillis = FIVE_MINUTES_MILLIS,
            ),
            OposiBotsAuthOperation.RECOVER to OposiBotsAuthRateLimitPolicy(
                ipLimit = 10,
                subjectLimit = 3,
                windowMillis = ONE_HOUR_MILLIS,
            ),
            OposiBotsAuthOperation.RESET_PASSWORD to OposiBotsAuthRateLimitPolicy(
                ipLimit = 10,
                subjectLimit = 5,
                windowMillis = FIFTEEN_MINUTES_MILLIS,
            ),
            OposiBotsAuthOperation.OAUTH_GOOGLE to OposiBotsAuthRateLimitPolicy(
                ipLimit = 30,
                subjectLimit = 10,
                windowMillis = FIVE_MINUTES_MILLIS,
            ),
            OposiBotsAuthOperation.OAUTH_APPLE to OposiBotsAuthRateLimitPolicy(
                ipLimit = 30,
                subjectLimit = 10,
                windowMillis = FIVE_MINUTES_MILLIS,
            ),
        )
    }
}

internal data class OposiBotsAuthRateLimitPolicy(
    val ipLimit: Int,
    val subjectLimit: Int,
    val windowMillis: Long,
) {
    init {
        require(ipLimit > 0)
        require(subjectLimit > 0)
        require(windowMillis > 0)
    }
}

private data class RateWindow(
    val count: Int,
    val expiresAtMillis: Long,
)

private fun String.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
