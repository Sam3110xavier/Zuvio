package com.nuvio.app.core.diagnostics

/**
 * Platform bridge for locally saved crash reports and diagnostics export.
 * Crash reports are not uploaded by this API; export is always user-initiated.
 */
internal expect object DebugDiagnosticsPlatform {
    fun loadCrashReports(): String
    fun clearCrashReports()
    fun export(content: String)
}
