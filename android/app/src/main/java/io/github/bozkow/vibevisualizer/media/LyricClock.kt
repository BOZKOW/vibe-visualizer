// Ported from Spicy Lyrics Mobile by TheX24 (github.com/spicylyrics/mobile, AGPL-3.0), unchanged but for the package.
package io.github.bozkow.vibevisualizer.media

import kotlin.math.abs

/**
 * Where the song is, kept from the player's position reports. Android can't ask a player where
 * its audio is, only take its word.
 *
 * A player on this device stamps each report when it measures it, and its reports agree with each
 * other to a millisecond or two: anything [MIN_MOVE_MS] or more off is taken at once. Players send
 * a burst of reports on a change and then one every second or two, so waiting for a second
 * opinion leaves the lyrics off for that long.
 *
 * A relayed session ([Report.relayed], e.g. KDE Connect for a PC player) stamps a report when it
 * arrives, and now and then sends a stray one: its first after a resume or a seek runs up to
 * ~0.6 s ahead, often as a burst of copies. So its reports are votes, and the clock moves once two
 * votes at least [AGREE_AFTER_MS] apart agree. A jump of [JUMP_MS] or more is too big to be a
 * stray one (a seek or a new song) and is taken at once, and so is any report in a song's first
 * [SONG_START_MS], where players restart a song's start and the clock has nothing better to go on.
 *
 * Pause reports go through [ClockCorrection.reportBiasMs] first, which the vote can't replace:
 * some players count on from a stale pause position after resuming, so every report after it is
 * wrong by the same amount and would win any vote. Whether a player does is read off its own
 * resume: YouTube Music's first report after a resume is within ~40 ms of its pause report, while
 * Spotify and KDE Connect resume 0.07-3 s past theirs, and the bias is dropped.
 *
 * Spotify's first report after a resume lands past its pause report, by about as long as its
 * audio kept playing after it reported the pause (its fade-out, ~0.3 s). That's where the audio
 * picks up again, measured against the mixer's frame count, so it's taken as it comes.
 *
 * Plain Kotlin, no Android: the times are `elapsedRealtime`, passed in.
 */
internal class LyricClock(nowMs: Long) {
    data class Anchor(val positionMs: Long, val atMs: Long, val speed: Float, val isPlaying: Boolean)

    /**
     * A position report: the player's state, whether that state is playing, and its position when
     * last updated. [relayed]: the session mirrors a player on another device.
     */
    data class Report(
        val state: Int,
        val playing: Boolean,
        val positionMs: Long,
        val updatedAtMs: Long,
        val speed: Float,
        val relayed: Boolean = false,
    )

    /** What one report did, for the log. [driftMs]: how far it was from the clock. [appliedMs]: how far the clock moved. */
    data class Outcome(val driftMs: Long, val appliedMs: Long, val biasMs: Long)

    private class Vote(val atMs: Long, val positionMs: Long, val speed: Float) {
        fun positionAt(nowMs: Long) = positionMs + ((nowMs - atMs) * speed).toLong()
    }

    // Read off the main thread by the music haptics.
    @Volatile var anchor = Anchor(0L, nowMs, 0f, false)
        private set
    private var lastReport: Report? = null
    /** How far the player's reports run behind the audio since a stale pause report ([ClockCorrection.reportBiasMs]). */
    var biasMs = 0L
        private set
    /** The position of the pause report that set [biasMs]. */
    private var stalePauseAtMs = 0L
    private val votes = ArrayDeque<Vote>()

    val isPlaying get() = anchor.isPlaying

    fun positionAt(nowMs: Long): Long {
        val a = anchor
        val elapsed = if (a.isPlaying) (nowMs - a.atMs).coerceAtLeast(0L) else 0L
        return (a.positionMs + elapsed * a.speed).toLong().coerceAtLeast(0L)
    }

    /** Whether [report] says anything the last one didn't. */
    fun isFresh(report: Report) = report != lastReport

