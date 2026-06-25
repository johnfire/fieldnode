package de.christopherrehm.fieldnode.dispatch

import android.os.Environment
import java.io.File

/**
 * Where to send captures and how to authenticate. Read from `Fieldnode/fleet.config` (key=value
 * lines) in the managed dir, so it can be set by file now and by a settings screen later. The device
 * token is the ONLY secret on the phone — it authenticates to the VPS forwarder, which holds the real
 * Open Brain key server-side. A leaked token is revocable and can only post captures.
 */
data class FleetConfig(val endpoint: String, val token: String) {

    /** Forwarder base (endpoint is the /capture URL) — used for sibling routes like /nearby. */
    val baseUrl: String get() = endpoint.substringBeforeLast('/')

    companion object {
        fun load(): FleetConfig? {
            val file = File(Environment.getExternalStorageDirectory(), "Fieldnode/fleet.config")
            if (!file.exists()) return null
            val fields = file.readLines().filter { it.contains('=') }.associate {
                val splitAt = it.indexOf('=')
                it.substring(0, splitAt).trim() to it.substring(splitAt + 1).trim()
            }
            val endpoint = fields["endpoint"]
            val token = fields["token"]
            return if (!endpoint.isNullOrBlank() && !token.isNullOrBlank()) {
                FleetConfig(endpoint, token)
            } else {
                null
            }
        }
    }
}
