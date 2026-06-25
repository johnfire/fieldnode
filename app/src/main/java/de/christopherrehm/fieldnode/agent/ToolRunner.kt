package de.christopherrehm.fieldnode.agent

import org.json.JSONObject

/**
 * Runs one of the agent's tool calls and returns a human/LLM-readable result string. [ToolExecutor] is
 * the real (Android, caged-FileEngine) implementation; tests inject a fake so [AgentRunner] is exercisable
 * without a device.
 */
interface ToolRunner {
    fun run(name: String, args: JSONObject): String
}
