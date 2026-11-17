package com.unitrading.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Distinguishes touch layouts from desktop even when a window or tablet changes size. */
internal enum class ChartPlatform { ANDROID, IOS, DESKTOP }
internal expect val nativeChartPlatform: ChartPlatform
internal val LocalChartPlatform = staticCompositionLocalOf { nativeChartPlatform }
