package de.christopherrehm.fieldnode.file

import java.io.File

/**
 * Soft-delete store: "delete" MOVES a file here and records where it came from, so every delete is
 * reversible. Nothing in the app ever calls File.delete() on a user file directly — destruction
 * flows through here, and a TTL sweep is the only thing that ever removes bytes permanently.
 *
 * Manifest is tab-separated `trashId  originalPath  trashedAtMillis`, one line per entry.
 */
class TrashStore(
    private val trashDir: File,
    private val manifestFile: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val trashId: String, val originalPath: String, val trashedAt: Long)

    /** Move [file] into the trash. Returns the trashId, or null if the move failed. */
    fun trash(file: File): String? {
        if (!file.exists()) return null
        trashDir.mkdirs()
        val trashId = "${now()}-${file.name}"
        val destination = File(trashDir, trashId)
        if (!relocate(file, destination)) return null
        appendManifest(Entry(trashId, file.absolutePath, now()))
        return trashId
    }

    /** Restore a trashed entry to its original path. Returns true on success. */
    fun restore(trashId: String): Boolean {
        val entry = entries().firstOrNull { it.trashId == trashId } ?: return false
        val source = File(trashDir, trashId)
        if (!source.exists()) return false
        val target = File(entry.originalPath)
        if (!relocate(source, target)) return false
        rewriteManifest(entries().filterNot { it.trashId == trashId })
        return true
    }

    fun entries(): List<Entry> =
        if (!manifestFile.exists()) {
            emptyList()
        } else {
            manifestFile.readLines().mapNotNull(::parseLine)
        }

    /** Permanently remove trash entries older than [ttlMillis]. Returns the count purged. */
    fun sweepExpired(ttlMillis: Long): Int {
        val cutoff = now() - ttlMillis
        val (expired, kept) = entries().partition { it.trashedAt < cutoff }
        expired.forEach { File(trashDir, it.trashId).deleteRecursively() }
        rewriteManifest(kept)
        return expired.size
    }

    // --- internals -------------------------------------------------------------------------------

    /** Move via rename; fall back to copy+delete when crossing filesystems. */
    private fun relocate(source: File, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        if (source.renameTo(destination)) return true
        return try {
            source.copyRecursively(destination, overwrite = true)
            source.deleteRecursively()
        } catch (error: Exception) {
            destination.deleteRecursively()
            false
        }
    }

    private fun appendManifest(entry: Entry) {
        manifestFile.parentFile?.mkdirs()
        manifestFile.appendText("${entry.trashId}\t${entry.originalPath}\t${entry.trashedAt}\n")
    }

    private fun rewriteManifest(entries: List<Entry>) {
        manifestFile.parentFile?.mkdirs()
        manifestFile.writeText(entries.joinToString("") { "${it.trashId}\t${it.originalPath}\t${it.trashedAt}\n" })
    }

    private fun parseLine(line: String): Entry? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val trashedAt = parts[2].toLongOrNull() ?: return null
        return Entry(parts[0], parts[1], trashedAt)
    }
}
