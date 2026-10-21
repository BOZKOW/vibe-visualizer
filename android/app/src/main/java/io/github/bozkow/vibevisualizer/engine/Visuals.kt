package io.github.bozkow.vibevisualizer.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.now
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Colors from the cover, the bars' palette, and the background baked from the cover (blur done once here, the GPU
 *  only moves it), as in the web app. */
object Visuals {
    val DEF_COLS = listOf(intArrayOf(139, 124, 255), intArrayOf(255, 122, 200), intArrayOf(80, 200, 255))
    private val STOPS = mapOf(
        "aurora" to listOf("#19f5b0", "#5b7cff", "#c24bff"), "sunset" to listOf("#ff3d6e", "#ff8a3d", "#ffd23d"),
        "neon" to listOf("#ff2bd6", "#7a5cff", "#19e3ff"), "ice" to listOf("#7fd7ff", "#d4f1ff", "#6a8dff"), "mono" to listOf("#ffffff", "#8d93a0"))

    var rawCols: List<IntArray> = DEF_COLS; private set
    var barCols: List<IntArray> = DEF_COLS; private set
    var cover: Bitmap? = null; private set              // the cover as loaded (or null: the default colors)
    var coverVer by mutableIntStateOf(0); private set   // bumps on every new cover (the UI redraws it)
    var accent by mutableStateOf(Color(0xFF8B7CFF)); private set

    // the bars' colors, blending over 0.9 s from the old ones
    var pal: IntArray = IntArray(0); private set
    private var palRGB: Array<FloatArray> = emptyArray()
    private var palFrom: Array<FloatArray>? = null; private var palTo: Array<FloatArray>? = null; private var palT0 = 0.0
    var palDirty = true

    // the baked background: the cover blurred (128 px), and three soft layers cut from it that drift and spin
    var baked: Bitmap? = null; private set
    var bakedPrev: Bitmap? = null; private set
    var fadeAt = 0.0; private set                        // when the new background started fading in
    val sprites = arrayOfNulls<Bitmap>(3)
    var spritesPrev: Array<Bitmap?> = arrayOfNulls(3); private set
    var bakeVer by mutableIntStateOf(0); private set
    // the liquid background's source (the cover, very blurred; saturation and brightness are done by the shader)
    var kwSrc: Bitmap? = null; private set
    var kwPrev: Bitmap? = null; private set
    private var kwAt = 0.0
    fun kwFade(t: Double): Float = if (kwPrev == null) 1f else min(1.0, (t - kwAt) / 1000).toFloat()

    fun hex(h: String): IntArray = runCatching { intArrayOf(h.substring(1, 3).toInt(16), h.substring(3, 5).toInt(16), h.substring(5, 7).toInt(16)) }.getOrDefault(intArrayOf(255, 255, 255))

