package com.g992.anhud

import android.content.Context

object UpdatePrefs {
    private const val PREFS_NAME = "update_prefs"
    private const val KEY_AVAILABLE = "available"
    private const val KEY_LATEST_VERSION = "latest_version"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_APK_URL = "apk_url"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_LAST_CHECKED = "last_checked"
    private const val KEY_DOWNLOAD_ID = "download_id"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isUpdateAvailable(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AVAILABLE, false)

    fun getLatestVersion(context: Context): String =
        prefs(context).getString(KEY_LATEST_VERSION, "") ?: ""

    fun getReleaseNotes(context: Context): String =
        prefs(context).getString(KEY_RELEASE_NOTES, "") ?: ""

    fun getApkUrl(context: Context): String =
        prefs(context).getString(KEY_APK_URL, "") ?: ""

    fun getApkName(context: Context): String =
        prefs(context).getString(KEY_APK_NAME, "") ?: ""

    fun getLastChecked(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CHECKED, 0L)

    fun setLastChecked(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_LAST_CHECKED, value).apply()
    }

    fun setDownloadId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_DOWNLOAD_ID, id).apply()
    }

    fun getDownloadId(context: Context): Long =
        prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)

    fun clearDownloadId(context: Context) {
        prefs(context).edit().remove(KEY_DOWNLOAD_ID).apply()
    }

    fun setUpdateInfo(
        context: Context,
        available: Boolean,
        latestVersion: String,
        releaseNotes: String,
        apkUrl: String,
        apkName: String
    ) {
        prefs(context).edit()
            .putBoolean(KEY_AVAILABLE, available)
            .putString(KEY_LATEST_VERSION, latestVersion)
            .putString(KEY_RELEASE_NOTES, releaseNotes)
            .putString(KEY_APK_URL, apkUrl)
            .putString(KEY_APK_NAME, apkName)
            .apply()
    }
}
