// Ported from Spicy Lyrics Mobile by TheX24 (github.com/spicylyrics/mobile, AGPL-3.0), unchanged but for the package.
package io.github.bozkow.vibevisualizer.lyrics

import kotlin.random.Random

/**
 * Makes a client quieter while a server refuses it. After [threshold] failures in a row the source
 * rests for the next rung of [ladderMs] (jittered 0.5x-1.5x, the last rung repeating), or for as
 * long as the server asked if that is longer. A success ends the run of failures; an hour without
 * a trip starts the ladder over.
 */
class BackoffLadder(
    private val ladderMs: List<Long> = DEFAULT_LADDER_MS,
    private val threshold: Int = 2,
    private val decayMs: Long = 3_600_000L,
    private val random: Random = Random.Default,
) {
    private var failures = 0
    private var rung = 0
    private var lastTripAt = Long.MIN_VALUE / 2
    private var openUntil = 0L

    /** When requests may go out again, or null while they may now. */
    @Synchronized
    fun openUntil(nowMs: Long): Long? = openUntil.takeIf { it > nowMs }

    @Synchronized
    fun success() {
        failures = 0
    }

    /**
     * Counts a refusal. [retryAfterMs] is the server's own deadline, if it sent one.
     * @return when requests may go out again, if this failure opened the breaker.
     */
    @Synchronized
    fun failure(nowMs: Long, retryAfterMs: Long? = null): Long? {
        failures++
        if (failures < threshold) return null
        failures = 0
        if (nowMs - lastTripAt > decayMs) rung = 0
        val pause = (ladderMs[rung.coerceAtMost(ladderMs.lastIndex)] * random.nextDouble(0.5, 1.5)).toLong()
        rung = (rung + 1).coerceAtMost(ladderMs.lastIndex)
        lastTripAt = nowMs
        openUntil = maxOf(nowMs + pause, retryAfterMs ?: 0L)
        return openUntil
    }

    companion object {
        /** A blip costs half a minute; a real outage settles at five minutes between tries. */
        val DEFAULT_LADDER_MS = listOf(
            30_000L, 30_000L, 30_000L,
            60_000L,
            120_000L, 120_000L, 120_000L, 120_000L, 120_000L, 120_000L, 120_000L, 120_000L,
            300_000L,
        )
    }
}
