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
    private var spicyId: String? = null   // the song whose Canvas came from Spicy Lyrics' Canvas service
    private var ready = false
    var active by mutableStateOf(false); private set     // a Canvas is loading or playing: its video needs a view to play in
    var cvmode by mutableStateOf(false); private set     // the Canvas layout (once its first frame is shown)
    var cvlive by mutableStateOf(false); private set     // the blurred Canvas behind everything
    var blurred: Bitmap? = null; private set
    var blurVer by mutableIntStateOf(0); private set
    private var view: WeakReference<TextureView>? = null
    private var lastCv = 0.0
    private val grabs = arrayOfNulls<Bitmap>(2); private var grabI = 0   // two small frames, taken in turn (no new bitmap each time)
    private var revealAt = 0.0

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() { ready = true; revealAt = now(); applyMode(); syncPlay() }
        override fun onPlayerError(error: PlaybackException) { if (url != null) {
            android.util.Log.w("VV-canvas", "can't play: ${error.errorCodeName}")
            spicyId?.let { id -> Vibe.scope.launch { SpicyCanvas.forget(id) } }   // remembered, but gone: looked up again next time
            clear(false)
        } }
    }

    private fun ensure(): ExoPlayer = player ?: ExoPlayer.Builder(Vibe.app).build().apply {
        volume = 0f; repeatMode = Player.REPEAT_MODE_ONE; addListener(listener)
        view?.get()?.let { setVideoTextureView(it) }
        player = this
    }

    fun bindView(tv: TextureView) {
        view = WeakReference(tv)
        player?.setVideoTextureView(tv)
    }

    /** A view going away: only lets go if it's the one the video plays in (another cover may have taken over). */
    fun unbind(tv: TextureView) {
        if (view?.get() !== tv) return
        view = null; player?.clearVideoTextureView(tv)
    }

    val hasVideo get() = url != null && ready

    fun clear(keepLayout: Boolean) {
        url = null; ready = false; active = false; spicyId = null
        player?.let { it.stop(); it.clearMediaItems() }
        blurred = null; cvlive = false
        if (!keepLayout) cvmode = false
    }

    private fun set(u: String) {
        clear(true)
        url = u; active = true
        ensure().apply { setMediaItem(MediaItem.fromUri(u)); prepare() }
    }

    fun setLocal(u: Uri) = set(u.toString())

    fun lookup(m: SongMeta, tok: Int) {
        Vibe.scope.launch {
            look(m, tok)
            if (tok == E.coverTok && url == null) cvmode = false   // this song has no Canvas: animate back
        }
    }

    /** Your server first (when set), then Spicy Lyrics' Canvas service for Spotify songs. */
    private suspend fun look(m: SongMeta, tok: Int) {
        if (!S.canvasOn) return
        val tpl = Vibe.cfg.canvasUrl.trim()
        if (tpl.isNotEmpty() && fromServer(m, tok, tpl)) return
        if (tok != E.coverTok || !S.b("cvSpicy") || m.id.isEmpty() || m.ep) return
        val v = SpicyCanvas.find(m.id) ?: return
        if (tok == E.coverTok) { set(v); spicyId = m.id }
    }

    /** Your server's Canvas for the song: true once it's set (or another song plays). A failed lookup is tried once
     *  more after 1.5 s (as Spicy Lyrics Mobile 0.14.4 does). */
    private suspend fun fromServer(m: SongMeta, tok: Int, tpl: String): Boolean {
        for (attempt in 0..1) {
            if (attempt > 0) { delay(1500); if (tok != E.coverTok) return true }
            when (val r = askServer(m, tok, tpl)) { null -> continue; else -> return r }
        }
        return false
    }

    /** true: set (or another song plays), false: the server has none, null: the lookup failed. */
    private suspend fun askServer(m: SongMeta, tok: Int, tpl: String): Boolean? {
        var u = Regex("\\{(\\w+)\\}").replace(tpl) { r ->
            URLEncoder.encode(when (r.groupValues[1]) { "id" -> m.id; "uri" -> m.uri; "title" -> m.title; "artist" -> m.artist; else -> "" }, "UTF-8")
        }
        if (u.startsWith("/")) u = Server.url(u) ?: return false
        if (m.id.isEmpty() && tpl.contains("{id}")) return false
        try {
            val r = Net.fetch(u, "canvas")
            if (r.code == 404) return false
            if (!r.ok) throw IllegalStateException(r.json()?.optString("error")?.ifEmpty { null } ?: "HTTP ${r.code}")
            val ct = r.header("content-type") ?: ""
            val v = when {
                ct.startsWith("video/") -> u
                ct.contains("json") -> r.json()?.let { j -> listOf("url", "canvasUrl", "video", "canvas").firstNotNullOfOrNull { k -> j.optString(k).ifEmpty { null } } }
                else -> r.text.trim()
            }
            if (tok != E.coverTok) return true
            if (v.isNullOrEmpty()) return false
            set(v); return true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { android.util.Log.w("VV-canvas", "lookup: ${e.message}"); return null }   // no Canvas, quietly
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
        if ((E.playing() || E.src == "none") && E.visible && !E.ambient) p.play() else p.pause()
    }

    fun onBackground() { player?.pause() }

    /** The blurred Canvas: 12 times a second (it's a blur: more looks the same and costs a copy from the GPU each
     *  time), a small frame of the video, blurred, as the background. */
    fun frame(t: Double) {
        if (!cvlive || t - lastCv < 80 || !E.visible) return
        lastCv = t
        val tv = view?.get() ?: return
        if (!tv.isAvailable) return
        val bm = grabs[grabI] ?: Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also { grabs[grabI] = it }
        if (runCatching { tv.getBitmap(bm) }.isFailure) return
        grabI = 1 - grabI
        Visuals.blurBitmap(bm, S.bgblur * .025, S.sat / 100.0, S.bri / 100.0)
        blurred = bm; blurVer++
    }

    /** How far the Canvas has faded in (0-1), for the layout change. */
    fun reveal(t: Double) = if (!hasVideo) 0f else ((t - revealAt) / 800).coerceIn(0.0, 1.0).toFloat()

    fun release() { player?.release(); player = null }
}
