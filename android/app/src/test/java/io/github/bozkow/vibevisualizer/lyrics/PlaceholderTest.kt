package io.github.bozkow.vibevisualizer.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "This song has no lyrics" notes some catalogues send instead of lyrics are told apart from real lyrics. */
class PlaceholderTest {
    private fun ls(vararg t: String) = t.mapIndexed { i, x -> Line(i * 2.0, null, x) }

    @Test fun notes() {
        assertTrue(SourceParse.placeholder(ls("纯音乐，请欣赏")))
        assertTrue(SourceParse.placeholder(ls("此歌曲为没有填词的纯音乐，请您欣赏")))
        assertTrue(SourceParse.placeholder(ls("暂无歌词")))
        assertTrue(SourceParse.placeholder(ls("Instrumental")))
        assertTrue(SourceParse.placeholder(ls("♪ Instrumental ♪")))
        assertTrue(SourceParse.placeholder(listOf(Line(0.0, null, "", dots = true)) + ls("純音樂，請欣賞")))
    }

    @Test fun realLyricsStay() {
        assertFalse(SourceParse.placeholder(ls("I'm so crazy for you", "Wait, I'm here", "where am I?")))
        // a song that sings the words, among enough other lines
        assertFalse(SourceParse.placeholder(ls("纯音乐在耳边", "我们一起唱", "风吹过来", "天亮了", "再见了")))
        assertFalse(SourceParse.placeholder(ls("Instrumental break, then we sing it loud")))
        assertFalse(SourceParse.placeholder(emptyList()))
    }
}
