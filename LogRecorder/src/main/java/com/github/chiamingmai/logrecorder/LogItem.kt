package com.github.chiamingmai.logrecorder

import java.util.Date

/**
 * Represents a single log entry in the [LogRecorder].
 *
 * Each log item has a timestamp indicating when it was created.
 */
sealed class LogItem(val timestamp: Date = Date()) {
    /** JSON log entry */
    data class JSONLog(val obj: Any) : LogItem()

    /** Plain text log entry */
    data class PlainTextLog(val text: String) : LogItem()
}