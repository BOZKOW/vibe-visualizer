package io.github.bozkow.vibevisualizer.engine

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.clockLeft
import io.github.bozkow.vibevisualizer.core.fmtTime
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.core.wall
import io.github.bozkow.vibevisualizer.lyrics.SongMeta
import io.github.bozkow.vibevisualizer.lyrics.norm
import io.github.bozkow.vibevisualizer.ui.SettingsUi
import io.github.bozkow.vibevisualizer.media.Local
import io.github.bozkow.vibevisualizer.spotify.Spotify
import io.github.bozkow.vibevisualizer.spotify.SpotifyApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/** The app's state and its frame loop: what plays, the clock, the bars, the background, notices and problems. */
object E {
    /* ---- what plays ---- */
    var src by mutableStateOf("none")            // none | file | spotify
        internal set
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var status by mutableStateOf("")               // where the cover came from, "Spotify", "Searching for cover…"
    var trackVer by mutableIntStateOf(0)           // bumps on every new song (the title animates in)
    var coverTok = 0
        private set
    var curArtistId = ""
    var playingState by mutableStateOf(false)
        internal set

    /* ---- the screen ---- */
    var visible = false                            // the activity is on screen (or in picture-in-picture)
        private set
    var inPip by mutableStateOf(false)
    var screen by mutableStateOf("player")         // the music app's screens: player (Now Playing) | lyrics | viz
    var wide by mutableStateOf(false)               // side by side (landscape, tablets): the lyrics are always on screen
    var uiHidden by mutableStateOf(false)
    var wakeTap = false                            // this touch only brought the controls back
    var settingsPage by mutableStateOf<String?>(null)
    var wizard by mutableStateOf(false)
    var sheet by mutableStateOf<String?>(null)    // audio | net | devices
    var timeText by mutableStateOf("0:00 / 0:00")
    var seekFrac by mutableFloatStateOf(0f)
    var seeking = false
    var fpsText by mutableStateOf("")
    val barsTick = mutableLongStateOf(0)
    val bgTick = mutableLongStateOf(0)
    val sec = mutableIntStateOf(0)                 // ticks every second (countdowns)
    private var actRef: WeakReference<Activity>? = null
    val activity: Activity? get() = actRef?.get()

    /* ---- notices: cards (notify), short messages (toast), and problems shown where the lyrics go ---- */
    class Act(val label: String, val pri: Boolean = false, val fn: (() -> Unit)? = null)
    class Note(val key: String? = null, val kind: String = "", val title: String, val sub: String? = null, val msg: String? = null,
               val list: List<String> = emptyList(), val actions: List<Act> = listOf(Act("OK")), val ms: Long = 0) { val id = ++noteSeq }
    class Toast(val msg: String, val kind: String, val ms: Long) { val id = ++noteSeq }
    class Prob(val pr: Int, val k: String, val t: String, val s: String, val ok: Boolean = false)
    private var noteSeq = 0L
    val notes = mutableStateListOf<Note>()
    val toasts = mutableStateListOf<Toast>()
    var appProb by mutableStateOf<Prob?>(null)
    private var appProbAt = 0L

    fun notify(n: Note) {
        if (n.key != null) notes.removeAll { it.key == n.key }
        notes.add(0, n); while (notes.size > 3) notes.removeAt(notes.size - 1)
        if (n.ms > 0) Vibe.scope.launch { delay(n.ms); notes.remove(n) }
    }

    /** Errors and warnings aren't notifications: they show where the lyrics go (an error replaces them, a warning is
     *  a small line above). Everything else is a short message at the bottom. */
    fun toast(msg: String, kind: String = "err", ms: Long = 6500) {
        if (msg.isEmpty()) return
        if (kind == "err") Reports.report("notice", msg)
        if (kind == "err" || kind == "warn") {
            appProb = Prob(if (kind == "err") 60 else 30, if (kind == "err") "err" else "wait", if (kind == "err") "Something went wrong" else "Heads up", msg, ok = true)
            appProbAt = wall(); return
        }
        if (toasts.any { it.msg == msg }) return
        val t = Toast(msg, kind, ms); toasts.add(t); while (toasts.size > 4) toasts.removeAt(0)
        Vibe.scope.launch { delay(ms); toasts.remove(t) }
        haptic()
    }

