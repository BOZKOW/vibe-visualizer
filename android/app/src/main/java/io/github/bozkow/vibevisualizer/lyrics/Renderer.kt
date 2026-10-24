package io.github.bozkow.vibevisualizer.lyrics

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.now
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The lyrics on screen: laid out like the web app's DOM (lines, words, letters, background vocals, romanization and
 *  translation under them), with its classes (active, sung, distance, Focus mode) as states that glide like its CSS
 *  transitions, and the Spicy Lyrics word animation. Each line is drawn into its own GPU layer, so lines that don't
 *  change cost nothing but moving and blurring that layer. */
class LyricsRenderer {
    /* ================= model ================= */
    inner class Lt(val ch: String, val ts: TS) { var dx = 0f; var w = 0f; var sp: Springs? = null }
    inner class Pc(val text: String, val ts: TS) {
        var x = 0f; var y = 0f; var w = 0f; var adv = 0f
        var letters: List<Lt>? = null
        var sp: Springs? = null
        var grad = -20.0
        var gR = 0.0; var gO = 0.0                    // glow radius (px at 40 px font) and opacity (%)
        var scale = Double.NaN; var yo = 0.0          // NaN: the resting pose from the CSS
        var slm = -50.0                               // refreshed simple mode: the soft edge
    }
    inner class Row(val kind: Int, val fs: Float, val lh: Float, val mt: Float, val weight: Int) {
        var pieces: List<Pc>? = null
        var text: String? = null
        var oneTs: TS? = null
        var layout: StaticLayout? = null
        var paint: TextPaint? = null
        var y = 0f; var h = 0f
        var fit = 1f   // smaller than its size when one word is wider than the row
        var rowsW = FloatArray(0)
        val timed get() = pieces != null || oneTs != null
    }
    inner class LV(val i: Int, val l: Line) {
        val dots = l.dots
        val opp = l.opp
        val rows = ArrayList<Row>()
        var mainTs: List<TS>? = null
        var top = 0f; var h = 0f; var hText = 0f; var bgH = 0f
        var a = false; var p = false; var dp = false; var sa = false; var dist = 0
        var fv = false; var fn = false; var fs = false; var fu = false
        val opacity = Tween(.51f); val blur = Tween(0f); val fy = Tween(0f); val fx = Tween(0f)
        val bgOpen = Tween(1f); val dotScale = Tween(0f); val dotOp = Tween(0f)
        var activeAt = -1e9; var tapAt = -1e9
        var lineGlow: Spring? = null; var lineGrad = -20.0; var lineGlowV = 0.0
        val dotSp = if (l.dots) Array(3) { DotSprings() } else null
        val dotV = DoubleArray(12)
        var sweep: Sweep? = null
        var layer: GraphicsLayer? = null
        var dirty = true
        var recW = 0; var recH = 0
        var key = ""
        val rtl = RTL.containsMatchIn(l.text)
    }
    class Sweep(val xs: FloatArray, val ws: FloatArray, val lx: Array<FloatArray?>, val lw: Array<FloatArray?>, val fade: Float, val edges: DoubleArray)

    private var lines: List<Line> = emptyList()
    var sync = false; private set
    private var lv: List<LV> = emptyList()
    private var duet = false
    val tick = mutableLongStateOf(0L)
    var version by mutableIntStateOf(0); private set

    /* ================= metrics ================= */
    var W = 0f; private set
    var H = 0f; private set
    private var areaH = 0f
    private var screenH = 0f; private var screenW = 0f
    private var dens = 1f
    var F = 0f; private set
    private var padL = 0f; private var padR = 0f
    private var built = false; private var laidOut = false

    /* ================= state (the web app's lyricsTick / setActive) ================= */
    var li = -2; private set
    private var lsi = -2; private var lfi = -2; private var lfo = false; private var lfn = ""; private var lfs = ""
    private var lfnSet = emptySet<Int>(); private var lfsSet = emptySet<Int>()
    private var lAct = emptySet<Int>(); private var lActKey = ""; private var lOut = false
    var allsung by mutableStateOf(false); private set
    private val moving = HashSet<Int>()
    private var lastWT = 0.0

    // scrolling
    private val lyY = Tween(0f)
    var lyFree by mutableStateOf(false); private set
    private var lyFreeY = 0f; private var lyFreeAt = 0.0
    var dragged = false
    private var lyTall = false; private var lyTallAt = 0.0
    private val bandT = Tween(-1f); private val bandB = Tween(-1f)
    private var placeUntil = 0.0
    // untimed lyrics scroll slowly with the song
    private var stPos: Float? = null; private var stVel = 0f; private var stUser = 0.0; var stScroll = 0f; private set
    private var stLast = 0.0
    // new lyrics rise in; old ones fade out
    private var enterAt = -1e9; private var enterFrom = 0
    var leaving = false; private set
    private val leaveA = Tween(1f)
    var pressed = -1
    var creditsY by mutableStateOf(0f); private set    // where credits under the lyrics go (screen y inside the view)

    val isStatic get() = !sync && lines.isNotEmpty()
    val empty get() = lines.isEmpty()

    /* ================= paints ================= */
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val tfCache = HashMap<String, Typeface>()
    private fun face(weight: Int): Typeface {
        val f = S.str("lfont"); val k = "$f$weight"
        return tfCache.getOrPut(k) {
            if (f == "system") Typeface.create(Typeface.DEFAULT, weight, false)
            else runCatching { Typeface.Builder(Vibe.app.assets, "fonts/inter.ttf").setFontVariationSettings(if (f == "spicy") "'wght' $weight, 'opsz' 32" else "'wght' $weight").build() }
                .getOrDefault(Typeface.create(Typeface.DEFAULT, weight, false))
        }
    }
    private fun paint(fs: Float, weight: Int): TextPaint { tp.typeface = face(weight); tp.textSize = fs; tp.shader = null; tp.clearShadowLayer(); return tp }

    /* ================= building (renderLyrics) ================= */

    fun set(ls: List<Line>, isSync: Boolean, quiet: Boolean) {
        release()
        lines = ls; sync = isSync
        duet = ls.any { it.opp }
        li = -2; lsi = -2; lfi = -2; lfo = false; lfn = ""; lfs = ""; lfnSet = emptySet(); lfsSet = emptySet(); lAct = emptySet(); lActKey = ""; lOut = false
        moving.clear(); lyTall = false; bandT.snap(-1f); bandB.snap(-1f); stPos = null
        if (!quiet) { stScroll = 0f; lyFree = false }
        lv = ls.mapIndexed { i, l -> LV(i, l) }
        built = false; laidOut = false
        leaving = false; leaveA.snap(1f)
        version++
        bump()
    }

    fun restyle() { lv.forEach { it.rows.clear() }; built = false; laidOut = false; tfCache.clear(); bump() }
    fun relayout() { laidOut = false; lv.forEach { it.dirty = true }; li = -2; lActKey = "x"; bump() }

    fun leave() { if (lines.isNotEmpty() && !S.low) { leaving = true; leaveA.set(0f, now(), 350.0, Ease.ease) } }

    fun enter() { if (!S.low) { enterAt = now() + 16; enterFrom = max(0, li) } }

    private fun release() { lv.forEach { it.layer?.let { l -> releaser?.invoke(l) }; it.layer = null } }
    var releaser: ((GraphicsLayer) -> Unit)? = null

