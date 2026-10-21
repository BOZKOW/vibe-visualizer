package io.github.bozkow.vibevisualizer.media

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.bozkow.vibevisualizer.audio.FileTap
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Files
import kotlinx.coroutines.launch
import kotlin.math.pow

/** Playing your own files (the player lives in [PlaybackService]; everything runs in one process). */
object Local {
    private var player: ExoPlayer? = null
    private var controller: MediaController? = null
    private var pending: List<Uri>? = null
    private var names = HashMap<String, String>()
    private var lastDur = 0.0
    private var curUri: String? = null
    var muted = false
        set(v) { field = v; applyVolume() }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { E.onFileState() }
        override fun onPlaybackStateChanged(state: Int) { E.onFileState() }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) FileTap.nextItem(lastDur)
            val u = item?.localConfiguration?.uri ?: return
            if (u.toString() == curUri && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return   // only its tags changed
            curUri = u.toString()
            E.onFileItem(u, names[u.toString()] ?: Files.name(u))
        }
        override fun onPlayerError(error: PlaybackException) { E.toast("This file could not be played (${error.errorCodeName}). The format may not be supported.") }
    }

    fun attach(p: ExoPlayer) {
        player = p; p.addListener(listener); applyVolume()
        pending?.let { pending = null; start(it) }
    }

    fun detach() { player?.removeListener(listener); player = null; controller = null }

    /** Starts the playback service (if it isn't running yet) and plays the files, in the order given. */
    fun open(uris: List<Uri>) {
        uris.forEach { u -> runCatching { Vibe.app.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (player != null) { start(uris); return }
        pending = uris
        if (controller == null) {
            val tok = SessionToken(Vibe.app, ComponentName(Vibe.app, PlaybackService::class.java))
            val f = MediaController.Builder(Vibe.app, tok).buildAsync()
            f.addListener({ controller = runCatching { f.get() }.getOrNull() }, { Vibe.scope.launch { it.run() } })
        }
    }

    private fun start(uris: List<Uri>) {
        val p = player ?: return
        names.clear(); curUri = null
        val items = uris.map { u -> val n = Files.name(u); names[u.toString()] = n
            MediaItem.Builder().setUri(u).setMediaId(u.toString()).setMediaMetadata(MediaMetadata.Builder().setTitle(Files.clean(n)).build()).build() }
        p.setMediaItems(items); p.prepare(); p.play()
    }

    /** The song's tags and cover go to the notification and the lock screen. */
    fun setMeta(title: String, artist: String, picture: ByteArray?) {
        val p = player ?: return
        val i = p.currentMediaItemIndex; val it = p.currentMediaItem ?: return
        val md = MediaMetadata.Builder().setTitle(title).setArtist(artist).apply { if (picture != null) setArtworkData(picture, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }.build()
        if (i >= 0 && i < p.mediaItemCount) p.replaceMediaItem(i, it.buildUpon().setMediaMetadata(md).build())
    }

    fun playing() = player?.isPlaying == true
    fun loaded() = (player?.mediaItemCount ?: 0) > 0
    fun positionMs(): Double = player?.currentPosition?.toDouble() ?: 0.0
    fun durationMs(): Double = player?.duration?.takeIf { it > 0 }?.toDouble() ?: 0.0
    fun hasNext() = player?.hasNextMediaItem() == true

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
    }
    fun pause() { player?.pause() }
    fun seek(ms: Long) { player?.seekTo(ms) }
    fun previous() { val p = player ?: return; if (p.currentPosition > 3000 || !p.hasPreviousMediaItem()) p.seekTo(0) else p.seekToPreviousMediaItem() }
    fun next() { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun applyVolume() { player?.volume = if (muted) 0f else (S.i("volume") / 100.0).pow(2).toFloat() }
    fun tickSession() { lastDur = durationMs() }
}
