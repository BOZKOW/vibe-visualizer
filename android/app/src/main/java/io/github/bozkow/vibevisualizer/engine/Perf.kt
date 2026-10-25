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
        if (t - battAt > 60000 && E.visible) { battAt = t; E.batteryCheck() }   // low battery: once a minute is plenty
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
     *  its forecast for the next 10 s) or hot shows the warning. While the battery is cooling it never shows. Cooled
     *  down, the next warm spell starts over. */
    private fun heat(t: Double) {
        val pm = Vibe.app.getSystemService(PowerManager::class.java) ?: return
        val st = runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        var level = when { st >= PowerManager.THERMAL_STATUS_SEVERE -> 2; st >= PowerManager.THERMAL_STATUS_MODERATE -> 1; else -> 0 }
        if (level == 0 && t - roomAt > 10000) {   // Android asks for at most one forecast every few seconds
            roomAt = t
            room = runCatching { pm.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        }
        if (level == 0 && !room.isNaN() && room >= .9f) level = 1   // 1.0: it would slow itself down
        // the battery's temperature over the last minute (one reading every 5 s), to tell rising from dropping
        val c = batteryC()
        if (c != null) { temps.addLast(c); while (temps.size > 13) temps.removeFirst() }
        val dropping = c != null && temps.size >= 7 && c <= temps.first() - .2   // cooled 0.2 °C or more within the minute
        // cooled down for real (not just between two forecasts): the next warm spell may ask again
        if (level == 0) { if (st <= PowerManager.THERMAL_STATUS_LIGHT && (room.isNaN() || room < .75f)) { spell = 0; notNowAt = 0.0; refC = null }; return }
        if (S.low || warn > 0 || dropping) return   // it's cooling: no warning
        // after "Not now", again while it's still warm: at each whole degree above the one it was at then (41.7 → at 42.0),
        // or every 10 minutes while it isn't dropping
        val again = notNowAt > 0 && ((c != null && refC != null && kotlin.math.floor(c) >= kotlin.math.floor(refC!!) + 1) || now() - notNowAt >= 10 * 60000)
        if (level <= spell && !again) return
        spell = maxOf(spell, level); notNowAt = 0.0
        asked = true; warn = level   // a warning over the whole screen
    }

    private var battAt = 0.0
    private val temps = ArrayDeque<Double>()

    /* ---- Power saving: Low performance mode follows it ---- */
    private val pp by lazy { Vibe.app.getSharedPreferences("perf", android.content.Context.MODE_PRIVATE) }

    /** The phone's Power saving came on or went off (or the app starts, and compares with how it was): on, Low
     *  performance mode turns on too; off, it turns off again if it came on that way. Only the change counts, so
     *  turning Low performance mode off by hand while Power saving is on keeps it off. */
    fun saverCheck(force: Boolean = false) {
        val on = runCatching { Vibe.app.getSystemService(PowerManager::class.java)?.isPowerSaveMode }.getOrNull() ?: return
        val was = pp.getBoolean("saver", false)
        pp.edit().putBoolean("saver", on).apply()
        if (!S.b("aSaverLow") || (on == was && !force)) return
        if (on && !S.low) {
            S.set("low", true); pp.edit().putBoolean("auto", true).apply()
            E.toast("Power saving is on, so Low performance mode is too", "ok")
        } else if (!on && pp.getBoolean("auto", false)) {
            pp.edit().putBoolean("auto", false).apply()
            if (S.low) { S.set("low", false); E.toast("Power saving is off, so Low performance mode is off again", "ok") }
        }
    }

    /** Low performance mode changed by hand: it isn't Power saving's to turn off any more. */
    fun lowByHand() { pp.edit().putBoolean("auto", false).apply() }
    private var notNowAt = 0.0          // when the heat warning was last answered "Not now" (0: not in this warm spell)
    private var refC: Double? = null    // the battery's temperature then

    /** The heat warning over the whole screen: 0 none, 1 getting warm, 2 hot. */
    var warn by mutableIntStateOf(0); private set

    /** The heat warning's answer: Low performance mode, or not now (asked again while it stays warm: each degree
     *  warmer, every 10 minutes while it isn't cooling, and at once if it gets hot). */
    fun warnDone(turnOn: Boolean) {
        if (turnOn) S.set("low", true) else { notNowAt = now(); refC = batteryC() }
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
