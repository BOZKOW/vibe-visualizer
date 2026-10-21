package io.github.bozkow.vibevisualizer.engine

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.spotify.Spotify
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.min

/** The live background's clock: it moves at the music's pace (faster when it's loud and busy, a push on every bass
 *  hit, the overall speed following the tempo), as Spicy Lyrics moves its background. */
object Background {
    var bgT = 0.0; private set            // the drifting layers' clock
    var pulse = 0f; private set           // the bass, smoothed: the layers swell with it
    var kwTime = 0.0; private set         // the liquid warp's clock (Kawarp-style)
    private var lastF = 0.0
    var still by mutableStateOf<Bitmap?>(null); private set   // Artist image / Cover art
    private var stillTok = 0

    fun live() = S.bgStyle == "dynamic" && !S.low && !CanvasVideo.cvlive && E.visible && E.settingsPage == null && !E.wizard

    fun tick(t: Double, dt: Double) {
        if (!live()) return
        val tempo = if (Audio.bgTempo > 0f) Audio.bgTempo.toDouble() else 1.0
        bgT += dt / 1000 * (S.bgspeed / 100.0) * (.35 + Audio.bgEnergy * 1.8 + Audio.bgKick * 2.2) * tempo
        pulse += (Audio.bass - pulse) * (1 - exp(-dt / 120)).toFloat()
        // as Spicy Lyrics moves it: speed 1 while music plays (0.1 paused), times the tempo and the loudness, plus a
        // pulse on every beat (up to +1.5, fading), within 0.1-3
        val sp = if (E.playing()) ((tempo * (.5 + min(1.0, Audio.bgEnergy * 2.2) * .7) + 1.5 * Audio.bgKick).coerceIn(.1, 3.0)) else .1
        kwTime += dt / 1000 * sp * S.bgspeed / 100
        if (t - lastF >= 16) { lastF = t; E.bgTick.longValue++ }   // 60 times a second at most: a slow blur looks the same
    }

    fun resume() { E.bgTick.longValue++ }

    /** Settings → Background: the still ones show the artist's photo or the cover. */
    fun applyStill() {
        val st = S.bgStyle; val tok = ++stillTok
        if (st != "artist" && st != "cover") { still = null; E.bgTick.longValue++; return }
        Vibe.scope.launch {
            var img: Bitmap? = null
            if (st == "artist" && E.src == "spotify") { val u = Spotify.artistImage(E.curArtistId); if (u.isNotEmpty()) img = Images.load(u) }
            if (img == null) img = Visuals.cover
            if (tok == stillTok) { still = img; E.bgTick.longValue++ }
        }
    }
}
