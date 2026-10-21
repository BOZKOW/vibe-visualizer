// Ported from Spicy Lyrics Mobile by TheX24 (github.com/spicylyrics/mobile, AGPL-3.0), unchanged but for the package.
package io.github.bozkow.vibevisualizer.lyrics

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackoffLadderTest {
    /** Jitter pinned to 1x. */
    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble(from: Double, until: Double) = 1.0
    }

    @Test fun opensAfterTwoFailuresInARowForThirtySeconds() {
        val ladder = BackoffLadder(random = noJitter)
        assertNull(ladder.failure(0L))
        assertEquals(30_000L, ladder.failure(0L))
        assertEquals(30_000L, ladder.openUntil(10_000L))
        assertNull(ladder.openUntil(30_000L))
    }

    @Test fun aSuccessEndsTheRun() {
        val ladder = BackoffLadder(random = noJitter)
        ladder.failure(0L)
        ladder.success()
        assertNull(ladder.failure(0L))
    }

    @Test fun climbsTheLadderAndStartsOverAfterAQuietHour() {
        val ladder = BackoffLadder(random = noJitter)
        val pauses = (0 until 5).map { trip -> val now = trip * 1_000_000L; ladder.failure(now); ladder.failure(now)!! - now }
        assertEquals(listOf(30_000L, 30_000L, 30_000L, 60_000L, 120_000L), pauses)
        ladder.failure(10_000_000L)
        assertEquals(30_000L, ladder.failure(10_000_000L)!! - 10_000_000L)
    }

    @Test fun aLongerRetryAfterWins() {
        val ladder = BackoffLadder(random = noJitter)
        ladder.failure(0L)
        assertEquals(90_000L, ladder.failure(0L, retryAfterMs = 90_000L))
    }
}
