package io.github.bozkow.vibevisualizer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    private fun hit(q: String, text: String, ctx: String = "") = SearchMatch.score(SearchMatch.query(q), text, ctx)

    @Test fun anyOrderAndHalfTyped() {
        assertNotNull(hit("screen lock", "Show over the lock screen"))
        assertNotNull(hit("keep scr", "Keep the screen on while music plays"))
    }

    @Test fun typos() {
        assertNotNull(hit("canvsa", "Canvas"))          // two letters swapped
        assertNotNull(hit("tranlation", "Translation service"))   // one missing
        assertNotNull(hit("romanizaton", "Romanization"))
        assertNull(hit("cat", "Canvas"))                 // short words need to be right
    }

    @Test fun accentsDashesAndJoinedWords() {
        assertNotNull(hit("picture in picture", "Picture-in-picture"))
        assertNotNull(hit("lockscreen", "Show over the lock screen"))
        assertNotNull(hit("pip", "Picture-in-picture"))
        assertNotNull(hit("cafe", "Café"))
    }

    @Test fun sectionAndPageCount() {
        assertNotNull(hit("widget size", "Lyrics size", "Phone Home screen widget"))
        assertNull(hit("widget size", "Lyrics size", "Lyrics"))
    }

    @Test fun everyWordHasToMatch() = assertNull(hit("canvas zebra", "Canvas"))

    @Test fun closerRanksHigher() {
        val exact = hit("canvas blur", "Canvas blur")!!; val loose = hit("canvas blur", "Blur the Canvas")!!
        assertTrue(exact > loose)
    }

    @Test fun distance() {
        assertEquals(1, SearchMatch.dist("ab", "ba"))
        assertEquals(1, SearchMatch.dist("blur", "blr"))
        assertEquals(0, SearchMatch.dist("x", "x"))
    }
}
