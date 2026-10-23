package io.github.bozkow.vibevisualizer.spotify

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.NetError
import io.github.bozkow.vibevisualizer.core.Resp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.fmtWait
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.core.wall
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.lyrics.SongMeta
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class SpotifyError(msg: String, val rate: Boolean = false, val scope: Boolean = false) : Exception(msg)

data class SpDevice(val id: String, val name: String, val type: String, val volume: Int?, val supportsVolume: Boolean, val active: Boolean, val restricted: Boolean)

/** Spotify through its official Web API (PKCE login, no client secret), as in the web app. */
object Spotify {
    const val SCOPES = "user-read-currently-playing user-read-playback-state user-modify-playback-state user-library-read user-library-modify"
    private val cfg get() = Vibe.cfg
    private val prefs by lazy { Vibe.app.getSharedPreferences("spotify", Context.MODE_PRIVATE) }

    // ---- what Spotify says is playing ----
    class PState(var playing: Boolean, var progress: Double, val duration: Double)
    var spState: PState? = null
        private set
    var spId: String? = null
        private set
    var meta by mutableStateOf<SongMeta?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    private var spFetched = 0.0
    private var spSlew: Pair<Double, Double>? = null   // (error, since)
    private var spSnap = false
    private class Sample(val off: Double, val rtt: Double)
    private var spSamples = ArrayList<Sample>()
    private var spRtt = 0.0

    // ---- pace, rate limits, errors ----
    var backoff by mutableLongStateOf(0L)              // wall clock ms: Spotify asked to wait until then
        private set
    var waitKnown = 0.0
        private set
    private var spRateN = 0
    var errMsg by mutableStateOf("")
        private set
    private var spErrAt = 0L
    private var errN = 0   // failed requests in a row: the retries space out (3, 6, 12, 24, 30 s)
    private var pollJob: Job? = null
    private var refreshing: kotlinx.coroutines.Deferred<Unit>? = null

    // ---- more controls: shuffle, repeat, Liked Songs, devices, volume ----
    var shuffle by mutableStateOf(false); private set
    var repeat by mutableStateOf("off"); private set
    var liked by mutableStateOf<Boolean?>(null); private set
    var device by mutableStateOf<SpDevice?>(null); private set
    var disallows by mutableStateOf<Set<String>>(emptySet()); private set
    var version by mutableIntStateOf(0); private set   // bumps when any of the above changes
    private var spxHold = 0.0
    private var likeTok = 0
    private var spLibOld = false

    val connected get() = cfg.spAccess.isNotEmpty()
    fun hasScope(x: String) = cfg.spScope.isEmpty() || cfg.spScope.split(' ').contains(x)   // unknown (older logins): just try

    /* ================= login ================= */

