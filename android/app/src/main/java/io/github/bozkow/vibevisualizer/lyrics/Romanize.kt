package io.github.bozkow.vibevisualizer.lyrics

import android.icu.text.Transliterator
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer

/* ---- romanization the way Spicy Lyrics does it (src/utils/Lyrics/ProcessLyrics.ts, AGPL-3.0) ----
   Every syllable is romanized on its own, so the romanized text stays timed with the singing. Romanization the lyrics
   already came with wins; the gaps are filled per script: Japanese with kuromoji (as kuroshiro does in the web app),
   Chinese with pinyin (Android's own ICU), Korean (Revised Romanization), Cyrillic and Greek with tables. */
object Romanizer {
    private const val TAG = "VV-rom"
    private val jaLock = Mutex()
    @Volatile private var ja: com.atilika.kuromoji.ipadic.Tokenizer? = null
    private val han by lazy { runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull() }

    private class Job(val word: Word? = null, val bg: Bg? = null, val line: Line? = null, val text: String) {
        fun put(r: String?) {
            val x = r?.replace(Regex("\\s+"), " ")?.trim()
            if (x.isNullOrEmpty()) return
            when { word != null -> word.rom = x; bg != null -> bg.rom = x; line != null -> line.lineRom = x }
        }
        fun has() = when { word != null -> word.rom != null; bg != null -> bg.rom != null; line != null -> line.lineRom != null; else -> true }
    }

    suspend fun romanizeLyrics(lines: List<Line>) = withContext(Dispatchers.Default) {
        // what still needs romanizing: syllables (or whole lines without syllables) the lyrics didn't come romanized
        val jobs = ArrayList<Job>()
        for (l in lines) {
            if (!nonLatin(l.text)) continue
            val ws = l.words
            if (!ws.isNullOrEmpty()) ws.forEach { w -> if (w.rom == null && nonLatin(w.text)) jobs.add(Job(word = w, text = w.text)) }
            else if (l.apiRom == null) jobs.add(Job(line = l, text = l.text))
            l.bg?.forEach { b ->
                val bw = b.words
                if (!bw.isNullOrEmpty()) bw.forEach { w -> if (w.rom == null && nonLatin(w.text)) jobs.add(Job(word = w, text = w.text)) }
                else if (b.rom == null && nonLatin(b.text)) jobs.add(Job(bg = b, text = b.text))
            }
        }
        if (jobs.isNotEmpty()) {
            val isJa = JA.containsMatchIn(lines.joinToString("\n") { it.text })
            // Han characters are Japanese in a song with kana, Chinese otherwise (as Spicy Lyrics decides it)
            val heavy = jobs.filter { JA.containsMatchIn(it.text) || HAN.containsMatchIn(it.text) }
            if (heavy.isNotEmpty()) {
                if (isJa) {
                    val tk = withTimeoutOrNull(60_000) { japanese() }
                    if (tk != null) heavy.forEach { j -> runCatching { j.put(jaRomaji(tk, j.text)) } }
                    else Log.w(TAG, "Japanese dictionary unavailable; Google fills in")
                } else heavy.forEach { j -> j.put(pinyin(j.text)) }
            }
            val rest = jobs.filter { it !in heavy }
            rest.forEach { j ->
                when {
                    KO.containsMatchIn(j.text) -> j.put(korean(j.text))
                    CYR.containsMatchIn(j.text) && !j.has() -> j.put(cyrillic(j.text))
                    GR.containsMatchIn(j.text) && !j.has() -> j.put(greek(j.text))
                }
            }
        }
        fun join(ws: List<Word>) = ws.joinToString("") { (it.rom ?: it.text) + if (it.sp) " " else "" }.replace(Regex("\\s+"), " ").trim()
        for (l in lines) {
            val ws = l.words
            if (!ws.isNullOrEmpty() && nonLatin(l.text) && ws.any { it.rom != null } && ws.none { nonLatin(it.text) && it.rom == null }) l.sylRom = join(ws)
            l.bg?.forEach { b -> val bw = b.words; if (bw != null && bw.any { it.rom != null } && b.rom == null) b.rom = join(bw) }
        }
    }

    /** kuromoji's dictionary is big: built once, in the background, the first time a Japanese song plays. */
    private suspend fun japanese(): com.atilika.kuromoji.ipadic.Tokenizer? = jaLock.withLock {
        ja ?: runCatching { com.atilika.kuromoji.ipadic.Tokenizer() }.onFailure { Log.w(TAG, "kuromoji: $it") }.getOrNull().also { ja = it }
    }

