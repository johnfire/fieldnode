package de.christopherrehm.fieldnode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import de.christopherrehm.fieldnode.notify.NotifyConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.5b notify-back: a foreground service that holds a streaming connection to the self-hosted ntfy
 * topic and raises a notification for each fleet message (CI done, Hermes finished, approval needed…).
 *
 * Reuses the canary's foreground-service shape (which we measured surviving MIUI deep idle). It
 * reconnects with backoff and uses ntfy's `since` so a brief drop replays the gap instead of losing
 * messages. A read timeout slightly above ntfy's keepalive interval detects dead connections.
 */
class NotifyService : Service() {

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var nextMessageId = 2000

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(STATUS_NOTIFICATION_ID, statusNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) {
            worker = Thread { subscribeLoop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- the subscriber loop ---------------------------------------------------------------------

    private fun subscribeLoop() {
        var since = (System.currentTimeMillis() / 1000).toString() // start: only new messages
        while (running.get()) {
            val config = NotifyConfig.load()
            if (config == null) {
                sleep(10_000)
                continue
            }
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("${config.streamUrl}?since=$since").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 90_000 // > ntfy keepalive (~45s); a silent dead conn then reconnects
                    setRequestProperty("Authorization", "Bearer ${config.token}")
                }
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        val event = parse(line) ?: continue
                        if (event.optString("event") == "message") {
                            showMessage(event)
                            val time = event.optLong("time")
                            if (time > 0) since = (time + 1).toString()
                        }
                    }
                }
            } catch (error: Exception) {
                // dropped / timed out — fall through to backoff + reconnect
            } finally {
                connection?.disconnect()
            }
            if (running.get()) sleep(5_000)
        }
    }

    private fun parse(line: String): JSONObject? =
        try {
            if (line.isBlank()) null else JSONObject(line)
        } catch (error: Exception) {
            null
        }

    // --- notifications ---------------------------------------------------------------------------

    private fun showMessage(event: JSONObject) {
        val title = event.optString("title").ifBlank { "Fleet" }
        val body = event.optString("message")
        val notificationId = nextMessageId++

        val builder = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)

        addActions(builder, event.optJSONArray("actions"), notificationId)
        manager().notify(notificationId, builder.build())
    }

    /** Turn ntfy "http" actions into notification buttons handled by [ActionReceiver]. */
    private fun addActions(builder: NotificationCompat.Builder, actions: org.json.JSONArray?, notificationId: Int) {
        if (actions == null) return
        for (index in 0 until actions.length()) {
            val action = actions.optJSONObject(index) ?: continue
            if (action.optString("action") != "http") continue
            val intent = Intent(this, ActionReceiver::class.java).apply {
                putExtra(ActionReceiver.EXTRA_URL, action.optString("url"))
                putExtra(ActionReceiver.EXTRA_METHOD, action.optString("method", "POST"))
                putExtra(ActionReceiver.EXTRA_BODY, action.optString("body"))
                putExtra(ActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                notificationId * 10 + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, action.optString("label", "Action"), pendingIntent)
        }
    }

    private fun statusNotification(): Notification =
        NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setContentTitle("Fieldnode")
            .setContentText("Listening for fleet messages")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    private fun createChannels() {
        val manager = manager()
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL_ID, "Fieldnode listener", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL_ID, "Fleet messages", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun manager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val STATUS_CHANNEL_ID = "notify-status"
        private const val MESSAGE_CHANNEL_ID = "fleet-messages"
        private const val STATUS_NOTIFICATION_ID = 2001
    }
}
