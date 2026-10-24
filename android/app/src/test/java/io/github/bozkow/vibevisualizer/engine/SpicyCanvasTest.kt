package io.github.bozkow.vibevisualizer.engine

import io.github.bozkow.vibevisualizer.engine.SpicyCanvas.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spicy Lyrics' Canvas service answers (shapes from Spicy Lyrics Mobile's SpotifyCanvasFinderTest). */
class SpicyCanvasTest {
    private val id = "4uLU6hMCjMI75M1A2tKUQC"
    private val other = "0VjIjW4GlUZAMYd2vXMi3b"
    private val mp4 = "https://canvaz.scdn.co/upload/artist/x/video/y.cnvs.mp4"

    @Test fun found() = assertEquals(Answer.Found(mp4), SpicyCanvas.parse("""{"object":"canvaslist.entries","data":{"$id":{"url":"$mp4","type":"VIDEO_LOOPING_RANDOM","uri":"spotify:canvas:1"}}}""", id))

    @Test fun none() = assertEquals(Answer.None, SpicyCanvas.parse("""{"data":{"$id":null,"$other":null}}""", id))

    @Test fun stillImageIsNone() = assertEquals(Answer.None, SpicyCanvas.parse("""{"data":{"$id":{"url":"https://i.scdn.co/image/x.jpg"}}}""", id))

    @Test fun failuresAreNotMisses() {
        assertNull(SpicyCanvas.parse("""{"data":{"$other":{"url":"$mp4"}}}""", id))
        assertNull(SpicyCanvas.parse("""{"$id":{"url":"$mp4"}}""", id))
        assertNull(SpicyCanvas.parse("""{"data":{"$id":{"error":{"status":502,"code":"upstream_failed"}}}}""", id))
        assertNull(SpicyCanvas.parse("not json", id))
    }

    @Test fun trackIds() {
        assertTrue(SpicyCanvas.isTrackId(id))
        assertFalse(SpicyCanvas.isTrackId("not-an-id"))
        assertFalse(SpicyCanvas.isTrackId(""))
    }
}
