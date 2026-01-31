package com.g992.anhud

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

object HudDisplayUtils {
    private const val PREFERRED_DISPLAY_ID = 1

    fun resolveDisplay(context: Context, displayId: Int): Display? {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val resolved = displayManager.getDisplay(displayId)
        if (resolved != null) {
            return resolved
        }
        val fallbackId = preferredDisplayId(context)
        return displayManager.getDisplay(fallbackId)
    }

    fun preferredDisplayId(context: Context): Int {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        return if (displayManager.getDisplay(PREFERRED_DISPLAY_ID) != null) {
            PREFERRED_DISPLAY_ID
        } else {
            Display.DEFAULT_DISPLAY
        }
    }

    fun hasDisplay(context: Context, displayId: Int): Boolean {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        return displayManager.getDisplay(displayId) != null
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
