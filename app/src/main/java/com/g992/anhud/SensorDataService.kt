package com.g992.anhud

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class SensorDataService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SensorDataService created")
        UiLogStore.append(LogCategory.SENSORS, "Сервис создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UiLogStore.append(LogCategory.SENSORS, "Сервис запущен")
        return START_STICKY
    }

    override fun onDestroy() {
        UiLogStore.append(LogCategory.SENSORS, "Сервис остановлен")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SensorDataService"
    }
}