    /** Everything that's wrong right now, most important first: what stops the most comes first, errors before waits. */
    fun problems(homeScreen: Boolean): List<Prob> {
        sec.intValue   // re-read every second (countdowns)
        val out = ArrayList<Prob>()
        val sp = Spotify.connected && (homeScreen || src == "spotify")
        if (!Net.online.value) out.add(Prob(100, "err", "You’re offline", "This phone has no internet connection. Lyrics, Spotify and Canvas come back by themselves when it’s back."))
        else {
            if (Server.set && Ping.serverDown) {
                val why = when {
                    Net.privateDns.value -> "Android’s Private DNS is on, and it hides your server’s Tailscale name. Turn it off (Settings → Connections → More connection settings → Private DNS → Off), or set Tailscale’s DNS there."
                    !Net.vpn.value -> "Tailscale isn’t connected on this phone. Open the Tailscale app and connect, then it comes back by itself."
                    else -> "Lyrics, Canvas and Spotify’s lyrics come from it. Check that the server is on and Tailscale is connected."
                }
                out.add(Prob(90, "err", "Your server can’t be reached", why))
            }
            if (src == "spotify" && !Spotify.connected) out.add(Prob(80, "err", "Spotify isn’t connected", "The Spotify login ended. Tap Connect Spotify to log in again."))
            else if (sp && Spotify.errMsg.isNotEmpty()) out.add(Prob(70, "err", "Spotify isn’t answering", Spotify.errMsg))
            if (sp && wall() < Spotify.backoff) out.add(Prob(40, "wait", "Spotify is making Vibe Visualizer wait",
                "It asked Spotify too often, so Spotify paused it. It carries on by itself in " + clockLeft((Spotify.backoff - wall()) / 1000.0) +
                    (if (homeScreen || out.any { it.k == "err" }) "." else "; the lyrics keep running meanwhile.")))
        }
        appProb?.let { p -> if (wall() - appProbAt < 10000 && out.none { it.s == p.s }) out.add(p) }   // one-off errors and warnings: 10 s or until OK
        return out.sortedByDescending { it.pr }
    }

    /* ---- the clock ---- */
    fun playing(): Boolean = when (src) { "file" -> Local.playing(); "spotify" -> Spotify.spPlaying(); else -> false }
    internal var clockOverride: Double? = null      // screenshot tests: a fixed point in the song
    fun curTime(): Double = clockOverride ?: when (src) {
        "spotify" -> min(Spotify.curMs(), Spotify.durationMs()) / 1000
        "file" -> Local.positionMs() / 1000
        else -> 0.0
    }
    fun curDur(): Double = when (src) { "spotify" -> Spotify.durationMs() / 1000; "file" -> Local.durationMs() / 1000; else -> 0.0 }

