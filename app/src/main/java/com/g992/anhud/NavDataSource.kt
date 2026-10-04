package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date

/**
 * External turn-by-turn data provider. At most one is active; [NONE] keeps the stock
 * Yandex/notification pipeline as the only source.
 */
enum class NavDataSource(
    val prefValue: String,
    /** Value written to [NavigationHudState.source] while this provider owns the route. */
    val storeSource: String,
    @StringRes val badgeRes: Int
) {
    NONE("none", "", 0),
    CARPLAY("carplay", "carplay", R.string.nav_data_source_badge_carplay),
    ANDROID_AUTO("android_auto", "headunit", R.string.nav_data_source_badge_android_auto);

    companion object {
        fun fromPref(value: String?): NavDataSource =
            values().firstOrNull { it.prefValue == value } ?: NONE

        fun fromStoreSource(source: String): NavDataSource? =
            values().firstOrNull { it != NONE && it.storeSource == source }
    }
}

/** Kept outside hud_overlay_prefs on purpose: presets must not switch the data provider. */
object NavDataSourcePrefs {
    private const val PREFS_NAME = "nav_data_source_prefs"
    private const val KEY_SOURCE = "source"

    fun source(context: Context): NavDataSource =
        NavDataSource.fromPref(prefs(context).getString(KEY_SOURCE, null))

    fun setSource(context: Context, source: NavDataSource) {
        prefs(context).edit().putString(KEY_SOURCE, source.prefValue).apply()
        YandexVisualStore.setSourceEnabled(source == NavDataSource.NONE)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** Snapshot of the route as reported by an external provider, already formatted for the HUD. */
data class ProviderNavUpdate(
    val maneuverType: String,
    val maneuverDistance: String,
    val street: String,
    val remainingDistance: String,
    val remainingTime: String,
    val arrival: String,
    val exitNumber: Int? = null
)

interface NavDataProvider {
    fun start()
    fun stop()
}

object NavDataProviders {
    private const val TAG = "NavDataProviders"

    @Volatile private var activeSource = NavDataSource.NONE
    private var activeProvider: NavDataProvider? = null

    /** Starts the provider selected in settings and stops any other one. Idempotent. */
    @Synchronized
    fun apply(context: Context) = applyWithCarPlayFactory(context) { CarPlayNavProvider(it) }

    @Synchronized
    internal fun applyWithCarPlayFactory(context: Context, carPlayFactory: (Context) -> NavDataProvider) {
        val appContext = context.applicationContext
        val desired = NavDataSourcePrefs.source(appContext)
        YandexVisualStore.setSourceEnabled(desired == NavDataSource.NONE)
        if (desired == activeSource && (desired == NavDataSource.NONE || activeProvider != null)) {
            return
        }
        val previous = activeSource
        stopActive(appContext, endRoute = false)
        // Any source switch wipes everything the previous source drew, whoever wrote it last.
        // Speed limit survives only when it cannot be stale: providers never write it, so after
        // one it can only be HUD Speed's; after Yandex it is Yandex's unless HUD Speed is preferred.
        NavigationReceiver.clearRouteForSourceSwitch(
            appContext,
            preserveSpeedLimit = previous != NavDataSource.NONE || OverlayPrefs.speedLimitFromHudSpeed(appContext)
        )
        val provider = when (desired) {
            NavDataSource.NONE -> null
            NavDataSource.CARPLAY -> carPlayFactory(appContext)
            NavDataSource.ANDROID_AUTO -> AndroidAutoNavProvider(appContext)
        }
        activeSource = desired
        activeProvider = provider
        if (provider != null) {
            Log.d(TAG, "Starting provider $desired")
            UiLogStore.append(LogCategory.NAVIGATION, "источник данных: ${desired.prefValue}")
            provider.start()
        } else {
            UiLogStore.append(LogCategory.NAVIGATION, "источник данных: штатный")
        }
    }

    @Synchronized
    fun retryCarPlayPatch() {
        (activeProvider as? CarPlayNavProvider)?.requestPatch()
    }

    @Synchronized
    fun stopAll(context: Context) {
        stopActive(context.applicationContext)
        activeSource = NavDataSource.NONE
    }

    private fun stopActive(context: Context, endRoute: Boolean = true) {
        val provider = activeProvider ?: return
        Log.d(TAG, "Stopping provider $activeSource")
        provider.stop()
        activeProvider = null
        if (endRoute && NavigationHudStore.snapshot().source == activeSource.storeSource) {
            NavigationReceiver.endProviderNavigation(context, activeSource, "источник данных отключён")
        }
    }

    /** True while the selected provider holds an active route, so Yandex must not overwrite it. */
    fun ownsActiveRoute(): Boolean {
        val source = activeSource
        if (source == NavDataSource.NONE) {
            return false
        }
        val state = NavigationHudStore.snapshot()
        return state.source == source.storeSource && state.routeActive == true
    }
}

object ProviderNavFormat {
    private const val ICON_SIZE_PX = 128
    private val iconCache = mutableMapOf<String, Bitmap>()

    fun normalize(value: String?): String =
        value.orEmpty().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    fun arrival(seconds: Long, now: Long = System.currentTimeMillis()): String {
        if (seconds <= 0 || seconds > (Long.MAX_VALUE - now) / 1000) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now + seconds * 1000))
    }

