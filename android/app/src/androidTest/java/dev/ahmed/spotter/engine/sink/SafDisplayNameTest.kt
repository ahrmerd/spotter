package dev.ahmed.spotter.engine.sink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** M5 audit F1: a picked-folder (tree) URI used to crash the app at start-up (UnsupportedOperationException). */
@RunWith(AndroidJUnit4::class)
class SafDisplayNameTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    @Test
    fun treeUriNeverThrowsAndFallsBackToTheFolderName() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        // No permission is granted for this URI in the test, so the provider refuses; the label must still come back.
        assertEquals("Movies", SafSink.displayName(resolver, tree))
    }

    @Test
    fun blankAndGarbageInputIsHandled() {
        assertEquals("", SafSink.displayName(resolver, ""))
        assertTrue(SafSink.displayName(resolver, "content://not.a.provider/tree/x%3Ay").isNotEmpty())
    }
}