    fun reduceMotion(): Boolean = S.str("anim") == "off" || (S.str("anim") != "on" && systemAnimOff())
    private fun systemAnimOff() = runCatching { Settings.Global.getFloat(Vibe.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    /* ================= sources ================= */

    fun setSource(s: String) {
        if (s == "spotify") {
            if (!Spotify.connected) { spLogin(); return }
            if (src == "spotify") return
            Local.pause(); src = "spotify"; Spotify.start()
        } else { Spotify.stop(); src = s }
        updPlay(); poke()
    }

    fun netKick() = Ping.kick()

    fun spLogin() { activity?.let { Spotify.login(it) } ?: toast("Open the app to log in to Spotify.", "warn") }

    fun askScope(what: String = "To control playback") {
        notify(Note(key = "scope", kind = "warn", title = "$what, Spotify needs one more permission", msg = "Reconnect Spotify now?",
            actions = listOf(Act("Not now"), Act("Reconnect", pri = true) { spLogin() })))
    }

    fun spIdle() { title = "Nothing playing"; artist = "Play something in Spotify"; status = ""; Lyr.clear(""); CanvasVideo.clear(false); updPlay() }

    fun onSpUpdate() { updPlay(); CanvasVideo.syncPlay() }

    fun onSpTrack(m: SongMeta) {
        NextUp.fly(m)
        curArtistId = m.artistId
        Spotify.likeCheck(m)
        Audio.dropBars()   // the bars drop and grow back with the new song
        val tok = ++coverTok
        CanvasVideo.clear(true)
        title = m.title; artist = m.artist; status = "Spotify"; trackVer++
        Vibe.scope.launch {
            val ok = m.cover.isNotEmpty() && setCover(m.cover, "", tok)
            if (!ok && tok == coverTok) {
                if (S.b("online")) { status = "Searching for cover…"; if (!findCover(m.artist + " " + m.title, tok) && tok == coverTok) { status = "No cover found · using default"; resetCover() } }
                else resetCover()
            }
        }
        Lyr.load(m, tok)
        CanvasVideo.lookup(m, tok)
        updPlay()
    }

    /* ---- your own files ---- */
    fun openFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Vibe.scope.launch {
            val r = Files.sort(uris)
            r.lyrics.takeIf { it.isNotEmpty() }?.let { Files.addLyrics(it, r.audio.isEmpty() && it.size == 1) }
            r.images.firstOrNull()?.let { u -> val tok = ++coverTok; if (!setCover(u.toString(), "Custom cover", tok)) toast("That image could not be read.") }
            r.videos.firstOrNull()?.let { CanvasVideo.setLocal(it); toast("Canvas video loaded.", "ok", 2500) }
            if (r.audio.isNotEmpty()) { Spotify.stop(); src = "file"; Local.open(r.audio); poke() }
        }
    }

    /** A song of your own starts: title from the file name until its tags are read, then cover and lyrics. */
    fun onFileItem(uri: Uri, name: String) {
        curArtistId = ""
        val tok = ++coverTok
        CanvasVideo.clear(false); Audio.dropBars(); resetCover()
        val parts = Files.clean(name).split(Regex("\\s[-–]\\s"))
        title = if (parts.size > 1) parts.drop(1).joinToString(" - ") else Files.clean(name)
        artist = if (parts.size > 1) parts[0] else ""; status = ""; trackVer++
        Lyr.loading()
        Vibe.scope.launch {
            val tags = Files.tags(uri)
            if (tok != coverTok) return@launch
            tags.title?.let { title = it }; tags.artist?.let { artist = it }
            val meta = SongMeta(title = tags.title ?: title, artist = tags.artist ?: artist, album = tags.album ?: "", duration = tags.durationMs / 1000.0, file = name)
            Lyr.load(meta, tok)
            Local.setMeta(title, artist, tags.picture)
            if (tags.picture != null && setCoverBitmap(Images.decode(tags.picture), "Embedded cover", tok)) return@launch
            if (!S.b("online")) { status = "No cover · using default"; return@launch }
            status = "Searching for cover…"
            if (!findCover(if (meta.artist.isNotEmpty()) meta.artist + " " + meta.title else Files.clean(name), tok) && tok == coverTok) status = "No cover found · using default"
        }
        updPlay()
    }

    fun onFileState() { updPlay(); CanvasVideo.syncPlay(); poke() }

    /* ================= cover pipeline ================= */

    suspend fun setCover(url: String, label: String, tok: Int): Boolean {
        val bm = Images.load(url) ?: return false
        return setCoverBitmap(bm, label, tok)
    }

    fun setCoverBitmap(bm: android.graphics.Bitmap?, label: String, tok: Int): Boolean {
        if (bm == null || tok != coverTok) return false
        Visuals.setCover(bm)
        if (label.isNotEmpty() || src != "spotify") status = label
        if (S.bgStyle != "dynamic") Background.applyStill()
        return true
    }

    fun resetCover() { Visuals.setCover(null); if (S.bgStyle != "dynamic") Background.applyStill() }

    // Apple Music artwork via Apple's official iTunes Search API (no key needed)
    private suspend fun appleCover(q: String, tok: Int, want: SongMeta? = null): Boolean {
        try {
            val d = Net.getJson("https://itunes.apple.com/search?media=music&entity=song&limit=10&term=" + URLEncoder.encode(q, "UTF-8"))
            val a = d.optJSONArray("results") ?: return false
            var res = (0 until a.length()).map { a.getJSONObject(it) }
            if (want != null) {   // best match first: same title, then same artist
                val t = norm(want.title); val ar = norm(want.artist)
                fun score(r: JSONObject): Int { val n = norm(r.optString("trackName")); return (if (n == t) 4 else if (n.startsWith(t) || t.startsWith(n)) 2 else 0) + (if (ar.isNotEmpty() && norm(r.optString("artistName")).contains(ar)) 2 else 0) }
                res = res.map { score(it) to it }.filter { it.first >= 2 }.sortedByDescending { it.first }.map { it.second }.take(3)
            }
            for (r in res) {
                if (tok != coverTok) return true
                val u = r.optString("artworkUrl100").replace("100x100bb", "1000x1000bb")
                if (u.isNotEmpty() && setCover(u, "Found online · " + r.optString("artistName") + " – " + r.optString("trackName"), tok)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    suspend fun findCover(q: String, tok: Int): Boolean {
        if (appleCover(q, tok)) return true
        try {
            val d = Net.getJson("https://musicbrainz.org/ws/2/release/?fmt=json&limit=3&query=" + URLEncoder.encode(q, "UTF-8"))
            val a = d.optJSONArray("releases") ?: return false
            for (i in 0 until a.length()) {
                if (tok != coverTok) return true
                val r = a.getJSONObject(i)
                if (setCover("https://coverartarchive.org/release/" + r.optString("id") + "/front-500", "Found online · " + r.optString("title"), tok)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    /* ================= transport ================= */

    private fun updPlay() { val p = playing(); if (p != playingState) playingState = p }

    fun toggle() {
        haptic()
        when (src) {
            "spotify" -> Spotify.toggle()
            "file" -> Local.toggle()
            else -> FilePicker.pickAudio()
        }
    }

    fun prev() { haptic(); if (src == "spotify") Spotify.previous() else if (src == "file") Local.previous() }
    fun next() { haptic(); if (src == "spotify") Spotify.next() else if (src == "file") Local.next() }
    fun canNext() = src == "spotify" || (src == "file" && Local.hasNext())

    /** Jump to a time (seconds) in whatever is playing; used by the lyrics. */
    fun seekTo(sec: Double): Boolean {
        val s = max(0.0, sec)
        if (src == "file" && Local.durationMs() > 0) { Local.seek((s * 1000).toLong()); return true }
        if (src == "spotify" && (Spotify.spState != null || Spotify.appTiming())) { Spotify.seek((s * 1000).toLong()); return true }
        return false
    }

    fun seekFraction(f: Float) { val d = curDur(); if (d > 0) seekTo(f * d) }

    fun nudgeVol(d: Int) {
        if (src == "spotify") { Spotify.device?.volume?.let { Spotify.setVolume(it + d) }; return }
        S.set("volume", (S.i("volume") + d).coerceIn(0, 100))
    }

    /* ================= controls hide while music plays ================= */
    private var hideJob: Job? = null
    fun poke() {
        if (uiHidden) uiHidden = false
        hideJob?.cancel()
        // only the full-screen lyrics and visualizer hide their controls (as a music app does); Now Playing keeps them
        if (playing() && S.str("uiHide") != "never" && screen != "player" && !wide && settingsPage == null && sheet == null && !wizard)
            hideJob = Vibe.scope.launch { delay(2800); if (sheet == null && settingsPage == null && screen != "player") uiHidden = true }
    }

    fun show(s: String) { if (screen != s) { screen = s; haptic() }; poke() }
    fun lyricsVisible() = inPip || wide || screen == "lyrics"
    fun goHome() { settingsPage = null; screen = "player" }

    fun openSettings(page: String? = null) { settingsPage = page ?: SettingsUi.last; poke() }

    /* ================= the frame loop ================= */
    private var last = 0.0; private var fAcc = 0.0; private var fPrev = 0.0; private var lastUi = 0.0
    private var fc = 0; private var ft = 0.0
    private var wasActive = false
    private var secAt = 0L

    fun frame(t: Double) {
        Perf.tick(t)
        // Limit frame rate: time adds up between screen refreshes and a frame is drawn once a whole frame's worth has
        // passed, so 60 on a 120 Hz screen really gives 60
        val cap = S.str("fpscap").toIntOrNull() ?: 0
        val iv = 1000.0 / min(if (cap > 0) cap else 240, if (S.low) 30 else 240)
        fAcc += min(t - (if (fPrev == 0.0) t else fPrev), 100.0); fPrev = t
        if (fAcc < iv - .5) return
        fAcc = min(fAcc - iv, iv)
        val dt = min(t - (if (last == 0.0) t else last), 50.0); last = t
        if (wall() - secAt >= 1000) { secAt = wall(); sec.intValue++ }
        Audio.sample(dt, t); Audio.beatTick(t, dt)
        Background.tick(t, dt)
        Lyr.tick(t)
        Desync.tick(t)
        uiTick(t)
        if (Visuals.palDirty) Visuals.buildPal()
        val palMoving = Visuals.palTick(t)
        CanvasVideo.frame(t)
        val pl = playing()
        val active = pl || Audio.mxv > .003f || Audio.idleK > .005f || palMoving
        if (active || wasActive) barsTick.longValue++
        wasActive = active
        if (S.b("fps")) { fc++; if (t - ft > 500) { fpsText = "${Math.round(fc * 1000 / (t - ft))} fps"; fc = 0; ft = t } }
    }

    private fun uiTick(t: Double) {
        if (t - lastUi < 240) return
        lastUi = t
        NextUp.tick()
        updPlay()
        val d = curDur(); val c = curTime()
        val tt = fmtTime(c) + " / " + fmtTime(d)
        if (tt != timeText) timeText = tt
        if (!seeking) seekFrac = if (d > 0) (c / d).toFloat().coerceIn(0f, 1f) else 0f
        if (src == "file") Local.tickSession()
    }

    /* ================= the activity ================= */

    fun attach(a: Activity) { actRef = WeakReference(a) }

    fun onVisible(v: Boolean) {
        if (visible == v) return
        visible = v
        Audio.applyCapture()
        if (v) {
            SpotifyApp.refresh(Vibe.app)
            if (src == "spotify") Spotify.poll()
            Spotify.loginCheck()
            Ping.kick()
            Background.resume()
        }
    }

    private val vib by lazy { Vibe.app.getSystemService(android.os.Vibrator::class.java) }
    fun haptic() {
        if (!S.b("aHaptics")) return
        activity?.window?.decorView?.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /* ================= settings changed ================= */
    fun onSetting(k: String) {
        when (k) {
            "theme", "c1", "c2" -> Visuals.palDirty = true
            "bars" -> { Audio.setupBands(); Visuals.palDirty = true }
            "bgblur", "bri", "sat" -> { Visuals.bake(false); Background.applyStill() }
            "bgStyle" -> Background.applyStill()
            "low" -> { Audio.setupBands(); Visuals.palDirty = true; CanvasVideo.applyMode() }
            "uiHide" -> poke()
            "canvasOn", "cvNoArt" -> CanvasVideo.applyMode()
            "bridge", "aCap" -> Audio.applyCapture()
            "aSession" -> SpotifyApp.refresh(Vibe.app)
            "volume" -> Local.applyVolume()
            "ljoin", "lbgBr", "lsimple", "xRefSimple", "romMode", "lfont" -> Lyr.restyle()
            "lprov" -> Lyr.cacheClear()
            "tr", "trTo", "trSkip" -> Lyr.decorate()
            "lnotice" -> Lyr.noticeChanged()
            "lsize", "xFocus", "xBgPop", "lduet", "view", "credPos", "xRefCred", "lcredLast", "lminimal", "xKeep" -> Lyr.relayout()
        }
        if (k == "romMode") Lyr.decorate()
        barsTick.longValue++
    }

    /* ================= battery: offer Low performance mode when it's low ================= */
    fun batteryCheck() {
        val bm = Vibe.app.getSystemService(BatteryManager::class.java) ?: return
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (!bm.isCharging && lvl in 1..20) Perf.suggest("battery", lvl / 100.0)
        val pm = Vibe.app.getSystemService(PowerManager::class.java)
        if (pm?.isPowerSaveMode == true && !S.low) Perf.suggest("saver", 0.0)
    }

    fun shareIntent(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
}
