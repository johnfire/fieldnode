package de.christopherrehm.fieldnode.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * One LLM turn: given the conversation + tool catalogue, return the assistant message (content +
 * optional tool_calls). The seam that lets [AgentRunner] run against a real network ([AgentClient]) in
 * the app and a scripted fake on the JVM in tests — the runner never knows which.
 */
interface AgentBrain {
    fun complete(messages: JSONArray, tools: JSONArray): JSONObject
}
