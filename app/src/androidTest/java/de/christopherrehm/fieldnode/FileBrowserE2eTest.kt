package de.christopherrehm.fieldnode

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented e2e smoke of the file-browser confirm + undo flow
 * (coding-standards §9.0 Phase 3 item 7).
 *
 * Creates the test file through the app's own UI to avoid root-ownership
 * issues with shell-created files on sdcardfs/FUSE-backed storage.
 */
@RunWith(AndroidJUnit4::class)
class FileBrowserE2eTest {

    private val testFilePath = "/storage/emulated/0/Fieldnode/e2e-smoke.txt"

    private val uiAutomation
        get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    @Before
    fun setUp() {
        uiAutomation.executeShellCommand(
            "appops set de.christopherrehm.fieldnode MANAGE_EXTERNAL_STORAGE allow",
        )
        uiAutomation.executeShellCommand("settings put global window_animation_scale 0.0")
        uiAutomation.executeShellCommand("settings put global transition_animation_scale 0.0")
        uiAutomation.executeShellCommand("settings put global animator_duration_scale 0.0")
        uiAutomation.executeShellCommand("rm -rf $testFilePath")
    }

    @After
    fun tearDown() {
        uiAutomation.executeShellCommand("rm -rf $testFilePath")
        uiAutomation.executeShellCommand("settings put global window_animation_scale 1.0")
        uiAutomation.executeShellCommand("settings put global transition_animation_scale 1.0")
        uiAutomation.executeShellCommand("settings put global animator_duration_scale 1.0")
    }

    // ---- confirm + undo round-trip -------------------------------------------------

    @Test
    fun deleteThenUndoRestoresTheFile() {
        ActivityScenario.launch(FileBrowserActivity::class.java)

        navigateToDir("Fieldnode")

        // Create the file through the app UI.
        createFile("e2e-smoke.txt")

        // Now delete it.
        tapEntry("e2e-smoke.txt")

        onView(withText("Delete"))
            .inRoot(isDialog())
            .perform(click())
        onView(withText("Delete"))
            .inRoot(isDialog())
            .perform(click())

        onView(withText(containsString("Moved to trash")))
            .check(matches(isDisplayed()))

        Thread.sleep(200)
        assertFalse("file moved to trash", File(testFilePath).exists())

        // UNDO.
        onView(withText("UNDO"))
            .perform(click())

        Thread.sleep(500)
        assertTrue("file restored after undo", File(testFilePath).exists())
    }

    @Test
    fun deleteWithoutUndoFileIsGone() {
        ActivityScenario.launch(FileBrowserActivity::class.java)

        navigateToDir("Fieldnode")
        createFile("e2e-smoke.txt")
        tapEntry("e2e-smoke.txt")

        onView(withText("Delete"))
            .inRoot(isDialog())
            .perform(click())
        onView(withText("Delete"))
            .inRoot(isDialog())
            .perform(click())

        onView(withText(containsString("Moved to trash")))
            .check(matches(isDisplayed()))

        Thread.sleep(200)
        assertFalse("file is in trash, not on disk", File(testFilePath).exists())
    }

    // ---- helpers -------------------------------------------------------------------

    /** Navigate into [dirName] by tapping its entry. Uses onView since the
     *  displayed text for dirs is "[dir]  name". */
    private fun navigateToDir(dirName: String) {
        onView(withText("[dir]  $dirName"))
            .perform(click())
    }

    /** Tap an entry in the file list by its displayed text.
     *  Files are displayed as "       name" (7 spaces padding). */
    private fun tapEntry(name: String) {
        onView(withText("       $name"))
            .perform(click())
    }

    /** Create a file via the New file button → type name → Create dialog flow. */
    private fun createFile(name: String) {
        onView(withId(R.id.new_file_button))
            .perform(click())

        // Dialog's EditText has id=-1 on Material dialogs; match by hint.
        onView(withHint("file name"))
            .inRoot(isDialog())
            .perform(replaceText(name))

        onView(withText("Create"))
            .inRoot(isDialog())
            .perform(click())
    }
}
