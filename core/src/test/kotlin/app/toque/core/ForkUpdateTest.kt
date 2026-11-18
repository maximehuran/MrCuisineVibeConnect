package app.toque.core

import app.toque.core.update.Releases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork-only: kept out of UpdateTest so upstream changes to it merge cleanly. */
class ForkUpdateTest {
    @Test fun forkUpdatesAndVersionOrdering() {
        assertEquals("https://api.github.com/repos/maximehuran/MrCuisineVibeConnect/releases/latest", Releases.LATEST_URL)
        assertTrue(Releases.isNewer("0.5.1.2", "0.5.1.1"))
        assertTrue(Releases.isNewer("0.5.2.3", "0.5.1.2"))
        assertFalse(Releases.isNewer("0.5.1", "0.5.1.1"))
    }
}
