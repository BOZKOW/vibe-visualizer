package io.github.bozkow.vibevisualizer.engine

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.lyrics.SongMeta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.net.URLEncoder

/** Spotify Canvas videos (from your server, or the endpoint set in Settings) and your own canvas video. While one
 *  plays, the layout makes room for it and the background is the video itself, blurred. */
@OptIn(UnstableApi::class)
object CanvasVideo {
    private var player: ExoPlayer? = null
    private var url: String? = null
    private var ready = false
    var cvmode by mutableStateOf(false); private set     // the Canvas layout
    var cvlive by mutableStateOf(false); private set     // the blurred Canvas behind everything
    var blurred: Bitmap? = null; private set
    var blurVer by mutableIntStateOf(0); private set
    private var view: WeakReference<TextureView>? = null
    private var lastCv = 0.0
    private var revealAt = 0.0

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() { ready = true; revealAt = now(); applyMode(); syncPlay() }
        override fun onPlayerError(error: PlaybackException) { if (url != null) { clear(false); E.toast("The canvas video could not be loaded (blocked or unsupported).", "warn") } }
    }

    private fun ensure(): ExoPlayer = player ?: ExoPlayer.Builder(Vibe.app).build().apply {
        volume = 0f; repeatMode = Player.REPEAT_MODE_ONE; addListener(listener)
        view?.get()?.let { setVideoTextureView(it) }
        player = this
    }

    fun bindView(tv: TextureView?) {
        view = tv?.let { WeakReference(it) }
        player?.let { if (tv != null) it.setVideoTextureView(tv) else it.clearVideoSurface() }
    }

    val hasVideo get() = url != null && ready

    fun clear(keepLayout: Boolean) {
        url = null; ready = false
        player?.let { it.stop(); it.clearMediaItems() }
        blurred = null; cvlive = false
        if (!keepLayout) cvmode = false
    }

    private fun set(u: String) {
        clear(true)
        url = u
        ensure().apply { setMediaItem(MediaItem.fromUri(u)); prepare() }
    }

    fun setLocal(u: Uri) = set(u.toString())

    fun lookup(m: SongMeta, tok: Int) {
        Vibe.scope.launch {
            look(m, tok)
            if (tok == E.coverTok && url == null) cvmode = false   // this song has no Canvas: animate back
        }
    }

    private suspend fun look(m: SongMeta, tok: Int) {
        val tpl = Vibe.cfg.canvasUrl.trim()
        if (!S.canvasOn || tpl.isEmpty()) return
        var u = Regex("\\{(\\w+)\\}").replace(tpl) { r ->
            URLEncoder.encode(when (r.groupValues[1]) { "id" -> m.id; "uri" -> m.uri; "title" -> m.title; "artist" -> m.artist; else -> "" }, "UTF-8")
        }
        if (u.startsWith("/")) u = Server.url(u) ?: return
        if (m.id.isEmpty() && tpl.contains("{id}")) return
        try {
            val r = Net.fetch(u, "canvas")
            if (r.code == 404) return
            if (!r.ok) throw IllegalStateException(r.json()?.optString("error")?.ifEmpty { null } ?: "HTTP ${r.code}")
            val ct = r.header("content-type") ?: ""
            val v = when {
                ct.startsWith("video/") -> u
                ct.contains("json") -> r.json()?.let { j -> listOf("url", "canvasUrl", "video", "canvas").firstNotNullOfOrNull { k -> j.optString(k).ifEmpty { null } } }
                else -> r.text.trim()
            }
            if (tok != E.coverTok || v.isNullOrEmpty()) return
            set(v)
        } catch (e: Exception) { E.toast("Canvas lookup failed: ${e.message}", "warn") }
    }

    fun applyMode() {
        val has = hasVideo
        cvmode = S.canvasOn && has
        cvlive = S.canvasOn && has && !S.low
        if (!S.canvasOn) player?.pause() else syncPlay()
    }

    fun syncPlay() {
        val p = player ?: return
        if (url == null || !S.canvasOn) return
        if ((E.playing() || E.src == "none") && E.visible) p.play() else p.pause()
    }

    fun onBackground() { player?.pause() }

    /** The blurred Canvas: 30 times a second, a small frame of the video, blurred, as the background. */
    fun frame(t: Double) {
        if (!cvlive || t - lastCv < 33 || !E.visible) return
        lastCv = t
        val tv = view?.get() ?: return
        if (!tv.isAvailable) return
        val bm = runCatching { tv.getBitmap(48, 48) }.getOrNull() ?: return
        Visuals.blurBitmap(bm, S.bgblur * .025, S.sat / 100.0, S.bri / 100.0)
        blurred = bm; blurVer++
    }

    /** How far the Canvas has faded in (0-1), for the layout change. */
    fun reveal(t: Double) = if (!hasVideo) 0f else ((t - revealAt) / 800).coerceIn(0.0, 1.0).toFloat()

    fun release() { player?.release(); player = null }
    @Suppress("unused") private suspend fun waitCover() = delay(0)
}
