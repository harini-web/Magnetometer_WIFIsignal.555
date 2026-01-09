package com.example.magnetometerwifisignal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long,
    val message: String,
    val colorHex: String,
    val type: LogType
)

enum class LogType { REALTIME, SUMMARY }

object LogRepository {
    // Stores every detection (for ThreatLogsActivity)
    val realtimeLogs = mutableListOf<LogEntry>()

    // Stores the 30s aggregated summaries (for MainActivity)
    val summaryLogs = mutableListOf<LogEntry>()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addRealtime(msg: String, color: String) {
        // Keep buffer size manageable (max 1000 items)
        if (realtimeLogs.size > 1000) realtimeLogs.removeAt(0)
        realtimeLogs.add(0, LogEntry(System.currentTimeMillis(), msg, color, LogType.REALTIME))
    }

    fun addSummary(msg: String, color: String) {
        if (summaryLogs.size > 50) summaryLogs.removeAt(0)
        summaryLogs.add(0, LogEntry(System.currentTimeMillis(), msg, color, LogType.SUMMARY))
    }

    fun getFormattedSummaryLog(): String {
        return summaryLogs.joinToString("\n\n") {
            "${dateFormat.format(Date(it.timestamp))}: ${it.message}"
        }
    }

    fun getThreatCount(): Int = realtimeLogs.size

    fun getActiveAlertCount(): Int {
        // Count recent high-priority alerts in the last minute
        val threshold = System.currentTimeMillis() - 60000
        return realtimeLogs.count { it.timestamp > threshold && (it.colorHex.contains("D32F2F") || it.colorHex.contains("9C27B0")) }
    }

    fun clear() {
        realtimeLogs.clear()
        summaryLogs.clear()
    }
}