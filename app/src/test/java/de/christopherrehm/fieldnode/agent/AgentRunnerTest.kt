package de.christopherrehm.fieldnode.agent

import de.christopherrehm.fieldnode.session.SessionStore
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerTest {

    private val dir = Files.createTempDirectory("runner-sessions").toFile()
    private var clock = 1000L
    private val store = SessionStore(dir) { clock }

    // --- fakes -----------------------------------------------------------------------------------

    /** Replays scripted assistant turns and records each context it was asked to complete. */
    private class FakeBrain(private val scripted: MutableList<JSONObject>) : AgentBrain {
        val seen = mutableListOf<JSONArray>()
        val correlationIds = mutableListOf<String>()
        override fun complete(messages: JSONArray, tools: JSONArray, correlationId: String): JSONObject {
            seen.add(JSONArray(messages.toString())) // deep snapshot — runner mutates the original after
            correlationIds.add(correlationId)
            return scripted.removeAt(0)
        }
    }

    private class FakeExecutor(private val result: String) : ToolRunner {
        val calls = mutableListOf<String>()
        override fun run(name: String, args: JSONObject): String {
            calls.add(name)
            return result
        }
    }

    private class RecordingListener : AgentRunner.Listener {
        val events = mutableListOf<String>()
        override fun onToolCall(name: String, argsJson: String) {
            events.add("call:$name:$argsJson")
        }
        override fun onToolResult(name: String, result: String) {
            events.add("result:$name")
        }
        override fun onAssistantText(text: String) {
            events.add("text:$text")
        }
        override fun onError(message: String) {
            events.add("error:$message")
        }
        override fun onFinished() {
            events.add("finished")
        }
    }

    private fun assistantText(text: String) = JSONObject().put("role", "assistant").put("content", text)

    private fun assistantToolCall(id: String, name: String, args: String) = JSONObject()
        .put("role", "assistant")
        .put("content", "")
        .put(
            "tool_calls",
            JSONArray().put(
                JSONObject().put("id", id).put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", args)),
            )
        )

    private fun userTurn(text: String) = JSONObject().put("role", "user").put("content", text)

    // --- run() loop ------------------------------------------------------------------------------

    @Test fun persistsEveryTurnInOrder() {
        val session = store.create("t")
        store.appendMessage(session.id, userTurn("organize my files"))
        val brain = FakeBrain(
            mutableListOf(
                assistantToolCall("c1", "list_files", """{"path":"/x"}"""),
                assistantText("done"),
            )
        )
        val listener = RecordingListener()
        AgentRunner(
            session.id,
            store,
            brain,
            FakeExecutor("two files"),
            listener,
            systemPrompt = "SYS",
            tools = JSONArray()
        ).run()

        val messages = store.load(session.id)!!.messages
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
        )
        assertEquals("two files", messages.getJSONObject(2).getString("content"))     // tool result persisted
        assertEquals("done", messages.getJSONObject(3).getString("content"))
        assertEquals(
            listOf("call:list_files:{\"path\":\"/x\"}", "result:list_files", "text:done", "finished"),
            listener.events,
        )
    }

    @Test fun correlationIdIsStableAcrossStepsWithinOneRun() {
        val session = store.create("t")
        store.appendMessage(session.id, userTurn("organize my files"))
        val brain = FakeBrain(
            mutableListOf(
                assistantToolCall("c1", "list_files", """{"path":"/x"}"""),
                assistantText("done"),
            )
        )
        AgentRunner(
            session.id,
            store,
            brain,
            FakeExecutor("two files"),
            RecordingListener(),
            systemPrompt = "SYS",
            tools = JSONArray()
        ).run()

        // Two brain.complete() calls happened (tool step + final reply); both carried the same id,
        // so the whole run is traceable through the server logs with one correlation id.
        assertEquals(2, brain.correlationIds.size)
        assertEquals(brain.correlationIds[0], brain.correlationIds[1])
        assertTrue(brain.correlationIds[0].isNotBlank())
    }

    @Test fun plainReplyEndsImmediately() {
        val session = store.create("t")
        store.appendMessage(session.id, userTurn("hi"))
        val brain = FakeBrain(mutableListOf(assistantText("hello")))
        val listener = RecordingListener()
        AgentRunner(session.id, store, brain, FakeExecutor("x"), listener, tools = JSONArray()).run()

        assertEquals(2, store.load(session.id)!!.messages.length())   // user + assistant
        assertEquals(listOf("text:hello", "finished"), listener.events)
    }

    @Test fun reinjectsCurrentSystemPrompt() {
        val session = store.create("t")
        store.appendMessage(session.id, userTurn("hi"))
        val brain = FakeBrain(mutableListOf(assistantText("ok")))
        AgentRunner(
            session.id,
            store,
            brain,
            FakeExecutor("x"),
            RecordingListener(),
            systemPrompt = "FRESH-PROMPT",
            tools = JSONArray()
        ).run()

        val firstContext = brain.seen.first()
        assertEquals("system", firstContext.getJSONObject(0).getString("role"))
        assertEquals("FRESH-PROMPT", firstContext.getJSONObject(0).getString("content"))
        // The system turn is re-injected, never stored.
        assertTrue(
            (0 until store.load(session.id)!!.messages.length()).none {
                store.load(session.id)!!.messages.getJSONObject(it).getString("role") == "system"
            }
        )
    }

    @Test fun missingSessionReportsErrorAndFinishes() {
        val listener = RecordingListener()
        AgentRunner(
            "nope",
            store,
            FakeBrain(mutableListOf()),
            FakeExecutor("x"),
            listener,
            tools = JSONArray()
        ).run()
        assertEquals(listOf("error:session not found", "finished"), listener.events)
    }

    @Test fun runEnforcesCapAndPersistsTheTrim() {
        val session = store.create("t")
        val big = "x".repeat(50)
        store.appendMessage(session.id, userTurn("A$big"))      // oldest round
        store.appendMessage(session.id, assistantText("a$big"))
        store.appendMessage(session.id, userTurn("B$big"))      // most recent user turn
        val cap = 100                                            // only the last user round fits
        val brain = FakeBrain(mutableListOf(assistantText("ok")))

        AgentRunner(
            session.id,
            store,
            brain,
            FakeExecutor("x"),
            RecordingListener(),
            systemPrompt = "SYS",
            tools = JSONArray(),
            cap = cap
        ).run()

        val messages = store.load(session.id)!!.messages
        // Trimmed to [userB, assistant-final]; the older user/assistant round was dropped at the boundary.
        assertEquals(
            listOf("user", "assistant"),
            (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }
        )
        assertTrue(messages.getJSONObject(0).getString("content").startsWith("B"))
        // The brain saw system + trimmed history (system + userB), not the dropped round.
        val firstContext = brain.seen.first()
        assertEquals(2, firstContext.length())
        assertEquals("system", firstContext.getJSONObject(0).getString("role"))
        assertTrue(firstContext.getJSONObject(1).getString("content").startsWith("B"))
    }

    // --- pure truncation -------------------------------------------------------------------------

    @Test fun trimOldestUnderCapReturnsSameInstance() {
        val history = JSONArray().put(userTurn("hi")).put(assistantText("yo"))
        assertSame(history, AgentRunner.trimOldest(history, 10_000))
    }

    @Test fun trimOldestKeepsLongestUserBoundarySuffixThatFits() {
        val big = "x".repeat(50)
        val history = JSONArray()
            .put(userTurn("A$big")).put(assistantText("a$big"))   // round 1
            .put(userTurn("B$big")).put(assistantText("b$big"))   // round 2 (most recent)
        val cap = 200                                             // ~ one round fits, not two
        val trimmed = AgentRunner.trimOldest(history, cap)
        assertEquals(2, trimmed.length())
        assertEquals("user", trimmed.getJSONObject(0).getString("role"))
        assertTrue(trimmed.getJSONObject(0).getString("content").startsWith("B"))
        assertTrue(AgentRunner.contentLength(trimmed) <= cap)
    }

    @Test fun trimOldestNeverLeavesAToolTurnFirst() {
        val big = "x".repeat(50)
        val history = JSONArray()
            .put(userTurn("A$big"))
            .put(assistantToolCall("c1", "list_files", "{}"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "r$big"))
            .put(userTurn("B$big"))
            .put(assistantText("b$big"))
        val trimmed = AgentRunner.trimOldest(history, 200)
        // Cut at the user boundary, so a tool turn is never orphaned without its assistant tool_call.
        assertEquals("user", trimmed.getJSONObject(0).getString("role"))
        assertTrue(trimmed.getJSONObject(0).getString("content").startsWith("B"))
    }

    @Test fun trimOldestKeepsLastUserTurnEvenWhenItAloneExceedsCap() {
        val huge = "x".repeat(500)
        val history = JSONArray()
            .put(userTurn("A")).put(assistantText("a"))
            .put(userTurn("B$huge")).put(assistantText("b$huge"))
        val trimmed = AgentRunner.trimOldest(history, 100)
        assertEquals(2, trimmed.length())                        // from the last user turn, over-cap but recent
        assertTrue(trimmed.getJSONObject(0).getString("content").startsWith("B"))
    }

    @Test fun trimOldestWithNoUserTurnReturnsUnchanged() {
        val history = JSONArray().put(assistantText("x".repeat(500)))
        assertSame(history, AgentRunner.trimOldest(history, 10))
    }
}
