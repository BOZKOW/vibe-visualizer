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

    /** Credit lines some catalogues put at the top of the lyrics ("Lyrics by：…", "作词：…"), and the song's own
     *  "Artist - Title" line: not part of what's sung. */
    private val CREDIT = Regex("^\\s*(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|弦乐|录音|监制|词|曲|lyrics|lyricist|composer|composed|producer|produced|arranger|arranged|written|writer|mixed|mastered|music|vocals?)(\\s*by)?\\s*[:：]", RegexOption.IGNORE_CASE)
    fun withoutCredits(ls: List<Line>, title: String, artist: String): List<Line> {
        val self = setOf(Match.key("$artist - $title"), Match.key("$title - $artist"), Match.key("${firstArtist(artist)} - $title"))
        return ls.filter { it.dots || (!CREDIT.containsMatchIn(it.text) && Match.key(it.text) !in self) }
    }

    /** "This song has no lyrics" notes some catalogues send instead of lyrics (纯音乐，请欣赏 "pure music, enjoy",
     *  此歌曲为没有填词的纯音乐 "no words were written", 暂无歌词 "no lyrics yet", "Instrumental"…): only when that's
     *  all there is (a few short lines), so a song that sings those words keeps its lyrics. */
    private val NO_LYRICS = Regex("纯音乐|純音樂|没有填词|沒有填詞|暂无歌词|暫無歌詞|暂时没有歌词|没有歌词|沒有歌詞|无歌词|無歌詞|歌词正在制作|歌词制作中|^instrumental$|^nolyrics?$|^nolyricsavailable$|^lyricsnotavailable$|^nolyricsfound$", RegexOption.IGNORE_CASE)
    fun placeholder(ls: List<Line>): Boolean {
        val real = ls.filter { !it.dots }.map { it.text.trim() }.filter { it.isNotEmpty() }
        if (real.isEmpty() || real.size > 4) return false
        val t = real.joinToString("").replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        return t.length <= 40 && NO_LYRICS.containsMatchIn(t)
    }

    /** A word-synced or line-synced catalogue answer as lyrics, without its credit lines. */
    fun catalogue(ls: List<Line>, sync: Boolean, name: String, source: String, title: String, artist: String): LyricsResult? {
        val clean = withoutCredits(ls, title, artist)
        if (clean.none { !it.dots }) return null
        return LyricsResult(clean, sync, name, Credit(source))
    }

    /** lrc.red's song.json: Apple Music's TTML (with lrc.red's names put back to Apple's), or its own model's sync. */
    fun lrcred(song: JSONObject): LyricsResult? {
        val ttml = song.optString("source").takeIf { it.isNotBlank() && !song.isNull("source") } ?: return null
        val apple = ttml.replace("xmlns:lrc=", "xmlns:itunes=").replace(Regex("(?<=\\s)lrc:(?=[A-Za-z]+=)"), "itunes:")
            .replace("<sourceMetadata", "<iTunesMetadata").replace("</sourceMetadata>", "</iTunesMetadata>")
        val t = fromText(apple); if (t.lines.none { !it.dots }) return null
        val byModel = song.optBoolean("byModel")
        return LyricsResult(t.lines, t.sync, "lrc.red", Credit("lrcred", via = if (byModel) "lrc.red’s AI sync" else "Apple Music", writers = t.writers))
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
