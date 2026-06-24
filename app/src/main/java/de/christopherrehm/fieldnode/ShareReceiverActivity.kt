package de.christopherrehm.fieldnode

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import de.christopherrehm.fieldnode.capture.Capture
import de.christopherrehm.fieldnode.capture.CaptureStoreFactory

/**
 * v1a front door: receives shared text or an image from ANY app and drops it into the offline queue.
 * No UI — grab, save, toast, finish. This is what makes capture "always-with-you": anything you can
 * share, you can send to the fleet.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            handleSend(intent)
        }
        finish()
    }

    private fun handleSend(intent: Intent) {
        if (!Environment.isExternalStorageManager()) {
            toast("Grant all-files access in Fieldnode first")
            return
        }
        val store = CaptureStoreFactory.create()
        val id = System.currentTimeMillis().toString()
        val type = intent.type.orEmpty()

        try {
            when {
                type.startsWith("text/") -> {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    if (text.isBlank()) {
                        toast("Nothing to capture")
                        return
                    }
                    store.save(Capture(id, Capture.Kind.TEXT, text, null, id.toLong(), Capture.Status.PENDING))
                    toast("Captured text")
                }

                type.startsWith("image/") -> {
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri == null) {
                        toast("No image found")
                        return
                    }
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        toast("Could not read image")
                        return
                    }
                    val extension = type.substringAfter('/', "jpg").ifBlank { "jpg" }
                    val attachment = store.saveAttachment(id, extension, bytes)
                    val caption = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    store.save(Capture(id, Capture.Kind.IMAGE, caption, attachment, id.toLong(), Capture.Status.PENDING))
                    toast("Captured image")
                }

                else -> toast("Unsupported type: $type")
            }
        } catch (error: Exception) {
            toast("Capture failed: ${error.message}")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
