package io.github.bozkow.vibevisualizer.lyrics

import kotlin.math.max

/** The time a piece of text is sung over, in seconds. */
class TS(var t: Double, var end: Double)

val UNSPACED = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsThai}\\p{IsLao}\\p{IsKhmer}\\p{IsMyanmar}]")
val RTL = Regex("[\\u0590-\\u08FF\\uFB1D-\\uFDFF\\uFE70-\\uFEFF]")
private val DASH_END = Regex("[-\\u2010-\\u2015]$")

/** Join Split Words, ported from Spicy Lyrics (src/utils/Lyrics/Applyer/Utils/JoinSplitWords.ts, AGPL-3.0): the
 *  parts of a word sung in syllables become one word, unless they don't line up cleanly (a gap over 100 ms, an uneven
 *  pace, a space inside, a trailing dash, a script written without spaces). */
fun joinWords(ws: List<Word>, ts: List<TS>): Pair<List<Word>, List<TS>> {
    val out = ArrayList<Word>(); val tout = ArrayList<TS>()
    var i = 0
    fun ends(w: Word) = w.sp || w.text.lastOrNull()?.isWhitespace() == true
    while (i < ws.size) {
        var e = i
        while (!ends(ws[e]) && e + 1 < ws.size) e++
        val grp = ws.subList(i, e + 1); val rt = ts.subList(i, e + 1)
        val ok = grp.size > 1 && grp.all { it.text.trim().isNotEmpty() && it.text.trim().none { c -> c.isWhitespace() } } &&
            grp.dropLast(1).none { DASH_END.containsMatchIn(it.text) } && grp.none { UNSPACED.containsMatchIn(it.text) } &&
            rt.withIndex().all { (k, x) -> k == 0 || x.t - rt[k - 1].end <= .1 } && evenPace(grp, rt)
        if (ok) {
            out.add(Word(rt[0].t, null, grp.joinToString("") { it.text }, grp.last().sp, if (grp.any { it.rom != null }) grp.joinToString("") { it.rom ?: it.text } else null))
            tout.add(TS(rt[0].t, rt.maxOf { it.end }))
        } else grp.forEachIndexed { k, w -> out.add(w); tout.add(rt[k]) }
        i = e + 1
    }
    return out to tout
}

private fun evenPace(grp: List<Word>, rt: List<TS>): Boolean {
    val du = rt.last().end - rt[0].t; val ch = grp.sumOf { it.text.length }
    if (du <= 0 || ch == 0) return false
    return rt.withIndex().all { (k, x) -> val tsr = (x.end - x.t) / du; val cs = grp[k].text.length.toDouble() / ch; tsr >= cs / 3 && tsr <= cs * 3 }
}

/** "• • •" lines for the intro and for long instrumental gaps; short or trailing ones are dropped. */
fun interludes(ls: List<Line>): List<Line> {
    val real = ls.filter { !it.dots }
    if (real.isEmpty()) return real
    val out = ArrayList<Line>()
    if ((real[0].t ?: 0.0) > 4) out.add(Line(0.0, null, "", dots = true))
    ls.forEachIndexed { i, l ->
        out.add(l)
        if (!l.dots && (l.words != null || (l.end != null && l.end != 0.0))) {
            val end = l.end ?: (l.words!!.last().t + 1.2)
            val nx = ls.subList(i + 1, ls.size).find { !it.dots }
            if (nx != null && (nx.t ?: 0.0) - end > 5) out.add(Line(end, null, "", dots = true))
        }
    }
    val res = ArrayList<Line>()
    out.forEachIndexed { i, l ->
        if (!l.dots) { res.add(l); return@forEachIndexed }
        val nx = out.subList(i + 1, out.size).find { !it.dots }
        if (nx == null || (nx.t ?: 0.0) - (l.t ?: 0.0) < 4 || (res.isNotEmpty() && res.last().dots)) return@forEachIndexed
        res.add(Line(l.t, nx.t, "", dots = true))
    }
    return res
}

/** The end of a line: the latest of its own end and its words' and background vocals' ends; null when the source
 *  doesn't say (LRC): the line stays lit. */
fun lineEnd(l: Line?): Double? {
    if (l == null || l.dots) return null
    var e = l.end
    fun up(ws: List<Word>?) { ws?.forEach { w -> val x = w.end ?: w.t; if (e == null || x > e!!) e = x } }
    up(l.words); l.bg?.forEach { up(it.words) }
    return e
}

fun findIdx(L: List<Line>, t: Double): Int {
    var a = 0; var b = L.size - 1; var r = -1
    while (a <= b) { val m = (a + b) ushr 1; if ((L[m].t ?: Double.MAX_VALUE) <= t) { r = m; a = m + 1 } else b = m - 1 }
    return r
}

/** Auto timing check: lyrics found by song name can belong to a slightly different version (radio edit, sped up,
 *  remaster) that runs at another pace. When the lyrics' own length is known, or their last line comes after the song
 *  has ended, the lyrics timeline is stretched to fit this song. Returns the pace (1 = as they are). */
fun checkPace(r: LyricsResult, dur: Double): Double {
    if (!r.sync || dur <= 0) return 1.0
    val src0 = r.credit?.source ?: ""
    if (src0 in setOf("spicy_lyrics", "apple_music", "spotify", "file", "amll")) return 1.0   // matched by track id or your own file: exact
    val last = r.lines.lastOrNull { !it.dots && it.t != null }
    var src = r.srcDur?.takeIf { kotlin.math.abs(it - dur) > 1.5 }
    if (src == null && last != null && last.t!! > dur - .5) src = last.t!! + max(2.0, dur * .03)   // a line after the end can't be right
    return if (src != null && kotlin.math.abs(src - dur) / dur < .12) src / dur else 1.0
}
