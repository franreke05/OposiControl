package com.oposicontrol.admin.data.local

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataGetTypeID
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class)
internal actual class PlatformSecureValueStore actual constructor(
    @Suppress("UNUSED_PARAMETER")
    platformContext: AppPlatformContext,
) {
    actual fun read(): String? = runCatching {
        readFromKeychain()
    }.getOrNull()

    actual fun write(value: String) {
        val valueData = value.encodeToByteArray().toCFData()
        try {
            val updateStatus = updateExisting(valueData)
            when (updateStatus) {
                errSecSuccess -> Unit
                errSecItemNotFound -> addNew(valueData)
                else -> keychainFailure("update", updateStatus)
            }
        } finally {
            CFRelease(valueData)
        }
    }

    actual fun clear() {
        val status = withBaseQuery { query -> SecItemDelete(query) }
        if (status != errSecSuccess && status != errSecItemNotFound) {
            keychainFailure("delete", status)
        }
    }

    private fun readFromKeychain(): String? = withBaseQuery { query ->
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

        memScoped {
            val result = alloc<CFTypeRefVar>()
            result.value = null
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) keychainFailure("read", status)

            val item = result.value ?: return@memScoped null
            try {
                if (CFGetTypeID(item) != CFDataGetTypeID()) return@memScoped null

                val data: CFDataRef = item.reinterpret()
                val length = CFDataGetLength(data)
                if (length !in 0..MAX_SESSION_BYTES.toLong()) return@memScoped null
                if (length == 0L) return@memScoped ""

                val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
                bytes.reinterpret<ByteVar>().readBytes(length.toInt()).decodeToString()
            } finally {
                CFRelease(item)
            }
        }
    }

    private fun updateExisting(valueData: CFDataRef): Int {
        val attributes = newDictionary()
        return try {
            CFDictionarySetValue(attributes, kSecValueData, valueData)
            CFDictionarySetValue(
                attributes,
                kSecAttrAccessible,
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            )
            withBaseQuery { query -> SecItemUpdate(query, attributes) }
        } finally {
            CFRelease(attributes)
        }
    }

    private fun addNew(valueData: CFDataRef) {
        val status = withBaseQuery { query ->
            CFDictionarySetValue(query, kSecValueData, valueData)
            CFDictionarySetValue(
                query,
                kSecAttrAccessible,
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            )
            SecItemAdd(query, null)
        }

        when (status) {
            errSecSuccess -> Unit
            // WHY: another writer can insert between update and add; converge on update.
            errSecDuplicateItem -> {
                val retryStatus = updateExisting(valueData)
                if (retryStatus != errSecSuccess) keychainFailure("update-after-race", retryStatus)
            }
            else -> keychainFailure("add", status)
        }
    }

    private inline fun <T> withBaseQuery(block: (CFMutableDictionaryRef) -> T): T {
        val service = requireNotNull(
            CFStringCreateWithCString(null, SERVICE, kCFStringEncodingUTF8),
        )
        val account = requireNotNull(
            CFStringCreateWithCString(null, ACCOUNT, kCFStringEncodingUTF8),
        )
        val query = newDictionary()
        return try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(service)
        }
    }

    private fun newDictionary(): CFMutableDictionaryRef {
        // WHY: all referenced values outlive the immediate Security.framework call.
        return requireNotNull(CFDictionaryCreateMutable(null, 0, null, null))
    }

    private fun ByteArray.toCFData(): CFDataRef {
        val data = if (isEmpty()) {
            CFDataCreate(null, null, 0)
        } else {
            usePinned { pinned ->
                CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())
            }
        }
        return requireNotNull(data)
    }

    private fun keychainFailure(operation: String, status: Int): Nothing {
        throw IllegalStateException("iOS Keychain $operation failed (OSStatus=$status)")
    }

    private companion object {
        const val SERVICE = "com.oposicontrol.admin.auth"
        const val ACCOUNT = "supabase.session.v1"
        const val MAX_SESSION_BYTES = 1_048_576
    }
}
