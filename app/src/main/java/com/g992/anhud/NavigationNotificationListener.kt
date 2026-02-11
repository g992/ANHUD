package com.g992.anhud

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Handler
import android.os.Looper

class NavigationNotificationListener : NotificationListenerService() {
    private var staticNavNotificationPresent = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingEnd: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
        UiLogStore.append(LogCategory.NAVIGATION, "NotificationListener подключен")
        logTrackedActiveNotifications()
        checkStaticNotification()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isYandexPackage(sbn.packageName)) {
            return
        }
        logNotificationDetails(sbn, "POSTED")

        if (isStaticNavigatorNotification(sbn)) {
            handleStaticNotificationPosted()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isYandexPackage(sbn.packageName)) {
            return
        }
        logNotificationDetails(sbn, "REMOVED")

        if (isStaticNavigatorNotification(sbn)) {
            handleStaticNotificationRemoved()
        }
    }

    private fun logNotificationDetails(sbn: StatusBarNotification, action: String) {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val category = notification.category.orEmpty()
        val isOngoing = sbn.isOngoing
        val flags = notification.flags
        val id = sbn.id

        Log.d(
            TAG,
            "[$action] pkg=${sbn.packageName} id=$id category=$category ongoing=$isOngoing " +
                "flags=$flags title=\"$title\" text=\"$text\""
        )
    }

    private fun isStaticNavigatorNotification(sbn: StatusBarNotification): Boolean {
        // Static Yandex Navigator notification: id=2, title indicates navigator is running
        if (sbn.id != YANDEX_STATIC_NOTIFICATION_ID) {
            return false
        }

        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        return title.contains("Navigator", ignoreCase = true) ||
            title.contains("Навигатор", ignoreCase = true)
    }

    private fun handleStaticNotificationPosted() {
        if (staticNavNotificationPresent) {
            Log.d(TAG, "Static notification already tracked, ignoring")
            return
        }

        staticNavNotificationPresent = true

        // Cancel any pending navigation end
        pendingEnd?.let { handler.removeCallbacks(it) }
        pendingEnd = null

        Log.d(TAG, "Static navigation notification appeared - starting navigation")
        startNavigation()
    }

    private fun handleStaticNotificationRemoved() {
        if (!staticNavNotificationPresent) {
            Log.d(TAG, "Static notification was not tracked, ignoring removal")
            return
        }

        Log.d(TAG, "Static navigation notification removed - scheduling navigation end")
        scheduleNavigationEnd()
    }

    private fun checkStaticNotification() {
        // Check if static notification already exists on connect
        activeNotifications?.forEach { sbn ->
            if (isYandexPackage(sbn.packageName) && isStaticNavigatorNotification(sbn)) {
                staticNavNotificationPresent = true
                Log.d(TAG, "Found existing static notification on connect")
                startNavigation()
                return
            }
        }
        staticNavNotificationPresent = false
    }

    private fun scheduleNavigationEnd() {
        pendingEnd?.let { handler.removeCallbacks(it) }
        val delayMs = OverlayPrefs.navNotificationEndTimeout(applicationContext)
            .toLong()
            .coerceAtLeast(0L) * 1000L
        val runnable = Runnable {
            pendingEnd = null
            // Double-check notification still gone
            val stillPresent = activeNotifications?.any { sbn ->
                isYandexPackage(sbn.packageName) && isStaticNavigatorNotification(sbn)
            } ?: false

            if (stillPresent) {
                Log.d(TAG, "Navigation notification reappeared, cancelling end")
                staticNavNotificationPresent = true
                return@Runnable
            }

            staticNavNotificationPresent = false
            endNavigation()
        }
        pendingEnd = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun logTrackedActiveNotifications() {
        val tracked = activeNotifications
            ?.filter { isYandexPackage(it.packageName) }
            .orEmpty()
        Log.d(TAG, "Tracked active notifications: ${tracked.size}")
        tracked.forEach { sbn ->
            logNotificationDetails(sbn, "ACTIVE")
        }
    }

    private fun startNavigation() {
        Log.d(TAG, "Navigation active via notification")
        UiLogStore.append(LogCategory.NAVIGATION, "навигация по уведомлению: старт")
        NavigationReceiver.onNavigationStartedFromNotification(applicationContext)

        NavigationHudStore.update { state ->
            state.copy(
                routeActive = true,
                source = state.source.ifBlank { SOURCE_YANDEX },
                lastUpdated = System.currentTimeMillis(),
                lastAction = ACTION_NAV_NOTIFICATION_ACTIVE
            )
        }

        // Don't start native navigation here - wait for actual navigation data (turnId/distance)
        Log.d(TAG, "Waiting for navigation data before starting native nav")
    }

    private fun endNavigation() {
        Log.d(TAG, "Navigation ended via notification removal")
        UiLogStore.append(LogCategory.NAVIGATION, "навигация по уведомлению: стоп")
        NavigationReceiver.clearNavigatorIntentTimeout()

        // Only stop native navigation if it was enabled
        if (OverlayPrefs.nativeNavEnabled(applicationContext)) {
            NativeNavigationController.stopNavigation(applicationContext)
        }
        NavigationHudStore.reset(
            ACTION_NAV_NOTIFICATION_ENDED,
            preserveSpeedLimit = true,
            preserveRoadCamera = true,
            preserveHudSpeed = true
        )

        // Send broadcast to clear overlay display
        val intent = android.content.Intent(OverlayBroadcasts.ACTION_CLEAR_NAVIGATION)
            .setPackage(applicationContext.packageName)
        applicationContext.sendBroadcast(intent)
    }

    private fun isYandexPackage(packageName: String): Boolean {
        return packageName in YANDEX_PACKAGES
    }

    companion object {
        private const val TAG = "NavNotifListener"
        private const val ACTION_NAV_NOTIFICATION_ACTIVE = "notification.NAVIGATION_ACTIVE"
        private const val ACTION_NAV_NOTIFICATION_ENDED = "notification.NAVIGATION_ENDED"
        private const val SOURCE_YANDEX = "yandex"
        private const val YANDEX_STATIC_NOTIFICATION_ID = 2

        // Yandex packages to listen for (same as PLUS_MONJ)
        private val YANDEX_PACKAGES = setOf(
            "ru.yandex.yandexmaps",
            "ru.yandex.yandexnavi",
            "com.yango.maps.android"
        )
    }
}
