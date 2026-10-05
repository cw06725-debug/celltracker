package com.example.celltracker

import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportStorage {
    private fun safe(value: String, fallback: String): String =
        value.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifBlank { fallback }

    fun relativePath(category: String, timestampMs: Long): String {
        // Use year/month/day folders so the path reads naturally as 2026/10/01.
        // '/' is a path separator on Android, so this intentionally creates nested folders.
        val datePath = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(timestampMs))
        return Environment.DIRECTORY_DOWNLOADS + "/CellTracker/" + datePath + "/" + safe(category, "Other")
    }

    /** One folder per test session to prevent reports from different runs being mixed up. */
    fun sessionRelativePath(category: String, timestampMs: Long, label: String = "Test"): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
        val cleanLabel = safe(label, "Test").take(64)
        // Most report labels already end in yyyyMMdd_HHmmss. Do not prepend HHmmss again;
        // that produced folders such as 142916_TikTok_Upload_20261001_142916.
        val folder = if (Regex("\\d{8}_\\d{6}").containsMatchIn(cleanLabel)) cleanLabel else "${cleanLabel}_$stamp"
        return relativePath(category, timestampMs) + "/" + folder
    }
}
