package com.g992.anhud

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val API_URL = "https://api.github.com/repos/g992/ANHUD/releases/latest"
    @Volatile
    private var isChecking = false

    data class ReleaseInfo(
        val versionName: String,
        val body: String,
        val apkUrl: String,
        val apkName: String
    )

    fun checkForUpdates(
        context: Context,
        force: Boolean = false,
        onResult: ((ReleaseInfo?, Boolean) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        if (isChecking && !force) {
            return
        }
        isChecking = true
        Thread {
            var info: ReleaseInfo? = null
            var available = false
            try {
                info = fetchLatestRelease()
                if (info != null && info.apkUrl.isNotBlank()) {
                    available = compareVersions(info.versionName, BuildConfig.VERSION_NAME) > 0
                }
                if (info != null) {
                    UpdatePrefs.setUpdateInfo(
                        appContext,
                        available,
                        info.versionName,
                        info.body,
                        info.apkUrl,
                        info.apkName
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}", e)
            } finally {
                UpdatePrefs.setLastChecked(appContext, System.currentTimeMillis())
                notifyStatusChanged(appContext)
                if (onResult != null) {
                    Handler(Looper.getMainLooper()).post {
                        onResult(info, available)
                    }
                }
                isChecking = false
            }
        }.start()
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val url = URL(API_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ANHUD")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "GitHub API response $code: $body")
                null
            } else {
                parseRelease(body)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(responseBody: String): ReleaseInfo? {
        val json = JSONObject(responseBody)
        val tag = json.optString("tag_name", "")
        val versionName = normalizeTag(tag)
        val body = json.optString("body", "")
        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        var apkName = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url", "")
                    apkName = name
                    break
                }
            }
        }

        return ReleaseInfo(
            versionName = versionName.ifBlank { tag },
            body = body,
            apkUrl = apkUrl,
            apkName = apkName
        )
    }

    private fun normalizeTag(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V")

    private fun compareVersions(a: String, b: String): Int {
        val left = splitVersion(a)
        val right = splitVersion(b)
        val maxSize = maxOf(left.size, right.size)
        for (i in 0 until maxSize) {
            val lv = left.getOrElse(i) { 0 }
            val rv = right.getOrElse(i) { 0 }
            if (lv != rv) {
                return lv.compareTo(rv)
            }
        }
        return 0
    }

    private fun splitVersion(value: String): List<Int> {
        val cleaned = value.trim().removePrefix("v").removePrefix("V")
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split(".")
            .map { segment -> segment.filter { it.isDigit() }.toIntOrNull() ?: 0 }
    }

    private fun notifyStatusChanged(context: Context) {
        context.sendBroadcast(
            Intent(UpdateBroadcasts.ACTION_UPDATE_STATUS_CHANGED).setPackage(context.packageName)
        )
    }
}
