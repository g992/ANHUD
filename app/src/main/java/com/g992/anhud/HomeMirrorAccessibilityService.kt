package com.g992.anhud

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class HomeMirrorAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        Log.v(TAG, "Accessibility event: ${event.eventType}")
    }

    override fun onInterrupt() {
        Log.w(TAG, "HomeMirrorAccessibilityService interrupted")
        UiLogStore.append(LogCategory.SYSTEM, "Служба доступности: прервана")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100L
            packageNames = arrayOf(
                "com.sec.android.app.launcher",
                "com.google.android.apps.nexuslauncher",
                "com.google.android.apps.nexuslauncher.debug"
            )
        }
        serviceInfo = info
        UiLogStore.append(LogCategory.SYSTEM, "Служба доступности: подключена")
    }

    companion object {
        private const val TAG = "HomeMirrorAS"
    }
}
