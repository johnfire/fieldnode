package de.christopherrehm.fieldnode.agent

import de.christopherrehm.fieldnode.dispatch.FleetConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One LLM turn via the forwarder's /agent proxy. Returns the assistant message (content + tool_calls). */
class AgentClient(private val config: FleetConfig) {

    fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject().put("messages", messages).put("tools", tools).toString()
        val connection = (URL("${config.baseUrl}/agent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Device-Token", config.token)
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(180)}")
        return JSONObject(text)
    }
}
