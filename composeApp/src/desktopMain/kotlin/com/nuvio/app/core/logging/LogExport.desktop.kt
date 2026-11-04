package com.nuvio.app.core.logging

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
actual fun rememberLogExportAction(): (String) -> Unit = remember {
    { logText ->
        val directory = Path.of(System.getProperty("user.home"), "Downloads", "NuvioEx")
        Files.createDirectories(directory)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "NuvioEx-logs-" + timestamp + ".txt"
        Files.writeString(directory.resolve(fileName), logText)
    }
}
