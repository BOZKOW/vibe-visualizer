package io.github.bozkow.vibevisualizer.lyrics

import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.NetError
import org.json.JSONArray
import java.net.URLEncoder

/** Translation and romanization from Google Translate's free web endpoint (unofficial), as in the web app. */
object Google {
    private const val GT = "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=auto&dt=t&dt=rm&tl="

    class Res(val tr: List<String?>, val rom: List<String?>, val lang: String?)
    private class Chunk(val tr: List<String>?, val rom: List<String>?, val lang: String?)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private suspend fun chunk(lines: List<String>, tl: String): Chunk {
        val r = Net.fetch(GT + enc(tl) + "&q=" + enc(lines.joinToString("\n|\n")), "google")
        if (!r.ok) throw NetError(if (r.code == 429) "Google is limiting requests right now, try again later" else "HTTP ${r.code}")
        val d = r.jsonArray() ?: throw NetError("unexpected answer")
        val segs = d.optJSONArray(0) ?: JSONArray()
        fun split(s: String) = s.split(Regex("\\s*\\|\\s*")).map { it.trim() }
        val trText = StringBuilder(); val rm = ArrayList<String>()
        for (i in 0 until segs.length()) {
            val x = segs.optJSONArray(i) ?: continue
            if (!x.isNull(0)) trText.append(x.optString(0))
            if (x.length() > 3 && !x.isNull(3)) x.optString(3).takeIf { it.isNotEmpty() }?.let { rm.add(it) }
        }
        val tr = split(trText.toString())
        val rom = if (rm.isNotEmpty()) split(rm.joinToString(" ")) else emptyList()
        return Chunk(tr.takeIf { it.size == lines.size }, rom.takeIf { it.size == lines.size }, d.optString(2).takeIf { it.isNotEmpty() && it != "null" })
    }

    suspend fun all(lines: List<String>, tl: String): Res {
        val tr = ArrayList<String?>(); val rom = ArrayList<String?>(); var lang: String? = null
        var i = 0
        while (i < lines.size) {   // chunks that keep the request URL a sensible size
            var j = i; var len = 0
            while (j < lines.size && (j == i || len + enc(lines[j]).length < 3500)) { len += enc(lines[j]).length + 9; j++ }
            val part = lines.subList(i, j)
            var r = chunk(part, tl)
            if ((r.tr == null || r.rom == null) && part.size > 1) {   // separators got mangled: one line at a time
                val one = part.map { chunk(listOf(it), tl) }
                r = Chunk(one.map { it.tr?.get(0) ?: "" }, one.map { it.rom?.get(0) ?: "" }, r.lang)
            }
            lang = lang ?: r.lang
            part.indices.forEach { k -> tr.add(r.tr?.getOrNull(k)?.ifEmpty { null }); rom.add(r.rom?.getOrNull(k)?.ifEmpty { null }) }
            i = j
        }
        return Res(tr, rom, lang)
    }
}
