package de.christopherrehm.fieldnode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.christopherrehm.fieldnode.capture.CaptureStoreFactory
import de.christopherrehm.fieldnode.dispatch.Dispatcher
import de.christopherrehm.fieldnode.dispatch.FleetClient
import de.christopherrehm.fieldnode.dispatch.FleetConfig

/**
 * Fires a dispatch of unsent captures on the `de.christopherrehm.fieldnode.DISPATCH` broadcast — so an
 * automation tool (or a test) can flush the queue without opening the app. Network runs on a worker
 * thread via goAsync(), never on the main thread.
 */
class DispatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISPATCH) return
        val pending = goAsync()
        Thread {
            try {
                val config = FleetConfig.load() ?: return@Thread
                val dispatcher = Dispatcher(CaptureStoreFactory.create(), FleetClient(config))
                dispatcher.dispatchUnsent()
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_DISPATCH = "de.christopherrehm.fieldnode.DISPATCH"
    }
}
