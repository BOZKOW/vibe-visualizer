package io.github.bozkow.vibevisualizer.lyrics

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real answers from every lyrics source, through the parsers the app uses. They aren't kept in the repository (Spicy
 *  Lyrics' terms forbid copies, and the others are someone's lyrics too), so this runs only when VV_LIVE_DIR names a
 *  folder of saved answers: spicy_*.json, amll.ttml, unison.json, lrcmux.json, netease.json, spotlyr.json, lrclib.json. */
class LiveSourcesTest {
    private val dir = System.getenv("VV_LIVE_DIR")?.let(::File)
    private fun file(n: String): File { assumeTrue(dir != null && File(dir, n).exists()); return File(dir, n) }
    private fun json(n: String) = JSONObject(file(n).readText())

    /** Synced lines in order, each word inside its song, times in seconds. */
    private fun checkTimed(ls: List<Line>, what: String) {
        val real = ls.filter { !it.dots }
        assertTrue("$what: lines", real.size >= 10)
        real.zipWithNext().forEach { (a, b) -> assertTrue("$what: in order at ${a.t}", (a.t ?: 0.0) <= (b.t ?: 0.0)) }
        real.forEach { l ->
            assertTrue("$what: seconds, not ms (${l.t})", (l.t ?: 0.0) < 1200)
            l.words?.forEach { w -> assertTrue("$what: word end after start in '${l.text}'", (w.end ?: w.t) >= w.t) }
        }
    }

    @Test fun spicyLyrics() {
        val d = dir; assumeTrue(d != null)
        val files = d!!.listFiles { f -> f.name.startsWith("spicy_") }.orEmpty()
        assumeTrue(files.isNotEmpty())
        for (f in files) {
            val j = JSONObject(f.readText()); val body = j.optJSONObject("Body") ?: j
            val r = parseSpicy(j, null)
            assertTrue("${f.name}: synced", r.sync)
            checkTimed(r.lines, f.name)
            val c = body.getJSONArray("Content")
            val wantBg = (0 until c.length()).count { c.getJSONObject(it).optJSONArray("Background")?.length() ?: 0 > 0 }
            val wantOpp = (0 until c.length()).count { c.getJSONObject(it).optBoolean("OppositeAligned") }
            assertEquals("${f.name}: background vocals", wantBg, r.lines.count { !it.bg.isNullOrEmpty() })
            assertEquals("${f.name}: duet lines", wantOpp, r.lines.count { it.opp })
            if (body.optString("Type") == "Syllable") assertTrue("${f.name}: word-synced", r.lines.all { it.words != null })
            // words joined into the line's text: a syllable inside a word has no space after it
            r.lines.filter { it.words != null }.forEach { l -> assertEquals(l.text, l.words!!.joinToString("") { it.text + if (it.sp) " " else "" }.trim()) }
            // the credit names where Spicy Lyrics got them: its community, or Apple Music for some songs
            assertTrue("${f.name}: credits (${r.credit?.source})", r.credit?.source in setOf("spicy_lyrics", "apple_music"))
            assertTrue("${f.name}: songwriters", r.credit!!.writers.isNotEmpty())
            // the intro and long gaps get Spicy Lyrics' dots
            val withDots = interludes(r.lines)
            if ((r.lines[0].t ?: 0.0) > 4) assertTrue("${f.name}: intro dots", withDots[0].dots)
        }
    }

    @Test fun amllTtml() {
        val p = fromText(file("amll.ttml").readText())
        assertTrue(p.sync); checkTimed(p.lines, "AMLL")
        assertTrue("AMLL: word-synced", p.lines.filter { !it.dots }.all { it.words != null })
        assertTrue("AMLL: background vocals", p.lines.any { !it.bg.isNullOrEmpty() })
    }

    @Test fun unison() {
        val r = SourceParse.unison(json("unison.json").getJSONObject("data"))
        assertNotNull(r); r!!
        assertTrue(r.sync); checkTimed(r.lines, "Unison")
        assertTrue("Unison: word-synced", r.lines.filter { !it.dots }.all { it.words != null })
        assertTrue("Unison: the second singer on the right", r.lines.any { it.opp })
        assertTrue("Unison: background vocals", r.lines.any { !it.bg.isNullOrEmpty() })
        assertNotNull("Unison: who synced it", r.credit?.uploader)
    }

    @Test fun lrcmux() {
        val r = SourceParse.lrcmux(json("lrcmux.json"), "Blinding Lights", "After Hours")
        assertNotNull(r); r!!
        checkTimed(r.lines, "LRCMux")
        val ws = r.lines.filter { it.words != null }
        assertTrue("LRCMux: word-synced", ws.size >= r.lines.count { !it.dots } * 0.8)
        ws.forEach { l -> assertEquals(l.text, l.words!!.joinToString("") { it.text + if (it.sp) " " else "" }.trim()) }
    }