    fun rgb2hsl(r0: Double, g0: Double, b0: Double): DoubleArray {
        val r = r0 / 255; val g = g0 / 255; val b = b0 / 255
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2; val d = mx - mn
        var h = 0.0; var s = 0.0
        if (d > 0) { s = d / (1 - abs(2 * l - 1)); h = when (mx) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 }; h *= 60 }
        return doubleArrayOf(h, s, l)
    }

    fun hsl2rgb(h: Double, s: Double, l: Double): IntArray {
        fun k(n: Double) = (n + h / 30) % 12
        val a = s * min(l, 1 - l)
        fun f(n: Double) = l - a * max(-1.0, min(k(n) - 3, min(9 - k(n), 1.0)))
        return intArrayOf((f(0.0) * 255).roundToInt(), (f(8.0) * 255).roundToInt(), (f(4.0) * 255).roundToInt())
    }

    /** The cover's main colors, and lighter, more colorful ones for the bars (they sit on this same cover, blurred). */
    fun extract(img: Bitmap): Pair<List<IntArray>, List<IntArray>> {
        val small = Bitmap.createScaledBitmap(img, 48, 48, true)
        val px = IntArray(48 * 48); small.getPixels(px, 0, 48, 0, 0, 48, 48)
        class Bk(var n: Int = 0, var r: Double = 0.0, var g: Double = 0.0, var b: Double = 0.0)
        val m = HashMap<Int, Bk>()
        var lum = 0.0
        for (p in px) {
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            val k = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val e = m.getOrPut(k) { Bk() }; e.n++; e.r += r; e.g += g; e.b += b
            lum += (.2126 * r + .7152 * g + .0722 * b) / 255
        }
        lum /= px.size
        class Cand(val c: DoubleArray, val h: Double, val s: Double, val l: Double, val w: Double)
        val cand = m.values.map { e ->
            val r = e.r / e.n; val g = e.g / e.n; val b = e.b / e.n; val hsl = rgb2hsl(r, g, b)
            Cand(doubleArrayOf(r, g, b), hsl[0], hsl[1], hsl[2], e.n * (.2 + hsl[1]) * (if (hsl[2] > .1 && hsl[2] < .92) 1.0 else .15))
        }.sortedByDescending { it.w }
        val out = ArrayList<Cand>()
        for (k in cand) { if (out.all { o -> hypot(hypot(o.c[0] - k.c[0], o.c[1] - k.c[1]), o.c[2] - k.c[2]) > 70 }) out.add(k); if (out.size >= 4) break }
        if (out.size < 2) {
            val o = out.firstOrNull() ?: Cand(doubleArrayOf(120.0, 100.0, 220.0), 250.0, .5, .5, 0.0)
            val h = (o.h + 40) % 360; val l = min(.8, o.l + .15); val c = hsl2rgb(h, o.s, l)
            out.add(Cand(doubleArrayOf(c[0].toDouble(), c[1].toDouble(), c[2].toDouble()), h, o.s, l, 0.0))
        }
        val floorL = min(.86, max(.6, lum + .3))
        val raw = out.map { intArrayOf(it.c[0].roundToInt(), it.c[1].roundToInt(), it.c[2].roundToInt()) }
        val bar = out.map { if (it.s < .12) hsl2rgb(it.h, .18, max(.88, floorL)) else hsl2rgb(it.h, max(it.s, .62), min(.86, max(floorL, it.l))) }
        return raw to bar
    }

    fun setCover(img: Bitmap?) {
        cover = img
        if (img != null) runCatching { extract(img) }.onSuccess { (r, b) -> rawCols = r; barCols = b }.onFailure { rawCols = DEF_COLS; barCols = DEF_COLS }
        else { rawCols = DEF_COLS; barCols = DEF_COLS }
        palDirty = true
        coverVer++
        bake(true)
    }

    /* ---- palette + bands ---- */
    fun buildPal() {
        val n = Audio.N
        val st: List<IntArray> = when (S.str("theme")) { "cover" -> barCols; "custom" -> listOf(hex(S.str("c1")), hex(S.str("c2"))); else -> (STOPS[S.str("theme")] ?: STOPS.getValue("aurora")).map(::hex) }
        val to = Array(n) { i ->
            val t = if (n > 1) i.toDouble() / (n - 1) else 0.0; val f = t * (st.size - 1); val j = min(floor(f).toInt(), st.size - 2).coerceAtLeast(0); val u = f - j
            val a = st[j]; val b = st[min(j + 1, st.size - 1)]
            FloatArray(3) { x -> (a[x] + (b[x] - a[x]) * u).toFloat() }
        }
        if (palRGB.size == n && !S.low) { palFrom = palRGB; palTo = to; palT0 = now() } else { palRGB = to; palFrom = null; palTo = null }
        palShow(); palDirty = false
    }

    private fun palShow() {
        pal = IntArray(palRGB.size) { i -> val c = palRGB[i]; (0xFF shl 24) or (c[0].roundToInt().coerceIn(0, 255) shl 16) or (c[1].roundToInt().coerceIn(0, 255) shl 8) or c[2].roundToInt().coerceIn(0, 255) }
        val a = pal.getOrNull(min(1, pal.size - 1)); if (a != null) accent = Color(a)
    }

    /** Returns true while the colors are still blending. */
    fun palTick(t: Double): Boolean {
        val from = palFrom ?: return false; val to = palTo ?: return false
        val k = min(1.0, (t - palT0) / 900); val e = if (k < .5) 2 * k * k else 1 - (-2 * k + 2).pow(2) / 2
        palRGB = Array(to.size) { i -> val o = from.getOrNull(i) ?: to[i]; val c = to[i]; FloatArray(3) { x -> (o[x] + (c[x] - o[x]) * e).toFloat() } }
        palShow()
        if (k >= 1) { palFrom = null; palTo = null }
        return true
    }

    /* ---- background: blur baked here (128 px), the GPU only moves it ---- */
    private val LV = arrayOf(doubleArrayOf(1.0, .5, .5), doubleArrayOf(1 / 1.7, .2, .8), doubleArrayOf(1 / 2.1, .85, .15))

    /** paint(): the cover cropped square, or soft blobs of its colors */
    private fun paint(c: Canvas, s: Int) {
        c.drawColor(0xFF07070C.toInt())
        val img = cover
        if (img != null) {
            val m = min(img.width, img.height)
            c.drawBitmap(img, Rect((img.width - m) / 2, (img.height - m) / 2, (img.width + m) / 2, (img.height + m) / 2), Rect(0, 0, s, s), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val P = arrayOf(floatArrayOf(.25f, .3f), floatArrayOf(.75f, .7f), floatArrayOf(.7f, .2f), floatArrayOf(.2f, .8f))
            rawCols.forEachIndexed { i, k ->
                val p = P[i % 4]; val col = (k[0] shl 16) or (k[1] shl 8) or k[2]
                val pt = Paint().apply { shader = RadialGradient(p[0] * s, p[1] * s, s * .75f, intArrayOf((0xF2 shl 24) or col, col), null, Shader.TileMode.CLAMP) }
                c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), pt)
            }
        }
    }

    /** A box blur (three passes ≈ a gaussian) with a fractional radius, then saturation and brightness. */
    fun blurBitmap(bm: Bitmap, rf: Double, sat: Double, bri: Double) {
        val w = bm.width; val h = bm.height; val px = IntArray(w * h); bm.getPixels(px, 0, w, 0, 0, w, h)
        val r0 = floor(rf).toInt(); val fr = rf - r0
        var out = boxBlur(px, w, h, r0)
        if (fr > .01) { val b = boxBlur(px, w, h, r0 + 1); out = FloatArray(out.size) { i -> out[i] + (b[i] - out[i]) * fr.toFloat() } }
        for (p in 0 until w * h) {
            val r = out[p * 3]; val g = out[p * 3 + 1]; val b = out[p * 3 + 2]; val l = .299f * r + .587f * g + .114f * b
            fun ch(v: Float) = ((l + (v - l) * sat.toFloat()) * bri.toFloat()).roundToInt().coerceIn(0, 255)
            px[p] = (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        bm.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int): FloatArray {
        val a = FloatArray(w * h * 3) { i -> ((px[i / 3] shr (16 - 8 * (i % 3))) and 255).toFloat() }
        if (r < 1) return a
        val b = FloatArray(a.size); val dv = 2f * r + 1
        fun pass(s: FloatArray, t: FloatArray, len: Int, lines: Int, st: Int, ls: Int) {
            val mx = len - 1
            for (l in 0 until lines) {
                val o = l * ls
                for (c in 0 until 3) {
                    var sum = 0f
                    for (i in -r..r) sum += s[o + (if (i < 0) 0 else if (i > mx) mx else i) * st + c]
                    for (i in 0 until len) { t[o + i * st + c] = sum / dv; sum += s[o + min(mx, i + r + 1) * st + c] - s[o + max(0, i - r) * st + c] }
                }
            }
        }
        repeat(3) { pass(a, b, w, h, 3, w * 3); pass(b, a, h, w, w * 3, 3) }
        return a
    }

    /** renderBg(): the cover, blurred, and the three layers. fade: crossfade from the old one (a new song). */
    fun bake(fade: Boolean) {
        runCatching {
            val s = 128
            val pb = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            paint(Canvas(pb), s)
            blurBitmap(pb, S.bgblur * .06, S.sat / 100.0, S.bri / 100.0)
            if (fade && baked != null && !S.low) { bakedPrev = baked; spritesPrev = sprites.copyOf(); fadeAt = now() } else { bakedPrev = null; fadeAt = 0.0 }
            baked = pb
            val kw = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            paint(Canvas(kw), s)
            blurBitmap(kw, 2 + S.bgblur / 60.0 * 6, 1.0, 1.0)
            if (fade && kwSrc != null && !S.low) { kwPrev = kwSrc; kwAt = now() } else kwPrev = null
            kwSrc = kw
            val rp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            for (i in 0 until 3) {
                val cs = (s * LV[i][0]).toInt(); val ox = ((s - cs) * LV[i][1]).toInt(); val oy = ((s - cs) * LV[i][2]).toInt()
                val sp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); val c = Canvas(sp)
                c.drawBitmap(pb, Rect(ox, oy, ox + cs, oy + cs), Rect(0, 0, s, s), rp)
                if (i > 0) {   // soft round edges for the live layers
                    val m = Paint().apply { shader = RadialGradient(64f, 64f, 64f, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0), floatArrayOf(0f, 30f / 64f, 1f), Shader.TileMode.CLAMP); xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                    c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), m)
                }
                sprites[i] = sp
            }
            bakeVer++
        }.onFailure { android.util.Log.w("VV", "background: $it") }
    }

    fun fadeProgress(t: Double): Float = if (bakedPrev == null || fadeAt == 0.0) 1f else min(1.0, (t - fadeAt) / 900).toFloat().also { if (it >= 1f) { bakedPrev = null; spritesPrev = arrayOfNulls(3) } }

    /** For Settings → Background → Color: the cover's main color, darker. */
    fun plainColor(): Color { val c = rawCols.firstOrNull() ?: intArrayOf(40, 40, 60); return Color((c[0] * .55f) / 255f, (c[1] * .55f) / 255f, (c[2] * .55f) / 255f) }
}