    private fun build() {
        val romMode = S.romMode; val join = S.b("ljoin"); val br = S.b("lbgBr")
        for (d in lv) {
            d.rows.clear(); d.mainTs = null; d.sweep = null
            val l = d.l
            if (l.dots) continue
            val idx = d.i
            val main = Row(0, 1f, 1.18f, 0f, 700)
            val romOnly = romMode == "replace" && l.rom != null
            val ws = l.words
            if (ws != null && ws.isNotEmpty() && sync) {
                val nt = lines.getOrNull(idx + 1)?.t ?: Double.POSITIVE_INFINITY
                val ts = ws.mapIndexed { k, w -> TS(w.t, w.end ?: if (k + 1 < ws.size) ws[k + 1].t else min(w.t + 1.5, nt)) }
                val rw = if (romOnly) l.rom!!.split(Regex("\\s+")) else null
                when {
                    !romOnly -> { val (jw, jt) = if (join) joinWords(ws, ts) else ws to ts; main.pieces = jw.mapIndexed { k, w -> Pc(w.text + if (w.sp) " " else "", jt[k]) }; d.mainTs = jt }
                    l.romSyl && ws.any { it.rom != null } -> { val (jw, jt) = if (join) joinWords(ws, ts) else ws to ts; main.pieces = jw.mapIndexed { k, w -> Pc((w.rom ?: w.text) + if (w.sp) " " else "", jt[k]) }; d.mainTs = jt }   // per-syllable romanization
                    rw != null && rw.size == ws.size -> { main.pieces = rw.mapIndexed { k, x -> Pc(x + if (k < rw.size - 1) " " else "", ts[k]) }; d.mainTs = ts }   // romanized words line up 1:1
                    else -> { main.text = l.rom; main.oneTs = TS(ws[0].t, min(ws.last().t + .8, nt)); d.mainTs = listOf(main.oneTs!!) }   // fill the whole romanized line
                }
                main.pieces?.let { ps -> splitLetters(ps) }
            } else main.text = if (romOnly) l.rom else l.text
            d.rows.add(main)
            l.bg?.forEach { b ->   // background vocals: their own smaller line, sung word by word on their own timing
                val bwds = b.words
                val romW = romMode == "replace" && l.rom != null && bwds?.any { it.rom != null } == true
                val romB = romMode == "replace" && l.rom != null && b.rom != null && !romW
                val r = Row(1, .75f, 1.18f, .1f, 600)
                if (sync && bwds != null && bwds.isNotEmpty() && !romB) {
                    val bts = bwds.mapIndexed { k, w -> TS(w.t, w.end ?: if (k + 1 < bwds.size) bwds[k + 1].t else w.t + 1) }
                    val brk = br && !Regex("^\\s*[(\\[]").containsMatchIn(b.text); val n = bwds.size
                    r.pieces = bwds.mapIndexed { k, w ->
                        var t = if (romW && w.rom != null) w.rom!! else w.text
                        if (brk && k == 0) t = "($t"; if (brk && k == n - 1) t = t.trimEnd() + ")"
                        Pc(t + if (w.sp) " " else "", bts[k])
                    }
                    splitLetters(r.pieces!!)
                } else { val t = if (romB) b.rom!! else b.text; r.text = if (br && !Regex("^\\s*[(\\[]").containsMatchIn(t)) "($t)" else t }
                d.rows.add(r)
            }
            // romanization and translation under the line fill in time with it: word by word when the romanized words
            // line up with the sung ones, otherwise across the whole line
            val lEnd = d.mainTs?.lastOrNull()?.end ?: lines.getOrNull(idx + 1)?.t ?: ((l.t ?: 0.0) + 4)
            fun timed(kind: Int, text: String, words: List<Pair<String, Boolean>>?) {
                val r = Row(kind, .46f, 1.3f, .22f, if (kind == 3) 600 else 700)
                val lw = l.words
                if (!sync || l.t == null) r.text = text
                else if (words != null && lw != null && words.size == lw.size) {
                    r.pieces = words.mapIndexed { k, (x, sp) -> val w = lw[k]; Pc(x + if (sp) " " else "", TS(w.t, w.end ?: if (k + 1 < lw.size) lw[k + 1].t else min(w.t + 1.5, lEnd))) }
                } else { r.text = text; r.oneTs = TS(l.t!!, max(l.t!! + .3, lEnd)) }
                d.rows.add(r)
            }
            val rom = l.rom
            if (rom != null && romMode == "below") {
                val lw = l.words
                timed(2, rom, if (l.romSyl && lw != null) lw.map { (it.rom ?: it.text) to it.sp } else rom.split(Regex("\\s+")).filter { it.isNotEmpty() }.let { p -> p.mapIndexed { k, x -> x to (k < p.size - 1) } })
            }
            l.tr?.let { timed(3, it, null) }
        }
        built = true
    }

    /** Emphasize: a held word becomes a letter group; the letters share its time evenly and finish 250 ms early. */
    private fun splitLetters(ps: List<Pc>) {
        for (pc in ps) {
            val tm = pc.ts; val core = pc.text.trimEnd()
            val lets = graphemes(core)
            if (tm.end - tm.t < SL.MinLetterCapableDuration || lets.isEmpty() || RTL.containsMatchIn(core)) continue
            tm.end = max(tm.t + .05, tm.end - SL.EmphasisEndTrim)
            val per = (tm.end - tm.t) / lets.size
            pc.letters = lets.mapIndexed { i, ch -> Lt(ch, TS(tm.t + i * per, tm.t + (i + 1) * per)) }
        }
    }

    private fun graphemes(s: String): List<String> {
        val out = ArrayList<String>(); val it = java.text.BreakIterator.getCharacterInstance(); it.setText(s)
        var a = it.first(); var b = it.next()
        while (b != java.text.BreakIterator.DONE) { out.add(s.substring(a, b)); a = b; b = it.next() }
        return out
    }

    /* ================= layout ================= */

    fun size(w: Float, h: Float, area: Float, scrW: Float, scrH: Float, density: Float, font: Float, pl: Float, pr: Float) {
        if (w == W && h == H && font == F && area == areaH && pl == padL && pr == padR) return
        W = w; H = h; areaH = area; screenW = scrW; screenH = scrH; dens = density; F = font; padL = pl; padR = pr
        laidOut = false; lv.forEach { it.dirty = true }; shadowCache.clear()
        bump()
    }

    private fun linePad() = if (S.lsimple && S.xRefSimple && !S.low) .32f else .2f

