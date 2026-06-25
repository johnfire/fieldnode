package de.christopherrehm.fieldnode.session

import org.json.JSONArray

/**
 * One agent conversation thread — the per-thread analogue of [de.christopherrehm.fieldnode.capture.Capture].
 * [messages] is the DeepSeek loop context: the protocol turns (user / assistant-with-tool_calls / tool
 * results) WITHOUT the leading system turn — the current system prompt is prepended at load time so a
 * prompt edit applies to existing threads. [archived] is the soft-delete flag (deletes are recoverable,
 * matching the project's undoable-everything posture). [runState] is reserved for concurrent background
 * execution (feature A) and stays IDLE until then.
 *
 * Plain data class; [SessionStore] owns (de)serialization — same split as Capture/CaptureStore.
 */
data class AgentSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: JSONArray,
    val archived: Boolean = false,
    val runState: RunState = RunState.IDLE,
) {
    /** Lifecycle of a run. B only ever writes IDLE; A drives RUNNING/DONE/ERROR. */
    enum class RunState { IDLE, RUNNING, DONE, ERROR }

    fun meta(): SessionMeta = SessionMeta(id, title, createdAt, updatedAt, archived, runState)

    companion object {
        /**
         * Per-session conversation-content cap (~characters). When a session grows past this, the runner
         * truncates the OLDEST turns while always preserving the system prompt and the most-recent
         * context (truncate-oldest is v1; summarize-oldest may replace it later). Enforcement lands when
         * the runner is extracted (slice 2); this is the single named source of the limit.
         */
        const val MAX_SESSION_CHARS = 600_000
    }
}

/** The index row — everything a session list needs to render WITHOUT parsing the full conversation. */
data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean,
    val runState: AgentSession.RunState,
)
