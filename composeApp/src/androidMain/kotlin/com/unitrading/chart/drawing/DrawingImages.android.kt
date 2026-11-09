package com.unitrading.chart.drawing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberDrawingImagePicker(onResult: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        current(uri?.let { selected ->
            runCatching { context.contentResolver.openInputStream(selected)?.use { it.readBytes() } }.getOrNull()?.takeIf { it.size <= MAX_DRAWING_IMAGE_BYTES }
        })
    }
    return remember(launcher) { { launcher.launch("image/*") } }
}
