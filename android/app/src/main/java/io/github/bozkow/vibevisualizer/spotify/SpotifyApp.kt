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

/** Only here so Android lists Vibe Visualizer under notification access: with it, the app can see the Spotify app's
 *  media session on this phone (what plays, where in the song, and its controls). No notification is read. */
class SessionListener : NotificationListenerService() {
    override fun onListenerConnected() { SpotifyApp.refresh(this) }
}

/** The Spotify app on this phone, through its media session: exact timing without asking the Web API, instant
 *  song changes, and play / pause / skip / seek that work without Spotify Premium. */
object SpotifyApp {
    private const val PKG = "com.spotify.music"
    private val main = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var comp: ComponentName? = null
    private var ctrl: MediaController? = null
    private var listening = false

    val connected = mutableStateOf(false)   // notification access given and the Spotify app has a session
    val access = mutableStateOf(false)      // notification access given
    var title = ""; var artist = ""; var album = ""; var durMs = 0L
        private set
    private var state: PlaybackState? = null

    var onTrack: (() -> Unit)? = null       // a new song started in the Spotify app
    var onState: (() -> Unit)? = null       // played, paused or seeked

    fun hasAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) { readMeta(m, true) }
        override fun onPlaybackStateChanged(s: PlaybackState?) { state = s; onState?.invoke() }
        override fun onSessionDestroyed() { attach(null) }
    }

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        access.value = hasAccess(app)
        if (!access.value) { stop(); return }
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

    private fun pick(list: List<MediaController>?) {
        val c = list?.firstOrNull { it.packageName == PKG }
        if (c?.sessionToken != ctrl?.sessionToken) attach(c)
    }

    private fun attach(c: MediaController?) {
        ctrl?.let { runCatching { it.unregisterCallback(cb) } }
        ctrl = c
        if (c == null) { connected.value = false; state = null; title = ""; artist = ""; durMs = 0; onState?.invoke(); return }
        c.registerCallback(cb, main)
        connected.value = true
        state = c.playbackState
        readMeta(c.metadata, false)
        onState?.invoke()
    }

    private fun readMeta(m: MediaMetadata?, notify: Boolean) {
        val t = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val changed = t != title
        title = t
        artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        durMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
        if (notify && changed && t.isNotEmpty()) onTrack?.invoke()
    }

    val playing get() = state?.state == PlaybackState.STATE_PLAYING

    /** Where the Spotify app is in the song right now (ms), the way Android's own media controls count it. */
    fun positionMs(): Double? {
        val s = state ?: return null
        if (s.position < 0) return null
        var p = s.position.toDouble()
        if (s.state == PlaybackState.STATE_PLAYING) p += (SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed.toDouble()
        return p
    }

    /** Is the song the Web API says is playing the one in the Spotify app? */
    fun matches(name: String, durationMs: Double): Boolean {
        if (!connected.value || title.isEmpty()) return false
        val a = name.lowercase().trim(); val b = title.lowercase().trim()
        return (a == b || a.startsWith(b) || b.startsWith(a)) && (durMs <= 0 || kotlin.math.abs(durMs - durationMs) < 2500)
    }

    fun play() = ctrl?.transportControls?.play()
    fun pause() = ctrl?.transportControls?.pause()
    fun next() = ctrl?.transportControls?.skipToNext()
    fun previous() = ctrl?.transportControls?.skipToPrevious()
    fun seek(ms: Long) = ctrl?.transportControls?.seekTo(ms)
    val canControl get() = ctrl != null
}
