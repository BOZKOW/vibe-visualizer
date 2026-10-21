package io.github.bozkow.vibevisualizer.lyrics

import org.json.JSONObject
import kotlin.math.abs

/** Each lyrics source's answer, turned into lyrics. Plain functions (no network, no Android), so the unit tests can
 *  feed them real answers. Asking the sources is engine/Lyr's job. */
object SourceParse {
    /** Spotify's own lyrics (your server's /api/spotify-lyrics): line-synced or not synced, with romanization. */
    fun spotify(j: JSONObject?): LyricsResult? {
        val ly = j?.optJSONObject("lyrics") ?: return null
        val arr = ly.optJSONArray("lines") ?: return null
        val sync = ly.optString("syncType") != "UNSYNCED"
        var ls = (0 until arr.length()).map { i -> val o = arr.getJSONObject(i); val t = (o.optString("startTimeMs").toDoubleOrNull() ?: 0.0) / 1000; val x = o.optString("words").trim()
            if (x.isEmpty() || x == "♪") Line(t, null, "", dots = true) else Line(t, null, x, apiRom = o.optString("transliteratedWords").trim().ifEmpty { null }) }
        if (!sync) ls = ls.filter { !it.dots }.map { Line(null, null, it.text, apiRom = it.apiRom) }
        if (ls.none { !it.dots }) return null
        return LyricsResult(ls, sync, "Spotify", Credit("spotify", via = ly.optString("providerDisplayName").ifEmpty { ly.optString("provider").ifEmpty { null } }))
    }

    /** One LRCLIB record: its synced lyrics, or the plain ones. */
    fun lrclib(d: JSONObject): LyricsResult? {
        val synced = if (d.isNull("syncedLyrics")) "" else d.optString("syncedLyrics")
        if (synced.isNotEmpty()) { val l = parseLRC(synced); if (l.isNotEmpty()) return LyricsResult(l, true, "LRCLIB", Credit("lrclib"), d.optDouble("duration", 0.0).takeIf { it > 0 }) }
        val plain = if (d.isNull("plainLyrics")) "" else d.optString("plainLyrics")
        if (plain.isNotEmpty()) { val r = fromText(plain); if (r.lines.isNotEmpty()) return LyricsResult(r.lines, false, "LRCLIB", Credit("lrclib")) }
        return null
    }

    private val NETEASE_CREDITS = Regex("^(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|词|曲|Lyrics|Composer|Producer|Arranger)\\s*[:：]", RegexOption.IGNORE_CASE)

    /** NetEase (your server's /api/netease): the lyrics, with its romanization and Chinese translation lined up. */
    fun netease(j: JSONObject?): LyricsResult? {
        fun clean(t: String?) = parseLRC(t ?: "").filter { it.dots || !NETEASE_CREDITS.containsMatchIn(it.text) }
        val ls = clean(j?.optString("lrc")); if (ls.none { !it.dots }) return null
        val rom = clean(j?.optString("romalrc")); val tr = clean(j?.optString("tlyric"))
        fun at(a: List<Line>, t: Double?) = a.find { !it.dots && t != null && abs((it.t ?: 0.0) - t) < .35 }?.text
        ls.forEach { l -> if (!l.dots) { l.apiRom = at(rom, l.t); at(tr, l.t)?.let { l.apiTr = it; l.apiTrLang = "zh" } } }
        return LyricsResult(ls, true, "NetEase", Credit("netease"))
    }

    /** One Unison record (its "data"): TTML, LRC or plain lyrics, credited to who synced them. */
    fun unison(d: JSONObject): LyricsResult? {
        val text = d.optString("lyrics").takeIf { it.isNotBlank() && !d.isNull("lyrics") } ?: return null
        val f = d.optString("format").lowercase()
        if (f.isNotEmpty() && f !in setOf("ttml", "lrc", "plain", "txt")) return null
        val t = fromText(text); if (t.lines.none { !it.dots }) return null
        val who = d.optJSONObject("submitter")?.let { s -> s.optString("displayName").takeIf { it.isNotBlank() }?.let { n ->
            val key = s.optString("keyId").takeIf { Regex("[0-9a-f]{16,128}").matches(it) }
            Person(n, key, key?.let { "https://unison.boidu.dev/curator/$it" }, s.optString("avatarUrl").takeIf { it.startsWith("https://") }) } }
        return LyricsResult(t.lines, t.sync, "Unison", Credit("unison", uploader = who, writers = t.writers))
    }

    /** An LRCMux answer: word-synced lines (or line-synced ones), unless it's another version of the song (it matches
     *  on title and length alone, so a remix as long as the song can answer for it). */
    fun lrcmux(j: JSONObject, title: String, album: String): LyricsResult? {
        val tr = j.optJSONObject("track")
        if (Match.otherVersion(listOfNotNull(tr?.optString("title"), tr?.optString("album")).joinToString(" "), "$title $album")) return null
        val arr = j.optJSONArray("lines") ?: return null
        val objs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        fun num(o: JSONObject, k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
        // milliseconds or seconds, decided once for the whole answer
        val scale = if ((objs.flatMap { listOfNotNull(num(it, "start"), num(it, "end")) }.maxOrNull() ?: 0.0) > 1000) 1000.0 else 1.0
        val ls = objs.mapNotNull { o ->
            val text = o.optString("text").trim()
            val start = num(o, "start")?.div(scale) ?: return@mapNotNull null
            val wa = o.optJSONArray("words")
            val words = wa?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { w ->
                val x = w.optString("text"); val ws = num(w, "start") ?: return@mapNotNull null
                if (x.isBlank()) null else Word(ws / scale, num(w, "end")?.div(scale), x.trim(), x.endsWith(" ")) } }?.takeIf { it.isNotEmpty() }
            words?.let { ws -> ws.forEachIndexed { i, w -> if (i < ws.lastIndex && !w.sp) w.sp = text.contains(w.text + " ") } }
            if (text.isEmpty() || text == "♪") Line(start, null, "", dots = true)
            else Line(start, words?.last()?.end ?: num(o, "end")?.div(scale), text, words)
        }
        if (ls.none { !it.dots }) return null
        val via = j.optJSONObject("meta")?.optJSONObject("source")?.optString("name")?.takeIf { it.isNotBlank() }
        return LyricsResult(ls, true, "LRCMux", Credit("lrcmux", via = via))
    }
}
