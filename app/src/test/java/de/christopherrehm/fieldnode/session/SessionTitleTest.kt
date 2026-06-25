package de.christopherrehm.fieldnode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTitleTest {

    @Test fun shortMessageBecomesTheTitleVerbatim() {
        assertEquals("organize my downloads", SessionTitle.fromMessage("organize my downloads"))
    }

    @Test fun whitespaceIsFlattenedAndTrimmed() {
        assertEquals("clean up the files", SessionTitle.fromMessage("  clean\tup\n  the   files  "))
    }

    @Test fun longMessageIsTruncatedWithEllipsis() {
        val title = SessionTitle.fromMessage("a".repeat(100))
        assertTrue(title.length <= SessionTitle.MAX + 1)   // +1 for the ellipsis char
        assertTrue(title.endsWith("…"))
    }

    @Test fun blankFallsBackToNewSession() {
        assertEquals("New session", SessionTitle.fromMessage("   \n\t  "))
    }
}
