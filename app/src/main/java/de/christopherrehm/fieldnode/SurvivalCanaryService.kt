package de.christopherrehm.fieldnode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * Step 0.7 canary. Does nothing useful on purpose — its only job is to answer the question that
 * decides how hard v1.5 notify-back will be: will stock MIUI 12.5.7 let a foreground service stay
 * alive with the screen off?
 *
 * It writes a timestamped heartbeat every 30s to `Fieldnode/.canary/heartbeat.log`. Survival is read
 * straight off that file: steady 30s ticks = alive; a gap = doze/throttle; an abrupt stop with no
 * "onDestroy" line = MIUI killed it. No guessing.
 */
class SurvivalCanaryService : Service() {

    private var timer: Timer? = null
    private var beat = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        appendLog("SERVICE started")
        timer = Timer().also {
            it.scheduleAtFixedRate(
                object : TimerTask() {
                    override fun run() {
                        beat++
                        appendLog("beat $beat")
                    }
                },
                0L,
                HEARTBEAT_INTERVAL_MS,
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        appendLog("SERVICE onDestroy (clean stop — a hard MIUI kill would NOT log this)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun appendLog(message: String) {
        val dir = File(Environment.getExternalStorageDirectory(), "$MANAGED/.canary")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(dir, "heartbeat.log").appendText("$stamp\t$message\n")
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Survival canary", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fieldnode canary")
            .setContentText("Measuring background survival on MIUI")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val MANAGED = "Fieldnode"
        private const val CHANNEL_ID = "canary"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
