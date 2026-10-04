package com.g992.anhud

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import java.io.Closeable
import java.io.File

internal interface CarPlayLogStream : Closeable {
    fun readLine(): String?
}

/** In-process CP-SRV stream like GeeGeek t1/i0; sees CarPlay logs only with READ_LOGS (log gid). */
internal class CarPlayLogReader(
    private val isActive: () -> Boolean,
    private val receive: (String) -> Unit,
    private val open: () -> CarPlayLogStream = ::openLogcat
) {
    @Volatile private var running = false
    @Volatile private var stream: CarPlayLogStream? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "CarPlayLogs").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.interrupt()
        thread = null
        runCatching { stream?.close() }
    }

    private fun loop() {
        while (running) {
            try {
                open().use { source ->
                    stream = source
                    while (running) {
                        val line = source.readLine() ?: break
                        // Runs on this thread; CP-SRV emits ~35 lines/s, so receivers must stay cheap.
                        if (running && isActive()) receive(line)
                    }
                }
            } catch (_: Exception) {
                // logcat ended or failed to start; restart below.
            }
            stream = null
            if (!running) return
            try {
                Thread.sleep(RESTART_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    companion object {
        private const val RESTART_MS = 2000L

        fun openLogcat(): CarPlayLogStream {
            val process = ProcessBuilder("logcat", "-v", "epoch", "-T", "1", "-s", "CP-SRV")
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            return object : CarPlayLogStream {
                override fun readLine(): String? = reader.readLine()
                override fun close() {
                    process.destroy()
                    runCatching { reader.close() }
                }
            }
        }
    }
}

/** READ_LOGS via local ADB `pm grant`, then one process restart so the log gid applies (GeeGeek HudService.a). */
internal object CarPlayLogAccess {
    private const val LOG_GID = "1007"
    private const val RESTART_KEY = "read_logs_restart_at"
    private const val RESTART_INTERVAL_MS = 600_000L

    fun hasAccess(): Boolean = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("Groups:") }
            ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.contains(LOG_GID) == true
    }.getOrDefault(false)

    fun ensure(context: Context, shell: CarPlayPatchShell) {
        if (hasAccess()) return
        try {
            shell.execute("pm grant ${context.packageName} android.permission.READ_LOGS")
        } catch (e: Exception) {
            log("не удалось выдать доступ к журналу CarPlay: ${e.message}")
            return
        }
        val prefs = context.applicationContext.getSharedPreferences("carplay_patch_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val since = now - prefs.getLong(RESTART_KEY, 0L)
        if (since in 0 until RESTART_INTERVAL_MS) {
            log("доступ к журналу CarPlay выдан, но не появился после перезапуска")
            return
        }
        prefs.edit().putLong(RESTART_KEY, now).commit()
        log("доступ к журналу CarPlay выдан, перезапуск ANHUD")
        val restart = PendingIntent.getForegroundService(
            context, 1, Intent(context, HudBackgroundService::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + 1500L
        try {
            alarms?.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, restart)
        } catch (_: SecurityException) {
            alarms?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, restart)
        }
        Process.killProcess(Process.myPid())
    }

    private fun log(message: String) {
        UiLogStore.append(LogCategory.NAVIGATION, "carplay: $message")
    }
}
