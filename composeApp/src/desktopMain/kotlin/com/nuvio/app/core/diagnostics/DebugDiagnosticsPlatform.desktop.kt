package com.nuvio.app.core.diagnostics

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal actual object DebugDiagnosticsPlatform {
    actual fun loadCrashReports(): String = ""

    actual fun clearCrashReports() = Unit

    actual fun export(content: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(content), null)
        }
    }
}
