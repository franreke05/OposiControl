package com.oposicontrol.admin.features.exams.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject

@Composable
actual fun rememberContentFilePickerLauncher(
    mimeType: String,
    onPicked: (PickedFileReference) -> Unit,
): ContentFilePickerLauncher {
    val delegate = remember { ImagePickerDelegate(onPicked) }
    return remember(delegate) {
        ContentFilePickerLauncher {
            val root = topViewController() ?: return@ContentFilePickerLauncher
            val config = PHPickerConfiguration()
            config.filter = PHPickerFilter.imagesFilter
            config.selectionLimit = 1
            val picker = PHPickerViewController(configuration = config)
            picker.delegate = delegate
            root.presentViewController(picker, animated = true, completion = null)
        }
    }
}

@Composable
actual fun rememberTextFilePickerLauncher(
    onPicked: (PickedTextFile) -> Unit,
): ContentFilePickerLauncher = ContentFilePickerLauncher {}

private class ImagePickerDelegate(
    private val onPicked: (PickedFileReference) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    @OptIn(ExperimentalForeignApi::class)
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val result = didFinishPicking.filterIsInstance<PHPickerResult>().firstOrNull() ?: return
        result.itemProvider.loadFileRepresentationForTypeIdentifier("public.image") { url, _ ->
            val fileUrl = url ?: return@loadFileRepresentationForTypeIdentifier
            val origName = fileUrl.lastPathComponent ?: "imagen.jpg"
            val ext = origName.substringAfterLast(".", "jpg")
            val uniqueName = "${origName.substringBeforeLast(".")}_${NSDate().timeIntervalSince1970.toLong()}.$ext"
            val tempPath = NSTemporaryDirectory() + uniqueName
            val destUrl = NSURL.fileURLWithPath(tempPath)
            NSFileManager.defaultManager.removeItemAtPath(tempPath, error = null)
            val copied = NSFileManager.defaultManager.copyItemAtURL(fileUrl, toURL = destUrl, error = null)
            val finalPath = if (copied) tempPath else (fileUrl.path ?: return@loadFileRepresentationForTypeIdentifier)
            val mimeType = when (ext.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                else -> "image/jpeg"
            }
            onPicked(PickedFileReference(uri = finalPath, name = origName, mimeType = mimeType))
        }
    }
}

private fun topViewController(): UIViewController? {
    val scene = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull() ?: return null
    @Suppress("UNCHECKED_CAST")
    val windows = scene.windows as? List<platform.UIKit.UIWindow> ?: return null
    var vc = windows.firstOrNull { it.isKeyWindow() }?.rootViewController() ?: return null
    while (true) { vc = vc.presentedViewController() ?: return vc }
}
