package io.github.bozkow.vibevisualizer.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.floor

/** The app's long-lived parts, made once in [io.github.bozkow.vibevisualizer.VibeApp]. */
@SuppressLint("StaticFieldLeak")
object Vibe {
    lateinit var app: Context
        private set
    lateinit var settings: Settings
        private set
    lateinit var cfg: Config
        private set
    /** Everything runs on the main thread, as in the web app; network and heavy work hop to Dispatchers.IO. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        settings = Settings(app)
        cfg = Config(app)
        Net.init(app)
        io.github.bozkow.vibevisualizer.media.Outputs.init(app)
    }
}

/** Shorthands for the settings, read the way the web app reads S.x */
object S {
    private val s get() = Vibe.settings
    operator fun get(k: String) = s[k]
    fun b(k: String) = s.b(k)
    fun i(k: String) = s.i(k)
    fun str(k: String) = s.s(k)
    fun set(k: String, v: Any) = s.set(k, v)

    val low get() = s.b("low")
    val view get() = s.s("view")
    val xFocus get() = s.b("xFocus")
    val lsimple get() = s.b("lsimple")
    val xRefSimple get() = s.b("xRefSimple")
    val lminimal get() = s.b("lminimal")
    val xKeep get() = s.b("xKeep")
    val xBgPop get() = s.b("xBgPop")
    val lduet get() = s.b("lduet")
    val lblur get() = s.b("lblur")
    val lsmooth get() = s.b("lsmooth")
    val learly get() = s.b("learly")
    val learlyMs get() = s.i("learlyMs")
    val loff get() = s.i("loff")
    val lsize get() = s.i("lsize")
    val romMode get() = s.s("romMode")
    val tr get() = s.b("tr")
    val trTo get() = s.s("trTo")
    val trSkip get() = s.s("trSkip")
    val bgStyle get() = s.s("bgStyle")
    val bgblur get() = s.i("bgblur")
    val bri get() = s.i("bri")
    val sat get() = s.i("sat")
    val bgspeed get() = s.i("bgspeed")
    val bars get() = s.i("bars")
    val design get() = s.s("design")
    /** Settings → Appearance → Design: One UI (the look of Samsung's own apps) instead of Android's. */
    val oneUi get() = s.s("aDesign") == "oneui"
    val credPos get() = s.s("credPos")
    val canvasOn get() = s.b("canvasOn")
}

fun now(): Double = SystemClock.elapsedRealtimeNanos() / 1e6   // like performance.now(), in ms
fun wall(): Long = System.currentTimeMillis()

/** 4:59, 1:02:03 */
fun clockLeft(sec: Double): String {
    val s = kotlin.math.max(0.0, kotlin.math.ceil(sec)).toLong()
    val h = s / 3600; val m = (s % 3600) / 60; val x = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$x" else "$m:$x"
}

fun fmtTime(s: Double): String =
    if (s.isFinite() && s >= 0) "${floor(s / 60).toInt()}:${floor(s % 60).toInt().toString().padStart(2, '0')}" else "0:00"

fun fmtWait(s: Double): String = when {
    s < 90 -> "${Math.round(s)} s"
    s < 5400 -> "${Math.round(s / 60)} min"
    else -> String.format("%.1f h", s / 3600)
}

fun ago(t: Long): String {
    val s = Math.round((wall() - t) / 1000.0)
    return when { s < 5 -> "just now"; s < 90 -> "$s s ago"; s < 5400 -> "${Math.round(s / 60.0)} min ago"; else -> "${Math.round(s / 3600.0)} h ago" }
}

fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v
fun clampf(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
