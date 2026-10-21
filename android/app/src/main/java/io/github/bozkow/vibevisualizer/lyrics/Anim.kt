package io.github.bozkow.vibevisualizer.lyrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/* ==== Lyrics animation, ported from Spicy Lyrics ====
   Source: https://github.com/Spikerko/spicy-lyrics (src/utils/Lyrics/Animator/Lyrics/LyricsAnimator.ts,
   src/modules/Spring.ts, src/utils/Lyrics/Applyer/Utils/Emphasize.ts, src/css/Lyrics/Mixed.css)
   Copyright (c) Spikerko and the Spicy Lyrics contributors, licensed AGPL-3.0; ported to Kotlin for Vibe Visualizer,
   which is AGPL-3.0 as well (through its web app's JavaScript port). The Spring is itself a port of Fraktality's spr (MIT). */

private const val SL_SLEEP_OFFSET_SQ = (1.0 / 3840) * (1.0 / 3840)
private const val SL_SLEEP_VEL_SQ = 1e-2 * 1e-2
private const val SL_EPS = 1e-5

class Spring(start: Double, private val f0: Double, private val d: Double, goal: Double? = null) {
    var g = goal ?: start
    var p = start
    var v = 0.0

    fun step(dt: Double): Double {
        val f = f0 * (2 * PI); val g = g; var p = p; var v = v
        if (d == 1.0) {
            val q = exp(-f * dt); val w = dt * q; val c0 = q + w * f; val c2 = q - w * f; val c3 = w * f * f; val o = p - g
            p = o * c0 + v * w + g; v = v * c2 - o * c3
        } else if (d < 1) {
            val q = exp(-d * f * dt); val c = sqrt(1 - d * d); val i = cos(dt * f * c); val j = sin(dt * f * c)
            val z = if (c > SL_EPS) j / c else { val a = dt * f; a + ((a * a) * (c * c) * (c * c) / 20 - c * c) * (a * a * a) / 6 }
            val y = if (f * c > SL_EPS) j / (f * c) else { val b = f * c; dt + ((dt * dt) * (b * b) * (b * b) / 20 - b * b) * (dt * dt * dt) / 6 }
            val o = p - g
            p = (o * (i + z * d) + v * y) * q + g; v = (v * (i - z * d) - o * (z * f)) * q
        } else {
            val c = sqrt(d * d - 1); val r1 = -f * (d + c); val r2 = -f * (d - c); val ec1 = exp(r1 * dt); val ec2 = exp(r2 * dt); val o = p - g
            val co2 = (v - o * r1) / (2 * f * c); val co1 = ec1 * (o - co2)
            p = co1 + co2 * ec2 + g; v = co1 * r1 + co2 * ec2 * r2
        }
        this.p = p; this.v = v; return p
    }

    fun canSleep(): Boolean { if (v * v > SL_SLEEP_VEL_SQ) return false; val o = p - g; return o * o <= SL_SLEEP_OFFSET_SQ }
    fun goal(x: Double) { g = x }
}

/** Natural cubic spline through (time, value) points, as the cubic-spline package Spicy Lyrics uses. */
class Spline(vararg pts: Pair<Double, Double>) {
    private val n = pts.size; private val x = DoubleArray(n) { pts[it].first }; private val y = DoubleArray(n) { pts[it].second }
    private val b = DoubleArray(n); private val c = DoubleArray(n); private val dd = DoubleArray(n)
    init {
        val h = DoubleArray(n); val a = DoubleArray(n); val l = DoubleArray(n); val mu = DoubleArray(n); val z = DoubleArray(n)
        l[0] = 1.0
        for (i in 0 until n - 1) h[i] = x[i + 1] - x[i]
        for (i in 1 until n - 1) { a[i] = 3 / h[i] * (y[i + 1] - y[i]) - 3 / h[i - 1] * (y[i] - y[i - 1]); l[i] = 2 * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1]; mu[i] = h[i] / l[i]; z[i] = (a[i] - h[i - 1] * z[i - 1]) / l[i] }
        for (k in n - 2 downTo 0) { c[k] = z[k] - mu[k] * c[k + 1]; b[k] = (y[k + 1] - y[k]) / h[k] - h[k] * (c[k + 1] + 2 * c[k]) / 3; dd[k] = (c[k + 1] - c[k]) / (3 * h[k]) }
    }
    fun at(t: Double): Double { var i = 0; while (i < n - 2 && t > x[i + 1]) i++; val u = t - x[i]; return y[i] + b[i] * u + c[i] * u * u + dd[i] * u * u * u }
}

