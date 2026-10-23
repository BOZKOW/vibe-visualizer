package io.github.bozkow.vibevisualizer.lyrics

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.InflaterInputStream
import kotlin.math.abs

/* The Chinese catalogues (KuGou, QQ Music, Kuwo): telling their search hits apart and reading their lyrics formats.
   Ported from Spicy Lyrics Mobile by TheX24 (github.com/spicylyrics/mobile, AGPL-3.0): CatalogueTitles, KrcCodec,
   QrcCodec and KuwoLyrics. Plain Kotlin, no network: engine/Lyr asks the catalogues. */

/** Whether a catalogue's search hit is the song asked for. */
object CatalogueTitles {
    /** Version markers: a hit claiming one we didn't ask for is another recording. */
    private val CUT = Regex("\\b(live|acoustic|cover|remix|instrumental|karaoke|nightcore|demo|tribute|rehearsal|session" +
        "|mashup|parody|sped[\\s-]?up|slowed|bootleg|re-?work|flip|vip)\\b", RegexOption.IGNORE_CASE)
    private val BRACKETED = Regex("\\s*[(（\\[【][^)）\\]】]*[)）\\]】]")
    private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")

    private fun cutWords(text: String): Set<String> =
        CUT.findAll(CAMEL.replace(text, " ")).map { it.groupValues[1].lowercase().filter(Char::isLetter) }.toSet()

    /** [theirs] names the song [ours] does: equal, or equal once a bracketed tag is taken off either one, unless the
     *  tag claims a version ours doesn't. */
    fun sameTitle(theirs: String, ours: String): Boolean {
        val a = Match.key(theirs); val b = Match.key(ours)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if ((cutWords(theirs) - cutWords(ours)).isNotEmpty()) return false
        return b == Match.key(BRACKETED.replace(theirs, "")) || a == Match.key(BRACKETED.replace(ours, "")) || Match.sameTitle(theirs, ours)
    }

    /** [theirs] bills the artist [ours] does, or, written in another script, the title is exact and the lengths within
     *  two seconds. */
    fun sameArtist(theirs: String, ours: String, theirTitle: String, ourTitle: String, theirSeconds: Int, ourSeconds: Int): Boolean {
        val o = Match.key(firstArtist(ours))
        if (o.isNotEmpty() && Match.key(theirs).contains(o)) return true
        val a = kinds(theirs); val b = kinds(ours)
        return a.isNotEmpty() && b.isNotEmpty() && a != b && Match.key(ourTitle).isNotEmpty() && Match.key(theirTitle) == Match.key(ourTitle) &&
            ourSeconds > 0 && theirSeconds > 0 && abs(theirSeconds - ourSeconds) <= 2
    }

    /** Lengths that can be the same recording (or one of them unknown). */
    fun sameLength(theirs: Int, ours: Int) = theirs <= 0 || ours <= 0 || abs(theirs - ours) <= 8

    /** The scripts a name is written in: cjk, hangul, latin. */
    fun kinds(text: String): Set<String> = buildSet {
        for (ch in text) when {
            ch in '぀'..'ヿ' || ch in '一'..'鿿' -> add("cjk")
            ch in '가'..'힯' -> add("hangul")
            ch.isLetter() && ch.code < 128 -> add("latin")
        }
    }
}

/** KuGou's KRC (and Kuwo's word timing, rewritten as KRC): `[start,length]<offset,length,0>word…` per line. */
object KrcCodec {
    private val key = byteArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69)
    private val line = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val word = Regex("<(\\d+),(\\d+)(?:,\\d+)?>([^<]*)")

    /** KuGou's download (base64 of "krc1" and the XOR-ed, deflated text) as KRC text. */
    fun decode(encoded: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(encoded); val encrypted = raw.copyOfRange(4, raw.size)
        val plain = ByteArray(encrypted.size) { i -> (encrypted[i].toInt() xor key[i % key.size].toInt()).toByte() }
        InflaterInputStream(ByteArrayInputStream(plain)).bufferedReader().readText()
    }.getOrNull()

    /** KRC as word-synced lines (times in seconds); a space after a word stays with it. */
    fun lines(text: String): List<Line> = text.lineSequence().mapNotNull { row ->
        val m = line.matchEntire(row.trim()) ?: return@mapNotNull null
        val start = m.groupValues[1].toLong(); val dur = m.groupValues[2].toLong()
        val ws = word.findAll(m.groupValues[3]).mapNotNull { w ->
            val x = w.groupValues[3]; if (x.isBlank()) return@mapNotNull null
            val off = w.groupValues[1].toLong(); val len = w.groupValues[2].toLong()
            Word((start + off) / 1000.0, (start + off + len) / 1000.0, x.trim(), x.endsWith(" ") || x.endsWith("\u3000"))
        }.toList()
        if (ws.isEmpty()) return@mapNotNull null
        val text = ws.joinToString("") { it.text + if (it.sp) " " else "" }.trim()
        Line(start / 1000.0, (start + dur) / 1000.0, text, ws)
    }.toList()
}

