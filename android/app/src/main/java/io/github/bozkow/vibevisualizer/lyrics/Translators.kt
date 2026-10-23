package io.github.bozkow.vibevisualizer.lyrics

import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.NetError
import io.github.bozkow.vibevisualizer.core.Vibe
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** The translation services besides Google (Settings → Translation → Service): DeepL, LibreTranslate, and AI through
 *  any OpenAI-compatible service or Gemini. As Spicy Lyrics Mobile by TheX24 asks them (github.com/spicylyrics/mobile,
 *  AGPL-3.0: HttpTranslators, AiTranslators). Addresses, keys and models are in [Vibe.cfg] ("trkeys", out of backups).
 *  Romanization isn't theirs: the phone does it, and Google fills the gaps. */
object Translators {
    class Out(val tr: List<String?>, val lang: String?)

    /** [service]: deepl | libre | openai | gemini. [target]: a code from LANGS. One answer per line, in order. */
    suspend fun translate(service: String, lines: List<String>, target: String): Out = when (service) {
        "deepl" -> deepl(lines, target)
        "libre" -> libre(lines, target)
        "openai" -> chunked(lines) { openAi(it, target, lines) }
        "gemini" -> chunked(lines) { gemini(it, target, lines) }
        else -> throw NetError("unknown translation service")
    }

    private fun key(n: String) = Vibe.cfg.trKey(n)

    /** AI models read a few thousand characters well at a time: the lines go in parts, each with the whole song for meaning. */
    private suspend fun chunked(lines: List<String>, ask: suspend (List<String>) -> Out): Out {
        val tr = ArrayList<String?>(); var lang: String? = null
        var i = 0
        while (i < lines.size) {
            var j = i; var len = 0
            while (j < lines.size && (j == i || len + lines[j].length < 3500)) { len += lines[j].length + 1; j++ }
            val o = ask(lines.subList(i, j)); lang = lang ?: o.lang
            tr.addAll(o.tr); i = j
        }
        return Out(tr, lang)
    }

    /* ---- DeepL (your API key; a free key ends in ":fx") ---- */
    private fun deeplLang(t: String) = when (t.lowercase()) { "en" -> "EN-US"; "pt" -> "PT-BR"; "zh-cn" -> "ZH-HANS"; "zh-tw" -> "ZH-HANT"; "no" -> "NB"; else -> t.substringBefore('-').uppercase() }

