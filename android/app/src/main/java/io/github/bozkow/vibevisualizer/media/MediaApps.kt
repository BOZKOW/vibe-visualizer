package io.github.bozkow.vibevisualizer.media

import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.spotify.SessionFollower
import io.github.bozkow.vibevisualizer.spotify.SpotifyApp

/** Any other music app on this phone (YouTube Music, SoundCloud, Apple Music, Deezer, TIDAL, Amazon Music,
 *  Poweramp, Samsung Music…), through its media session, as Spicy Lyrics Mobile follows them: the song, where it is
 *  (the lyric clock), its cover and its controls. Needs notification access, as the Spotify app's link does. The
 *  Spotify app has its own ([SpotifyApp], with the Web API) while Spotify is connected; without it, it's followed here
 *  like the others. This app's own player is left out. */
object MediaApps : SessionFollower({ p -> p != Vibe.app.packageName && (p != SpotifyApp.PKG || !io.github.bozkow.vibevisualizer.spotify.Spotify.connected) }) {
    init { onTrack = { io.github.bozkow.vibevisualizer.engine.E.onMediaTrack() }; onState = { io.github.bozkow.vibevisualizer.engine.E.onMediaState() }; onMeta = { io.github.bozkow.vibevisualizer.engine.E.onMediaMeta() } }

    private val KNOWN = mapOf(
        "com.google.android.apps.youtube.music" to "YouTube Music", "com.google.android.youtube" to "YouTube",
        "com.soundcloud.android" to "SoundCloud", "com.apple.android.music" to "Apple Music", "deezer.android.app" to "Deezer",
        "com.aspiro.tidal" to "TIDAL", "com.amazon.mp3" to "Amazon Music", "com.maxmpz.audioplayer" to "Poweramp",
        "com.sec.android.app.music" to "Samsung Music", "com.pandora.android" to "Pandora", "com.bandcamp.android" to "Bandcamp",
        "in.krosbits.musicolet" to "Musicolet", "com.jio.media.jiobeats" to "JioSaavn", "com.qobuz.music" to "Qobuz",
        "com.anghami" to "Anghami", "com.audiomack" to "Audiomack", "com.napster.android" to "Napster", "ru.yandex.music" to "Yandex Music",
        "com.vkontakte.android" to "VK", "com.zvooq.openplay" to "Zvuk",
    )
    private val names = HashMap<String, String>()

    /** The followed app's name as the phone shows it ("YouTube Music"). */
    fun appName(): String {
        val p = pkg; if (p.isEmpty()) return ""
        return names.getOrPut(p) {
            KNOWN[p] ?: runCatching { val pm = Vibe.app.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrNull() ?: "another app"
        }
    }
}
