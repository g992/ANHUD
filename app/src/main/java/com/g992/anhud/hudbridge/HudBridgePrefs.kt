package com.g992.anhud.hudbridge

import android.content.Context

/** Kept apart from overlay prefs: presets clear those, and this flag belongs to the head unit. */
object HudBridgePrefs {
    private const val PREFS_NAME = "hud_bridge_prefs"
    private const val KEY_ENABLED = "background_render_enabled"

    fun enabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
