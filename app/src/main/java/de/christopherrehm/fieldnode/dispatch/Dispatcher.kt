package de.christopherrehm.fieldnode.dispatch

import de.christopherrehm.fieldnode.capture.Capture
import de.christopherrehm.fieldnode.capture.CaptureStore

/**
 * Sends not-yet-delivered captures (PENDING or previously FAILED) to the fleet, marking each SENT or
 * FAILED. Offline-safe: a network failure just leaves the capture in a re-sendable state, so the next
 * dispatch retries it and nothing is lost. Blocking; call on a background thread.
 */
class Dispatcher(private val store: CaptureStore, private val client: FleetClient) {

    data class Outcome(val sent: Int, val failed: Int, val detail: String)

    fun dispatchUnsent(): Outcome {
        val toSend = store.list().filter { it.status != Capture.Status.SENT }
        var sent = 0
        var failed = 0
        val notes = StringBuilder()
        for (capture in toSend) {
            when (val result = client.send(capture)) {
                is FleetClient.Result.Sent -> {
                    store.updateStatus(capture.id, Capture.Status.SENT)
                    sent++
                }
                is FleetClient.Result.Failed -> {
                    store.updateStatus(capture.id, Capture.Status.FAILED)
                    failed++
                    notes.appendLine("• ${capture.id}: ${result.reason}")
                }
            }
        }
        return Outcome(sent, failed, notes.toString().trim())
    }
}