/** Tencent's QRC cipher is 3DES-ECB with two deliberately non-standard S-box entries. */
object QrcCodec {
    private val key = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray()
    private val line = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val token = Regex("(.*?)\\((\\d+),(\\d+)\\)")

    fun decrypt(hex: String): String? = runCatching {
        val data = decryptBytes(hex)
        InflaterInputStream(ByteArrayInputStream(data)).bufferedReader().readText()
    }.getOrNull()

    internal fun decryptBytes(hex: String): ByteArray {
        var data = hex.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(data.size % 8 == 0)
        data = crypt(data, key.copyOfRange(16, 24), false)
        data = crypt(data, key.copyOfRange(8, 16), true)
        return crypt(data, key.copyOfRange(0, 8), false)
    }

    fun toTtml(raw: String): String? {
        val content = Regex("LyricContent=\"(.*?)\"\\s*/>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(raw)?.groupValues?.get(1)?.let(::htmlDecode) ?: raw
        var offset = 0L
        val paragraphs = content.lineSequence().mapNotNull { source ->
            val row = source.trim()
            Regex("^\\[offset:(-?\\d+)]", RegexOption.IGNORE_CASE).find(row)?.let { offset = it.groupValues[1].toLong(); return@mapNotNull null }
            val match = line.matchEntire(row) ?: return@mapNotNull null
            val start = (match.groupValues[1].toLong() + offset).coerceAtLeast(0)
            val duration = match.groupValues[2].toLong()
            val spans = token.findAll(match.groupValues[3]).mapNotNull {
                val text = it.groupValues[1].takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val wordStart = (it.groupValues[2].toLong() + offset).coerceAtLeast(0)
                val wordDuration = it.groupValues[3].toLong()
                "<span begin=\"${sec(wordStart)}\" end=\"${sec(wordStart + wordDuration)}\">${xml(text)}</span>"
            }.toList()
            if (spans.isEmpty()) null else "<p begin=\"${sec(start)}\" end=\"${sec(start + duration)}\">${spans.joinToString("")}</p>"
        }.toList()
        if (paragraphs.isEmpty()) return null
        return """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="word"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""
    }

    private fun crypt(input: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray {
        val keys = donorSchedule(key, encrypt)
        return ByteArray(input.size).also { output ->
            for (offset in input.indices step 8) {
                val block = input.copyOfRange(offset, offset + 8)
                var left = 0; var right = 0
                repeat(32) { left = left or bitNum(block, IPL[it], 31 - it); right = right or bitNum(block, IPR[it], 31 - it) }
                repeat(15) { round -> val previous = right; right = donorF(right, keys[round]) xor left; left = previous }
                left = donorF(right, keys[15]) xor left
                donorJoin(left, right).copyInto(output, offset)
            }
        }
    }

    private fun donorSchedule(key: ByteArray, encrypt: Boolean): Array<ByteArray> {
        val schedule = Array(16) { ByteArray(6) }; var c = 0; var d = 0
        repeat(28) { c = c or bitNum(key, KEY_C[it], 31 - it); d = d or bitNum(key, KEY_D[it], 31 - it) }
        repeat(16) { round ->
            val shift = SHIFTS[round]; c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt(); d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()
            val target = if (encrypt) round else 15 - round
            repeat(24) { j -> schedule[target][j ushr 3] = (schedule[target][j ushr 3].toInt() or bitR(c, KEY_COMP[j], 7 - (j and 7))).toByte() }
            for (j in 24 until 48) schedule[target][j ushr 3] = (schedule[target][j ushr 3].toInt() or bitR(d, KEY_COMP[j] - 27, 7 - (j and 7))).toByte()
        }
        return schedule
    }

    private fun donorF(state: Int, key: ByteArray): Int {
        val t1 = bitL(state,31,0) or ((state and 0xf0000000.toInt()) ushr 1) or bitL(state,4,5) or bitL(state,3,6) or ((state and 0x0f000000) ushr 3) or bitL(state,8,11) or bitL(state,7,12) or ((state and 0x00f00000) ushr 5) or bitL(state,12,17) or bitL(state,11,18) or ((state and 0x000f0000) ushr 7) or bitL(state,16,23)
        val t2 = bitL(state,15,0) or ((state and 0x0000f000) shl 15) or bitL(state,20,5) or bitL(state,19,6) or ((state and 0x00000f00) shl 13) or bitL(state,24,11) or bitL(state,23,12) or ((state and 0x000000f0) shl 11) or bitL(state,28,17) or bitL(state,27,18) or ((state and 0x0000000f) shl 9) or bitL(state,0,23)
        val b0=(t1 ushr 24 xor key[0].toInt()) and 0xff; val b1=(t1 ushr 16 xor key[1].toInt()) and 0xff; val b2=(t1 ushr 8 xor key[2].toInt()) and 0xff
        val b3=(t2 ushr 24 xor key[3].toInt()) and 0xff; val b4=(t2 ushr 16 xor key[4].toInt()) and 0xff; val b5=(t2 ushr 8 xor key[5].toInt()) and 0xff
        val boxed=(SBOX[0][sboxBit(b0 ushr 2)] shl 28) or (SBOX[1][sboxBit(((b0 and 3) shl 4) or (b1 ushr 4))] shl 24) or (SBOX[2][sboxBit(((b1 and 15) shl 2) or (b2 ushr 6))] shl 20) or (SBOX[3][sboxBit(b2 and 63)] shl 16) or (SBOX[4][sboxBit(b3 ushr 2)] shl 12) or (SBOX[5][sboxBit(((b3 and 3) shl 4) or (b4 ushr 4))] shl 8) or (SBOX[6][sboxBit(((b4 and 15) shl 2) or (b5 ushr 6))] shl 4) or SBOX[7][sboxBit(b5 and 63)]
        var out=0; PPAIRS.forEach { (from,to) -> out = out or bitL(boxed,from,to) }; return out
    }

    private fun donorJoin(left:Int,right:Int):ByteArray { val out=ByteArray(8); repeat(8){k->val base=7-k;var v=0;repeat(4){t->v=v or bitR(right,base+8*t,7-2*t) or bitR(left,base+8*t,6-2*t)};out[INV_ORDER[k]]=v.toByte()};return out }
    private fun bitNum(bytes:ByteArray,b:Int,c:Int):Int { val index=(b/32)*4+3-(b%32)/8;return (((bytes[index].toInt() and 0xff) ushr (7-b%8)) and 1) shl c }
    private fun bitR(value:Int,b:Int,c:Int)=((value ushr (31-b)) and 1) shl c
    private fun bitL(value:Int,b:Int,c:Int)=((value shl b) and Int.MIN_VALUE) ushr c
    private fun sboxBit(value:Int)=(value and 0x20) or ((value and 0x1f) ushr 1) or ((value and 1) shl 4)

    private fun sec(ms: Long) = "${"%.3f".format(Locale.ROOT, ms / 1000.0)}s"
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun htmlDecode(s: String) = s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private val SHIFTS = intArrayOf(1,1,2,2,2,2,2,2,1,2,2,2,2,2,2,1)
    private val KEY_C=intArrayOf(56,48,40,32,24,16,8,0,57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2,59,51,43,35)
    private val KEY_D=intArrayOf(62,54,46,38,30,22,14,6,61,53,45,37,29,21,13,5,60,52,44,36,28,20,12,4,27,19,11,3)
    private val KEY_COMP=intArrayOf(13,16,10,23,0,4,2,27,14,5,20,9,22,18,11,3,25,7,15,6,26,19,12,1,40,51,30,36,46,54,29,39,50,44,32,47,43,48,38,55,33,52,45,41,49,35,28,31)
    private val IPL=intArrayOf(57,49,41,33,25,17,9,1,59,51,43,35,27,19,11,3,61,53,45,37,29,21,13,5,63,55,47,39,31,23,15,7)
    private val IPR=intArrayOf(56,48,40,32,24,16,8,0,58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4,62,54,46,38,30,22,14,6)
    private val PPAIRS=arrayOf(15 to 0,6 to 1,19 to 2,20 to 3,28 to 4,11 to 5,27 to 6,16 to 7,0 to 8,14 to 9,22 to 10,25 to 11,4 to 12,17 to 13,30 to 14,9 to 15,1 to 16,7 to 17,23 to 18,13 to 19,31 to 20,26 to 21,2 to 22,8 to 23,18 to 24,12 to 25,29 to 26,5 to 27,21 to 28,10 to 29,3 to 30,24 to 31)
    private val INV_ORDER=intArrayOf(3,2,1,0,7,6,5,4)
    private val SBOX = arrayOf(
        intArrayOf(14,4,13,1,2,15,11,8,3,10,6,12,5,9,0,7,0,15,7,4,14,2,13,1,10,6,12,11,9,5,3,8,4,1,14,8,13,6,2,11,15,12,9,7,3,10,5,0,15,12,8,2,4,9,1,7,5,11,3,14,10,0,6,13),
        intArrayOf(15,1,8,14,6,11,3,4,9,7,2,13,12,0,5,10,3,13,4,7,15,2,8,15,12,0,1,10,6,9,11,5,0,14,7,11,10,4,13,1,5,8,12,6,9,3,2,15,13,8,10,1,3,15,4,2,11,6,7,12,0,5,14,9),
        intArrayOf(10,0,9,14,6,3,15,5,1,13,12,7,11,4,2,8,13,7,0,9,3,4,6,10,2,8,5,14,12,11,15,1,13,6,4,9,8,15,3,0,11,1,2,12,5,10,14,7,1,10,13,0,6,9,8,7,4,15,14,3,11,5,2,12),
        intArrayOf(7,13,14,3,0,6,9,10,1,2,8,5,11,12,4,15,13,8,11,5,6,15,0,3,4,7,2,12,1,10,14,9,10,6,9,0,12,11,7,13,15,1,3,14,5,2,8,4,3,15,0,6,10,10,13,8,9,4,5,11,12,7,2,14),
        intArrayOf(2,12,4,1,7,10,11,6,8,5,3,15,13,0,14,9,14,11,2,12,4,7,13,1,5,0,15,10,3,9,8,6,4,2,1,11,10,13,7,8,15,9,12,5,6,3,0,14,11,8,12,7,1,14,2,13,6,15,0,9,10,4,5,3),
        intArrayOf(12,1,10,15,9,2,6,8,0,13,3,4,14,7,5,11,10,15,4,2,7,12,9,5,6,1,13,14,0,11,3,8,9,14,15,5,2,8,12,3,7,0,4,10,1,13,11,6,4,3,2,12,9,5,15,10,11,14,1,7,6,0,8,13),
        intArrayOf(4,11,2,14,15,0,8,13,3,12,9,7,5,10,6,1,13,0,11,7,4,9,1,10,14,3,5,12,2,15,8,6,1,4,11,13,12,3,7,14,10,15,6,8,0,5,9,2,6,11,13,8,1,4,10,7,9,5,0,15,14,2,3,12),
        intArrayOf(13,2,8,4,6,15,11,1,10,9,3,14,5,0,12,7,1,15,13,8,10,3,7,4,12,5,6,11,0,14,9,2,7,11,4,1,9,12,14,2,0,6,10,13,15,3,5,8,2,1,14,7,4,10,8,13,15,12,9,0,3,5,6,11),
    )
}

/** Kuwo: its search answer and its lyrics, asked with `lrcx=1` (word timing where it has it). */
object KuwoLyrics {
    class Song(val title: String, val artist: String, val seconds: Int, val rid: String)

    const val TRIES = 3
    /** Kuwo's own version markers (backing track, excerpt, live, cover, ringtone, DJ edit, preview). */
    val CUT = Regex("伴奏|片段|现场|翻唱|铃声|DJ版|试听")
    private val KEY = "yeelion".encodeToByteArray()
    private val TOKEN = Regex("<(-?\\d+),(-?\\d+)>([^<]*)")
    private val LINE = Regex("^\\[(\\d+):(\\d+)(?:\\.(\\d+))?]")
    /** "Translation by …" notes that open some machine-translated lyrics. */
    private val NOTE = Regex("歌词翻译由.{0,24}提供")

    /** The search's records (its JSON can come single-quoted, which org.json reads too). */
    fun search(raw: String): List<Song> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        fun JSONObject.text(n: String) = optString(n).replace("&nbsp;", " ").replace("&amp;", "&").trim()
        val a = root.optJSONArray("abslist") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val rid = r.text("MUSICRID").ifBlank { r.text("DC_TARGETID") }.ifBlank { return@mapNotNull null }
            Song(r.text("SONGNAME").ifBlank { r.text("NAME") }, r.text("ARTIST"), r.text("DURATION").toIntOrNull() ?: 0, if (rid.startsWith("MUSIC_")) rid else "MUSIC_$rid")
        }
    }

    /** The lyrics request's query, XOR-ed with Kuwo's key and base64-encoded. */
    fun query(rid: String): String = Base64.getEncoder().encodeToString(xor("user=12345,web,web,web&requester=localhost&req=1&rid=$rid&lrcx=1".toByteArray()))

    /** The lyrics answer (a header, a blank line, the deflated body) as text, or null when it has none. */
    fun body(bytes: ByteArray): String? {
        val split = indexOf(bytes, "\r\n\r\n".toByteArray()); if (split < 0) return null
        if (!bytes.copyOfRange(0, split).toString(Charsets.US_ASCII).startsWith("tp=content")) return null
        return runCatching { text(InflaterInputStream(ByteArrayInputStream(bytes.copyOfRange(split + 4, bytes.size))).readBytes()) }.getOrNull()
    }

    private fun xor(raw: ByteArray): ByteArray = ByteArray(raw.size) { i -> (raw[i].toInt() xor KEY[i % KEY.size].toInt()).toByte() }

    private fun text(inflated: ByteArray): String {
        val plain = if (inflated.dropWhile { it.toInt().toChar().isWhitespace() }.firstOrNull() == '['.code.toByte()) inflated
        else xor(Base64.getDecoder().decode(String(inflated, Charsets.US_ASCII).trim()))
        return String(plain, charset("GB18030")).removePrefix("\uFEFF")
    }

    /** LRCX rewritten as KRC, or null without word timing. With k the `[kuwo:]` value read as octal, a word starts
     *  |a+b| / 2(k / 10) ms into its line and lasts |a−b| / 2(k % 10) ms. Lines whose words are all `<0,0>` are
     *  translations. */
    fun krc(text: String): String? {
        if (!TOKEN.containsMatchIn(text)) return null
        val k = Regex("\\[kuwo:(\\d+)]").find(text)?.groupValues?.get(1)?.toIntOrNull(8) ?: return null
        val k1 = k / 10; val k2 = k % 10
        if (k1 == 0 || k2 == 0) return null
        val out = text.lineSequence().mapNotNull { raw ->
            val m = LINE.find(raw.trimEnd('\r')) ?: return@mapNotNull null
            val tokens = TOKEN.findAll(raw.substring(m.range.last + 1)).map { it.groupValues }.toList()
            if (tokens.isEmpty() || tokens.all { it[1] == "0" && it[2] == "0" }) return@mapNotNull null
            val at = (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + m.groupValues[3].padEnd(3, '0').take(3).toLong()
            val syls = tokens.map { (_, a, b, w) ->
                val x = a.toLong(); val y = b.toLong()
                Triple(Math.floorDiv(abs(x + y), 2L * k1), Math.floorDiv(abs(x - y), 2L * k2), w)
            }
            "[$at,${syls.maxOf { it.first + it.second }}]" + syls.joinToString("") { (o, d, w) -> "<$o,$d,0>$w" }
        }.toList()
        return out.joinToString("\n").takeIf { out.isNotEmpty() }
    }

    /** The text as plain LRC: Kuwo's own tag lines and translation notes dropped. */
    fun lrc(text: String): String = text.lineSequence().map { it.trimEnd('\r') }
        .filterNot { it.startsWith("[kuwo:") || it.startsWith("[ml:") || NOTE.containsMatchIn(it) }.joinToString("\n")

    private fun indexOf(h: ByteArray, n: ByteArray): Int { outer@ for (i in 0..h.size - n.size) { for (j in n.indices) if (h[i + j] != n[j]) continue@outer; return i }; return -1 }
}
