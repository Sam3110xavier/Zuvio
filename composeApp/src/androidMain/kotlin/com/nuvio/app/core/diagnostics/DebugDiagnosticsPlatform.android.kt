package com.nuvio.app.core.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.nuvio.app.core.build.AppVersionConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

internal actual object DebugDiagnosticsPlatform {
    actual fun loadCrashReports(): String = CrashReportStore.readAll()

    actual fun clearCrashReports() = CrashReportStore.clear()

    actual fun export(content: String) {
        val context = CrashReportStore.applicationContext ?: return
        runCatching {
            val directory = File(context.cacheDir, "debug-exports").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(directory, "zuvio-diagnostics-$timestamp.txt")
            file.writeText(content)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Zuvio diagnostics")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(shareIntent, "Export Zuvio diagnostics")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

internal object CrashReportStore {
    private const val MAX_REPORTS = 5
    private const val MAX_REPORT_CHARS = 200_000

    @Volatile
    var applicationContext: Context? = null
        private set

    @Volatile
    private var installed = false

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        if (installed) return
        installed = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { persist(thread, error) }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun readAll(): String {
        val directory = crashDirectory() ?: return ""
        return (1..MAX_REPORTS)
            .map { File(directory, "crash-$it.txt") }
            .filter { it.isFile }
            .joinToString(separator = "\n\n") { file ->
                runCatching { file.readText() }.getOrDefault("")
            }
    }

    fun clear() {
        val directory = crashDirectory() ?: return
        (1..MAX_REPORTS).forEach { index ->
            File(directory, "crash-$index.txt").delete()
        }
    }

    private fun persist(thread: Thread, error: Throwable) {
        val context = applicationContext ?: return
        val directory = File(context.filesDir, "crash-reports").apply { mkdirs() }
        for (index in MAX_REPORTS downTo 2) {
            val previous = File(directory, "crash-${index - 1}.txt")
            val destination = File(directory, "crash-$index.txt")
            destination.delete()
            if (previous.exists()) previous.renameTo(destination)
        }
        val stackTrace = StringWriter().also { writer ->
            error.printStackTrace(PrintWriter(writer))
        }.toString().take(MAX_REPORT_CHARS)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val report = buildString {
            appendLine("Crash timestamp: $timestamp")
            appendLine("App: ${AppVersionConfig.VERSION_NAME} (${AppVersionConfig.VERSION_CODE})")
            appendLine("Package: ${context.packageName}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${error.javaClass.name}")
            appendLine()
            append(stackTrace)
        }
        File(directory, "crash-1.txt").writeText(report)
    }

    private fun crashDirectory(): File? =
        applicationContext?.let { File(it.filesDir, "crash-reports") }
}
