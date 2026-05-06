package com.oposicontrol.admin.data.storage

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppError
import com.oposicontrol.admin.core.common.result.AppResult

actual class PlatformFileReader actual constructor(
    platformContext: AppPlatformContext,
) {
    actual fun read(localPathOrUri: String): AppResult<LocalBinaryAsset> {
        return AppResult.Failure(
            AppError.Validation("La lectura directa de archivos aun no esta soportada en iOS para este panel."),
        )
    }
}
