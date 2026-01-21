package com.g992.anhud

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class SensorDataService : Service() {
    private val speedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action != ACTION_SENSOR_FLOAT_CHANGED && action != ACTION_SENSOR_FLOAT_RESULT) {
                return
            }
            val id = readIntExtra(intent, EXTRA_ID) ?: return
            if (id != SENSOR_ID_CAR_SPEED) {
                return
            }
            val value = readFloatExtra(intent, EXTRA_VALUE) ?: return
            val speedKmh = (value * MS_TO_KMH).roundToInt().coerceAtLeast(0)
            NavigationHudStore.update { current ->
                current.copy(speedKmh = speedKmh)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UiLogStore.append(LogCategory.SENSORS, "Сервис создан")
        val filter = IntentFilter().apply {
            addAction(ACTION_SENSOR_FLOAT_CHANGED)
            addAction(ACTION_SENSOR_FLOAT_RESULT)
        }
        ContextCompat.registerReceiver(this, speedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UiLogStore.append(LogCategory.SENSORS, "Сервис запущен")
        subscribeToSpeedSensor()
        return START_STICKY
    }

    override fun onDestroy() {
        UiLogStore.append(LogCategory.SENSORS, "Сервис остановлен")
        try {
            unregisterReceiver(speedReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun subscribeToSpeedSensor() {
        val listenIntent = Intent(ACTION_LISTEN_SENSOR_CHANGES).apply {
            setPackage(GBINDER_PACKAGE)
            putExtra(EXTRA_ID, SENSOR_ID_CAR_SPEED)
        }
        sendBroadcast(listenIntent)
        val getIntent = Intent(ACTION_GET_FLOAT_SENSOR).apply {
            setPackage(GBINDER_PACKAGE)
            putExtra(EXTRA_ID, SENSOR_ID_CAR_SPEED)
        }
        sendBroadcast(getIntent)
    }

    private fun readIntExtra(intent: Intent, key: String): Int? {
        val intValue = intent.getIntExtra(key, 0)
        if (intValue != 0) {
            return intValue
        }
        val stringValue = intent.getStringExtra(key) ?: return null
        return stringValue.toIntOrNull()
    }

    private fun readFloatExtra(intent: Intent, key: String): Float? {
        val floatValue = intent.getFloatExtra(key, Float.NaN)
        if (!floatValue.isNaN()) {
            return floatValue
        }
        val doubleValue = intent.getDoubleExtra(key, Double.NaN)
        if (!doubleValue.isNaN()) {
            return doubleValue.toFloat()
        }
        val stringValue = intent.getStringExtra(key) ?: return null
        return stringValue.toFloatOrNull()
    }

    companion object {
        private const val GBINDER_PACKAGE = "com.salat.gbinder"
        private const val ACTION_GET_FLOAT_SENSOR = "com.salat.gbinder.GET_FLOAT_SENSOR"
        private const val ACTION_LISTEN_SENSOR_CHANGES = "com.salat.gbinder.LISTEN_SENSOR_CHANGES"
        private const val ACTION_SENSOR_FLOAT_RESULT = "com.salat.gbinder.SENSOR_FLOAT_RESULT"
        private const val ACTION_SENSOR_FLOAT_CHANGED = "com.salat.gbinder.SENSOR_FLOAT_CHANGED"
        private const val EXTRA_ID = "id"
        private const val EXTRA_VALUE = "value"
        private const val SENSOR_ID_CAR_SPEED = 1048832
        private const val MS_TO_KMH = 3.6f
    }
}
