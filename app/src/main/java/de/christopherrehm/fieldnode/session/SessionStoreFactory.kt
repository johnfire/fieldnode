package de.christopherrehm.fieldnode.session

import android.os.Environment
import java.io.File

/** Wires a [SessionStore] to the on-device managed dir. Kept apart so SessionStore stays JVM-pure. */
object SessionStoreFactory {
    fun create(): SessionStore =
        SessionStore(File(Environment.getExternalStorageDirectory(), "Fieldnode/agent/sessions"))
}
