package io.github.bozkow.vibevisualizer.engine

import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.now

/** Notices when the phone can't keep up while a song plays, gets warm, or the battery is low, and offers Low
 *  performance mode. */
object Perf {
    private var prev = 0.0; private var t0 = 0.0; private var n = 0; private var slow = 0; private var secs = 0; private var bad = 0; private var sum = 0.0
    private var snooze = 0.0; private var asked = false
    private var heatAt = 0.0; private var roomAt = 0.0; private var room = Float.NaN; private var spell = 0   // spell: the warmest it's asked about since the phone last cooled down (0, 1 warm, 2 hot)

    fun tick(t: Double) {
        if (t - heatAt > 5000 && E.visible && !S.low && S.b("perfAsk")) { heatAt = t; heat(t) }
        val gap = t - prev; prev = t
        if (S.low || !S.b("perfAsk") || asked || !E.visible || !E.playing() || t < snooze || gap > 1000) { t0 = t; n = 0; slow = 0; secs = 0; bad = 0; sum = 0.0; return }   // only judged while a song plays on screen
        n++; if (gap > 50) slow++   // a frame that took 3+ refreshes at 60 Hz
        if (t - t0 < 1000) return
        val fps = n * 1000 / (t - t0); t0 = t
        secs++; sum += fps; if (fps < 30 || slow > n * .25) bad++
        n = 0; slow = 0
        if (secs >= 12) { if (bad >= 9) suggest("slow", sum / secs); secs = 0; bad = 0; sum = 0.0 }   // most of the last 12 seconds
    }

    /** How warm Android says the phone is (the same signal it slows itself down by): getting warm (or about to be, by
     *  its forecast for the next 10 s) asks once; hot asks again, even after a "not now". Cooled down, the next warm
     *  spell may ask again. */
    private fun heat(t: Double) {
        val pm = Vibe.app.getSystemService(PowerManager::class.java) ?: return
        val st = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        var level = when { st >= PowerManager.THERMAL_STATUS_SEVERE -> 2; st >= PowerManager.THERMAL_STATUS_MODERATE -> 1; else -> 0 }
        if (level == 0 && t - roomAt > 10000) {   // Android asks for at most one forecast every few seconds
            roomAt = t
            room = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        }
        if (level == 0 && !room.isNaN() && room >= .9f) level = 1   // 1.0: it would slow itself down
        // cooled down for real (not just between two forecasts): the next warm spell may ask again
        if (level == 0) { if (st <= PowerManager.THERMAL_STATUS_LIGHT && (room.isNaN() || room < .75f)) spell = 0; return }
        if (level <= spell || S.low) return
        if (level == 1 && (asked || now() < snooze)) return   // something's already asking, or "not now" was a moment ago
        spell = level
        asked = true; warn = level   // a warning over the whole screen
    }

    /** The heat warning over the whole screen: 0 none, 1 getting warm, 2 hot. */
    var warn by mutableIntStateOf(0); private set

    /** The heat warning's answer: Low performance mode, or not now (asked again when it gets hot, or in 20 minutes). */
    fun warnDone(turnOn: Boolean) {
        if (turnOn) S.set("low", true) else snooze = now() + 20 * 60000
        warn = 0; asked = false
    }

    /** The battery's temperature in °C (Android gives no other), or null. */
    fun batteryC(): Double? = runCatching {
        Vibe.app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it > -500 }?.div(10.0)
    }.getOrNull()

    fun suggest(why: String, v: Double, force: Boolean = false) {
        if (asked || S.low || !S.b("perfAsk") || (!force && now() < snooze)) return
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
