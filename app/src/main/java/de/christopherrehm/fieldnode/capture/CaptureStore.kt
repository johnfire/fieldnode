package de.christopherrehm.fieldnode.capture

import java.io.File
import java.util.Base64

/**
 * Offline-first persistence for captures. One file per capture (`<id>.capture`), attachments stored
 * alongside. Plain `java.io.File` + Base64 so it's free of Android APIs and unit-testable on the JVM.
 * Text is Base64-encoded so newlines/tabs in a note can't corrupt the line-based format.
 *
 * This is app plumbing (the queue), deliberately separate from the agent-facing FileEngine.
 */
class CaptureStore(private val dir: File) {

    fun save(capture: Capture) {
        dir.mkdirs()
        recordFile(capture.id).writeText(serialize(capture))
    }

    /** Persist raw attachment bytes next to the capture; returns the stored filename. */
    fun saveAttachment(captureId: String, extension: String, bytes: ByteArray): String {
        dir.mkdirs()
        val name = "$captureId.$extension"
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun list(): List<Capture> =
        (dir.listFiles { file -> file.name.endsWith(CAPTURE_EXT) } ?: emptyArray())
            .mapNotNull { runCatching { deserialize(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun pending(): List<Capture> = list().filter { it.status == Capture.Status.PENDING }

    fun updateStatus(id: String, status: Capture.Status) {
        val existing = runCatching { deserialize(recordFile(id).readText()) }.getOrNull() ?: return
        save(existing.copy(status = status))
    }

    fun attachmentFile(capture: Capture): File? = capture.attachment?.let { File(dir, it) }

    // --- serialization ---------------------------------------------------------------------------

    private fun recordFile(id: String) = File(dir, "$id$CAPTURE_EXT")

    private fun serialize(capture: Capture): String = buildString {
        appendLine("id\t${capture.id}")
        appendLine("kind\t${capture.kind}")
        appendLine("createdAt\t${capture.createdAt}")
        appendLine("status\t${capture.status}")
        appendLine("attachment\t${capture.attachment ?: "-"}")
        appendLine("text\t${encode(capture.text)}")
    }

    private fun deserialize(raw: String): Capture {
        val fields = raw.lineSequence().filter { it.contains('\t') }.associate {
            val splitAt = it.indexOf('\t')
            it.substring(0, splitAt) to it.substring(splitAt + 1)
        }
        return Capture(
            id = fields.getValue("id"),
            kind = Capture.Kind.valueOf(fields.getValue("kind")),
            text = decode(fields["text"].orEmpty()),
            attachment = fields["attachment"].takeUnless { it == null || it == "-" },
            createdAt = fields.getValue("createdAt").toLong(),
            status = Capture.Status.valueOf(fields.getValue("status")),
        )
    }

    private fun encode(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun decode(b64: String): String =
        if (b64.isEmpty()) "" else String(Base64.getDecoder().decode(b64))

    private companion object {
        const val CAPTURE_EXT = ".capture"
    }
}
