package com.oposicontrol.admin.data.local

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal actual class PlatformSecureValueStore actual constructor(
    @Suppress("UNUSED_PARAMETER")
    platformContext: AppPlatformContext,
) {
    private val preferences = Preferences.userRoot().node(PREFERENCES_NODE)
    private var memoryValue: String? = null

    actual fun read(): String? {
        val secret = sessionSecret() ?: return memoryValue
        val encoded = preferences.get(SESSION_KEY, null) ?: return null
        return runCatching { decrypt(encoded, secret) }
            .getOrElse {
                clear()
                null
            }
    }

    actual fun write(value: String) {
        val secret = sessionSecret()
        if (secret == null) {
            // No plaintext persistence when the OS/launcher has not supplied a secret.
            memoryValue = value
            preferences.remove(SESSION_KEY)
            return
        }
        memoryValue = null
        preferences.put(SESSION_KEY, encrypt(value, secret))
    }

    actual fun clear() {
        memoryValue = null
        preferences.remove(SESSION_KEY)
    }

    private fun encrypt(value: String, secret: String): String {
        val iv = ByteArray(IV_SIZE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret.toKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(value.encodeToByteArray()))
    }

    private fun decrypt(value: String, secret: String): String {
        val payload = Base64.getDecoder().decode(value)
        require(payload.size > IV_SIZE_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret.toKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, payload.copyOfRange(0, IV_SIZE_BYTES)),
        )
        return cipher.doFinal(payload.copyOfRange(IV_SIZE_BYTES, payload.size)).decodeToString()
    }

    private fun String.toKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(encodeToByteArray())
        return SecretKeySpec(digest, "AES")
    }

    private fun sessionSecret(): String? {
        return System.getProperty(SESSION_SECRET_PROPERTY)
            ?.takeIf { it.length >= MIN_SECRET_LENGTH }
            ?: System.getenv(SESSION_SECRET_ENV)?.takeIf { it.length >= MIN_SECRET_LENGTH }
    }

    private companion object {
        const val PREFERENCES_NODE = "com/oposicontrol/admin/session"
        const val SESSION_KEY = "encrypted_session"
        const val SESSION_SECRET_ENV = "OPOSICONTROL_DESKTOP_SESSION_SECRET"
        const val SESSION_SECRET_PROPERTY = "oposi.session.secret"
        const val MIN_SECRET_LENGTH = 24
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
