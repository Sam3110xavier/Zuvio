package com.nuvio.app.core.logging

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
actual fun rememberLogExportAction(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { logText ->
            try {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "Exporting logs to the public Downloads folder requires Android 10 or newer."
                }
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val fileName = "NuvioEx-logs-" + timestamp + ".txt"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NuvioEx")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Android could not create the log file.")
                try {
                    val output = resolver.openOutputStream(uri)
                        ?: error("Android could not open the log file for writing.")
                    output.use { stream -> stream.write(logText.toByteArray(Charsets.UTF_8)) }
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                    Toast.makeText(context, "Logs saved to Downloads/NuvioEx/" + fileName, Toast.LENGTH_LONG).show()
                } catch (error: Throwable) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            } catch (error: Throwable) {
                Toast.makeText(context, "Could not export logs: " + (error.message ?: "unknown error"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