    private fun jaRomaji(tk: com.atilika.kuromoji.ipadic.Tokenizer, text: String): String {
        val parts = ArrayList<String>()
        var gem = false   // the last word ended in っ: it doubles this one's first consonant (だっ た → da tta)
        for (t in tk.tokenize(text)) {
            val s = t.surface
            if (s.isBlank()) continue
            val particle = t.partOfSpeechLevel1 == "助詞"
            val kana = t.reading?.takeIf { it != "*" } ?: s
            var r = when {
                particle && s == "は" -> "wa"; particle && s == "へ" -> "e"; particle && s == "を" -> "o"
                JA.containsMatchIn(s) || HAN.containsMatchIn(s) -> kanaToRomaji(kana)
                else -> s
            }
            if (gem && r.isNotEmpty() && r[0].isLetter() && r[0].lowercaseChar() !in "aeioun") r = (if (r.startsWith("ch")) "t" else r[0].toString()) + r
            gem = (JA.containsMatchIn(s) || HAN.containsMatchIn(s)) && (kana.endsWith('ッ') || kana.endsWith('っ'))
            parts.add(r)
        }
        return parts.joinToString(" ")   // kuroshiro's "spaced" mode
    }

    private fun pinyin(text: String): String {
        val tr = han ?: return text
        val items = ArrayList<String>(); val run = StringBuilder()
        for (ch in text) {
            if (HAN.matches(ch.toString())) {
                if (run.isNotEmpty()) { items.add(run.toString()); run.clear() }
                items.add(tr.transliterate(ch.toString()).trim())
            } else run.append(ch)
        }
        if (run.isNotEmpty()) items.add(run.toString())
        return items.joinToString("-")
    }

    // ---- kana → Hepburn ----
    private val KANA = mapOf(
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo", "シャ" to "sha", "シュ" to "shu", "ショ" to "sho", "シェ" to "she",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho", "チェ" to "che", "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo", "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo", "リャ" to "rya",
        "リュ" to "ryu", "リョ" to "ryo", "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo", "ジャ" to "ja", "ジュ" to "ju",
        "ジョ" to "jo", "ジェ" to "je", "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo", "ビャ" to "bya", "ビュ" to "byu",
        "ビョ" to "byo", "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo", "ファ" to "fa", "フィ" to "fi", "フェ" to "fe",
        "フォ" to "fo", "フュ" to "fyu", "ティ" to "ti", "ディ" to "di", "トゥ" to "tu", "ドゥ" to "du", "デュ" to "dyu",
        "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo", "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo",
        "ツァ" to "tsa", "ツィ" to "tsi", "ツェ" to "tse", "ツォ" to "tso", "イェ" to "ye", "クァ" to "kwa", "グァ" to "gwa",
        "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o", "カ" to "ka", "キ" to "ki", "ク" to "ku", "ケ" to "ke",
        "コ" to "ko", "サ" to "sa", "シ" to "shi", "ス" to "su", "セ" to "se", "ソ" to "so", "タ" to "ta", "チ" to "chi",
        "ツ" to "tsu", "テ" to "te", "ト" to "to", "ナ" to "na", "ニ" to "ni", "ヌ" to "nu", "ネ" to "ne", "ノ" to "no",
        "ハ" to "ha", "ヒ" to "hi", "フ" to "fu", "ヘ" to "he", "ホ" to "ho", "マ" to "ma", "ミ" to "mi", "ム" to "mu",
        "メ" to "me", "モ" to "mo", "ヤ" to "ya", "ユ" to "yu", "ヨ" to "yo", "ラ" to "ra", "リ" to "ri", "ル" to "ru",
        "レ" to "re", "ロ" to "ro", "ワ" to "wa", "ヰ" to "wi", "ヱ" to "we", "ヲ" to "o", "ン" to "n", "ガ" to "ga",
        "ギ" to "gi", "グ" to "gu", "ゲ" to "ge", "ゴ" to "go", "ザ" to "za", "ジ" to "ji", "ズ" to "zu", "ゼ" to "ze",
        "ゾ" to "zo", "ダ" to "da", "ヂ" to "ji", "ヅ" to "zu", "デ" to "de", "ド" to "do", "バ" to "ba", "ビ" to "bi",
        "ブ" to "bu", "ベ" to "be", "ボ" to "bo", "パ" to "pa", "ピ" to "pi", "プ" to "pu", "ペ" to "pe", "ポ" to "po",
        "ヴ" to "vu", "ァ" to "a", "ィ" to "i", "ゥ" to "u", "ェ" to "e", "ォ" to "o", "ャ" to "ya", "ュ" to "yu", "ョ" to "yo",
        "ヮ" to "wa", "ヵ" to "ka", "ヶ" to "ke",
    )

