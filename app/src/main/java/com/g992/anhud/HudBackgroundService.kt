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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class HudBackgroundService : Service() {
    private val overlayController by lazy { HudOverlayController(applicationContext) }
    private val navAppMonitor by lazy {
        NavigationAppMonitor(applicationContext, ::onNavAppOpened, ::onNavAppClosed)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED) {
                val navX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_X_DP, Float.NaN)
                val navY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_Y_DP, Float.NaN)
                val speedX = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_X_DP, Float.NaN)
                val speedY = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_Y_DP, Float.NaN)
                val navScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_SCALE, Float.NaN)
                val speedScale = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_SCALE, Float.NaN)
                val navAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_NAV_ALPHA, Float.NaN)
                val speedAlpha = intent.getFloatExtra(OverlayBroadcasts.EXTRA_SPEED_ALPHA, Float.NaN)
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
                val speedPosition = if (!speedX.isNaN() && !speedY.isNaN()) {
                    android.graphics.PointF(speedX, speedY)
                } else {
                    null
                }
                val navScaleValue = navScale.takeIf { !it.isNaN() }
                val speedScaleValue = speedScale.takeIf { !it.isNaN() }
                val navAlphaValue = navAlpha.takeIf { !it.isNaN() }
                val speedAlphaValue = speedAlpha.takeIf { !it.isNaN() }
                overlayController.updateLayout(
                    navPosition,
                    speedPosition,
                    navScaleValue,
                    speedScaleValue,
                    navAlphaValue,
                    speedAlphaValue,
                    preview,
                    previewTarget,
                    previewShowOthers
                )
                overlayController.refresh()
                overlayController.updateNavigation(NavigationHudStore.snapshot())
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
        navAppMonitor.start()
        val filter = android.content.IntentFilter(OverlayBroadcasts.ACTION_OVERLAY_SETTINGS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }
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
        private const val ACTION_NAV_APP_OPENED = "nav_app_opened"
        private const val ACTION_NAV_APP_CLOSED = "nav_app_closed"
    }

    override fun onDestroy() {
        NavigationHudStore.unregisterListener(navListener)
        navAppMonitor.shutdown()
        try {
            unregisterReceiver(settingsReceiver)
        } catch (_: Exception) {
        }
        overlayController.destroy()
        super.onDestroy()
    }

    private fun onNavAppOpened(packageName: String) {
        mainHandler.post {
            val label = OverlayPrefs.navAppLabel(applicationContext).ifBlank { packageName }
            UiLogStore.append(LogCategory.NAVIGATION, "навигатор открыт: $label")
            NavigationHudStore.reset(ACTION_NAV_APP_OPENED)
        }
    }

    private fun onNavAppClosed(packageName: String) {
        mainHandler.post {
            val label = OverlayPrefs.navAppLabel(applicationContext).ifBlank { packageName }
            UiLogStore.append(LogCategory.NAVIGATION, "навигатор закрыт: $label")
            NavigationHudStore.reset(ACTION_NAV_APP_CLOSED)
        }
    }
}
