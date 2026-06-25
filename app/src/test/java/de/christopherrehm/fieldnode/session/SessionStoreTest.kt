package de.christopherrehm.fieldnode.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStoreTest {

    private val dir = Files.createTempDirectory("sessions").toFile()
    private var clock = 1000L
    private val store = SessionStore(dir) { clock }

    private fun userTurn(text: String) = JSONObject().put("role", "user").put("content", text)

    @Test fun createPersistsAndLoadsBack() {
        val created = store.create("First")
        val loaded = store.load(created.id)!!
        assertEquals(created.id, loaded.id)
        assertEquals("First", loaded.title)
        assertEquals(0, loaded.messages.length())
        assertFalse(loaded.archived)
        assertEquals(AgentSession.RunState.IDLE, loaded.runState)
    }

    @Test fun createDefaultsTitleWhenBlank() {
        val created = store.create("   ")
        assertEquals("Session ${created.id}", store.load(created.id)!!.title)
    }

    @Test fun appendMessagePersistsTurnsInOrder() {
        val session = store.create("t")
        store.appendMessage(session.id, userTurn("hello"))
        store.appendMessage(session.id, JSONObject().put("role", "assistant").put("content", "hi"))
        val messages = store.load(session.id)!!.messages
        assertEquals(2, messages.length())
        assertEquals("hello", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
    }

    @Test fun messageContentSurvivesTabsAndNewlines() {
        val session = store.create("t")
        store.appendMessage(session.id, userTurn("a\tb\nc"))
        assertEquals("a\tb\nc", store.load(session.id)!!.messages.getJSONObject(0).getString("content"))
    }

    @Test fun listIsNewestUpdatedFirst() {
        clock = 100; val a = store.create("a")
        clock = 200; val b = store.create("b")
        clock = 300; store.appendMessage(a.id, userTurn("bump a")) // a becomes most-recently-updated
        assertEquals(listOf(a.id, b.id), store.list().map { it.id })
    }

    @Test fun archiveHidesFromDefaultListButKeepsFileRecoverable() {
        val session = store.create("keep me")
        store.archive(session.id)
        assertTrue(store.list().isEmpty())
        assertEquals(listOf(session.id), store.list(includeArchived = true).map { it.id })
        assertNotNull(store.load(session.id))               // soft delete — file still there
        assertTrue(store.load(session.id)!!.archived)
    }

    @Test fun unarchiveRestoresToDefaultList() {
        val session = store.create("x")
        store.archive(session.id)
        store.unarchive(session.id)
        assertEquals(listOf(session.id), store.list().map { it.id })
        assertFalse(store.load(session.id)!!.archived)
    }

    @Test fun renameUpdatesTitleInFileAndIndex() {
        val session = store.create("old")
        store.rename(session.id, "new")
        assertEquals("new", store.load(session.id)!!.title)
        assertEquals("new", store.list().single().title)
    }

    @Test fun titleWithTabsRoundTripsThroughIndex() {
        store.create("a\tb")                                // index is tab-separated; title is Base64
        assertEquals("a\tb", store.list().single().title)
    }

    @Test fun setRunStatePersistsToIndex() {
        val session = store.create("x")
        store.setRunState(session.id, AgentSession.RunState.RUNNING)
        assertEquals(AgentSession.RunState.RUNNING, store.list().single().runState)
    }

    @Test fun rebuildIndexReconstructsFromSessionFiles() {
        val a = store.create("a"); clock = 2000; val b = store.create("b")
        File(dir, "index").delete()                         // lose the cache
        store.rebuildIndex()
        assertEquals(setOf(a.id, b.id), store.list().map { it.id }.toSet())
    }

    @Test fun listSelfHealsWhenIndexMissing() {
        val session = store.create("a")
        File(dir, "index").delete()
        assertEquals(listOf(session.id), store.list().map { it.id })
    }

    @Test fun maxSessionCharsConstantIsExposed() {
        assertEquals(600_000, AgentSession.MAX_SESSION_CHARS)
    }
}
