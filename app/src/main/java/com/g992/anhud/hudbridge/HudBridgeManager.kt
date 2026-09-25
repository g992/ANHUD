package com.g992.anhud.hudbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.g992.anhud.BuildConfig
import com.g992.anhud.HudDisplayUtils
import com.g992.anhud.LogCategory
import com.g992.anhud.OverlayPrefs
import com.g992.anhud.UiLogStore
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

sealed class HudBridgeState {
    object Disabled : HudBridgeState()
    data class Starting(val step: String) : HudBridgeState()
    data class Running(val buildId: String?) : HudBridgeState()
    data class Reconnecting(val reason: String) : HudBridgeState()
    data class Error(val message: String) : HudBridgeState()
}

/**
 * "Отрисовка на заднем плане": the HUD overlay is laid out on a private VirtualDisplay and its
 * frames go to ghudbridgelited on QNX, which shows them on the HUD below hud-hmi.
 * One attempt per app process: after an error the switch stays inactive until restart,
 * while the preference is kept so the next start tries again.
 */
object HudBridgeManager {
    private const val TAG = "HudBridgeManager"
    private const val QNX_USER = "root"
    private const val QNX_WAIT_MS = 180_000L
    private const val QNX_RETRY_MS = 3_000L
    private const val RECONNECT_WINDOW_MS = 30_000L
    private const val RECONNECT_STEP_MS = 2_000L
    private const val RECONNECT_OPEN_TIMEOUT_MS = 3_000L
    private const val DEFAULT_DPI = 160
    private const val QNX_MIN_WAIT_MS = 10_000L
    private const val SINK_RELEASE_DELAY_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "hud-bridge") }
    private val generation = AtomicInteger()
    private val listeners = CopyOnWriteArraySet<(HudBridgeState) -> Unit>()

    @Volatile
    var state: HudBridgeState = HudBridgeState.Disabled
        private set

    @Volatile
    var failedThisSession = false
        private set

    @Volatile
    private var link: HudLink? = null

    @Volatile
    private var sink: HudBridgeFrameSink? = null

    @Volatile
    private var renderTargetListener: (() -> Unit)? = null

    private val processStartedAt = SystemClock.elapsedRealtime()

    @Volatile
    private var daemonBuildId: String? = null

    /** The display the overlay must be attached to while bridging, or null for the regular HUD display. */
    fun renderDisplay(): Display? {
        val current = state
        if (current !is HudBridgeState.Running && current !is HudBridgeState.Reconnecting) {
            return null
        }
        return sink?.display
    }

    fun isBusy(): Boolean {
        val current = state
        return current is HudBridgeState.Starting || current is HudBridgeState.Reconnecting
    }

    fun setRenderTargetListener(listener: (() -> Unit)?) {
        renderTargetListener = listener
    }

    fun addListener(listener: (HudBridgeState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (HudBridgeState) -> Unit) {
        listeners.remove(listener)
    }

    /** Starts or stops bridging to match the preferences. */
    fun sync(context: Context) {
        val wanted = HudBridgePrefs.enabled(context) && OverlayPrefs.isEnabled(context)
        if (wanted) start(context) else stop()
    }

    fun start(context: Context) {
        val current = state
        if (failedThisSession || current !is HudBridgeState.Disabled) {
            return
        }
        val appContext = context.applicationContext
        val gen = generation.incrementAndGet()
        publish(HudBridgeState.Starting("Подготовка…"))
        executor.execute { runStart(appContext, gen) }
    }

    fun stop() {
        if (state is HudBridgeState.Disabled || state is HudBridgeState.Error) {
            return
        }
        generation.incrementAndGet()
        publish(HudBridgeState.Disabled)
        executor.execute {
            teardown()
            notifyRenderTarget()
        }
    }

    private fun runStart(context: Context, gen: Int) {
        try {
            val bootWaitLeft = QNX_WAIT_MS - (SystemClock.elapsedRealtime() - processStartedAt)
            val buildId = connect(context, gen, maxOf(bootWaitLeft, QNX_MIN_WAIT_MS))
            daemonBuildId = buildId
            if (!isCurrent(gen)) {
                teardown()
                return
            }
            val newSink = HudBridgeFrameSink(
                context = context,
                dpi = resolveDpi(context),
                sender = { rgba, rect ->
                    val current = link ?: throw IOException("нет связи с демоном")
                    current.sendFrame(rgba, rect)
                },
                onSendFailed = { onLinkLost(context, "ошибка отправки: ${it.message}") }
            )
            sink = newSink
            if (!isCurrent(gen)) {
                teardown()
                return
            }
            log("фоновая отрисовка запущена (${buildId ?: "?"})")
            publish(HudBridgeState.Running(buildId))
            notifyRenderTarget()
        } catch (e: InterruptedException) {
            teardown()
        } catch (e: Exception) {
            teardown()
            if (isCurrent(gen)) {
                fail(describe(e))
            }
        }
    }

    /** Steps 1–5: checks, QNX login, daemon install/launch, gcp session. Returns the daemon build id. */
    private fun connect(context: Context, gen: Int, qnxWaitMs: Long): String? {
        step(gen, "Проверяю сборку…")
        if (!BuildConfig.HUD_BRIDGE_BUNDLED) {
            throw IOException("сборка без демона HUD (нет ghudbridgelited или пароля QNX)")
        }
        try {
            context.assets.open(DaemonInstaller.ASSET_NAME).close()
        } catch (e: IOException) {
            throw IOException("в APK нет ${DaemonInstaller.ASSET_NAME}")
        }

        val qnx = openQnx(gen, qnxWaitMs)
        val installed = qnx.use { shell ->
            DaemonInstaller.ensureRunning(context, shell) { step(gen, it) }
        }
        if (installed.reused && installed.buildId != null &&
            BuildConfig.HUD_BRIDGE_BUILD_ID.isNotBlank() && installed.buildId != BuildConfig.HUD_BRIDGE_BUILD_ID
        ) {
            log("запущен демон ${installed.buildId}, в APK ${BuildConfig.HUD_BRIDGE_BUILD_ID}")
        }
        step(gen, "Жду связи с демоном…")
        link = HudLink.openWithRetry(BuildConfig.QNX_HOST, appName(context), linkListener(context))
        return installed.buildId ?: BuildConfig.HUD_BRIDGE_BUILD_ID.ifBlank { null }
    }

    private fun openQnx(gen: Int, waitMs: Long): QnxShell {
        val deadline = SystemClock.elapsedRealtime() + waitMs.coerceAtLeast(0L)
        while (true) {
            step(gen, "Подключаюсь к QNX…")
            try {
                return QnxShell.open(BuildConfig.QNX_HOST, QNX_USER, BuildConfig.QNX_ROOT_PASSWORD)
            } catch (e: QnxAuthException) {
                throw e
            } catch (e: IOException) {
                if (SystemClock.elapsedRealtime() + QNX_RETRY_MS > deadline) {
                    throw IOException("QNX недоступна: ${e.message}", e)
                }
                step(gen, "Жду QNX (${e.message})…")
                Thread.sleep(QNX_RETRY_MS)
            }
        }
    }

    private fun linkListener(context: Context) = HudLink.Listener { reason -> onLinkLost(context, reason) }

    private fun onLinkLost(context: Context, reason: String) {
        executor.execute {
            if (state !is HudBridgeState.Running) {
                return@execute
            }
            val gen = generation.get()
            log("связь с HUD потеряна: $reason")
            publish(HudBridgeState.Reconnecting(reason))
            link?.close()
            link = null
            val deadline = SystemClock.elapsedRealtime() + RECONNECT_WINDOW_MS
            var lastError = reason
            while (isCurrent(gen) && SystemClock.elapsedRealtime() < deadline) {
                try {
                    link = try {
                        HudLink.openWithRetry(
                            BuildConfig.QNX_HOST,
                            appName(context),
                            linkListener(context),
                            timeoutMs = RECONNECT_OPEN_TIMEOUT_MS
                        )
                    } catch (e: BusyException) {
                        throw e
                    } catch (e: DaemonRejectedException) {
                        throw e
                    } catch (e: IOException) {
                        // The daemon may be gone (QNX restarted, /tmp wiped): bring it back.
                        QnxShell.open(BuildConfig.QNX_HOST, QNX_USER, BuildConfig.QNX_ROOT_PASSWORD).use { shell ->
                            DaemonInstaller.ensureRunning(context, shell) { }
                        }
                        HudLink.openWithRetry(BuildConfig.QNX_HOST, appName(context), linkListener(context))
                    }
                    if (!isCurrent(gen)) {
                        link?.close()
                        link = null
                        return@execute
                    }
                    sink?.restart()
                    log("связь с HUD восстановлена")
                    publish(HudBridgeState.Running(daemonBuildId))
                    return@execute
                } catch (e: BusyException) {
                    lastError = describe(e)
                    break
                } catch (e: QnxAuthException) {
                    lastError = describe(e)
                    break
                } catch (e: Exception) {
                    lastError = describe(e)
                    try {
                        Thread.sleep(RECONNECT_STEP_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            if (!isCurrent(gen)) {
                return@execute
            }
            teardown()
            fail("связь с HUD потеряна: $lastError")
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, "failed: $message")
        log("фоновая отрисовка: ошибка — $message")
        failedThisSession = true
        publish(HudBridgeState.Error(message))
        notifyRenderTarget()
    }

    private fun teardown() {
        val oldSink = sink
        sink = null
        val oldLink = link
        link = null
        if (oldSink != null) {
            // Detach the overlay from the virtual display before it goes away.
            notifyRenderTarget()
        }
        oldLink?.close()
        if (oldSink != null) {
            mainHandler.postDelayed({ oldSink.close() }, SINK_RELEASE_DELAY_MS)
        }
    }

    private fun step(gen: Int, text: String) {
        if (!isCurrent(gen)) {
            throw InterruptedException("stopped")
        }
        Log.d(TAG, text)
        publish(HudBridgeState.Starting(text))
    }

    private fun isCurrent(gen: Int) = generation.get() == gen

    private fun publish(newState: HudBridgeState) {
        state = newState
        mainHandler.post {
            if (state != newState) return@post
            listeners.forEach { it(newState) }
        }
    }

    private fun notifyRenderTarget() {
        mainHandler.post { renderTargetListener?.invoke() }
    }

    private fun resolveDpi(context: Context): Int {
        val display = HudDisplayUtils.resolveDisplay(context, OverlayPrefs.displayId(context), allowFallback = false)
            ?: return DEFAULT_DPI
        return context.createDisplayContext(display).resources.displayMetrics.densityDpi
            .takeIf { it > 0 } ?: DEFAULT_DPI
    }

    private fun appName(context: Context): String {
        return context.applicationInfo.loadLabel(context.packageManager).toString()
    }

    private fun describe(e: Throwable): String {
        return when (e) {
            is BusyException -> e.message.orEmpty()
            is QnxAuthException -> "вход в QNX не удался: ${e.message}"
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }

    private fun log(message: String) {
        UiLogStore.append(LogCategory.SYSTEM, "HUD-мост: $message")
    }
}
