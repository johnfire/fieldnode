package de.christopherrehm.fieldnode.session

import java.io.File
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for agent conversation threads. One file per session (`<id>.session`, a JSON object) plus
 * a rebuildable `index` (tab-separated, one line per session) so the list UI never has to parse every
 * full conversation just to draw the list. Plain `java.io.File` + `org.json` — the same wire format
 * `AgentClient` already speaks — so it stays unit-testable on the JVM (the real org.json is a
 * test-scoped dependency; the app gets org.json from Android at runtime).
 *
 * Anti-fragility: the `.session` files are the source of truth; the `index` is a derived cache, rebuilt
 * by scanning them when it's missing or unreadable. Losing the index must never lose a conversation.
 *
 * App plumbing, deliberately separate from the agent-facing FileEngine (mirrors CaptureStore). The clock
 * is injectable so ids/timestamps are deterministic under test (mirrors ActionLog).
 */
class SessionStore(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun create(title: String? = null): AgentSession {
        val stamp = now()
        val id = stamp.toString()
        val session = AgentSession(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: "Session $id",
            createdAt = stamp,
            updatedAt = stamp,
            messages = JSONArray(),
        )
        write(session)
        return session
    }

    /** Active sessions, newest-updated first. Archived (soft-deleted) sessions are excluded by default. */
    fun list(includeArchived: Boolean = false): List<SessionMeta> =
        indexEntries().values
            .filter { includeArchived || !it.archived }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): AgentSession? =
        runCatching { deserialize(recordFile(id).readText()) }.getOrNull()

    /** Append one protocol turn and bump updatedAt. Durable per-turn so a crash/switch loses nothing. */
    fun appendMessage(id: String, turn: JSONObject) {
        val session = load(id) ?: return
        session.messages.put(turn)
        write(session.copy(updatedAt = now()))
    }

    fun rename(id: String, title: String) {
        val session = load(id) ?: return
        write(session.copy(title = title, updatedAt = now()))
    }

    /** Soft delete — recoverable. The `.session` file stays; it's flagged archived and dropped from list(). */
    fun archive(id: String) = setArchived(id, true)

    fun unarchive(id: String) = setArchived(id, false)

    /** A's hook: mark a session running/done without touching its conversation. */
    fun setRunState(id: String, state: AgentSession.RunState) {
        val session = load(id) ?: return
        write(session.copy(runState = state))
    }

    /**
     * Replace a session's whole message history — used by the runner's cap enforcement (truncate-oldest).
     * Deliberately does NOT bump updatedAt: trimming is housekeeping, not a content update, so it must not
     * reorder the session list.
     */
    fun replaceMessages(id: String, messages: JSONArray) {
        val session = load(id) ?: return
        write(session.copy(messages = messages))
    }

    /** Rebuild the index cache from the source-of-truth `.session` files. */
    fun rebuildIndex() {
        val sessions = (dir.listFiles { f -> f.name.endsWith(SESSION_EXT) } ?: emptyArray())
            .mapNotNull { runCatching { deserialize(it.readText()) }.getOrNull() }
        writeIndex(sessions.associate { it.id to it.meta() })
    }

    // --- internals -------------------------------------------------------------------------------

    private fun setArchived(id: String, archived: Boolean) {
        val session = load(id) ?: return
        write(session.copy(archived = archived))
    }

    /** Write the session file (source of truth) then upsert its line in the index cache. */
    private fun write(session: AgentSession) {
        dir.mkdirs()
        recordFile(session.id).writeText(serialize(session))
        val entries = indexEntries().toMutableMap()
        entries[session.id] = session.meta()
        writeIndex(entries)
    }

    private fun indexEntries(): Map<String, SessionMeta> {
        val index = indexFile()
        if (!index.exists()) {
            rebuildIndex()
            if (!index.exists()) return emptyMap()
        }
        return index.readLines().mapNotNull { parseIndexLine(it) }.associateBy { it.id }
    }

    private fun writeIndex(entries: Map<String, SessionMeta>) {
        dir.mkdirs()
        val body = entries.values
            .sortedByDescending { it.updatedAt }
            .joinToString("\n") { formatIndexLine(it) }
        // Write-temp-then-rename: a crash mid-write can't leave a half-line. The index is a rebuildable
        // cache anyway, so this is belt-and-suspenders.
        val tmp = File(dir, "$INDEX_NAME.tmp")
        tmp.writeText(if (body.isEmpty()) "" else "$body\n")
        if (!tmp.renameTo(indexFile())) {
            tmp.copyTo(indexFile(), overwrite = true)
            tmp.delete()
        }
    }

    // --- serialization ---------------------------------------------------------------------------

    private fun recordFile(id: String) = File(dir, "$id$SESSION_EXT")
    private fun indexFile() = File(dir, INDEX_NAME)

    private fun serialize(session: AgentSession): String = JSONObject()
        .put("id", session.id)
        .put("title", session.title)
        .put("createdAt", session.createdAt)
        .put("updatedAt", session.updatedAt)
        .put("archived", session.archived)
        .put("runState", session.runState.name)
        .put("messages", session.messages)
        .toString()

    private fun deserialize(raw: String): AgentSession {
        val obj = JSONObject(raw)
        return AgentSession(
            id = obj.getString("id"),
            title = obj.getString("title"),
            createdAt = obj.getLong("createdAt"),
            updatedAt = obj.getLong("updatedAt"),
            messages = obj.optJSONArray("messages") ?: JSONArray(),
            archived = obj.optBoolean("archived", false),
            runState = AgentSession.RunState.valueOf(obj.optString("runState", "IDLE")),
        )
    }

    /** Index line: `id  base64(title)  createdAt  updatedAt  archived(0/1)  runState`. Title is Base64
     *  so a tab/newline in it can't corrupt the line format (same trick CaptureStore uses for text). */
    private fun formatIndexLine(meta: SessionMeta): String = listOf(
        meta.id,
        Base64.getEncoder().encodeToString(meta.title.toByteArray()),
        meta.createdAt.toString(),
        meta.updatedAt.toString(),
        if (meta.archived) "1" else "0",
        meta.runState.name,
    ).joinToString("\t")

    private fun parseIndexLine(line: String): SessionMeta? {
        if (line.isBlank()) return null
        val fields = line.split('\t')
        if (fields.size < 6) return null
        return runCatching {
            SessionMeta(
                id = fields[0],
                title = String(Base64.getDecoder().decode(fields[1])),
                createdAt = fields[2].toLong(),
                updatedAt = fields[3].toLong(),
                archived = fields[4] == "1",
                runState = AgentSession.RunState.valueOf(fields[5]),
            )
        }.getOrNull()
    }

    private companion object {
        const val SESSION_EXT = ".session"
        const val INDEX_NAME = "index"
    }
}
