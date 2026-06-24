package de.christopherrehm.fieldnode.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CaptureStoreTest {

    private val dir = Files.createTempDirectory("captures").toFile()
    private val store = CaptureStore(dir)

    private fun capture(id: String, text: String, status: Capture.Status = Capture.Status.PENDING) =
        Capture(id, Capture.Kind.TEXT, text, null, id.toLong(), status)

    @Test fun savesAndReadsBackACapture() {
        store.save(capture("100", "a thought with\ttabs and\nnewlines"))
        val loaded = store.list().single()
        assertEquals("100", loaded.id)
        assertEquals("a thought with\ttabs and\nnewlines", loaded.text)
        assertEquals(Capture.Status.PENDING, loaded.status)
    }

    @Test fun listIsNewestFirst() {
        store.save(capture("100", "old"))
        store.save(capture("200", "new"))
        assertEquals(listOf("200", "100"), store.list().map { it.id })
    }

    @Test fun pendingExcludesSentAndFailed() {
        store.save(capture("1", "p", Capture.Status.PENDING))
        store.save(capture("2", "s", Capture.Status.SENT))
        store.save(capture("3", "f", Capture.Status.FAILED))
        assertEquals(listOf("1"), store.pending().map { it.id })
    }

    @Test fun updateStatusRewritesInPlace() {
        store.save(capture("42", "to send"))
        store.updateStatus("42", Capture.Status.SENT)
        assertEquals(Capture.Status.SENT, store.list().single().status)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun attachmentRoundTrips() {
        val name = store.saveAttachment("9", "jpg", byteArrayOf(1, 2, 3))
        store.save(Capture("9", Capture.Kind.IMAGE, "", name, 9L, Capture.Status.PENDING))
        val loaded = store.list().single()
        assertEquals(name, loaded.attachment)
        assertEquals(3, store.attachmentFile(loaded)!!.readBytes().size)
    }

    @Test fun textCaptureHasNoAttachment() {
        store.save(capture("5", "plain"))
        assertNull(store.list().single().attachment)
    }
}
