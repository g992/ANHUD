package com.g992.anhud

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.g992.anhud.hudbridge.DaemonInstaller
import com.g992.anhud.hudbridge.HudBridgeManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Выгрузить логи": one zip in Downloads with the on-disk UI log and crash reports
 * ([DiagnosticsLog]), this app's logcat, the system's record of how earlier processes ended, and
 * the daemon's log from QNX. Every part is best-effort: a failed one becomes a note in the zip.
 * Blocking (QNX shell, logcat); call off the main thread.
 */
object DiagnosticsExport {
    private const val LOGCAT_TIMEOUT_MS = 10_000L
    private const val LOGCAT_MAX_BYTES = 8 * 1024 * 1024
    private const val TRACE_MAX_BYTES = 512 * 1024
    private const val QNX_TIMEOUT_MS = 10_000L
    private const val QNX_LOG_LINES = 3000

    /** Writes the zip and returns its file name in Downloads. */
    @Throws(IOException::class)
    fun exportToDownloads(context: Context): String {
        val name = "anhud_logs_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
        openDownloadsOutput(context, name).use { out -> writeZip(context, out) }
        return name
    }

    private fun openDownloadsOutput(context: Context, name: String): OutputStream {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore не создал файл")
            return context.contentResolver.openOutputStream(uri) ?: throw IOException("не открылся $uri")
        }
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        downloads.mkdirs()
        return FileOutputStream(File(downloads, name))
    }

    private fun writeZip(context: Context, out: OutputStream) {
        DiagnosticsLog.flush()
        ZipOutputStream(out).use { zip ->
            fun text(name: String, body: () -> String) {
                val content = try {
                    body()
                } catch (e: Exception) {
                    "не удалось собрать: $e\n"
                }
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            text("info.txt") { info(context) }
            text("ui_now.txt") { UiLogStore.dump() }
            for (file in DiagnosticsLog.logFiles()) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (file in DiagnosticsLog.crashFiles()) {
                zip.putNextEntry(ZipEntry("crashes/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            text("exit_reasons.txt") { exitReasons(context) }
            text("logcat.txt") { logcat() }
            text("qnx_daemon.txt") { qnxDaemon() }
        }
    }

    private fun info(context: Context): String = buildString {
        appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        appendLine("app: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("daemon in APK: ${BuildConfig.HUD_BRIDGE_BUILD_ID.ifBlank { "-" }}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        appendLine("build: ${Build.DISPLAY}")
        appendLine("hud bridge: ${HudBridgeManager.state}")
        appendLine("camera calibration: ${HudBridgeManager.cameraCalibration.state}")
    }

    private fun exitReasons(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "недоступно до Android 11\n"
        }
        val am = context.getSystemService(ActivityManager::class.java)
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
        if (reasons.isEmpty()) return "нет записей\n"
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            for (r in reasons) {
                appendLine("${format.format(Date(r.timestamp))} ${r.processName} pid=${r.pid} " +
                    "reason=${reasonName(r.reason)} status=${r.status} importance=${r.importance} " +
                    "pss=${r.pss}kB rss=${r.rss}kB")
                r.description?.let { appendLine("  $it") }
                if (r.reason == ApplicationExitInfo.REASON_ANR || r.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try {
                        r.traceInputStream?.use { appendLine(readLimited(it, TRACE_MAX_BYTES).prependIndent("  ")) }
                    } catch (e: Exception) {
                        appendLine("  trace: $e")
                    }
                }
            }
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }

    /** An app sees only its own uid's lines without READ_LOGS. */
    private fun logcat(): String {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash")
            .redirectErrorStream(true)
            .start()
        val text = StringBuilder()
        val reader = Thread {
            try {
                text.append(readLimited(process.inputStream, LOGCAT_MAX_BYTES))
            } catch (_: IOException) {
            }
        }.apply { start() }
        if (!process.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroy()
        }
        reader.join(2_000L)
        return text.toString()
    }

    private fun qnxDaemon(): String {
        if (!BuildConfig.HUD_BRIDGE_BUNDLED) return "сборка без демона HUD\n"
        HudBridgeManager.openQnxShell().use { shell ->
            val log = DaemonInstaller.QNX_LOG
            return buildString {
                appendLine("# pidin")
                appendLine(shell.exec("pidin | grep ghudbridge", QNX_TIMEOUT_MS).output)
                appendLine("# cores")
                appendLine(shell.exec("ls -l /var/log/ | grep ghudbridge", QNX_TIMEOUT_MS).output)
                appendLine("# tail -n $QNX_LOG_LINES $log")
                appendLine(shell.exec("tail -n $QNX_LOG_LINES $log", QNX_TIMEOUT_MS).output)
            }
        }
    }

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() < maxBytes) out.write(buf, 0, minOf(n, maxBytes - out.size()))
        }
        val suffix = if (out.size() >= maxBytes) "\n... обрезано на $maxBytes байт\n" else ""
        return out.toString(Charsets.UTF_8.name()) + suffix
    }
}