    private fun b64u(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

    /** Spotify comes back to your server's page (the address registered for the web app), which hands the login to
     *  this app (state "a.…" → vibevisualizer://callback), as it does for the Mac app. Without a server, the app's own
     *  address has to be registered. */
    fun redirectUri(): String = Server.base?.let { "$it/" } ?: "vibevisualizer://callback"

    fun login(act: Activity) {
        if (cfg.spotId.isBlank()) { E.toast("Add your Spotify Client ID first", "warn"); E.openSettings("spotify"); return }
        val v = b64u(rnd(48)); val st = "a." + b64u(rnd(12))
        val ch = b64u(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()))
        prefs.edit().putString("v", v).putString("s", st).putString("r", redirectUri()).putLong("at", wall()).apply()
        val u = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", cfg.spotId.trim()).appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri()).appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", ch).appendQueryParameter("state", st).appendQueryParameter("scope", SCOPES).build()
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true)
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(0xFF07070C.toInt()).build())
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK).build().launchUrl(act, u)
        }.onFailure { E.toast("Couldn’t open a browser for the Spotify login") }
    }

    /** The browser came back with vibevisualizer://callback?code=…&state=… */
    suspend fun callback(data: Uri): Boolean {
        val code = data.getQueryParameter("code"); val err = data.getQueryParameter("error")
        val st = prefs.getString("s", null); val v = prefs.getString("v", "") ?: ""; val r = prefs.getString("r", redirectUri()) ?: redirectUri()
        prefs.edit().remove("v").remove("s").remove("at").apply()
        if (err != null) { E.toast("Spotify login was cancelled", "warn"); return false }
        if (code == null) return false
        if (data.getQueryParameter("state") != st) { E.toast("Spotify login didn’t match, try again", "warn"); return false }
        return try {
            token(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to r, "code_verifier" to v))
            true
        } catch (e: Exception) { E.toast(e.message ?: "Spotify login failed"); false }
    }

    /** Back in the app without a login (the browser was closed, or Spotify stopped on its own page): say why. */
    fun loginCheck() {
        val at = prefs.getLong("at", 0)
        if (connected || at == 0L || wall() - at < 2500 || wall() - at > 15 * 60000) return
        prefs.edit().remove("at").apply()
        E.snack(E.Snack("Spotify didn’t finish connecting", "warn", E.Act("Try again") { E.spLogin() }, long = true))
    }

    private suspend fun token(extra: Map<String, String>) {
        val form = (mapOf("client_id" to cfg.spotId.trim()) + extra).entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val r = try { Net.fetch("https://accounts.spotify.com/api/token", "spotify", "POST", body = form.toByteArray(), contentType = "application/x-www-form-urlencoded") }
        catch (e: NetError) { throw SpotifyError("Could not reach Spotify (offline?).") }
        val j = r.json() ?: JSONObject()
        if (!r.ok) throw SpotifyError("Spotify login failed: " + (j.optString("error_description").ifEmpty { j.optString("error").ifEmpty { "HTTP ${r.code}" } }))
        cfg.setSpotify(j.getString("access_token"), j.optString("refresh_token").ifEmpty { null }, j.optLong("expires_in", 3600), j.optString("scope").ifEmpty { null })
    }

    private suspend fun refresh() {
        // one renewal at a time: two at once would use the same refresh token twice
        refreshing?.let { if (it.isActive) { it.await(); return } }
        val d = kotlinx.coroutines.CompletableDeferred<Unit>(); refreshing = d
        try { token(mapOf("grant_type" to "refresh_token", "refresh_token" to cfg.spRefresh)); d.complete(Unit) }
        catch (e: Exception) { d.completeExceptionally(e); throw e }
    }

    fun disconnect() { cfg.clearSpotify(); spState = null; spId = null; meta = null; isPlaying = false; pollJob?.cancel() }

    /* ================= the Web API ================= */

    suspend fun api(path: String, method: String = "GET", body: JSONObject? = null, raw: Boolean = false, again: Boolean = false): Any? {
        if (!connected) throw SpotifyError("Not connected to Spotify.")
        if (wall() > cfg.spExp) {
            if (cfg.spRefresh.isEmpty()) { cfg.clearSpotify(); throw SpotifyError("Spotify session expired. Reconnect.") }
            try { refresh() } catch (e: SpotifyError) { if (e.message?.contains("reach") == true) throw e; cfg.clearSpotify(); throw SpotifyError("Spotify session expired. Reconnect.") }
        }
        val r: Resp = try {
            Net.fetch("https://api.spotify.com/v1$path", "spotify", method, mapOf("Authorization" to "Bearer " + cfg.spAccess),
                body?.toString()?.toByteArray() ?: if (method != "GET") ByteArray(0) else null, if (body != null) "application/json" else null)
        } catch (e: NetError) { E.netKick(); throw SpotifyError("Could not reach Spotify (offline?).") }
        if (r.code == 204) return null
        if (r.code == 401) {
            if (!again && cfg.spRefresh.isNotEmpty()) { runCatching { refresh() }.onSuccess { return api(path, method, body, raw, true) } }   // renew the login once first
            cfg.clearSpotify(); throw SpotifyError("Spotify session expired. Reconnect.")
        }
        if (raw) return r
        if (r.code == 403 && method != "GET") {   // playback control refused: Premium or permission missing
            val why = r.json()?.optJSONObject("error")?.let { it.optString("reason").ifEmpty { it.optString("message") } } ?: ""
            throw if (why.contains("PREMIUM", true)) SpotifyError("Playback control needs Spotify Premium.")
            else if (why.contains("scope", true)) SpotifyError("scope", scope = true) else SpotifyError("Spotify refused that (${why.ifEmpty { "403" }}).")
        }
        if (r.code == 403) throw SpotifyError("Spotify refused access (403). In development mode your account must be added under the app’s User Management.")
        if (method != "GET") { if (r.code == 404) throw SpotifyError("No active Spotify device. Start playing in Spotify first."); if (r.ok) return null }
        if (r.code == 429) {   // the app can read Spotify's Retry-After (a browser can't): a known wait, or a blind back-off
            spRateN = min(spRateN + 1, 6); if (spRateN == 1) paceSlower()
            val ra = r.header("Retry-After")?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
            val wait = if (ra > 0) ra.toDouble() else min(300.0, 10.0 * 2.0.pow(spRateN - 1))   // known wait, or 10, 20, 40, 80, 160, 300 s
            backoff = wall() + (wait * 1000).toLong(); waitKnown = if (ra > 0) wait else 0.0
            throw SpotifyError(if (ra > 0) "Spotify asked Vibe Visualizer to wait ${fmtWait(ra.toDouble())} before asking again. It will carry on by itself then; the lyrics keep running meanwhile."
                else "Spotify asked Vibe Visualizer to slow down, so it waits a little longer each time until Spotify answers again. The lyrics keep running meanwhile.", rate = true)
        }
        if (!r.ok) throw SpotifyError("Spotify error (HTTP ${r.code}).")
        return r.json() ?: r.jsonArray()
    }

    /* ================= polling ================= */

    // how often to ask Spotify: often enough to stay in sync (the position is extrapolated in between), rarely enough not
    // to hit Spotify's rate limit; right after the song should end, to pick up the next one quickly. Each refusal slows
    // it (3 → 5 → 8 → 12 → 15 s while playing); after 30 minutes without one it speeds back up a step at a time.
    // With the Spotify app on this phone giving the timing, it asks far less often.
    private val PACE = longArrayOf(3000, 5000, 8000, 12000, 15000)
    private fun paceNow(): Long {
        var i = prefs.getInt("pace", 0).coerceIn(0, PACE.size - 1); val at = prefs.getLong("paceAt", 0)
        if (i > 0 && wall() - at > 1_800_000) { i--; prefs.edit().putInt("pace", i).putLong("paceAt", wall()).apply() }
        return PACE[i]
    }
    private fun paceSlower() { val i = min(PACE.size - 1, prefs.getInt("pace", 0) + 1); prefs.edit().putInt("pace", i).putLong("paceAt", wall()).apply() }

    fun start() { spState = null; spId = null; poll() }
    fun stop() { pollJob?.cancel(); pollJob = null; spState = null; spId = null; isPlaying = false }

    private fun sched(extra: Long = 0, fixed: Long? = null) {
        pollJob?.cancel()
        if (E.src != "spotify") return
        val pace = paceNow()
        var d = fixed ?: ((if (!E.visible) max(15000, pace * 2) else if (spPlaying()) pace else pace * 2) + extra)
        if (fixed == null && appTiming()) d = max(d, 15000)   // the Spotify app gives the timing and the song changes
        val st = spState
        if (fixed == null && spPlaying() && st != null) { val left = st.duration - curMs(); if (left > 0 && left + 400 < d) d = (left + 400).toLong() }
        if (wall() < backoff) d = max(d, backoff - wall() + 50)
        pollJob = Vibe.scope.launch { delay(d); poll() }
    }

    fun poll() {
        if (E.src != "spotify") return
        if (!E.visible || wall() < backoff) { sched(); return }
        pollJob?.cancel()
        pollJob = Vibe.scope.launch {
            var retry = false
            try {
                val t0 = now()
                val d = api("/me/player?additional_types=track,episode") as? JSONObject
                if (E.src != "spotify") return@launch
                val t1 = now(); spRtt = t1 - t0
                apply(d, t0 + (t1 - t0) / 2)   // Spotify read the position somewhere during the request: count it from the midpoint
                spRateN = 0; backoff = 0; errMsg = ""; errN = 0
            } catch (e: SpotifyError) {
                if (!e.rate) { errMsg = e.message ?: ""; retry = true }   // shown as a problem, not a message
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { errMsg = e.message ?: "error"; retry = true }
            // after an error, try again soon so it clears quickly, then less and less often (offline, access refused…)
            if (retry) { sched(fixed = min(30000L, 3000L shl min(errN, 4))); errN++ } else sched()
        }
    }

    // Spotify's position lands a little early or late on every reading (its own estimate, and the request's travel time).
    // The readings since the last skip, seek or pause are averaged: the quickest round trips count most, a stray one is
    // left out, and the clock only moves when they agree it's off by more than 30 ms.
    private fun est(): Double? {
        val s = spSamples; if (s.isEmpty()) return null
        val offs = s.map { it.off }.sorted(); val med = offs[offs.size / 2]
        var sw = 0.0; var so = 0.0
        for (x in s) { if (abs(x.off - med) > 250) continue; val w = 1 / (x.rtt / 2 + 80).pow(2); sw += w; so += w * x.off }
        return if (sw > 0) so / sw else med
    }

    private fun apply(d: JSONObject?, mid: Double) {
        val nowT = now(); val before = if (spState?.playing == true) curMs() else null; val oldId = spId
        spFetched = mid
        val it = d?.optJSONObject("item")
        spxFrom(d)
        if (it == null) {
            spState = null; isPlaying = false
            if (spId != null || E.title.isEmpty()) { spId = null; meta = null; E.spIdle() }
            return
        }
        val ep = it.optString("type") == "episode"
        val imgs = (if (ep) it.optJSONArray("images") else it.optJSONObject("album")?.optJSONArray("images")) ?: JSONArray()
        val artists = it.optJSONArray("artists") ?: JSONArray()
        val m = SongMeta(id = it.optString("id"), uri = it.optString("uri"), title = it.optString("name"),
            artist = if (ep) it.optJSONObject("show")?.optString("name") ?: "" else (0 until artists.length()).joinToString(", ") { k -> artists.getJSONObject(k).optString("name") },
            album = if (ep) "" else it.optJSONObject("album")?.optString("name") ?: "", duration = it.optDouble("duration_ms") / 1000,
            cover = imgs.optJSONObject(0)?.optString("url") ?: "", coverSmall = imgs.optJSONObject(1)?.optString("url") ?: "", artistId = if (ep) "" else artists.optJSONObject(0)?.optString("id") ?: "", ep = ep)
        val st = PState(d.optBoolean("is_playing"), d.optDouble("progress_ms", 0.0), it.optDouble("duration_ms"))
        spState = st
        // small differences glide in over ~0.6 s instead of jumping the lyrics; big ones (seek, skip, pause) snap
        val after = st.progress + if (st.playing) nowT - mid else 0.0
        val same = !spSnap && before != null && m.id == oldId && st.playing && abs(before - after) < 1500
        if (same) {
            spSamples.add(Sample(st.progress - mid, spRtt)); if (spSamples.size > 8) spSamples.removeAt(0)
            val l3 = spSamples.takeLast(3).map { x -> x.off }; val l2 = l3.takeLast(2); val e0 = est() ?: 0.0
            if (spSamples.size >= 5 && l3.max() - l3.min() < 80 && abs(l3.average() - e0) > 150) spSamples = ArrayList(spSamples.takeLast(3))   // it really moved
            else if (spSamples.size >= 4 && abs(l2[0] - l2[1]) < 60 && abs((l2[0] + l2[1]) / 2 - e0) > 200) spSamples = ArrayList(spSamples.takeLast(2))   // a big move: two readings are enough
            st.progress = (est() ?: 0.0) + nowT; spFetched = nowT   // the position the readings agree on, as of now
        } else spSamples = if (st.playing) arrayListOf(Sample(st.progress - mid, spRtt)) else ArrayList()
        val err = if (before == null) null else before - (st.progress + if (st.playing) nowT - spFetched else 0.0)
        if (same && err != null && abs(err) <= 30) { st.progress = before!!; spFetched = nowT; spSlew = null }   // close enough: the lyrics don't move at all
        else spSlew = if (same && err != null && abs(err) > 15) err to nowT else null
        isPlaying = st.playing
        meta = m
        if (m.id != spId) { spId = m.id; E.onSpTrack(m) }
        E.onSpUpdate()
    }

    /** The Spotify app on this phone plays the song the Web API reported: its timing is used. */
    fun appTiming(): Boolean {
        val m = meta ?: return false
        return S.b("aSession") && SpotifyApp.matches(m.title, m.duration * 1000)
    }

    fun spPlaying() = if (appTiming()) SpotifyApp.playing else spState?.playing == true

    /** Spotify position in ms, with any resync correction still gliding in. */
    fun curMs(): Double {
        if (appTiming()) SpotifyApp.positionMs()?.let { return it }
        val st = spState ?: return 0.0
        val n = now()
        var t = st.progress + if (st.playing) n - spFetched else 0.0
        spSlew?.let { (e, at) -> val k = (n - at) / 600; if (k >= 1) spSlew = null else t += e * (1 - k * k * (3 - 2 * k)) }
        return t
    }

    fun durationMs() = spState?.duration ?: ((meta?.duration ?: 0.0) * 1000)

    // the Spotify app on this phone tells the app at once when it plays, pauses, seeks or changes song
    init { SpotifyApp.onTrack = { onAppTrack() }; SpotifyApp.onState = { onAppState() } }

    /** The Spotify app played, paused or seeked. If its song isn't the one the Web API last named (a skip), ask now. */
    fun onAppState() {
        if (appTiming()) { isPlaying = SpotifyApp.playing; E.onSpUpdate(); return }
        if (E.src == "spotify" && S.b("aSession") && SpotifyApp.connected.value && SpotifyApp.title.isNotEmpty() && wall() - lastCatch > 5000) catchUp()
    }

    /** A new song started in the Spotify app. */
    fun onAppTrack() { if (E.src == "spotify" && S.b("aSession")) catchUp() }

    private var catchJob: Job? = null
    private var lastCatch = 0L
    /** Ask the Web API for the new song's details (its id), a few times until it names the song the Spotify app plays. */
    private fun catchUp() {
        lastCatch = wall()
        catchJob?.cancel()
        catchJob = Vibe.scope.launch {
            for (k in 0 until 4) {
                delay(if (k == 0) 250 else 900)
                if (E.src != "spotify" || wall() < backoff) return@launch
                poll(); delay(700)
                if (appTiming()) return@launch   // caught up
            }
        }
    }

    /** Manual resync: three fresh readings, the most exact one wins, snapped (no gliding). Returns how far it moved. */
    suspend fun resync(): Double? {
        if (appTiming()) { SpotifyApp.resync(); return 0.0 }
        val before = if (spState != null) curMs() else null; val id = spId
        spSlew = null; spSnap = true
        var best: Triple<Double, Double, Pair<Double, Boolean>>? = null
        try {
            for (k in 0 until 3) {
                val t0 = now(); val d = api("/me/player?additional_types=track,episode") as? JSONObject; val t1 = now()
                spRtt = t1 - t0; apply(d, t0 + spRtt / 2)
                val st = spState; if (st == null || spId != id) break
                if (best == null || spRtt < best.first) best = Triple(spRtt, spFetched, st.progress to st.playing)
                if (k < 2) delay(150)
            }
        } finally { spSnap = false }
        val st = spState
        if (best != null && st != null && spId == id) {
            st.progress = best.third.first; spFetched = best.second; st.playing = best.third.second; spSlew = null
            spSamples = if (st.playing) arrayListOf(Sample(st.progress - spFetched, best.first)) else ArrayList()
            if (before != null) return curMs() - before
        }
        return null
    }

    /* ================= controls ================= */

    private fun spxFrom(d: JSONObject?) {   // what Spotify says, unless the person just changed it here (the next poll can still be stale)
        if (now() < spxHold) return
        shuffle = d?.optBoolean("shuffle_state") == true; repeat = d?.optString("repeat_state")?.ifEmpty { null } ?: "off"
        val dis = d?.optJSONObject("actions")?.optJSONObject("disallows")
        disallows = dis?.keys()?.asSequence()?.filter { dis.optBoolean(it) }?.toSet() ?: emptySet()
        device = d?.optJSONObject("device")?.let(::deviceFrom)
        version++
    }

    private fun deviceFrom(o: JSONObject) = SpDevice(o.optString("id"), o.optString("name"), o.optString("type"),
        if (o.isNull("volume_percent")) null else o.optInt("volume_percent"), o.optBoolean("supports_volume", true), o.optBoolean("is_active"), o.optBoolean("is_restricted"))

    /** Control Spotify itself (the Web API needs Premium; the Spotify app on this phone doesn't). */
    suspend fun ctl(method: String, path: String, body: JSONObject? = null): Boolean {
        if (connected && cfg.spScope.isNotEmpty() && !cfg.spScope.contains("user-modify-playback-state")) { E.askScope(); return false }
        return try { api(path, method, body); Vibe.scope.launch { delay(450); poll() }; true }
        catch (e: SpotifyError) { if (e.scope) E.askScope() else E.toast(if (e.rate) "Spotify is busy, try again in a moment" else (e.message ?: "").removeSuffix("."), "warn"); false }
    }

    fun toggle() {
        val play = !spPlaying()
        if (appTiming() && SpotifyApp.canControl) { if (play) SpotifyApp.play() else SpotifyApp.pause(); return }
        Vibe.scope.launch {
            if (ctl("PUT", if (play) "/me/player/play" else "/me/player/pause")) spState?.let { st ->
                st.progress = curMs(); st.playing = play; spFetched = now(); spSlew = null; spSamples.clear(); isPlaying = play; E.onSpUpdate()
            }
        }
    }

    fun next() { if (appTiming() && SpotifyApp.canControl) SpotifyApp.next() else Vibe.scope.launch { ctl("POST", "/me/player/next") } }
    fun previous() { if (appTiming() && SpotifyApp.canControl) SpotifyApp.previous() else Vibe.scope.launch { ctl("POST", "/me/player/previous") } }

    fun seek(ms: Long) {
        if (appTiming() && SpotifyApp.canControl) { SpotifyApp.seek(ms); return }
        Vibe.scope.launch { if (ctl("PUT", "/me/player/seek?position_ms=$ms")) spState?.let { it.progress = ms.toDouble(); spFetched = now(); spSlew = null; spSamples.clear() } }
    }

    fun toggleShuffle() {
        if (spState == null) return
        val v = !shuffle; shuffle = v; spxHold = now() + 1500; version++
        Vibe.scope.launch { if (!ctl("PUT", "/me/player/shuffle?state=$v")) { shuffle = !v; version++ } }
    }

    fun cycleRepeat() {   // off → all → this song → off, as in Spotify
        if (spState == null) return
        val was = repeat; val v = when (was) { "off" -> "context"; "context" -> "track"; else -> "off" }
        repeat = v; spxHold = now() + 1500; version++
        Vibe.scope.launch { if (!ctl("PUT", "/me/player/repeat?state=$v")) { repeat = was; version++ } }
    }

    private var volJob: Job? = null
    fun setVolume(v0: Int, nowNow: Boolean = false) {
        val v = v0.coerceIn(0, 100)
        device = device?.copy(volume = v); spxHold = now() + 1500; version++
        volJob?.cancel(); volJob = Vibe.scope.launch { if (!nowNow) delay(180); ctl("PUT", "/me/player/volume?volume_percent=$v") }   // one request per pause in dragging
    }

    suspend fun devices(): List<SpDevice> {
        val a = (api("/me/player/devices") as? JSONObject)?.optJSONArray("devices") ?: JSONArray()
        return (0 until a.length()).map { deviceFrom(a.getJSONObject(it)) }
    }

    suspend fun transfer(d: SpDevice): Boolean = ctl("PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(d.id)).put("play", spPlaying()))

    // Liked Songs: Spotify's newer library endpoint first, the older one if this app can't use it
    private suspend fun lib(m: SongMeta, method: String): Any? {
        suspend fun old() = api("/me/tracks" + (if (method == "GET") "/contains" else "") + "?ids=" + URLEncoder.encode(m.id, "UTF-8"), method, raw = true) as Resp
        var r: Resp = if (spLibOld) old() else api("/me/library" + (if (method == "GET") "/contains" else "") + "?uris=" + URLEncoder.encode(m.uri, "UTF-8"), method, raw = true) as Resp
        if (!spLibOld && r.code in setOf(400, 404, 405, 410)) { spLibOld = true; r = old() }
        if (r.code == 403) throw SpotifyError("scope", scope = true)
        if (!r.ok) throw SpotifyError("Spotify error (HTTP ${r.code}).")
        return if (method == "GET") r.jsonArray() else null
    }

    fun likeCheck(m: SongMeta?) {
        val tok = ++likeTok; liked = null; version++
        if (m == null || m.ep || m.uri.isEmpty() || !hasScope("user-library-read")) return
        Vibe.scope.launch { runCatching { val j = lib(m, "GET") as? JSONArray; if (tok == likeTok && j != null) { liked = j.optBoolean(0); version++ } } }
    }

    fun toggleLike() {
        val m = meta ?: return; if (m.ep) return
        if (!hasScope("user-library-modify")) { E.askScope("To save songs to your Liked Songs"); return }
        val v = liked != true; liked = v; version++
        Vibe.scope.launch {
            try { lib(m, if (v) "PUT" else "DELETE") }
            catch (e: SpotifyError) { if (m == meta) { liked = !v; version++ }; if (e.scope) E.askScope("To save songs to your Liked Songs") else E.toast((e.message ?: "").removeSuffix("."), "warn") }
        }
    }

    /** Local files and other songs: the Spotify track id, found through your Spotify connection. */
    // one search per song: the lyrics sources ask for it at the same time
    private val ids = HashMap<String, kotlinx.coroutines.Deferred<String?>>()
    suspend fun idFor(m: SongMeta): String? {
        if (m.id.isNotEmpty()) return m.id
        if (!connected || m.title.isEmpty()) return null
        val key = (m.artist + "|" + m.title).lowercase()
        val d = ids.getOrPut(key) { Vibe.scope.async { runCatching {
            val q = "track:" + m.title + if (m.artist.isNotEmpty()) " artist:" + m.artist.split(',')[0] else ""
            val d = api("/search?type=track&limit=1&q=" + URLEncoder.encode(q, "UTF-8")) as? JSONObject
            d?.optJSONObject("tracks")?.optJSONArray("items")?.optJSONObject(0)?.optString("id")?.ifEmpty { null }
        }.getOrNull() } }
        val id = d.await()
        if (id == null) ids.remove(key)   // not found or failed: asked again next time
        return id
    }

    private var artistFor = ""; private var artistImg = ""
    suspend fun artistImage(id: String): String {
        if (id.isEmpty()) return ""
        if (artistFor == id) return artistImg
        artistImg = runCatching { ((api("/artists/$id") as? JSONObject)?.optJSONArray("images")?.optJSONObject(0)?.optString("url")) ?: "" }.getOrDefault("")
        artistFor = id
        return artistImg
    }
}
