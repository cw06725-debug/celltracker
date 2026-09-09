package com.example.celltracker

import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportStorage {
    fun relativePath(category: String, timestampMs: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestampMs))
        val safeCategory = category.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifBlank { "Other" }
        return Environment.DIRECTORY_DOWNLOADS + "/CellTracker/" + date + "/" + safeCategory
    }
}
