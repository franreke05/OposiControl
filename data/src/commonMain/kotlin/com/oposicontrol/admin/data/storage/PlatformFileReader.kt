package com.oposicontrol.admin.data.storage

import com.oposicontrol.admin.core.common.platform.AppPlatformContext
import com.oposicontrol.admin.core.common.result.AppResult

data class LocalBinaryAsset(
    val fileName: String,
    val mimeType: String?,
    val bytes: ByteArray,
)

expect class PlatformFileReader(
    platformContext: AppPlatformContext,
) {
    fun read(localPathOrUri: String): AppResult<LocalBinaryAsset>
}
