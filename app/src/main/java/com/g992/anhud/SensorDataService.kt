package com.g992.anhud

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

class SensorDataService : Service() {
    private var locationManager: LocationManager? = null
    private var isLocationSubscribed = false
    private val gpsSpeedSamples = ArrayDeque<Location>()
    private var lastGpsSpeedUpdateElapsedMs: Long = 0L
    private var lastGbinderSpeedKmh: Int? = null
    private var lastGbinderSpeedChangedElapsedMs: Long = 0L
    private var carProxyBinder: IBinder? = null
    private var carProxyConnection: ServiceConnection? = null
    private var lastTurnSignalRaw: Int? = null

    private val staleSpeedHandler = Handler(Looper.getMainLooper())
    private val staleSpeedRunnable = object : Runnable {
        override fun run() {
            clearStaleGpsSpeedIfNeeded()
            resubscribeGbinderSpeedIfNeeded()
            staleSpeedHandler.postDelayed(this, GPS_STALE_CHECK_INTERVAL_MS)
        }
    }
    private val turnSignalPollRunnable = object : Runnable {
        override fun run() {
            pollTurnSignalFromCarProxy()
            staleSpeedHandler.postDelayed(this, TURN_SIGNAL_POLL_INTERVAL_MS)
        }
    }
    private val carProxyReconnectRunnable = Runnable { bindTurnSignalCarProxy() }

