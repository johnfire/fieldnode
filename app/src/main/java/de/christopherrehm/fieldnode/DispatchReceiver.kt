package de.christopherrehm.fieldnode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.christopherrehm.fieldnode.capture.CaptureStoreFactory
import de.christopherrehm.fieldnode.dispatch.Dispatcher
import de.christopherrehm.fieldnode.dispatch.FleetClient
import de.christopherrehm.fieldnode.dispatch.FleetConfig

/**
 * Fleet-control broadcasts, so automation (or a test) can drive Fieldnode without opening the app —
 * even while locked:
 *   - `…DISPATCH`      flush the capture queue to the fleet
 *   - `…NOTIFY_START`  start the fleet-message listener
 *   - `…NOTIFY_STOP`   stop it
 */
class DispatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_DISPATCH -> dispatchInBackground()
            ACTION_NOTIFY_START -> context.startForegroundService(Intent(context, NotifyService::class.java))
            ACTION_NOTIFY_STOP -> context.stopService(Intent(context, NotifyService::class.java))
        }
    }

    private fun dispatchInBackground() {
        val pending = goAsync()
        Thread {
            try {
                val config = FleetConfig.load() ?: return@Thread
                Dispatcher(CaptureStoreFactory.create(), FleetClient(config)).dispatchUnsent()
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_DISPATCH = "de.christopherrehm.fieldnode.DISPATCH"
        const val ACTION_NOTIFY_START = "de.christopherrehm.fieldnode.NOTIFY_START"
        const val ACTION_NOTIFY_STOP = "de.christopherrehm.fieldnode.NOTIFY_STOP"
    }
}
