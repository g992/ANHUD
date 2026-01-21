package com.g992.anhud

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log

class NavigationAppMonitor(
    context: Context,
    private val onAppOpened: (String) -> Unit,
    private val onAppClosed: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val usageStats = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val handlerThread = HandlerThread("NavAppMonitor").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var running = false
    private var lastTimestamp = 0L
    private var lastTargetPackage = ""
    private var targetInForeground = false
    private var missingAccessLogged = false
    private var lastHeartbeat = 0L

    fun start() {
        if (running) {
            return
        }
        running = true
        Log.d(TAG, "NavigationAppMonitor started")
        handler.post(pollRunnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "NavigationAppMonitor stopped")
    }

    fun shutdown() {
        stop()
        handlerThread.quitSafely()
    }

    private val pollRunnable = Runnable {
        pollOnce()
    }

    private fun pollOnce() {
        if (!running) {
            return
        }
        val targetPackage = OverlayPrefs.navAppPackage(appContext)
        if (targetPackage != lastTargetPackage) {
            lastTargetPackage = targetPackage
            targetInForeground = false
            lastTimestamp = 0L
            Log.d(TAG, "Target package set to \"$targetPackage\"")
        }
        if (targetPackage.isBlank()) {
            scheduleNext()
            return
        }
        val hasAccess = hasUsageAccess()
        if (!hasAccess) {
            if (!missingAccessLogged) {
                UiLogStore.append(LogCategory.SYSTEM, "Нет доступа к истории использования для отслеживания навигатора")
                missingAccessLogged = true
            }
            Log.w(TAG, "Missing usage access for package=${appContext.packageName}")
            scheduleNext()
            return
        }
        missingAccessLogged = false
        val now = System.currentTimeMillis()
        if (now - lastHeartbeat >= HEARTBEAT_MS) {
            lastHeartbeat = now
            Log.d(TAG, "Poll tick target=$targetPackage lastTs=$lastTimestamp")
        }
        val startTime = if (lastTimestamp > 0L) {
            (lastTimestamp - POLL_GRACE_MS).coerceAtLeast(0L)
        } else {
            (now - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
        }
        val events = usageStats.queryEvents(startTime, now)
        val event = UsageEvents.Event()
        var latestTimestamp = lastTimestamp
        var targetEventSeen = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp <= lastTimestamp) {
                continue
            }
            if (event.timeStamp > latestTimestamp) {
                latestTimestamp = event.timeStamp
            }
            val isForeground = isForegroundEvent(event.eventType)
            val isBackground = isBackgroundEvent(event.eventType)
            if (event.packageName == targetPackage) {
                targetEventSeen = true
                if (isForeground && !targetInForeground) {
                    targetInForeground = true
                    Log.d(TAG, "Target opened: $targetPackage")
                    onAppOpened(targetPackage)
                } else if (isBackground && targetInForeground) {
                    targetInForeground = false
                    Log.d(TAG, "Target closed: $targetPackage")
                    onAppClosed(targetPackage)
                }
            } else if (isForeground && targetInForeground) {
                targetInForeground = false
                Log.d(TAG, "Target backgrounded by ${event.packageName}")
                onAppClosed(targetPackage)
            }
        }
        if (!targetEventSeen && latestTimestamp > lastTimestamp) {
            Log.d(TAG, "No target events in window [$startTime-$now]")
        }
        if (latestTimestamp > lastTimestamp) {
            lastTimestamp = latestTimestamp
        }
        scheduleNext()
    }

    private fun scheduleNext() {
        if (running) {
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
    }

    private fun hasUsageAccess(): Boolean {
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            appContext.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isForegroundEvent(type: Int): Boolean {
        return type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            type == UsageEvents.Event.ACTIVITY_RESUMED
    }

    private fun isBackgroundEvent(type: Int): Boolean {
        return type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            type == UsageEvents.Event.ACTIVITY_PAUSED ||
            type == UsageEvents.Event.ACTIVITY_STOPPED
    }

    companion object {
        private const val TAG = "NavAppMonitor"
        private const val POLL_INTERVAL_MS = 1000L
        private const val INITIAL_LOOKBACK_MS = 10000L
        private const val POLL_GRACE_MS = 1500L
        private const val HEARTBEAT_MS = 15000L
    }
}
