package com.example.celltracker

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Small MediaStore helper used to prevent duplicate report exports. */
object ExportMediaStore {
    fun findDownload(context: Context, displayName: String, relativePath: String): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.RELATIVE_PATH)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf(displayName),
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val pathIndex = cursor.getColumnIndex(MediaStore.Downloads.RELATIVE_PATH)
            val expected = relativePath.trimEnd('/')
            while (cursor.moveToNext()) {
                val actual = if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty().trimEnd('/') else ""
                if (actual == expected) {
                    return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idIndex))
                }
            }
        }
        return null
    }

    fun saveOrReuse(
        context: Context,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
        relativePath: String
    ): Pair<Uri, Boolean> {
        findDownload(context, displayName, relativePath)?.let { return it to true }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create export file")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Unable to write export file")
        return uri to false
    }
}
