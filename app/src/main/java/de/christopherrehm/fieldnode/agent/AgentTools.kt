package de.christopherrehm.fieldnode.agent

import org.json.JSONArray

/** The agent's identity and its tool catalogue (OpenAI/DeepSeek function-calling schema). */
object AgentTools {

    val SYSTEM_PROMPT = """
        You are Fieldnode, an assistant running ON Chris's Android phone (a Redmi Note 8 Pro).
        You act on the phone and his agent fleet using the provided tools. Be concise and do things
        directly rather than asking permission for small steps.

        Files: always use absolute paths. Shared storage root is /storage/emulated/0; your managed
        area is /storage/emulated/0/Fieldnode. You may READ anywhere, but WRITE / MOVE / DELETE only
        within the writable scope — call writable_scope to see it. Deletes are reversible (they go to a
        trash, not gone). If unsure of a path, call list_files first. After acting, briefly tell Chris
        what you did.
    """.trimIndent()

    fun tools(): JSONArray = JSONArray(TOOLS_JSON)

    private val TOOLS_JSON = """
    [
      {"type":"function","function":{"name":"writable_scope","description":"Show which folders the agent may write/move/delete in.","parameters":{"type":"object","properties":{}}}},
      {"type":"function","function":{"name":"list_files","description":"List entries in a directory (read is unrestricted).","parameters":{"type":"object","properties":{"path":{"type":"string","description":"absolute directory path"}},"required":["path"]}}},
      {"type":"function","function":{"name":"read_file","description":"Read a text file (first 4000 chars).","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
      {"type":"function","function":{"name":"write_file","description":"Create or overwrite a text file (only within the writable scope; a prior version is trashed).","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}}},
      {"type":"function","function":{"name":"create_dir","description":"Create a directory within the writable scope.","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
      {"type":"function","function":{"name":"move_file","description":"Move/rename a file within the writable scope.","parameters":{"type":"object","properties":{"source":{"type":"string"},"destination":{"type":"string"}},"required":["source","destination"]}}},
      {"type":"function","function":{"name":"delete_file","description":"Delete a file (moves to recoverable trash; only within the writable scope).","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},
      {"type":"function","function":{"name":"capture_note","description":"Save a note to the capture queue (dispatched to the fleet/Open Brain).","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}},
      {"type":"function","function":{"name":"find_nearby_leads","description":"Find engcrm business leads near the phone's current location.","parameters":{"type":"object","properties":{}}}}
    ]
    """.trimIndent()
}
