package com.oposicontrol.admin.data.storage

import android.net.Uri
import android.provider.OpenableColumns
import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
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
        return AppResult.Success(
            LocalBinaryAsset(
                fileName = file.name,
                mimeType = mimeType,
                bytes = file.readBytes(),
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

        return AppResult.Success(
            LocalBinaryAsset(
                fileName = fileName,
                mimeType = mimeType,
                bytes = bytes,
            ),
        )
    }
}