    /**
     * Takes a report. [snap] moves the clock to it outright (a new song's first report, or a resync
     * on a player that can't seek); otherwise a playing report is a vote.
     */
    fun onReport(report: Report, nowMs: Long, snap: Boolean = false): Outcome {
        val fresh = isFresh(report)
        val wasPlaying = lastReport?.playing == true
        lastReport = report
        val elapsed = if (report.playing) (nowMs - report.updatedAtMs).coerceAtLeast(0L) else 0L
        val raw = (report.positionMs + elapsed * report.speed).toLong().coerceAtLeast(0L)
        val predicted = positionAt(nowMs)
        // Buffering after a seek isn't a pause: its report is the seek target, not an old sample.
        val pauseReport = fresh && wasPlaying && report.state == STATE_PAUSED
        val resuming = report.playing && !anchor.isPlaying
        // A resume that doesn't carry on from the stale pause report isn't counting on from it.
        if (resuming && abs(report.positionMs - stalePauseAtMs) >= RESUME_CARRIES_ON_MS) biasMs = 0L
        biasMs = when {
            snap -> 0L
            else -> ClockCorrection.reportBiasMs(
                rawMs = raw,
                predictedMs = predicted,
                biasMs = biasMs,
                pauseReport = pauseReport,
            )
        }
        if (pauseReport) stalePauseAtMs = report.positionMs
        val reported = raw + biasMs
        val drift = reported - predicted
        val speedChanged = report.playing && anchor.isPlaying && report.speed != anchor.speed
        val adjustment = when {
            // A local pause report a little behind the clock arrives ~70 ms after the audio stopped
            // and names a spot ~30 ms short of it: where the clock stopped is nearer the audio.
            pauseReport && !report.relayed && !snap && drift in -PAUSE_LAG_MAX_MS until 0L -> {
                votes.clear()
                0L
            }
            snap || speedChanged || !report.playing -> {
                votes.clear()
                drift
            }
            !report.relayed -> {
                votes.clear()
                if (abs(drift) >= MIN_MOVE_MS) drift else 0L
            }
            // A relayed resume carries on from where the clock stopped, and its report is only a
            // vote: it is the one most often off.
            !anchor.isPlaying -> {
                votes.clear()
                vote(reported, drift, nowMs, report.speed)
            }
            else -> vote(reported, drift, nowMs, report.speed)
        }
        anchor = Anchor(
            positionMs = (predicted + adjustment).coerceAtLeast(0L),
            atMs = nowMs,
            speed = report.speed,
            isPlaying = report.playing,
        )
        return Outcome(drift, adjustment, biasMs)
    }

    /** How far to move the clock for a relayed playing report at [reportedMs], [driftMs] from the clock. */
    private fun vote(reportedMs: Long, driftMs: Long, nowMs: Long, speed: Float): Long {
        while (votes.isNotEmpty() && (nowMs - votes.first().atMs > VOTE_WINDOW_MS || votes.size >= MAX_VOTES)) {
            votes.removeFirst()
        }
        val vote = Vote(nowMs, reportedMs, speed)
        if (abs(driftMs) >= JUMP_MS || (reportedMs < SONG_START_MS && abs(driftMs) >= MIN_MOVE_MS)) {
            votes.clear()
            votes.addLast(vote)
            return driftMs
        }
        votes.addLast(vote)
        if (abs(driftMs) < MIN_MOVE_MS) return 0L
        val agreeing = votes.filter { abs(it.positionAt(nowMs) - reportedMs) < AGREE_MS }
        if (agreeing.none { nowMs - it.atMs >= AGREE_AFTER_MS }) return 0L
        val positions = agreeing.map { it.positionAt(nowMs) }.sorted()
        val adjustment = positions[positions.size / 2] - (reportedMs - driftMs)
        return if (abs(adjustment) >= MIN_MOVE_MS) adjustment else 0L
    }