    @Test fun otherVersions() {   // no saved answers needed
        assertTrue(Match.otherVersion("Dynamite (NightTime Version)", "Dynamite"))
        assertTrue(Match.otherVersion("Blinding Lights - Live", "Blinding Lights"))
        assertTrue(!Match.otherVersion("Song (2015 Remaster)", "Song"))
        assertTrue(!Match.otherVersion("Song (Live)", "Song (Live)"))
        assertTrue(Match.sameTitle("Blinding Lights", "Blinding Lights - Remastered 2011"))
        assertTrue(Match.sameTitle("Señorita", "Senorita (feat. X)"))
    }

    @Test fun netease() {
        val r = SourceParse.netease(json("netease.json"))
        assertNotNull(r); r!!
        checkTimed(r.lines, "NetEase")
        val real = r.lines.filter { !it.dots }
        assertTrue("NetEase: romanization lined up", real.count { it.apiRom != null } >= real.size / 2)
        assertTrue("NetEase: translation lined up", real.count { it.apiTr != null } >= real.size / 2)
        assertTrue("NetEase: no credit lines", real.none { it.text.startsWith("作词") || it.text.startsWith("作曲") })
    }

    @Test fun japaneseRomanization() {
        val r = SourceParse.netease(json("netease.json")) ?: return
        val ja = r.lines.filter { !it.dots && JA.containsMatchIn(it.text) }.take(12).map { Line(it.t, it.end, it.text) }
        assumeTrue(ja.isNotEmpty())
        runBlocking { Romanizer.romanizeLyrics(ja) }
        // no kana or kanji left (Japanese quotes 「」 stay, as punctuation)
        ja.forEach { l -> assertTrue("romanized: ${l.text} → ${l.lineRom}", l.lineRom != null && !JA.containsMatchIn(l.lineRom!!) && !HAN.containsMatchIn(l.lineRom!!)) }
        // a small っ at a word's end doubles the next consonant
        val dat = ja.firstOrNull { it.text.contains("だった") }
        if (dat != null) assertTrue("っ: ${dat.lineRom}", dat.lineRom!!.contains("tta"))
    }

    // the catalogues from Spicy Lyrics Mobile
    @Test fun kugouKrc() {
        val text = KrcCodec.decode(file("kugou_krc.txt").readText().trim())
        assertNotNull("KuGou: the KRC decodes", text)
        val r = SourceParse.catalogue(KrcCodec.lines(text!!), true, "KuGou", "kugou", "Blinding Lights", "The Weeknd")
        assertNotNull(r); r!!
        checkTimed(r.lines, "KuGou")
        assertTrue("KuGou: word-synced", r.lines.filter { !it.dots }.all { it.words != null })
        assertTrue("KuGou: no credit lines", r.lines.none { it.text.contains("Lyrics by", true) || it.text.contains("Produced by", true) })
        r.lines.filter { it.words != null }.forEach { l -> assertEquals(l.text, l.words!!.joinToString("") { it.text + if (it.sp) " " else "" }.trim()) }
    }

    @Test fun qqQrc() {
        val qrc = QrcCodec.decrypt(file("qq_lyric.txt").readText().trim())
        assertNotNull("QQ Music: the QRC decrypts", qrc)
        val ttml = QrcCodec.toTtml(qrc!!); assertNotNull("QQ Music: as TTML", ttml)
        val r = SourceParse.catalogue(fromText(ttml!!).lines, true, "QQ Music", "qq", "Blinding Lights", "The Weeknd")
        assertNotNull(r); r!!
        checkTimed(r.lines, "QQ Music")
        val plain = r.lines.filter { !it.dots && it.words == null }
        assertTrue("QQ Music: word-synced (not: " + plain.take(3).joinToString(" | ") { it.text } + ")", plain.isEmpty())
    }

    @Test fun kuwo() {
        val songs = KuwoLyrics.search(file("kuwo_search.json").readText())
        assertTrue("Kuwo: search hits", songs.isNotEmpty() && songs[0].rid.startsWith("MUSIC_"))
        val text = KuwoLyrics.body(file("kuwo_lyric.bin").readBytes()); assertNotNull("Kuwo: the lyrics inflate", text)
        val krc = KuwoLyrics.krc(text!!)
        val lines = if (krc != null) KrcCodec.lines(krc) else parseLRC(KuwoLyrics.lrc(text))
        val r = SourceParse.catalogue(lines, true, "Kuwo", "kuwo", "Blinding Lights", "The Weeknd")
        assertNotNull(r); checkTimed(r!!.lines, "Kuwo")
    }

    @Test fun lrcred() {
        val r = SourceParse.lrcred(json("lrcred_song.json"))
        assertNotNull(r); r!!
        assertTrue(r.sync); checkTimed(r.lines, "lrc.red")
        assertTrue("lrc.red: word-synced", r.lines.filter { !it.dots }.all { it.words != null })
        assertEquals("Apple Music", r.credit?.via)
    }

    @Test fun spotify() {
        val r = SourceParse.spotify(json("spotlyr.json"))
        assertNotNull(r); r!!
        assertTrue(r.sync); checkTimed(r.lines, "Spotify")
        assertNotNull("Spotify: who provided them", r.credit?.via)
    }

    @Test fun lrclib() {
        val r = SourceParse.lrclib(json("lrclib.json"))
        assertNotNull(r); r!!
        assertTrue(r.sync); checkTimed(r.lines, "LRCLIB")
    }
}
