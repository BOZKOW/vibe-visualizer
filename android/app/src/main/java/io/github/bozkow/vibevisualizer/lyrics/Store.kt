package io.github.bozkow.vibevisualizer.lyrics

import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.wall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** What's playing, as the lyrics, cover and Canvas lookups need it. */
data class SongMeta(
    val id: String = "", val uri: String = "", val title: String = "", val artist: String = "", val album: String = "",
    val duration: Double = 0.0, val cover: String = "", val artistId: String = "", val ep: Boolean = false, val file: String = "",
    val coverSmall: String = "",   // Spotify's smaller size of the same cover, tried when the big one won't load
    val isrc: String = "",         // the recording's code (from Spotify): lrc.red finds Apple Music's lyrics by it
    val year: String = "",         // when it came out (the album's release date, or the file's tags)
)

fun norm(s: String?): String = (s ?: "").lowercase()
    .replace(Regex("\\s*[(\\[].*?[)\\]]"), "").replace(Regex("\\s*-\\s.*$"), "")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
fun fnorm(s: String?): String = (s ?: "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()   // a whole file name, nothing cut off
fun baseName(n: String?): String = (n ?: "").replace(Regex("\\.[^.]+$"), "")
fun firstArtist(a: String?): String = (a ?: "").split(Regex(",|&| feat\\.? | ft\\.? ", RegexOption.IGNORE_CASE))[0].trim()

/** Where a song's own lyrics file would be filed. */
fun songKeys(m: SongMeta): List<String> {
    val k = ArrayList<String>()
    if (m.id.isNotEmpty()) k.add("id:" + m.id)
    if (m.file.isNotEmpty()) k.add("f:" + fnorm(baseName(m.file)))
    if (m.title.isNotEmpty()) { if (m.artist.isNotEmpty()) k.add("n:" + norm(firstArtist(m.artist)) + "|" + norm(m.title)); k.add("t:" + norm(m.title)) }
    return k
}

/** What a file's name says about its song. */
fun nameKeys(name: String): List<String> {
    val b = baseName(name); val k = ArrayList<String>()
    Regex("(?:^|[^0-9A-Za-z])([0-9A-Za-z]{22})(?:$|[^0-9A-Za-z])").find(b)?.groupValues?.get(1)?.let { id ->
        if (id.any { it.isDigit() } && id.any { it.isUpperCase() }) k.add("id:$id")
    }
    val parts = b.replace('_', ' ').split(Regex("\\s[-–]\\s"))
    if (parts.size > 1) k.add("n:" + norm(firstArtist(parts[0])) + "|" + norm(parts.drop(1).joinToString(" - ")))
    else k.add("t:" + norm(b))   // title alone only for files named by title alone
    k.add("f:" + fnorm(b))
    return k.filter { !Regex(":\\|?$").containsMatchIn(it) }
}

/** Lyrics saved on this device, so a song's lyrics aren't fetched again every time: found lyrics for 7 days,
 *  "no lyrics" for 1 day. Spicy Lyrics allows keeping its responses for up to 30 days; expired ones are deleted. */
object LyricsCache {
    private const val HIT = 7 * 86_400_000L
    private const val MISS = 86_400_000L
    private val dir by lazy { File(Vibe.app.filesDir, "lyrics").apply { mkdirs() } }

    sealed class Got { object Absent : Got(); object Miss : Got(); class Hit(val r: LyricsResult) : Got() }

    private fun file(key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$h.json")
    }
    private fun fresh(o: JSONObject) = wall() - o.optLong("at") < (if (o.isNull("r")) MISS else HIT)

    suspend fun get(key: String): Got = withContext(Dispatchers.IO) {
        val f = file(key)
        if (!f.exists()) return@withContext Got.Absent
        runCatching {
            val o = JSONObject(f.readText())
            if (!fresh(o)) { f.delete(); Got.Absent } else if (o.isNull("r")) Got.Miss else Got.Hit(resultFrom(o.getJSONObject("r")))
        }.getOrElse { f.delete(); Got.Absent }
    }

    suspend fun put(key: String, r: LyricsResult?) = withContext(Dispatchers.IO) {
        runCatching { file(key).writeText(JSONObject().put("at", wall()).put("r", r?.toJson() ?: JSONObject.NULL).toString()) }
    }

    suspend fun del(key: String) = withContext(Dispatchers.IO) { file(key).delete() }
    suspend fun clear() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }

    /** Drops what's out of date; returns how many songs' lyrics are saved. */
    suspend fun prune(): Int = withContext(Dispatchers.IO) {
        var n = 0
        dir.listFiles()?.forEach { f ->
            runCatching { val o = JSONObject(f.readText()); if (!fresh(o)) f.delete() else if (!o.isNull("r")) n++ }.onFailure { f.delete() }
        }
        n
    }
}

/** Your own TTML or LRC files, kept on this phone and used for the songs they belong to. A file is found by the
 *  Spotify track id in its name, by "Artist - Title", by the title alone, or by the audio file it came with. */
object MyLyrics {
    class Entry(val name: String, val text: String, val at: Long)
    private val file by lazy { File(Vibe.app.filesDir, "mylyrics/store.json").apply { parentFile?.mkdirs() } }
    private var data: JSONObject? = null
    private val lock = Mutex()

    private fun load(): JSONObject {
        data?.let { return it }
        val o = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject().put("keys", JSONObject()).put("files", JSONObject()) }
        data = o; return o
    }
    private fun save() { runCatching { file.writeText(load().toString()) } }

    suspend fun get(key: String): Entry? = withContext(Dispatchers.IO) { lock.withLock {
        val o = load(); val id = o.getJSONObject("keys").optString(key, "")
        if (id.isEmpty()) return@withLock null
        val f = o.getJSONObject("files").optJSONObject(id) ?: return@withLock null
        Entry(f.optString("name"), f.optString("text"), f.optLong("at"))
    } }

    suspend fun putKeys(keys: Collection<String>, name: String, text: String) = withContext(Dispatchers.IO) { lock.withLock {
        val o = load(); val id = "f" + wall() + "_" + (0..9999).random()
        o.getJSONObject("files").put(id, JSONObject().put("name", name).put("text", text).put("at", wall()))
        keys.forEach { o.getJSONObject("keys").put(it, id) }
        // files no key points to any more are dropped
        val used = HashSet<String>(); val ks = o.getJSONObject("keys"); ks.keys().forEach { used.add(ks.getString(it)) }
        val fs = o.getJSONObject("files"); fs.keys().asSequence().toList().forEach { if (it !in used) fs.remove(it) }
        save()
    } }

    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { data = JSONObject().put("keys", JSONObject()).put("files", JSONObject()); save() } }
    suspend fun count(): Int = withContext(Dispatchers.IO) { lock.withLock { load().getJSONObject("files").length() } }
}
