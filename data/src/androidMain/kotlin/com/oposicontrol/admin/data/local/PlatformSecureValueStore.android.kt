package com.oposicontrol.admin.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal actual class PlatformSecureValueStore actual constructor(
    platformContext: AppPlatformContext,
) {
    private val preferences = platformContext.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        android.content.Context.MODE_PRIVATE,
    )

    actual fun read(): String? {
        val encoded = preferences.getString(SESSION_KEY, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_SIZE_BYTES)
            val iv = payload.copyOfRange(0, IV_SIZE_BYTES)
            val encrypted = payload.copyOfRange(IV_SIZE_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.doFinal(encrypted).decodeToString()
        }.getOrElse {
            clear()
            null
        }
    }

    actual fun write(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        val payload = cipher.iv + encrypted
        preferences.edit()
            .putString(SESSION_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    actual fun clear() {
        preferences.edit().remove(SESSION_KEY).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "oposicontrol_secure_session"
        const val SESSION_KEY = "encrypted_session"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "oposicontrol.admin.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_LENGTH_BITS = 128
    }
}
