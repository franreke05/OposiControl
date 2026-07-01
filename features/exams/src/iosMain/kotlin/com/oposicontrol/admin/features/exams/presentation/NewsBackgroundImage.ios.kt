@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIViewContentMode

@Composable
actual fun NewsBackgroundImage(
    backgroundPath: String,
    modifier: Modifier,
) {
    val image = produceState<UIImage?>(initialValue = null, backgroundPath) {
        value = withContext(Dispatchers.Default) {
            runCatching { backgroundPath.loadImage() }.getOrNull()
        }
    }.value
    UIKitView(
        factory = {
            UIImageView().apply {
                clipsToBounds = true
                contentMode = UIViewContentMode.UIViewContentModeScaleAspectFill
                userInteractionEnabled = false
            }
        },
        modifier = modifier,
        update = { it.image = image },
    )
}

private fun String.loadImage(): UIImage? {
    if (startsWith("http://") || startsWith("https://")) {
        val url = NSURL.URLWithString(this) ?: return null
        val data = NSData.create(contentsOfURL = url) ?: return null
        return UIImage.imageWithData(data)
    }
    val path = removePrefix("file://")
    return UIImage.imageWithContentsOfFile(path)
}
