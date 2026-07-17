package de.christopherrehm.fieldnode.file

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashStoreTest {

    private val base = Files.createTempDirectory("trash").toFile()
    private val trashDir = File(base, "trash")
    private val manifest = File(trashDir, "manifest.tsv")
    private var clock = 1_000L
    private val trash = TrashStore(trashDir, manifest) { clock }

    private fun seedFile(name: String, content: String): File = File(base, name).apply { writeText(content) }

    @Test fun trashingMovesFileAndRecordsManifest() {
        val file = seedFile("doc.txt", "data")
        val id = trash.trash(file)

        assertNotNull(id)
        assertFalse("original should be gone", file.exists())
        assertEquals(1, trash.entries().size)
        assertEquals(file.absolutePath, trash.entries().first().originalPath)
    }

    @Test fun trashingMissingFileReturnsNull() {
        assertNull(trash.trash(File(base, "ghost.txt")))
    }

    @Test fun restoreReturnsFileToOriginalPathWithContent() {
        val file = seedFile("note.txt", "keep me")
        val id = trash.trash(file)!!

        assertTrue(trash.restore(id))
        assertTrue(file.exists())
        assertEquals("keep me", file.readText())
        assertEquals("manifest cleared after restore", 0, trash.entries().size)
    }

    @Test fun sweepPurgesOnlyEntriesOlderThanTtl() {
        val ttl = 10_000L
        clock = 1_000L
        trash.trash(seedFile("old.txt", "x"))   // trashedAt = 1000
        clock = 9_000L
        trash.trash(seedFile("new.txt", "y"))    // trashedAt = 9000

        clock = 12_000L                          // cutoff = 12000 - 10000 = 2000
        val purged = trash.sweepExpired(ttl)

        assertEquals("only the 1000-era entry is past TTL", 1, purged)
        assertEquals(1, trash.entries().size)
        assertEquals("new.txt", File(trash.entries().first().originalPath).name)
    }
}
