package com.unitrading.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The Material Icons this app uses, on their 24dp grid. Path data is copied from Google's Material Icons (Apache-2.0).
 * The full icon library is a 37 MB jar on desktop, so only these glyphs ship; every UI icon comes from here rather than
 * from Unicode characters, which render differently on each device font.
 */
internal object AppIcons {
    private fun icon(name: String, mirrored: Boolean = false, evenOdd: Boolean = false, paths: Array<String>): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).apply {
            paths.forEach { path ->
                addPath(addPathNodes(path), fill = SolidColor(Color.Black),
                    pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)
            }
        }.build()

    private val LogoCandles = arrayOf(
        "M6 9.5h1v2h1.5v6H7v2H6v-2H4.5v-6H6z",
        "M11.5 6h1v2H14v6h-1.5v2h-1v-2H10V8h1.5z",
        "M17 3h1v2h1.5v6H18v2h-1v-2h-1.5V5H17z",
    )

    /** Three rising candles, the brand mark. */
    val Logo by lazy { icon("Logo", paths = LogoCandles) }

    /** The mark on its tile, for places that cannot tint it: the desktop window and taskbar. */
    val LogoTile by lazy {
        ImageVector.Builder("LogoTile", 64.dp, 64.dp, 24f, 24f).apply {
            addPath(addPathNodes("M6 0h12a6 6 0 0 1 6 6v12a6 6 0 0 1 -6 6H6a6 6 0 0 1 -6 -6V6a6 6 0 0 1 6 -6z"), fill = SolidColor(Color(0xFF0F1B2D)))
            LogoCandles.forEach { addPath(addPathNodes(it), fill = SolidColor(Color(0xFF81B7FF))) }
        }.build()
    }

    val Add by lazy { icon("Add", paths = arrayOf("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")) }
    val Remove by lazy { icon("Remove", paths = arrayOf("M19 13H5v-2h14v2z")) }
    val Close by lazy { icon("Close", paths = arrayOf("M19 6.41L17.59 5L12 10.59L6.41 5L5 6.41L10.59 12L5 17.59L6.41 19L12 13.41L17.59 19L19 17.59L13.41 12L19 6.41z")) }
    val Check by lazy { icon("Check", paths = arrayOf("M9 16.17L4.83 12l-1.42 1.41L9 19L21 7l-1.41 -1.41L9 16.17z")) }
    val CheckCircle by lazy { icon("CheckCircle", paths = arrayOf("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2zM10 17l-5 -5l1.41 -1.41L10 14.17l7.59 -7.59L19 8l-9 9z")) }
    val Edit by lazy { icon("Edit", paths = arrayOf("M14.06 9.02l0.92 0.92L5.92 19L5 19v-0.92l9.06 -9.06M17.66 3c-0.25 0 -0.51 0.1 -0.7 0.29l-1.83 1.83l3.75 3.75l1.83 -1.83c0.39 -0.39 0.39 -1.02 0 -1.41l-2.34 -2.34c-0.2 -0.2 -0.45 -0.29 -0.71 -0.29zM14.06 6.19L3 17.25L3 21h3.75L17.81 9.94l-3.75 -3.75z")) }
    val Delete by lazy { icon("Delete", paths = arrayOf("M16 9v10H8V9h8m-1.5 -6h-5l-1 1H5v2h14V4h-3.5l-1 -1zM18 7H6v12c0 1.1 0.9 2 2 2h8c1.1 0 2 -0.9 2 -2V7z")) }
    val Search by lazy { icon("Search", paths = arrayOf("M15.5 14h-0.79l-0.28 -0.27C15.41 12.59 16 11.11 16 9.5C16 5.91 13.09 3 9.5 3S3 5.91 3 9.5S5.91 16 9.5 16c1.61 0 3.09 -0.59 4.23 -1.57l0.27 0.28v0.79l5 4.99L20.49 19l-4.99 -5zM9.5 14C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5S14 7.01 14 9.5S11.99 14 9.5 14z")) }
    val ArrowBack by lazy { icon("ArrowBack", mirrored = true, paths = arrayOf("M20 11H7.83l5.59 -5.59L12 4l-8 8l8 8l1.41 -1.41L7.83 13H20v-2z")) }
    val DragIndicator by lazy { icon("DragIndicator", paths = arrayOf("M11 18c0 1.1 -0.9 2 -2 2s-2 -0.9 -2 -2s0.9 -2 2 -2s2 0.9 2 2zM9 10c-1.1 0 -2 0.9 -2 2s0.9 2 2 2s2 -0.9 2 -2s-0.9 -2 -2 -2zM9 4c-1.1 0 -2 0.9 -2 2s0.9 2 2 2s2 -0.9 2 -2s-0.9 -2 -2 -2zM15 8c1.1 0 2 -0.9 2 -2s-0.9 -2 -2 -2s-2 0.9 -2 2s0.9 2 2 2zM15 10c-1.1 0 -2 0.9 -2 2s0.9 2 2 2s2 -0.9 2 -2s-0.9 -2 -2 -2zM15 16c-1.1 0 -2 0.9 -2 2s0.9 2 2 2s2 -0.9 2 -2s-0.9 -2 -2 -2z")) }
    val Send by lazy { icon("Send", mirrored = true, paths = arrayOf("M2.01 21L23 12L2.01 3L2 10l15 2l-15 2z")) }
    val ExpandMore by lazy { icon("ExpandMore", paths = arrayOf("M16.59 8.59L12 13.17L7.41 8.59L6 10l6 6l6 -6l-1.41 -1.41z")) }
    val ExpandLess by lazy { icon("ExpandLess", paths = arrayOf("M12 8l-6 6l1.41 1.41L12 10.83l4.59 4.58L18 14l-6 -6z")) }
    val ChevronRight by lazy { icon("ChevronRight", paths = arrayOf("M10 6L8.59 7.41L13.17 12l-4.58 4.59L10 18l6 -6l-6 -6z")) }
    val ChevronLeft by lazy { icon("ChevronLeft", paths = arrayOf("M15.41 7.41L14 6l-6 6l6 6l1.41 -1.41L10.83 12l4.58 -4.59z")) }
    val Sync by lazy { icon("Sync", paths = arrayOf("M12 4L12 1L8 5l4 4L12 6c3.31 0 6 2.69 6 6c0 1.01 -0.25 1.97 -0.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0 -4.42 -3.58 -8 -8 -8zM12 18c-3.31 0 -6 -2.69 -6 -6c0 -1.01 0.25 -1.97 0.7 -2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4 -4l-4 -4v3z")) }
    val Tune by lazy { icon("Tune", paths = arrayOf("M3 17v2h6v-2L3 17zM3 5v2h10L13 5L3 5zM13 21v-2h8v-2h-8v-2h-2v6h2zM7 9v2L3 11v2h4v2h2L9 9L7 9zM21 13v-2L11 11v2h10zM15 9h2L17 7h4L21 5h-4L17 3h-2v6z")) }
    val Layers by lazy { icon("Layers", paths = arrayOf("M11.99 18.54l-7.37 -5.73L3 14.07l9 7l9 -7l-1.63 -1.27zM12 16l7.36 -5.73L21 9l-9 -7l-9 7l1.63 1.27L12 16zM12 4.53L17.74 9L12 13.47L6.26 9L12 4.53z")) }
    val Star by lazy { icon("Star", paths = arrayOf("M12 17.27L18.18 21l-1.64 -7.03L22 9.24l-7.19 -0.61L12 2L9.19 8.63L2 9.24l5.46 4.73L5.82 21z")) }
    val StarBorder by lazy { icon("StarBorder", paths = arrayOf("M22 9.24l-7.19 -0.62L12 2L9.19 8.63L2 9.24l5.46 4.73L5.82 21L12 17.27L18.18 21l-1.63 -7.03L22 9.24zM12 15.4l-3.76 2.27l1 -4.28l-3.32 -2.88l4.38 -0.38L12 6.1l1.71 4.04l4.38 0.38l-3.32 2.88l1 4.28L12 15.4z")) }
    val ZoomIn by lazy { icon("ZoomIn", paths = arrayOf("M15.5 14h-0.79l-0.28 -0.27C15.41 12.59 16 11.11 16 9.5C16 5.91 13.09 3 9.5 3S3 5.91 3 9.5S5.91 16 9.5 16c1.61 0 3.09 -0.59 4.23 -1.57l0.27 0.28v0.79l5 4.99L20.49 19l-4.99 -5zM9.5 14C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5S14 7.01 14 9.5S11.99 14 9.5 14zM10 7L9 7v2L7 9v1h2v2h1v-2h2L12 9h-2z")) }
    val ZoomOut by lazy { icon("ZoomOut", paths = arrayOf("M15.5 14h-0.79l-0.28 -0.27C15.41 12.59 16 11.11 16 9.5C16 5.91 13.09 3 9.5 3S3 5.91 3 9.5S5.91 16 9.5 16c1.61 0 3.09 -0.59 4.23 -1.57l0.27 0.28v0.79l5 4.99L20.49 19l-4.99 -5zM9.5 14C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5S14 7.01 14 9.5S11.99 14 9.5 14zM7 9h5v1L7 10z")) }
    val RestartAlt by lazy { icon("RestartAlt", paths = arrayOf("M6 13c0 -1.65 0.67 -3.15 1.76 -4.24L6.34 7.34C4.9 8.79 4 10.79 4 13c0 4.08 3.05 7.44 7 7.93v-2.02C8.17 18.43 6 15.97 6 13zM20 13c0 -4.42 -3.58 -8 -8 -8c-0.06 0 -0.12 0.01 -0.18 0.01l1.09 -1.09L11.5 2.5L8 6l3.5 3.5l1.41 -1.41l-1.08 -1.08C11.89 7.01 11.95 7 12 7c3.31 0 6 2.69 6 6c0 2.97 -2.17 5.43 -5 5.91v2.02C16.95 20.44 20 17.08 20 13z")) }
    val Visibility by lazy { icon("Visibility", paths = arrayOf("M12 6c3.79 0 7.17 2.13 8.82 5.5C19.17 14.87 15.79 17 12 17s-7.17 -2.13 -8.82 -5.5C4.83 8.13 8.21 6 12 6m0 -2C7 4 2.73 7.11 1 11.5C2.73 15.89 7 19 12 19s9.27 -3.11 11 -7.5C21.27 7.11 17 4 12 4zM12 9c1.38 0 2.5 1.12 2.5 2.5S13.38 14 12 14s-2.5 -1.12 -2.5 -2.5S10.62 9 12 9m0 -2c-2.48 0 -4.5 2.02 -4.5 4.5S9.52 16 12 16s4.5 -2.02 4.5 -4.5S14.48 7 12 7z")) }
    val VisibilityOff by lazy { icon("VisibilityOff", paths = arrayOf("M12 6c3.79 0 7.17 2.13 8.82 5.5c-0.59 1.22 -1.42 2.27 -2.41 3.12l1.41 1.41c1.39 -1.23 2.49 -2.77 3.18 -4.53C21.27 7.11 17 4 12 4c-1.27 0 -2.49 0.2 -3.64 0.57l1.65 1.65C10.66 6.09 11.32 6 12 6zM10.93 7.14L13 9.21c0.57 0.25 1.03 0.71 1.28 1.28l2.07 2.07c0.08 -0.34 0.14 -0.7 0.14 -1.07C16.5 9.01 14.48 7 12 7c-0.37 0 -0.72 0.05 -1.07 0.14zM2.01 3.87l2.68 2.68C3.06 7.83 1.77 9.53 1 11.5C2.73 15.89 7 19 12 19c1.52 0 2.98 -0.29 4.32 -0.82l3.42 3.42l1.41 -1.41L3.42 2.45L2.01 3.87zM9.51 11.37l2.61 2.61c-0.04 0.01 -0.08 0.02 -0.12 0.02c-1.38 0 -2.5 -1.12 -2.5 -2.5c0 -0.05 0.01 -0.08 0.01 -0.13zM6.11 7.97l1.75 1.75c-0.23 0.55 -0.36 1.15 -0.36 1.78c0 2.48 2.02 4.5 4.5 4.5c0.63 0 1.23 -0.13 1.77 -0.36l0.98 0.98c-0.88 0.24 -1.8 0.38 -2.75 0.38c-3.79 0 -7.17 -2.13 -8.82 -5.5c0.7 -1.43 1.72 -2.61 2.93 -3.53z")) }
    val AccountCircle by lazy { icon("AccountCircle", paths = arrayOf("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2zM7.35 18.5C8.66 17.56 10.26 17 12 17s3.34 0.56 4.65 1.5C15.34 19.44 13.74 20 12 20S8.66 19.44 7.35 18.5zM18.14 17.12L18.14 17.12C16.45 15.8 14.32 15 12 15s-4.45 0.8 -6.14 2.12l0 0C4.7 15.73 4 13.95 4 12c0 -4.42 3.58 -8 8 -8s8 3.58 8 8C20 13.95 19.3 15.73 18.14 17.12z", "M12 6c-1.93 0 -3.5 1.57 -3.5 3.5S10.07 13 12 13s3.5 -1.57 3.5 -3.5S13.93 6 12 6zM12 11c-0.83 0 -1.5 -0.67 -1.5 -1.5S11.17 8 12 8s1.5 0.67 1.5 1.5S12.83 11 12 11z")) }
    val Palette by lazy { icon("Palette", paths = arrayOf("M12 22C6.49 22 2 17.51 2 12S6.49 2 12 2s10 4.04 10 9c0 3.31 -2.69 6 -6 6h-1.77c-0.28 0 -0.5 0.22 -0.5 0.5c0 0.12 0.05 0.23 0.13 0.33c0.41 0.47 0.64 1.06 0.64 1.67C14.5 20.88 13.38 22 12 22zM12 4c-4.41 0 -8 3.59 -8 8s3.59 8 8 8c0.28 0 0.5 -0.22 0.5 -0.5c0 -0.16 -0.08 -0.28 -0.14 -0.35c-0.41 -0.46 -0.63 -1.05 -0.63 -1.65c0 -1.38 1.12 -2.5 2.5 -2.5H16c2.21 0 4 -1.79 4 -4C20 7.14 16.41 4 12 4z", "M6.5 11.5m-1.5 0a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1 -3 0", "M9.5 7.5m-1.5 0a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1 -3 0", "M14.5 7.5m-1.5 0a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1 -3 0", "M17.5 11.5m-1.5 0a1.5 1.5 0 1 1 3 0a1.5 1.5 0 1 1 -3 0")) }
    val History by lazy { icon("History", paths = arrayOf("M13 3c-4.97 0 -9 4.03 -9 9L1 12l3.89 3.89l0.07 0.14L9 12L6 12c0 -3.87 3.13 -7 7 -7s7 3.13 7 7s-3.13 7 -7 7c-1.93 0 -3.68 -0.79 -4.94 -2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9 -4.03 9 -9s-4.03 -9 -9 -9zM12 8v5l4.25 2.52l0.77 -1.28l-3.52 -2.09L13.5 8z")) }
    val Logout by lazy { icon("Logout", mirrored = true, paths = arrayOf("M17 8l-1.41 1.41L17.17 11H9v2h8.17l-1.58 1.58L17 16l4 -4L17 8zM5 5h7V3H5C3.9 3 3 3.9 3 5v14c0 1.1 0.9 2 2 2h7v-2H5V5z")) }
    val Pause by lazy { icon("Pause", paths = arrayOf("M6 19h4L10 5L6 5v14zM14 5v14h4L18 5h-4z")) }
    val PlayArrow by lazy { icon("PlayArrow", paths = arrayOf("M10 8.64L15.27 12L10 15.36V8.64M8 5v14l11 -7L8 5z")) }
    val DeleteSweep by lazy { icon("DeleteSweep", paths = arrayOf("M15 16h4v2h-4zM15 8h7v2h-7zM15 12h6v2h-6zM3 18c0 1.1 0.9 2 2 2h6c1.1 0 2 -0.9 2 -2L13 8L3 8v10zM5 10h6v8L5 18v-8zM10 4L6 4L5 5L2 5v2h12L14 5h-3z")) }
    val Info by lazy { icon("Info", paths = arrayOf("M11 7h2v2h-2zM11 11h2v6h-2zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2zM12 20c-4.41 0 -8 -3.59 -8 -8s3.59 -8 8 -8s8 3.59 8 8s-3.59 8 -8 8z")) }
    val ErrorOutline by lazy { icon("ErrorOutline", paths = arrayOf("M11 15h2v2h-2v-2zM11 7h2v6h-2L11 7zM11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0 -8 -3.58 -8 -8s3.58 -8 8 -8s8 3.58 8 8s-3.58 8 -8 8z")) }
    val Bolt by lazy { icon("Bolt", paths = arrayOf("M11 21h-1l1 -7H7.5c-0.88 0 -0.33 -0.75 -0.31 -0.78C8.48 10.94 10.42 7.54 13.01 3h1l-1 7h3.51c0.4 0 0.62 0.19 0.4 0.66C12.97 17.55 11 21 11 21z")) }
    val ViewQuilt by lazy { icon("ViewQuilt", paths = arrayOf("M3 5v14h18V5H3zM8.33 17H5V7h3.33V17zM13.67 17h-3.33v-4h3.33V17zM19 17h-3.33v-4H19V17zM19 11h-8.67V7H19V11z")) }
    val Key by lazy { icon("Key", paths = arrayOf("M21 10h-8.35C11.83 7.67 9.61 6 7 6c-3.31 0 -6 2.69 -6 6s2.69 6 6 6c2.61 0 4.83 -1.67 5.65 -4H13l2 2l2 -2l2 2l4 -4.04L21 10zM7 15c-1.65 0 -3 -1.35 -3 -3c0 -1.65 1.35 -3 3 -3s3 1.35 3 3C10 13.65 8.65 15 7 15z")) }
    val Inbox by lazy { icon("Inbox", paths = arrayOf("M19 3L5 3c-1.1 0 -2 0.9 -2 2v14c0 1.1 0.89 2 2 2h14c1.1 0 2 -0.9 2 -2L21 5c0 -1.1 -0.9 -2 -2 -2zM19 19L5 19v-3h3.56c0.69 1.19 1.97 2 3.45 2s2.75 -0.81 3.45 -2L19 16v3zM19 14h-4.99c0 1.1 -0.9 2 -2 2s-2 -0.9 -2 -2L5 14L5 5h14v9z")) }
    val Notifications by lazy { icon("Notifications", paths = arrayOf("M12 22c1.1 0 2 -0.9 2 -2h-4c0 1.1 0.9 2 2 2zM18 16v-5c0 -3.07 -1.63 -5.64 -4.5 -6.32L13.5 4c0 -0.83 -0.67 -1.5 -1.5 -1.5s-1.5 0.67 -1.5 1.5v0.68C7.64 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2 -2zM16 17L8 17v-6c0 -2.48 1.51 -4.5 4 -4.5s4 2.02 4 4.5v6z")) }
    val Folder by lazy { icon("Folder", paths = arrayOf("M9.17 6l2 2H20v10H4V6h5.17M10 4H4c-1.1 0 -1.99 0.9 -1.99 2L2 18c0 1.1 0.9 2 2 2h16c1.1 0 2 -0.9 2 -2V8c0 -1.1 -0.9 -2 -2 -2h-8l-2 -2z")) }
    val AddCircle by lazy { icon("AddCircle", paths = arrayOf("M13 7h-2v4L7 11v2h4v4h2v-4h4v-2h-4L13 7zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2zM12 20c-4.41 0 -8 -3.59 -8 -8s3.59 -8 8 -8s8 3.59 8 8s-3.59 8 -8 8z")) }
    val RemoveCircle by lazy { icon("RemoveCircle", paths = arrayOf("M7 11v2h10v-2L7 11zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10s10 -4.48 10 -10S17.52 2 12 2zM12 20c-4.41 0 -8 -3.59 -8 -8s3.59 -8 8 -8s8 3.59 8 8s-3.59 8 -8 8z")) }
    val ReceiptLong by lazy { icon("ReceiptLong", mirrored = true, paths = arrayOf("M19.5 3.5L18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2L7.5 3.5L6 2v14H3v3c0 1.66 1.34 3 3 3h12c1.66 0 3 -1.34 3 -3V2L19.5 3.5zM15 20H6c-0.55 0 -1 -0.45 -1 -1v-1h10V20zM19 19c0 0.55 -0.45 1 -1 1s-1 -0.45 -1 -1v-3H8V5h11V19z", "M9 7h6v2h-6z", "M16 7h2v2h-2z", "M9 10h6v2h-6z", "M16 10h2v2h-2z")) }
    val ReceiptLongFilled by lazy { icon("ReceiptLongFilled", mirrored = true, paths = arrayOf("M19.5 3.5L18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2L7.5 3.5L6 2v14H3v3c0 1.66 1.34 3 3 3h12c1.66 0 3 -1.34 3 -3V2L19.5 3.5zM19 19c0 0.55 -0.45 1 -1 1s-1 -0.45 -1 -1v-3H8V5h11V19z", "M9 7h6v2h-6z", "M16 7h2v2h-2z", "M9 10h6v2h-6z", "M16 10h2v2h-2z")) }
    val Candlestick by lazy { icon("Candlestick", paths = arrayOf("M9 4H7v2H5v12h2v2h2v-2h2V6H9V4zM9 16H7V8h2V16z", "M19 8h-2V4h-2v4h-2v7h2v5h2v-5h2V8zM17 13h-2v-3h2V13z")) }
    val CandlestickFilled by lazy { icon("CandlestickFilled", paths = arrayOf("M9 4l-2 0l0 2l-2 0l0 12l2 0l0 2l2 0l0 -2l2 0l0 -12l-2 0z", "M19 8l-2 0l0 -4l-2 0l0 4l-2 0l0 7l2 0l0 5l2 0l0 -5l2 0z")) }
    val TrendingUp by lazy { icon("TrendingUp", mirrored = true, paths = arrayOf("M16 6l2.29 2.29l-4.88 4.88l-4 -4L2 16.59L3.41 18l6 -6l4 4l6.3 -6.29L22 12V6h-6z")) }
    val Settings by lazy { icon("Settings", paths = arrayOf("M19.43 12.98c0.04 -0.32 0.07 -0.64 0.07 -0.98c0 -0.34 -0.03 -0.66 -0.07 -0.98l2.11 -1.65c0.19 -0.15 0.24 -0.42 0.12 -0.64l-2 -3.46c-0.09 -0.16 -0.26 -0.25 -0.44 -0.25c-0.06 0 -0.12 0.01 -0.17 0.03l-2.49 1c-0.52 -0.4 -1.08 -0.73 -1.69 -0.98l-0.38 -2.65C14.46 2.18 14.25 2 14 2h-4c-0.25 0 -0.46 0.18 -0.49 0.42l-0.38 2.65c-0.61 0.25 -1.17 0.59 -1.69 0.98l-2.49 -1c-0.06 -0.02 -0.12 -0.03 -0.18 -0.03c-0.17 0 -0.34 0.09 -0.43 0.25l-2 3.46c-0.13 0.22 -0.07 0.49 0.12 0.64l2.11 1.65c-0.04 0.32 -0.07 0.65 -0.07 0.98c0 0.33 0.03 0.66 0.07 0.98l-2.11 1.65c-0.19 0.15 -0.24 0.42 -0.12 0.64l2 3.46c0.09 0.16 0.26 0.25 0.44 0.25c0.06 0 0.12 -0.01 0.17 -0.03l2.49 -1c0.52 0.4 1.08 0.73 1.69 0.98l0.38 2.65c0.03 0.24 0.24 0.42 0.49 0.42h4c0.25 0 0.46 -0.18 0.49 -0.42l0.38 -2.65c0.61 -0.25 1.17 -0.59 1.69 -0.98l2.49 1c0.06 0.02 0.12 0.03 0.18 0.03c0.17 0 0.34 -0.09 0.43 -0.25l2 -3.46c0.12 -0.22 0.07 -0.49 -0.12 -0.64l-2.11 -1.65zM17.45 11.27c0.04 0.31 0.05 0.52 0.05 0.73c0 0.21 -0.02 0.43 -0.05 0.73l-0.14 1.13l0.89 0.7l1.08 0.84l-0.7 1.21l-1.27 -0.51l-1.04 -0.42l-0.9 0.68c-0.43 0.32 -0.84 0.56 -1.25 0.73l-1.06 0.43l-0.16 1.13l-0.2 1.35h-1.4l-0.19 -1.35l-0.16 -1.13l-1.06 -0.43c-0.43 -0.18 -0.83 -0.41 -1.23 -0.71l-0.91 -0.7l-1.06 0.43l-1.27 0.51l-0.7 -1.21l1.08 -0.84l0.89 -0.7l-0.14 -1.13c-0.03 -0.31 -0.05 -0.54 -0.05 -0.74s0.02 -0.43 0.05 -0.73l0.14 -1.13l-0.89 -0.7l-1.08 -0.84l0.7 -1.21l1.27 0.51l1.04 0.42l0.9 -0.68c0.43 -0.32 0.84 -0.56 1.25 -0.73l1.06 -0.43l0.16 -1.13l0.2 -1.35h1.39l0.19 1.35l0.16 1.13l1.06 0.43c0.43 0.18 0.83 0.41 1.23 0.71l0.91 0.7l1.06 -0.43l1.27 -0.51l0.7 1.21l-1.07 0.85l-0.89 0.7l0.14 1.13zM12 8c-2.21 0 -4 1.79 -4 4s1.79 4 4 4s4 -1.79 4 -4s-1.79 -4 -4 -4zM12 14c-1.1 0 -2 -0.9 -2 -2s0.9 -2 2 -2s2 0.9 2 2s-0.9 2 -2 2z")) }
    val SettingsFilled by lazy { icon("SettingsFilled", paths = arrayOf("M19.14 12.94c0.04 -0.3 0.06 -0.61 0.06 -0.94c0 -0.32 -0.02 -0.64 -0.07 -0.94l2.03 -1.58c0.18 -0.14 0.23 -0.41 0.12 -0.61l-1.92 -3.32c-0.12 -0.22 -0.37 -0.29 -0.59 -0.22l-2.39 0.96c-0.5 -0.38 -1.03 -0.7 -1.62 -0.94L14.4 2.81c-0.04 -0.24 -0.24 -0.41 -0.48 -0.41h-3.84c-0.24 0 -0.43 0.17 -0.47 0.41L9.25 5.35C8.66 5.59 8.12 5.92 7.63 6.29L5.24 5.33c-0.22 -0.08 -0.47 0 -0.59 0.22L2.74 8.87C2.62 9.08 2.66 9.34 2.86 9.48l2.03 1.58C4.84 11.36 4.8 11.69 4.8 12s0.02 0.64 0.07 0.94l-2.03 1.58c-0.18 0.14 -0.23 0.41 -0.12 0.61l1.92 3.32c0.12 0.22 0.37 0.29 0.59 0.22l2.39 -0.96c0.5 0.38 1.03 0.7 1.62 0.94l0.36 2.54c0.05 0.24 0.24 0.41 0.48 0.41h3.84c0.24 0 0.44 -0.17 0.47 -0.41l0.36 -2.54c0.59 -0.24 1.13 -0.56 1.62 -0.94l2.39 0.96c0.22 0.08 0.47 0 0.59 -0.22l1.92 -3.32c0.12 -0.22 0.07 -0.47 -0.12 -0.61L19.14 12.94zM12 15.6c-1.98 0 -3.6 -1.62 -3.6 -3.6s1.62 -3.6 3.6 -3.6s3.6 1.62 3.6 3.6S13.98 15.6 12 15.6z")) }
}
