package com.example.celltracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Explicit user-granted import, never bypasses Android's private-data sandbox. */
internal object VendorLogFileImport {
    data class ImportResult(val files: List<File>, val message: String)
    private const val MAX_FILE_BYTES = 512L * 1024 * 1024

    fun importSelected(context: Context, uris: List<Uri>): ImportResult {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            "VendorLogImports").apply { if (!exists() && !mkdirs()) throw IOException("Cannot create import folder") }
        val output = mutableListOf<File>()
        val failures = mutableListOf<String>()
        uris.take(20).forEachIndexed { index, uri ->
            var dest: File? = null
            try {
                val rawName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: "vendor_log_${index + 1}.bin"
                val name = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(90).ifBlank { "vendor_log.bin" }
                val target = File(directory, "${System.currentTimeMillis()}_${index}_$name")
                dest = target
                var total = 0L
                val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("File not readable")
                stream.use { input ->
                    target.outputStream().use { fileOut ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val amount = input.read(buffer)
                            if (amount == -1) break
                            total += amount
                            if (total > MAX_FILE_BYTES) throw IOException("File exceeds 512 MiB cap")
                            fileOut.write(buffer, 0, amount)
                        }
                    }
                }
                output.add(target)
            } catch (e: Exception) {
                dest?.delete()
                failures.add(e.message ?: "Failed to import ${index + 1}")
            }
        }
        if (uris.size > 20) failures.add("Only first 20 files processed")
        val summary = "Imported ${output.size} file(s)" + if(failures.isNotEmpty()) "; ${failures.size} error(s): ${failures.first()}" else ""
        return ImportResult(output, summary)
    }

    fun savedFiles(context: Context): List<File> =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "VendorLogImports")
            .listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun share(context: Context, files: List<File>) {
        require(files.isNotEmpty()) { "Select at least one file" }
        val uris = ArrayList<Uri>(files.map { file ->
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        })
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share vendor log files"))
    }
}
