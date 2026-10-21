package io.github.bozkow.vibevisualizer.engine

import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.now

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
        val m = when (why) {
            "battery" -> "Battery at ${Math.round(v * 100)}% · Low performance mode uses less"
            "saver" -> "Battery saver is on · Low performance mode uses less"
            else -> "Running slowly (${Math.round(v)} fps) · Low performance mode helps"
        }
        // swiping it away asks again in 20 minutes at the earliest; Settings → Performance turns the question off
        E.snack(E.Snack(m, "warn", E.Act("Turn on") { S.set("low", true) }, long = true, onDismiss = { snooze = now() + 20 * 60000; asked = false }))
    }
}
