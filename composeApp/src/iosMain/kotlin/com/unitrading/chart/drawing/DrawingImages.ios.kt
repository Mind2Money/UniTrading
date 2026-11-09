package com.unitrading.chart.drawing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** No photo picker on iOS yet; the image tool cancels itself. */
@Composable
internal actual fun rememberDrawingImagePicker(onResult: (ByteArray?) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onResult)
    return remember { { current(null) } }
}
