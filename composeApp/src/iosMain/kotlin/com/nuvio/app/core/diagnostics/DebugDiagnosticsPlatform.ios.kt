package com.nuvio.app.core.diagnostics

import platform.UIKit.UIPasteboard

internal actual object DebugDiagnosticsPlatform {
    actual fun loadCrashReports(): String = ""

    actual fun clearCrashReports() = Unit

    actual fun export(content: String) {
        UIPasteboard.generalPasteboard.string = content
    }
}
