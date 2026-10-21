package io.github.bozkow.vibevisualizer.lyrics

import org.json.JSONArray
import org.json.JSONObject

/** One timed piece of a line: a word or a syllable (sp: a space follows it). Times in seconds. */
class Word(var t: Double, var end: Double?, val text: String, var sp: Boolean, var rom: String? = null)

/** Background vocals under a line, with their own timing when the lyrics have it. */
class Bg(val text: String, var rom: String?, val words: List<Word>?)

class Line(
    var t: Double?,
    var end: Double?,
    val text: String,
    var words: List<Word>? = null,
    var bg: List<Bg>? = null,
    val opp: Boolean = false,          // sung by the other singer of a duet: on the right
    val dots: Boolean = false,         // an instrumental break
    var apiRom: String? = null,        // romanization that came with the lyrics
    var apiTr: String? = null,         // translation that came with the lyrics
    var apiTrLang: String? = null,
) {
    // filled in on screen: romanization and translation, and how they were made
    var rom: String? = null
    var tr: String? = null
    var romSyl = false
    var sylRom: String? = null
    var lineRom: String? = null

    /** The line as shown: its own copy, sharing the words (as Object.assign({}, l) in the web app). */
    fun copy() = Line(t, end, text, words, bg, opp, dots, apiRom, apiTr, apiTrLang).also { it.sylRom = sylRom; it.lineRom = lineRom }
}

class Person(val username: String, val id: String?, val url: String?, val avatar: String?)

class Credit(
    val source: String?,
    val name: String? = null,          // your own file's name
    val uploader: Person? = null,
    val maker: Person? = null,
    val writers: List<String> = emptyList(),
    val via: String? = null,
)

class LyricsResult(
    val lines: List<Line>,
    val sync: Boolean,
    var label: String,
    var credit: Credit?,
    val srcDur: Double? = null,
    var prov: String? = null,
)

/* ---- saved on this device as JSON (the lyrics cache and your lyrics files) ---- */

private fun JSONObject.optStr(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
private fun JSONObject.optD(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k).takeIf { !it.isNaN() } else null

fun Word.toJson(): JSONObject = JSONObject().put("t", t).put("e", end ?: JSONObject.NULL).put("x", text).put("s", sp).apply { rom?.let { put("r", it) } }
fun wordFrom(o: JSONObject) = Word(o.optDouble("t"), o.optD("e"), o.optString("x"), o.optBoolean("s"), o.optStr("r"))

fun List<Word>.toJsonArr() = JSONArray().also { a -> forEach { a.put(it.toJson()) } }
fun wordsFrom(a: JSONArray?): List<Word>? = a?.let { (0 until it.length()).map { i -> wordFrom(it.getJSONObject(i)) } }

fun Line.toJson(): JSONObject = JSONObject().apply {
    put("t", t ?: JSONObject.NULL); put("e", end ?: JSONObject.NULL); put("x", text)
    words?.let { put("w", it.toJsonArr()) }
    bg?.let { b -> put("b", JSONArray().also { a -> b.forEach { x -> a.put(JSONObject().put("x", x.text).put("r", x.rom ?: JSONObject.NULL).apply { x.words?.let { put("w", it.toJsonArr()) } }) } }) }
    if (opp) put("o", true); if (dots) put("d", true)
    apiRom?.let { put("ar", it) }; apiTr?.let { put("at", it) }; apiTrLang?.let { put("al", it) }
}

fun lineFrom(o: JSONObject) = Line(
    o.optD("t"), o.optD("e"), o.optString("x"), wordsFrom(o.optJSONArray("w")),
    o.optJSONArray("b")?.let { a -> (0 until a.length()).map { i -> val b = a.getJSONObject(i); Bg(b.optString("x"), b.optStr("r"), wordsFrom(b.optJSONArray("w"))) } },
    o.optBoolean("o"), o.optBoolean("d"), o.optStr("ar"), o.optStr("at"), o.optStr("al"),
)

private fun Person.toJson() = JSONObject().put("username", username).put("id", id ?: JSONObject.NULL).put("url", url ?: JSONObject.NULL).put("avatar", avatar ?: JSONObject.NULL)
fun personFrom(o: JSONObject?): Person? = o?.let { val u = it.optStr("username") ?: it.optStr("Username") ?: return null
    Person(u, it.optStr("id") ?: it.optStr("Id"), it.optStr("url") ?: it.optStr("Url"), it.optStr("avatar") ?: it.optStr("Avatar")) }

fun Credit.toJson(): JSONObject = JSONObject().apply {
    put("source", source ?: JSONObject.NULL); name?.let { put("name", it) }; uploader?.let { put("uploader", it.toJson()) }
    maker?.let { put("maker", it.toJson()) }; put("writers", JSONArray(writers)); via?.let { put("via", it) }
}

fun creditFrom(o: JSONObject?): Credit? = o?.let {
    Credit(it.optStr("source"), it.optStr("name"), personFrom(it.optJSONObject("uploader")), personFrom(it.optJSONObject("maker")),
        it.optJSONArray("writers")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList(), it.optStr("via"))
}

fun LyricsResult.toJson(): JSONObject = JSONObject().apply {
    put("lines", JSONArray().also { a -> lines.forEach { a.put(it.toJson()) } }); put("sync", sync); put("label", label)
    credit?.let { put("credit", it.toJson()) }; srcDur?.let { put("srcDur", it) }; prov?.let { put("prov", it) }
}

fun resultFrom(o: JSONObject): LyricsResult {
    val a = o.getJSONArray("lines")
    return LyricsResult((0 until a.length()).map { lineFrom(a.getJSONObject(it)) }, o.optBoolean("sync"), o.optString("label"),
        creditFrom(o.optJSONObject("credit")), o.optD("srcDur"), o.optStr("prov"))
}
