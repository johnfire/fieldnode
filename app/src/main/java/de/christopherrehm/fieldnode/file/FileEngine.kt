package de.christopherrehm.fieldnode.file

import java.io.File

/**
 * The single doorway for all file operations. Reads pass straight through; every MUTATION is
 * gated by [ScopePolicy], recorded in [ActionLog] against [actor], and — when destructive — routed
 * through [TrashStore] instead of a raw delete. There is deliberately no method here that destroys
 * bytes directly: delete moves to trash, and overwrite trashes the prior version first.
 *
 * [actor] is fixed for the instance's lifetime (coding-standards 7.6) — the debug UI constructs its
 * engine with [Actor.USER], the on-phone agent with [Actor.AI_AGENT], so every entry this engine
 * writes is attributed correctly without every call site having to say who's asking.
 */
class FileEngine(
    private val scope: ScopePolicy,
    private val log: ActionLog,
    private val trash: TrashStore,
    private val actor: String,
) {
    // --- reads (unrestricted) --------------------------------------------------------------------

    fun list(dir: File): List<File> = dir.listFiles()?.sortedBy { it.name } ?: emptyList()

    fun readText(file: File): String? = if (file.isFile) file.readText() else null

    fun exists(file: File): Boolean = file.exists()

    fun writableScope(): String = scope.describe()

    /** Whether [target] may be mutated — so the UI can show read-only state before an op is tried. */
    fun canMutate(target: File): Boolean = scope.canMutate(target)

    fun trashEntries(): List<TrashStore.Entry> = trash.entries()

    fun actionLog(): String = log.readAll()

    /** Audit a non-file action this engine's actor took (e.g. an agent tool call with no file target),
     * so the same append-only log covers everything the actor did, not just file mutations. */
    fun recordAction(operation: String, target: String, outcome: String, detail: String = "") =
        log.record(operation, target, outcome, actor, detail)

    // --- mutations (scope-gated + logged) --------------------------------------------------------

    fun createDir(dir: File): FileOpResult = guarded("mkdir", dir) {
        if (dir.exists()) {
            FileOpResult.Success("Already exists: ${dir.absolutePath}")
        } else if (dir.mkdirs()) {
            FileOpResult.Success("Created ${dir.absolutePath}")
        } else {
            FileOpResult.Failure("Could not create ${dir.absolutePath}")
        }
    }

    /** Write text. If the file already exists, the prior version is trashed first (recoverable). */
    fun writeText(file: File, content: String): FileOpResult = guarded("write", file) {
        val existed = file.exists()
        if (existed) trash.trash(file)
        file.parentFile?.mkdirs()
        file.writeText(content)
        FileOpResult.Success(
            (if (existed) "Updated " else "Created ") + "${file.absolutePath} (${content.length} chars)",
        )
    }

    fun move(source: File, destination: File): FileOpResult {
        if (!scope.canMutate(source) || !scope.canMutate(destination)) {
            log.record(
                "move", "${source.absolutePath} -> ${destination.absolutePath}", "BLOCKED", actor,
                "out of scope",
            )
            return FileOpResult.Blocked("Move needs both ends in a writable scope")
        }
        if (!source.exists()) {
            log.record("move", source.absolutePath, "FAILURE", actor, "source missing")
            return FileOpResult.Failure("No such file: ${source.absolutePath}")
        }
        if (destination.exists()) trash.trash(destination)
        destination.parentFile?.mkdirs()
        val moved = source.renameTo(destination) || copyThenDelete(source, destination)
        val outcome = if (moved) "MOVED" else "FAILURE"
        log.record("move", "${source.absolutePath} -> ${destination.absolutePath}", outcome, actor)
        return if (moved) {
            FileOpResult.Success("Moved to ${destination.absolutePath}")
        } else {
            FileOpResult.Failure("Could not move ${source.absolutePath}")
        }
    }

    fun rename(file: File, newName: String): FileOpResult =
        move(file, File(file.parentFile, newName))

    /** Delete = move to trash. Never a raw File.delete() on a user file. */
    fun delete(file: File): FileOpResult = guarded("delete", file) {
        if (!file.exists()) {
            FileOpResult.Failure("No such file: ${file.absolutePath}")
        } else {
            val trashId = trash.trash(file)
            if (trashId != null) {
                FileOpResult.Success("Moved to trash ($trashId)")
            } else {
                FileOpResult.Failure("Could not trash ${file.absolutePath}")
            }
        }
    }

    fun restore(trashId: String): FileOpResult {
        val restored = trash.restore(trashId)
        log.record("restore", trashId, if (restored) "RESTORED" else "FAILURE", actor)
        return if (restored) FileOpResult.Success("Restored $trashId") else FileOpResult.Failure("Could not restore $trashId")
    }

    fun emptyExpired(ttlMillis: Long): FileOpResult {
        val purged = trash.sweepExpired(ttlMillis)
        log.record("sweep", "trash", "PURGED", actor, "$purged entries")
        return FileOpResult.Success("Purged $purged expired trash entries")
    }

    // --- internals -------------------------------------------------------------------------------

    /** Scope-check, run [action], log the outcome — the shared spine of every single-target mutation. */
    private inline fun guarded(operation: String, target: File, action: () -> FileOpResult): FileOpResult {
        if (!scope.canMutate(target)) {
            log.record(operation, target.absolutePath, "BLOCKED", actor, "out of scope")
            return FileOpResult.Blocked("Not within a writable scope: ${target.absolutePath}")
        }
        val result = try {
            action()
        } catch (error: Exception) {
            FileOpResult.Failure("${error.javaClass.simpleName}: ${error.message}")
        }
        val outcome = when (result) {
            is FileOpResult.Success -> "OK"
            is FileOpResult.Blocked -> "BLOCKED"
            is FileOpResult.Failure -> "FAILURE"
        }
        log.record(operation, target.absolutePath, outcome, actor, (result as? FileOpResult.Success)?.message ?: "")
        return result
    }

    private fun copyThenDelete(source: File, destination: File): Boolean = try {
        source.copyRecursively(destination, overwrite = true)
        source.deleteRecursively()
    } catch (error: Exception) {
        false
    }
}