    private suspend fun deepl(lines: List<String>, target: String): Out {
        val k = key("deeplKey"); if (k.isEmpty()) throw NetError("add your DeepL API key in Settings → Translation")
        val host = if (k.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val form = (lines.map { "text=" + enc(it) } + listOf("target_lang=" + deeplLang(target), "split_sentences=nonewlines", "preserve_formatting=1",
            "context=" + enc(lines.joinToString("\n")))).joinToString("&")
        val r = Net.fetch("$host/v2/translate", "deepl", "POST", mapOf("Authorization" to "DeepL-Auth-Key $k"), form.toByteArray(), "application/x-www-form-urlencoded")
        when (r.code) { 403 -> throw NetError("DeepL didn’t accept your API key"); 456 -> throw NetError("your DeepL quota is used up for now"); 429 -> throw NetError("DeepL is busy, try again in a moment") }
        if (!r.ok) throw NetError("DeepL: HTTP ${r.code}")
        val a = r.json()?.optJSONArray("translations") ?: throw NetError("DeepL sent an unexpected answer")
        val tr = (0 until a.length()).map { a.optJSONObject(it)?.optString("text")?.trim()?.ifEmpty { null } }
        val langs = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("detected_source_language")?.lowercase()?.ifEmpty { null } }
        return Out(tr, langs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
    }

    /* ---- LibreTranslate (your own server, or a key for libretranslate.com) ---- */
    private suspend fun libre(lines: List<String>, target: String): Out {
        val base = key("libreUrl").ifEmpty { "https://libretranslate.com" }.trimEnd('/')
        if (!base.startsWith("https://")) throw NetError("LibreTranslate’s address has to start with https://")
        val body = JSONObject().put("q", JSONArray(lines)).put("source", "auto").put("target", target.substringBefore('-').let { if (target.equals("zh-TW", true)) "zt" else it })
            .put("format", "text").apply { key("libreKey").takeIf { it.isNotEmpty() }?.let { put("api_key", it) } }
        val r = Net.fetch("$base/translate", "libre", "POST", body = body.toString().toByteArray(), contentType = "application/json")
        if (r.code == 403 || r.code == 401) throw NetError("LibreTranslate wants an API key (Settings → Translation)")
        if (!r.ok) throw NetError(r.json()?.optString("error")?.ifEmpty { null } ?: "LibreTranslate: HTTP ${r.code}")
        val j = r.json() ?: throw NetError("LibreTranslate sent an unexpected answer")
        val t = j.opt("translatedText")
        val tr = when (t) { is JSONArray -> (0 until t.length()).map { t.optString(it).trim().ifEmpty { null } }; is String -> listOf(t.trim().ifEmpty { null }); else -> emptyList() }
        val d = j.opt("detectedLanguage")
        val lang = when (d) { is JSONArray -> d.optJSONObject(0)?.optString("language"); is JSONObject -> d.optString("language"); else -> null }?.ifEmpty { null }
        return Out(tr, lang)
    }

    /* ---- AI: the lines go as a JSON array with the whole song for meaning, one line (or null) comes back per line ---- */
    private fun langName(t: String) = io.github.bozkow.vibevisualizer.core.LANGS.find { it.first == t }?.second ?: t

    private fun system(target: String): String {
        val l = "${langName(target)} ($target)"
        return "You translate song lyrics into $l. You are given the song's lines as a JSON array of strings. " +
            "Translate each line on its own so it stays lined up with its original, keeping its meaning, tone and imagery " +
            "natural in $l. Don't add, merge, split, explain or censor lines. A line that is already in $l, " +
            "or has nothing to translate, is null. Reply with only a JSON object, no code fence: " +
            "{\"language\": \"<ISO 639-1 code of the song's main language>\", \"lines\": [<one string or null per line, in order>]}. " +
            "\"lines\" has exactly as many items as the array you were given."
    }

    private fun user(part: List<String>, song: List<String>): String =
        "The whole song, for meaning:\n" + song.joinToString("\n") + "\n\nLines to translate (" + part.size + "):\n" + JSONArray(part)

    /** The model's reply as translations, wherever the JSON object sits in it (models add fences or a sentence). */
    internal fun parseAi(reply: String, count: Int): Out {
        val text = reply.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        val root = if (s >= 0 && e > s) runCatching { JSONObject(text.substring(s, e + 1)) }.getOrNull() else null
        val arr = root?.optJSONArray("lines") ?: run { val a = text.indexOf('['); val b = text.lastIndexOf(']'); if (a >= 0 && b > a) runCatching { JSONArray(text.substring(a, b + 1)) }.getOrNull() else null }
            ?: throw NetError("the AI didn’t reply with translations")
        if (arr.length() != count) throw NetError("the AI returned ${arr.length()} lines for $count")
        return Out((0 until arr.length()).map { if (arr.isNull(it)) null else arr.optString(it).trim().ifEmpty { null } }, root?.optString("language")?.lowercase()?.ifEmpty { null })
    }

    private fun aiError(service: String, code: Int, body: String, model: String): NetError {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()?.take(160)
        return NetError(when (code) {
            401, 403 -> "$service didn’t accept your API key"
            404 -> "$service has no model called “$model”"
            429 -> "$service is busy or out of quota, try again later"
            else -> "$service: HTTP $code" + (msg?.let { " · $it" } ?: "")
        })
    }

    private suspend fun openAi(part: List<String>, target: String, song: List<String>): Out {
        val base = key("aiUrl").ifEmpty { "https://api.openai.com/v1" }.trimEnd('/')
        if (!base.startsWith("https://")) throw NetError("the AI service’s address has to start with https://")
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val model = key("aiModel"); if (model.isEmpty()) throw NetError("add the AI model to use in Settings → Translation")
        val body = JSONObject().put("model", model).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system(target)))
            .put(JSONObject().put("role", "user").put("content", user(part, song))))
            .put("response_format", JSONObject().put("type", "json_object"))
        val k = key("aiKey")
        val r = Net.fetch(url, "ai", "POST", if (k.isNotEmpty()) mapOf("Authorization" to "Bearer $k") else emptyMap(), body.toString().toByteArray(), "application/json")
        if (!r.ok) throw aiError(Regex("https://([^/]+)").find(base)?.groupValues?.get(1) ?: "The AI service", r.code, r.text, model)
        val reply = r.json()?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: throw NetError("the AI sent an unexpected answer")
        return parseAi(reply, part.size)
    }

    const val GEMINI_MODEL = "gemini-3.5-flash-lite"

    private suspend fun gemini(part: List<String>, target: String, song: List<String>): Out {
        val k = key("geminiKey"); if (k.isEmpty()) throw NetError("add your Gemini API key in Settings → Translation")
        val model = key("geminiModel").removePrefix("models/").ifEmpty { GEMINI_MODEL }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system(target)))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user(part, song))))))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
        // the key in a header, not the address, so it stays out of logs
        val r = Net.fetch("https://generativelanguage.googleapis.com/v1beta/models/" + enc(model) + ":generateContent", "ai", "POST", mapOf("x-goog-api-key" to k), body.toString().toByteArray(), "application/json")
        if (!r.ok) throw aiError("Gemini", r.code, r.text, model)
        val parts = r.json()?.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: throw NetError("Gemini sent no translation (it may have blocked these lyrics)")
        val reply = (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text") ?: "" }
        return parseAi(reply, part.size)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
