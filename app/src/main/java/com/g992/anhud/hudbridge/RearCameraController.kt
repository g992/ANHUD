package com.g992.anhud.hudbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.g992.anhud.BuildConfig
import com.g992.anhud.CarSensorHub
import com.g992.anhud.CustomBlocksContract
import com.g992.anhud.LogCategory
import com.g992.anhud.NavigationHudState
import com.g992.anhud.NavigationHudStore
import com.g992.anhud.OverlayPrefs
import com.g992.anhud.PrefsJson
import com.g992.anhud.SensorDataService
import com.g992.anhud.UiLogStore
import kotlin.math.roundToInt

/**
 * "Камеры на проекцию" (rear camera on reverse, side cameras on turn signals): tells ghudbridgelited
 * where to draw the rear and side cameras (CAMCFG v2 on every session start and settings change)
 * and what gear/speed/turn signals the car has (VEHSTATE on change and every 500 ms). The daemon
 * itself decides which camera to show. Works only while the HUD bridge session is up.
 */
object RearCameraController {
    private const val TAG = "RearCameraController"
    private const val MIN_TICK_MS = 50L
    private const val FALLBACK_SPEED_MAX_AGE_MS = 10_000L

    // Debug builds only, bench tests (the Autolink VHAL ignores car_service inject-vhal-event):
    // adb shell am broadcast -a com.g992.anhud.DEBUG_CAMERA --ei gear 2 --es turn left|right|hazard|off --ef speed 5 --ei seconds 20
    private const val ACTION_DEBUG_CAMERA = "com.g992.anhud.DEBUG_CAMERA"
    // Old name, same extras.
    private const val ACTION_DEBUG_GEAR = "com.g992.anhud.DEBUG_REAR_GEAR"

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    // Everything below is touched on the controller thread only.
    private var reader: CarVehicleStateReader? = null
    private val pacer = RearCameraLogic.VehicleStatePacer()
    private var config: Gcp.CameraConfig? = null
    private var configDirty = true
    private var sentConfig: Gcp.CameraConfig? = null
    @Volatile
    private var linkUp = false
    private var lastReverse = false
    private var lastTurn = RearCameraLogic.TurnSignals.UNKNOWN
    private val debug = RearCameraLogic.DebugOverrides()
    private var preview: Preview? = null
    private var cameraShow: CameraShowRequest? = null
    private val cameraShowTimeout = Runnable {
        cameraShow = null
        configDirty = true
        evaluate()
    }

    /** "Показать на проекции" from the view editor: this view forced on, with unsaved parameters. */
    private class Preview(val role: Int, val view: OverlayPrefs.CameraViewSettings, val untilMs: Long)

    /** Why the editor can't show its view on the HUD right now. */
    enum class PreviewBlock { NONE, NO_LINK, SPEED_UNKNOWN, MOVING }

    /** Last speed seen by the controller (km/h, -1 = unknown), for the view editor. */
    @Volatile
    var speedKmh = -1f
        private set

    /** Role of the view shown by "Показать на проекции", 0 = none. */
    @Volatile
    var previewRole = 0
        private set

    @Volatile
    private var editorOpen = false

    // Store listener thread: only turn signal changes wake the controller.
    @Volatile
    private var storeTurnKey = -1

