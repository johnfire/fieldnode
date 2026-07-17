package de.christopherrehm.fieldnode.file

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileEngineTest {

    private val base = Files.createTempDirectory("engine").toFile()
    private val workspace = File(base, "ws").apply { mkdirs() }
    private val outside = File(base, "outside").apply { mkdirs() }
    private val engine = FileEngine(
        scope = ScopePolicy(listOf(workspace)),
        log = ActionLog(File(base, "log.txt")),
        trash = TrashStore(File(base, "trash"), File(base, "trash/manifest.tsv")),
    )

    @Test fun writeAndReadWithinScope() {
        val file = File(workspace, "a/note.txt")
        assertTrue(engine.writeText(file, "hello").ok)
        assertEquals("hello", engine.readText(file))
    }

    @Test fun deleteWithinScopeGoesToTrashNotOblivion() {
        val file = File(workspace, "doomed.txt")
        engine.writeText(file, "bye")

        val result = engine.delete(file)

        assertTrue(result.ok)
        assertFalse("original removed", file.exists())
        assertEquals("recoverable in trash", 1, engine.trashEntries().size)
    }

    @Test fun deletedFileCanBeRestored() {
        val file = File(workspace, "recover.txt")
        engine.writeText(file, "precious")
        engine.delete(file)

        val trashId = engine.trashEntries().last().trashId
        assertTrue(engine.restore(trashId).ok)
        assertTrue(file.exists())
        assertEquals("precious", engine.readText(file))
    }

    @Test fun deleteOutsideScopeIsBlockedAndFileUntouched() {
        val file = File(outside, "keep.txt").apply { writeText("safe") }

        val result = engine.delete(file)

        assertTrue(result is FileOpResult.Blocked)
        assertTrue("out-of-scope file must be untouched", file.exists())
        assertEquals("nothing trashed", 0, engine.trashEntries().size)
    }

    @Test fun writeOutsideScopeIsBlocked() {
        val file = File(outside, "intruder.txt")
        val result = engine.writeText(file, "nope")

        assertTrue(result is FileOpResult.Blocked)
        assertFalse(file.exists())
    }

    @Test fun overwritePreservesPriorVersionInTrash() {
        val file = File(workspace, "versioned.txt")
        engine.writeText(file, "v1")
        engine.writeText(file, "v2")

        assertEquals("v2", engine.readText(file))
        assertEquals("prior version kept in trash", 1, engine.trashEntries().size)
    }

    @Test fun moveWithinScopeRelocatesFile() {
        val source = File(workspace, "from.txt").also { engine.writeText(it, "x") }
        val destination = File(workspace, "sub/to.txt")

        assertTrue(engine.move(source, destination).ok)
        assertFalse(source.exists())
        assertEquals("x", engine.readText(destination))
    }
}
