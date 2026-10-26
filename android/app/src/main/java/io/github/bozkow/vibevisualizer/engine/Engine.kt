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
import io.github.bozkow.vibevisualizer.lyrics.TrackNameCleaner
import io.github.bozkow.vibevisualizer.lyrics.SongMeta
import io.github.bozkow.vibevisualizer.lyrics.Match
import io.github.bozkow.vibevisualizer.lyrics.firstArtist
import io.github.bozkow.vibevisualizer.media.Local
import io.github.bozkow.vibevisualizer.media.MediaApps
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
    var src by mutableStateOf("none")            // none | file | spotify | media (another music app on this phone)
        internal set
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var year by mutableStateOf("")                 // when the song came out, if known
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
    var ambient by mutableStateOf(false)           // Ambient mode: untouched while music plays, the lyrics and the song on black
        private set
    var unlock: ((() -> Unit) -> Unit)? = null     // over the lock screen: asks to unlock, then goes on
    var settingsPage by mutableStateOf<String?>(null)
    var wizard by mutableStateOf(false)
    var sheet by mutableStateOf<String?>(null)    // audio | net | devices
    var timeText by mutableStateOf("0:00 / 0:00")
    var seekFrac by mutableFloatStateOf(0f)
    var seeking = false
        private set
    private var quietSeek = false                  // a seek started while the controls were hidden: they stay hidden
    var fpsText by mutableStateOf("")
    val barsTick = mutableLongStateOf(0)
    val bgTick = mutableLongStateOf(0)
    val sec = mutableIntStateOf(0)                 // ticks every second (countdowns)
    private var actRef: WeakReference<Activity>? = null
    val activity: Activity? get() = actRef?.get()

    /* ---- notices, the Android way: a snackbar at the bottom (one at a time, one action at most), a dialog only for a
       question you asked for, and problems as the empty state where the lyrics go (never on top of anything) ---- */
    class Act(val label: String, val pri: Boolean = false, val fn: (() -> Unit)? = null)
    class Snack(val msg: String, val kind: String = "ok", val action: Act? = null, val long: Boolean = false, val onDismiss: (() -> Unit)? = null) { val id = ++noteSeq }
    class Ask(val title: String, val msg: String, val actions: List<Act>)
    /** id: offline | server | spotify | wait (its icon); act: the one thing to do about it */
    class Prob(val pr: Int, val k: String, val id: String, val t: String, val s: String, val act: Act? = null)
    private var noteSeq = 0L
    val snacks = mutableStateListOf<Snack>()       // the first one is on screen, the others wait their turn
    var ask by mutableStateOf<Ask?>(null)

    fun snack(n: Snack) {
        if (snacks.any { it.msg == n.msg }) return
        snacks.add(n); while (snacks.size > 3) snacks.removeAt(1)
    }

    /** A short message at the bottom. Errors are saved in the error log on your server too. */
    fun toast(msg: String, kind: String = "err", @Suppress("UNUSED_PARAMETER") ms: Long = 0) {
        if (msg.isEmpty()) return
        if (kind == "err") Reports.report("notice", msg)
        snack(Snack(msg, kind))
    }

    /** "Copied": Android 13 and newer show their own confirmation. */
    fun copied(what: String) { if (android.os.Build.VERSION.SDK_INT < 33) toast("$what copied", "ok") }

    /** Everything that's wrong right now, most important first, in a few words each. */
    fun problems(homeScreen: Boolean): List<Prob> {
        sec.intValue   // re-read every second (countdowns)
        val out = ArrayList<Prob>()
        val sp = Spotify.connected && (homeScreen || src == "spotify")
        if (!Net.online.value) out.add(Prob(100, "err", "offline", "No internet", "Lyrics and Spotify come back when you’re online."))
        else {
            if (Server.set && Ping.serverDown) out.add(when {
                Net.privateDns.value -> Prob(90, "err", "server", "Can’t reach your server", "Private DNS is on, and it hides Tailscale names.",
                    Act("Turn it off") { runCatching { activity?.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } })
                !Net.vpn.value -> Prob(90, "err", "server", "Can’t reach your server", "Tailscale isn’t connected.", Act("Open Tailscale") { openTailscale() })
                else -> Prob(90, "err", "server", "Can’t reach your server", "Check that it’s on.", Act("Try again") { Ping.kick() })
            })
            if (src == "spotify" && !Spotify.connected) out.add(Prob(80, "err", "spotify", "Spotify isn’t connected", "Log in again to follow what you play.", Act("Connect") { spLogin() }))
            else if (sp && Spotify.errMsg.isNotEmpty()) out.add(Prob(70, "err", "spotify", "Spotify isn’t answering", Spotify.errMsg.substringBefore(". ").take(90), Act("Try again") { Spotify.poll() }))
            if (sp && wall() < Spotify.backoff) out.add(Prob(40, "wait", "wait", "Spotify paused requests", "Back in " + clockLeft((Spotify.backoff - wall()) / 1000.0) + (if (homeScreen) "" else " · the lyrics keep running")))
        }
        return out.sortedByDescending { it.pr }
    }

    /** The most important problem, in a few words, for the header. */
    fun statusLine(): String? {
        val p = problems(false).firstOrNull() ?: return null
        return if (p.id == "wait") "Spotify paused · " + p.s.substringBefore(" ·").lowercase() else p.t
    }

    private fun openTailscale() {
        val a = activity ?: return
        val i = a.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
        runCatching { a.startActivity(i ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn"))) }
    }

    /* ---- the clock ---- */
    fun playing(): Boolean = when (src) { "file" -> Local.playing(); "spotify" -> Spotify.spPlaying(); "media" -> MediaApps.playing; else -> false }
    internal var clockOverride: Double? = null      // screenshot tests: a fixed point in the song
    fun curTime(): Double = clockOverride ?: when (src) {
        "spotify" -> min(Spotify.curMs(), Spotify.durationMs()) / 1000
        "file" -> Local.positionMs() / 1000
        "media" -> (MediaApps.positionMs() ?: 0.0) / 1000
        else -> 0.0
    }
    fun curDur(): Double = when (src) { "spotify" -> Spotify.durationMs() / 1000; "file" -> Local.durationMs() / 1000; "media" -> MediaApps.durMs / 1000.0; else -> 0.0 }

    fun reduceMotion(): Boolean = S.str("anim") == "off" || (S.str("anim") != "on" && systemAnimOff())
    private fun systemAnimOff() = runCatching { Settings.Global.getFloat(Vibe.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    /* ================= sources ================= */

    fun setSource(s: String) {
        if (s == "spotify") {
            if (!Spotify.connected) { spLogin(); return }
            if (src == "spotify") return
            Local.pause(); src = "spotify"; Spotify.start()
        } else if (s == "media") {
            if (src == "media") return
            Spotify.stop(); Local.pause(); src = "media"; onMediaTrack(force = true)
        } else { Spotify.stop(); src = s }
        updPlay(); poke()
    }

    /* ---- another music app (YouTube Music, SoundCloud…) ---- */

    /** Another music app started playing: followed, unless something here already plays (your files, or Spotify). */
    fun onMediaState() {
        if (src == "media") { updPlay(); poke(); if (MediaApps.connected.value.not()) { title = "Nothing playing"; artist = "Play something in a music app"; resetCover(); Lyr.clear("") }; return }
        if (!S.b("aOtherApps") || !MediaApps.playing || MediaApps.title.isEmpty()) return
        val busy = (src == "file" && Local.playing()) || (src == "spotify" && Spotify.spPlaying())
        if (!busy) setSource("media")
    }

    /** A new song in the app followed: its name (cleaned of "(Official Video)" and the like), its cover (or one
     *  found online), and its lyrics found by name and length. No Canvas: that needs a Spotify song. */
    fun onMediaTrack(force: Boolean = false) {
        if (src != "media") return
        val t0 = MediaApps.title; if (t0.isEmpty()) return
        val tn = TrackNameCleaner.clean(t0, MediaApps.artist)
        val nt = tn.title.ifEmpty { t0 }; val na = tn.artist.ifEmpty { MediaApps.artist }
        if (!force && nt == title && na == artist) return
        curArtistId = ""
        val tok = ++coverTok
        CanvasVideo.clear(false); Audio.dropBars()
        title = nt; artist = na; year = ""; status = ""; trackVer++
        val meta = SongMeta(title = nt, artist = na, album = MediaApps.album, duration = MediaApps.durMs / 1000.0)
        Lyr.load(meta, tok)
        mediaArt = setCoverBitmap(MediaApps.cover(), "From " + MediaApps.appName(), tok)
        if (!mediaArt) {
            resetCover()
            if (S.b("online")) Vibe.scope.launch { status = "Searching for cover…"; if (!findCover(meta, tok) && tok == coverTok) status = "No cover found · using default" }
        }
        updPlay()
    }
    private var mediaArt = false

    /** The app's details changed without a new song: its cover often comes a moment after the name. */
    fun onMediaMeta() {
        if (src != "media" || mediaArt) return
        MediaApps.cover()?.let { mediaArt = setCoverBitmap(it, "From " + MediaApps.appName(), coverTok, quiet = false) }
    }

    fun netKick() = Ping.kick()

    fun spLogin() { activity?.let { Spotify.login(it) } ?: toast("Open the app to log in to Spotify", "warn") }

    fun askScope(what: String = "To control playback") {
        ask = Ask("Reconnect Spotify?", "$what, Spotify needs one more permission. You’ll log in again in the browser.", listOf(Act("Not now"), Act("Reconnect", pri = true) { spLogin() }))
    }

    fun spIdle() { title = "Nothing playing"; artist = "Play something in Spotify"; year = ""; status = ""; Lyr.clear(""); CanvasVideo.clear(false); updPlay() }

    fun onSpUpdate() { updPlay(); CanvasVideo.syncPlay() }

    /** The Spotify app on this phone changed song: its name and cover show at once, without waiting for the Web API
     *  to name it (which brings its id, and with it the lyrics and the Canvas, a moment later). */
    private var appShown = ""; private var appArt = false
    fun onAppSong(t: String, a: String, art: android.graphics.Bitmap?) {
        if (t.isEmpty() || t == title || src != "spotify") return
        val tok = ++coverTok
        Audio.dropBars(); CanvasVideo.clear(true); Lyr.loading()
        title = t; artist = a; year = ""; status = "Spotify"; trackVer++
        appShown = t
        appArt = art != null && setCoverBitmap(art, "From the Spotify app", tok)
        if (!appArt) resetCover()
    }

    fun onSpTrack(m: SongMeta) {
        curArtistId = m.artistId
        Spotify.likeCheck(m)
        // already showing from the Spotify app: no second drop of the bars, no title animating in again, and the cover
        // only gets sharper
        val same = appShown.isNotEmpty() && appShown == SpotifyApp.title && SpotifyApp.matches(m.title, m.duration * 1000)
        val quiet = same && appArt
        appShown = ""; appArt = false
        if (!same) Audio.dropBars()   // the bars drop and grow back with the new song
        val tok = ++coverTok
        CanvasVideo.clear(true)
        title = m.title; artist = m.artist; year = m.year; status = "Spotify"; if (!same) trackVer++
        Vibe.scope.launch {
            // Spotify's cover; once more past the cache if it doesn't load, then its smaller size
            var ok = m.cover.isNotEmpty() && setCover(m.cover, "", tok, quiet = quiet)
            if (!ok && tok == coverTok && m.cover.isNotEmpty()) { delay(500); ok = setCover(m.cover, "", tok, fresh = true) }
            if (!ok && tok == coverTok && m.coverSmall.isNotEmpty()) ok = setCover(m.coverSmall, "", tok, fresh = true)
            if (tok == coverTok) coverNote = if (ok) "from Spotify" else if (m.cover.isEmpty()) "Spotify gave none" else "Spotify’s didn’t load: " + Images.lastError
            // no cover from Spotify (or it didn't load): the Spotify app on this phone shows one for the same song
            if (!ok && tok == coverTok) SpotifyApp.artFor(m.title, m.duration * 1000)?.let { ok = setCoverBitmap(it, "From the Spotify app", tok); if (ok) coverNote += " · from the Spotify app" }
            if (!ok && tok == coverTok) {
                if (S.b("online")) { status = "Searching for cover…"; ok = findCover(m, tok); if (!ok && tok == coverTok) { status = "No cover found · using default"; resetCover() } }
                else resetCover()
                if (tok == coverTok) coverNote += if (ok) " · found online" else " · none online"
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
            // the song's own token: a new one would also cancel its lyrics while they're still being found
            r.images.firstOrNull()?.let { u -> if (!setCover(u.toString(), "Custom cover", coverTok)) toast("That image couldn’t be read", "warn") }
            r.videos.firstOrNull()?.let { CanvasVideo.setLocal(it) }
            if (r.audio.isNotEmpty()) { Spotify.stop(); src = "file"; Local.open(r.audio); poke() }
        }
    }

    /** A song of your own starts: title from the file name until its tags are read, then cover and lyrics. */
    fun onFileItem(uri: Uri, name: String) {
        curArtistId = ""
        val tok = ++coverTok
        CanvasVideo.clear(false); Audio.dropBars(); resetCover()
        // "03 - Artist - Song (Official Video).mp3" → Song by Artist (Spicy Lyrics Mobile's name cleaner)
        val fn = TrackNameCleaner.clean(name, "")
        title = fn.title.ifEmpty { Files.clean(name) }; artist = fn.artist; year = ""; status = ""; trackVer++
        Lyr.loading()
        Vibe.scope.launch {
            val tags = Files.tags(uri)
            if (tok != coverTok) return@launch
            if (tags.title != null || tags.artist != null) {   // tags can be a file name too, or name no artist
                val tn = TrackNameCleaner.clean(tags.title ?: name, tags.artist ?: "")
                title = tn.title.ifEmpty { title }; artist = tn.artist.ifEmpty { artist }
            }
            year = tags.year ?: ""
            val meta = SongMeta(title = title, artist = artist, album = tags.album ?: "", duration = tags.durationMs / 1000.0, file = name, year = year)
            Lyr.load(meta, tok)
            Local.setMeta(title, artist, tags.picture)
            if (tags.picture != null && setCoverBitmap(Images.decode(tags.picture), "Embedded cover", tok)) return@launch
            // Android's own album art for the file: its picture, or a cover.jpg / folder.jpg next to it
            Files.androidArt(uri)?.let { if (tok == coverTok && setCoverBitmap(it, "From this phone", tok)) return@launch }
            if (tok != coverTok) return@launch
            if (!S.b("online")) { status = "No cover · using default"; return@launch }
            status = "Searching for cover…"
            if (!findCover(meta.copy(title = meta.title.ifEmpty { Files.clean(name) }), tok) && tok == coverTok) status = "No cover found · using default"
        }
        updPlay()
    }

    fun onFileState() { updPlay(); CanvasVideo.syncPlay(); poke() }

    /* ================= cover pipeline ================= */

    suspend fun setCover(url: String, label: String, tok: Int, fresh: Boolean = false, quiet: Boolean = false): Boolean {
        val bm = Images.load(url, fresh = fresh) ?: return false
        return setCoverBitmap(bm, label, tok, quiet)
    }

    /** Where this song's cover came from, or why none did (Settings → Help & about → debug info). */
    var coverNote = ""; private set

    fun setCoverBitmap(bm: android.graphics.Bitmap?, label: String, tok: Int, quiet: Boolean = false): Boolean {
        if (bm == null || tok != coverTok) return false
        Visuals.setCover(bm, quiet)
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
            if (want != null) {   // only the same song: its title (with or without a version note) and, when known, its artist
                val ar = Match.key(firstArtist(want.artist))
                fun titleOk(r: JSONObject) = Match.sameTitle(r.optString("trackName"), want.title) || Match.sameTitle(want.title, r.optString("trackName"))
                fun artistOk(r: JSONObject) = ar.isEmpty() || Match.key(r.optString("artistName")).contains(ar)
                fun albumOk(r: JSONObject) = want.album.isNotEmpty() && Match.key(r.optString("collectionName")) == Match.key(want.album)
                res = res.filter { titleOk(it) && artistOk(it) }.sortedByDescending { albumOk(it) }.take(3)
            }
            for (r in res) {
                if (tok != coverTok) return true
                val u = r.optString("artworkUrl100").replace("100x100bb", "1000x1000bb")
                if (u.isNotEmpty() && setCover(u, "Found online · " + r.optString("artistName") + " – " + r.optString("trackName"), tok)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    /** A cover found online, only when it's this song's: the first search result used to be taken as it came, so
     *  another song's cover often showed. */
    suspend fun findCover(want: SongMeta, tok: Int): Boolean {
        if (want.title.isEmpty()) return false
        if (appleCover((if (want.artist.isNotEmpty()) firstArtist(want.artist) + " " else "") + want.title, tok, want)) return true
        // MusicBrainz by the album, when there is one (a release is an album, not a song)
        if (want.album.isEmpty() || want.artist.isEmpty()) return false
        try {
            val q = "release:\"" + want.album.replace("\"", "") + "\" AND artist:\"" + firstArtist(want.artist).replace("\"", "") + "\""
            val d = Net.getJson("https://musicbrainz.org/ws/2/release/?fmt=json&limit=5&query=" + URLEncoder.encode(q, "UTF-8"))
            val a = d.optJSONArray("releases") ?: return false
            val ar = Match.key(firstArtist(want.artist))
            for (i in 0 until a.length()) {
                if (tok != coverTok) return true
                val r = a.getJSONObject(i)
                val credit = r.optJSONArray("artist-credit")?.let { c -> (0 until c.length()).joinToString(" ") { c.optJSONObject(it)?.optString("name") ?: "" } } ?: ""
                if (Match.key(r.optString("title")) != Match.key(want.album) || !Match.key(credit).contains(ar)) continue
                if (setCover("https://coverartarchive.org/release/" + r.optString("id") + "/front-500", "Found online · " + r.optString("title"), tok)) return true
            }
        } catch (_: Exception) {}
        return false
    }

    /* ================= transport ================= */

    private fun updPlay() { val p = playing(); if (p != playingState) { playingState = p; activeAt = wall() } }

    fun toggle() {
        haptic()
        when (src) {
            "spotify" -> Spotify.toggle()
            "file" -> Local.toggle()
            "media" -> if (MediaApps.playing) MediaApps.pause() else MediaApps.play()
            else -> FilePicker.pickAudio()
        }
    }

    fun prev() { haptic(); when (src) { "spotify" -> Spotify.previous(); "file" -> Local.previous(); "media" -> MediaApps.previous() } }
    fun next() { haptic(); when (src) { "spotify" -> Spotify.next(); "file" -> Local.next(); "media" -> MediaApps.next() } }
    fun canNext() = src == "spotify" || src == "media" || (src == "file" && Local.hasNext())

    /** Jump to a time (seconds) in whatever is playing; used by the lyrics. */
    fun seekTo(sec: Double): Boolean {
        val s = max(0.0, sec)
        if (src == "file" && Local.durationMs() > 0) { Local.seek((s * 1000).toLong()); return true }
        if (src == "spotify" && (Spotify.spState != null || Spotify.appTiming())) { Spotify.seek((s * 1000).toLong()); return true }
        if (src == "media" && MediaApps.durMs > 0) { MediaApps.seek((s * 1000).toLong()); return true }
        return false
    }

    fun seekFraction(f: Float) { val d = curDur(); if (d > 0) seekTo(f * d) }

    fun nudgeVol(d: Int) {
        if (src == "spotify") { Spotify.device?.volume?.let { Spotify.setVolume(it + d) }; return }
        S.set("volume", (S.i("volume") + d).coerceIn(0, 100))
    }

    /* ================= controls hide while music plays ================= */
    private var bakeJob: Job? = null
    private var activeAt = 0L   // the last touch or play / pause: the frame loop slows down a while after
    /** Paused and untouched for a few seconds: nothing on screen moves much, 30 frames a second are enough. */
    fun idle() = !playingState && wall() - activeAt > 3000 && sheet == null

    fun poke() {
        activeAt = wall()
        if (uiHidden) uiHidden = false
        if (ambient) { ambient = false; CanvasVideo.syncPlay() }
    }

    /** Ambient mode may dim the screen: on, music plays, nothing's open, the app is on screen (not in a small window). */
    private fun canAmbient() = S.b("aAmbient") && playingState && settingsPage == null && sheet == null && !wizard && !seeking && !inPip && visible

    /** The controls may hide: music plays, nothing's open over the player, nobody's dragging the seek bar. */
    private fun canHide() = playingState && S.str("uiHide") != "never" && settingsPage == null && sheet == null && !wizard && (!seeking || quietSeek)

    /** The seek bar is dragged (or let go). Touched while the controls are hidden, it only seeks: they stay away. */
    fun seekDrag(on: Boolean) {
        if (on && !seeking) quietSeek = uiHidden
        seeking = on
        if (quietSeek) activeAt = wall() else poke()
    }

    /** Checked a few times a second (it used to be decided only at a touch, so when the music started later, they never
     *  hid): three seconds untouched while music plays and they go; paused or with something open, they're back. */
    private fun hideTick() {
        val amb = canAmbient()
        if (!ambient && amb && wall() - activeAt > (S.str("aAmbientSec").toIntOrNull() ?: 30) * 1000L) { ambient = true; CanvasVideo.syncPlay() }   // the Canvas rests
        else if (ambient && !amb) { ambient = false; CanvasVideo.syncPlay() }
        val can = canHide() || ambient
        if (!uiHidden && can && wall() - activeAt > (S.str("aHideSec").toIntOrNull() ?: 3) * 1000L) uiHidden = true
        else if (uiHidden && !can) uiHidden = false
    }

    fun show(s: String) { if (screen != s) { screen = s; haptic() }; poke() }
    fun lyricsVisible() = inPip || (ambient && S.b("aAmbLyrics")) || ((wide || screen == "lyrics") && settingsPage == null && !wizard)   // they go on behind Settings' blur
    fun goHome() { settingsPage = null; screen = "player" }

    /** Settings, on [page], or on the list of pages to pick from ("" while it's open on the list). */
    fun openSettings(page: String? = null) = unlocked { settingsPage = page ?: ""; poke() }

    /** A sheet over the player (devices, audio): over the lock screen, once you've unlocked. */
    fun openSheet(s: String) = unlocked { sheet = s }

    /** Settings and the menus aren't for whoever picks up a locked phone: over the lock screen they ask to unlock first. */
    private fun unlocked(go: () -> Unit) { unlock?.invoke(go) ?: go() }

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
        // Settings over the player (it isn't drawn then): what it would show is worked out about 20 times a second,
        // not at the screen's rate, so Settings get the phone to themselves
        val under = settingsPage != null
        slowDt += dt
        val step = !under || t - slowAt >= 50
        Audio.sample(dt, t); Audio.beatTick(t, dt)
        if (step) { slowAt = t; Background.tick(t, slowDt); slowDt = 0.0; Lyr.tick(t) }
        Desync.tick(t)
        uiTick(t)
        if (Visuals.palDirty) Visuals.buildPal()
        val palMoving = Visuals.palTick(t)
        CanvasVideo.frame(t)
        val pl = playing()
        val active = pl || Audio.mxv > .003f || Audio.idleK > .005f || palMoving
        if ((active || wasActive) && step) barsTick.longValue++
        wasActive = active
        if (S.b("fps")) { fc++; if (t - ft > 500) { fpsText = "${Math.round(fc * 1000 / (t - ft))} fps"; fc = 0; ft = t } }
    }

    private var slowAt = 0.0; private var slowDt = 0.0

    private fun uiTick(t: Double) {
        if (t - lastUi < 240) return
        lastUi = t
        updPlay()
        val d = curDur(); val c = curTime()
        val tt = fmtTime(c) + " / " + fmtTime(d)
        if (tt != timeText) timeText = tt
        if (!seeking) seekFrac = if (d > 0) (c / d).toFloat().coerceIn(0f, 1f) else 0f
        if (src == "file") Local.tickSession()
        hideTick()
    }

    /* ================= the activity ================= */

    fun attach(a: Activity) { actRef = WeakReference(a) }

    fun onVisible(v: Boolean) {
        if (visible == v) return
        visible = v
        Audio.applyCapture()
        if (v) {
            SpotifyApp.refresh(Vibe.app); MediaApps.refresh(Vibe.app)
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
        if (k.startsWith("aWg") || k == "lfont") io.github.bozkow.vibevisualizer.widget.Widgets.refresh()
        when (k) {
            "theme", "c1", "c2" -> Visuals.palDirty = true
            "bars" -> { Audio.setupBands(); Visuals.palDirty = true }
            "bgblur", "bri", "sat" -> { bakeJob?.cancel(); bakeJob = Vibe.scope.launch { delay(150); Visuals.bake(false); Background.applyStill() } }   // once the slider rests
            "bgStyle" -> Background.applyStill()
            "low" -> { Audio.setupBands(); Visuals.palDirty = true; CanvasVideo.applyMode(); Perf.lowByHand() }   // (Perf sets it back to "auto" when Power saving did it)
            "aSaverLow" -> if (S.b("aSaverLow")) Perf.saverCheck(force = true)
            // Refined credits are the credits under the lyrics: turning one on turns on the other (they did nothing
            // with the credits in the corner)
            "xRefCred" -> { if (S.b("xRefCred") && S.credPos != "lyrics") S.set("credPos", "lyrics"); Lyr.relayout() }
            "credPos" -> { if (S.credPos == "lyrics" && !S.b("xRefCred")) S.set("xRefCred", true); Lyr.relayout() }
            "uiHide" -> poke()
            "canvasOn", "cvNoArt" -> {
                CanvasVideo.applyMode()
                // turned on during a song: look for its Canvas now, not from the next song
                if (k == "canvasOn" && S.canvasOn && src == "spotify" && !CanvasVideo.active) Spotify.meta?.let { CanvasVideo.lookup(it, coverTok) }
            }
            "bridge", "aCap" -> Audio.applyCapture()
            "aSession" -> SpotifyApp.refresh(Vibe.app)
            "volume" -> Local.applyVolume()
            "ljoin", "lbgBr", "lsimple", "xRefSimple", "romMode", "lfont" -> Lyr.restyle()
            "lprov" -> Lyr.cacheClear()
            "tr", "trTo", "trSkip", "aTrSvc" -> Lyr.decorate()
            "lnotice" -> Lyr.noticeChanged()
            "lsize", "xFocus", "xBgPop", "lduet", "view", "credPos", "xRefCred", "lcredLast", "lminimal", "xKeep" -> Lyr.relayout()
        }
        if (k == "romMode") Lyr.decorate()
        if (k == "lsimple" || k == "xRefSimple") Lyr.relayout()   // Background Vocal Pop-Out depends on them: the rows change height
        barsTick.longValue++
    }

    /* ================= closing the app ================= */
    private var quitting = false
    /** The app was closed (swiped away from Recents, or its small window dismissed): your settings are saved, the
     *  music, the bars' listening, the Canvas and the Spotify app's link stop, and the process ends, instead of going
     *  on in the background. Android keeps notification access bound to a running app, so that link lets go first
     *  (it's asked for again when the app opens). */
    fun quit() {
        if (quitting) return
        quitting = true
        Vibe.settings.flush()
        runCatching { Spotify.stop() }; runCatching { SpotifyApp.stop() }; runCatching { Audio.stopAll() }; runCatching { CanvasVideo.release() }
        runCatching { io.github.bozkow.vibevisualizer.spotify.SessionListener.current?.requestUnbind() }
        runCatching { Vibe.app.stopService(Intent(Vibe.app, io.github.bozkow.vibevisualizer.media.PlaybackService::class.java)) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
    }

    /* ================= battery: offer Low performance mode when it's low ================= */
    fun batteryCheck() {
        val bm = Vibe.app.getSystemService(BatteryManager::class.java) ?: return
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (!bm.isCharging && lvl in 1..20) Perf.suggest("battery", lvl / 100.0)
        val pm = Vibe.app.getSystemService(PowerManager::class.java)
        if (pm?.isPowerSaveMode == true && !S.low && !S.b("aSaverLow")) Perf.suggest("saver", 0.0)   // with "With Power saving" on, it's turned on instead
    }

    fun shareIntent(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
}
