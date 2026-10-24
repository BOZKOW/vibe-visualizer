package io.github.bozkow.vibevisualizer

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.engine.Background
import io.github.bozkow.vibevisualizer.engine.CanvasVideo
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.FilePicker
import io.github.bozkow.vibevisualizer.engine.Files
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.engine.Ping
import io.github.bozkow.vibevisualizer.engine.Updates
import io.github.bozkow.vibevisualizer.engine.Visuals
import io.github.bozkow.vibevisualizer.media.Local
import io.github.bozkow.vibevisualizer.spotify.Spotify
import io.github.bozkow.vibevisualizer.spotify.SpotifyApp
import io.github.bozkow.vibevisualizer.ui.Perms
import io.github.bozkow.vibevisualizer.ui.VibeRoot
import io.github.bozkow.vibevisualizer.ui.toggleRom
import io.github.bozkow.vibevisualizer.ui.toggleTr
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { E.openFiles(it) }
    private val openLyrics = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) lifecycleScope.launch { Files.addLyrics(uris, lyricsForSong) } }
    private var lyricsForSong = false
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { E.openFiles(listOf(it)) } }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> u?.let { E.openFiles(listOf(it)) } }
    private var micDone: ((Boolean) -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> micDone?.invoke(ok); micDone = null; Audio.applyCapture() }
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        androidx.core.content.ContextCompat.registerReceiver(this, pipReceiver, android.content.IntentFilter(PIP_ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        window.isNavigationBarContrastEnforced = false
        E.attach(this)
        FilePicker.audio = {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            openAudio.launch(arrayOf("audio/*", "application/ogg", "application/ttml+xml", "application/xml", "text/plain", "application/octet-stream"))
        }
        FilePicker.lyrics = { forSong -> lyricsForSong = forSong; openLyrics.launch(arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain", "application/octet-stream")) }
        FilePicker.image = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        FilePicker.video = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
        Perms.mic = { done -> if (Audio.permitted()) done(true) else { micDone = done; askMic.launch(Manifest.permission.RECORD_AUDIO) } }
        Perms.notificationAccess = { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }

        if (savedInstanceState == null) first()
        setContent { VibeRoot() }
        handle(intent)

        // keep the screen on while music plays; picture-in-picture follows the music too
        lifecycleScope.launch {
            snapshotFlow { Triple(E.playingState, S.b("aKeepOn"), S.b("aPip")) }.collect { (pl, keep, pip) ->
                if (pl && keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                pipParams(pl && pip)
            }
        }
        lifecycleScope.launch { snapshotFlow { S.b("aLock") }.collect { setShowWhenLocked(it) } }
        E.unlock = { go ->
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (km?.isKeyguardLocked == true) km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() { override fun onDismissSucceeded() { go() } })
            else go()
        }
        // Ambient mode: the screen no brighter than 10% (and never brighter than it was)
        lifecycleScope.launch { snapshotFlow { E.ambient }.collect { on ->
            val sys = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(1f)
            window.attributes = window.attributes.apply { screenBrightness = if (on) minOf(sys, .1f) else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        } }
        lifecycleScope.launch { snapshotFlow { S.b("aHideStatus") to S.b("aHideNav") }.collect { bars() } }
        lifecycleScope.launch { snapshotFlow { S.str("fpscap") to S.low }.collect { (c, low) -> refreshRate(if (low) 60f else c.toFloatOrNull() ?: 0f) } }
    }

    /** The first start of the app's process: the engine's first steps. */
    private fun first() {
        Vibe.settings.onChange = { E.onSetting(it) }
        Visuals.setCover(null)
        Audio.setupBands()
        Ping.loop()
        Lyr.refreshCounts()
        Updates.whatsNew()
        Updates.auto()
        when {
            Spotify.connected -> E.setSource("spotify")
            !Vibe.cfg.setupDone -> { E.wizard = true }
            else -> {}
        }
        // a new install with a server already set: your settings kept on the server come along (never over settings this phone has)
        if (!Vibe.cfg.setupDone && Vibe.cfg.server.isNotEmpty() && Vibe.settings.fresh) io.github.bozkow.vibevisualizer.engine.Sync.importFromServer()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        i ?: return
        val data = i.data
        when {
            i.action == Intent.ACTION_VIEW && data?.scheme == "vibevisualizer" -> lifecycleScope.launch { if (Spotify.callback(data)) { E.wizard = false; Vibe.cfg.saveSetup(true); E.setSource("spotify") } }
            i.action == Intent.ACTION_VIEW && data != null -> E.openFiles(listOf(data))
            i.action == Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))?.let { E.openFiles(listOf(it)) }
            i.action == Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM))?.let { E.openFiles(it) }
            i.action == "io.github.bozkow.vibevisualizer.LYRICS" -> E.show("lyrics")
            i.action == "io.github.bozkow.vibevisualizer.VISUALIZER" -> E.show("viz")
            i.action == "io.github.bozkow.vibevisualizer.OPEN_FILE" -> window.decorView.post { FilePicker.pickAudio() }
            i.action == "io.github.bozkow.vibevisualizer.SETTINGS" -> E.openSettings()
        }
        if (data != null || i.action?.startsWith("android.intent.action.SEND") == true) setIntent(Intent(this, MainActivity::class.java))
    }

    override fun onStart() { super.onStart(); E.attach(this); E.onVisible(true); CanvasVideo.syncPlay() }
    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) { E.onVisible(false); CanvasVideo.onBackground() }
        Vibe.settings.flush()
    }
    override fun onResume() { super.onResume(); SpotifyApp.refresh(this); Background.resume(); Updates.showConfirm() }

    /** Settings → Phone: the status bar (with the notifications) and the navigation bar hidden or shown. Hidden, a swipe
     *  from the edge brings them back for a moment. */
    private fun bars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (S.b("aHideStatus")) c.hide(WindowInsetsCompat.Type.statusBars()) else c.show(WindowInsetsCompat.Type.statusBars())
        if (S.b("aHideNav")) c.hide(WindowInsetsCompat.Type.navigationBars()) else c.show(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) bars()   // a dialog or the keyboard can bring them back
    }

    /* ---- picture-in-picture ---- */
    private fun pipParams(auto: Boolean) {
        runCatching {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setAutoEnterEnabled(auto).setSeamlessResizeEnabled(true)
                .setActions(pipActions()).build())
        }
    }

    // the controls on the picture-in-picture window (taps there don't reach the app's own buttons): previous,
    // play / pause (it follows the music), next
    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { when (i.getStringExtra("a")) { "prev" -> E.prev(); "toggle" -> E.toggle(); "next" -> E.next() } }
    }

    private fun pipActions(): List<android.app.RemoteAction> {
        fun act(a: String, icon: Int, title: String, req: Int) = android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), title, title,
            android.app.PendingIntent.getBroadcast(this, req, Intent(PIP_ACTION).setPackage(packageName).putExtra("a", a), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        val playing = E.playingState
        return listOf(act("prev", R.drawable.pip_prev, "Previous", 1),
            act("toggle", if (playing) R.drawable.pip_pause else R.drawable.pip_play, if (playing) "Pause" else "Play", 2),
            act("next", R.drawable.pip_next, "Next", 3))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (S.b("aPip") && E.playingState && Build.VERSION.SDK_INT < 31) runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        E.inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) E.onVisible(false)
    }

    /* ---- display ---- */
    private fun refreshRate(hz: Float) { window.attributes = window.attributes.apply { preferredRefreshRate = hz } }

    /* ---- the web app's keyboard shortcuts, for a keyboard (tablets, DeX, Chromebooks) ---- */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || currentFocus is android.widget.EditText) return super.onKeyDown(keyCode, event)
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> { E.toggle(); true }
            KeyEvent.KEYCODE_N -> { E.next(); true }
            KeyEvent.KEYCODE_P -> { E.prev(); true }
            KeyEvent.KEYCODE_S -> { if (E.src == "spotify") Spotify.toggleShuffle(); E.src == "spotify" }
            KeyEvent.KEYCODE_L -> { if (E.src == "spotify") Spotify.toggleLike(); E.src == "spotify" }
            KeyEvent.KEYCODE_M -> { Local.muted = !Local.muted; true }
            KeyEvent.KEYCODE_T -> { toggleTr(); true }
            KeyEvent.KEYCODE_R -> { toggleRom(); true }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> { Lyr.nudge(100); true }
            KeyEvent.KEYCODE_LEFT_BRACKET -> { Lyr.nudge(-100); true }
            KeyEvent.KEYCODE_1 -> { E.show("viz"); true }
            KeyEvent.KEYCODE_2 -> { E.show("lyrics"); true }
            KeyEvent.KEYCODE_3 -> { E.show("player"); true }
            KeyEvent.KEYCODE_DPAD_UP -> { E.nudgeVol(5); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { E.nudgeVol(-5); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (E.src == "file") { Local.seek((Local.positionMs() + 5000).toLong()); true } else false }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (E.src == "file") { Local.seek((Local.positionMs() - 5000).toLong().coerceAtLeast(0)); true } else false }
            KeyEvent.KEYCODE_ESCAPE -> { Lyr.view.freeEnd(); E.settingsPage = null; E.sheet = null; true }
            else -> false
        }
        if (handled) E.poke()
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { runCatching { unregisterReceiver(pipReceiver) }; if (E.unlock != null) E.unlock = null; super.onDestroy(); if (isFinishing) CanvasVideo.release() }

    companion object { private const val PIP_ACTION = "io.github.bozkow.vibevisualizer.PIP" }
}
