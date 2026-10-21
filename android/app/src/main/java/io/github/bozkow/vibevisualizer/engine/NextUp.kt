package io.github.bozkow.vibevisualizer.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.core.wall
import io.github.bozkow.vibevisualizer.lyrics.SongMeta
import io.github.bozkow.vibevisualizer.spotify.Spotify
import kotlinx.coroutines.launch

/** Next up: in the last 30 seconds of a Spotify song, ask once what's next in the queue and show it. When that song
 *  starts, the card flows into the cover. */
object NextUp {
    class Item(val title: String, val artist: String, val img: String)
    var item by mutableStateOf<Item?>(null); private set
    var shown by mutableStateOf(false); private set
    var flyAt by mutableStateOf(0.0); private set        // when the card started flowing into the cover
    private var forId: String? = null

    fun tick() {
        val st = Spotify.spState
        val left = if (E.src == "spotify" && st != null && Spotify.spPlaying()) (Spotify.durationMs() - Spotify.curMs()) / 1000 else Double.POSITIVE_INFINITY
        val show = left <= 30 && left > -8 && Spotify.repeat != "track"   // stays past the end until the next song is in
        if (show && forId != Spotify.spId && wall() >= Spotify.backoff) {
            forId = Spotify.spId; item = null; val id = Spotify.spId
            Vibe.scope.launch { runCatching { Spotify.queueNext() }.getOrNull()?.let { (t, a, i) -> if (Spotify.spId == id) item = Item(t, a, i) } }
        }
        val on = show && item != null && forId == Spotify.spId
        if (on != shown) shown = on
    }

    fun fly(m: SongMeta) {
        val it = item
        if (shown && it != null && it.title == m.title && !S.low && !E.reduceMotion()) flyAt = now()
        shown = false
    }
}

/** Notices when the phone can't keep up while a song plays (or the battery is low) and offers Low performance mode. */
object Perf {
    private var prev = 0.0; private var t0 = 0.0; private var n = 0; private var slow = 0; private var secs = 0; private var bad = 0; private var sum = 0.0
    private var snooze = 0.0; private var asked = false

    fun tick(t: Double) {
        val gap = t - prev; prev = t
        if (S.low || !S.b("perfAsk") || asked || !E.visible || !E.playing() || t < snooze || gap > 1000) { t0 = t; n = 0; slow = 0; secs = 0; bad = 0; sum = 0.0; return }   // only judged while a song plays on screen
        n++; if (gap > 50) slow++   // a frame that took 3+ refreshes at 60 Hz
        if (t - t0 < 1000) return
        val fps = n * 1000 / (t - t0); t0 = t
        secs++; sum += fps; if (fps < 30 || slow > n * .25) bad++
        n = 0; slow = 0
        if (secs >= 12) { if (bad >= 9) suggest("slow", sum / secs); secs = 0; bad = 0; sum = 0.0 }   // most of the last 12 seconds
    }

    fun suggest(why: String, v: Double) {
        if (asked || S.low || !S.b("perfAsk") || now() < snooze) return
        asked = true
        val fx = "Low performance mode turns off the heavy effects (blur, glow, the moving background)"
        E.notify(E.Note(key = "perf", kind = "warn",
            title = when (why) { "battery" -> "Battery is low"; "saver" -> "Battery saver is on"; else -> "Vibe Visualizer is running slowly" },
            msg = when (why) {
                "battery" -> "Your battery is at ${Math.round(v * 100)}% and not charging. $fx, so it uses much less power."
                "saver" -> "Your phone is saving battery. $fx, so it uses much less power."
                else -> "It’s only managing about ${Math.round(v)} frames a second. $fx, so everything runs smoothly again."
            },
            actions = listOf(
                E.Act("Turn on Low performance mode", pri = true) { S.set("low", true); E.toast("Low performance mode is on. The ⚡ chip turns it off again.", "ok", 4000) },
                E.Act("Not now") { snooze = now() + 20 * 60000; asked = false },   // asks again in 20 minutes at the earliest
                E.Act("Don’t ask again") { S.set("perfAsk", false) })))
    }
}
