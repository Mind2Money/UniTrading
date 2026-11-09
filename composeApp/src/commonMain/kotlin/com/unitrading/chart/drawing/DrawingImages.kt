package com.unitrading.chart.drawing

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.io.encoding.Base64

/** Decodes a picked picture file. */
internal fun decodeDrawingImage(encoded: ByteArray): ImageBitmap = encoded.decodeToImageBitmap()

/** Pictures are kept in the snapshot store as text, each under its own key. */
internal fun encodeDrawingImage(encoded: ByteArray): String = Base64.encode(encoded)
internal fun decodeStoredImage(text: String): ImageBitmap = decodeDrawingImage(Base64.decode(text))

/** Pictures larger than this are refused: each one lives in the app's own storage. */
internal const val MAX_DRAWING_IMAGE_BYTES = 8 * 1024 * 1024

/** Opens the platform's file or photo picker; the result is null when the user backs out or the file is too large. */
@Composable
internal expect fun rememberDrawingImagePicker(onResult: (ByteArray?) -> Unit): () -> Unit
