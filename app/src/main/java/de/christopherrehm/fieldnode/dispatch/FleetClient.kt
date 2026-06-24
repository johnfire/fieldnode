package de.christopherrehm.fieldnode.dispatch

import de.christopherrehm.fieldnode.capture.Capture
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posts one capture to the fleet endpoint as JSON, with the device token in a header. Plain
 * HttpURLConnection — no HTTP-library dependency. Blocking; call on a background thread.
 */
class FleetClient(private val config: FleetConfig) {

    fun send(capture: Capture): Result {
        val payload = JSONObject().apply {
            put("id", capture.id)
            put("kind", capture.kind.name)
            put("text", capture.text)
            put("createdAt", capture.createdAt)
        }.toString()

        return try {
            val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-Token", config.token)
            }
            connection.outputStream.use { it.write(payload.toByteArray()) }
            val code = connection.responseCode
            connection.disconnect()
            if (code in 200..299) Result.Sent else Result.Failed("HTTP $code")
        } catch (error: Exception) {
            Result.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    sealed interface Result {
        data object Sent : Result
        data class Failed(val reason: String) : Result
    }
}
