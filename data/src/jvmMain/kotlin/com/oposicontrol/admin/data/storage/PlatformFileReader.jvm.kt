package com.oposicontrol.admin.data.storage

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult
import java.io.File
import java.net.URLConnection

actual class PlatformFileReader actual constructor(
    platformContext: AppPlatformContext,
) {
    actual fun read(localPathOrUri: String): AppResult<LocalBinaryAsset> {
        val file = File(localPathOrUri)
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
}