object SL {
    val ScaleSpline = Spline(0.0 to .95, .7 to 1.0505, 1.0 to 1.0)
    val LetterScaleSpline = Spline(0.0 to .95, .7 to 1.175, 1.0 to 1.0)
    val YOffsetSpline = Spline(0.0 to 1.0 / 100, .9 to -(1.0 / 60), 1.0 to 0.0)
    val LetterYOffsetSpline = Spline(0.0 to 1.0 / 100, .9 to -(1.0 / 56), 1.0 to 0.0)
    val GlowSpline = Spline(0.0 to 0.0, .15 to 1.0, .6 to 1.0, 1.0 to 0.0)
    val LineGlowSpline = Spline(0.0 to 0.0, .5 to 1.0, 1.0 to 0.0)
    const val YOffsetDamping = .4; const val YOffsetFrequency = 1.45; const val ScaleDamping = .64; const val ScaleFrequency = .88
    const val GlowDamping = .56; const val GlowFrequency = 1.18; const val LetterGlowMultiplier_Opacity = 185.0
    const val MinLetterCapableDuration = 1.0; const val EmphasisEndTrim = .25   // seconds: words held 1 s+ split into letters, which finish 250 ms early

    const val preHiddenDotLine = .5; const val InterludeTimePadding = -(preHiddenDotLine + .05)
    val DotScaleSpline = Spline(0.0 to .75, .7 to 1.05, 1.0 to 1.0)
    val DotYOffsetSpline = Spline(0.0 to 0.0, .9 to -.12, 1.0 to 0.0)
    val DotGlowSpline = Spline(0.0 to 0.0, .6 to 1.0, 1.0 to 1.0)
    val DotOpacitySpline = Spline(0.0 to .35, .6 to 1.0, 1.0 to 1.0)

    fun state(t: Double, a: Double, b: Double) = if (t < a) 0 else if (t >= b) 2 else 1    // NotSung, Active, Sung
    fun pct(t: Double, a: Double, b: Double) = if (t <= a) 0.0 else if (t >= b) 1.0 else (t - a) / (b - a)
    fun fillAt(pos: Double, w: TS) = if (pos < w.t) -20.0 else if (pos >= w.end) 100.0 else -20 + 120 * pct(pos, w.t, w.end)   // gradient position of a timed piece of text
    fun easeSinOut(t: Double) = sin(t * PI / 2)
}

/** The three springs of a word or letter, and what they last showed. */
class Springs(scale: Spline, y: Spline) {
    val scale = Spring(scale.at(0.0), SL.ScaleFrequency, SL.ScaleDamping)
    val y = Spring(y.at(0.0), SL.YOffsetFrequency, SL.YOffsetDamping)
    val glow = Spring(SL.GlowSpline.at(0.0), SL.GlowFrequency, SL.GlowDamping)
    fun asleep() = scale.canSleep() && y.canSleep() && glow.canSleep()
}

class DotSprings {
    val scale = Spring(SL.DotScaleSpline.at(0.0), .7, .6)
    val y = Spring(SL.DotYOffsetSpline.at(0.0), 1.25, .4)
    val glow = Spring(SL.DotGlowSpline.at(0.0), 1.0, .5)
    val opacity = Spring(SL.DotOpacitySpline.at(0.0), 1.0, .5)
    fun asleep() = scale.canSleep() && y.canSleep() && glow.canSleep() && opacity.canSleep()
}

/* Refreshed Simple Lyrics Mode (experiment), ported from Spicy Lyrics 6.4.50 (AGPL-3.0). The fill runs along the whole
   line as one soft edge; words float up as they're sung; the letters of a short held word swell, spread, lift and glow
   one after another, stronger the longer it's held. */
object RF {
    const val stagger = 1 / 2.5; const val scale = .1; const val spread = .03; const val lift = .025; const val maxAmt = 1.2
    const val radius = .3; const val maxR = .3; const val maxBlur = .8; const val float = .05; const val lead = .4; const val stretch = 1.4
    const val dim = .4; const val fade = .55

