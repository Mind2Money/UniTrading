package com.unitrading.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

internal actual fun panelResizeCursor(horizontal: Boolean): PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(if (horizontal) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