    fun meters(meters: Int): String {
        if (meters < 0) {
            return ""
        }
        val rounded = roundMeters(meters)
        return when {
            rounded < 1000 -> "$rounded м"
            meters < 10_000 -> String.format(Locale.US, "%.1f", meters / 1000.0).replace('.', ',') + " км"
            else -> "${Math.round(meters / 1000.0)} км"
        }
    }

    /** Re-rounds a provider distance string ("648 м", "1,2 км") with the same steps as [meters]. */
    fun roundedDistance(text: String): String {
        val trimmed = normalize(text)
        if (trimmed.isEmpty()) {
            return ""
        }
        val parsed = distanceMeters(trimmed, allowUnitless = true) ?: return trimmed
        return meters(parsed).ifBlank { trimmed }
    }

    fun seconds(seconds: Long): String {
        if (seconds <= 0) {
            return ""
        }
        val totalMinutes = seconds / 60 + if (seconds % 60 > 0) 1 else 0
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
    }

    fun unitOf(text: String): String {
        val match = Regex("[\\p{L}]+\\.?$").find(text.trim()) ?: return ""
        return match.value
    }

    /** CarPlay can send imperial units and localized digit groups, e.g. "1 500 фт". */
    fun distanceMeters(text: String, allowUnitless: Boolean = false): Int? {
        val number = "([0-9]+(?:[\\s\u00a0\u202f][0-9]{3})*(?:[.,][0-9]+)?)"
        val units = "(км|km|миль|miles?|mi|ярд|yd|фт|ft|м|m)(?![\\p{L}\\d])"
        val normalized = text.lowercase(Locale.ROOT)
        val match = Regex("$number\\s*$units").find(normalized)
        val raw = match?.groupValues?.get(1)
            ?: if (allowUnitless) Regex(number).find(normalized)?.groupValues?.get(1) else null
        val value = raw?.replace(Regex("\\s|[\u00a0\u202f]"), "")?.replace(',', '.')?.toDoubleOrNull()
            ?: return null
        val multiplier = when (match?.groupValues?.get(2)) {
            "км", "km" -> 1000.0
            "миль", "mi", "mile", "miles" -> 1609.344
            "ярд", "yd" -> 0.9144
            "фт", "ft" -> 0.3048
            else -> 1.0
        }
        return (value * multiplier).toInt().coerceAtLeast(0)
    }

    /** Renders a context_ra_* drawable so the overlay and arrow block need no provider-specific code. */
    @Synchronized
    fun maneuverBitmap(context: Context, maneuverType: String, exitNumber: Int? = null): Bitmap? {
        if (maneuverType.isBlank()) {
            return null
        }
        val exit = exitNumber?.takeIf { it in 1..19 && maneuverType == "context_ra_out_circular_movement" }
        val cacheKey = "$maneuverType:$exit"
        iconCache[cacheKey]?.let { return it }
        val resId = context.resources.getIdentifier(maneuverType, "drawable", context.packageName)
        if (resId == 0) {
            return null
        }
        val drawable = AppCompatResources.getDrawable(context, resId) ?: return null
        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
        val canvas = Canvas(bitmap)
        drawable.draw(canvas)
        if (exit != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = if (exit < 10) 36f else 30f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val baseline = 64f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
            canvas.drawText(exit.toString(), 56f, baseline, paint)
        }
        iconCache[cacheKey] = bitmap
        return bitmap
    }

    private fun roundMeters(meters: Int): Int = when {
        meters < 100 -> (meters + 5) / 10 * 10
        meters < 500 -> (meters + 25) / 50 * 50
        else -> (meters + 50) / 100 * 100
    }
}
