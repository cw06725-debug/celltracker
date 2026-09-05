package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object CsvExporter {
    fun exportLatest(context: Context, sourcePath: String): String {
        val source = File(sourcePath)
        require(source.exists()) { "Recording file not found" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CellTracker")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create exported file")
        resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
            ?: error("Unable to open export destination")
        return "Saved to Downloads/CellTracker/${source.name}"
    }
}
