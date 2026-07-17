package de.christopherrehm.fieldnode

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.christopherrehm.fieldnode.dispatch.FleetConfig
import de.christopherrehm.fieldnode.dispatch.FleetUrlPolicy
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Runs a notification action button (one-tap approve). The HTTP call is authenticated with the
 * device's own token (from fleet.config) — so the fleet's message specifies only *what* to call,
 * never a secret. Network runs on a worker thread via goAsync(); the notification is dismissed on
 * success.
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        intent ?: return
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val method = intent.getStringExtra(EXTRA_METHOD) ?: "POST"
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        val pending = goAsync()
        Thread {
            try {
                val config = FleetConfig.load()
                // The action URL came from a fleet message (external data). Only ever call our own fleet
                // host over https, and only then attach the device token — so a spoofed/rogue action can't
                // exfiltrate the token or turn the phone into a request proxy. Anything else: refuse.
                if (config == null || !FleetUrlPolicy.isTrustedFleetUrl(url, config.endpoint)) {
                    return@Thread
                }
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("X-Device-Token", config.token)
                    // Each tapped action is its own unit of work (coding-standards 7.5).
                    setRequestProperty("X-Correlation-Id", UUID.randomUUID().toString())
                    if (method == "POST" || method == "PUT") {
                        doOutput = true
                        setRequestProperty("Content-Type", "text/plain")
                    }
                }
                if (connection.doOutput) {
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = connection.responseCode
                connection.disconnect()
                if (code in 200..299 && notificationId >= 0) {
                    context.getSystemService(NotificationManager::class.java).cancel(notificationId)
                }
            } catch (error: Exception) {
                // leave the notification up so the user can retry
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_METHOD = "method"
        const val EXTRA_BODY = "body"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
