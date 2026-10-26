package com.g992.anhud

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.g992.anhud.hudbridge.CameraSnapshots
import com.g992.anhud.hudbridge.CameraViewRenderer
import com.g992.anhud.hudbridge.Gcp
import com.g992.anhud.hudbridge.HudBridgeManager
import com.g992.anhud.hudbridge.PerCarCalibration
import com.g992.anhud.hudbridge.RearCameraController
import com.g992.anhud.hudbridge.RearCameraLogic
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * "Редактор вида … камеры": the camera's corrected picture rendered with the daemon's math from a
 * live frame (the AVM calibration still when there is none, with a hint); drag / pinch / rotate or the sliders change the view (car terms), "Готово"
 * saves it. "Показать на проекции" shows the unsaved view on the HUD while the car stands.
 */
class CameraViewEditorActivity : ScaledActivity() {
    private lateinit var slot: OverlayPrefs.CameraSlot
    private var role = Gcp.CAM_ROLE_REAR
    private lateinit var fit: PerCarCalibration.Fit
    private lateinit var saved: OverlayPrefs.CameraViewSettings
    private lateinit var view: OverlayPrefs.CameraViewSettings
    private var vignette = true

    private lateinit var preview: CameraPreviewView
    private lateinit var loadPanel: View
    private lateinit var loadProgress: ProgressBar
    private lateinit var loadStatus: TextView
    private lateinit var refreshButton: TextView
    private lateinit var sourceHint: TextView
    private lateinit var mirrorSwitch: SwitchCompat
    private lateinit var hudSwitch: SwitchCompat
    private lateinit var hudHint: TextView
    private val sliders = ArrayList<Slider>()
    private var syncing = false

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "camera-editor") }
    private val loader = Executors.newSingleThreadExecutor { Thread(it, "camera-snapshot") }

    // Touched on the main thread only.
    private var source: IntArray? = null
    private var outW = 0
    private var outH = 0
    private var rendering = false
    private var loading = false
    private var pendingRender: Boolean? = null
    private var gestureActive = false
    private var lastHudUpdateAt = 0L
    private var destroyed = false

    private val fullRender = Runnable { requestRender(lowRes = false) }
    private val hudUpdate = Runnable { sendHudUpdate() }
    private val pollRunnable = object : Runnable {
        override fun run() {
            syncHudPreview()
            main.postDelayed(this, POLL_MS)
        }
    }

    /** One slider row: value in degrees with [STEPS_PER_DEGREE] resolution over [min]..[max]. */
    private class Slider(
        val seek: SeekBar,
        val value: TextView,
        val min: Float,
        val max: Float,
        val format: (Float) -> String,
        val get: (OverlayPrefs.CameraViewSettings) -> Float,
        val set: (OverlayPrefs.CameraViewSettings, Float) -> OverlayPrefs.CameraViewSettings
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        role = intent.getIntExtra(EXTRA_ROLE, Gcp.CAM_ROLE_REAR)
        slot = RearCameraLogic.slotOf(role)
        val calibrated = HudBridgeManager.cameraCalibration(this).fit(role)
        if (calibrated == null) {
            Toast.makeText(this, R.string.camera_editor_no_fit, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        fit = calibrated
        saved = OverlayPrefs.cameraView(this, slot)
        view = saved
        vignette = OverlayPrefs.camerasVignette(this)
        setContentView(R.layout.activity_camera_view_editor)

        findViewById<TextView>(R.id.cameraEditorTitle).setText(
            when (slot) {
                OverlayPrefs.CameraSlot.LEFT -> R.string.camera_editor_title_left
                OverlayPrefs.CameraSlot.RIGHT -> R.string.camera_editor_title_right
                OverlayPrefs.CameraSlot.REAR -> R.string.camera_editor_title_rear
            }
        )
        findViewById<View>(R.id.cameraEditorBack).setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        preview = findViewById(R.id.cameraPreview)
        loadPanel = findViewById(R.id.cameraLoadPanel)
        loadProgress = findViewById(R.id.cameraLoadProgress)
        loadStatus = findViewById(R.id.cameraLoadStatus)
        refreshButton = findViewById(R.id.cameraRefresh)
        sourceHint = findViewById(R.id.cameraSourceHint)
        refreshButton.setOnClickListener { loadSnapshot() }
        mirrorSwitch = findViewById(R.id.cameraMirrorSwitch)
        hudSwitch = findViewById(R.id.cameraHudPreviewSwitch)
        hudHint = findViewById(R.id.cameraHudPreviewHint)

        setupOutputSize()
        setupSliders()
        mirrorSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!syncing) change(view.copy(mirror = isChecked), lowRes = false)
        }
        hudSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncing) return@setOnCheckedChangeListener
            if (isChecked) RearCameraController.startPreview(role, view) else RearCameraController.stopPreview()
            syncHudPreview()
        }
        val reset = findViewById<Button>(R.id.cameraEditorReset)
        val done = findViewById<Button>(R.id.cameraEditorDone)
        styleButton(reset, primary = false)
        styleButton(done, primary = true)
        reset.setOnClickListener { change(OverlayPrefs.defaultCameraView(slot), lowRes = false) }
        done.setOnClickListener {
            OverlayPrefs.setCameraView(this, slot, view)
            finish()
        }
        preview.listener = gestureListener
        syncControls()
        requestRender(lowRes = false)
        loadSnapshot()
    }

    override fun onStart() {
        super.onStart()
        if (::fit.isInitialized) {
            RearCameraController.setEditorOpen(true)
            main.post(pollRunnable)
        }
    }

    override fun onStop() {
        // "Показать на проекции" never outlives the visible editor.
        main.removeCallbacks(pollRunnable)
        main.removeCallbacks(hudUpdate)
        if (::hudSwitch.isInitialized && hudSwitch.isChecked) {
            RearCameraController.stopPreview()
            syncing = true
            hudSwitch.isChecked = false
            syncing = false
        }
        RearCameraController.setEditorOpen(false)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        loader.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ view changes

    private val gestureListener = object : CameraPreviewView.Listener {
        override fun onGestureStart() {
            gestureActive = true
        }

        override fun onGestureEnd() {
            gestureActive = false
            scheduleFullRender()
        }

        override fun onDrag(dx: Float, dy: Float, imageWidthPx: Float) {
            // Degrees per screen pixel at the picture centre; "grab the picture" direction.
            val k = Math.toDegrees(atan(1.0 / focalScreenPx(imageWidthPx))).toFloat()
            val sx = if (view.mirror) 1f else -1f
            change(view.copy(headingDeg = view.headingDeg + sx * dx * k, downDeg = view.downDeg - dy * k), lowRes = true)
        }

        override fun onPinch(scale: Float) {
            val half = Math.toRadians(view.hfovDeg / 2.0)
            val hfov = Math.toDegrees(2 * atan(tan(half) / scale)).toFloat()
            change(view.copy(hfovDeg = hfov), lowRes = true)
        }

        override fun onRotate(degrees: Float) {
            // Positive roll turns the (unmirrored) picture counter-clockwise.
            val s = if (view.mirror) 1f else -1f
            change(view.copy(rollDeg = view.rollDeg + s * degrees), lowRes = true)
        }
    }

    private fun focalScreenPx(imageWidthPx: Float): Double =
        (imageWidthPx / 2.0) / tan(Math.toRadians(view.hfovDeg / 2.0))

    private fun change(wanted: OverlayPrefs.CameraViewSettings, lowRes: Boolean) {
        val clamped = OverlayPrefs.clampCameraView(slot, wanted)
        val next = RearCameraLogic.limitToLens(fit, view, clamped)
        if (next == view) return
        view = next
        syncControls()
        requestRender(lowRes)
        if (lowRes) scheduleFullRender()
        if (hudSwitch.isChecked) scheduleHudUpdate()
    }

    private fun scheduleFullRender() {
        main.removeCallbacks(fullRender)
        main.postDelayed(fullRender, FULL_RENDER_DELAY_MS)
    }

    /** At most one CAMCFG per [RearCameraLogic.PREVIEW_UPDATE_MS], the last change always goes out. */
    private fun scheduleHudUpdate() {
        val wait = lastHudUpdateAt + RearCameraLogic.PREVIEW_UPDATE_MS - SystemClock.elapsedRealtime()
        main.removeCallbacks(hudUpdate)
        if (wait <= 0) sendHudUpdate() else main.postDelayed(hudUpdate, wait)
    }

    private fun sendHudUpdate() {
        lastHudUpdateAt = SystemClock.elapsedRealtime()
        RearCameraController.updatePreview(view)
    }

    private fun leave() {
        if (view == saved) {
            finish()
            return
        }
        val dialog = AlertDialog.Builder(this, R.style.ThemeOverlay_ANHUD_Dialog)
            .setTitle(R.string.camera_editor_unsaved_title)
            .setPositiveButton(R.string.camera_editor_save) { _, _ ->
                OverlayPrefs.setCameraView(this, slot, view)
                finish()
            }
            .setNegativeButton(R.string.camera_editor_discard) { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            val metrics = resources.displayMetrics
            val width = minOf((metrics.widthPixels * 0.9f).roundToInt(), (UNSAVED_DIALOG_MAX_WIDTH_DP * metrics.density).roundToInt())
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ controls

    private fun setupSliders() {
        val heading0 = OverlayPrefs.defaultCameraView(slot).headingDeg
        val container = findViewById<LinearLayout>(R.id.cameraEditorSliders)
        fun add(
            labelRes: Int,
            min: Float,
            max: Float,
            format: (Float) -> String,
            get: (OverlayPrefs.CameraViewSettings) -> Float,
            set: (OverlayPrefs.CameraViewSettings, Float) -> OverlayPrefs.CameraViewSettings
        ) {
            val value = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
            }
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@CameraViewEditorActivity).apply {
                    setText(labelRes)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
            val seek = SeekBar(this).apply { this.max = ((max - min) * STEPS_PER_DEGREE).roundToInt() }
            container.addView(seek, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
            val slider = Slider(seek, value, min, max, format, get, set)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || syncing) return
                    change(set(view, min + progress / STEPS_PER_DEGREE), lowRes = true)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = scheduleFullRender()
            })
            sliders.add(slider)
        }
        add(
            R.string.camera_editor_heading,
            heading0 - OverlayPrefs.CAMERA_HEADING_RANGE,
            heading0 + OverlayPrefs.CAMERA_HEADING_RANGE,
            ::formatHeading,
            { it.headingDeg },
            { v, x -> v.copy(headingDeg = x) }
        )
        add(
            R.string.camera_editor_down,
            OverlayPrefs.CAMERA_DOWN_MIN,
            OverlayPrefs.CAMERA_DOWN_MAX,
            ::formatDown,
            { it.downDeg },
            { v, x -> v.copy(downDeg = x) }
        )
        add(
            R.string.camera_editor_roll,
            OverlayPrefs.CAMERA_ROLL_MIN,
            OverlayPrefs.CAMERA_ROLL_MAX,
            { getString(R.string.camera_editor_degrees, number(it)) },
            { it.rollDeg },
            { v, x -> v.copy(rollDeg = x) }
        )
        add(
            R.string.camera_editor_hfov,
            OverlayPrefs.CAMERA_HFOV_MIN,
            OverlayPrefs.CAMERA_HFOV_MAX,
            { getString(R.string.camera_editor_degrees, number(it)) },
            { it.hfovDeg },
            { v, x -> v.copy(hfovDeg = x) }
        )
    }

    private fun syncControls() {
        syncing = true
        try {
            for (s in sliders) {
                val v = s.get(view)
                s.seek.progress = ((v - s.min) * STEPS_PER_DEGREE).roundToInt()
                s.value.text = s.format(v)
            }
            mirrorSwitch.isChecked = view.mirror
        } finally {
            syncing = false
        }
    }

    /** 180 = straight back; more is to the car's left. */
    private fun formatHeading(heading: Float): String {
        val dev = heading - 180f
        return when {
            abs(dev) < 0.25f -> getString(R.string.camera_editor_heading_back)
            dev > 0f -> getString(R.string.camera_editor_heading_left, number(dev))
            else -> getString(R.string.camera_editor_heading_right, number(-dev))
        }
    }

    private fun formatDown(down: Float): String = when {
        abs(down) < 0.25f -> getString(R.string.camera_editor_down_level)
        down > 0f -> getString(R.string.camera_editor_down_value, number(down))
        else -> getString(R.string.camera_editor_up_value, number(-down))
    }

    /** Half-degree resolution, no trailing ",0". */
    private fun number(v: Float): String {
        val r = (v * 2).roundToInt() / 2f
        return if (r % 1f == 0f) r.toInt().toString() else String.format("%.1f", r)
    }

    private fun syncHudPreview() {
        val block = RearCameraController.previewBlock()
        val shown = RearCameraController.previewRole == role
        if (hudSwitch.isChecked && !shown) {
            // The controller ended it: the car moved, 5 minutes passed or the link dropped.
            syncing = true
            hudSwitch.isChecked = false
            syncing = false
            main.removeCallbacks(hudUpdate)
        }
        val on = hudSwitch.isChecked
        hudSwitch.isEnabled = on || block == RearCameraController.PreviewBlock.NONE
        hudSwitch.alpha = if (hudSwitch.isEnabled) 1f else 0.5f
        hudHint.setText(
            when {
                on -> R.string.camera_editor_hud_preview_on
                block == RearCameraController.PreviewBlock.NO_LINK -> R.string.camera_editor_hud_preview_no_link
                block == RearCameraController.PreviewBlock.SPEED_UNKNOWN -> R.string.camera_editor_hud_preview_no_speed
                block == RearCameraController.PreviewBlock.MOVING -> R.string.camera_editor_hud_preview_moving
                else -> R.string.camera_editor_hud_preview_on
            }
        )
    }

    private fun styleButton(button: Button, primary: Boolean) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            val blue = ContextCompat.getColor(this@CameraViewEditorActivity, R.color.hud_blue)
            setColor(if (primary) blue else Color.parseColor("#222222"))
            setStroke(dp(1), Color.parseColor(if (primary) "#4EA3FF" else "#3A3A3A"))
            cornerRadius = dp(10).toFloat()
        }
        button.setTextColor(Color.WHITE)
        button.setPadding(dp(14), dp(8), dp(14), dp(8))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------------ rendering

    /** The picture has the aspect of the HUD rect it will fill: the camera area, or its half for a side camera. */
    private fun setupOutputSize() {
        val size = if (OverlayPrefs.camerasInContainer(this)) {
            OverlayPrefs.containerSizeDp(this)
        } else {
            OverlayPrefs.cameraContainerSizeDp(this)
        }
        val width = if (slot == OverlayPrefs.CameraSlot.REAR) size.x else size.x / 2f
        val aspect = (width / size.y.coerceAtLeast(1f)).takeIf { it.isFinite() && it > 0f }?.coerceIn(0.2f, 5f) ?: 1.6f
        outH = FULL_RENDER_HEIGHT
        outW = (outH * aspect).roundToInt()
        if (outW > FULL_RENDER_MAX_WIDTH) {
            outW = FULL_RENDER_MAX_WIDTH
            outH = (outW / aspect).roundToInt()
        }
        preview.aspect = aspect
    }

    /** One render at a time; while busy only the newest request is kept. */
    private fun requestRender(lowRes: Boolean) {
        if (destroyed) return
        if (rendering) {
            pendingRender = (pendingRender ?: true) && lowRes
            return
        }
        rendering = true
        val w = if (lowRes) (outW / LOW_RES_DIVISOR).coerceAtLeast(16) else outW
        val h = if (lowRes) (outH / LOW_RES_DIVISOR).coerceAtLeast(16) else outH
        val lens = RearCameraLogic.lensOf(fit)
        val params = RearCameraLogic.rendererView(fit, view, view.mirror, vignette)
        val src = source
        worker.execute {
            val pixels = CameraViewRenderer.render(src, w, h, lens, params)
            val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            main.post {
                rendering = false
                if (destroyed) return@post
                preview.setBitmap(bitmap)
                pendingRender?.let {
                    pendingRender = null
                    requestRender(it)
                }
            }
        }
    }

    /** A live frame when the daemon can take one, else the factory still with a hint; "Обновить кадр" repeats it. */
    private fun loadSnapshot() {
        if (loading) return
        loading = true
        setRefreshEnabled(false)
        loadPanel.visibility = View.VISIBLE
        loadProgress.visibility = View.VISIBLE
        loadStatus.text = ""
        val appContext: Context = applicationContext
        loader.execute {
            val result = try {
                val snapshot = CameraSnapshots.load(appContext, role) { text -> main.post { loadStatus.text = text } }
                Result.success(snapshot to CameraViewRenderer.nv12ToArgb(snapshot.nv12))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                if (destroyed) return@post
                loading = false
                setRefreshEnabled(true)
                result.onSuccess { (snapshot, pixels) ->
                    source = pixels
                    loadPanel.visibility = View.GONE
                    sourceHint.visibility = if (snapshot.live) View.GONE else View.VISIBLE
                    if (!snapshot.live) {
                        sourceHint.text = getString(R.string.camera_editor_factory_still, snapshot.liveProblem ?: "?")
                    }
                    requestRender(lowRes = false)
                }.onFailure { e ->
                    val why = e.message ?: e.javaClass.simpleName
                    if (source != null) {
                        // A refresh failed: keep the picture there is.
                        loadPanel.visibility = View.GONE
                        Toast.makeText(this, getString(R.string.camera_editor_refresh_failed, why), Toast.LENGTH_LONG).show()
                    } else {
                        loadProgress.visibility = View.GONE
                        loadStatus.text = getString(R.string.camera_editor_snapshot_failed, why)
                    }
                }
            }
        }
    }

    private fun setRefreshEnabled(enabled: Boolean) {
        refreshButton.isEnabled = enabled
        refreshButton.alpha = if (enabled) 1f else 0.5f
    }

    companion object {
        const val EXTRA_ROLE = "camera_role"
        private const val STEPS_PER_DEGREE = 2f
        private const val FULL_RENDER_HEIGHT = 400
        private const val FULL_RENDER_MAX_WIDTH = 720
        private const val LOW_RES_DIVISOR = 3
        private const val FULL_RENDER_DELAY_MS = 250L
        private const val UNSAVED_DIALOG_MAX_WIDTH_DP = 480
        private const val POLL_MS = 500L

        fun intent(context: Context, role: Int): Intent =
            Intent(context, CameraViewEditorActivity::class.java).putExtra(EXTRA_ROLE, role)
    }
}
