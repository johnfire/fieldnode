package de.christopherrehm.fieldnode.file

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only record of every mutation the engine performs OR blocks, AND who performed it — a human
 * via the debug UI, or the on-phone AI agent (coding-standards 7.6). Human-readable, on-device.
 * Tab-separated: `timestamp  operation  outcome  actor  target  [detail]`. [actor] has no default —
 * every call site must say explicitly who acted, so nothing is silently attributed to "system". The
 * clock is injectable so the log is deterministic under test.
 */
class ActionLog(
    private val logFile: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun record(operation: String, target: String, outcome: String, actor: String, detail: String = "") {
        logFile.parentFile?.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now()))
        val line = buildString {
            append(stamp).append('\t')
            append(operation).append('\t')
            append(outcome).append('\t')
            append(actor).append('\t')
            append(target)
            if (detail.isNotEmpty()) append('\t').append(detail)
            append('\n')
        }
        logFile.appendText(line)
    }

    fun readAll(): String = if (logFile.exists()) logFile.readText() else ""

    fun lineCount(): Int = if (logFile.exists()) logFile.readLines().count { it.isNotBlank() } else 0
}
