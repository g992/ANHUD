package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log

class NavigationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!NavigationAppGate.shouldAllow(context)) {
            Log.d(TAG, "Ignoring nav update while app closed: ${intent.action}")
            return
        }
        when (intent.action) {
            ACTION_NAV_UPDATE, ACTION_NAV_UPDATE_DEBUG -> {
                val update = NavigationUpdate(
                    title = normalizeText(intent.getStringExtra(EXTRA_TITLE).orEmpty()),
                    text = normalizeText(intent.getStringExtra(EXTRA_TEXT).orEmpty()),
                    subtext = normalizeText(intent.getStringExtra(EXTRA_SUBTEXT).orEmpty()),
                    speedLimit = normalizeText(intent.getStringExtra(EXTRA_SPEED_LIMIT).orEmpty()),
                    routeActive = intent.getBooleanExtra(EXTRA_ROUTE_ACTIVE, false),
                    source = normalizeText(intent.getStringExtra(EXTRA_SOURCE).orEmpty()),
                    timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis()),
                    hasImage = intent.getBooleanExtra(EXTRA_HAS_IMAGE, false)
                )
                Log.d(TAG, "Navigation update: $update")
                UiLogStore.append(
                    LogCategory.NAVIGATION,
                    "обновление title=\"${update.title}\" text=\"${update.text}\" subtext=\"${update.subtext}\" " +
                        "speedLimit=\"${update.speedLimit}\" active=${update.routeActive} source=\"${update.source}\""
                )
                val primary = update.title.ifBlank { update.text }
                val secondary = listOf(update.text, update.subtext)
                    .filter { it.isNotBlank() && it != primary }
                    .joinToString(" • ")
                NavigationHudStore.update { state ->
                    state.copy(
                        primaryText = primary,
                        secondaryText = secondary,
                        speedLimit = update.speedLimit,
                        source = update.source.ifBlank { state.source },
                        routeActive = update.routeActive,
                        lastUpdated = update.timestamp,
                        lastAction = intent.action.orEmpty(),
                        rawTitle = update.title,
                        rawText = update.text,
                        rawSubtext = update.subtext,
                        rawSpeedLimit = update.speedLimit
                    )
                }
            }
            ACTION_NAV_ENDED, ACTION_NAV_ENDED_DEBUG -> {
                Log.d(TAG, "Navigation ended")
                UiLogStore.append(LogCategory.NAVIGATION, "завершено")
                NavigationHudStore.reset(intent.action.orEmpty())
            }
            ACTION_YANDEX_MANEUVER -> {
                val bitmap = getBitmapExtra(intent, EXTRA_MANEUVER_BITMAP)
                val size = if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "none"
                Log.d(TAG, "Yandex maneuver bitmap: $size")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс маневр bitmap=$size")
                NavigationHudStore.update { state ->
                    state.copy(
                        maneuverBitmap = bitmap ?: state.maneuverBitmap,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = intent.action.orEmpty()
                    )
                }
            }
            ACTION_YANDEX_NEXT_TEXT -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_NEXT_TEXT).orEmpty())
                Log.d(TAG, "Yandex next text: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс next_text=\"$raw\"")
                NavigationHudStore.update { state ->
                    val unit = extractTrailingUnit(raw)
                    state.copy(
                        primaryText = raw.takeIf { it.isNotBlank() } ?: state.primaryText,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = intent.action.orEmpty(),
                        rawNextText = raw,
                        distanceUnit = unit.ifBlank { state.distanceUnit }
                    )
                }
            }
            ACTION_YANDEX_NEXT_STREET -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_NEXT_STREET).orEmpty())
                Log.d(TAG, "Yandex next street: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс next_street=\"$raw\"")
                NavigationHudStore.update { state ->
                    state.copy(
                        secondaryText = raw.takeIf { it.isNotBlank() } ?: state.secondaryText,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = intent.action.orEmpty(),
                        rawNextStreet = raw
                    )
                }
            }
            ACTION_YANDEX_SPEEDLIMIT -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_SPEEDLIMIT_TEXT).orEmpty())
                Log.d(TAG, "Yandex speedlimit: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс speedlimit=\"$raw\"")
                NavigationHudStore.update { state ->
                    state.copy(
                        speedLimit = raw.takeIf { it.isNotBlank() } ?: state.speedLimit,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = intent.action.orEmpty(),
                        rawSpeedLimit = raw
                    )
                }
            }
            ACTION_YANDEX_ARRIVAL -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_ARRIVAL_TEXT).orEmpty())
                Log.d(TAG, "Yandex arrival: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс arrival=\"$raw\"")
                NavigationHudStore.update { state ->
                    state.copy(
                        arrival = raw.takeIf { it.isNotBlank() } ?: state.arrival,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = intent.action.orEmpty(),
                        rawArrival = raw
                    )
                }
            }
            ACTION_YANDEX_DISTANCE -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_DISTANCE_TEXT).orEmpty())
                Log.d(TAG, "Yandex distance: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс distance=\"$raw\"")
                NavigationHudStore.update { state ->
                    state.copy(
                        distance = raw.takeIf { it.isNotBlank() } ?: state.distance,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = intent.action.orEmpty(),
                        rawDistance = raw
                    )
                }
            }
            ACTION_YANDEX_TIME -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_TIME_TEXT).orEmpty())
                Log.d(TAG, "Yandex time: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс time=\"$raw\"")
                NavigationHudStore.update { state ->
                    state.copy(
                        time = raw.takeIf { it.isNotBlank() } ?: state.time,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = intent.action.orEmpty(),
                        rawTime = raw
                    )
                }
            }
            ACTION_YANDEX_TRAFFICLIGHT -> {
                val rawColor = normalizeText(intent.getStringExtra(EXTRA_TRAFFICLIGHT_COLOR).orEmpty())
                val rawCountdown = normalizeText(intent.getStringExtra(EXTRA_TRAFFICLIGHT_COUNTDOWN).orEmpty())
                val timestamp = intent.getLongExtra(EXTRA_TRAFFICLIGHT_TIMESTAMP, 0L)
                Log.d(TAG, "Yandex trafficlight: color=$rawColor countdown=$rawCountdown ts=$timestamp")
                UiLogStore.append(
                    LogCategory.NAVIGATION,
                    "яндекс trafficlight color=\"$rawColor\" countdown=\"$rawCountdown\" ts=$timestamp"
                )
                NavigationHudStore.update { state ->
                    state.copy(
                        trafficLight = rawColor.takeIf { it.isNotBlank() } ?: state.trafficLight,
                        trafficCountdown = rawCountdown.takeIf { it.isNotBlank() } ?: state.trafficCountdown,
                        source = SOURCE_YANDEX,
                        lastUpdated = if (timestamp > 0L) timestamp else System.currentTimeMillis(),
                        lastAction = intent.action.orEmpty(),
                        rawTrafficLight = rawColor,
                        rawTrafficCountdown = rawCountdown
                    )
                }
            }
        }
    }

    data class NavigationUpdate(
        val title: String,
        val text: String,
        val subtext: String,
        val speedLimit: String,
        val routeActive: Boolean,
        val source: String,
        val timestamp: Long,
        val hasImage: Boolean
    )

    companion object {
        private const val TAG = "NavigationReceiver"

        const val ACTION_NAV_UPDATE = "plus.monjaro.NAVIGATION_UPDATE"
        const val ACTION_NAV_ENDED = "plus.monjaro.NAVIGATION_ENDED"
        const val ACTION_NAV_UPDATE_DEBUG = "debug.monjaro.NAVIGATION_UPDATE"
        const val ACTION_NAV_ENDED_DEBUG = "debug.monjaro.NAVIGATION_ENDED"

        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SUBTEXT = "subtext"
        const val EXTRA_SPEED_LIMIT = "speedlimit"
        const val EXTRA_ROUTE_ACTIVE = "route_active"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_HAS_IMAGE = "has_image"

        const val ACTION_YANDEX_MANEUVER = "com.yandex.MANEUVER"
        const val ACTION_YANDEX_NEXT_TEXT = "com.yandex.NIXT"
        const val ACTION_YANDEX_NEXT_STREET = "com.yandex.NEXTSTREET"
        const val ACTION_YANDEX_SPEEDLIMIT = "com.yandex.SPEEDLIMIT"
        const val ACTION_YANDEX_ARRIVAL = "com.yandex.ARRIVAL"
        const val ACTION_YANDEX_DISTANCE = "com.yandex.DISTANCE"
        const val ACTION_YANDEX_TIME = "com.yandex.TIME"
        const val ACTION_YANDEX_TRAFFICLIGHT = "com.yandex.TRAFFICLIGHT"

        const val EXTRA_MANEUVER_BITMAP = "maneuver_bitmap"
        const val EXTRA_NEXT_TEXT = "next_text"
        const val EXTRA_NEXT_STREET = "next_street"
        const val EXTRA_SPEEDLIMIT_TEXT = "speedlimit_text"
        const val EXTRA_ARRIVAL_TEXT = "Arrival_text"
        const val EXTRA_DISTANCE_TEXT = "Distance_text"
        const val EXTRA_TIME_TEXT = "Time_text"
        const val EXTRA_TRAFFICLIGHT_COLOR = "signal_color"
        const val EXTRA_TRAFFICLIGHT_COUNTDOWN = "countdown"
        const val EXTRA_TRAFFICLIGHT_TIMESTAMP = "timestamp"
        private const val SOURCE_YANDEX = "yandex"

        private fun normalizeText(value: String): String {
            if (value.isBlank()) {
                return value
            }
            val normalized = value
                .replace('\u00A0', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
            return normalized
        }

        private fun extractTrailingUnit(text: String): String {
            val trimmed = text.trim()
            if (trimmed.isBlank()) {
                return ""
            }
            val match = Regex("[^0-9.,\\s]+\\s*$").find(trimmed)
            return match?.value?.trim().orEmpty()
        }

        private fun getBitmapExtra(intent: Intent, key: String): Bitmap? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(key, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(key)
            }
        }
    }
}
