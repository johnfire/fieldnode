package de.christopherrehm.fieldnode.file

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLogTest {

    private val dir = Files.createTempDirectory("action-log").toFile()
    private val log = ActionLog(File(dir, "actions.log")) { 1_700_000_000_000L }

    @Test fun recordWritesOperationOutcomeActorAndTarget() {
        log.record("write", "/x/note.txt", "OK", Actor.USER)

        val line = log.readAll().trim()
        assertTrue("operation missing", line.contains("write"))
        assertTrue("outcome missing", line.contains("OK"))
        assertTrue("actor missing", line.contains(Actor.USER))
        assertTrue("target missing", line.contains("/x/note.txt"))
    }

    @Test fun recordDistinguishesUserFromAiAgent() {
        log.record("delete", "/x/a.txt", "OK", Actor.USER)
        log.record("delete", "/x/b.txt", "OK", Actor.AI_AGENT)

        val lines = log.readAll().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains(Actor.USER))
        assertTrue(lines[1].contains(Actor.AI_AGENT))
    }

    @Test fun detailIsOptionalAndOmittedWhenBlank() {
        log.record("mkdir", "/x", "OK", Actor.USER)

        val fields = log.readAll().trim().split('\t')
        // timestamp, operation, outcome, actor, target — no trailing detail column.
        assertEquals(5, fields.size)
    }

    @Test fun detailIsAppendedWhenPresent() {
        log.record("mkdir", "/x", "BLOCKED", Actor.AI_AGENT, "out of scope")

        val fields = log.readAll().trim().split('\t')
        assertEquals(6, fields.size)
        assertEquals("out of scope", fields.last())
    }

    @Test fun lineCountIgnoresBlankLines() {
        log.record("mkdir", "/x", "OK", Actor.USER)
        log.record("mkdir", "/y", "OK", Actor.USER)

        assertEquals(2, log.lineCount())
    }

    @Test fun readAllOnMissingFileReturnsEmptyString() {
        val missing = ActionLog(File(dir, "never-written.log"))

        assertEquals("", missing.readAll())
    }
}
