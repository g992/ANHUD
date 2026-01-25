package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class HudStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANHUD_STATUS) {
            return
        }
        val enabled = parseBooleanExtra(intent, EXTRA_ENABLE)
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

    private fun parseBooleanExtra(intent: Intent, key: String): Boolean? {
        if (!intent.hasExtra(key)) {
            return null
        }
        val raw = intent.extras?.get(key) ?: return null
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
        val raw = intent.extras?.get(key) ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    companion object {
        const val ACTION_ANHUD_STATUS = "G992.ANHUD.STATUS"
        const val EXTRA_ENABLE = "ENABLE"
        const val EXTRA_NAV_ENABLED = "NAV"
        const val EXTRA_SPEED_ENABLED = "SPEED_LIMIT"
        const val EXTRA_SPEED_LIMIT_ALERT_ENABLED = "SPEED_ALERT"
        const val EXTRA_SPEED_LIMIT_ALERT_THRESHOLD = "SPEED_ALERT_THRESHOLD"
        const val EXTRA_SPEEDOMETER_ENABLED = "SPEEDOMETER"
        const val EXTRA_CLOCK_ENABLED = "CLOCK"
    }
}
