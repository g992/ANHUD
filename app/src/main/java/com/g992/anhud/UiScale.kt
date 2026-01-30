package com.g992.anhud

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import kotlin.math.roundToInt

object UiScale {
    // Increase overall UI scale for all activities (dp + sp).
    private const val UI_SCALE = 2.0f

    fun wrap(base: Context): Context {
        if (UI_SCALE == 1f) {
            return base
        }
        val res = base.resources
        val metrics = res.displayMetrics
        val config = Configuration(res.configuration)
        val targetDensityDpi = (metrics.densityDpi * UI_SCALE).roundToInt()
        if (config.densityDpi == targetDensityDpi) {
            return base
        }
        config.densityDpi = targetDensityDpi
        val context = base.createConfigurationContext(config)
        val newMetrics: DisplayMetrics = context.resources.displayMetrics
        newMetrics.density = metrics.density * UI_SCALE
        newMetrics.scaledDensity = metrics.scaledDensity * UI_SCALE
        newMetrics.densityDpi = targetDensityDpi
        return context
    }
}
