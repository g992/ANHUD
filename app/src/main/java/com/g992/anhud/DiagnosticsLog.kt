package com.g992.anhud

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * On-disk copy of the UI logs plus uncaught-exception reports, so a log export still shows what
 * happened before a crash or restart. Files live in `filesDir/diag`; see [DiagnosticsExport].
 */
object DiagnosticsLog {
    private const val TAG = "DiagnosticsLog"
    private const val LOG_NAME = "anhud.log"
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MAX_CRASH_FILES = 10
    const val CRASH_PREFIX = "crash_"

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "diag-log").apply { isDaemon = true } }

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "diag")
        d.mkdirs()
        dir = d
        installCrashHandler()
        record("--- запуск процесса ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ---")
    }

    fun directory(): File? = dir

    /** Current log first-older-then-newer: [LOG_NAME].1, [LOG_NAME]. */
    fun logFiles(): List<File> {
        val d = dir ?: return emptyList()
        return listOf(File(d, "$LOG_NAME.1"), File(d, LOG_NAME)).filter { it.isFile }
    }

    fun crashFiles(): List<File> {
        val d = dir ?: return emptyList()
        return d.listFiles { f -> f.isFile && f.name.startsWith(CRASH_PREFIX) }?.sortedBy { it.name }.orEmpty()
    }

    /** Appends one line (with date and time) off the caller's thread; no-op before [init]. */
    fun record(line: String) {
        val d = dir ?: return
        val stamped = "${stamp("MM-dd HH:mm:ss.SSS")} $line\n"
        writer.execute {
            try {
                val file = File(d, LOG_NAME)
                if (file.length() > MAX_LOG_BYTES) {
                    val old = File(d, "$LOG_NAME.1")
                    old.delete()
                    file.renameTo(old)
                }
                file.appendText(stamped)
            } catch (e: Exception) {
                Log.w(TAG, "log write failed: ${e.message}")
            }
        }
    }

    /** Waits (briefly) until lines recorded so far are on disk. */
    fun flush(timeoutMs: Long = 2_000L) {
        try {
            writer.submit {}.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrash(thread, error)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Synchronous: the process is about to die. */
    private fun writeCrash(thread: Thread, error: Throwable) {
        val d = dir ?: return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        File(d, "$CRASH_PREFIX${stamp("yyyyMMdd_HHmmss")}.txt").writeText(
            "time: ${stamp("yyyy-MM-dd HH:mm:ss.SSS")}\n" +
                "version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "thread: ${thread.name}\n\n$trace"
        )
        File(d, LOG_NAME).appendText("${stamp("MM-dd HH:mm:ss.SSS")} !!! краш в потоке ${thread.name}: $error\n")
        crashFiles().dropLast(MAX_CRASH_FILES).forEach { it.delete() }
    }

    private fun stamp(pattern: String): String = SimpleDateFormat(pattern, Locale.US).format(Date())
}
