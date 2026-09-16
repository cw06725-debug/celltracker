package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DeviceLogCheck(
    val source: String = "/data/debuglogger",
    val readable: Boolean = false,
    val detail: String = "Not checked",
    val shellUid: String = "--"
)

object DeviceLogManager {
    const val DEFAULT_DUT_PATH = "/data/debuglogger"

    suspend fun check(path: String = DEFAULT_DUT_PATH): DeviceLogCheck = withContext(Dispatchers.IO) {
        val uid = shell("id").trim().ifBlank { "Unavailable" }
        val result = shellWithCode("ls -ld ${quote(path)}")
        DeviceLogCheck(path, result.first == 0, result.second.trim().ifBlank { if (result.first == 0) "Accessible" else "No output" }, uid)
    }

    suspend fun export(context: Context, path: String = DEFAULT_DUT_PATH): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val src = File(path)
            if (!src.exists() || !src.canRead()) error("CellTracker app UID cannot read $path. Use Local ADB shell mode when available.")
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val name = "DUT_debuglogger_$stamp.zip"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/DUT")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Cannot create export file")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(out.buffered()).use { zip -> addTree(zip, src, src.name) }
                } ?: error("Cannot open export stream")
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)
                "Download/CellTracker/Logs/DUT/$name"
            } catch (t: Throwable) {
                resolver.delete(uri, null, null); throw t
            }
        }
    }

    private fun addTree(zip: ZipOutputStream, file: File, entryName: String) {
        if (file.isDirectory) {
            val children = file.listFiles() ?: error("Permission denied while reading ${file.absolutePath}")
            if (children.isEmpty()) { zip.putNextEntry(ZipEntry("$entryName/")); zip.closeEntry() }
            children.forEach { addTree(zip, it, "$entryName/${it.name}") }
        } else {
            zip.putNextEntry(ZipEntry(entryName)); file.inputStream().buffered().use { it.copyTo(zip) }; zip.closeEntry()
        }
    }

    private fun shell(command: String): String = shellWithCode(command).second
    private fun shellWithCode(command: String): Pair<Int,String> = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$command 2>&1"))
        val text = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
        p.waitFor() to text
    } catch (t: Throwable) { -1 to (t.message ?: t.javaClass.simpleName) }
    private fun quote(v: String) = "'" + v.replace("'", "'\\''") + "'"
}
