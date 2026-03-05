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
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

class SensorDataService : Service() {
    private var locationManager: LocationManager? = null
    private var isLocationSubscribed = false
    private val gpsSpeedSamples = ArrayDeque<Location>()
    private var lastGpsSpeedUpdateElapsedMs: Long = 0L
    private var lastTurnSignalState: TurnSignalState? = null
    private var lastLeftTurnState: Boolean? = null  // Для отдельных Geely/Ecarx сенсоров
    private var lastRightTurnState: Boolean? = null // Для отдельных Geely/Ecarx сенсоров
    private var hasVehicleTurnSignalSource = false
    private var hasGeelyTurnSignalSource = false
    private var turnSignalSourceLogSuppressed = false
    private var invalidVehicleTurnSignalLogSuppressed = false
    private var lastTurnSignalEventSignature: String? = null

    private val staleSpeedHandler = Handler(Looper.getMainLooper())
    private val staleSpeedRunnable = object : Runnable {
        override fun run() {
            clearStaleGpsSpeedIfNeeded()
            staleSpeedHandler.postDelayed(this, GPS_STALE_CHECK_INTERVAL_MS)
        }
    }
    private val turnSignalPollHandler = Handler(Looper.getMainLooper())
    private val turnSignalPollRunnable = object : Runnable {
        override fun run() {
            requestTurnSignalSnapshots()
            turnSignalPollHandler.postDelayed(this, TURN_SIGNAL_POLL_INTERVAL_MS)
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

    private val turnSignalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action != ACTION_SENSOR_INT_CHANGED &&
                action != ACTION_SENSOR_INT_RESULT &&
                action != ACTION_PROPERTY_INT_CHANGED &&
                action != ACTION_PROPERTY_INT_RESULT
            ) {
                return
            }
            val id = readIntExtra(intent, EXTRA_ID) ?: return
            if (id !in TURN_SIGNAL_CANDIDATE_IDS) {
                return
            }
            // Игнорируем SENSOR_ID_LIGHT_STATE если он приходит как property (должен быть sensor)
            if (id == SENSOR_ID_LIGHT_STATE &&
                (action == ACTION_PROPERTY_INT_CHANGED || action == ACTION_PROPERTY_INT_RESULT)
            ) {
                return
            }
            // Geely/Ecarx sensor ID работают только как sensors, не properties
            if ((id == SENSOR_ID_LEFT_TURN || id == SENSOR_ID_RIGHT_TURN) &&
                (action == ACTION_PROPERTY_INT_CHANGED || action == ACTION_PROPERTY_INT_RESULT)
            ) {
                return
            }
            val rawValue = readIntExtraAllowZero(intent, EXTRA_VALUE) ?: return
            val areaId = readIntExtraAllowZero(intent, EXTRA_AREA_ID)
            logRawTurnSignalEventIfChanged(id = id, action = action, rawValue = rawValue, areaId = areaId)
            val state = decodeTurnSignalState(id, rawValue)
            if (state == null) {
                onInvalidTurnSignalPayload(id, rawValue)
                return
            }
            if (shouldIgnoreTurnSignalEvent(id)) {
                return
            }
            if (id == VEHICLE_PROPERTY_TURN_SIGNAL_STATE) {
                invalidVehicleTurnSignalLogSuppressed = false
            }

            // Для Geely/Ecarx объединяем состояние от отдельных сенсоров
            val finalState = if (id == SENSOR_ID_LEFT_TURN || id == SENSOR_ID_RIGHT_TURN) {
                if (id == SENSOR_ID_LEFT_TURN) {
                    lastLeftTurnState = state.left
                }
                if (id == SENSOR_ID_RIGHT_TURN) {
                    lastRightTurnState = state.right
                }
                val combinedLeft = lastLeftTurnState ?: false
                val combinedRight = lastRightTurnState ?: false
                val combinedHazard = combinedLeft && combinedRight
                TurnSignalState(
                    left = combinedLeft,
                    right = combinedRight,
                    hazard = combinedHazard
                )
            } else {
                state
            }

            val previous = lastTurnSignalState
            if (previous == finalState) {
                return
            }
            lastTurnSignalState = finalState
            applyTurnSignalState(finalState)
            val areaText = areaId?.let { " area=$it" }.orEmpty()
            val sourceText = when (id) {
                SENSOR_ID_LEFT_TURN -> "geely_left"
                SENSOR_ID_RIGHT_TURN -> "geely_right"
                VEHICLE_PROPERTY_TURN_SIGNAL_STATE -> "vehicle_property"
                SENSOR_ID_LIGHT_STATE -> "sensor_light"
                else -> "unknown"
            }
            val message =
                "Поворотники: left=${finalState.left} right=${finalState.right} hazard=${finalState.hazard} " +
                    "(raw=$rawValue id=$id source=$sourceText action=$action$areaText)"
            Log.i(TAG, message)
            UiLogStore.append(LogCategory.SENSORS, message)
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
        ContextCompat.registerReceiver(this, speedReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        val turnFilter = IntentFilter().apply {
            addAction(ACTION_SENSOR_INT_CHANGED)
            addAction(ACTION_SENSOR_INT_RESULT)
            addAction(ACTION_PROPERTY_INT_CHANGED)
            addAction(ACTION_PROPERTY_INT_RESULT)
        }
        ContextCompat.registerReceiver(this, turnSignalReceiver, turnFilter, ContextCompat.RECEIVER_EXPORTED)
        staleSpeedHandler.postDelayed(staleSpeedRunnable, GPS_STALE_CHECK_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UiLogStore.append(LogCategory.SENSORS, "Сервис запущен")
        subscribeToSpeedSensor()
        subscribeToTurnSignals()
        requestTurnSignalSnapshots()
        turnSignalPollHandler.removeCallbacks(turnSignalPollRunnable)
        turnSignalPollHandler.postDelayed(turnSignalPollRunnable, TURN_SIGNAL_POLL_INTERVAL_MS)
        ensureLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        UiLogStore.append(LogCategory.SENSORS, "Сервис остановлен")
        try {
            unregisterReceiver(speedReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(turnSignalReceiver)
        } catch (_: Exception) {
        }
        staleSpeedHandler.removeCallbacks(staleSpeedRunnable)
        turnSignalPollHandler.removeCallbacks(turnSignalPollRunnable)
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

    private fun subscribeToTurnSignals() {
        TURN_SIGNAL_CANDIDATE_IDS.forEach { id ->
            sendBroadcast(
                Intent(ACTION_LISTEN_SENSOR_CHANGES).apply {
                    setPackage(GBINDER_PACKAGE)
                    putExtra(EXTRA_ID, id)
                }
            )
            sendBroadcast(
                Intent(ACTION_GET_INT_SENSOR).apply {
                    setPackage(GBINDER_PACKAGE)
                    putExtra(EXTRA_ID, id)
                }
            )
            sendBroadcast(
                Intent(ACTION_LISTEN_PROPERTY_CHANGES).apply {
                    setPackage(GBINDER_PACKAGE)
                    putExtra(EXTRA_ID, id)
                    putExtra(EXTRA_VALUE, "*")
                }
            )
            sendBroadcast(
                Intent(ACTION_GET_INT_PROPERTY).apply {
                    setPackage(GBINDER_PACKAGE)
                    putExtra(EXTRA_ID, id)
                }
            )
        }
        UiLogStore.append(
            LogCategory.SENSORS,
            "Поворотники: подписка на id=${TURN_SIGNAL_CANDIDATE_IDS.joinToString()}"
        )
    }

    private fun requestTurnSignalSnapshots() {
        TURN_SIGNAL_CANDIDATE_IDS.forEach { id ->
            sendBroadcast(
                Intent(ACTION_GET_INT_SENSOR).apply {
                    setPackage(GBINDER_PACKAGE)
                    putExtra(EXTRA_ID, id)
                }
            )
            sendBroadcast(
                Intent(ACTION_GET_INT_PROPERTY).apply {
                    setPackage(GBINDER_PACKAGE)
                    putExtra(EXTRA_ID, id)
                }
            )
        }
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
        val value = readIntExtraAllowZero(intent, key) ?: return null
        return value.takeIf { it != 0 }
    }

    private fun readIntExtraAllowZero(intent: Intent, key: String): Int? {
        val extras = intent.extras ?: return null
        if (!extras.containsKey(key)) {
            return null
        }
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

    private fun logRawTurnSignalEventIfChanged(
        id: Int,
        action: String,
        rawValue: Int,
        areaId: Int?
    ) {
        val signature = "$id|$action|$rawValue|${areaId ?: "na"}"
        if (signature == lastTurnSignalEventSignature) {
            return
        }
        lastTurnSignalEventSignature = signature
        val areaText = areaId?.let { " area=$it" }.orEmpty()
        val message = "Поворотники(raw): id=$id action=$action raw=$rawValue$areaText"
        Log.i(TAG, message)
        UiLogStore.append(LogCategory.SENSORS, message)
    }

    private fun onInvalidTurnSignalPayload(id: Int, rawValue: Int) {
        if (id != VEHICLE_PROPERTY_TURN_SIGNAL_STATE) {
            return
        }
        if (invalidVehicleTurnSignalLogSuppressed) {
            return
        }
        UiLogStore.append(
            LogCategory.SENSORS,
            "Поворотники: игнор невалидного vehicle_property значения raw=$rawValue"
        )
        invalidVehicleTurnSignalLogSuppressed = true
    }

    private fun shouldIgnoreTurnSignalEvent(id: Int): Boolean {
        // Приоритет 1: Geely/Ecarx отдельные сенсоры (самый надежный)
        if (id == SENSOR_ID_LEFT_TURN || id == SENSOR_ID_RIGHT_TURN) {
            if (!hasGeelyTurnSignalSource) {
                UiLogStore.append(LogCategory.SENSORS, "Поворотники: источник переключен на geely/ecarx sensors")
            }
            hasGeelyTurnSignalSource = true
            hasVehicleTurnSignalSource = false
            turnSignalSourceLogSuppressed = false
            return false
        }

        // Приоритет 2: VehicleProperty (стандартный AOSP)
        if (id == VEHICLE_PROPERTY_TURN_SIGNAL_STATE) {
            // Игнорируем если уже работают Geely сенсоры
            if (hasGeelyTurnSignalSource) {
                if (!turnSignalSourceLogSuppressed) {
                    UiLogStore.append(
                        LogCategory.SENSORS,
                        "Поворотники: события vehicle_property игнорируются (есть geely/ecarx)"
                    )
                    turnSignalSourceLogSuppressed = true
                }
                return true
            }
            if (!hasVehicleTurnSignalSource) {
                UiLogStore.append(LogCategory.SENSORS, "Поворотники: источник переключен на vehicle_property")
            }
            hasVehicleTurnSignalSource = true
            turnSignalSourceLogSuppressed = false
            return false
        }

        // Приоритет 3: Legacy SENSOR_ID_LIGHT_STATE (fallback)
        if (id == SENSOR_ID_LIGHT_STATE) {
            // Игнорируем если уже работают более приоритетные источники
            if (hasGeelyTurnSignalSource || hasVehicleTurnSignalSource) {
                if (!turnSignalSourceLogSuppressed) {
                    val activeSource = if (hasGeelyTurnSignalSource) "geely/ecarx" else "vehicle_property"
                    UiLogStore.append(
                        LogCategory.SENSORS,
                        "Поворотники: события sensor_light игнорируются (есть $activeSource)"
                    )
                    turnSignalSourceLogSuppressed = true
                }
                return true
            }
            return false
        }

        return false
    }

    private fun decodeTurnSignalState(id: Int, raw: Int): TurnSignalState? {
        return when (id) {
            SENSOR_ID_LEFT_TURN -> {
                // Geely/Ecarx левый поворотник: 0=выкл, 1=вкл
                val isActive = raw != 0
                TurnSignalState(left = isActive, right = false, hazard = false)
            }
            SENSOR_ID_RIGHT_TURN -> {
                // Geely/Ecarx правый поворотник: 0=выкл, 1=вкл
                val isActive = raw != 0
                TurnSignalState(left = false, right = isActive, hazard = false)
            }
            VEHICLE_PROPERTY_TURN_SIGNAL_STATE -> decodeVehiclePropertyTurnSignalState(raw)
            SENSOR_ID_LIGHT_STATE -> decodeLegacySensorLightTurnSignalState(raw)
            else -> decodeBitmaskTurnSignalState(raw, 0x1, 0x2, 0x4)
        }
    }

    private fun decodeVehiclePropertyTurnSignalState(raw: Int): TurnSignalState? {
        if (raw < 0) {
            return null
        }
        if ((raw and VEHICLE_TURN_SIGNAL_KNOWN_MASK.inv()) != 0) {
            return null
        }
        // VehicleProperty TURN_SIGNAL_STATE (AOSP): RIGHT=0x1, LEFT=0x2, EMERGENCY=0x4.
        return decodeBitmaskTurnSignalState(
            raw = raw,
            leftMask = VEHICLE_TURN_SIGNAL_LEFT_MASK,
            rightMask = VEHICLE_TURN_SIGNAL_RIGHT_MASK,
            hazardMask = VEHICLE_TURN_SIGNAL_HAZARD_MASK
        )
    }

    private fun decodeLegacySensorLightTurnSignalState(raw: Int): TurnSignalState {
        // Some gbinder builds expose SENSOR_ID_LIGHT_STATE as enum: 0=none, 1=left, 2=right, 3=hazard.
        val normalizedRaw = normalizeLegacySensorLightState(raw)
        return when (normalizedRaw) {
            LEGACY_LIGHT_STATE_NONE -> TurnSignalState(left = false, right = false, hazard = false)
            LEGACY_LIGHT_STATE_LEFT -> TurnSignalState(left = true, right = false, hazard = false)
            LEGACY_LIGHT_STATE_RIGHT -> TurnSignalState(left = false, right = true, hazard = false)
            LEGACY_LIGHT_STATE_HAZARD -> TurnSignalState(left = true, right = true, hazard = true)
            else -> decodeBitmaskTurnSignalState(normalizedRaw, 0x1, 0x2, 0x4)
        }
    }

    private fun normalizeLegacySensorLightState(raw: Int): Int {
        if (raw >= SENSOR_ID_LIGHT_STATE && raw <= SENSOR_ID_LIGHT_STATE + LEGACY_LIGHT_STATE_MAX_DELTA) {
            return raw - SENSOR_ID_LIGHT_STATE
        }
        if (raw in 0..LEGACY_LIGHT_STATE_MAX_DELTA) {
            return raw
        }
        return raw and 0xFF
    }

    private fun decodeBitmaskTurnSignalState(
        raw: Int,
        leftMask: Int,
        rightMask: Int,
        hazardMask: Int
    ): TurnSignalState {
        val hazardBit = (raw and hazardMask) != 0
        val leftBit = (raw and leftMask) != 0
        val rightBit = (raw and rightMask) != 0
        return TurnSignalState(
            left = leftBit || hazardBit,
            right = rightBit || hazardBit,
            hazard = hazardBit
        )
    }

    private fun applyTurnSignalState(state: TurnSignalState) {
        NavigationHudStore.update { current ->
            if (current.turnSignalLeft == state.left &&
                current.turnSignalRight == state.right &&
                current.turnSignalHazard == state.hazard
            ) {
                current
            } else {
                current.copy(
                    turnSignalLeft = state.left,
                    turnSignalRight = state.right,
                    turnSignalHazard = state.hazard
                )
            }
        }
    }

    private fun readFloatExtra(intent: Intent, key: String): Float? {
        val extras = intent.extras ?: return null
        if (!extras.containsKey(key)) {
            return null
        }
        return when (val raw = extras.get(key)) {
            is Float -> raw
            is Double -> raw.toFloat()
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull()
            is Boolean -> if (raw) 1f else 0f
            else -> raw?.toString()?.toFloatOrNull()
        }
    }

    private data class GpsWindowStats(
        val speedKmh: Float,
        val distanceMeters: Float,
        val totalSeconds: Float,
        val points: Int
    )

    private data class TurnSignalState(
        val left: Boolean,
        val right: Boolean,
        val hazard: Boolean
    )

    companion object {
        private const val TAG = "SensorDataService"
        private const val GBINDER_PACKAGE = "com.salat.gbinder"
        private const val ACTION_GET_FLOAT_SENSOR = "com.salat.gbinder.GET_FLOAT_SENSOR"
        private const val ACTION_GET_INT_SENSOR = "com.salat.gbinder.GET_INT_SENSOR"
        private const val ACTION_GET_INT_PROPERTY = "com.salat.gbinder.GET_INT_PROPERTY"
        private const val ACTION_LISTEN_SENSOR_CHANGES = "com.salat.gbinder.LISTEN_SENSOR_CHANGES"
        private const val ACTION_LISTEN_PROPERTY_CHANGES = "com.salat.gbinder.LISTEN_PROPERTY_CHANGES"
        private const val ACTION_SENSOR_FLOAT_RESULT = "com.salat.gbinder.SENSOR_FLOAT_RESULT"
        private const val ACTION_SENSOR_FLOAT_CHANGED = "com.salat.gbinder.SENSOR_FLOAT_CHANGED"
        private const val ACTION_SENSOR_INT_RESULT = "com.salat.gbinder.SENSOR_INT_RESULT"
        private const val ACTION_SENSOR_INT_CHANGED = "com.salat.gbinder.SENSOR_INT_CHANGED"
        private const val ACTION_PROPERTY_INT_RESULT = "com.salat.gbinder.PROPERTY_INT_RESULT"
        private const val ACTION_PROPERTY_INT_CHANGED = "com.salat.gbinder.PROPERTY_INT_CHANGED"
        private const val EXTRA_ID = "id"
        private const val EXTRA_VALUE = "value"
        private const val EXTRA_AREA_ID = "area"
        private const val SENSOR_ID_CAR_SPEED = 1055232
        private const val SENSOR_ID_LIGHT_STATE = 2_100_992
        private const val VEHICLE_PROPERTY_TURN_SIGNAL_STATE = 289_408_008
        private const val SENSOR_ID_LEFT_TURN = 0x22010102  // 570491138 - Geely/Ecarx left turn signal
        private const val SENSOR_ID_RIGHT_TURN = 0x22010103 // 570491139 - Geely/Ecarx right turn signal
        private const val VEHICLE_TURN_SIGNAL_RIGHT_MASK = 0x1
        private const val VEHICLE_TURN_SIGNAL_LEFT_MASK = 0x2
        private const val VEHICLE_TURN_SIGNAL_HAZARD_MASK = 0x4
        private const val VEHICLE_TURN_SIGNAL_KNOWN_MASK = 0x7
        private const val LEGACY_LIGHT_STATE_NONE = 0
        private const val LEGACY_LIGHT_STATE_LEFT = 1
        private const val LEGACY_LIGHT_STATE_RIGHT = 2
        private const val LEGACY_LIGHT_STATE_HAZARD = 3
        private const val LEGACY_LIGHT_STATE_MAX_DELTA = 0xFF
        private val TURN_SIGNAL_CANDIDATE_IDS = intArrayOf(
            SENSOR_ID_LEFT_TURN,        // Geely/Ecarx left turn (приоритет)
            SENSOR_ID_RIGHT_TURN,       // Geely/Ecarx right turn (приоритет)
            VEHICLE_PROPERTY_TURN_SIGNAL_STATE,  // AOSP VehicleProperty fallback
            SENSOR_ID_LIGHT_STATE       // Legacy gbinder fallback
        )
        private const val TURN_SIGNAL_POLL_INTERVAL_MS = 1000L
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
