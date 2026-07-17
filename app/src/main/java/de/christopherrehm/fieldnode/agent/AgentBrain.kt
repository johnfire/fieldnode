package de.christopherrehm.fieldnode.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * One LLM turn: given the conversation + tool catalogue, return the assistant message (content +
 * optional tool_calls). The seam that lets [AgentRunner] run against a real network ([AgentClient]) in
 * the app and a scripted fake on the JVM in tests — the runner never knows which.
 *
 * [correlationId] identifies the whole agent run this turn belongs to (coding-standards 7.5) — every
 * /agent call within one [AgentRunner.run] shares it, so the server-side logs for a multi-step run can
 * be traced end to end with a single id.
 */
interface AgentBrain {
    fun complete(messages: JSONArray, tools: JSONArray, correlationId: String): JSONObject
}