    fun kanaToRomaji(src: String): String {
        val k = buildString { for (c in src) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c) }   // hiragana → katakana
        val out = StringBuilder(); var i = 0; var gem = false
        while (i < k.length) {
            val c = k[i]
            if (c == 'ッ') { gem = true; i++; continue }
            if (c == 'ー') { val v = out.lastOrNull { it in "aeiou" }; if (v != null) out.append(v); i++; continue }
            val two = if (i + 1 < k.length) KANA[k.substring(i, i + 2)] else null
            val r = two ?: KANA[c.toString()]
            if (r == null) { out.append(c); gem = false; i++; continue }
            if (gem) { out.append(if (r.startsWith("ch")) 't' else r[0]); gem = false }
            // ん before a vowel or y: n' keeps it readable (kon'ya)
            if (out.endsWith("n") && i > 0 && k[i - 1] == 'ン' && (r[0] in "aeiouy")) out.append('\'')
            out.append(r); i += if (two != null) 2 else 1
        }
        return out.toString()
    }

    // ---- Korean: Revised Romanization, letter by letter ----
    private val KO_L = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val KO_V = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val KO_T = arrayOf("", "k", "kk", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lb", "ls", "lt", "lp", "lh", "m", "p", "ps", "t", "ss", "ng", "t", "t", "k", "t", "p", "h")

    fun korean(t: String): String = buildString {
        for (c in t) {
            val x = c.code - 0xAC00
            if (x in 0 until 11172) { append(KO_L[x / 588]); append(KO_V[(x % 588) / 28]); append(KO_T[x % 28]) } else append(c)
        }
    }

    // ---- Cyrillic ----
    private val CYR_MAP = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya", 'є' to "ye", 'і' to "i", 'ї' to "yi", 'ґ' to "g", 'ў' to "u", 'ђ' to "dj", 'ј' to "j",
        'љ' to "lj", 'њ' to "nj", 'ћ' to "c", 'џ' to "dz", 'ѓ' to "gj", 'ќ' to "kj", 'ѕ' to "dz",
    )

    fun cyrillic(t: String): String = buildString {
        for (c in t) {
            val lo = c.lowercaseChar(); val r = CYR_MAP[lo]
            if (r == null) append(c) else if (c != lo && r.isNotEmpty()) append(r[0].uppercaseChar()).append(r.substring(1)) else append(r)
        }
    }

    // ---- Greek: ELOT 743 style, with the common two-letter sounds ----
    private val GREEK = mapOf('α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th",
        'ι' to "i", 'κ' to "k", 'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s",
        'ς' to "s", 'τ' to "t", 'υ' to "y", 'φ' to "f", 'χ' to "ch", 'ψ' to "ps", 'ω' to "o")
    private val GR_PAIRS = listOf("ου" to "ou", "αι" to "ai", "ει" to "ei", "οι" to "oi", "μπ" to "b", "ντ" to "nt", "γκ" to "gk", "γγ" to "ng", "αυ" to "av", "ευ" to "ev")

    fun greek(src: String): String {
        val t = Normalizer.normalize(Normalizer.normalize(src, Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), ""), Normalizer.Form.NFC)
        val out = StringBuilder(); var i = 0
        while (i < t.length) {
            if (i + 1 < t.length) {
                val two = t.substring(i, i + 2); val lo = two.lowercase(); val pr = GR_PAIRS.find { it.first == lo }
                if (pr != null) { out.append(if (two[0] != lo[0]) pr.second[0].uppercaseChar() + pr.second.substring(1) else pr.second); i += 2; continue }
            }
            val c = t[i]; val l = c.lowercaseChar(); val r = GREEK[l]
            out.append(if (r == null) c.toString() else if (c != l) r[0].uppercaseChar() + r.substring(1) else r); i++
        }
        return out.toString()
    }
}
