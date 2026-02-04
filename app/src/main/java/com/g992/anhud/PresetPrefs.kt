package com.g992.anhud

import android.content.Context

object PresetPrefs {
    private const val PREFS_NAME = "preset_prefs"
    private const val KEY_ACTIVE_PRESET_ID = "active_preset_id"

    fun activePresetId(context: Context): String? {
        return prefs(context).getString(KEY_ACTIVE_PRESET_ID, null)?.takeIf { it.isNotBlank() }
    }

    fun setActivePresetId(context: Context, presetId: String) {
        prefs(context).edit().putString(KEY_ACTIVE_PRESET_ID, presetId).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