    private fun layoutAll() {
        if (W <= 0 || F <= 0) return
        if (!built) build()
        val avail = W - padL - padR
        for (d in lv) {
            if (d.dots) { d.hText = F * 1.18f + 2 * F * .2f; continue }
            val maxW = avail - if (duet && S.lduet) avail * .14f else 0f
            var y = 0f; var bg = 0f
            for (r in d.rows) {
                val ps = r.pieces
                // a word wider than the row (a long held "Weeeeeng") makes its line smaller, instead of running off the screen
                r.fit = 1f
                if (ps != null) { val p0 = paint(F * r.fs, r.weight); val widest = ps.maxOfOrNull { p0.measureText(it.text.trimEnd()) } ?: 0f; if (widest > maxW) r.fit = max(.4f, maxW / widest * .98f) }
                val fs = F * r.fs * r.fit; val lh = fs * r.lh
                r.y = y + F * r.mt
                if (ps != null) {
                    val p = paint(fs, r.weight)
                    var x = 0f; var row = 0; val rowW = ArrayList<Float>(); var rowStart = 0
                    for ((k, pc) in ps.withIndex()) {
                        pc.adv = p.measureText(pc.text); pc.w = p.measureText(pc.text.trimEnd())
                        if (x > 0 && x + pc.w > maxW) { rowW.add(x); row++; x = 0f; rowStart = k }
                        pc.x = x; pc.y = row * lh; x += pc.adv
                        pc.letters?.let { lt -> var off = 0; for (c in lt) { c.dx = p.measureText(pc.text, 0, off); c.w = p.measureText(c.ch); off += c.ch.length } }
                    }
                    rowW.add(x)
                    r.rowsW = rowW.toFloatArray()
                    // right-aligned (the other singer) and right-to-left lines. A duet's lines wrap 14% short of the
                    // other side, as Spicy Lyrics pads them, but the other singer's still end at the right edge
                    for (pc in ps) {
                        val rw = r.rowsW[(pc.y / lh).roundToInt()]
                        if (d.rtl) pc.x = rw - pc.x - pc.adv + (pc.adv - pc.w)
                        if (d.opp) pc.x += avail - rw
                    }
                    r.h = (row + 1) * lh
                } else {
                    val tpp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = face(r.weight); textSize = fs }
                    val txt = r.text ?: ""
                    val sl = StaticLayout.Builder.obtain(txt, 0, txt.length, tpp, max(1, maxW.toInt()))
                        .setAlignment(if (d.opp) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false).setLineSpacing(0f, 1f).build()
                    // set line height to the CSS one
                    val nat = if (sl.lineCount > 0) (sl.getLineBottom(0) - sl.getLineTop(0)).toFloat() else lh
                    val sl2 = if (abs(nat - lh) > .5f) StaticLayout.Builder.obtain(txt, 0, txt.length, tpp, max(1, maxW.toInt()))
                        .setAlignment(if (d.opp) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false).setLineSpacing(lh - nat, 1f).build() else sl
                    r.layout = sl2; r.paint = tpp
                    r.h = max(lh, sl2.lineCount * lh)
                }
                y = r.y + r.h
                if (r.kind == 1) bg += F * r.mt + r.h
            }
            d.hText = y + 2 * F * linePad(); d.bgH = bg
        }
        laidOut = true
        lv.forEach { it.dirty = true }
    }

    /** Line tops: inactive breaks take no room, tucked background vocals neither. */
    private fun tops(now: Double) {
        var y = 0f
        for (d in lv) {
            d.h = when {
                d.dots -> if (d.a && !S.xFocus) d.hText else 0f
                S.xBgPop && !isStatic && d.bgH > 0 -> d.hText - d.bgH * (1 - d.bgOpen.value(now))
                else -> d.hText
            }
            d.top = y; y += d.h
        }
        totalH = y
    }
    private var totalH = 0f

    /* ================= every frame ================= */

    fun frame(t: Double, pos: Double, viewOn: Boolean) {
        posNow = pos
        if (lines.isEmpty() || W <= 0) return
        if (!laidOut) layoutAll()
        val wasPlace = placeUntil > 0 && t < placeUntil
        if (wasPlace) place(t, pos)
        else if (lyTall && !lyFree && t - lyTallAt > 250) { lyTallAt = t; place(t, pos) }
        if (!viewOn) return
        if (!sync) { staticScroll(t, pos); tops(t); bump(); return }
        if (lyFree && t - lyFreeAt > 4000) freeEnd(t, pos)
        val i = findIdx(lines, pos); val e = if (i >= 0) lineEnd(lines[i]) else null; val out = e != null && pos >= e
        val nx = lines.getOrNull(i + 1)
        var si = if (S.learly && nx?.t != null && pos >= nx.t!! - S.learlyMs / 1000.0) i + 1 else i
        var fi = -2; var fo = false; var fn = ""; var fs = ""
        if (S.xFocus) {   // a line is due on screen 200 ms before it's sung, but the line before holds until it's sung to the end
            fun en(k: Int): Double { val l = lines[k]; if (l.dots) return l.t ?: 0.0; return lineEnd(l) ?: lines.getOrNull(k + 1)?.t ?: Double.POSITIVE_INFINITY }
            var f = findIdx(lines, pos + .2); while (f > 0 && en(f - 1) > pos) f--
            fi = f; fo = f >= 0 && pos >= en(f); si = max(f, 0)
            var k = max(f + 1, 0)
            val sbn = StringBuilder(); val sbs = StringBuilder()
            while (k < lines.size && lines[k].t != null && pos >= lines[k].t!! - .2) { if (!lines[k].dots) { sbn.append(k).append(','); if (pos >= lines[k].t!!) sbs.append(k).append(',') }; k++ }
            fn = sbn.toString(); fs = sbs.toString()
            if (fn != lfn) lfnSet = fn.split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toSet()
            if (fs != lfs) lfsSet = fs.split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toSet()
        }
        // every line still being sung is active, not just the newest: a duet line can start while another is going
        val ak = StringBuilder(); if (i >= 0 && !out) ak.append(i).append(',')
        var k = i - 1
        while (k >= 0 && k >= i - 6) { val l = lines[k]; if (!l.dots) { val ek = lineEnd(l); if (ek != null && pos >= l.t!! && pos < ek) ak.append(k).append(',') }; k-- }
        val aks = ak.toString()
        run { var n = lines.size - 1; while (n >= 0 && lines[n].dots) n--; val all = i > n || (i == n && (out || (e == null && pos >= (lines[n].t ?: 0.0) + 4))); if (all != allsung) allsung = all }
        if (i != li || out != lOut || fi != lfi || fo != lfo || fn != lfn || fs != lfs || aks != lActKey) {
            lOut = out; lsi = si; lfi = fi; lfo = fo; lfn = fn; lfs = fs
            lAct = aks.split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toSet(); lActKey = aks
            setActive(i, t, pos)
        } else if (si != lsi) { lsi = si; place(t, pos) }
        wordsTick(t, pos)
        tops(t)
        bump()
    }

    private fun setActive(i: Int, t: Double, pos: Double) {
        li = i
        if (S.xBgPop) placeUntil = t + 650   // background vocals sliding in or out change the line's height: follow it
        val minAct = lAct.minOrNull() ?: Int.MAX_VALUE
        val focus = S.xFocus
        for (d in lv) {
            val j = d.i
            val a = lAct.contains(j) || (j == i && !lOut)
            val dp = j == i && lOut && !lAct.contains(j)
            val p = !a && (j < i || dp)
            val sa = j < i && lAct.isNotEmpty() && j > minAct
            val dist = abs(j - i)
            val fv = lfi != -2 && j == lfi && !lfo; val fn = lfi != -2 && lfnSet.contains(j); val fs = fn && lfsSet.contains(j)
            val fu = lfi != -2 && (j < lfi || (j == lfi && lfo))
            val key = "$a$p$dp$sa${if (a) 0 else min(dist, 6)}$fv$fn$fs$fu"
            if (a && !d.a) { d.activeAt = t; moving.add(j) }
            d.a = a; d.p = p; d.dp = dp; d.sa = sa; d.dist = dist; d.fv = fv; d.fn = fn; d.fs = fs; d.fu = fu
            if (key != d.key) { d.key = key; d.dirty = true; targets(d, t, focus) }
        }
        place(t, pos)
    }

    /** What each line's classes ask for (opacity, blur, Focus mode's flight), set as transitions. */
    private fun targets(d: LV, t: Double, focus: Boolean) {
        val free = lyFree
        var op = when { d.a -> 1f; d.p -> .497f; else -> .51f }
        if (S.lminimal && d.p && !d.dp) op = if (S.xKeep && d.sa) .45f else 0f
        val lb = S.lblur && !S.low
        var bl = if (d.a || !lb) 0f else when { d.dp || d.dist <= 1 -> .075f; d.dist == 2 -> .1125f; d.dist == 3 -> .15f; d.dist == 4 -> .175f; else -> .2f }
        if (S.lminimal && S.xKeep && d.p && d.sa && !d.dp) bl = 0f
        var y = 0f; var x = 0f
        var dO = 200.0; var eO = Ease.opac; var dB = 350.0; var eB = Ease.easeOut; var dT = 800.0; var eT = Ease.eOut
        if (focus) {
            dO = 500.0; eO = Ease.ease; dB = 600.0; eB = Ease.ease
            if (d.fv) { op = 1f; bl = 0f }
            else if (d.fu) { op = 0f; bl = .35f; y = -.34f * screenH; dO = 450.0; dB = 450.0; dT = 600.0; eT = Ease.fuMove }
            else if (d.fn) { op = if (d.fs) 1f else .5f; bl = if (d.fs) 0f else .125f }
            else { op = 0f; bl = .35f; y = .6f * screenH; if (duet) x = if (d.opp) .06f * screenW else -.06f * screenW }
            if (d.dots) op = 0f
        }
        if (d.dots && !focus) op = if (d.a) 1f else 0f
        if (free) { bl = 0f; if (!d.a && !d.fv) op = .55f; y = 0f; x = 0f; dO = 300.0 }
        d.opacity.set(op, t, if (d.dots) 140.0 else dO, eO)
        d.blur.set(bl, t, dB, eB)
        d.fy.set(y, t, dT, eT); d.fx.set(x, t, dT, eT)
        if (S.xBgPop) d.bgOpen.set(if (d.a) 1f else 0f, t, 500.0, Ease.eOut) else d.bgOpen.snap(1f)
        if (d.dots) d.dotOp.set(if (d.a) 1f else 0f, t, 140.0, Ease.linear)
    }

    /* ---- where the lyrics sit (place / fitLine) ---- */
    private fun place(t: Double, pos: Double) {
        if (lv.isEmpty() || isStatic) return
        tops(t)
        val el = lv.getOrNull(max(if (lsi >= 0) lsi else li, 0)) ?: return
        val hh = if (S.xFocus && S.view == "both") areaH * .74f - 24 * dens else H   // Focus mode in Both: the same middle as before
        var y = hh * .38f - (el.top + el.h / 2)
        val top = hh * .16f; if (el.top + y < top) y = top - el.top   // a tall line moves down so its top stays out of the fade
        setLyY(fitLine(el, y, pos), t)
    }

    private fun sungRow(d: LV, pos: Double): Float {   // how far down the line (px) the words being sung are
        var best: Float? = null; var bt = Double.NEGATIVE_INFINITY
        for (r in d.rows) r.pieces?.forEach { pc -> if (pc.ts.t <= pos && pc.ts.t > bt) { bt = pc.ts.t; best = F * linePad() + r.y + pc.y + F * r.fs * r.fit * r.lh / 2 } }
        return best ?: 0f
    }

    private fun fitLine(el: LV, y: Float, pos: Double): Float {
        val h = el.h; val m = max(18 * dens, H * .05f); var t = el.top + y; lyTall = false
        if (S.xFocus || lyFree || H <= 0 || (t >= H * .14f && t + h <= H * .8f)) { band(-1f, -1f); return y }   // it fits: the usual band
        if (h <= H - 2 * m) t = max(m, min(t, H - m - h))   // the whole line in view, as high as it needs to go
        else { lyTall = true; t = max(H - m - h, min(m, H * .4f - sungRow(el, pos))); band(m, H - m); return t - el.top }   // taller than everything: the words being sung stay in view
        band(max(0f, min(H * .14f, t - 2)), min(H, max(H * .8f, t + h + 2)))
        return t - el.top
    }

    private fun band(a: Float, b: Float) { val n = now(); bandT.set(a, n, 550.0, Ease.eOut); bandB.set(b, n, 550.0, Ease.eOut) }

    private fun setLyY(y: Float, t: Double) { if (!lyFree) lyY.set(y, t, if (S.lsmooth) 550.0 else 0.0, Ease.eOut) }

    /* ---- scrolling through the lyrics by hand, as in Spicy Lyrics ---- */
    fun freeBy(dy: Float) {
        if (lines.isEmpty() || !sync) { if (isStatic) { stUser = now(); stPos = null; stScroll = (stScroll - dy).coerceIn(0f, max(0f, staticMax())); bump() }; return }
        val t = now()
        if (!lyFree) { lyFree = true; lyFreeY = lyY.value(t); lv.forEach { targets(it, t, S.xFocus) } }
        val hh = H; val maxY = hh * .45f; val minY = min(maxY, hh * .45f - totalH)
        lyFreeY = (lyFreeY + dy).coerceIn(minY, maxY)
        lyY.snap(lyFreeY)
        lyFreeAt = t
        bump()
    }

    fun freeEnd(t: Double = now(), pos: Double? = null) {
        if (!lyFree) return
        lyFree = false
        lv.forEach { targets(it, t, S.xFocus) }
        lyY.snap(lyFreeY)
        if (pos != null) place(t, pos) else lActKey = "x"   // re-placed on the next frame
        bump()
    }

    /** The line under a tap (y inside the view), or -1. */
    fun lineAt(y: Float): Int {
        if (isStatic) return -1
        val yy = y - lyY.v
        for (d in lv) if (!d.dots && d.h > 0 && yy >= d.top && yy < d.top + d.h && d.opacity.v > .05f) return d.i
        return -1
    }

    fun tapped(i: Int) { lv.getOrNull(i)?.let { it.tapAt = now(); it.dirty = true; bump() } }

    // untimed lyrics scroll slowly by themselves: the top as the song starts, the end as it ends
    private fun staticMax() = totalH + H * .49f - H
    private fun staticScroll(t: Double, pos: Double) {
        val dt = min(.1, (t - (if (stLast == 0.0) t else stLast)) / 1000); stLast = t
        if (t - stUser < 4000) return
        val d = durFn?.invoke() ?: 0.0; val mx = staticMax(); if (d <= 0 || mx <= 0) return
        val target = (pos / d).coerceIn(0.0, 1.0).toFloat() * mx
        var p = stPos ?: stScroll.also { stVel = 0f }
        val gap = target - p
        if (abs(gap) < 1.5f && abs(stVel) < 20) { p = target; stVel = 0f }
        else { val w = 2 * Math.PI.toFloat() * .9f; stVel += (w * w * gap - 2 * w * stVel) * dt.toFloat(); p += stVel * dt.toFloat() }
        stPos = p; stScroll = p
    }
    var durFn: (() -> Double)? = null

    /* ---- the words (wordsTick) ---- */
    private fun wordsTick(t: Double, pos: Double) {
        val dt = min(.1, max(0.0, (t - (if (lastWT == 0.0) t else lastWT)) / 1000)); lastWT = t
        if (li >= 0) moving.add(li)
        lAct.forEach { moving.add(it) }
        val simple = S.low || S.lsimple
        val refreshed = S.lsimple && S.xRefSimple && !S.low && !reduceMotion()
        val it = moving.iterator()
        while (it.hasNext()) {
            val k = it.next(); val d = lv.getOrNull(k)
            if (d == null) { it.remove(); continue }
            d.dirty = true
            if (d.dots) {
                if (k > li) { restDots(d); it.remove(); continue }
                if (!animateDots(d, pos, dt) && k != li) it.remove()
                continue
            }
            if (k > li) { restLine(d); it.remove(); continue }   // seeking back: lines ahead go back to rest
            var m = false
            val main = d.rows.firstOrNull()
            if (refreshed && main?.pieces != null) {
                m = refreshedLine(d, main, pos)   // its extra rows fill simply (drawn from the clock)
                d.rows.forEach { r -> r.oneTs?.let { o -> oneGrad[r] = SL.fillAt(pos, o) } }
            } else if (simple) {
                for (r in d.rows) r.pieces?.forEach { pc -> pc.sp = null; pc.scale = 1.0; pc.yo = 0.0; pc.gO = 0.0; pc.grad = SL.fillAt(pos, pc.ts); pc.letters?.forEach { lt -> lt.sp = null } }
                if (main != null && main.pieces == null && main.oneTs == null) animateLineGlow(d, pos, dt)
                d.rows.forEach { r -> r.oneTs?.let { o -> oneGrad[r] = SL.fillAt(pos, o) } }
            } else {
                for (r in d.rows) r.pieces?.forEach { pc -> if (animateWord(pc, pos, dt)) m = true }
                d.rows.forEach { r -> r.oneTs?.let { o -> oneGrad[r] = SL.fillAt(pos, o) } }
                if (main != null && main.pieces == null && main.oneTs == null) m = animateLineGlow(d, pos, dt) || m   // line-synced: the line itself fills
            }
            if (t - d.activeAt < 600) m = true
            if (!m && k != li && !lAct.contains(k)) it.remove()   // settled; the words keep their final pose, as in Spicy Lyrics
        }
    }
    private val oneGrad = HashMap<Row, Double>()
    var reduceMotion: () -> Boolean = { false }

    private fun restLine(d: LV) {
        d.sweep = null; d.lineGlow = null; d.lineGrad = -20.0; d.lineGlowV = 0.0
        for (r in d.rows) { oneGrad.remove(r); r.pieces?.forEach { pc -> pc.sp = null; pc.scale = Double.NaN; pc.yo = 0.0; pc.grad = -20.0; pc.gO = 0.0; pc.gR = 0.0; pc.slm = -50.0; pc.letters?.forEach { it.sp = null } } }
        d.dirty = true
    }
    private fun restDots(d: LV) { val sp = d.dotSp ?: return; for (k in sp.indices) sp[k] = DotSprings(); d.dotV.fill(0.0); d.dirty = true }

    fun restAll() { moving.forEach { lv.getOrNull(it)?.let(::restLine) }; moving.clear(); lastWT = 0.0; li = -2; lsi = -2; lActKey = "x"; bump() }

    // per piece of text: values for drawing (scale, y in em, gradient, glow); letters keep theirs in their springs
    private val letterVals = HashMap<Lt, DoubleArray>()

    private fun animateWord(pc: Pc, pos: Double, dt: Double): Boolean {
        val w = pc.ts
        val state = SL.state(pos, w.t, w.end); val pct = SL.pct(pos, w.t, w.end); val lg = pc.letters != null
        val sp = pc.sp ?: Springs(SL.ScaleSpline, SL.YOffsetSpline).also { pc.sp = it }
        val q = when (state) { 1 -> pct; 0 -> 0.0; else -> 1.0 }
        sp.scale.goal(SL.ScaleSpline.at(q)); sp.y.goal(SL.YOffsetSpline.at(q)); sp.glow.goal(SL.GlowSpline.at(q))
        val cS = sp.scale.step(dt); val cY = sp.y.step(dt); val cG = sp.glow.step(dt)
        pc.scale = cS; pc.yo = cY; pc.grad = when (state) { 1 -> -20 + 120 * pct; 0 -> -20.0; else -> 100.0 }
        if (!lg) { pc.gR = 4 + 2 * cG; pc.gO = min(cG * 35, 100.0) } else { pc.gR = 0.0; pc.gO = 0.0 }
        var mv = !sp.asleep()
        val L_ = pc.letters ?: return mv
        var ai = -1; var ap = 0.0
        if (state == 1) for (i in L_.indices) if (SL.state(pos, L_[i].ts.t, L_[i].ts.end) == 1) { ai = i; ap = SL.pct(pos, L_[i].ts.t, L_[i].ts.end); break }
        for ((k, lt) in L_.withIndex()) {
            val ls = lt.sp ?: Springs(SL.LetterScaleSpline, SL.LetterYOffsetSpline).also { lt.sp = it }
            var sc: Double; var y: Double; var g: Double; val grad: Double
            if (state == 1) {
                sc = SL.LetterScaleSpline.at(0.0); y = SL.LetterYOffsetSpline.at(0.0); g = SL.GlowSpline.at(0.0)
                val lst = SL.state(pos, lt.ts.t, lt.ts.end)
                if (ai != -1) {
                    val bS = SL.LetterScaleSpline.at(ap); val bY = SL.LetterYOffsetSpline.at(ap); val bG = SL.GlowSpline.at(ap)
                    val rS = SL.LetterScaleSpline.at(0.0); val rY = SL.LetterYOffsetSpline.at(0.0); val rG = SL.GlowSpline.at(0.0)
                    val dist = abs(k - ai).toDouble(); val fo = max(0.0, 1 / (1 + dist.pow(2.8))); val gfo = max(0.0, 1 / (1 + dist * .9))
                    sc = rS + (bS - rS) * fo; y = rY + (bY - rY) * fo; g = rG + (bG - rG) * gfo
                }
                if (lst == 0) { sc = SL.LetterScaleSpline.at(0.0); y = SL.LetterYOffsetSpline.at(0.0); g = SL.GlowSpline.at(0.0) }
                else if (lst == 2 && ai == -1) g = SL.GlowSpline.at(.2)
                grad = if (lst == 0) -20.0 else if (lst == 2) 100.0 else if (k == ai) -20 + 120 * SL.easeSinOut(ap) else -20.0
            } else if (state == 0) { sc = SL.LetterScaleSpline.at(0.0); y = SL.LetterYOffsetSpline.at(0.0); g = SL.GlowSpline.at(0.0); grad = -20.0 }
            else { sc = SL.LetterScaleSpline.at(1.0); y = SL.LetterYOffsetSpline.at(1.0); g = SL.GlowSpline.at(1.0); grad = 100.0 }
            ls.scale.goal(sc); ls.y.goal(y); ls.glow.goal(g)
            val a = ls.scale.step(dt); val b = ls.y.step(dt); val c = ls.glow.step(dt)
            val v = letterVals.getOrPut(lt) { DoubleArray(5) }
            v[0] = a; v[1] = b * 2; v[2] = grad; v[3] = 4 + 12 * c; v[4] = c * SL.LetterGlowMultiplier_Opacity
            if (!ls.asleep()) mv = true
        }
        return mv
    }

    private fun animateLineGlow(d: LV, pos: Double, dt: Double): Boolean {   // lines without word timing: top-to-bottom fill and a glow
        val l = d.l; val t0 = l.t ?: return false
        val end = l.end ?: lines.getOrNull(d.i + 1)?.t ?: (t0 + 4)
        val state = SL.state(pos, t0, end); val pct = SL.pct(pos, t0, end)
        val g0 = d.lineGlow ?: Spring(SL.LineGlowSpline.at(0.0), 1.0, .5).also { d.lineGlow = it }
        g0.goal(if (state == 1) SL.LineGlowSpline.at(pct) else SL.LineGlowSpline.at(if (state == 2) 1.0 else 0.0))
        val g = g0.step(dt)
        d.lineGrad = when (state) { 1 -> pct * 100; 0 -> -20.0; else -> 100.0 }
        d.lineGlowV = g
        return !g0.canSleep()
    }

    private fun animateDots(d: LV, pos: Double, dt: Double): Boolean {   // the three dots each take a third of the break, ending 550 ms before the next line
        val l = d.l; val t0 = l.t ?: 0.0; val end = l.end ?: (t0 + 4)
        val total = max(.1, end - t0); val base = total / 3; val pad = SL.InterludeTimePadding / 3
        val e1 = max(0.0, base + pad); val e2 = max(e1, base * 2 + pad * 2); val e3 = max(e2, total + SL.InterludeTimePadding)
        val span = arrayOf(0.0 to e1, e1 to e2, e2 to e3)
        val ph = pos > end - SL.preHiddenDotLine
        d.dotScale.set(if (ph || !d.a) 0f else 1f, now(), if (ph || !d.a) 400.0 else 300.0, if (ph || !d.a) Ease.dotHide else Ease.ease)
        var mv = d.dotScale.moving(now())
        val sps = d.dotSp ?: return false
        for (k in 0 until 3) {
            val a = t0 + span[k].first; val b = t0 + span[k].second; val st = SL.state(pos, a, b); val pc = SL.pct(pos, a, b)
            val q = if (st == 1) pc else if (st == 0) 0.0 else 1.0
            val sp = sps[k]
            sp.scale.goal(SL.DotScaleSpline.at(q)); sp.y.goal(SL.DotYOffsetSpline.at(q)); sp.glow.goal(SL.DotGlowSpline.at(q)); sp.opacity.goal(SL.DotOpacitySpline.at(q))
            d.dotV[k * 4] = sp.scale.step(dt); d.dotV[k * 4 + 1] = sp.y.step(dt) / 1.3; d.dotV[k * 4 + 2] = sp.glow.step(dt); d.dotV[k * 4 + 3] = sp.opacity.step(dt).coerceIn(0.0, 1.0)
            if (!sp.asleep()) mv = true
        }
        return mv
    }

    private fun refreshedLine(d: LV, main: Row, pos: Double): Boolean {
        val ps = main.pieces ?: return false
        val sw = d.sweep ?: run {
            val n = ps.size; val xs = FloatArray(n); val ws = FloatArray(n); val lx = arrayOfNulls<FloatArray>(n); val lw = arrayOfNulls<FloatArray>(n)
            var x = 0f
            for ((k, pc) in ps.withIndex()) { xs[k] = x; ws[k] = pc.w; pc.letters?.let { ls -> lx[k] = FloatArray(ls.size) { ls[it].dx }; lw[k] = FloatArray(ls.size) { ls[it].w } }; x += pc.w }
            Sweep(xs, ws, lx, lw, F * RF.fade.toFloat(), DoubleArray(n) { Double.NEGATIVE_INFINITY }).also { d.sweep = it }
        }
        // the edge crosses each word in that word's time and waits where the last one ended through a gap
        val E_ = sw.edges; val half = sw.fade / 2; val last = ps.size - 1; var reached = Double.NEGATIVE_INFINITY
        for (i in ps.indices) {
            val w = ps[i].ts; val from = if (i == 0) sw.xs[i] - half else sw.xs[i]; val to = sw.xs[i] + sw.ws[i] + if (i == last) half else 0f
            var at = Double.NEGATIVE_INFINITY
            if (pos >= w.end) at = to.toDouble() else if (pos > w.t) at = from + (to - from) * (pos - w.t) / (w.end - w.t)
            E_[i] = at; if (at > reached) reached = at
        }
        var before = Double.NEGATIVE_INFINITY
        for (i in ps.indices) { val w = ps[i].ts; val at = E_[i]; if (pos >= w.end) E_[i] = reached else if (pos <= w.t) E_[i] = before; if (at > before) before = at }
        var cap = 1.0; var mv = false
        for ((j, pc) in ps.withIndex()) {
            val w = pc.ts
            cap = min(cap, RF.float(w.t, w.end, pos)); pc.yo = SL.YOffsetSpline.at(cap); pc.scale = 1.0; pc.gO = 0.0
            if (pc.letters == null) { pc.slm = RF.pos(E_[j], sw.xs[j].toDouble(), sw.ws[j].toDouble(), sw.fade.toDouble()); continue }
            val L_ = pc.letters!!; val n = L_.size; val wave = n in 2..7   // longer held words fill like any other word
            val st = RF.strength(w.end - w.t, j == last); val start = L_[0].ts.t
            for ((k, lt) in L_.withIndex()) {
                val g = RF.pos(E_[j], (sw.xs[j] + sw.lx[j]!![k]).toDouble(), sw.lw[j]!![k].toDouble(), sw.fade.toDouble())
                val v = letterVals.getOrPut(lt) { DoubleArray(7) }.let { if (it.size < 7) DoubleArray(7).also { n2 -> letterVals[lt] = n2 } else it }
                v[5] = g
                if (!wave) { v[0] = 1.0; v[1] = 0.0; v[6] = 0.0; v[3] = 0.0; v[4] = 0.0; continue }
                val at = pos - start - (st.du * RF.stagger / n) * k; val swv = RF.emph(at / st.du); val fa = (at + RF.lead) / (st.du * RF.stretch)
                val fl = if (fa > 0 && fa < 1) sin(fa * Math.PI) * RF.float else 0.0
                val lx = -swv * RF.spread * st.a * (n / 2.0 - k); val ly = -swv * RF.lift * st.a - fl
                v[0] = 1 + swv * RF.scale * st.a; v[1] = ly; v[6] = lx
                v[3] = min(RF.maxR, st.b * RF.radius) * 40   // in px at a 40 px font, like the others
                val lit = min(1.0, max(0.0, (g + 50) / 150)); v[4] = swv * st.b * (RF.dim + (1 - RF.dim) * lit) * 100
                if (!(swv == 0.0 && fl == 0.0)) mv = true
            }
            if (cap < 1) mv = true
        }
        return mv
    }

    /* ================= drawing ================= */

    private val shadowCache = HashMap<Int, RenderEffect>()
    private fun effect(blurPx: Float, shadow: Boolean): RenderEffect? {
        val q = (blurPx * 2).roundToInt()
        if (q <= 0 && !shadow) return null
        val key = q * 2 + if (shadow) 1 else 0
        return shadowCache.getOrPut(key) {
            if (shadow) {
                // drop-shadow(0 6px 16px rgba(0,0,0,.35)): a soft dark halo under the line being sung
                val r = 16f / 40f * F * .5f; val m = ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, .35f, 0f))
                val sh = RenderEffect.createOffsetEffect(0f, 6f / 40f * F, RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m), RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL)))
                RenderEffect.createBlendModeEffect(sh, RenderEffect.createOffsetEffect(0f, 0f), BlendMode.SRC_OVER)
            } else RenderEffect.createBlurEffect(q / 2f, q / 2f, Shader.TileMode.DECAL)
        }
    }

    fun draw(ds: DrawScope, obtain: () -> GraphicsLayer) {
        if (lines.isEmpty() || W <= 0) return
        if (!laidOut) { layoutAll(); tops(now()) }
        val t = now()
        val la = leaveA.value(t)
        val pad = ceil(F * .7f).toInt()
        val baseY = if (isStatic) H * .09f - stScroll else lyY.value(t)
        val st = isStatic
        // credits under the lyrics: right after the last line
        val lastBottom = lv.lastOrNull()?.let { it.top + it.h } ?: 0f
        val cy = baseY + lastBottom + F * .5f; if (abs(cy - creditsY) > .5f) creditsY = cy
        for (d in lv) {
            val op0 = if (st) 1f else d.opacity.value(t)
            val enterK = if (enterAt > 0 && !st) { val delay = min(abs(d.i - enterFrom), 12) * 45.0; Ease.eOut(((t - enterAt - delay) / 700).coerceIn(0.0, 1.0)).toFloat() } else 1f
            val op = op0 * la * (if (t - enterAt < 1300) enterK else 1f)
            if (op <= .004f || d.h <= 0f && !d.dots) continue
            val fy = if (st) 0f else d.fy.value(t); val fx = if (st) 0f else d.fx.value(t)
            val y = baseY + d.top + fy + (if (enterK < 1f) 26 * dens * (1 - enterK) else 0f)
            val hh = max(d.h, d.hText)
            if (y + hh + pad < -H * .1f || y - pad > H * 1.1f) continue
            val avail = W - padL - padR
            val w = (avail + 2 * pad).toInt(); val h = (hh + 2 * pad).toInt().coerceAtLeast(1)
            val layer = d.layer ?: obtain().also { d.layer = it; d.dirty = true }
            if (d.dirty || d.recW != w || d.recH != h || d.tapAt > t - 300) {
                layer.record(ds, ds.layoutDirection, IntSize(w, h)) { drawIntoCanvas { c -> c.nativeCanvas.translate(pad.toFloat(), pad.toFloat()); drawLine(c.nativeCanvas, d, t) } }
                d.recW = w; d.recH = h; d.dirty = d.a || moving.contains(d.i) || (S.xBgPop && d.bgOpen.moving(t)) || (d.dots && d.dotScale.moving(t))
            }
            layer.topLeft = IntOffset((padL + fx - pad).roundToInt(), (y - pad).roundToInt())
            layer.alpha = op.coerceIn(0f, 1f)
            val blurPx = if (st || lyFree) 0f else d.blur.value(t) * F
            val tapK = ((t - d.tapAt) / 260).coerceIn(0.0, 1.0).toFloat()
            val sc = if (tapK < 1f) .97f + .03f * Ease.easeOut(tapK.toDouble()).toFloat() else 1f
            layer.scaleX = sc; layer.scaleY = sc
            layer.pivotOffset = androidx.compose.ui.geometry.Offset(if (d.opp) pad + avail else pad.toFloat(), h / 2f)
            layer.renderEffect = effect(blurPx, d.a && !S.low && !st)?.asComposeRenderEffect()
            ds.drawLayer(layer)
        }
        if (enterAt > 0 && t - enterAt > 1300) enterAt = -1e9
    }

    /** Masks for the lyrics area: the clear band (sharp between two fades), widened for a tall line. */
    fun bandTop(): Float { val v = bandT.value(now()); return if (v < 0) H * .14f else v }
    fun bandBottom(): Float { val v = bandB.value(now()); return if (v < 0) H * .8f else v }

    private fun white(a: Double) = ((a.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or 0xFFFFFF
    private fun white(a: Float) = white(a.toDouble())

    private fun drawLine(c: Canvas, d: LV, t: Double) {
        val padT = F * linePad()
        if (d.dots) { drawDots(c, d, padT); return }
        if (pressed == d.i && !d.a && S.b("lhover")) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white(.08) }
            c.drawRoundRect(-.3f * F, 0f, W - padL - padR + .3f * F, d.hText, .35f * F, .35f * F, p)
        }
        val st = isStatic
        val active = d.a && !st
        val refreshedOn = S.lsimple && S.xRefSimple && !S.low && !reduceMotion()
        val far = d.p && d.dist > 5
        val bgK = if (S.xBgPop && !st) d.bgOpen.value(t) else 1f
        var yShift = 0f
        for (r in d.rows) {
            val fs = F * r.fs * r.fit; val lh = fs * r.lh
            if (r.kind == 1 && bgK < 1f) {   // background vocals tucked away until the line is sung
                if (bgK <= .01f) { yShift -= F * r.mt + r.h; continue }
            }
            val ry = padT + r.y + yShift
            c.save()
            if (r.kind == 1 && bgK < 1f) {
                val k = bgK
                c.clipRect(-F, ry - F * .1f, W + F, ry + r.h * k + F * .2f)
                c.translate(0f, -.6f * fs * (1 - k)); c.scale(.8f + .2f * k, .8f + .2f * k, if (d.opp) W - padL - padR else 0f, ry)
                yShift -= (F * r.mt + r.h) * (1 - k)
            }
            // the subs of the line being sung slide up and fade in (.5 s; the translation a little later)
            var subA = 1f
            if (active && r.kind >= 2) {
                val k = (((t - d.activeAt) - if (r.kind == 3) 60 else 0) / 500).coerceIn(0.0, 1.0)
                val e = Ease.eOut(k).toFloat(); subA = e; c.translate(0f, 14 * dens * (1 - e))
            }
            val ps = r.pieces
            if (ps != null) {
                val p = paint(fs, r.weight)
                val (aFill, aEnd) = when (r.kind) { 0 -> .85f to .5f; 1 -> .6f to .3f; 2 -> 1f to .45f; else -> .8f to .35f }
                val restColor = when {
                    st -> 1f
                    r.kind == 2 -> if (active) 1f else .75f
                    r.kind == 3 -> .58f
                    d.p -> if (r.kind == 1) .6f else .85f
                    else -> if (r.kind == 1) .3f else .5f
                }
                // how this row moves: springs (Spicy Lyrics), refreshed simple mode, or a plain fill
                val mode = when { st -> 0; r.kind == 0 && refreshedOn -> 2; S.low || S.lsimple -> 1; else -> 3 }
                val restScale = if (mode != 3 || far) 1.0 else .95
                val fillOn = active
                val glowOk = fillOn && (mode == 3 || mode == 2)
                for (pc in ps) {
                    val base = ry + pc.y + (lh - (p.descent() - p.ascent())) / 2 - p.ascent()
                    val springy = mode == 3 && !pc.scale.isNaN()
                    val sc = if (springy) pc.scale else if (mode == 2) 1.0 else restScale
                    val yo = if (springy || mode == 2) pc.yo else if (mode == 3 && !far) (if (pc.letters != null) .02 else .01) else 0.0
                    val lt = pc.letters
                    if (lt == null || mode == 0) {
                        val grad = if (mode == 1) SL.fillAt(posNow, pc.ts) else pc.grad
                        drawPiece(c, p, pc.text, pc.x, base, pc.w, fs, sc.toFloat(), yo.toFloat(),
                            if (fillOn && mode != 2) grad else null, if (fillOn) aFill else restColor, if (fillOn) aEnd else restColor,
                            if (glowOk && mode == 3) pc.gR else 0.0, if (glowOk && mode == 3) pc.gO else 0.0, subA, if (fillOn && mode == 2) pc.slm else null)
                    } else {
                        // a held word: each letter paints itself (the word's own fill and glow are off)
                        c.save(); c.translate(0f, (yo * fs).toFloat()); c.scale(sc.toFloat(), sc.toFloat(), pc.x + pc.w / 2, base - fs * .35f)
                        for (x in lt) {
                            val v = letterVals[x]
                            val live = v != null && (mode == 2 || (mode == 3 && x.sp != null))
                            val lsc = if (live) v!![0] else if (mode == 3 && !far) .95 else 1.0
                            val ly = if (live) v!![1] else if (mode == 3 && !far) .02 else 0.0
                            val lxo = if (live && mode == 2 && v!!.size > 6) v[6] else 0.0
                            val grad = if (mode == 1) SL.fillAt(posNow, x.ts) else v?.get(2) ?: -20.0
                            drawPiece(c, p, x.ch, pc.x + x.dx + (lxo * fs).toFloat(), base, x.w, fs, lsc.toFloat(), ly.toFloat(),
                                if (fillOn && mode != 2) grad else null, if (fillOn) aFill else restColor, if (fillOn) aEnd else restColor,
                                if (glowOk && live) v!![3] else 0.0, if (glowOk && live) v!![4] else 0.0, subA,
                                if (fillOn && mode == 2 && v != null && v.size > 5) v[5] else null)
                        }
                        c.restore()
                    }
                }
            } else {
                val sl = r.layout; val tpp = r.paint
                if (sl != null && tpp != null) {
                    tpp.clearShadowLayer(); tpp.shader = null
                    when {
                        st -> { tpp.color = white(1f); tpp.setShadowLayer(.2f * fs, 0f, .05f * fs, 0x55000000) }
                        active && r.kind == 0 && r.oneTs == null -> {   // line-synced: top-to-bottom fill
                            val g = d.lineGrad; val y0 = -.35f * fs; val y1 = r.h + .35f * fs; val hh = y1 - y0
                            tpp.color = white(1f)
                            tpp.shader = LinearGradient(0f, y0 + (hh * g / 100).toFloat(), 0f, y0 + (hh * (g + 20) / 100).toFloat(), white(.85f), white(.35f), Shader.TileMode.CLAMP)
                            val gl = d.lineGlowV; if (gl > .01 && !S.low) tpp.setShadowLayer(((4 + 8 * gl) / 40 * F * .87).toFloat(), 0f, 0f, white(gl * .5))
                        }
                        active && r.oneTs != null -> {   // a whole romanized line, or a translation, filled in time
                            val g = oneGrad[r] ?: -20.0; val (a1, a2) = when (r.kind) { 0 -> .85f to .5f; 2 -> 1f to .45f; else -> .8f to .35f }
                            val w = sl.width.toFloat()
                            tpp.color = white(1f); tpp.shader = LinearGradient((w * g / 100).toFloat(), 0f, (w * (g + 20) / 100).toFloat(), 0f, white(a1 * subA), white(a2 * subA), Shader.TileMode.CLAMP)
                        }
                        else -> tpp.color = white(when (r.kind) {
                            0 -> if (active) .6f else if (d.p) .85f else .35f
                            1 -> if (active) .6f else if (d.p) .6f else .3f
                            2 -> if (active) 1f else .75f
                            else -> .58f
                        } * subA)
                    }
                    c.save(); c.translate(if (d.opp) W - padL - padR - sl.width else 0f, ry); sl.draw(c); c.restore()   // the other singer: at the right edge
                }
            }
            c.restore()
        }
    }
    var posNow = 0.0

    private fun drawPiece(c: Canvas, p: TextPaint, text: String, x: Float, base: Float, w: Float, fs: Float, scale: Float, yEm: Float,
                          grad: Double?, aFill: Float, aEnd: Float, glowR: Double, glowO: Double, alpha: Float, slm: Double?) {
        c.save()
        if (scale != 1f || yEm != 0f) { c.translate(0f, yEm * fs); c.scale(scale, scale, x + w / 2, base - fs * .35f) }
        p.shader = null; p.clearShadowLayer()
        when {
            slm != null -> {   // refreshed simple mode: one soft edge, the lit part on its left
                val fw = .55f * fs; val q = ((100 - slm) / 150).toFloat(); val ex = x + w + fw / 2 - (w + fw) * q
                p.color = white(1f); p.shader = LinearGradient(ex - fw / 2, 0f, ex + fw / 2, 0f, white(aFill * alpha), white(.4f * alpha), Shader.TileMode.CLAMP)
            }
            grad != null && aFill != aEnd -> {
                val x0 = x - .06f * fs; val gw = w + .12f * fs
                p.color = white(1f)
                p.shader = LinearGradient(x0 + (gw * grad / 100).toFloat(), 0f, x0 + (gw * (grad + 20) / 100).toFloat(), 0f, white(aFill * alpha), white(aEnd * alpha), Shader.TileMode.CLAMP)
            }
            else -> p.color = white(aFill * alpha)
        }
        if (glowO > .5) p.setShadowLayer(max(.5f, (glowR / 40 * F * .87).toFloat()), 0f, 0f, white(min(1.0, glowO / 100) * alpha))
        c.drawText(text, x, base, p)
        c.restore()
    }

    private fun drawDots(c: Canvas, d: LV, padT: Float) {
        val fs = F * 1.3f; val p = paint(fs, 700); val gap = .18f * F
        val dw = p.measureText("•"); val total = dw * 3 + gap * 2
        val avail = W - padL - padR
        val x0 = if (d.opp) avail - total else 0f
        val cy = padT + F * 1.18f / 2
        val gs = d.dotScale.value(now())
        if (gs <= .001f) return
        c.save(); c.scale(gs, gs, x0 + total / 2, cy)
        for (k in 0 until 3) {
            val sc = d.dotV[k * 4].takeIf { it > 0 } ?: .75; val y = d.dotV[k * 4 + 1]; val g = d.dotV[k * 4 + 2]; val o = d.dotV[k * 4 + 3].takeIf { it > 0 } ?: .35
            val x = x0 + k * (dw + gap)
            c.save(); c.translate(0f, (y * fs).toFloat()); c.scale(sc.toFloat(), sc.toFloat(), x + dw / 2, cy)
            p.color = white(.85 * o)
            if (g > .01) p.setShadowLayer(((4 + 6 * g) / 40 * F * .87).toFloat(), 0f, 0f, white(min(1.0, g * .9) * o)) else p.clearShadowLayer()
            c.drawText("•", x, cy - (p.ascent() + p.descent()) / 2, p)
            c.restore()
        }
        c.restore()
    }

    fun bump() { tick.longValue++ }
    fun lineCount() = lines.size
}
