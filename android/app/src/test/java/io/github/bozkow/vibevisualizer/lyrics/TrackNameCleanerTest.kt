// Ported from Spicy Lyrics Mobile by TheX24 (github.com/spicylyrics/mobile, AGPL-3.0), unchanged but for the package.
package io.github.bozkow.vibevisualizer.lyrics

import io.github.bozkow.vibevisualizer.lyrics.TrackNameCleaner.Names
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackNameCleanerTest {
    @Test fun taggedTrackPassesThrough() {
        assertEquals(Names("7 rings", "Ariana Grande"), TrackNameCleaner.clean("7 rings", "Ariana Grande"))
        assertEquals(Names("Song - Remix", "A"), TrackNameCleaner.clean("Song - Remix (Official Video)", "A"))
        assertEquals(Names("Song (Remix)", "A"), TrackNameCleaner.clean("Song (Remix)", "A"))
    }

    @Test fun youTubeVideoBecomesTheSong() {
        assertEquals(Names("Never Gonna Give You Up", "Rick Astley"),
            TrackNameCleaner.clean("Rick Astley - Never Gonna Give You Up (Official Music Video)", "RickAstleyVEVO"))
        assertEquals(Names("Blinding Lights", "The Weeknd"), TrackNameCleaner.clean("Blinding Lights", "The Weeknd - Topic"))
        assertEquals(Names("Levitating", "Dua Lipa"), TrackNameCleaner.clean("Levitating [Official Visualiser]", "Dua Lipa"))
        // The part before the dash isn't the channel: it stays in the title.
        assertEquals(Names("Intro - Outro", "Band"), TrackNameCleaner.clean("Intro - Outro", "Band"))
    }

    @Test fun fileNameWithoutArtistIsSplit() {
        assertEquals(Names("INDUSTRY BABY", "Lil Nas X"),
            TrackNameCleaner.clean("03 - Lil Nas X - INDUSTRY BABY (Official Video).mp3", "<unknown>"))
        assertEquals(Names("Blinding Lights", "The Weeknd"), TrackNameCleaner.clean("The_Weeknd_-_Blinding_Lights.flac", ""))
        assertEquals(Names("Song (Remix)", "Artist"), TrackNameCleaner.clean("01 Artist - Song (Remix) [Lyrics].m4a", "Unknown artist"))
    }

    @Test fun fileNameWithKnownArtistKeepsArtist() {
        assertEquals(Names("Levitating", "Dua Lipa"), TrackNameCleaner.clean("05. Levitating.opus", "Dua Lipa"))
        assertEquals(Names("7 rings", "Ariana Grande"), TrackNameCleaner.clean("7 rings.mp3", "Ariana Grande"))
    }

    @Test fun titleWithoutSeparatorStaysArtistless() {
        assertEquals(Names("Some Song", ""), TrackNameCleaner.clean("Some Song.mp3", ""))
    }
}
