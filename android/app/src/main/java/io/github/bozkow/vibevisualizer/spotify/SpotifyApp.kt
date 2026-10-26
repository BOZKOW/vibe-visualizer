package io.github.bozkow.vibevisualizer.spotify

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat
import io.github.bozkow.vibevisualizer.media.LyricClock

/** Only here so Android lists Vibe Visualizer under notification access: with it, the app can see the media
 *  sessions of the music apps on this phone, the Spotify app's and the others' (what plays, where in the song, and
 *  their controls). No notification is read. */
class SessionListener : NotificationListenerService() {
    override fun onListenerConnected() { current = this; SpotifyApp.refresh(this); io.github.bozkow.vibevisualizer.media.MediaApps.refresh(this) }
    override fun onListenerDisconnected() { if (current === this) current = null }
    companion object { var current: SessionListener? = null; private set }
}

/** A music app's player on this phone, through its media session (with notification access): what plays, where in
 *  the song (Spicy Lyrics Mobile's lyric clock), the cover, and play / pause / skip / seek. [accept] says which apps'
 *  sessions it follows. [SpotifyApp] follows the Spotify app, [io.github.bozkow.vibevisualizer.media.MediaApps] any
 *  other music app. */
open class SessionFollower(private val accept: (String) -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var comp: ComponentName? = null
    private var ctrl: MediaController? = null
    private var listening = false

    val connected = mutableStateOf(false)   // notification access given and such an app has a session
    var pkg = ""; private set               // the app followed
    var title = ""; var artist = ""; var album = ""; var durMs = 0L
        private set
    protected var art: android.graphics.Bitmap? = null   // the cover the app shows for its song
    private var state: PlaybackState? = null
    // where the song is, kept from the app's position reports (Spicy Lyrics Mobile's lyric clock): jitter under a
    // frame doesn't move the lyrics, and a stale pause report doesn't throw them back
    private val clock = LyricClock(SystemClock.elapsedRealtime())
    private var awaitingReport = false   // a new song whose first report hasn't come yet
    private var resyncNext = false       // "Resync": the next playing report is taken outright
    private var lastReportAt = 0L

    var onTrack: (() -> Unit)? = null       // a new song started in the app
    var onState: (() -> Unit)? = null       // played, paused or seeked
    var onMeta: (() -> Unit)? = null        // the details changed (the cover often comes after the name)

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) { readMeta(m, true) }
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            state = s; report(s); onState?.invoke()
            if (!active(s)) { main.removeCallbacks(recheck); main.postDelayed(recheck, STICK_MS + 50) }   // another session may play on
        }
        override fun onSessionDestroyed() { attach(null) }
    }

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        SpotifyApp.access.value = SpotifyApp.hasAccess(app)
        if (!SpotifyApp.access.value) { stop(); return }
        if (listening) { runCatching { pick(msm?.getActiveSessions(comp)) }; return }
        msm = app.getSystemService(MediaSessionManager::class.java)
        comp = ComponentName(app, SessionListener::class.java)
        runCatching {
            msm!!.addOnActiveSessionsChangedListener(sessions, comp, main)
            listening = true
            pick(msm!!.getActiveSessions(comp))
        }
    }

    fun stop() {
        if (listening) runCatching { msm?.removeOnActiveSessionsChangedListener(sessions) }
        listening = false; attach(null)
    }

    /* The session followed is kept while it plays or is between states, and for 2 s after it stops: playing on a
       Spotify Connect speaker, the phone's Spotify session drops out now and then while another one for the same
       speaker plays on, and each switch threw the lyrics back to the start (Spicy Lyrics Mobile's SessionStickiness). */
    private val STICK_MS = 2000L
    private var stoppedAt = 0L   // when the followed session stopped playing (0: it plays)
    private val recheck = Runnable { runCatching { pick(msm?.getActiveSessions(comp)) } }

    private fun active(s: PlaybackState?) = when (s?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    private fun pick(list: List<MediaController>?) {
        val sp = list.orEmpty().filter { accept(it.packageName) }
        val now = SystemClock.elapsedRealtime()
        val cur = sp.firstOrNull { it.sessionToken == ctrl?.sessionToken }
        if (cur != null) {
            if (active(cur.playbackState)) { stoppedAt = 0; return }
            if (stoppedAt == 0L) stoppedAt = now
            val left = STICK_MS - (now - stoppedAt)
            if (left > 0) { main.removeCallbacks(recheck); main.postDelayed(recheck, left + 50); return }
        }
        val best = sp.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sp.firstOrNull()
        if (cur != null && best?.playbackState?.state != PlaybackState.STATE_PLAYING) return   // paused, and nothing else plays: stay
        stoppedAt = 0
        if (best?.sessionToken != ctrl?.sessionToken) attach(best)
    }

    private fun attach(c: MediaController?) {
        ctrl?.let { runCatching { it.unregisterCallback(cb) } }
        ctrl = c; stoppedAt = 0; main.removeCallbacks(recheck); pkg = c?.packageName ?: ""
        clock.reset(SystemClock.elapsedRealtime()); awaitingReport = false; resyncNext = false
        if (c == null) { connected.value = false; state = null; title = ""; artist = ""; album = ""; durMs = 0; art = null; onState?.invoke(); return }
        c.registerCallback(cb, main)
        connected.value = true
        state = c.playbackState
        readMeta(c.metadata, false)
        report(state, force = true, snap = true)
        onState?.invoke()
    }

    private fun readMeta(m: MediaMetadata?, notify: Boolean) {
        val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val changed = t != title
        title = t
        artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        durMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (notify && changed && t.isNotEmpty()) { report(state, force = true, trackChanged = true); onTrack?.invoke() }
        if (notify) onMeta?.invoke()
    }

    /** A position report into the clock. force: take it even if it isn't new; snap: move the clock to it outright
     *  rather than count it as a vote. (Spicy Lyrics Mobile's reconcileClock.) */
    private fun report(s: PlaybackState?, force: Boolean = false, trackChanged: Boolean = false, snap: Boolean = trackChanged) {
        if (s == null || s.position < 0) return
        val playing = s.state == PlaybackState.STATE_PLAYING
        // Spotify reports buffering at its end position about a second before the next song starts: stopping the
        // clock there would freeze a line that runs to the end, so it carries on until the next song's report
        if (!trackChanged && s.state == PlaybackState.STATE_BUFFERING && clock.isPlaying && durMs > 0 && s.position >= durMs - 2000) return
        val relayed = ctrl?.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        val r = LyricClock.Report(s.state, playing, s.position, s.lastPositionUpdateTime, s.playbackSpeed, relayed)
        val fresh = clock.isFresh(r); val now = SystemClock.elapsedRealtime()
        if (trackChanged && !fresh && now - lastReportAt > 1000) {
            // the report we have is the previous song's position: the new song starts from 0 until the app reports
            clock.startTrack(now, s.playbackSpeed, playing); awaitingReport = true; return
        }
        if (!force && !fresh) return
        val resync = resyncNext && fresh && playing; if (resync) resyncNext = false
        val first = awaitingReport && fresh; if (first) awaitingReport = false
        if (fresh) lastReportAt = now
        clock.onReport(r, now, snap = snap || resync || first)
    }

    /** "Resync": the last report is taken outright now, and so is the next one. */
    fun resync() {
        val s = ctrl?.playbackState ?: return
        report(s, force = true, snap = s.state == PlaybackState.STATE_PLAYING)
        resyncNext = true
    }

    val playing get() = state?.state == PlaybackState.STATE_PLAYING

    /** Where the app is in the song right now (ms). */
    fun positionMs(): Double? {
        val s = state ?: return null
        if (s.position < 0) return null
        val p = clock.positionAt(SystemClock.elapsedRealtime())
        return (if (durMs > 0) minOf(p, durMs) else p).toDouble()
    }

    /** The app's cover for what it plays now (readable: a hardware bitmap is copied). */
    fun cover(): android.graphics.Bitmap? = art?.takeIf { !it.isRecycled }
        ?.let { if (it.config == android.graphics.Bitmap.Config.HARDWARE) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it }

    /** The app's cover, if it plays that song. */
    fun artFor(name: String, durationMs: Double): android.graphics.Bitmap? = art?.takeIf { matches(name, durationMs) && !it.isRecycled }
        ?.let { if (it.config == android.graphics.Bitmap.Config.HARDWARE) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it }

    /** Is the song named the one the app plays? */
    fun matches(name: String, durationMs: Double): Boolean {
        if (!connected.value || title.isEmpty()) return false
        val a = name.lowercase().trim(); val b = title.lowercase().trim()
        return (a == b || a.startsWith(b) || b.startsWith(a)) && (durMs <= 0 || kotlin.math.abs(durMs - durationMs) < 2500)
    }

    fun play() = ctrl?.transportControls?.play()
    fun pause() = ctrl?.transportControls?.pause()
    fun next() = ctrl?.transportControls?.skipToNext()
    fun previous() = ctrl?.transportControls?.skipToPrevious()
    fun seek(ms: Long) { val c = ctrl ?: return; clock.seekTo(ms, SystemClock.elapsedRealtime()); c.transportControls.seekTo(ms) }
    val canControl get() = ctrl != null
    /** The sound comes out of this phone (not a computer or speaker the app controls, as with Spotify Connect). */
    val local get() = ctrl?.playbackInfo?.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
}

/** The Spotify app on this phone, through its media session: exact timing without asking the Web API, instant
 *  song changes, and play / pause / skip / seek that work without Spotify Premium. */
object SpotifyApp : SessionFollower({ it == "com.spotify.music" }) {
    const val PKG = "com.spotify.music"
    val access = mutableStateOf(false)      // notification access given

    fun hasAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)
}
