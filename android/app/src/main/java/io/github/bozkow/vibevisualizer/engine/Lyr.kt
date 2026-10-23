package io.github.bozkow.vibevisualizer.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.BuildConfig
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.NetError
import io.github.bozkow.vibevisualizer.core.Resp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.core.wall
import io.github.bozkow.vibevisualizer.lyrics.Credit
import io.github.bozkow.vibevisualizer.lyrics.Google
import io.github.bozkow.vibevisualizer.lyrics.Line
import io.github.bozkow.vibevisualizer.lyrics.LyricsCache
import io.github.bozkow.vibevisualizer.lyrics.LyricsRenderer
import io.github.bozkow.vibevisualizer.lyrics.LyricsResult
import io.github.bozkow.vibevisualizer.lyrics.Match
import io.github.bozkow.vibevisualizer.lyrics.MyLyrics
import io.github.bozkow.vibevisualizer.lyrics.Parsed
import io.github.bozkow.vibevisualizer.lyrics.Romanizer
import io.github.bozkow.vibevisualizer.lyrics.SongMeta
import io.github.bozkow.vibevisualizer.lyrics.SourceParse
import io.github.bozkow.vibevisualizer.lyrics.BackoffLadder
import io.github.bozkow.vibevisualizer.lyrics.firstArtist
import io.github.bozkow.vibevisualizer.lyrics.checkPace
import io.github.bozkow.vibevisualizer.lyrics.fromText
import io.github.bozkow.vibevisualizer.lyrics.guessLatin
import io.github.bozkow.vibevisualizer.lyrics.interludes
import io.github.bozkow.vibevisualizer.lyrics.lineLang
import io.github.bozkow.vibevisualizer.lyrics.nonLatin
import io.github.bozkow.vibevisualizer.lyrics.parseLRC
import io.github.bozkow.vibevisualizer.lyrics.parseSpicy
import io.github.bozkow.vibevisualizer.lyrics.songKeys
import io.github.bozkow.vibevisualizer.media.Outputs
import io.github.bozkow.vibevisualizer.spotify.Spotify
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.math.abs

/** A source that doesn't apply right now (no key on your server, no server): skipped, not an error. */
class SkipError(msg: String) : Exception(msg)

/** The lyrics: finding them (every source in your order until one has the song), keeping them, translating and
 *  romanizing them, and their clock. */
object Lyr {
    val view = LyricsRenderer()

    // what's showing
    var L: List<Line> = emptyList(); private set
    var lsync = false; private set
    var credit by mutableStateOf<Credit?>(null); private set
    var label by mutableStateOf(""); private set
    var lines by mutableIntStateOf(0); private set                // how many lines (the UI only needs to know when it changes)
    class Msg(val msg: String, val sub: String = "", val kind: String = "", val actions: List<E.Act> = emptyList())
    var msg by mutableStateOf<Msg?>(null); private set
    var empty by mutableStateOf(false); private set           // the lookup is done and there are no lyrics: the cover takes their place
    var needRom by mutableStateOf(false); private set
    var needTr by mutableStateOf(false); private set
    var needSync by mutableStateOf(false); private set
    class Look(val p: String, val st: String, val ms: Long?)
    val lookupLog = mutableStateListOf<Look>()
    var lookupSong by mutableStateOf(""); private set
    var savedCount by mutableIntStateOf(0); private set
    var filesCount by mutableIntStateOf(0); private set

    private class Cur(val m: SongMeta, val key: String, val skip: List<String>)
    private var cur: Cur? = null
    private val lcache = HashMap<String, LyricsResult>()
    private var lyAskAt = 0L
    private var loadJob: Job? = null
    private var loadT: Job? = null

    // the clock: the song's time, stretched to the lyrics' version, plus the offsets and any desync fix
    var lPace = 1.0; private set
    var lAdj = 0.0
    var songOffMs by mutableIntStateOf(0); private set
    fun lyrT() = E.curTime() * lPace + offS()
    /** Every offset on top of the song's time (s): yours, this song's, this output's and any desync fix. */
    private fun offS() = (S.loff + songOffMs + Outputs.lyricsOffMs(E.src)) / 1000.0 + lAdj

    fun curSong(): SongMeta? = cur?.m

    init { view.durFn = { E.curDur() }; view.reduceMotion = { E.reduceMotion() } }

    /* ================= sources ================= */

