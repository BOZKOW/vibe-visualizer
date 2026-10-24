package io.github.bozkow.vibevisualizer.engine

import android.os.SystemClock
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.Vibe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Spicy Lyrics' Canvas service (spicylyrics.org), asked by Spotify track ID when your server has no Canvas for a
 *  song (or none is set), as Spicy Lyrics Mobile asks it (github.com/spicylyrics/mobile, AGPL-3.0:
 *  SpotifyCanvasFinder). Only the song's Spotify ID is sent. Answers are remembered on the phone, songs without a
 *  Canvas for a day; after a failed lookup it's asked once more, then left alone for a minute, doubling up to 30. */
object SpicyCanvas {
    private const val BASE = "https://regional.canvasstore.slnode.spicylyrics.org/provider/canvaslist/"
    private const val MISS_TTL = 24L * 3600_000
    private const val PAUSE = 60_000L
    private const val MAX_PAUSE = 30L * 60_000

    private class Known(val url: String, val at: Long)
    private val lock = Mutex()
    private var memo: MutableMap<String, Known>? = null
    private var failedAt = 0L; private var pauseMs = 0L

    private val file get() = File(Vibe.app.filesDir, "canvas-known.json")

    sealed interface Answer { data class Found(val url: String) : Answer; data object None : Answer }

    /** The Canvas video's address, or null when the song has none or the lookup failed. */
    suspend fun find(id: String): String? {
        if (!isTrackId(id)) return null
        lock.withLock { memo()[id] }?.let { k -> if (k.url.isNotEmpty()) return k.url; if (System.currentTimeMillis() - k.at < MISS_TTL) return null }
        val now = SystemClock.elapsedRealtime()
        if (pauseMs > 0 && now - failedAt < pauseMs) return null
        var later = 0L
        var a: Answer? = null
        for (attempt in 0..1) {
            if (attempt > 0) delay(1500)
            try {
                val r = Net.fetch(BASE + id, "canvas", headers = mapOf("Accept" to "application/json"))
                if (r.ok) a = parse(r.text, id)
                else if (r.code == 400) a = Answer.None   // not a track ID it knows: never will be
                else r.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { later = it * 1000 }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            if (a != null || later > 0) break
        }
        if (a == null) {
            pauseMs = maxOf((pauseMs * 2).coerceIn(PAUSE, MAX_PAUSE), later.coerceAtMost(MAX_PAUSE)); failedAt = SystemClock.elapsedRealtime()
            return null
        }
        pauseMs = 0
        val url = (a as? Answer.Found)?.url
        lock.withLock { memo()[id] = Known(url ?: "", System.currentTimeMillis()); save() }
        return url
    }

    /** A remembered Canvas that wouldn't play: looked up again next time. */
    suspend fun forget(id: String) { lock.withLock { if (memo().remove(id) != null) save() } }

    internal fun isTrackId(id: String) = id.length == 22 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    /** `{"data":{"<id>":{"url":"…mp4",…}}}`, or `"<id>":null` for none. No `data`, no entry for the song, or an
     *  error entry is a failed lookup (null), not a song without a Canvas. Only an HTTPS MP4 counts. */
    internal fun parse(json: String, id: String): Answer? = runCatching {
        val data = JSONObject(json).optJSONObject("data") ?: return null
        if (!data.has(id)) return null
        if (data.isNull(id)) return Answer.None
        val c = data.optJSONObject(id) ?: return null
        if (c.has("error")) return null
        val u = c.optString("url")
        if (u.startsWith("https://") && u.substringBefore('?').endsWith(".mp4", true)) Answer.Found(u) else Answer.None
    }.getOrNull()

    private suspend fun memoLoad(): MutableMap<String, Known> = withContext(Dispatchers.IO) {
        val m = LinkedHashMap<String, Known>()
        runCatching { val j = JSONObject(file.readText()); j.keys().forEach { k -> j.optJSONObject(k)?.let { o -> m[k] = Known(o.optString("url"), o.optLong("at")) } } }
        m
    }
    private suspend fun memo(): MutableMap<String, Known> = memo ?: memoLoad().also { memo = it }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val m = memo ?: return@withContext
        while (m.size > 600) m.remove(m.keys.first())   // the oldest go first
        runCatching {
            val j = JSONObject(); m.forEach { (k, v) -> j.put(k, JSONObject().put("url", v.url).put("at", v.at)) }
            val tmp = File(file.path + ".tmp"); tmp.writeText(j.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}
