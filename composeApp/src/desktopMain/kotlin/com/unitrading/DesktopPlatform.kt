package com.unitrading

import java.nio.file.Path

internal val isWindows = System.getProperty("os.name").startsWith("Windows")
internal val isMacOs = System.getProperty("os.name").startsWith("Mac")

/** The per-user folder the app's data lives under: `%LOCALAPPDATA%` on Windows, `~/Library/Application Support` on macOS. */
internal fun userDataRoot(): Path =
    if (isMacOs) Path.of(System.getProperty("user.home"), "Library", "Application Support")
    else Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"))
