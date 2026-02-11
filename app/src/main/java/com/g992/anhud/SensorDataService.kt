package com.g992.anhud

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

class SensorDataService : Service() {
    private var locationManager: LocationManager? = null
    private var isLocationSubscribed = false
    private val gpsSpeedSamples = ArrayDeque<Location>()
    private var lastGpsSpeedUpdateElapsedMs: Long = 0L

    private val staleSpeedHandler = Handler(Looper.getMainLooper())
    private val staleSpeedRunnable = object : Runnable {
        override fun run() {
            clearStaleGpsSpeedIfNeeded()
            staleSpeedHandler.postDelayed(this, GPS_STALE_CHECK_INTERVAL_MS)
        }
    }

    private val speedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action != ACTION_SENSOR_FLOAT_CHANGED && action != ACTION_SENSOR_FLOAT_RESULT) {
                return
            }
            if (OverlayPrefs.speedFromGps(context)) {
                return
            }
            val id = readIntExtra(intent, EXTRA_ID) ?: return
            if (id != SENSOR_ID_CAR_SPEED) {
                return
            }
            val value = readFloatExtra(intent, EXTRA_VALUE) ?: return
            val rawSpeed = (value * MS_TO_KMH).roundToInt()
            val correction = OverlayPrefs.speedCorrection(context)
            val speedKmh = (rawSpeed + correction).coerceAtLeast(0)
            NavigationHudStore.update { current ->
                current.copy(speedKmh = speedKmh)
            }
            UiLogStore.append(
                LogCategory.SENSORS,
                "Скорость: $speedKmh км/ч (gbinder, raw=$rawSpeed, коррекция=$correction)"
            )
        }
    }

    private val gpsLocationListener = LocationListener { location ->
        handleGpsLocation(location)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UiLogStore.append(LogCategory.SENSORS, "Сервис создан")
        val filter = IntentFilter().apply {
            addAction(ACTION_SENSOR_FLOAT_CHANGED)
            addAction(ACTION_SENSOR_FLOAT_RESULT)
        }
        ContextCompat.registerReceiver(this, speedReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        staleSpeedHandler.postDelayed(staleSpeedRunnable, GPS_STALE_CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UiLogStore.append(LogCategory.SENSORS, "Сервис запущен")
        subscribeToSpeedSensor()
        ensureLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        UiLogStore.append(LogCategory.SENSORS, "Сервис остановлен")
        try {
            unregisterReceiver(speedReceiver)
        } catch (_: Exception) {
        }
        staleSpeedHandler.removeCallbacks(staleSpeedRunnable)
        stopLocationUpdates()
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

    private fun ensureLocationUpdates() {
        if (isLocationSubscribed) {
            return
        }
        if (!hasLocationPermission()) {
            UiLogStore.append(LogCategory.SENSORS, "GPS-скорость: нет разрешения на геопозицию")
            return
        }
        val manager = locationManager ?: getSystemService(LocationManager::class.java)?.also {
            locationManager = it
        } ?: run {
            UiLogStore.append(LogCategory.SENSORS, "GPS-скорость: LocationManager недоступен")
            return
        }

        val requested = requestProviderUpdates(manager, LocationManager.GPS_PROVIDER)
        if (!requested) {
            UiLogStore.append(LogCategory.SENSORS, "GPS-скорость: провайдер GPS недоступен")
            return
        }

        gpsSpeedSamples.clear()
        manager.getLastKnownLocationSafe(LocationManager.GPS_PROVIDER)?.let { lastKnown ->
            gpsSpeedSamples.addLast(Location(lastKnown))
        }

        isLocationSubscribed = true
        UiLogStore.append(LogCategory.SENSORS, "GPS-скорость: подписка активна")
    }

    private fun requestProviderUpdates(manager: LocationManager, provider: String): Boolean {
        val enabled = runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        if (!enabled) {
            return false
        }
        return try {
            manager.requestLocationUpdates(
                provider,
                GPS_MIN_UPDATE_INTERVAL_MS,
                GPS_MIN_UPDATE_DISTANCE_METERS,
                gpsLocationListener
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun stopLocationUpdates() {
        val manager = locationManager ?: return
        runCatching {
            manager.removeUpdates(gpsLocationListener)
        }
        isLocationSubscribed = false
        gpsSpeedSamples.clear()
        lastGpsSpeedUpdateElapsedMs = 0L
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun handleGpsLocation(location: Location) {
        if (!OverlayPrefs.speedFromGps(this)) {
            gpsSpeedSamples.clear()
            lastGpsSpeedUpdateElapsedMs = 0L
            return
        }

        if (!appendGpsSample(location)) {
            return
        }

        val stats = calculateGpsWindowStats() ?: return
        val normalizedSpeedKmh = if (stats.speedKmh <= GPS_STOP_SPEED_KMH) 0f else stats.speedKmh
        val speedKmh = normalizedSpeedKmh.roundToInt().coerceAtLeast(0)

        NavigationHudStore.update { current ->
            current.copy(speedKmh = speedKmh)
        }
        lastGpsSpeedUpdateElapsedMs = SystemClock.elapsedRealtime()

        UiLogStore.append(
            LogCategory.SENSORS,
            "Скорость: $speedKmh км/ч (gps-5p, points=${stats.points}, " +
                "dist=${"%.1f".format(Locale.US, stats.distanceMeters)}м, dt=${"%.2f".format(Locale.US, stats.totalSeconds)}с)"
        )
    }

    private fun appendGpsSample(location: Location): Boolean {
        val current = Location(location)
        val previous = gpsSpeedSamples.lastOrNull()
        if (previous != null) {
            val deltaSeconds = resolveDeltaSeconds(previous, current)
            if (deltaSeconds <= 0f || deltaSeconds > GPS_MAX_DELTA_SECONDS) {
                clearGpsSpeed(reason = "invalid_dt")
                gpsSpeedSamples.addLast(current)
                return false
            }
        }

        gpsSpeedSamples.addLast(current)
        while (gpsSpeedSamples.size > GPS_SPEED_WINDOW_POINTS) {
            gpsSpeedSamples.removeFirst()
        }
        return true
    }

    private fun calculateGpsWindowStats(): GpsWindowStats? {
        if (gpsSpeedSamples.size < 2) {
            return null
        }

        val samples = gpsSpeedSamples.toList()
        var totalDistanceMeters = 0f
        var totalSeconds = 0f

        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]

            val deltaSeconds = resolveDeltaSeconds(previous, current)
            if (deltaSeconds <= 0f || deltaSeconds > GPS_MAX_DELTA_SECONDS) {
                return null
            }

            val segmentDistanceMeters = previous.distanceTo(current)
            if (!segmentDistanceMeters.isFinite()) {
                return null
            }

            totalSeconds += deltaSeconds
            if (segmentDistanceMeters >= GPS_MIN_DISTANCE_FOR_SPEED_METERS) {
                totalDistanceMeters += segmentDistanceMeters
            }
        }

        if (totalSeconds <= 0f) {
            return null
        }

        val speedKmh = ((totalDistanceMeters / totalSeconds) * MS_TO_KMH).coerceAtLeast(0f)
        return GpsWindowStats(
            speedKmh = speedKmh,
            distanceMeters = totalDistanceMeters,
            totalSeconds = totalSeconds,
            points = samples.size
        )
    }

    private fun clearStaleGpsSpeedIfNeeded() {
        if (!OverlayPrefs.speedFromGps(this)) {
            return
        }
        val lastUpdate = lastGpsSpeedUpdateElapsedMs
        if (lastUpdate <= 0L) {
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - lastUpdate
        if (elapsed < GPS_SPEED_STALE_TIMEOUT_MS) {
            return
        }
        clearGpsSpeed(reason = "stale_timeout")
    }

    private fun clearGpsSpeed(reason: String) {
        gpsSpeedSamples.clear()
        lastGpsSpeedUpdateElapsedMs = 0L

        var cleared = false
        NavigationHudStore.update { current ->
            if (current.speedKmh == null) {
                current
            } else {
                cleared = true
                current.copy(speedKmh = null)
            }
        }
        if (cleared) {
            UiLogStore.append(LogCategory.SENSORS, "GPS-скорость: сброс ($reason)")
        }
    }

    private fun resolveDeltaSeconds(previous: Location, current: Location): Float {
        val prevRealtime = previous.elapsedRealtimeNanos
        val currRealtime = current.elapsedRealtimeNanos
        if (prevRealtime > 0L && currRealtime > prevRealtime) {
            return (currRealtime - prevRealtime) / NANOS_IN_SECOND
        }
        val deltaMillis = (current.time - previous.time).coerceAtLeast(0L)
        return deltaMillis / MILLIS_IN_SECOND
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

    private data class GpsWindowStats(
        val speedKmh: Float,
        val distanceMeters: Float,
        val totalSeconds: Float,
        val points: Int
    )

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
        private const val GPS_SPEED_WINDOW_POINTS = 3
        private const val GPS_MIN_UPDATE_INTERVAL_MS = 400L
        private const val GPS_MIN_UPDATE_DISTANCE_METERS = 0f
        private const val GPS_MIN_DISTANCE_FOR_SPEED_METERS = 0.5f
        private const val GPS_MAX_DELTA_SECONDS = 12f
        private const val GPS_STALE_CHECK_INTERVAL_MS = 1000L
        private const val GPS_SPEED_STALE_TIMEOUT_MS = 3000L
        private const val GPS_STOP_SPEED_KMH = 1.0f
        private const val NANOS_IN_SECOND = 1_000_000_000f
        private const val MILLIS_IN_SECOND = 1000f
    }
}

private fun LocationManager.getLastKnownLocationSafe(provider: String): Location? {
    return try {
        getLastKnownLocation(provider)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
