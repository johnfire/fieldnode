package de.christopherrehm.fieldnode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Part of the 0.7 canary: tests MIUI **autostart** specifically. MIUI blocks boot broadcasts to apps
 * whose Autostart toggle is off, so whether this fires after a reboot is itself the signal. When it
 * does fire, it logs the fact and relaunches the survival service.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val dir = File(Environment.getExternalStorageDirectory(), "Fieldnode/.canary").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(dir, "heartbeat.log").appendText("$stamp\tBOOT_COMPLETED received (MIUI autostart works)\n")

        context.startForegroundService(Intent(context, SurvivalCanaryService::class.java))
        // Resume listening for fleet messages after a reboot.
        context.startForegroundService(Intent(context, NotifyService::class.java))
    }
}
