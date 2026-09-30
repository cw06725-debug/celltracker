package com.example.celltracker

import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportStorage {
    private fun safe(value: String, fallback: String): String =
        value.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifBlank { fallback }

    fun relativePath(category: String, timestampMs: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
        return Environment.DIRECTORY_DOWNLOADS + "/CellTracker/" + date + "/" + safe(category, "Other")
    }

    /** One folder per test session to prevent reports from different runs being mixed up. */
    fun sessionRelativePath(category: String, timestampMs: Long, label: String = "Test"): String {
        val time = SimpleDateFormat("HHmmss", Locale.US).format(Date(timestampMs))
        return relativePath(category, timestampMs) + "/" + time + "_" + safe(label, "Test").take(64)
    }
}