    private val vehicleDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                ACTION_SENSOR_FLOAT_CHANGED,
                ACTION_SENSOR_FLOAT_RESULT -> handleSpeedIntent(context, intent)
            }
        }
    }

    private val gpsLocationListener = LocationListener { location -> handleGpsLocation(location) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UiLogStore.append(LogCategory.SENSORS, "Сервис создан")
        val vehicleFilter = IntentFilter().apply {
            addAction(ACTION_SENSOR_FLOAT_CHANGED)
            addAction(ACTION_SENSOR_FLOAT_RESULT)
        }
        ContextCompat.registerReceiver(this, vehicleDataReceiver, vehicleFilter, ContextCompat.RECEIVER_EXPORTED)
        staleSpeedHandler.postDelayed(staleSpeedRunnable, GPS_STALE_CHECK_INTERVAL_MS)
        initTurnSignalIntegration()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UiLogStore.append(LogCategory.SENSORS, "Сервис запущен")
        subscribeToSpeedSensor()
        subscribeToTurnSignalSensors()
        ensureLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        UiLogStore.append(LogCategory.SENSORS, "Сервис остановлен")
        try { unregisterReceiver(vehicleDataReceiver) } catch (_: Exception) {}
        staleSpeedHandler.removeCallbacks(staleSpeedRunnable)
        staleSpeedHandler.removeCallbacks(turnSignalPollRunnable)
        staleSpeedHandler.removeCallbacks(carProxyReconnectRunnable)
        unbindTurnSignalCarProxy()
        clearTurnSignalState()
        resetGbinderSpeedWatchdog()
        stopLocationUpdates()
        super.onDestroy()
    }

    private fun subscribeToSpeedSensor() {
        sendBroadcast(Intent(ACTION_LISTEN_SENSOR_CHANGES).apply {
            setPackage(GBINDER_PACKAGE)
            putExtra(EXTRA_ID, SENSOR_ID_CAR_SPEED)
        })
        sendBroadcast(Intent(ACTION_GET_FLOAT_SENSOR).apply {
            setPackage(GBINDER_PACKAGE)
            putExtra(EXTRA_ID, SENSOR_ID_CAR_SPEED)
        })
    }

    private fun initTurnSignalIntegration() {
        lastTurnSignalRaw = null
        clearTurnSignalState()
    }

    private fun subscribeToTurnSignalSensors() {
        bindTurnSignalCarProxy()
        staleSpeedHandler.removeCallbacks(turnSignalPollRunnable)
        staleSpeedHandler.postDelayed(turnSignalPollRunnable, TURN_SIGNAL_BIND_GRACE_MS)
    }

    private fun clearTurnSignalState() {
        lastTurnSignalRaw = null
        NavigationHudStore.update { current ->
            if (!current.turnSignalLeft && !current.turnSignalRight && !current.turnSignalHazard) {
                current
            } else {
                current.copy(
                    turnSignalLeft = false,
                    turnSignalRight = false,
                    turnSignalHazard = false
                )
            }
        }
    }

    private fun handleSpeedIntent(context: Context, intent: Intent) {
        if (OverlayPrefs.speedFromGps(context)) {
            resetGbinderSpeedWatchdog()
            return
        }
        val id = readIntExtra(intent, EXTRA_ID) ?: return
        if (id != SENSOR_ID_CAR_SPEED) return
        val value = readFloatExtra(intent, EXTRA_VALUE) ?: return
        val rawSpeed = (value * MS_TO_KMH).roundToInt()
        val correction = OverlayPrefs.speedCorrection(context)
        val speedKmh = (rawSpeed + correction).coerceAtLeast(0)
        markGbinderSpeedObserved(speedKmh)
        NavigationHudStore.update { current -> current.copy(speedKmh = speedKmh) }
        UiLogStore.append(
            LogCategory.SENSORS,
            "Скорость: $speedKmh км/ч (gbinder, raw=$rawSpeed, коррекция=$correction)"
        )
    }

    private fun publishTurnSignalState(state: TurnSignalResolvedState) {
        var changed = false
        NavigationHudStore.update { current ->
            if (
                current.turnSignalLeft == state.left &&
                current.turnSignalRight == state.right &&
                current.turnSignalHazard == state.hazard
            ) {
                current
            } else {
                changed = true
                current.copy(
                    turnSignalLeft = state.left,
                    turnSignalRight = state.right,
                    turnSignalHazard = state.hazard
                )
            }
        }
        if (changed) {
            UiLogStore.append(
                LogCategory.SENSORS,
                "Поворотники: L=${if (state.left) 1 else 0} R=${if (state.right) 1 else 0} A=${if (state.hazard) 1 else 0} (${state.source})"
            )
        }
    }

    private fun bindTurnSignalCarProxy() {
        if (carProxyConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                carProxyBinder = service
                if (service == null) {
                    UiLogStore.append(LogCategory.SENSORS, "Поворотники: CarProxy подключен без binder")
                    scheduleTurnSignalReconnect()
                    return
                }
                val subscribed = subscribeTurnSignalProperty()
                UiLogStore.append(
                    LogCategory.SENSORS,
                    if (subscribed) {
                        "Поворотники: CarProxy подключен, подписка на $PROP_TURN_SIGNAL активна"
                    } else {
                        "Поворотники: CarProxy подключен, но подписка на $PROP_TURN_SIGNAL не удалась"
                    }
                )
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleCarProxyDisconnect("service disconnected")
            }

            override fun onBindingDied(name: ComponentName?) {
                handleCarProxyDisconnect("binding died")
            }

            override fun onNullBinding(name: ComponentName?) {
                handleCarProxyDisconnect("null binding")
            }
        }
        val intent = Intent().setComponent(ComponentName(CAR_PROXY_PACKAGE, CAR_PROXY_SERVICE))
        val bound = runCatching { bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (bound) {
            carProxyConnection = connection
            UiLogStore.append(LogCategory.SENSORS, "Поворотники: запрос bind к CarProxy отправлен")
        } else {
            UiLogStore.append(LogCategory.SENSORS, "Поворотники: bindService к CarProxy вернул false")
            scheduleTurnSignalReconnect()
        }
    }

    private fun unbindTurnSignalCarProxy() {
        carProxyConnection?.let { connection ->
            runCatching { unbindService(connection) }
        }
        carProxyBinder = null
        carProxyConnection = null
    }

    private fun handleCarProxyDisconnect(reason: String) {
        carProxyBinder = null
        carProxyConnection = null
        UiLogStore.append(LogCategory.SENSORS, "Поворотники: CarProxy отключен ($reason)")
        scheduleTurnSignalReconnect()
    }

    private fun scheduleTurnSignalReconnect() {
        staleSpeedHandler.removeCallbacks(carProxyReconnectRunnable)
        staleSpeedHandler.postDelayed(carProxyReconnectRunnable, TURN_SIGNAL_RECONNECT_INTERVAL_MS)
    }

    private fun subscribeTurnSignalProperty(): Boolean {
        return transactCarProxyInt(TX_ADD_EVENT, PROP_TURN_SIGNAL) != null
    }

    private fun readTurnSignalFromCarProxy(): Int? {
        return transactCarProxyInt(TX_GET_INT, PROP_TURN_SIGNAL)
    }

    private fun transactCarProxyInt(code: Int, propertyId: Int): Int? {
        val binder = carProxyBinder ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CAR_PROXY_DESCRIPTOR)
            data.writeInt(propertyId)
            val success = binder.transact(code, data, reply, 0)
            if (!success) return null
            reply.readException()
            reply.readInt()
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun pollTurnSignalFromCarProxy() {
        val raw = readTurnSignalFromCarProxy() ?: return
        if (raw == lastTurnSignalRaw && raw !in 0..3) return
        lastTurnSignalRaw = raw
        val state = decodeCarProxyTurnSignal(raw) ?: run {
            UiLogStore.append(LogCategory.SENSORS, "Поворотники: CarProxy raw=$raw проигнорирован")
            return
        }
        publishTurnSignalState(state)
    }

    private fun ensureLocationUpdates() {
        if (isLocationSubscribed) return
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
        if (!enabled) return false
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
        runCatching { manager.removeUpdates(gpsLocationListener) }
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
        if (!appendGpsSample(location)) return
        val stats = calculateGpsWindowStats() ?: return
        val normalizedSpeedKmh = if (stats.speedKmh <= GPS_STOP_SPEED_KMH) 0f else stats.speedKmh
        val speedKmh = normalizedSpeedKmh.roundToInt().coerceAtLeast(0)
        NavigationHudStore.update { current -> current.copy(speedKmh = speedKmh) }
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
        while (gpsSpeedSamples.size > GPS_SPEED_WINDOW_POINTS) gpsSpeedSamples.removeFirst()
        return true
    }

    private fun calculateGpsWindowStats(): GpsWindowStats? {
        if (gpsSpeedSamples.size < 2) return null
        val samples = gpsSpeedSamples.toList()
        var totalDistanceMeters = 0f
        var totalSeconds = 0f
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val deltaSeconds = resolveDeltaSeconds(previous, current)
            if (deltaSeconds <= 0f || deltaSeconds > GPS_MAX_DELTA_SECONDS) return null
            val segmentDistanceMeters = previous.distanceTo(current)
            if (!segmentDistanceMeters.isFinite()) return null
            totalSeconds += deltaSeconds
            if (segmentDistanceMeters >= GPS_MIN_DISTANCE_FOR_SPEED_METERS) {
                totalDistanceMeters += segmentDistanceMeters
            }
        }
        if (totalSeconds <= 0f) return null
        val speedKmh = ((totalDistanceMeters / totalSeconds) * MS_TO_KMH).coerceAtLeast(0f)
        return GpsWindowStats(
            speedKmh = speedKmh,
            distanceMeters = totalDistanceMeters,
            totalSeconds = totalSeconds,
            points = samples.size
        )
    }

    private fun clearStaleGpsSpeedIfNeeded() {
        if (!OverlayPrefs.speedFromGps(this)) return
        val lastUpdate = lastGpsSpeedUpdateElapsedMs
        if (lastUpdate <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - lastUpdate
        if (elapsed < GPS_SPEED_STALE_TIMEOUT_MS) return
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
        if (cleared) UiLogStore.append(LogCategory.SENSORS, "GPS-скорость: сброс ($reason)")
    }

    private fun markGbinderSpeedObserved(speedKmh: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastGbinderSpeedKmh != speedKmh) {
            lastGbinderSpeedKmh = speedKmh
            lastGbinderSpeedChangedElapsedMs = now
            return
        }
        if (lastGbinderSpeedChangedElapsedMs <= 0L) {
            lastGbinderSpeedChangedElapsedMs = now
        }
    }

    private fun resetGbinderSpeedWatchdog() {
        lastGbinderSpeedKmh = null
        lastGbinderSpeedChangedElapsedMs = 0L
    }

    private fun resubscribeGbinderSpeedIfNeeded() {
        if (OverlayPrefs.speedFromGps(this)) {
            resetGbinderSpeedWatchdog()
            return
        }
        val timeoutSeconds = OverlayPrefs.speedometerFreezeTimeout(this)
        if (timeoutSeconds <= 0) return
        val speedKmh = lastGbinderSpeedKmh ?: return
        val lastChanged = lastGbinderSpeedChangedElapsedMs
        if (lastChanged <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - lastChanged
        if (elapsed < timeoutSeconds * MILLIS_PER_SECOND_LONG) return
        UiLogStore.append(
            LogCategory.SENSORS,
            "Скорость не менялась $timeoutSeconds c (gbinder=$speedKmh), переподписка на датчик"
        )
        subscribeToSpeedSensor()
        lastGbinderSpeedChangedElapsedMs = SystemClock.elapsedRealtime()
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
        val value = readIntExtraAllowZero(intent, key) ?: return null
        return value.takeIf { it != 0 }
    }

    private fun readIntExtraAllowZero(intent: Intent, key: String): Int? {
        val extras = intent.extras ?: return null
        if (!extras.containsKey(key)) return null
        @Suppress("DEPRECATION")
        return when (val raw = extras.get(key)) {
            is Int -> raw
            is Long -> raw.toInt()
            is Float -> raw.toInt()
            is Double -> raw.toInt()
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            is Boolean -> if (raw) 1 else 0
            else -> raw?.toString()?.toIntOrNull()
        }
    }

    private fun readFloatExtra(intent: Intent, key: String): Float? {
        val extras = intent.extras ?: return null
        if (!extras.containsKey(key)) return null
        @Suppress("DEPRECATION")
        return when (val raw = extras.get(key)) {
            is Float -> raw
            is Double -> raw.toFloat()
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull()
            is Boolean -> if (raw) 1f else 0f
            else -> raw?.toString()?.toFloatOrNull()
        }
    }

    private fun decodeCarProxyTurnSignal(raw: Int): TurnSignalResolvedState? {
        if (raw < 0 || raw == TURN_SIGNAL_INVALID_VALUE) return null
        val left = raw == 1 || raw == 3
        val right = raw == 2 || raw == 3
        val hazard = raw == 3
        if (raw !in 0..3) return null
        return TurnSignalResolvedState(
            left = left,
            right = right,
            hazard = hazard,
            source = "carproxy:$PROP_TURN_SIGNAL raw:$raw"
        )
    }

    private data class GpsWindowStats(
        val speedKmh: Float,
        val distanceMeters: Float,
        val totalSeconds: Float,
        val points: Int
    )

    private data class TurnSignalResolvedState(
        val left: Boolean,
        val right: Boolean,
        val hazard: Boolean,
        val source: String
    )

    companion object {
        private const val GBINDER_PACKAGE = "com.salat.gbinder"
        private const val ACTION_GET_FLOAT_SENSOR = "com.salat.gbinder.GET_FLOAT_SENSOR"
        private const val ACTION_LISTEN_SENSOR_CHANGES = "com.salat.gbinder.LISTEN_SENSOR_CHANGES"
        private const val ACTION_SENSOR_FLOAT_RESULT = "com.salat.gbinder.SENSOR_FLOAT_RESULT"
        private const val ACTION_SENSOR_FLOAT_CHANGED = "com.salat.gbinder.SENSOR_FLOAT_CHANGED"
        private const val EXTRA_ID = "id"
        private const val EXTRA_VALUE = "value"
        private const val SENSOR_ID_CAR_SPEED = 1055232
        private const val CAR_PROXY_PACKAGE = "com.autolink.carproxyservice"
        private const val CAR_PROXY_SERVICE = "com.autolink.carproxyservice.CarProxyService"
        private const val CAR_PROXY_DESCRIPTOR = "com.autolink.adapterbinder.ICarProxyService"
        private const val TX_ADD_EVENT = 3
        private const val TX_GET_INT = 5
        private const val PROP_TURN_SIGNAL = 557848166
        private const val TURN_SIGNAL_INVALID_VALUE = 255
        private const val TURN_SIGNAL_BIND_GRACE_MS = 500L
        private const val TURN_SIGNAL_POLL_INTERVAL_MS = 150L
        private const val TURN_SIGNAL_RECONNECT_INTERVAL_MS = 1500L
        private const val MS_TO_KMH = 3.6f
        private const val GPS_SPEED_WINDOW_POINTS = 3
        private const val GPS_MIN_UPDATE_INTERVAL_MS = 400L
        private const val GPS_MIN_UPDATE_DISTANCE_METERS = 0f
        private const val GPS_MIN_DISTANCE_FOR_SPEED_METERS = 0.5f
        private const val GPS_MAX_DELTA_SECONDS = 12f
        private const val GPS_STALE_CHECK_INTERVAL_MS = 1000L
        private const val GPS_SPEED_STALE_TIMEOUT_MS = 3000L
        private const val GPS_STOP_SPEED_KMH = 1.0f
        private const val MILLIS_PER_SECOND_LONG = 1000L
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
