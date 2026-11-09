package com.unitrading.chart.drawing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
internal actual fun rememberDrawingImagePicker(onResult: (ByteArray?) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onResult)
    return remember {
        {
            val dialog = FileDialog(null as Frame?, "选择图片", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp") }
            dialog.isVisible = true
            val file = dialog.file?.let { File(dialog.directory, it) }
            current(file?.takeIf { it.length() in 1..MAX_DRAWING_IMAGE_BYTES.toLong() }?.let { runCatching { it.readBytes() }.getOrNull() })
        }
    }
}
