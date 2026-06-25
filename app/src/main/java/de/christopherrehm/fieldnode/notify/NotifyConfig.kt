package de.christopherrehm.fieldnode.notify

import android.os.Environment
import java.io.File

/**
 * Where to listen for fleet push messages. Read from `Fieldnode/fleet.config` (same file as dispatch),
 * keys: `ntfy_url`, `ntfy_topic`, `ntfy_token`. The token is read-scoped to the topic on the server.
 */
data class NotifyConfig(val baseUrl: String, val topic: String, val token: String) {

    /** ntfy JSON stream endpoint for this topic. */
    val streamUrl: String get() = "${baseUrl.trimEnd('/')}/$topic/json"

    companion object {
        fun load(): NotifyConfig? {
            val file = File(Environment.getExternalStorageDirectory(), "Fieldnode/fleet.config")
            if (!file.exists()) return null
            val fields = file.readLines().filter { it.contains('=') }.associate {
                val splitAt = it.indexOf('=')
                it.substring(0, splitAt).trim() to it.substring(splitAt + 1).trim()
            }
            val url = fields["ntfy_url"]
            val topic = fields["ntfy_topic"]
            val token = fields["ntfy_token"]
            return if (!url.isNullOrBlank() && !topic.isNullOrBlank() && !token.isNullOrBlank()) {
                NotifyConfig(url, topic, token)
            } else {
                null
            }
        }
    }
}
