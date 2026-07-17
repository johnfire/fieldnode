package de.christopherrehm.fieldnode.agent

import de.christopherrehm.fieldnode.session.AgentSession
import de.christopherrehm.fieldnode.session.SessionStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent loop, lifted out of AgentActivity so it depends only on a session id + a [SessionStore] +
 * collaborators + a [Listener] — never on Activity fields or a UI thread. THIS is the seam that makes
 * concurrent background execution (feature A) purely additive: in B the host is a Thread with the Activity
 * as listener; in A the same `run()` is hosted by a foreground service with a notifying listener. Nothing
 * here changes.
 *
 * Per-turn durability: every turn (assistant, tool result) is persisted to the store as it's produced, so
 * a session switch or a crash mid-run can't lose the thread. The user turn is persisted by the caller
 * before `run()` (the store already holds it when we load).
 *
 * The system prompt is re-injected fresh each run (not stored), so editing [AgentTools.SYSTEM_PROMPT]
 * applies to existing threads.
 */
class AgentRunner(
    private val sessionId: String,
    private val store: SessionStore,
    private val brain: AgentBrain,
    private val executor: ToolRunner,
    private val listener: Listener,
    private val systemPrompt: String = AgentTools.SYSTEM_PROMPT,
    private val tools: JSONArray = AgentTools.tools(),
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val cap: Int = AgentSession.MAX_SESSION_CHARS,
) {
    /** How a run reports progress — decoupled from any UI. The Activity renders these; A will notify. */
    interface Listener {
        fun onToolCall(name: String, argsJson: String)
        fun onToolResult(name: String, result: String)
        fun onAssistantText(text: String)
        fun onError(message: String)
        fun onFinished()
    }

    fun run() {
        // One id for the whole run (coding-standards 7.5): every /agent call this run makes, however
        // many steps it takes, shares it, so the server-side logs for one run can be traced as a unit.
        val correlationId = UUID.randomUUID().toString()
        try {
            val session = store.load(sessionId)
            if (session == null) {
                listener.onError("session not found")
                return
            }
            // Enforce the cap on load: drop oldest turns at a safe (user) boundary, preserving the most
            // recent context. Persist the trim so storage is bounded too.
            val history = trimOldest(session.messages, cap)
            if (history !== session.messages) store.replaceMessages(sessionId, history)

            val context = JSONArray().put(systemTurn())
            for (index in 0 until history.length()) context.put(history.getJSONObject(index))

            var step = 0
            while (step++ < maxSteps) {
                val assistant = brain.complete(context, tools, correlationId)
                val cleaned = cleanAssistant(assistant)
                context.put(cleaned)
                store.appendMessage(sessionId, cleaned)

                val toolCalls = assistant.optJSONArray("tool_calls")
                if (toolCalls == null || toolCalls.length() == 0) {
                    listener.onAssistantText(assistant.optString("content").ifBlank { "(no reply)" })
                    return
                }
                for (index in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(index)
                    val function = call.getJSONObject("function")
                    val toolName = function.optString("name")
                    val rawArgs = function.optString("arguments").ifBlank { "{}" }
                    val parsedArgs = runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())
                    listener.onToolCall(toolName, rawArgs)
                    val result = executor.run(toolName, parsedArgs)
                    listener.onToolResult(toolName, result)
                    val toolTurn = JSONObject().put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("content", result)
                    context.put(toolTurn)
                    store.appendMessage(sessionId, toolTurn)
                }
            }
        } catch (error: Exception) {
            listener.onError(error.message ?: "error")
        } finally {
            listener.onFinished()
        }
    }

    private fun systemTurn(): JSONObject =
        JSONObject().put("role", "system").put("content", systemPrompt)

    /** Echo the assistant turn back stripped to what the protocol needs (role, content, tool_calls). */
    private fun cleanAssistant(message: JSONObject): JSONObject {
        val cleaned = JSONObject().put("role", "assistant").put("content", message.optString("content"))
        val toolCalls = message.optJSONArray("tool_calls") ?: return cleaned
        val rebuilt = JSONArray()
        for (index in 0 until toolCalls.length()) {
            val call = toolCalls.getJSONObject(index)
            val function = call.getJSONObject("function")
            rebuilt.put(
                JSONObject().put("id", call.optString("id")).put("type", "function").put(
                    "function",
                    JSONObject().put("name", function.optString("name"))
                        .put("arguments", function.optString("arguments")),
                ),
            )
        }
        return cleaned.put("tool_calls", rebuilt)
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 8

        /** Total serialized length of a message history — the measure the cap is expressed in. */
        internal fun contentLength(history: JSONArray): Int {
            var total = 0
            for (index in 0 until history.length()) total += history.get(index).toString().length
            return total
        }

        /**
         * Truncate-oldest cap enforcement. If [history] is within [cap], returns it unchanged (same ref).
         * Otherwise keeps the longest *suffix that starts at a `user` turn* and fits the cap — cutting at a
         * user boundary is what guarantees we never orphan a `tool` turn from the `assistant` tool_call it
         * answers. If even the last user turn's suffix exceeds the cap, we still keep from that last user
         * turn (always preserve the most recent context). With no user turn to cut at, returns unchanged.
         */
        internal fun trimOldest(history: JSONArray, cap: Int): JSONArray {
            if (contentLength(history) <= cap) return history
            val userStarts = (0 until history.length())
                .filter { history.optJSONObject(it)?.optString("role") == "user" }
            if (userStarts.isEmpty()) return history

            var start = userStarts.last()
            for (candidate in userStarts) {
                if (suffixLength(history, candidate) <= cap) {
                    start = candidate
                    break
                }
            }
            if (start == 0) return history

            val trimmed = JSONArray()
            for (index in start until history.length()) trimmed.put(history.get(index))
            return trimmed
        }

        private fun suffixLength(history: JSONArray, from: Int): Int {
            var total = 0
            for (index in from until history.length()) total += history.get(index).toString().length
            return total
        }
    }
}
