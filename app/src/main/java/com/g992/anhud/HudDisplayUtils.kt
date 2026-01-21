package com.g992.anhud

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

object HudDisplayUtils {
    fun resolveDisplay(context: Context, displayId: Int): Display? {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        if (displayId != OverlayPrefs.DISPLAY_ID_AUTO) {
            return displayManager.getDisplay(displayId)
        }
        val presentation = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (presentation.isNotEmpty()) {
            return presentation[0]
        }
        return displayManager.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY
        }
    }

    fun availableDisplays(context: Context): List<Display> {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        return displayManager.displays.sortedBy { it.displayId }
    }

    fun displaySize(display: Display): Point {
        val size = Point()
        display.getRealSize(size)
        return size
    }

}
