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
            addAction(NavigationReceiver.ACTION_NAV_UPDATE_DEBUG)
            addAction(NavigationReceiver.ACTION_YANDEX_MANEUVER)
            addAction(NavigationReceiver.ACTION_YANDEX_NEXT_TEXT)
            addAction(NavigationReceiver.ACTION_YANDEX_NEXT_STREET)
            addAction(NavigationReceiver.ACTION_YANDEX_SPEEDLIMIT)
            addAction(NavigationReceiver.ACTION_YANDEX_ARRIVAL)
            addAction(NavigationReceiver.ACTION_YANDEX_DISTANCE)
            addAction(NavigationReceiver.ACTION_YANDEX_TIME)
            addAction(NavigationReceiver.ACTION_YANDEX_NAV_ACTIVE)
            addAction(NavigationReceiver.ACTION_YANDEX_ROADCAMERA)
            addAction(NavigationReceiver.ACTION_YANDEX_TRAFFICLIGHT)
            addAction(NavigationReceiver.ACTION_YANDEX_ROUTE_POLYLINE)
            addAction(NavigationReceiver.ACTION_NATIVE_NAV_STOP)
            addAction(NavigationReceiver.ACTION_HUDSPEED_UPDATE)
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