    data class Prov(val name: String, val desc: String)
    val PROVS = linkedMapOf(
        "local" to Prov("Your lyrics files", "TTML or LRC files you added · kept on this phone"),
        "spicy" to Prov("Spicy Lyrics", "community word-synced lyrics · needs your API key"),
        "amll" to Prov("AMLL TTML DB", "community word-synced TTML lyrics (CC0), found by the Spotify track"),
        "unison" to Prov("Unison", "community word-synced lyrics from Better Lyrics, credited to who synced them"),
        "spotify" to Prov("Spotify", "Spotify’s own lyrics · needs the sp_dc cookie · unofficial"),
        "lrclib" to Prov("LRCLIB", "free, open lyrics database"),
        "netease" to Prov("NetEase Cloud Music", "strong for Japanese, Chinese, Korean · unofficial"),
        "lrcmux" to Prov("LRCMux", "word-synced lyrics gathered from other catalogues (KuGou, LRCLIB, …)"),
    )
    val PROVIDERS = mapOf("amll" to "AMLL TTML DB", "spicy_lyrics" to "Spicy Lyrics", "apple_music" to "Apple Music", "spotify" to "Spotify", "lrclib" to "LRCLIB", "netease" to "NetEase Cloud Music", "unison" to "Unison", "lrcmux" to "LRCMux")
    // the sources that can have word-synced lyrics: once line-synced ones are showing, only these are still asked
    private val WORDY = setOf("local", "spicy", "amll", "unison", "lrcmux")

    data class PItem(val k: String, val on: Boolean)
    fun provList(): List<PItem> {
        val out = ArrayList<PItem>(); val seen = HashSet<String>()
        S.str("lprov").split(',').forEach { x -> val kv = x.split(':'); val k = kv[0]; if (PROVS.containsKey(k) && seen.add(k)) out.add(PItem(k, kv.getOrNull(1) != "0")) }
        PROVS.keys.forEach { k -> if (k !in seen) { if (k == "local") out.add(0, PItem(k, true)) else out.add(PItem(k, true)) } }   // your own files come first
        return out
    }
    fun setProv(list: List<PItem>) { S.set("lprov", list.joinToString(",") { it.k + ":" + (if (it.on) 1 else 0) }); lcache.clear() }

    private fun server(path: String): String = Server.url(path) ?: throw SkipError("needs your server (Settings → Help & about → Server)")

    private fun dbg(m: String) { if (BuildConfig.DEBUG) android.util.Log.d("VV-lyrics", m) }

    private suspend fun fromLocal(m: SongMeta): LyricsResult? {
        dbg("local keys: " + songKeys(m))
        for (k in songKeys(m)) {
            val v = MyLyrics.get(k) ?: continue
            val r = runCatching { fromText(v.text) }.onFailure { dbg("your file ${v.name}: $it") }.getOrNull() ?: continue
            if (r.lines.isEmpty()) continue
            return LyricsResult(r.lines, r.sync, v.name, Credit("file", name = v.name, writers = r.writers))
        }
        return null
    }

    private suspend fun fromSpicy(m: SongMeta): LyricsResult? {
        val base = server("/api/lyrics")
        val id = Spotify.idFor(m) ?: return null
        val r = try { Net.fetch("$base?id=" + URLEncoder.encode(id, "UTF-8"), "spicy") } catch (e: NetError) { Ping.kick(); throw Exception("your server could not be reached") }
        val j = r.json()
        if (r.code == 404) return null
        if (r.code == 503 && j?.optString("code") == "no_key") throw SkipError("no API key on your server yet")
        if (r.code == 401 || r.code == 403) throw Exception("the API key was rejected (HTTP ${r.code})")
        if (r.code == 429) throw Exception("rate limited, try again in a bit")
        if (!r.ok) throw Exception(j?.optString("error")?.ifEmpty { null } ?: "HTTP ${r.code}")
        return parseSpicy(j ?: throw Exception("not JSON"), m.duration)
    }

    // AMLL TTML DB (github.com/amll-dev/amll-ttml-db, CC0): word-synced TTML files named by Spotify track id
    private suspend fun fromAmll(m: SongMeta): LyricsResult? {
        val id = Spotify.idFor(m) ?: return null
        val r = Net.fetch("https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/spotify-lyrics/" + URLEncoder.encode(id, "UTF-8") + ".ttml", "amll")
        if (r.code == 404) return null
        if (!r.ok) throw Exception("HTTP ${r.code}")
        val t = fromText(r.text); if (t.lines.isEmpty()) return null
        return LyricsResult(t.lines, t.sync, "AMLL TTML DB", Credit("amll", writers = t.writers))
    }