    /** Our own seek: the clock goes to [targetMs] now, and what came before no longer counts. */
    fun seekTo(targetMs: Long, nowMs: Long) {
        anchor = anchor.copy(positionMs = targetMs, atMs = nowMs)
        biasMs = 0L
        votes.clear()
    }

    /** A new song before its first report: it starts from 0 until the player reports. */
    fun startTrack(nowMs: Long, speed: Float, playing: Boolean) {
        anchor = Anchor(0L, nowMs, speed, playing)
        biasMs = 0L
        votes.clear()
    }

    /** No player: back to the start, stopped. */
    fun reset(nowMs: Long) {
        anchor = Anchor(0L, nowMs, 0f, false)
        lastReport = null
        biasMs = 0L
        votes.clear()
    }

    companion object {
        /** Smaller differences aren't worth moving for: under a frame. */
        const val MIN_MOVE_MS = 15L
        /** Two votes this close are the same position. */
        const val AGREE_MS = 30L
        /** A report this far from the clock is a seek or a new song, not a stray report. */
        const val JUMP_MS = 1_000L
        /** A report this early in a song is the player starting it, and is taken as it comes. */
        const val SONG_START_MS = 3_000L
        /** Two votes this far apart that agree win: closer ones are one report sent several times. */
        const val AGREE_AFTER_MS = 300L
        /**
         * A resume report this close to the pause report carries on from it. In the logs YouTube
         * Music's stay within 36 ms of theirs, and Spotify's land 71 ms or more past theirs.
         */
        const val RESUME_CARRIES_ON_MS = 50L
        /** A local pause report at most this far behind the clock is late, not the song going back. */
        const val PAUSE_LAG_MAX_MS = 150L
        /** Votes older than this don't count: sparse reporters send one every couple of seconds. */
        const val VOTE_WINDOW_MS = 5_000L
        private const val MAX_VOTES = 64
        /** `PlaybackState.STATE_PAUSED`. */
        private const val STATE_PAUSED = 2
    }
}


/** Reading a player's pause reports, which the lyric clock ([LyricClock]) can't take at their word. */
internal object ClockCorrection {
    /** Smaller differences are report jitter, not worth a visible jump. */
    const val JITTER_MS = 150L
    /** A pause report at least this far behind the clock means the song really went back. */
    const val STALE_PAUSE_MAX_MS = 5_000L

    /**
     * Players report a pause badly. The position they give is an old sample: YouTube Music's is up
     * to ~1.3 s behind where the audio stopped, KDE Connect's up to ~4 s. Reports while playing are
     * sound. So a pause report behind a clock that was in step is not believed: the clock keeps its
     * place and the gap becomes the bias returned here.
     *
     * From then on each report is read either as is or [biasMs] later, whichever agrees with the
     * clock: YouTube Music keeps counting from its stale sample after resuming, while the audio
     * carries on from where it really was; KDE Connect is right again on resume; and either may
     * correct itself later. A report that fits neither is a seek, and drops the bias.
     *
     * [pauseReport]: the first report of a pause after playing. [rawMs]: the report's position
     * brought to now. [predictedMs]: where the clock is.
     */
    fun reportBiasMs(rawMs: Long, predictedMs: Long, biasMs: Long, pauseReport: Boolean): Long {
        if (pauseReport) {
            val behind = predictedMs - rawMs
            // Position 0 is the player going back to the start, not an old sample.
            return if (rawMs > 0L && behind >= JITTER_MS && behind < STALE_PAUSE_MAX_MS) behind else 0L
        }
        // Back at the start: a restart or a new song, which no old sample explains.
        if (biasMs == 0L || rawMs == 0L) return 0L
        val asIs = abs(rawMs - predictedMs)
        val late = abs(rawMs + biasMs - predictedMs)
        return if (late < asIs && late < STALE_PAUSE_MAX_MS) biasMs else 0L
    }
}
