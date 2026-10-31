package com.hrvballoon.ui

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/** Creates Download/HRV/<fileName> via MediaStore — no IS_PENDING, so a crash mid-session leaves a visible, durable file. */
object MediaStoreCsv {
    fun open(context: Context, fileName: String): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HRV")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
        return resolver.openOutputStream(uri, "w") ?: throw IOException("cannot open $uri")
    }
}
