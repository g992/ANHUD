package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat

class HudStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANHUD_STATUS -> handleStatusIntent(context, intent)
            ACTION_SET_PRESET,
            ACTION_SET_PRESET_LEGACY -> handleSetPresetIntent(context, intent)
        }
    }

    private fun handleStatusIntent(context: Context, intent: Intent) {
        val enabled = parseBooleanExtra(intent, EXTRA_ENABLE)
        val stopNavigation = parseBooleanExtra(intent, EXTRA_STOP_NAVIGATION)
        val navEnabled = parseBooleanExtra(intent, EXTRA_NAV_ENABLED)
        val speedEnabled = parseBooleanExtra(intent, EXTRA_SPEED_ENABLED)
        val speedLimitAlertEnabled = parseBooleanExtra(intent, EXTRA_SPEED_LIMIT_ALERT_ENABLED)
        val speedLimitAlertThreshold = parseIntExtra(intent, EXTRA_SPEED_LIMIT_ALERT_THRESHOLD)
        val speedometerEnabled = parseBooleanExtra(intent, EXTRA_SPEEDOMETER_ENABLED)
        val clockEnabled = parseBooleanExtra(intent, EXTRA_CLOCK_ENABLED)

        var effectiveEnabled = enabled
        if (effectiveEnabled == true && !Settings.canDrawOverlays(context)) {
            UiLogStore.append(LogCategory.SYSTEM, "HUD enable ignored: overlay permission missing")
            effectiveEnabled = null
        }

        if (effectiveEnabled != null) {
            OverlayPrefs.setEnabled(context, effectiveEnabled)
            UiLogStore.append(
                LogCategory.SYSTEM,
                if (effectiveEnabled) "HUD enabled via intent" else "HUD disabled via intent"
            )

            // When disabling HUD, also stop any active navigation
            if (!effectiveEnabled) {
                stopActiveNavigation(context)
            }
        }

        // Handle explicit stop navigation request
        if (stopNavigation == true) {
            stopActiveNavigation(context)
        }

        if (navEnabled != null) {
            OverlayPrefs.setNavEnabled(context, navEnabled)
        }
        if (speedEnabled != null) {
            OverlayPrefs.setSpeedEnabled(context, speedEnabled)
        }
        if (speedLimitAlertEnabled != null) {
            OverlayPrefs.setSpeedLimitAlertEnabled(context, speedLimitAlertEnabled)
        }
        if (speedLimitAlertThreshold != null) {
            OverlayPrefs.setSpeedLimitAlertThreshold(context, speedLimitAlertThreshold)
        }
        if (speedometerEnabled != null) {
            OverlayPrefs.setSpeedometerEnabled(context, speedometerEnabled)
        }
        if (clockEnabled != null) {
            OverlayPrefs.setClockEnabled(context, clockEnabled)
        }

        val shouldBroadcast = effectiveEnabled != null ||
            navEnabled != null ||
            speedEnabled != null ||
            speedLimitAlertEnabled != null ||
            speedLimitAlertThreshold != null ||
            speedometerEnabled != null ||
            clockEnabled != null
        if (!shouldBroadcast) {
            return
        }

        sendOverlayRefresh(
            context,
            navEnabled,
            speedEnabled,
            speedLimitAlertEnabled,
            speedLimitAlertThreshold,
            speedometerEnabled,
            clockEnabled
        )
        if (effectiveEnabled == true) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HudBackgroundService::class.java)
            )
        }
    }

    private fun handleSetPresetIntent(context: Context, intent: Intent) {
        val presetNumber = parsePresetNumber(intent)
        if (presetNumber == null || presetNumber < 1) {
            UiLogStore.append(LogCategory.SYSTEM, "Смена пресета проигнорирована: неверный номер")
            return
        }
        val preset = PresetManager.loadPresets(context).getOrNull(presetNumber - 1)
        if (preset == null) {
            UiLogStore.append(
                LogCategory.SYSTEM,
                "Смена пресета проигнорирована: пресет #$presetNumber не найден"
            )
            return
        }
        val payload = PresetManager.readPresetPayload(context, preset)
        if (payload == null || !PrefsJson.applyPayload(context, payload)) {
            UiLogStore.append(
                LogCategory.SYSTEM,
                "Смена пресета проигнорирована: не удалось применить #$presetNumber"
            )
            return
        }
        PresetPrefs.setActivePresetId(context, preset.id)
        UiLogStore.append(LogCategory.SYSTEM, "Применен пресет #$presetNumber: ${preset.name}")
        sendOverlayRefreshFull(context)
        if (OverlayPrefs.isEnabled(context) && Settings.canDrawOverlays(context)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HudBackgroundService::class.java)
            )
        }
    }

    private fun sendOverlayRefresh(
        context: Context,
        navEnabled: Boolean?,
        speedEnabled: Boolean?,
        speedLimitAlertEnabled: Boolean?,
        speedLimitAlertThreshold: Int?,
        speedometerEnabled: Boolean?,
        clockEnabled: Boolean?
    ) {
        val updateIntent = Intent(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
            .setPackage(context.packageName)
        if (navEnabled != null) {
            updateIntent.putExtra(OverlayBroadcasts.EXTRA_NAV_ENABLED, navEnabled)
        }
        if (speedEnabled != null) {
            updateIntent.putExtra(OverlayBroadcasts.EXTRA_SPEED_ENABLED, speedEnabled)
        }
        if (speedLimitAlertEnabled != null) {
            updateIntent.putExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_ENABLED, speedLimitAlertEnabled)
        }
        if (speedLimitAlertThreshold != null) {
            updateIntent.putExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_THRESHOLD, speedLimitAlertThreshold)
        }
        if (speedometerEnabled != null) {
            updateIntent.putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ENABLED, speedometerEnabled)
        }
        if (clockEnabled != null) {
            updateIntent.putExtra(OverlayBroadcasts.EXTRA_CLOCK_ENABLED, clockEnabled)
        }
        context.sendBroadcast(updateIntent)
    }

    private fun sendOverlayRefreshFull(context: Context) {
        val navPos = OverlayPrefs.navPositionDp(context)
        val arrowPos = OverlayPrefs.arrowPositionDp(context)
        val speedPos = OverlayPrefs.speedPositionDp(context)
        val hudSpeedPos = OverlayPrefs.hudSpeedPositionDp(context)
        val roadCameraPos = OverlayPrefs.roadCameraPositionDp(context)
        val trafficLightPos = OverlayPrefs.trafficLightPositionDp(context)
        val speedometerPos = OverlayPrefs.speedometerPositionDp(context)
        val clockPos = OverlayPrefs.clockPositionDp(context)
        val containerPos = OverlayPrefs.containerPositionDp(context)
        val containerSize = OverlayPrefs.containerSizeDp(context)
        val updateIntent = Intent(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
            .setPackage(context.packageName)
            .putExtra(OverlayBroadcasts.EXTRA_CONTAINER_X_DP, containerPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_CONTAINER_Y_DP, containerPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_CONTAINER_WIDTH_DP, containerSize.x)
            .putExtra(OverlayBroadcasts.EXTRA_CONTAINER_HEIGHT_DP, containerSize.y)
            .putExtra(OverlayBroadcasts.EXTRA_NAV_X_DP, navPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_NAV_Y_DP, navPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_NAV_WIDTH_DP, OverlayPrefs.navWidthDp(context))
            .putExtra(OverlayBroadcasts.EXTRA_ARROW_X_DP, arrowPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_ARROW_Y_DP, arrowPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_SPEED_X_DP, speedPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_SPEED_Y_DP, speedPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_X_DP, hudSpeedPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_Y_DP, hudSpeedPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_X_DP, roadCameraPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_Y_DP, roadCameraPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_X_DP, trafficLightPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_Y_DP, trafficLightPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_X_DP, speedometerPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_Y_DP, speedometerPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_CLOCK_X_DP, clockPos.x)
            .putExtra(OverlayBroadcasts.EXTRA_CLOCK_Y_DP, clockPos.y)
            .putExtra(OverlayBroadcasts.EXTRA_NAV_SCALE, OverlayPrefs.navScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_NAV_TEXT_SCALE, OverlayPrefs.navTextScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_ARROW_SCALE, OverlayPrefs.arrowScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_SPEED_SCALE, OverlayPrefs.speedScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_SPEED_TEXT_SCALE, OverlayPrefs.speedTextScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_SCALE, OverlayPrefs.hudSpeedScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_SCALE, OverlayPrefs.roadCameraScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_SCALE, OverlayPrefs.trafficLightScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_SCALE, OverlayPrefs.speedometerScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_CLOCK_SCALE, OverlayPrefs.clockScale(context))
            .putExtra(OverlayBroadcasts.EXTRA_NAV_ALPHA, OverlayPrefs.navAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_ARROW_ALPHA, OverlayPrefs.arrowAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_SPEED_ALPHA, OverlayPrefs.speedAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ALPHA, OverlayPrefs.hudSpeedAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ALPHA, OverlayPrefs.roadCameraAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ALPHA, OverlayPrefs.trafficLightAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ALPHA, OverlayPrefs.speedometerAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_CLOCK_ALPHA, OverlayPrefs.clockAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_CONTAINER_ALPHA, OverlayPrefs.containerAlpha(context))
            .putExtra(OverlayBroadcasts.EXTRA_NAV_ENABLED, OverlayPrefs.navEnabled(context))
            .putExtra(OverlayBroadcasts.EXTRA_ARROW_ENABLED, OverlayPrefs.arrowEnabled(context))
            .putExtra(OverlayBroadcasts.EXTRA_SPEED_ENABLED, OverlayPrefs.speedEnabled(context))
            .putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ENABLED, OverlayPrefs.hudSpeedEnabled(context))
            .putExtra(OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ENABLED, OverlayPrefs.hudSpeedLimitEnabled(context))
            .putExtra(
                OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ALERT_ENABLED,
                OverlayPrefs.hudSpeedLimitAlertEnabled(context)
            )
            .putExtra(
                OverlayBroadcasts.EXTRA_HUDSPEED_LIMIT_ALERT_THRESHOLD,
                OverlayPrefs.hudSpeedLimitAlertThreshold(context)
            )
            .putExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ENABLED, OverlayPrefs.roadCameraEnabled(context))
            .putExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ENABLED, OverlayPrefs.trafficLightEnabled(context))
            .putExtra(OverlayBroadcasts.EXTRA_ARROW_ONLY_WHEN_NO_ICON, OverlayPrefs.arrowOnlyWhenNoIcon(context))
            .putExtra(
                OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_ENABLED,
                OverlayPrefs.speedLimitAlertEnabled(context)
            )
            .putExtra(
                OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_THRESHOLD,
                OverlayPrefs.speedLimitAlertThreshold(context)
            )
            .putExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ENABLED, OverlayPrefs.speedometerEnabled(context))
            .putExtra(OverlayBroadcasts.EXTRA_CLOCK_ENABLED, OverlayPrefs.clockEnabled(context))
            .putExtra(
                OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_MAX_ACTIVE,
                OverlayPrefs.trafficLightMaxActive(context)
            )
            .putExtra(OverlayBroadcasts.EXTRA_MAP_ENABLED, OverlayPrefs.mapEnabled(context))
            .putExtra(OverlayBroadcasts.EXTRA_PREVIEW, false)
        context.sendBroadcast(updateIntent)
    }

    private fun parsePresetNumber(intent: Intent): Int? {
        val valueByKnownKey = PRESET_NUMBER_KEYS.firstNotNullOfOrNull { key ->
            parseIntExtra(intent, key)
        }
        if (valueByKnownKey != null) {
            return valueByKnownKey
        }
        val extras = intent.extras ?: return null
        for (key in extras.keySet()) {
            val value = parseIntValue(readExtraValue(extras, key)) ?: continue
            return value
        }
        return null
    }

    private fun parseBooleanExtra(intent: Intent, key: String): Boolean? {
        if (!intent.hasExtra(key)) {
            return null
        }
        val extras = intent.extras ?: return null
        val raw = readExtraValue(extras, key) ?: return null
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.toIntOrNull()?.let { it != 0 }
                ?: raw.equals("true", ignoreCase = true)
            else -> null
        }
    }

    private fun parseIntExtra(intent: Intent, key: String): Int? {
        if (!intent.hasExtra(key)) {
            return null
        }
        val extras = intent.extras ?: return null
        return parseIntValue(readExtraValue(extras, key))
    }

    private fun parseIntValue(raw: Any?): Int? {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun readExtraValue(extras: Bundle, key: String): Any? = extras.get(key)

    private fun stopActiveNavigation(context: Context) {
        // Stop native navigation if it's enabled and active
        if (OverlayPrefs.nativeNavEnabled(context) && NativeNavigationController.isActive()) {
            NativeNavigationController.stopNavigation(context)
            UiLogStore.append(LogCategory.NAVIGATION, "Штатная навигация остановлена через intent")
        }

        // Clear navigation HUD store
        NavigationHudStore.reset(
            "G992.ANHUD.STATUS",
            preserveSpeedLimit = true,
            preserveRoadCamera = true,
            preserveHudSpeed = true
        )
        UiLogStore.append(LogCategory.NAVIGATION, "Маршрут завершен через intent")

        // Send broadcast to clear overlay display
        val clearIntent = Intent(OverlayBroadcasts.ACTION_CLEAR_NAVIGATION)
            .setPackage(context.packageName)
        context.sendBroadcast(clearIntent)
    }

    companion object {
        const val ACTION_ANHUD_STATUS = "G992.ANHUD.STATUS"
        const val ACTION_SET_PRESET = "ANHUD_SET_PRESET"
        const val ACTION_SET_PRESET_LEGACY = "G992.ANHUD.SET_PRESET"
        const val EXTRA_ENABLE = "ENABLE"
        const val EXTRA_STOP_NAVIGATION = "STOP_NAVIGATION"
        const val EXTRA_NAV_ENABLED = "NAV"
        const val EXTRA_SPEED_ENABLED = "SPEED_LIMIT"
        const val EXTRA_SPEED_LIMIT_ALERT_ENABLED = "SPEED_ALERT"
        const val EXTRA_SPEED_LIMIT_ALERT_THRESHOLD = "SPEED_ALERT_THRESHOLD"
        const val EXTRA_SPEEDOMETER_ENABLED = "SPEEDOMETER"
        const val EXTRA_CLOCK_ENABLED = "CLOCK"
        const val EXTRA_PRESET = "PRESET"
        const val EXTRA_PRESET_INDEX = "INDEX"
        private val PRESET_NUMBER_KEYS = listOf(
            EXTRA_PRESET,
            EXTRA_PRESET_INDEX,
            "preset",
            "index",
            "preset_number",
            "PRESET_NUMBER"
        )
    }
}
