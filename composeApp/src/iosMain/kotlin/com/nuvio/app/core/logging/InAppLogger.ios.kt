package com.nuvio.app.core.logging

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

internal actual fun currentInAppLogTimestamp(): String {
    val formatter = NSDateFormatter()
    formatter.dateFormat = "HH:mm:ss.SSS"
    return formatter.stringFromDate(NSDate())
}
