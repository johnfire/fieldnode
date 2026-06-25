package de.christopherrehm.fieldnode

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import de.christopherrehm.fieldnode.agent.AgentClient
import de.christopherrehm.fieldnode.agent.AgentTools
import de.christopherrehm.fieldnode.agent.ToolExecutor
import de.christopherrehm.fieldnode.dispatch.FleetConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3 on-phone agent: chat with an LLM that acts on the phone + fleet. The loop runs HERE (phone-side):
 * send the conversation to the forwarder's /agent proxy → get an assistant turn → if it has tool_calls,
 * run them via ToolExecutor (the caged FileEngine + capture + nearby), append results, and loop until
 * the model answers in plain text.
 */
class AgentActivity : AppCompatActivity() {

    private val executor by lazy { ToolExecutor(applicationContext) }
    private val messages = JSONArray().put(
        JSONObject().put("role", "system").put("content", AgentTools.SYSTEM_PROMPT),
    )

    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var voiceButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.transcript_scroll)
        input = findViewById(R.id.agent_input)
        sendButton = findViewById(R.id.agent_send)
        voiceButton = findViewById(R.id.agent_voice)
        sendButton.setOnClickListener { send() }
        voiceButton.setOnClickListener { startVoiceInput() }
    }

    /** Speak a command → the system recognizer transcribes it → send it straight to the agent. */
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Fieldnode what to do")
        }
        try {
            startActivityForResult(intent, REQUEST_VOICE)
        } catch (error: Exception) {
            append("⚠️ Voice input unavailable: ${error.message}")
        }
    }

    @Deprecated("startActivityForResult is fine for a single one-shot recognizer call")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VOICE && resultCode == Activity.RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                input.setText(spoken)
                send()
            }
        }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val config = FleetConfig.load()
        if (config == null) {
            append("⚠️ No fleet.config set")
            return
        }
        input.setText("")
        append("You: $text")
        messages.put(JSONObject().put("role", "user").put("content", text))
        sendButton.isEnabled = false
        runLoop(config)
    }

    private fun runLoop(config: FleetConfig) {
        val client = AgentClient(config)
        val tools = AgentTools.tools()
        Thread {
            try {
                var step = 0
                while (step++ < MAX_STEPS) {
                    val assistant = client.complete(messages, tools)
                    messages.put(cleanAssistant(assistant))
                    val toolCalls = assistant.optJSONArray("tool_calls")
                    if (toolCalls == null || toolCalls.length() == 0) {
                        val content = assistant.optString("content").ifBlank { "(no reply)" }
                        runOnUiThread { append("Fieldnode: $content") }
                        break
                    }
                    for (index in 0 until toolCalls.length()) {
                        val call = toolCalls.getJSONObject(index)
                        val function = call.getJSONObject("function")
                        val toolName = function.optString("name")
                        val rawArgs = function.optString("arguments").ifBlank { "{}" }
                        val parsedArgs = runCatching { JSONObject(rawArgs) }.getOrDefault(JSONObject())
                        runOnUiThread { append("→ $toolName($rawArgs)") }
                        val result = executor.run(toolName, parsedArgs)
                        runOnUiThread { append("← ${result.take(800)}") }
                        messages.put(
                            JSONObject().put("role", "tool")
                                .put("tool_call_id", call.optString("id"))
                                .put("content", result),
                        )
                    }
                }
            } catch (error: Exception) {
                runOnUiThread { append("⚠️ ${error.message}") }
            } finally {
                runOnUiThread { sendButton.isEnabled = true }
            }
        }.start()
    }

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
                    JSONObject().put("name", function.optString("name")).put("arguments", function.optString("arguments")),
                ),
            )
        }
        return cleaned.put("tool_calls", rebuilt)
    }

    private fun append(line: String) {
        transcript.append(if (transcript.text.isEmpty()) line else "\n\n$line")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private companion object {
        const val MAX_STEPS = 8
        const val REQUEST_VOICE = 7
    }
}
