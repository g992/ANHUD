package com.g992.anhud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class HudBackgroundService : Service() {
    private val overlayController by lazy { HudOverlayController(applicationContext) }
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                OverlayBroadcasts.ACTION_CLEAR_NAVIGATION -> {
                    overlayController.clearNavigation()
                    return
                }
                OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED -> {
                val navX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_X_DP, Float.NaN)
                val navY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_Y_DP, Float.NaN)
                val arrowX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_ARROW_X_DP, Float.NaN)
                val arrowY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_ARROW_Y_DP, Float.NaN)
                val speedX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_X_DP, Float.NaN)
                val speedY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_Y_DP, Float.NaN)
                val hudSpeedX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_HUDSPEED_X_DP, Float.NaN)
                val hudSpeedY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_HUDSPEED_Y_DP, Float.NaN)
                val roadCameraX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_X_DP, Float.NaN)
                val roadCameraY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_Y_DP, Float.NaN)
                val trafficLightX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_X_DP, Float.NaN)
                val trafficLightY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_Y_DP, Float.NaN)
                val speedometerX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_X_DP, Float.NaN)
                val speedometerY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_Y_DP, Float.NaN)
                val clockX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CLOCK_X_DP, Float.NaN)
                val clockY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CLOCK_Y_DP, Float.NaN)
                val containerX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CONTAINER_X_DP, Float.NaN)
                val containerY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CONTAINER_Y_DP, Float.NaN)
                val containerWidth = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CONTAINER_WIDTH_DP, Float.NaN)
                val containerHeight = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CONTAINER_HEIGHT_DP, Float.NaN)
                val navScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_SCALE, Float.NaN)
                val navTextScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_TEXT_SCALE, Float.NaN)
                val speedTextScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_TEXT_SCALE, Float.NaN)
                val arrowScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_ARROW_SCALE, Float.NaN)
                val speedScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_SCALE, Float.NaN)
                val hudSpeedScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_HUDSPEED_SCALE, Float.NaN)
                val roadCameraScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_SCALE, Float.NaN)
                val trafficLightScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_SCALE, Float.NaN)
                val speedometerScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_SCALE, Float.NaN)
                val clockScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CLOCK_SCALE, Float.NaN)
                val navAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_ALPHA, Float.NaN)
                val arrowAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_ARROW_ALPHA, Float.NaN)
                val speedAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_ALPHA, Float.NaN)
                val hudSpeedAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ALPHA, Float.NaN)
                val roadCameraAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ALPHA, Float.NaN)
                val trafficLightAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ALPHA, Float.NaN)
                val speedometerAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ALPHA, Float.NaN)
                val clockAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CLOCK_ALPHA, Float.NaN)
                val containerAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_CONTAINER_ALPHA, Float.NaN)
                val navEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_NAV_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_NAV_ENABLED, true)
                } else {
                    null
                }
                val arrowEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_ARROW_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_ARROW_ENABLED, false)
                } else {
                    null
                }
                val arrowOnlyWhenNoIcon = if (intent.hasExtra(OverlayBroadcasts.EXTRA_ARROW_ONLY_WHEN_NO_ICON)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_ARROW_ONLY_WHEN_NO_ICON, false)
                } else {
                    null
                }
                val speedEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_SPEED_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_SPEED_ENABLED, true)
                } else {
                    null
                }
                val hudSpeedEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_HUDSPEED_ENABLED, true)
                } else {
                    null
                }
                val roadCameraEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_ROAD_CAMERA_ENABLED, true)
                } else {
                    null
                }
                val trafficLightEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_ENABLED, true)
                } else {
                    null
                }
                val trafficLightMaxActive = if (intent.hasExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_MAX_ACTIVE)) {
                    intent.getIntExtra(OverlayBroadcasts.EXTRA_TRAFFIC_LIGHT_MAX_ACTIVE, 3)
                } else {
                    null
                }
                val speedLimitAlertEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_ENABLED, false)
                } else {
                    null
                }
                val speedLimitAlertThreshold = if (intent.hasExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_THRESHOLD)) {
                    intent.getIntExtra(OverlayBroadcasts.EXTRA_SPEED_LIMIT_ALERT_THRESHOLD, 0)
                } else {
                    null
                }
                val speedometerEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_SPEEDOMETER_ENABLED, true)
                } else {
                    null
                }
                val clockEnabled = if (intent.hasExtra(OverlayBroadcasts.EXTRA_CLOCK_ENABLED)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_CLOCK_ENABLED, true)
                } else {
                    null
                }
                val preview = intent.getBooleanExtra(OverlayBroadcasts.EXTRA_PREVIEW, false)
                val previewTarget = intent.getStringExtra(OverlayBroadcasts.EXTRA_PREVIEW_TARGET)
                val previewShowOthers = if (intent.hasExtra(OverlayBroadcasts.EXTRA_PREVIEW_SHOW_OTHERS)) {
                    intent.getBooleanExtra(OverlayBroadcasts.EXTRA_PREVIEW_SHOW_OTHERS, false)
                } else {
                    null
                }

                val navPosition = if (!navX.isNaN() && !navY.isNaN()) {
                    android.graphics.PointF(navX, navY)
                } else {
                    null
                }
                val arrowPosition = if (!arrowX.isNaN() && !arrowY.isNaN()) {
                    android.graphics.PointF(arrowX, arrowY)
                } else {
                    null
                }
                val speedPosition = if (!speedX.isNaN() && !speedY.isNaN()) {
                    android.graphics.PointF(speedX, speedY)
                } else {
                    null
                }
                val hudSpeedPosition = if (!hudSpeedX.isNaN() && !hudSpeedY.isNaN()) {
                    android.graphics.PointF(hudSpeedX, hudSpeedY)
                } else {
                    null
                }
                val roadCameraPosition = if (!roadCameraX.isNaN() && !roadCameraY.isNaN()) {
                    android.graphics.PointF(roadCameraX, roadCameraY)
                } else {
                    null
                }
                val trafficLightPosition = if (!trafficLightX.isNaN() && !trafficLightY.isNaN()) {
                    android.graphics.PointF(trafficLightX, trafficLightY)
                } else {
                    null
                }
                val speedometerPosition = if (!speedometerX.isNaN() && !speedometerY.isNaN()) {
                    android.graphics.PointF(speedometerX, speedometerY)
                } else {
                    null
                }
                val clockPosition = if (!clockX.isNaN() && !clockY.isNaN()) {
                    android.graphics.PointF(clockX, clockY)
                } else {
                    null
                }
                val containerPosition = if (!containerX.isNaN() && !containerY.isNaN()) {
                    android.graphics.PointF(containerX, containerY)
                } else {
                    null
                }
                val containerWidthValue = containerWidth.takeIf { !it.isNaN() }
                val containerHeightValue = containerHeight.takeIf { !it.isNaN() }
                val navScaleValue = navScale.takeIf { !it.isNaN() }
                val navTextScaleValue = navTextScale.takeIf { !it.isNaN() }
                val speedTextScaleValue = speedTextScale.takeIf { !it.isNaN() }
                val arrowScaleValue = arrowScale.takeIf { !it.isNaN() }
                val speedScaleValue = speedScale.takeIf { !it.isNaN() }
                val hudSpeedScaleValue = hudSpeedScale.takeIf { !it.isNaN() }
                val roadCameraScaleValue = roadCameraScale.takeIf { !it.isNaN() }
                val trafficLightScaleValue = trafficLightScale.takeIf { !it.isNaN() }
                val speedometerScaleValue = speedometerScale.takeIf { !it.isNaN() }
                val clockScaleValue = clockScale.takeIf { !it.isNaN() }
                val navAlphaValue = navAlpha.takeIf { !it.isNaN() }
                val arrowAlphaValue = arrowAlpha.takeIf { !it.isNaN() }
                val speedAlphaValue = speedAlpha.takeIf { !it.isNaN() }
                val hudSpeedAlphaValue = hudSpeedAlpha.takeIf { !it.isNaN() }
                val roadCameraAlphaValue = roadCameraAlpha.takeIf { !it.isNaN() }
                val trafficLightAlphaValue = trafficLightAlpha.takeIf { !it.isNaN() }
                val speedometerAlphaValue = speedometerAlpha.takeIf { !it.isNaN() }
                val clockAlphaValue = clockAlpha.takeIf { !it.isNaN() }
                val containerAlphaValue = containerAlpha.takeIf { !it.isNaN() }
                overlayController.updateLayout(
                    containerPosition,
                    containerWidthValue,
                    containerHeightValue,
                    navPosition,
                    arrowPosition,
                    speedPosition,
                    hudSpeedPosition,
                    roadCameraPosition,
                    trafficLightPosition,
                    speedometerPosition,
                    clockPosition,
                    navScaleValue,
                    navTextScaleValue,
                    speedTextScaleValue,
                    arrowScaleValue,
                    speedScaleValue,
                    hudSpeedScaleValue,
                    roadCameraScaleValue,
                    trafficLightScaleValue,
                    speedometerScaleValue,
                    clockScaleValue,
                    navAlphaValue,
                    arrowAlphaValue,
                    speedAlphaValue,
                    hudSpeedAlphaValue,
                    roadCameraAlphaValue,
                    trafficLightAlphaValue,
                    speedometerAlphaValue,
                    clockAlphaValue,
                    containerAlphaValue,
                    navEnabled,
                    arrowEnabled,
                    arrowOnlyWhenNoIcon,
                    speedEnabled,
                    hudSpeedEnabled,
                    roadCameraEnabled,
                    trafficLightEnabled,
                    speedLimitAlertEnabled,
                    speedLimitAlertThreshold,
                    speedometerEnabled,
                    clockEnabled,
                    trafficLightMaxActive,
                    preview,
                    previewTarget,
                    previewShowOthers
                )
                overlayController.refresh()
                overlayController.updateNavigation(NavigationHudStore.snapshot())
                }
            }
        }
    }
    private val navListener = object : NavigationHudStore.Listener {
        override fun onStateUpdated(state: NavigationHudState) {
            overlayController.updateNavigation(state)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UiLogStore.append(LogCategory.SYSTEM, "HudBackgroundService: создан")
        NavigationHudStore.registerListener(navListener)
        val filter = android.content.IntentFilter().apply {
            addAction(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
            addAction(OverlayBroadcasts.ACTION_CLEAR_NAVIGATION)
        }
        ContextCompat.registerReceiver(
            this,
            settingsReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.hud_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.hud_service_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UiLogStore.append(LogCategory.SYSTEM, "HudBackgroundService: запущен")
        startService(Intent(this, NavigationService::class.java))
        startService(Intent(this, SensorDataService::class.java))
        overlayController.refresh()
        overlayController.updateNavigation(NavigationHudStore.snapshot())
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.hud_service_notification_title))
            .setContentText(getString(R.string.hud_service_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "hud_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onDestroy() {
        NavigationHudStore.unregisterListener(navListener)
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {
        }
        overlayController.destroy()
        super.onDestroy()
    }
}
