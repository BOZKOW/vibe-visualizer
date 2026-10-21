package io.github.bozkow.vibevisualizer.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource(n)!!.readText()

    @Test fun ttml() {
        val p = fromText(res("test.ttml"))
        assertTrue(p.sync)
        assertEquals(8, p.lines.size)
        val l0 = p.lines[0]
        assertEquals(6.0, l0.t!!, 1e-9)
        assertEquals("Hello from the visualizer", l0.text)
        assertEquals(4, l0.words!!.size)
        assertTrue(l0.words!![0].sp)
        val l1 = p.lines[1]
        assertNotNull(l1.bg)
        assertEquals("(lights up)", l1.bg!![0].text)
        assertTrue("second singer on the right", p.lines[2].opp)
        assertEquals(listOf("Test Writer", "Second Writer"), p.writers)
    }

    @Test fun lrc() {
        val l = parseLRC("[00:01.00]One\n[00:03.50]\n[00:05.25][00:07.00]Two")
        assertEquals(4, l.size)
        assertTrue(l[1].dots)
        assertEquals(5.25, l[2].t!!, 1e-9)
    }

    @Test fun interludesAndKeys() {
        val p = fromText(res("test.ttml"))
        val i = interludes(p.lines)
        assertTrue("an intro break", i[0].dots)
        assertTrue("a break before 26 s", i.any { it.dots && (it.t ?: 0.0) in 18.0..19.0 })
        assertTrue(nameKeys("Vibe Tester - Test Song.ttml").contains("n:vibe tester|test song"))
        assertTrue(songKeys(SongMeta(title = "Test Song", artist = "Vibe Tester", file = "Vibe Tester - Test Song.wav")).contains("n:vibe tester|test song"))
    }

    @Test fun joins() {
        val ws = listOf(Word(0.0, .2, "beau", false), Word(.2, .4, "ti", false), Word(.4, .6, "ful", true))
        val ts = ws.map { TS(it.t, it.end!!) }
        val (jw, jt) = joinWords(ws, ts)
        assertEquals(1, jw.size); assertEquals("beautiful", jw[0].text); assertEquals(.6, jt[0].end, 1e-9)
    }
}
