package de.christopherrehm.fieldnode

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.christopherrehm.fieldnode.agent.AgentClient
import de.christopherrehm.fieldnode.agent.AgentRunner
import de.christopherrehm.fieldnode.agent.ToolExecutor
import de.christopherrehm.fieldnode.dispatch.FleetConfig
import de.christopherrehm.fieldnode.session.SessionMeta
import de.christopherrehm.fieldnode.session.SessionStore
import de.christopherrehm.fieldnode.session.SessionStoreFactory
import de.christopherrehm.fieldnode.session.SessionTitle
import org.json.JSONObject

/**
 * v3 on-phone agent — a thin HOST over per-session conversations. The agent loop lives in [AgentRunner]
 * (keyed by session id, persisting each turn); this Activity owns the current session, renders its
 * transcript, drives the session drawer (create / switch / rename / archive), and runs a runner on a
 * background Thread with itself as the listener. Swapping that Thread for a foreground service is
 * feature A — nothing here has to change for it.
 */
class AgentActivity : AppCompatActivity() {

    private val executor by lazy { ToolExecutor(applicationContext) }
    private val store: SessionStore by lazy { SessionStoreFactory.create() }
    private lateinit var currentSessionId: String

    private lateinit var drawer: DrawerLayout
    private lateinit var sessionAdapter: SessionListAdapter
    private lateinit var currentSessionLabel: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var voiceButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent)
        drawer = findViewById(R.id.drawer)
        currentSessionLabel = findViewById(R.id.current_session_title)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.transcript_scroll)
        input = findViewById(R.id.agent_input)
        sendButton = findViewById(R.id.agent_send)
        voiceButton = findViewById(R.id.agent_voice)
        sendButton.setOnClickListener { send() }
        voiceButton.setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.open_sessions).setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        findViewById<Button>(R.id.new_session).setOnClickListener { newSession() }

        val list = findViewById<RecyclerView>(R.id.session_list)
        list.layoutManager = LinearLayoutManager(this)
        sessionAdapter = SessionListAdapter(emptyList(), "", { switchTo(it.id) }, { showSessionMenu(it) })
        list.adapter = sessionAdapter

        // First run seeds "Session 1"; otherwise resume the most-recently-updated thread.
        currentSessionId = store.list().firstOrNull()?.id ?: store.create("Session 1").id
        renderSession(currentSessionId)
        refreshSessions()
    }

    @Deprecated("onBackPressed is fine on targetSdk 30; predictive-back is API 33+")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START)
        else super.onBackPressed()
    }

    // --- session drawer --------------------------------------------------------------------------

    private fun refreshSessions() {
        sessionAdapter.submit(store.list(), currentSessionId)
        currentSessionLabel.text = store.load(currentSessionId)?.title.orEmpty()
    }

    private fun switchTo(id: String) {
        currentSessionId = id
        renderSession(id)
        refreshSessions()
        drawer.closeDrawer(GravityCompat.START)
    }

    private fun newSession() {
        val count = store.list(includeArchived = true).size
        switchTo(store.create("Session ${count + 1}").id)
    }

    private fun showSessionMenu(meta: SessionMeta) {
        val actions = arrayOf(getString(R.string.session_rename), getString(R.string.session_delete))
        AlertDialog.Builder(this)
            .setTitle(meta.title)
            .setItems(actions) { _, which -> if (which == 0) renameSession(meta) else archiveSession(meta) }
            .show()
    }

    private fun renameSession(meta: SessionMeta) {
        val field = EditText(this).apply { setText(meta.title); setSelection(text.length) }
        AlertDialog.Builder(this)
            .setTitle(R.string.session_rename)
            .setView(field)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) {
                    store.rename(meta.id, name)
                    refreshSessions()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun archiveSession(meta: SessionMeta) {
        AlertDialog.Builder(this)
            .setTitle(R.string.session_delete)
            .setMessage(R.string.session_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                store.archive(meta.id)
                if (meta.id == currentSessionId) {
                    currentSessionId = store.list().firstOrNull()?.id ?: store.create("Session 1").id
                    renderSession(currentSessionId)
                }
                refreshSessions()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Re-derive the transcript view from a session's stored messages (the messages are the source). */
    private fun renderSession(id: String) {
        transcript.text = ""
        val session = store.load(id) ?: return
        val messages = session.messages
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            when (message.optString("role")) {
                "user" -> append("You: ${message.optString("content")}")
                "assistant" -> renderAssistant(message)
                "tool" -> append("← ${message.optString("content").take(800)}")
            }
        }
    }

    private fun renderAssistant(message: JSONObject) {
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val function = toolCalls.getJSONObject(index).getJSONObject("function")
                append("→ ${function.optString("name")}(${function.optString("arguments")})")
            }
        }
        val content = message.optString("content")
        if (content.isNotBlank()) append("Fieldnode: $content")
    }

    // --- voice + send ----------------------------------------------------------------------------

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

        // Title an untitled thread from its first message (decision #3).
        val isFirstTurn = (store.load(currentSessionId)?.messages?.length() ?: 0) == 0
        store.appendMessage(currentSessionId, JSONObject().put("role", "user").put("content", text))
        if (isFirstTurn) store.rename(currentSessionId, SessionTitle.fromMessage(text))
        refreshSessions()

        sendButton.isEnabled = false
        val sessionId = currentSessionId
        val brain = AgentClient(config)
        Thread { AgentRunner(sessionId, store, brain, executor, uiListener).run() }.start()
    }

    /** Bridges runner events onto the UI thread — the B host's listener. */
    private val uiListener = object : AgentRunner.Listener {
        override fun onToolCall(name: String, argsJson: String) =
            runOnUiThread { append("→ $name($argsJson)") }

        override fun onToolResult(name: String, result: String) =
            runOnUiThread { append("← ${result.take(800)}") }

        override fun onAssistantText(text: String) =
            runOnUiThread { append("Fieldnode: $text") }

        override fun onError(message: String) =
            runOnUiThread { append("⚠️ $message") }

        override fun onFinished() = runOnUiThread {
            sendButton.isEnabled = true
            refreshSessions()
        }
    }

    private fun append(line: String) {
        transcript.append(if (transcript.text.isEmpty()) line else "\n\n$line")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private companion object {
        const val REQUEST_VOICE = 7
    }
}