    private val evaluateRunnable = Runnable { evaluate() }
    private var gearSubscription: AutoCloseable? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler?.post {
            configDirty = true
            evaluate()
        }
    }

    private val bridgeListener: (HudBridgeState) -> Unit = { state ->
        handler?.post { onBridgeState(state) }
    }

    private val storeListener = object : NavigationHudStore.Listener {
        override fun onStateUpdated(state: NavigationHudState) {
            val key = (if (state.turnSignalLeft) 1 else 0) or
                (if (state.turnSignalRight) 2 else 0) or
                (if (state.turnSignalHazard) 4 else 0)
            if (key != storeTurnKey) {
                storeTurnKey = key
                post()
            }
        }
    }

    /** Each broadcast replaces all overrides; one without any (or with unparsable values) clears them. */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val extras = intent.extras
            @Suppress("DEPRECATION")
            fun number(key: String): Number? = when (val v = extras?.get(key)) {
                is Number -> v
                is String -> v.toFloatOrNull()
                else -> null
            }
            val gear = number("gear")?.toInt()
            val turnRaw = extras?.getString("turn")
            val turn = RearCameraLogic.TurnSignals.parse(turnRaw)
            val speed = number("speed")?.toFloat()
            val seconds = number("seconds")?.toInt() ?: RearCameraLogic.DEBUG_DEFAULT_SECONDS
            handler?.post {
                if (turnRaw != null && turn == null) {
                    log("отладка: непонятный turn=\"$turnRaw\" (left|right|hazard|off)")
                }
                debug.set(SystemClock.elapsedRealtime(), gear, turn, speed, seconds)
                if (debug.active) {
                    val s = seconds.coerceIn(1, RearCameraLogic.DEBUG_MAX_SECONDS)
                    log("отладка: $debug на $s с")
                } else {
                    log("отладка: подмена сброшена")
                }
                evaluate()
            }
        }
    }

    // Available in release builds too, while the HUD background service is running.
    private val cameraShowReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val request = try {
                if (intent.extras?.isEmpty != false) null else {
                    val cameras = intent.getStringExtra("cameras")
                        ?: throw IllegalArgumentException("нужен cameras; для закрытия вызовите без параметров")
                    @Suppress("DEPRECATION")
                    val rawSeconds = intent.extras?.get("seconds")
                    val seconds = when (rawSeconds) {
                        null -> 0
                        is Int -> rawSeconds
                        is String -> rawSeconds.toIntOrNull()
                        else -> null
                    } ?: throw IllegalArgumentException("seconds должен быть целым числом")
                    CameraShowRequest.parse(cameras, seconds)
                }
            } catch (e: RuntimeException) {
                log("показ камер: ${e.message}")
                return
            }
            handler?.post {
                val h = handler ?: return@post
                h.removeCallbacks(cameraShowTimeout)
                endPreview(null)
                cameraShow = request
                configDirty = true
                pacer.reset()
                if (request != null && request.seconds > 0) {
                    h.postDelayed(cameraShowTimeout, request.seconds.toLong() * 1000L)
                }
                log(if (request == null) "ручной показ камер закрыт" else
                    "ручной показ: ${request.roles.joinToString { roleName(it) }}, " +
                        if (request.seconds == 0) "до закрытия" else "${request.seconds} с")
                evaluate()
            }
        }
    }

    fun start(context: Context) {
        if (thread != null) return
        val ctx = context.applicationContext
        val t = HandlerThread("rear-camera").apply { start() }
        thread = t
        handler = Handler(t.looper)
        appContext = ctx
        prefs = ctx.getSharedPreferences(PrefsJson.OVERLAY_PREFS_NAME, Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        HudBridgeManager.addListener(bridgeListener)
        NavigationHudStore.registerListener(storeListener)
        gearSubscription = CarSensorHub.addGearListener { post() }
        ContextCompat.registerReceiver(ctx, cameraShowReceiver,
            IntentFilter(CameraShowRequest.ACTION), ContextCompat.RECEIVER_EXPORTED)
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter().apply {
                addAction(ACTION_DEBUG_CAMERA)
                addAction(ACTION_DEBUG_GEAR)
            }
            ContextCompat.registerReceiver(ctx, debugReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        handler?.post { onBridgeState(HudBridgeManager.state) }
    }

    fun stop() {
        val t = thread ?: return
        HudBridgeManager.removeListener(bridgeListener)
        NavigationHudStore.unregisterListener(storeListener)
        gearSubscription?.close()
        gearSubscription = null
        runCatching { appContext?.unregisterReceiver(cameraShowReceiver) }
        if (BuildConfig.DEBUG) {
            runCatching { appContext?.unregisterReceiver(debugReceiver) }
        }
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        prefs = null
        val h = handler
        thread = null
        handler = null
        h?.post {
            h.removeCallbacks(evaluateRunnable)
            h.removeCallbacks(cameraShowTimeout)
            cameraShow = null
            reader?.stop()
            reader = null
            sentConfig = null
            config = null
            linkUp = false
            debug.clear()
            preview = null
            previewRole = 0
            speedKmh = -1f
            t.quitSafely()
        }
    }

    /** The view editor is open: keep reading the speed so it can offer "Показать на проекции". */
    fun setEditorOpen(open: Boolean) {
        editorOpen = open
        post()
    }

    fun previewBlock(): PreviewBlock {
        if (handler == null || !linkUp || !HudBridgeManager.isLinkUp() || HudBridgeManager.cameraUnsupported) {
            return PreviewBlock.NO_LINK
        }
        val speed = speedKmh
        return when {
            speed < 0f -> PreviewBlock.SPEED_UNKNOWN
            speed > RearCameraLogic.PREVIEW_MAX_SPEED_KMH -> PreviewBlock.MOVING
            else -> PreviewBlock.NONE
        }
    }

    /** Shows [view] of camera [role] on the HUD until [stopPreview], the car moves or 5 minutes pass. */
    fun startPreview(role: Int, view: OverlayPrefs.CameraViewSettings) {
        val h = handler ?: return
        previewRole = role
        h.post {
            h.removeCallbacks(cameraShowTimeout)
            cameraShow = null
            if (preview == null) log("показ на проекции из редактора: ${roleName(role)}")
            preview = Preview(role, view, SystemClock.elapsedRealtime() + RearCameraLogic.PREVIEW_MAX_MS)
            configDirty = true
            evaluate()
        }
    }

    /** New parameters for the running preview (the editor throttles these). */
    fun updatePreview(view: OverlayPrefs.CameraViewSettings) {
        handler?.post {
            val p = preview ?: return@post
            preview = Preview(p.role, view, p.untilMs)
            configDirty = true
            evaluate()
        }
    }

    fun stopPreview() {
        previewRole = 0
        handler?.post {
            endPreview(null)
            evaluate()
        }
    }

    private fun endPreview(reason: String?) {
        if (preview == null) return
        preview = null
        previewRole = 0
        configDirty = true
        log("показ на проекции из редактора выключен" + (reason?.let { " ($it)" } ?: ""))
    }

    private fun post() {
        val h = handler ?: return
        h.removeCallbacks(evaluateRunnable)
        h.post(evaluateRunnable)
    }

    private fun onBridgeState(state: HudBridgeState) {
        // Running is published once per (re)connected session, which knows nothing about the camera yet.
        sentConfig = null
        configDirty = true
        pacer.reset()
        linkUp = state is HudBridgeState.Running
        evaluate()
    }

    private fun evaluate() {
        val h = handler ?: return
        val context = appContext ?: return
        h.removeCallbacks(evaluateRunnable)
        val now = SystemClock.elapsedRealtime()
        val enabled = OverlayPrefs.cameraFeatureEnabled(context)
        syncReader(context, enabled || editorOpen || preview != null || cameraShow != null)
        val r = reader
        r?.poll()
        if (debug.expire(now)) {
            log("отладка: подмена закончилась, снова реальные данные")
        }
        val speed = debug.speedKmh(currentSpeedKmh(r))
        speedKmh = speed
        preview?.let { p ->
            when {
                now >= p.untilMs -> endPreview("прошло 5 минут")
                !RearCameraLogic.isStationary(speed) -> endPreview("автомобиль движется")
            }
        }
        val shown = preview
        if (!linkUp || !HudBridgeManager.isLinkUp() || HudBridgeManager.cameraUnsupported) {
            return
        }
        if (!enabled && shown == null && cameraShow == null) {
            // Only a session that was told to show the camera needs to be told to stop.
            val sent = sentConfig
            if (sent != null && sent.enabled) {
                val off = sent.copy(enabled = false)
                if (HudBridgeManager.sendCameraConfig(off)) {
                    sentConfig = off
                    log("выключена")
                }
            }
            pacer.reset()
            if (editorOpen) h.postDelayed(evaluateRunnable, RearCameraLogic.HEARTBEAT_MS)
            return
        }
        if (configDirty || config == null) {
            config = buildConfig(context, shown)
            configDirty = config == null
        }
        val cfg = config ?: run {
            h.postDelayed(evaluateRunnable, RearCameraLogic.HEARTBEAT_MS)
            return
        }
        if (cfg != sentConfig) {
            if (!HudBridgeManager.sendCameraConfig(cfg)) return
            if (sentConfig == null) {
                log(cfg.views.filter { it.enabled }.joinToString("; ") { v ->
                    "${roleName(v.role)} ${v.rect.w}x${v.rect.h} в (${v.rect.x}, ${v.rect.y}), z=${v.z}" +
                        if (v.noCorrection) ", без выравнивания" else ""
                })
            }
            sentConfig = cfg
        }
        val realGear = RearCameraLogic.gearFromEcarx(CarSensorHub.readGear())
            ?: RearCameraLogic.normalizeGear(r?.gearRaw)
        // The previewed view is forced active the way the debug overrides do it.
        val gear = cameraShow?.gear ?: when (shown?.role) {
            Gcp.CAM_ROLE_REAR -> Gcp.GEAR_REVERSE
            null -> debug.gear(realGear)
            else -> realGear
        }
        val turn = when (shown?.role) {
            Gcp.CAM_ROLE_LEFT -> RearCameraLogic.TurnSignals(left = true, valid = true)
            Gcp.CAM_ROLE_RIGHT -> RearCameraLogic.TurnSignals(right = true, valid = true)
            null -> debug.turn(currentTurnSignals(now))
            else -> RearCameraLogic.TurnSignals(valid = true)
        }
        val reverse = RearCameraLogic.isReverse(gear)
        if (reverse != lastReverse || turn != lastTurn) {
            lastReverse = reverse
            lastTurn = turn
            Log.d(TAG, "reverse=$reverse gear=$gear speed=$speed turn=$turn")
        }
        val flags = cameraShow?.flags ?: turn.flags
        if (pacer.shouldSend(now, gear, speed, flags)) {
            HudBridgeManager.sendVehicleState(gear, speed, flags)
        }
        h.postDelayed(evaluateRunnable, pacer.delayUntilHeartbeat(now).coerceAtLeast(MIN_TICK_MS))
    }

    private fun syncReader(context: Context, enabled: Boolean) {
        if (enabled && reader == null) {
            val newReader = CarVehicleStateReader(context) { post() }
            val status = newReader.start()
            reader = newReader
            log("данные автомобиля: $status")
        } else if (!enabled && reader != null) {
            reader?.stop()
            reader = null
        }
    }

    private fun currentSpeedKmh(r: CarVehicleStateReader?): Float {
        r?.speedMetersPerSecond?.let { return RearCameraLogic.speedKmh(it) }
        // No PERF_VEHICLE_SPEED access: fall back to the ECarX speed the app already reads.
        val snapshot = CarSensorHub.snapshot(CustomBlocksContract.SPEED_SENSOR_ID) ?: return -1f
        if (System.currentTimeMillis() - snapshot.receivedAt > FALLBACK_SPEED_MAX_AGE_MS) return -1f
        return RearCameraLogic.speedKmh(snapshot.value)
    }

    private fun currentTurnSignals(nowMs: Long): RearCameraLogic.TurnSignals {
        val state = NavigationHudStore.snapshot()
        return RearCameraLogic.TurnSignals.fromSensors(
            left = state.turnSignalLeft,
            right = state.turnSignalRight,
            hazard = state.turnSignalHazard,
            readAtMs = SensorDataService.turnSignalReadAtMs,
            nowMs = nowMs
        )
    }

    private fun buildConfig(context: Context, preview: Preview?): Gcp.CameraConfig? {
        val display = HudBridgeManager.renderDisplay() ?: return null
        val metrics = context.createDisplayContext(display).resources.displayMetrics
        val density = metrics.density.takeIf { it > 0f } ?: 1f
        val settings = OverlayPrefs.cameraSettings(context)
        val flipX = OverlayPrefs.infoMirrorGalaxyEnabled(context)
        val rect = if (settings.inContainer) {
            val containerPos = OverlayPrefs.containerPositionDp(context)
            val containerSize = OverlayPrefs.containerSizeDp(context)
            RearCameraLogic.hudRect(
                displayWidthPx = metrics.widthPixels,
                displayHeightPx = metrics.heightPixels,
                density = density,
                xDp = containerPos.x,
                yDp = containerPos.y,
                widthDp = containerSize.x,
                heightDp = containerSize.y
            )
        } else {
            RearCameraLogic.hudRect(
                displayWidthPx = metrics.widthPixels,
                displayHeightPx = metrics.heightPixels,
                density = density,
                xDp = settings.containerPositionDp.x,
                yDp = settings.containerPositionDp.y,
                widthDp = settings.containerSizeDp.x,
                heightDp = settings.containerSizeDp.y,
                minSizePx = (OverlayPrefs.CAMERA_CONTAINER_MIN_SIZE_DP * density).roundToInt()
            )
        }
        // A camera without a per-car fit gets a plain picture whatever the setting says.
        val calibration = HudBridgeManager.cameraCalibration
        fun view(role: Int, enabled: Boolean, view: OverlayPrefs.CameraViewSettings, maxSpeedKmh: Int) =
            RearCameraLogic.cameraView(
                role = role,
                enabled = enabled || cameraShow?.roles?.contains(role) == true,
                rect = rect,
                flipX = flipX,
                z = settings.z,
                view = view,
                maxSpeedKmh = maxSpeedKmh,
                fit = calibration.fit(role),
                correction = CameraCalibrationCheck.correctionEnabled(settings.correction, calibration.fit(role)),
                vignette = settings.vignette
            )
        val views = listOf(
            view(Gcp.CAM_ROLE_REAR, settings.rear.enabled, settings.rear.view, settings.rear.maxSpeedKmh),
            view(Gcp.CAM_ROLE_LEFT, settings.side.enabled, settings.side.left, settings.side.maxSpeedKmh),
            view(Gcp.CAM_ROLE_RIGHT, settings.side.enabled, settings.side.right, settings.side.maxSpeedKmh)
        )
        cameraShow?.let { return it.config(Gcp.CameraConfig(enabled = true, views = views)) }
        if (preview != null) {
            // Only the previewed view, with the editor's parameters and no speed limit (the car stands).
            return Gcp.CameraConfig(
                enabled = true,
                views = views.map { v ->
                    if (v.role == preview.role) view(v.role, true, preview.view, 0) else v.copy(enabled = false, vignette = false)
                }
            )
        }
        return Gcp.CameraConfig(
            enabled = true,
            views = views,
            hazardBoth = settings.side.enabled && settings.side.hazardBoth
        )
    }

    private fun roleName(role: Int): String = when (role) {
        Gcp.CAM_ROLE_REAR -> "задняя"
        Gcp.CAM_ROLE_LEFT -> "левая"
        Gcp.CAM_ROLE_RIGHT -> "правая"
        else -> "камера $role"
    }

    private fun log(message: String) {
        UiLogStore.append(LogCategory.SYSTEM, "Камеры на проекции: $message")
    }
}
