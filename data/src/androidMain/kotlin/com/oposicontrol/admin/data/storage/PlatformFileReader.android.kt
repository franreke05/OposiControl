package com.oposicontrol.admin.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection

actual class PlatformFileReader actual constructor(
    private val platformContext: AppPlatformContext,
) {
    actual fun read(localPathOrUri: String): AppResult<LocalBinaryAsset> {
        return if (localPathOrUri.startsWith("content://")) {
            readContentUri(Uri.parse(localPathOrUri))
        } else {
            readFile(localPathOrUri)
        }
    }

    private fun readFile(localPath: String): AppResult<LocalBinaryAsset> {
        val file = File(localPath)
        if (!file.exists() || !file.isFile) {
            return AppResult.Failure(AppError.Validation("No se encontro el archivo local indicado."))
        }
        val mimeType = URLConnection.guessContentTypeFromName(file.name)
        val bytes = file.readBytes()
        val asset = normalizeImageOrientation(
            fileName = file.name,
            mimeType = mimeType,
            bytes = bytes,
        )
        return AppResult.Success(
            LocalBinaryAsset(
                fileName = asset.fileName,
                mimeType = asset.mimeType,
                bytes = asset.bytes,
            ),
        )
    }

    private fun readContentUri(uri: Uri): AppResult<LocalBinaryAsset> {
        val resolver = platformContext.applicationContext.contentResolver
        val mimeType = resolver.getType(uri)
        val fileName = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "resource.bin"

        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return AppResult.Failure(AppError.Validation("No se pudo leer el recurso indicado."))
        val asset = normalizeImageOrientation(
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes,
        )

        return AppResult.Success(
            LocalBinaryAsset(
                fileName = asset.fileName,
                mimeType = asset.mimeType,
                bytes = asset.bytes,
            ),
        )
    }

    private fun normalizeImageOrientation(
        fileName: String,
        mimeType: String?,
        bytes: ByteArray,
    ): LocalBinaryAsset {
        if (mimeType?.startsWith("image/") != true) {
            return LocalBinaryAsset(fileName = fileName, mimeType = mimeType, bytes = bytes)
        }
        val transform = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        if (transform == ExifInterface.ORIENTATION_NORMAL || transform == ExifInterface.ORIENTATION_UNDEFINED) {
            return LocalBinaryAsset(fileName = fileName, mimeType = mimeType, bytes = bytes)
        }

        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return LocalBinaryAsset(fileName = fileName, mimeType = mimeType, bytes = bytes)
        val matrix = Matrix().apply {
            when (transform) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(270f)
                    preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        if (rotated !== original) {
            original.recycle()
        }
        val output = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 92, output)
        rotated.recycle()

        return LocalBinaryAsset(
            fileName = fileName.withJpegExtension(),
            mimeType = "image/jpeg",
            bytes = output.toByteArray(),
        )
    }

    private fun String.withJpegExtension(): String {
        val dotIndex = lastIndexOf('.')
        val baseName = if (dotIndex > 0) substring(0, dotIndex) else this
        return "${baseName.ifBlank { "image" }}.jpg"
    }
}
