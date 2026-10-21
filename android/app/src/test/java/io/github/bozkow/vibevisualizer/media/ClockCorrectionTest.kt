// Ported from Spicy Lyrics Mobile by TheX24 (github.com/spicylyrics/mobile, AGPL-3.0), unchanged but for the package.
package io.github.bozkow.vibevisualizer.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockCorrectionTest {
    // The positions below are from device logs, checked against the audio mixer's own count.

    @Test fun youTubeMusicPauseAndResume() {
        // Paused at 12 892 while the audio had reached 13 649: the clock keeps its place.
        val bias = ClockCorrection.reportBiasMs(12_892L, 13_649L, 0L, pauseReport = true)
        assertEquals(757L, bias)
        // Resumed, it counts on from its stale sample: 12 942 is read as 13 699.
        assertEquals(757L, ClockCorrection.reportBiasMs(12_942L, 13_649L, bias, pauseReport = false))
        // Its next pause is a fresh sample, judged on its own: 15 588 against 15 861.
        assertEquals(273L, ClockCorrection.reportBiasMs(15_588L, 15_861L, bias, pauseReport = true))
    }

    @Test fun playerThatCorrectsItselfDropsTheBias() {
        // YouTube Music jumped 26 909 -> 27 896 mid-song, right onto the clock.
        assertEquals(0L, ClockCorrection.reportBiasMs(27_896L, 27_956L, 984L, pauseReport = false))
    }

    @Test fun kdeConnectStalePauseThenTrueResume() {
        // Playing at 43 314, then "paused" at 40 406 130 ms later.
        val bias = ClockCorrection.reportBiasMs(40_406L, 43_444L, 0L, pauseReport = true)
        assertEquals(3_038L, bias)
        // On resume it reports the true position.
        assertEquals(0L, ClockCorrection.reportBiasMs(43_681L, 43_444L, bias, pauseReport = false))
    }

    @Test fun pauseAheadOrFarBehindIsBelieved() {
        assertEquals(0L, ClockCorrection.reportBiasMs(15_588L, 15_088L, 0L, pauseReport = true))  // audio ran on
        assertEquals(0L, ClockCorrection.reportBiasMs(10_000L, 16_000L, 0L, pauseReport = true))  // seek back
        assertEquals(0L, ClockCorrection.reportBiasMs(10_000L, 10_100L, 0L, pauseReport = true))  // jitter
        assertEquals(0L, ClockCorrection.reportBiasMs(0L, 3_091L, 0L, pauseReport = true))  // back to the start
    }

    @Test fun seekOrNewSongDropsTheBias() {
        assertEquals(0L, ClockCorrection.reportBiasMs(90_000L, 30_000L, 800L, pauseReport = false))
        assertEquals(0L, ClockCorrection.reportBiasMs(40L, 208_150L, 1_323L, pauseReport = false))
        // YouTube Music paused 363 ms short at 2 791, then stopped back at 0: 0 is not 363.
        assertEquals(0L, ClockCorrection.reportBiasMs(0L, 3_154L, 363L, pauseReport = false))
    }
}