    private suspend fun fromSpotifyLyrics(m: SongMeta): LyricsResult? {
        val base = server("/api/spotify-lyrics")
        val id = Spotify.idFor(m) ?: return null
        val r = try { Net.fetch("$base?id=" + URLEncoder.encode(id, "UTF-8"), "spotifylyr") } catch (e: NetError) { Ping.kick(); throw Exception("your server could not be reached") }
        val j = r.json()
        if (r.code == 404) return null
        if (r.code == 503 && j?.optString("code") == "no_cookie") throw SkipError("no sp_dc cookie on your server")
        if (!r.ok) throw Exception(j?.optString("error")?.ifEmpty { null } ?: "HTTP ${r.code}")
        return SourceParse.spotify(j)
    }

    private suspend fun fromLrclib(m: SongMeta): LyricsResult? {
        suspend fun get(u: String): org.json.JSONObject? {   // a free service that sometimes answers 503/429 under load: retry with backoff
            var i = 0
            while (true) {
                val r = try { Net.fetch(u, "lrclib") } catch (e: NetError) { if (i < 2) { delay(1000L * (i + 1)); i++; continue }; throw Exception("offline or blocked") }
                if (r.code == 404) return null
                if (r.ok) return r.json() ?: r.jsonArray()?.let { a -> org.json.JSONObject().put("_list", a) }
                if ((r.code >= 500 || r.code == 429) && i < 2) { delay((r.header("Retry-After")?.toLongOrNull() ?: 0) * 1000L + if (r.header("Retry-After") == null) 1200L * (i + 1) else 0); i++; continue }
                throw Exception(if (r.code == 503) "the service is busy right now (HTTP 503), try again in a moment" else "HTTP ${r.code}")
            }
        }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        var q = "track_name=" + enc(m.title) + "&artist_name=" + enc(m.artist)
        if (m.album.isNotEmpty()) q += "&album_name=" + enc(m.album)
        if (m.duration > 0) q += "&duration=" + Math.round(m.duration)
        var d: org.json.JSONObject? = null; var getErr: Exception? = null
        try { d = get("https://lrclib.net/api/get?$q") } catch (e: Exception) { getErr = e }   // exact lookup failed: still try search
        if (d == null) {
            val s = get("https://lrclib.net/api/search?" + if (m.artist.isNotEmpty()) "track_name=" + enc(m.title) + "&artist_name=" + enc(m.artist) else "q=" + enc(m.title))
            val a = s?.optJSONArray("_list")
            if (a != null && a.length() > 0) {   // several versions: the synced one closest in length to this song
                val items = (0 until a.length()).map { a.getJSONObject(it) }
                fun off(x: org.json.JSONObject) = if (m.duration > 0 && x.optDouble("duration", 0.0) > 0) abs(x.optDouble("duration") - m.duration) else 99.0
                d = items.sortedWith(compareBy<org.json.JSONObject>({ if (it.optString("syncedLyrics").isNotEmpty() && !it.isNull("syncedLyrics")) 0 else 1 }, { off(it) })).first()
            }
        }
        if (d == null) { if (getErr != null) throw getErr; return null }
        return SourceParse.lrclib(d)
    }

    private suspend fun fromNetease(m: SongMeta): LyricsResult? {
        val base = server("/api/netease")
        if (m.title.isEmpty()) return null
        val q = "title=" + URLEncoder.encode(m.title, "UTF-8") + "&artist=" + URLEncoder.encode(m.artist.split(',')[0].trim(), "UTF-8") + "&duration=" + Math.round(m.duration)
        val r = try { Net.fetch("$base?$q", "netease") } catch (e: NetError) { Ping.kick(); throw Exception("your server could not be reached") }
        val j = r.json()
        if (r.code == 404) return null
        if (!r.ok) throw Exception(j?.optString("error")?.ifEmpty { null } ?: "HTTP ${r.code}")
        return SourceParse.netease(j)
    }

