package com.oposicontrol.admin.data.storage

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

actual class PlatformFileReader actual constructor(
    platformContext: AppPlatformContext,
) {
    @OptIn(ExperimentalForeignApi::class)
    actual fun read(localPathOrUri: String): AppResult<LocalBinaryAsset> {
        val path = localPathOrUri.removePrefix("file://")
        val data = NSData.dataWithContentsOfFile(path)
            ?: return AppResult.Failure(AppError.Validation("No se pudo leer el archivo: ${path.substringAfterLast("/")}"))
        val length = data.length.toInt()
        val bytes = ByteArray(length)
        if (length > 0) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
        val fileName = path.substringAfterLast("/")
        val mimeType = guessMimeType(fileName)
        return AppResult.Success(LocalBinaryAsset(fileName = fileName, mimeType = mimeType, bytes = bytes))
    }

    private fun guessMimeType(name: String): String = when (name.substringAfterLast(".").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}
