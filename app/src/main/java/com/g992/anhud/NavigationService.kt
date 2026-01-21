package com.g992.anhud

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

class NavigationService : Service() {
    private var navigationReceiver: NavigationReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val receiver = NavigationReceiver()
        navigationReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(NavigationReceiver.ACTION_NAV_UPDATE)
            addAction(NavigationReceiver.ACTION_NAV_ENDED)
            addAction(NavigationReceiver.ACTION_NAV_UPDATE_DEBUG)
            addAction(NavigationReceiver.ACTION_NAV_ENDED_DEBUG)
            addAction(NavigationReceiver.ACTION_YANDEX_MANEUVER)
            addAction(NavigationReceiver.ACTION_YANDEX_NEXT_TEXT)
            addAction(NavigationReceiver.ACTION_YANDEX_NEXT_STREET)
            addAction(NavigationReceiver.ACTION_YANDEX_SPEEDLIMIT)
            addAction(NavigationReceiver.ACTION_YANDEX_ARRIVAL)
            addAction(NavigationReceiver.ACTION_YANDEX_DISTANCE)
            addAction(NavigationReceiver.ACTION_YANDEX_TIME)
            addAction(NavigationReceiver.ACTION_YANDEX_TRAFFICLIGHT)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        Log.d(TAG, "Navigation receiver registered")
        UiLogStore.append(LogCategory.SYSTEM, "NavigationService: ресивер зарегистрирован")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            navigationReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        } finally {
            navigationReceiver = null
        }
        UiLogStore.append(LogCategory.SYSTEM, "NavigationService: остановлен")
    }

    companion object {
        private const val TAG = "NavigationService"
    }
}
