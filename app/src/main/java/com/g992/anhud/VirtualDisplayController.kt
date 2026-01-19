package com.g992.anhud

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout

class VirtualDisplayController(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var currentDisplayId: Int? = null
    private var targetMetrics: TargetMetrics? = null
    private var surface: Surface? = null
    private var lastPermissionGranted: Boolean? = null

    fun refresh() {
        handler.post {
            val hasPermission = HudDisplayUtils.hasVirtualDisplayPermission(context)
            if (lastPermissionGranted != hasPermission) {
                lastPermissionGranted = hasPermission
                if (!hasPermission && OverlayPrefs.isVirtualDisplayEnabled(context)) {
                    UiLogStore.append(
                        LogCategory.SYSTEM,
                        "Виртуальный дисплей: нужны системные права CAPTURE_VIDEO_OUTPUT"
                    )
                }
            }
            if (!OverlayPrefs.isVirtualDisplayEnabled(context)) {
                destroyInternal()
                return@post
            }
            if (!hasPermission) {
                destroyInternal()
                return@post
            }
            if (!Settings.canDrawOverlays(context)) {
                destroyInternal()
                UiLogStore.append(LogCategory.SYSTEM, "Виртуальный дисплей: нет разрешения на оверлей")
                return@post
            }
            val display = HudDisplayUtils.resolveDisplay(context, OverlayPrefs.hudDisplayId(context))
            if (display == null) {
                destroyInternal()
                UiLogStore.append(LogCategory.SYSTEM, "Виртуальный дисплей: экран не найден")
                return@post
            }
            if (rootView != null && currentDisplayId == display.displayId) {
                return@post
            }
            destroyInternal()
            createProjection(display)
        }
    }

    fun destroy() {
        handler.post { destroyInternal() }
    }

    private fun createProjection(display: Display) {
        val displayContext = context.createDisplayContext(display)
        val wm = displayContext.getSystemService(WindowManager::class.java)
        if (wm == null) {
            UiLogStore.append(LogCategory.SYSTEM, "Виртуальный дисплей: WindowManager недоступен")
            return
        }
        val metrics = displayContext.resources.displayMetrics
        targetMetrics = TargetMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

        val root = FrameLayout(displayContext).apply {
            setBackgroundColor(Color.BLACK)
        }

        val surfaceView = SurfaceView(displayContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surface = holder.surface
                    tryCreateVirtualDisplay()
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    surface = holder.surface
                    if (virtualDisplay == null) {
                        tryCreateVirtualDisplay()
                        return
                    }
                    val target = targetMetrics ?: return
                    if (width > 0 && height > 0) {
                        virtualDisplay?.resize(width, height, target.densityDpi)
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surface = null
                    releaseVirtualDisplay()
                }
            })
        }

        root.addView(surfaceView)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm.addView(root, layoutParams)
            windowManager = wm
            rootView = root
            currentDisplayId = display.displayId
            UiLogStore.append(
                LogCategory.SYSTEM,
                "Виртуальный дисплей: контейнер показан на экране ${display.displayId}"
            )
        } catch (e: Exception) {
            UiLogStore.append(LogCategory.SYSTEM, "Виртуальный дисплей: ошибка addView: ${e.message}")
            destroyInternal()
        }
    }

    private fun tryCreateVirtualDisplay() {
        val surface = surface ?: return
        if (!surface.isValid || virtualDisplay != null) {
            return
        }
        val metrics = targetMetrics ?: return
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val display = displayManager.createVirtualDisplay(
            HudDisplayUtils.VIRTUAL_DISPLAY_NAME,
            metrics.width,
            metrics.height,
            metrics.densityDpi,
            surface,
            flags
        )
        if (display == null) {
            UiLogStore.append(LogCategory.SYSTEM, "Виртуальный дисплей: не удалось создать")
            return
        }
        virtualDisplay = display
        UiLogStore.append(
            LogCategory.SYSTEM,
            "Виртуальный дисплей: создан ${display.display.displayId}"
        )
    }

    private fun destroyInternal() {
        releaseVirtualDisplay()
        val wm = windowManager
        val view = rootView
        if (wm != null && view != null) {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                UiLogStore.append(LogCategory.SYSTEM, "Виртуальный дисплей: ошибка удаления: ${e.message}")
            }
        }
        windowManager = null
        rootView = null
        currentDisplayId = null
        targetMetrics = null
        surface = null
    }

    private fun releaseVirtualDisplay() {
        val display = virtualDisplay ?: return
        display.release()
        virtualDisplay = null
    }

    private data class TargetMetrics(
        val width: Int,
        val height: Int,
        val densityDpi: Int
    )
}