    // the same song by its names: equal once case, accents and punctuation are gone (Spicy Lyrics Mobile's matcher)
    private fun sameArtist(theirs: String, ours: String): Boolean {
        val a = Match.key(theirs); val b = Match.key(ours)
        return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a) || Match.key(firstArtist(ours)).let { it.isNotEmpty() && a.contains(it) })
    }

    // Unison (unison.boidu.dev, Better Lyrics' community lyrics), as Spicy Lyrics Mobile asks it: by name, then a search
    private suspend fun fromUnison(m: SongMeta): LyricsResult? {
        if (m.title.isEmpty()) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun fits(o: org.json.JSONObject) = Match.sameTitle(o.optString("song").ifEmpty { o.optString("title") }, m.title) && sameArtist(o.optString("artist"), m.artist)
        suspend fun get(u: String): Resp? { val r = Net.fetch(u, "unison"); if (r.code == 404) return null; if (!r.ok) throw Exception("HTTP ${r.code}"); return r }
        get("https://unison.boidu.dev/lyrics?song=" + enc(m.title) + "&artist=" + enc(firstArtist(m.artist)))?.json()?.optJSONObject("data")?.let { d -> if (fits(d)) SourceParse.unison(d)?.let { return it } }
        val hits = get("https://unison.boidu.dev/lyrics/search?q=" + enc(firstArtist(m.artist) + " " + m.title))?.json()?.optJSONArray("data") ?: return null
        val id = (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }.firstOrNull(::fits)?.opt("id")?.toString() ?: return null
        return get("https://unison.boidu.dev/lyrics/" + enc(id))?.json()?.optJSONObject("data")?.let(SourceParse::unison)
    }

    // LRCMux (api.lrcmux.dev): word-synced lyrics it gathers from other catalogues, matched by title and length
    private suspend fun fromLrcmux(m: SongMeta): LyricsResult? {
        if (m.title.isEmpty() || m.duration <= 0) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val r = Net.fetch("https://api.lrcmux.dev/get?artist=" + enc(firstArtist(m.artist)) + "&title=" + enc(m.title) + "&duration=" + Math.round(m.duration) + "&format=json&level=word", "lrcmux")
        if (r.code == 404) return null
        if (!r.ok) throw Exception("HTTP ${r.code}")
        val j = r.json() ?: throw Exception("not JSON")
        return SourceParse.lrcmux(j, m.title, m.album)
    }

    /* ================= one lookup ================= */

    /** Every source in your order until one has the song. skip: sources not to ask (another source for this song);
     *  fresh: past the saved lyrics. */
    fun load(m: SongMeta, tok: Int, skip: List<String> = emptyList(), fresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = Vibe.scope.launch { doLoad(m, tok, skip, fresh) }
    }

    private suspend fun doLoad(m: SongMeta, tok: Int, skip: List<String>, fresh: Boolean) {
        loading()
        val quick = wall() - lyAskAt < 1200; lyAskAt = wall()
        if (quick && !fresh) { delay(650); if (tok != E.coverTok) return }   // skipping through songs: only ask for the one you stop on
        val skipS = skip.toSet(); val order = provList().filter { it.on && it.k !in skipS }.map { it.k }
        val log = ArrayList<Look>()
        fun note(p: String, st: String, ms: Long? = null) { log.add(Look(p, st, ms)); if (tok == E.coverTok) { lookupLog.clear(); lookupLog.addAll(log) } }
        lookupLog.clear(); lookupSong = (if (m.artist.isNotEmpty()) m.artist + " – " else "") + m.title
        if (order.isEmpty()) { if (skipS.isNotEmpty()) return otherDone(m, tok); clear("All lyrics sources are off", "", "none", listOf(E.Act("Settings", pri = true) { E.openSettings("lyrics") })); return }
        val key = listOf(m.id, m.artist, m.title, Math.round(m.duration).toString(), provList().filter { it.on }.joinToString(",") { it.k }).joinToString("|").lowercase()
        cur = Cur(m, key, skip)
        if (!fresh) {
            if (order[0] == "local") { val r = runCatching { fromLocal(m) }.getOrNull(); if (tok != E.coverTok) return; if (r != null) { r.prov = "local"; note("local", "found"); apply(r); return } }   // your own file wins, also over saved lyrics
            lcache[key]?.let { r -> note(r.prov ?: "?", "from memory"); apply(r); return }
            val saved = LyricsCache.get(key); if (tok != E.coverTok) return
            when (saved) {
                is LyricsCache.Got.Hit -> { lcache[key] = saved.r; note(saved.r.prov ?: "?", "saved on this device"); apply(saved.r); return }
                is LyricsCache.Got.Miss -> { note("—", "saved: no lyrics"); clear("No lyrics for this song", "None of your sources had it lately.", "none", listOf(E.Act("Look again") { clearSong() })); return }
                else -> {}
            }
        }
        val errs = ArrayList<String>()
        val t0 = now()
        // Spicy Lyrics Mobile's lookup: the first source asks alone (one request usually settles the song); if it misses
        // or takes over a second, the rest are asked together. Better timing wins (word-synced over line-synced over
        // not synced), your order breaks ties. The first answer shows as soon as every source above it is done, and a
        // better-timed one that lands in the next few seconds replaces it.
        class Got(val r: LyricsResult?, val e: Exception?, val ms: Long)
        val asks = HashMap<String, Deferred<Got>>()
        var best: LyricsResult? = null
        fun canBeat(p: String) = best.let { b -> b == null || rank(b) == 0 || (rank(b) == 1 && p in WORDY) }
        val resting = order.associateWith { ladder(it).openUntil(wall()) }
        coroutineScope {
            fun ask(p: String): Deferred<Got> = asks.getOrPut(p) { async {
                val a = now()
                try { Got(fetchFrom(p, m), null, (now() - a).toLong()) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Got(null, e, (now() - a).toLong()) }
            } }
            fun fanOut() { for (q in order) if (resting[q] == null && canBeat(q) && best.let { it == null || rank(it) < 2 }) ask(q) }
            var fanned = false
            for (p in order) {
                if (best.let { it != null && rank(it) == 2 }) break
                if (!canBeat(p)) continue
                resting[p]?.let { until -> note(p, "resting after errors, back at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))) }
                if (resting[p] != null) continue
                val d = ask(p)
                val lead = !fanned && p != "local"
                var g: Got? = if (lead) withTimeoutOrNull(1000) { d.await() } else null
                if (lead && g == null) { fanned = true; fanOut() }
                if (g == null) g = d.await()
                if (tok != E.coverTok) break
                val r = g.r
                when {
                    g.e is SkipError -> note(p, "skipped: " + g.e.message)
                    g.e != null -> {
                        note(p, "failed: " + (g.e.message ?: g.e.javaClass.simpleName), g.ms)
                        errs.add(PROVS.getValue(p).name + ": " + (g.e.message ?: "error"))
                        if (p != "local") ladder(p).failure(wall())
                    }
                    r != null && r.lines.any { !it.dots } -> {
                        ladder(p).success()
                        r.prov = p
                        val b = best
                        val better = b == null || rank(r) > rank(b)
                        note(p, "found: " + (if (r.sync) (if (r.lines.any { it.words != null }) "word-synced" else "line-synced") else "not synced") + if (b != null && !better) " (not used)" else "", g.ms)
                        if (better) {
                            best = r
                            if (skipS.isEmpty()) { lcache[key] = r; LyricsCache.put(key, r); refreshCounts() }
                            if (b == null || now() - t0 < 8000) apply(r)   // a late upgrade waits for the next time the song plays
                            if (tok != E.coverTok) break
                        }
                    }
                    else -> { ladder(p).success(); note(p, "not found", g.ms) }
                }
                if (lead) { fanned = true; fanOut() }
            }
            coroutineContext.cancelChildren()   // what's still out can't do better
        }
        if (tok != E.coverTok || best != null) return
        if (skipS.isNotEmpty()) return otherDone(m, tok)
        if (errs.isEmpty()) LyricsCache.put(key, null)   // remember "none" for a day; failures aren't remembered
        if (errs.isNotEmpty()) clear("Couldn’t load lyrics", errs.first(), "none", listOf(
            E.Act("Try again", pri = true) { ladders.clear(); lcache.remove(key); Vibe.scope.launch { LyricsCache.del(key) }; load(m, E.coverTok) },
            E.Act("Copy details") { copy(m.artist + " – " + m.title + "\n" + errs.joinToString("\n")); E.copied("Details") }))
        else clear("No lyrics for this song", "It may be instrumental. You can add your own .lrc or .ttml file.", "none", listOf(E.Act("Add a file") { FilePicker.lyrics?.invoke(true) }))
    }

    private suspend fun fetchFrom(p: String, m: SongMeta): LyricsResult? = when (p) {
        "local" -> fromLocal(m); "spicy" -> fromSpicy(m); "amll" -> fromAmll(m); "spotify" -> fromSpotifyLyrics(m)
        "lrclib" -> fromLrclib(m); "unison" -> fromUnison(m); "lrcmux" -> fromLrcmux(m); else -> fromNetease(m)
    }

    /** How well timed: 2 word-synced, 1 line-synced, 0 not synced. */
    private fun rank(r: LyricsResult) = if (!r.sync) 0 else if (r.lines.any { it.words != null }) 2 else 1

    // a source that fails twice in a row rests a while, longer each time (Spicy Lyrics Mobile's back-off ladder)
    private val ladders = HashMap<String, BackoffLadder>()
    private fun ladder(p: String) = synchronized(ladders) { ladders.getOrPut(p) { BackoffLadder() } }

    /** "Wrong lyrics?": the next source in your order for this song (not saved); after the last one, back to the first. */
    fun other() {
        val c = cur
        if (c == null || (L.isEmpty() && c.skip.isEmpty())) { E.toast("No lyrics to replace", "warn"); return }
        val used = if (L.isNotEmpty() && credit != null) (c.skip + listOfNotNull(prov())).filter { it.isNotEmpty() } else c.skip
        E.toast("Trying another source…", "ok")
        load(c.m, E.coverTok, used, true)
    }

    // the source that found the lyrics showing: from the lookup, not the credit, which can name where that source got them
    // (Spicy Lyrics says "apple_music" for some songs, so "Wrong lyrics?" asked Spicy Lyrics again)
    private var shownProv: String? = null
    private fun prov(): String? = shownProv ?: when (val s = credit?.source) { "spicy_lyrics" -> "spicy"; "file" -> "local"; else -> s }
    private fun otherDone(m: SongMeta, tok: Int) { E.toast("No other source has this song", "warn"); if (tok == E.coverTok) load(m, tok) }

    /* ================= on screen ================= */

    /** A song change: the old lyrics fade out, and "Looking for lyrics…" only shows if finding the new ones takes a while. */
    fun loading() {
        loadT?.cancel()
        val was = empty   // from one song without lyrics to the next, the big cover stays while looking
        if (L.isNotEmpty() && !S.low && !E.reduceMotion()) view.leave() else clear("", "", "")
        empty = was
        loadT = Vibe.scope.launch { delay(500); clear("Looking for lyrics…", "", "load"); empty = was }
    }

    fun clear(m: String, sub: String = "", kind: String = "", actions: List<E.Act> = emptyList()) {
        loadT?.cancel(); view.freeEnd()
        L = emptyList(); lsync = false; credit = null; label = ""; shownProv = null
        render(null, false)
        empty = kind == "none"
        msg = if (m.isEmpty() || (kind == "none" && !S.b("lnotice"))) null else Msg(m, sub, kind, actions)
    }

    private fun apply(r: LyricsResult) {
        loadT?.cancel(); view.freeEnd(); songOffLoad(); Outputs.refresh()
        lPace = checkPace(r, E.curDur())
        Desync.song()
        credit = r.credit ?: if (r.label.isNotEmpty()) Credit(null) else null
        shownProv = r.prov ?: if (r.credit?.source == "file") "local" else null
        L = (if (r.sync) interludes(r.lines) else r.lines.filter { !it.dots }).map { it.copy() }
        lsync = r.sync
        romDone = false; romJob = null
        msg = null; empty = false
        render(r.label, false)
        view.enter()
        decorate(true)
    }

    /** One of your files, opened for the song that's playing. */
    fun applyFile(p: Parsed, name: String) {
        val r = LyricsResult(p.lines, p.sync, name, Credit("file", name = name, writers = p.writers))
        apply(r)
        cur?.let { c -> lcache.remove(c.key); Vibe.scope.launch { LyricsCache.del(c.key) } }
    }

    private fun render(lbl: String?, quiet: Boolean) {
        if (!quiet && lbl != null) label = lbl
        view.set(L, lsync, quiet)
        lines = L.size
        updNeed()
    }

    // show the Translate / Romanize buttons only when the lyrics on screen need them
    private fun skipLangs(): Set<String> = S.trSkip.split(',').filter { it.isNotEmpty() }.toMutableSet().also { it.add(S.trTo.split('-')[0]) }
    private fun updNeed() {
        val ls = L.filter { !it.dots && it.text.isNotEmpty() }
        val latin = ls.filter { !nonLatin(it.text) }.map { it.text }
        val song = if (latin.isNotEmpty()) guessLatin(latin) else null
        val skip = skipLangs()
        needRom = ls.any { nonLatin(it.text) }
        needTr = ls.any { !skip.contains(if (nonLatin(it.text)) lineLang(it.text, null) else song) }
        needSync = lsync && ls.isNotEmpty()
    }

    /* ---- translation + romanization ---- */
    private var trTok = 0
    private val trCache = HashMap<String, Google.Res>()
    private var romDone = false
    private var romJob: Deferred<Unit>? = null

    fun decorate(fresh: Boolean = false) {
        val tok = ++trTok
        Vibe.scope.launch {
            val ls = L.filter { !it.dots && it.text.isNotEmpty() }
            L.forEach { it.tr = null; it.rom = null }
            val wantRom = S.romMode != "off" && ls.any { nonLatin(it.text) }; val wantTr = S.tr
            if (ls.isEmpty() || (!wantRom && !wantTr)) { if (L.isNotEmpty() && !fresh) render(null, true); return@launch }
            // romanization/translation that came with the lyrics is used first; Google fills the gaps. The API's
            // translations are assumed to be English, so they're only used when translating into English
            val skip0 = skipLangs()
            fun trOk(l: Line) = l.apiTr != null && S.trTo.split('-')[0] == (l.apiTrLang ?: "en")
            if (wantRom && !romDone) {
                val job = romJob ?: Vibe.scope.async { runCatching { Romanizer.romanizeLyrics(ls) }; romDone = true }.also { romJob = it }
                render(null, true)   // show the lyrics right away; the romanization follows
                job.await(); if (tok != trTok) return@launch
            }
            ls.forEach { l ->
                l.romSyl = false
                if (wantRom && nonLatin(l.text)) {
                    if (l.sylRom != null) { l.rom = l.sylRom; l.romSyl = true }   // per syllable, timed with the singing
                    else if (l.apiRom != null) { l.rom = l.apiRom; l.romSyl = l.words?.any { it.rom != null } == true }
                    else if (l.lineRom != null) l.rom = l.lineRom
                }
                if (wantTr && trOk(l) && !skip0.contains(lineLang(l.text, null))) l.tr = l.apiTr
            }
            val needG = ls.any { (wantRom && nonLatin(it.text) && it.rom == null) || (wantTr && it.tr == null && !trOk(it)) }
            if (!needG) { render(null, true); return@launch }
            val key = S.trTo + "|" + ls.joinToString("\n") { it.text }
            val res = trCache[key] ?: try { Google.all(ls.map { it.text }, S.trTo).also { trCache[key] = it } }
                catch (e: Exception) { if (tok == trTok) E.toast("Translation isn’t available right now", "warn"); render(null, true); return@launch }
            if (tok != trTok) return@launch
            val skip = skipLangs()
            ls.forEachIndexed { i, l ->
                val rom = res.rom.getOrNull(i); val tr = res.tr.getOrNull(i)
                if (wantRom && nonLatin(l.text) && l.rom == null && rom != null && rom != l.text) l.rom = rom
                if (wantTr && l.tr == null && !trOk(l) && tr != null && !skip.contains(lineLang(l.text, res.lang)) && !tr.equals(l.text, ignoreCase = true)) l.tr = tr
            }
            render(null, true)
        }
    }

    fun restyle() { if (L.isNotEmpty()) render(null, true) else view.restyle() }
    fun relayout() = view.relayout()
    fun noticeChanged() { if (!S.b("lnotice") && msg?.kind == "none") msg = null }
    fun cacheClear() = lcache.clear()

    /* ---- every frame ---- */
    fun tick(t: Double) {
        view.frame(t, lyrT(), E.lyricsVisible() && L.isNotEmpty())
    }

    /** A line was tapped: jump there. */
    fun tap(i: Int) {
        val l = L.getOrNull(i) ?: return
        if (view.dragged) return
        view.freeEnd()
        val t = l.t ?: return
        if (lsync && E.seekTo((t - offS()) / lPace - if (S.b("lseekFade") && E.src == "spotify") .3 else 0.0)) { view.tapped(i); E.haptic() }
    }

    /* ---- timing ---- */
    private val songOffPrefs by lazy { Vibe.app.getSharedPreferences("songoff", android.content.Context.MODE_PRIVATE) }
    private fun songKey(): String = Spotify.spId?.let { "id:$it" } ?: cur?.key ?: ""
    private fun songOffLoad() { songOffMs = songOffPrefs.getInt(songKey(), 0) }
    /** This song's own delay (Spicy Lyrics Mobile's "song delay"), on top of the general timing offset. */
    fun songOffSet(ms: Int) {
        val k = songKey(); if (k.isEmpty()) return
        songOffMs = ms.coerceIn(-5000, 5000)
        songOffPrefs.edit().apply { if (songOffMs != 0) putInt(k, songOffMs) else remove(k) }.apply()
        view.restAll()
    }

    fun nudge(ms: Int) {   // ] earlier, [ later
        val v = (S.loff + ms).coerceIn(-3000, 3000)
        S.set("loff", v)
    }

    private var resyncing = false
    var spinAt by mutableStateOf(0L); private set
    /** Manual resync: fresh readings from Spotify, the most exact one wins, snapped (no gliding); undoes a desync fix. */
    fun resync() {
        if (resyncing) return
        resyncing = true; spinAt = wall()
        Vibe.scope.launch {
            try {
                val moved = if (E.src == "spotify") Spotify.resync() else null
                val undo = lAdj != 0.0; lAdj = 0.0; Desync.reset(true)
                view.restAll()
                E.toast(if (moved == null || abs(moved) < 40) "In sync" else "Moved " + String.format("%.2f", abs(moved) / 1000) + " s " + if (moved > 0) "ahead" else "back", "ok")
                @Suppress("UNUSED_VARIABLE") val u = undo
            } catch (e: Exception) { E.toast("Couldn’t resync right now", "warn") }
            finally { resyncing = false }
        }
    }

    /* ---- Settings → Lyrics cache ---- */
    fun refreshCounts() { Vibe.scope.launch { savedCount = LyricsCache.prune(); filesCount = MyLyrics.count() } }

    fun clearSong() {
        val c = cur ?: run { E.toast("No song is playing", "warn"); return }
        lcache.remove(c.key); trCache.clear()
        Vibe.scope.launch { LyricsCache.del(c.key); refreshCounts(); E.toast("Cleared · fetching again", "ok"); load(c.m, E.coverTok) }
    }
    fun resetSong() { val c = cur ?: run { E.toast("No song is playing", "warn"); return }; lcache.remove(c.key); load(c.m, E.coverTok) }
    fun clearStored() { Vibe.scope.launch { LyricsCache.clear(); lcache.clear(); trCache.clear(); refreshCounts(); E.toast("Stored lyrics cleared", "ok") } }
    fun clearFiles() { Vibe.scope.launch { MyLyrics.clear(); refreshCounts(); E.toast("Your lyrics files were removed", "ok") } }
    fun reloadCurrent() { cur?.let { load(it.m, E.coverTok) } }

    fun testSpicy(out: (String) -> Unit) {
        out("Testing…")
        val m = if (E.src == "spotify" && Spotify.spId != null) SongMeta(id = Spotify.spId!!, title = E.title, artist = E.artist, duration = E.curDur())
            else SongMeta(id = "0VjIjW4GlUZAMYd2vXMi3b", title = "Blinding Lights", artist = "The Weeknd", duration = 200.0)
        Vibe.scope.launch {
            try { val r = fromSpicy(m); out(if (r != null && r.lines.isNotEmpty()) "Working · ${r.lines.size} lines for “${m.title}” (" + (if (r.sync) "synced" else "not synced") + ")." else "Connected, but Spicy Lyrics has nothing for “${m.title}”.") }
            catch (e: SkipError) { out(if (e.message?.contains("key") == true) "No API key on your server yet (see below)." else "Needs your server: set it in Settings → Help & about.") }
            catch (e: Exception) { out("Failed: " + e.message) }
        }
    }

    fun copy(text: String) {
        val cm = Vibe.app.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Vibe Visualizer", text))
    }

    fun debugInfo(): String = listOf(
        "Vibe Visualizer debug info",
        "app: Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "device: Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}",
        "server: " + (if (Server.set) "set" else "not set") + " · " + Ping.label,
        "source: " + E.src + (if (E.src != "none") " · " + (if (E.playing()) "playing" else "paused") else "") + " · view: " + S.view + " · design: " + S.str("aDesign") + (if (S.low) " · low performance" else ""),
        "song: " + (cur?.m?.let { it.artist + " – " + it.title + (Spotify.spId?.let { id -> " ($id)" } ?: "") } ?: "—"),
        "lyrics: " + (if (L.isNotEmpty()) (if (lsync) "synced" else "not synced") + " from " + (PROVS[prov() ?: ""]?.name ?: "?") else "none") + " · offset ${S.loff} ms · this song ${songOffMs} ms · ${Outputs.route.label} ${Outputs.offMs} ms" + (if (Outputs.here(E.src)) "" else " (not playing here)"),
        "cover: " + E.coverNote.ifEmpty { "—" },
        "last lookup: " + (lookupLog.joinToString("; ") { it.p + " " + it.st + (it.ms?.let { m -> " ${m}ms" } ?: "") }.ifEmpty { "—" }),
        "sources: " + S.str("lprov"),
        "problems: " + E.problems(false).joinToString("; ") { it.t },
    ).joinToString("\n")
}
