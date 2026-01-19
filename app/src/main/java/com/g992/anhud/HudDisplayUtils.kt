package com.g992.anhud

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.content.ContextCompat

object HudDisplayUtils {
    const val VIRTUAL_DISPLAY_NAME = "ANHUD Virtual Display"

    fun resolveDisplay(context: Context, displayId: Int): Display? {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        if (displayId != OverlayPrefs.DISPLAY_ID_AUTO) {
            return displayManager.getDisplay(displayId)?.takeIf { it.name != VIRTUAL_DISPLAY_NAME }
        }
        val presentation = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filterNot { it.name == VIRTUAL_DISPLAY_NAME }
        if (presentation.isNotEmpty()) {
            return presentation[0]
        }
        return displayManager.displays.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY && it.name != VIRTUAL_DISPLAY_NAME
        }
    }

    fun availableDisplays(context: Context): List<Display> {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        return displayManager.displays
            .filterNot { it.name == VIRTUAL_DISPLAY_NAME }
            .sortedBy { it.displayId }
    }

    fun hasVirtualDisplayPermission(context: Context): Boolean {
        val captureOutput = ContextCompat.checkSelfPermission(
            context,
            PERMISSION_CAPTURE_VIDEO_OUTPUT
        )
        if (captureOutput == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        val captureSecure = ContextCompat.checkSelfPermission(
            context,
            PERMISSION_CAPTURE_SECURE_VIDEO_OUTPUT
        )
        return captureSecure == PackageManager.PERMISSION_GRANTED
    }

    fun displaySize(display: Display): Point {
        val size = Point()
        display.getRealSize(size)
        return size
    }

    private const val PERMISSION_CAPTURE_VIDEO_OUTPUT = "android.permission.CAPTURE_VIDEO_OUTPUT"
    private const val PERMISSION_CAPTURE_SECURE_VIDEO_OUTPUT =
        "android.permission.CAPTURE_SECURE_VIDEO_OUTPUT"
}
