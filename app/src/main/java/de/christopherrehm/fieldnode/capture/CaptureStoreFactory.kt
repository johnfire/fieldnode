package de.christopherrehm.fieldnode.capture

import android.os.Environment
import java.io.File

/** Wires a [CaptureStore] to the on-device managed dir. Kept apart so CaptureStore stays JVM-pure. */
object CaptureStoreFactory {
    fun create(): CaptureStore =
        CaptureStore(File(Environment.getExternalStorageDirectory(), "Fieldnode/captures"))
}
