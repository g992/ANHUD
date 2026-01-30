package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.util.Log
import java.util.Locale

private const val MIN_ARROW_NATIVE_UPDATE_INTERVAL_MS = 3000L

class NavigationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        if (action.startsWith("com.yandex.")) {
            Log.d(TAG, "Yandex intent: $action extras=${formatExtras(intent)}")
        } else {
            Log.d(TAG, "not Yandex intent: $action extras=${formatExtras(intent)}")

        }
        when (action) {
            ACTION_NAV_UPDATE, ACTION_NAV_UPDATE_DEBUG -> {
                Log.d(
                    TAG,
                    "route_active extra present=${intent.hasExtra(EXTRA_ROUTE_ACTIVE)} value=" +
                        "${intent.getBooleanExtra(EXTRA_ROUTE_ACTIVE, false)}"
                )
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
                if (intent.hasExtra(EXTRA_ROUTE_ACTIVE) && !update.routeActive) {
                    UiLogStore.append(LogCategory.NAVIGATION, "маршрут завершен (route_active=false)")
                    NavigationHudStore.reset(
                        intent.action.orEmpty(),
                        update.timestamp,
                        preserveSpeedLimit = true
                    )
                    return
                }
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
                    lastAction = action,
                        rawTitle = update.title,
                        rawText = update.text,
                        rawSubtext = update.subtext,
                        rawSpeedLimit = update.speedLimit
                    )
                }
            }
            ACTION_YANDEX_MANEUVER -> {
                val bitmap = getBitmapExtra(intent, EXTRA_MANEUVER_BITMAP)
                val size = if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "none"
                val maneuverTypeFromExtra = normalizeText(intent.getStringExtra(EXTRA_MANEUVER_TYPE).orEmpty())
                val useExtraType = maneuverTypeFromExtra.isNotBlank()
                val maneuverTypePredicted = if (useExtraType) "skipped" else bitmap?.let {
                    predictManeuverType(context, it)
                }.orEmpty()
                val maneuverType = if (useExtraType) maneuverTypeFromExtra else maneuverTypePredicted
                Log.d(TAG, "Yandex maneuver bitmap: $size typeFromExtra=\"$maneuverTypeFromExtra\" typePredicted=\"$maneuverTypePredicted\" final=\"$maneuverType\"")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс маневр bitmap=$size typeExtra=\"$maneuverTypeFromExtra\" typePredicted=\"$maneuverTypePredicted\" final=\"$maneuverType\"")
                var updated: NavigationHudState? = null
                NavigationHudStore.update { state ->
                    val next = state.copy(
                        maneuverBitmap = bitmap ?: state.maneuverBitmap,
                        maneuverType = maneuverType.ifBlank { state.maneuverType },
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action
                    )
                    updated = next
                    next
                }
                updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.MANEUVER) }
            }
            ACTION_YANDEX_NEXT_TEXT -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_NEXT_TEXT).orEmpty())
                Log.d(TAG, "Yandex next text: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс next_text=\"$raw\"")
                var updated: NavigationHudState? = null
                NavigationHudStore.update { state ->
                    val unit = extractTrailingUnit(raw)
                    val next = state.copy(
                        primaryText = raw.takeIf { it.isNotBlank() } ?: state.primaryText,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawNextText = raw,
                        distanceUnit = unit.ifBlank { state.distanceUnit }
                    )
                    updated = next
                    next
                }
                updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.DISTANCE) }
            }
            ACTION_YANDEX_NEXT_STREET -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_NEXT_STREET).orEmpty())
                Log.d(TAG, "Yandex next street: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс next_street=\"$raw\"")
                var updated: NavigationHudState? = null
                NavigationHudStore.update { state ->
                    val next = state.copy(
                        secondaryText = raw.takeIf { it.isNotBlank() } ?: state.secondaryText,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawNextStreet = raw
                    )
                    updated = next
                    next
                }
                updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.STREET) }
                scheduleStreetReset(context)
            }
            ACTION_YANDEX_SPEEDLIMIT -> {
                if (OverlayPrefs.speedLimitFromHudSpeed(context)) {
                    Log.d(TAG, "Yandex speedlimit ignored: HUD Speed preferred")
                    return
                }
                val raw = normalizeText(intent.getStringExtra(EXTRA_SPEEDLIMIT_TEXT).orEmpty())
                Log.d(TAG, "Yandex speedlimit: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс speedlimit=\"$raw\"")
                NavigationHudStore.update { state ->
                    state.copy(
                        speedLimit = raw.takeIf { it.isNotBlank() } ?: state.speedLimit,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawSpeedLimit = raw
                    )
                }
            }
            ACTION_YANDEX_ARRIVAL -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_ARRIVAL_TEXT).orEmpty())
                Log.d(TAG, "Yandex arrival: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс прибытие: $raw")
                // Don't end navigation here - only end when notification is removed
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
                        lastAction = action,
                        rawDistance = raw
                    )
                }
            }
            ACTION_YANDEX_TIME -> {
                val raw = normalizeText(intent.getStringExtra(EXTRA_TIME_TEXT).orEmpty())
                Log.d(TAG, "Yandex time: $raw")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс time=\"$raw\"")
                var updated: NavigationHudState? = null
                NavigationHudStore.update { state ->
                    val next = state.copy(
                        time = raw.takeIf { it.isNotBlank() } ?: state.time,
                        source = SOURCE_YANDEX,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action,
                        rawTime = raw
                    )
                    updated = next
                    next
                }
                updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.TIME) }
            }
            ACTION_YANDEX_NAV_ACTIVE -> {
                val isActive = intent.getBooleanExtra(EXTRA_NAV_IS_ACTIVE, false)
                Log.d(TAG, "Yandex navigation state: isActive=$isActive")
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс состояние навигации: ${if (isActive) "активна" else "завершена"}")
                // NAV_ACTIVE is ignored for navigation state; keep for diagnostics only.
            }
            ACTION_YANDEX_ROADCAMERA -> {
                val cameraId = normalizeText(intent.getStringExtra(EXTRA_CAMERA_ID).orEmpty())
                val distance = normalizeText(intent.getStringExtra(EXTRA_CAMERA_DISTANCE).orEmpty())
                val icon = getBitmapExtra(intent, EXTRA_CAMERA_ICON)
                val iconSize = if (icon != null) "${icon.width}x${icon.height}" else "none"

                if (cameraId.isBlank()) {
                    Log.d(TAG, "Yandex road camera: hidden")
                    UiLogStore.append(LogCategory.NAVIGATION, "яндекс дорожная камера: скрыта")
                    NavigationHudStore.update { state ->
                        state.copy(
                            roadCameraId = null,
                            roadCameraDistance = null,
                            roadCameraIcon = null,
                            lastUpdated = System.currentTimeMillis(),
                            lastAction = action
                        )
                    }
                } else {
                    Log.d(TAG, "Yandex road camera: id=\"$cameraId\" distance=\"$distance\" icon=$iconSize")
                    UiLogStore.append(LogCategory.NAVIGATION, "яндекс дорожная камера: id=\"$cameraId\" distance=\"$distance\" icon=$iconSize")
                    NavigationHudStore.update { state ->
                        state.copy(
                            roadCameraId = cameraId,
                            roadCameraDistance = distance,
                            roadCameraIcon = icon,
                            source = SOURCE_YANDEX,
                            lastUpdated = System.currentTimeMillis(),
                            lastAction = action
                        )
                    }
                }
            }
            ACTION_YANDEX_TRAFFICLIGHT -> {
                val trafficLightId = intent.getIntExtra(EXTRA_TRAFFIC_LIGHT_ID, 0)
                val isVisible = intent.getBooleanExtra(EXTRA_TRAFFIC_IS_VISIBLE, true)
                val signalColor = normalizeText(intent.getStringExtra(EXTRA_TRAFFIC_SIGNAL_COLOR).orEmpty())
                val countdown = normalizeText(intent.getStringExtra(EXTRA_TRAFFIC_COUNTDOWN).orEmpty())
                val timestamp = intent.getLongExtra(EXTRA_TRAFFIC_TIMESTAMP, 0L)
                val arrowBitmap = getBitmapExtra(intent, EXTRA_TRAFFIC_ARROW_BITMAP)
                val arrowDirection = normalizeText(intent.getStringExtra(EXTRA_TRAFFIC_ARROW_DIRECTION).orEmpty())
                val arrowSize = if (arrowBitmap != null) "${arrowBitmap.width}x${arrowBitmap.height}" else "none"
                Log.d(
                    TAG,
                    "Yandex traffic light: color=\"$signalColor\" countdown=\"$countdown\" timestamp=$timestamp " +
                        "arrow=\"$arrowDirection\" arrowBitmap=$arrowSize id=$trafficLightId visible=$isVisible"
                )
                UiLogStore.append(LogCategory.NAVIGATION, "яндекс светофор: цвет=\"$signalColor\" обратный_отсчет=\"$countdown\"")
                handleTrafficLightUpdate(
                    context = context,
                    action = action,
                    id = trafficLightId,
                    isVisible = isVisible,
                    signalColor = signalColor,
                    countdown = countdown,
                    arrowBitmap = arrowBitmap,
                    arrowDirection = arrowDirection,
                    timestamp = timestamp
                )
            }
            ACTION_YANDEX_ROUTE_POLYLINE -> {
                val routeActive = intent.getBooleanExtra(EXTRA_ROUTE_ACTIVE_FLAG, false)
                val routeId = normalizeText(intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty())
                val lats = intent.getDoubleArrayExtra(EXTRA_POLYLINE_LATS)
                val lons = intent.getDoubleArrayExtra(EXTRA_POLYLINE_LONS)
                val count = intent.getIntExtra(EXTRA_POLYLINE_COUNT, 0)

                val latsInfo = if (lats != null) "size=${lats.size} first=${lats.firstOrNull()}" else "null"
                val lonsInfo = if (lons != null) "size=${lons.size} first=${lons.firstOrNull()}" else "null"

                Log.d(TAG, "Yandex route polyline: active=$routeActive id=\"$routeId\" count=$count lats=[$latsInfo] lons=[$lonsInfo]")
                UiLogStore.append(
                    LogCategory.NAVIGATION,
                    "яндекс полилиния маршрута: active=$routeActive id=\"$routeId\" points=$count"
                )

                if (routeActive && lats != null && lons != null && count > 0) {
                    Log.d(TAG, "Route polyline: ${count} points received")
                    UiLogStore.append(LogCategory.NAVIGATION, "получена полилиния: $count точек")
                } else if (!routeActive) {
                    Log.d(TAG, "Route polyline: route cleared")
                    UiLogStore.append(LogCategory.NAVIGATION, "полилиния очищена (маршрут завершен)")
                }
            }
            ACTION_NATIVE_NAV_STOP -> {
                endNavigation(context, action, "штатная навигация: стоп")
            }
            ACTION_HUDSPEED_UPDATE -> {
                val hasCamera = intent.getBooleanExtra(HUDSPEED_HAS_CAMERA, false)

                val distance = intent.getIntExtra(HUDSPEED_DISTANCE, -1)
                val limit1 = intent.getIntExtra(HUDSPEED_LIMIT_1, -1)
                val limit2 = intent.getIntExtra(HUDSPEED_LIMIT_2, -1)
                val camType = intent.getIntExtra(HUDSPEED_CAM_TYPE, -1)
                val camFlag = intent.getIntExtra(HUDSPEED_CAM_FLAG, -1)

                Log.d(
                    TAG,
                    "HUDSPEED_UPDATE: hasCamera=$hasCamera distance=$distance limit1=$limit1 limit2=$limit2 camType=$camType camFlag=$camFlag extras=${formatExtras(intent)}"
                )

                val resolvedDistance = distance.takeIf { hasCamera && it >= 0 }
                val resolvedCamType = camType.takeIf { hasCamera && it >= 0 }
                val resolvedCamFlag = camFlag.takeIf { hasCamera && it >= 0 }
                val resolvedLimit1 = limit1.takeIf { hasCamera && it > 0 }
                val hudSpeedUpdatedAt = if (hasCamera) System.currentTimeMillis() else 0L
                NavigationHudStore.update { state ->
                    val useHudSpeedLimit = OverlayPrefs.speedLimitFromHudSpeed(context) && resolvedLimit1 != null
                    state.copy(
                        hudSpeedHasCamera = hasCamera,
                        hudSpeedDistanceMeters = resolvedDistance,
                        hudSpeedCamType = resolvedCamType,
                        hudSpeedCamFlag = resolvedCamFlag,
                        hudSpeedLimit1 = resolvedLimit1,
                        hudSpeedUpdatedAt = hudSpeedUpdatedAt,
                        speedLimit = if (useHudSpeedLimit) resolvedLimit1.toString() else state.speedLimit,
                        rawSpeedLimit = if (useHudSpeedLimit) resolvedLimit1.toString() else state.rawSpeedLimit,
                        source = SOURCE_HUDSPEED,
                        lastUpdated = System.currentTimeMillis(),
                        lastAction = action
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
        private const val NATIVE_NAV_DEBOUNCE_MS = 100L
        private const val STREET_RESET_DELAY_MS = 2000L
        private const val TRAFFIC_LIGHT_NO_COUNTDOWN_TTL_MS = 2000L

        private val nativeNavHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingNativeNavUpdate: Runnable? = null
        private var pendingStreetReset: Runnable? = null
        private val trafficLightHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var pendingTrafficLightCleanup: Runnable? = null
        private val activeTrafficLights = mutableMapOf<Int, TrafficLightInfo>()
        private var trafficLightContext: Context? = null

        @Volatile
        private var lastArrowNativeUpdateAt: Long = 0L
        @Volatile
        private var lastStreetUpdateAt: Long = 0L
        @Volatile
        private var lastNativeNavPayload: NativeNavPayload? = null

        const val ACTION_NAV_UPDATE = "plus.monjaro.NAVIGATION_UPDATE"
        const val ACTION_NAV_UPDATE_DEBUG = "debug.monjaro.NAVIGATION_UPDATE"

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
        const val ACTION_YANDEX_NAV_ACTIVE = "com.yandex.NAV_ACTIVE"
        const val ACTION_YANDEX_ROADCAMERA = "com.yandex.ROADCAMERA"
        const val ACTION_YANDEX_TRAFFICLIGHT = "com.yandex.TRAFFICLIGHT"
        const val ACTION_YANDEX_ROUTE_POLYLINE = "com.yandex.ROUTE_POLYLINE"
        const val ACTION_NATIVE_NAV_STOP = "com.g992.anhud.NATIVE_NAV_STOP"
        const val ACTION_HUDSPEED_UPDATE = "air.strelkasd.CAMERA_INFO_CHANGED"

        const val EXTRA_MANEUVER_BITMAP = "maneuver_bitmap"
        const val EXTRA_MANEUVER_TYPE = "maneuver_type"
        const val EXTRA_NEXT_TEXT = "next_text"
        const val EXTRA_NEXT_STREET = "next_street"
        const val EXTRA_SPEEDLIMIT_TEXT = "speedlimit_text"
        const val EXTRA_ARRIVAL_TEXT = "Arrival_text"
        const val EXTRA_DISTANCE_TEXT = "Distance_text"
        const val EXTRA_TIME_TEXT = "Time_text"
        const val EXTRA_NAV_IS_ACTIVE = "is_active"
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_CAMERA_DISTANCE = "distance_text"
        const val EXTRA_CAMERA_ICON = "camera_icon"
        const val EXTRA_TRAFFIC_SIGNAL_COLOR = "signal_color"
        const val EXTRA_TRAFFIC_COUNTDOWN = "countdown"
        const val EXTRA_TRAFFIC_TIMESTAMP = "timestamp"
        const val EXTRA_TRAFFIC_ARROW_BITMAP = "arrow_bitmap"
        const val EXTRA_TRAFFIC_ARROW_DIRECTION = "arrow_direction"
        const val EXTRA_TRAFFIC_LIGHT_ID = "traffic_light_id"
        const val EXTRA_TRAFFIC_IS_VISIBLE = "is_visible"
        const val EXTRA_ROUTE_ACTIVE_FLAG = "route_active"
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_POLYLINE_LATS = "polyline_lats"
        const val EXTRA_POLYLINE_LONS = "polyline_lons"
        const val EXTRA_POLYLINE_COUNT = "polyline_count"
        const val HUDSPEED_HAS_CAMERA = "hasCamera"
        const val HUDSPEED_DISTANCE = "distance"
        const val HUDSPEED_LIMIT_1 = "limit1"
        const val HUDSPEED_LIMIT_2 = "limit2"
        const val HUDSPEED_CAM_TYPE = "camType"
        const val HUDSPEED_CAM_FLAG = "camFlag"

        private const val SOURCE_YANDEX = "yandex"
        private const val SOURCE_HUDSPEED = "hudspeed"
        const val DEFAULT_NATIVE_TURN_ID = 101

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

        private fun formatExtras(intent: Intent): String {
            val extras = intent.extras ?: return "{}"
            val entries = extras.keySet().sorted().map { key ->
                val value = extras.get(key)
                val formatted = describeExtraValue(value)
                "$key=$formatted"
            }
            return "{${entries.joinToString(", ")}}"
        }

        private fun describeExtraValue(value: Any?): String {
            return when (value) {
                null -> "null"
                is Bitmap -> "Bitmap(${value.width}x${value.height}, ${value.config})"
                is Bundle -> "Bundle(keys=${value.keySet().sorted().joinToString(",")}, size=${value.size()})"
                is ByteArray -> "ByteArray(size=${value.size})"
                is IntArray -> "IntArray(size=${value.size})"
                is LongArray -> "LongArray(size=${value.size})"
                is FloatArray -> "FloatArray(size=${value.size})"
                is DoubleArray -> "DoubleArray(size=${value.size})"
                is BooleanArray -> "BooleanArray(size=${value.size})"
                is Array<*> -> {
                    val type = value.firstOrNull()?.javaClass?.simpleName ?: "Unknown"
                    "Array<$type>(size=${value.size})"
                }
                else -> value.toString().take(200)
            }
        }

        /**
         * Schedule native navigation update with debouncing.
         * Waits 100ms for more broadcasts to arrive, then sends everything as a batch.
         */
        private fun maybeUpdateNativeNavigation(
            context: Context,
            state: NavigationHudState,
            trigger: NativeNavUpdateTrigger
        ) {
            if (!OverlayPrefs.nativeNavEnabled(context)) {
                return
            }
            if (trigger == NativeNavUpdateTrigger.MANEUVER && !canSendArrowUpdate()) {
                return
            }

            // Cancel any pending update
            pendingNativeNavUpdate?.let { nativeNavHandler.removeCallbacks(it) }

            // Schedule new update after debounce delay
            val runnable = Runnable {
                pendingNativeNavUpdate = null
                sendNativeNavUpdate(context)
            }
            pendingNativeNavUpdate = runnable
            nativeNavHandler.postDelayed(runnable, NATIVE_NAV_DEBOUNCE_MS)
        }

        private fun sendNativeNavUpdate(context: Context) {
            val state = NavigationHudStore.snapshot()
            val maneuverType = state.maneuverType.trim()
            val street = state.rawNextStreet.trim()
            val distanceText = state.rawNextText.trim()
            val destinationDistanceText = state.rawDistance.trim()
            val timeText = state.rawTime.trim()
            val distanceMeters = parseDistanceMeters(distanceText) ?: 0
            val destinationDistanceMeters = parseDistanceMeters(destinationDistanceText) ?: 0
            val etaSeconds = parseEtaSeconds(timeText) ?: 0
            val turnId = resolveTurnId(context, maneuverType)
            val payload = NativeNavPayload(
                turnId = turnId,
                street = street,
                distanceToManeuverMeters = distanceMeters,
                distanceToDestinationMeters = destinationDistanceMeters,
                totalDistanceToDestinationMeters = destinationDistanceMeters,
                etaSeconds = etaSeconds
            )

            // Only start native navigation if we have actual navigation data
            val hasNavigationData = turnId != DEFAULT_NATIVE_TURN_ID ||
                distanceMeters > 0 ||
                destinationDistanceMeters > 0
            if (!hasNavigationData) {
                Log.d(TAG, "No navigation data yet, skipping native nav update")
                return
            }

            if (NativeNavigationController.isActive() && lastNativeNavPayload == payload) {
                Log.d(TAG, "Native nav update skipped (no changes)")
                return
            }

            NavigationHudStore.update { current ->
                if (current.nativeTurnId == turnId) {
                    current
                } else {
                    current.copy(nativeTurnId = turnId)
                }
            }
            if (!NativeNavigationController.isActive()) {
                Log.d(TAG, "Starting native navigation with data: turnId=$turnId dist=$distanceMeters")
                NativeNavigationController.startNavigation(context)
            }
            NativeNavigationController.updateNavigation(
                context = context,
                turnId = turnId,
                streetName = street,
                distanceToManeuverMeters = distanceMeters,
                distanceToDestinationMeters = destinationDistanceMeters,
                totalDistanceToDestinationMeters = destinationDistanceMeters,
                etaSeconds = etaSeconds
            )
            lastNativeNavPayload = payload
            Log.d(
                TAG,
                "Native nav update sent: turnId=$turnId street=$street dist=$distanceMeters dest=$destinationDistanceMeters eta=$etaSeconds"
            )
        }

        private fun canSendArrowUpdate(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastArrowNativeUpdateAt < MIN_ARROW_NATIVE_UPDATE_INTERVAL_MS) {
                return false
            }
            lastArrowNativeUpdateAt = now
            return true
        }

        private enum class NativeNavUpdateTrigger {
            MANEUVER,
            DISTANCE,
            STREET,
            TIME
        }

        private fun endNavigation(context: Context, action: String, reason: String) {
            Log.d(TAG, "Navigation ended: $reason")
            UiLogStore.append(LogCategory.NAVIGATION, reason)
            // Only stop native navigation if it was enabled
            if (OverlayPrefs.nativeNavEnabled(context)) {
                NativeNavigationController.stopNavigation(context)
            }
            cancelStreetReset()
            lastNativeNavPayload = null
            NavigationHudStore.reset(action, preserveSpeedLimit = true)
        }

        private fun scheduleStreetReset(context: Context) {
            val scheduledAt = System.currentTimeMillis()
            lastStreetUpdateAt = scheduledAt
            pendingStreetReset?.let { nativeNavHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (lastStreetUpdateAt != scheduledAt) {
                    return@Runnable
                }
                clearStreetName(context)
            }
            pendingStreetReset = runnable
            nativeNavHandler.postDelayed(runnable, STREET_RESET_DELAY_MS)
        }

        private fun cancelStreetReset() {
            pendingStreetReset?.let { nativeNavHandler.removeCallbacks(it) }
            pendingStreetReset = null
            lastStreetUpdateAt = 0L
        }

        private fun clearStreetName(context: Context) {
            var updated: NavigationHudState? = null
            NavigationHudStore.update { state ->
                if (state.rawNextStreet.isBlank()) {
                    return@update state
                }
                val next = state.copy(
                    secondaryText = if (state.secondaryText == state.rawNextStreet) "" else state.secondaryText,
                    lastUpdated = System.currentTimeMillis(),
                    lastAction = "street_timeout",
                    rawNextStreet = ""
                )
                updated = next
                next
            }
            updated?.let { maybeUpdateNativeNavigation(context, it, NativeNavUpdateTrigger.STREET) }
        }

        private fun handleTrafficLightUpdate(
            context: Context,
            action: String,
            id: Int,
            isVisible: Boolean,
            signalColor: String,
            countdown: String,
            arrowBitmap: Bitmap?,
            arrowDirection: String,
            timestamp: Long
        ) {
            val now = System.currentTimeMillis()
            trafficLightContext = context.applicationContext
            if (!isVisible) {
                if (id != 0) {
                    activeTrafficLights.remove(id)
                }
                updateTrafficLightState(context, action, now)
                return
            }
            val countdownSeconds = countdown.toIntOrNull()?.takeIf { it > 0 }
            val countdownMs = countdownSeconds?.toLong()?.times(1000L)
            val expiresAt = if (countdownMs != null && countdownMs > 0L) {
                now + countdownMs
            } else {
                now + TRAFFIC_LIGHT_NO_COUNTDOWN_TTL_MS
            }
            val resolvedId = if (id != 0) {
                id
            } else {
                val base = timestamp.takeIf { it > 0L } ?: now
                ((base % Int.MAX_VALUE).toInt().coerceAtLeast(1))
            }
            activeTrafficLights[resolvedId] = TrafficLightInfo(
                id = resolvedId,
                color = signalColor,
                countdownText = countdown,
                arrowBitmap = arrowBitmap,
                arrowDirection = arrowDirection,
                lastUpdated = now,
                expiresAt = expiresAt
            )
            updateTrafficLightState(context, action, now)
        }

        private fun updateTrafficLightState(context: Context, action: String, now: Long) {
            val maxActive = OverlayPrefs.trafficLightMaxActive(context).coerceAtLeast(1)
            val resolved = activeTrafficLights.values
                .sortedWith(compareByDescending<TrafficLightInfo> { it.lastUpdated }.thenBy { it.id })
                .take(maxActive)
            NavigationHudStore.update { state ->
                state.copy(
                    trafficLightColor = resolved.firstOrNull()?.color.orEmpty(),
                    trafficLightCountdown = resolved.firstOrNull()?.countdownText.orEmpty(),
                    trafficLights = resolved,
                    source = SOURCE_YANDEX,
                    lastUpdated = now,
                    lastAction = action
                )
            }
            scheduleTrafficLightCleanup()
        }

        private fun scheduleTrafficLightCleanup() {
            pendingTrafficLightCleanup?.let { trafficLightHandler.removeCallbacks(it) }
            if (activeTrafficLights.isEmpty()) {
                pendingTrafficLightCleanup = null
                return
            }
            val now = System.currentTimeMillis()
            val nextExpiry = activeTrafficLights.values.minOfOrNull { it.expiresAt } ?: return
            val delayMs = (nextExpiry - now).coerceAtLeast(0L)
            val runnable = Runnable {
                pendingTrafficLightCleanup = null
                purgeExpiredTrafficLights()
            }
            pendingTrafficLightCleanup = runnable
            trafficLightHandler.postDelayed(runnable, delayMs)
        }

        private fun purgeExpiredTrafficLights() {
            val now = System.currentTimeMillis()
            val iterator = activeTrafficLights.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.expiresAt <= now) {
                    iterator.remove()
                }
            }
            val context = trafficLightContext ?: return
            updateTrafficLightState(context = context, action = "traffic_light_timeout", now = now)
        }

        private data class NativeNavPayload(
            val turnId: Int,
            val street: String,
            val distanceToManeuverMeters: Int,
            val distanceToDestinationMeters: Int,
            val totalDistanceToDestinationMeters: Int,
            val etaSeconds: Int
        )

        private fun resolveTurnId(context: Context, maneuverType: String): Int {
            val prefs = context.getSharedPreferences(
                "maneuver_match_prefs",
                Context.MODE_PRIVATE
            )
            val raw = prefs.getString("mapping_$maneuverType", null).orEmpty()
            val match = Regex("^\\d+").find(raw)
            return match?.value?.toIntOrNull() ?: DEFAULT_NATIVE_TURN_ID
        }

        private fun parseDistanceMeters(text: String): Int? {
            val normalized = text.lowercase(Locale.getDefault())
                .replace(',', '.')
            val numberMatch = Regex("([0-9]+(?:\\.[0-9]+)?)").find(normalized) ?: return null
            val value = numberMatch.groupValues[1].toDoubleOrNull() ?: return null
            val meters = when {
                normalized.contains("км") || normalized.contains("km") -> value * 1000.0
                normalized.contains("м") || normalized.contains("m") -> value
                else -> value
            }
            return meters.toInt().coerceAtLeast(0)
        }

        private fun parseEtaSeconds(text: String): Int? {
            val normalized = text.lowercase(Locale.getDefault())
            if (":" in normalized) {
                val parts = normalized.split(":").map { it.trim() }
                if (parts.size == 2) {
                    val first = parts[0].toIntOrNull() ?: return null
                    val second = parts[1].toIntOrNull() ?: return null
                    return if (first >= 1) {
                        first * 3600 + second * 60
                    } else {
                        first * 60 + second
                    }
                }
                if (parts.size == 3) {
                    val hours = parts[0].toIntOrNull() ?: return null
                    val minutes = parts[1].toIntOrNull() ?: return null
                    val seconds = parts[2].toIntOrNull() ?: return null
                    return hours * 3600 + minutes * 60 + seconds
                }
            }
            val hours = Regex("(\\d+)\\s*ч").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minutes = Regex("(\\d+)\\s*мин").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val seconds = Regex("(\\d+)\\s*сек").find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (hours == 0 && minutes == 0 && seconds == 0) {
                val fallback = Regex("(\\d+)").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
                return fallback?.let { it * 60 }
            }
            return hours * 3600 + minutes * 60 + seconds
        }

        private fun predictManeuverType(context: Context, bitmap: Bitmap): String {
            val result = ManeuverRecognition.analyze(context, bitmap)
            if (result.top.isNotEmpty()) {
                val top = result.top.joinToString { "${it.name}=${it.distance}" }
                Log.d(TAG, "Maneuver candidates: $top")
            }
            return result.bestName
        }
    }
}
