package de.christopherrehm.fieldnode.file

import android.os.Environment
import java.io.File

/**
 * Wires a [FileEngine] to real on-device locations. Kept separate from the engine so the engine
 * itself stays free of Android APIs and fully unit-testable on the JVM.
 *
 * Managed area lives under `/storage/emulated/0/Fieldnode/`:
 *   .trash/  — soft-deleted files + manifest      .log/ — action log
 *
 * Earned scope starts NARROW: the app's own managed dir plus Download and Documents. It is widened
 * later by adding roots here — capability (all-files access) is broad, but what the agent may
 * *mutate* is small until trusted.
 */
object FileEngineFactory {

    const val MANAGED_DIR_NAME = "Fieldnode"

    fun create(): FileEngine {
        val external = Environment.getExternalStorageDirectory()
        val managed = File(external, MANAGED_DIR_NAME)

        val trash = TrashStore(
            trashDir = File(managed, ".trash"),
            manifestFile = File(managed, ".trash/manifest.tsv"),
        )
        val log = ActionLog(File(managed, ".log/actions.log"))
        val scope = ScopePolicy(
            listOf(
                managed,
                File(external, "Download"),
                File(external, "Documents"),
            ),
        )
        return FileEngine(scope, log, trash)
    }
}