    fun cubicBez(x1: Double, y1: Double, x2: Double, y2: Double): (Double) -> Double {
        val cx = 3 * x1; val bx = 3 * (x2 - x1) - cx; val ax = 1 - cx - bx; val cy = 3 * y1; val by = 3 * (y2 - y1) - cy; val ay = 1 - cy - by
        fun X(t: Double) = ((ax * t + bx) * t + cx) * t
        fun Y(t: Double) = ((ay * t + by) * t + cy) * t
        fun dX(t: Double) = (3 * ax * t + 2 * bx) * t + cx
        return fun(x: Double): Double {
            if (x <= 0) return 0.0; if (x >= 1) return 1.0
            var t = x
            for (i in 0 until 8) { val e = X(t) - x; if (abs(e) < 1e-6) return Y(t); val d = dX(t); if (abs(d) < 1e-6) break; t = min(1.0, max(0.0, t - e / d)) }
            var lo = 0.0; var hi = 1.0; t = x
            while (hi - lo > 1e-6) { if (X(t) < x) lo = t else hi = t; t = (lo + hi) / 2 }
            return Y(t)
        }
    }
    private val rfIn = cubicBez(.2, .4, .58, 1.0); private val rfOut = cubicBez(.3, 0.0, .58, 1.0)
    fun emph(x: Double) = if (x <= 0 || x >= 1) 0.0 else if (x < .5) rfIn(x / .5) else 1 - rfOut((x - .5) / .5)

    class Strength(val a: Double, val b: Double, val du: Double)
    fun strength(du0: Double, last: Boolean): Strength {
        fun g(x: Double) = if (x > 1) sqrt(x) else x * x * x
        var du = max(1.0, du0); var a = g(du / 2) * .6; var b = g(du / 3) * .5
        if (last) { a *= 1.6; b *= 1.5; du *= 1.2 }
        return Strength(min(maxAmt, a), min(maxBlur, b), du)
    }
    fun float(t: Double, e: Double, pos: Double): Double { val x = min(1.0, max(0.0, (pos - t) / max(1.0, e - t))); return 1 - (1 - x) * (1 - x) }
    fun pos(edge: Double, x: Double, w: Double, fade: Double) = if (edge == Double.NEGATIVE_INFINITY) -50.0 else min(100.0, max(-50.0, 100 - 150 * ((w + fade / 2 - (edge - x)) / (w + fade))))
}

/** CSS timing functions. */
object Ease {
    val ease = RF.cubicBez(.25, .1, .25, 1.0)
    val easeOut = RF.cubicBez(0.0, 0.0, .58, 1.0)
    val eOut = RF.cubicBez(.2, .8, .2, 1.0)                // --e-out
    val opac = RF.cubicBez(.61, 1.0, .88, 1.0)             // the lines' opacity
    val fuMove = RF.cubicBez(.5, 0.0, .75, .4)             // finished lines flying up (Focus mode)
    val linear = { x: Double -> x }
    /** The dots' bouncy hide (CSS linear() curve from Spicy Lyrics). */
    private val DOT_PTS = doubleArrayOf(0.0, 0.0, .094, -.006, .18, -.029, .433, -.157, .514, -.185, .559, -.189, .60, -.182, .639, -.163, .676, -.133, .723, -.074, .767, .006, .85, .238, .927, .566, 1.0, 1.0)
    val dotHide = { x: Double ->
        var r = 1.0
        var i = 0
        while (i + 3 < DOT_PTS.size) {
            val x0 = DOT_PTS[i]; val y0 = DOT_PTS[i + 1]; val x1 = DOT_PTS[i + 2]; val y1 = DOT_PTS[i + 3]
            if (x <= x1) { r = y0 + (y1 - y0) * ((x - x0) / (x1 - x0)).coerceIn(0.0, 1.0); break }
            i += 2
        }
        r
    }
}

/** A value that glides to new targets like a CSS transition (from wherever it is now). */
class Tween(var v: Float) {
    private var from = v; private var to = v; private var t0 = 0.0; private var dur = 0.0; private var ease: (Double) -> Double = Ease.ease; private var delay = 0.0
    val target get() = to
    fun set(target: Float, now: Double, durMs: Double, e: (Double) -> Double = Ease.ease, delayMs: Double = 0.0) {
        if (target == to) return
        from = value(now); to = target; t0 = now; dur = durMs; ease = e; delay = delayMs
        if (durMs <= 0) { v = target; from = target }
    }
    fun snap(x: Float) { v = x; from = x; to = x; dur = 0.0 }
    fun value(now: Double): Float {
        if (dur <= 0 || v == to && from == to) { v = to; return v }
        val k = ((now - t0 - delay) / dur).coerceIn(0.0, 1.0)
        v = (from + (to - from) * ease(k)).toFloat()
        if (k >= 1) { from = to; dur = 0.0 }
        return v
    }
    fun moving(now: Double) = dur > 0 && now - t0 - delay < dur
}
