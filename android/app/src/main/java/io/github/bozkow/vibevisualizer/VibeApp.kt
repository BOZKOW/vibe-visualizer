package io.github.bozkow.vibevisualizer

import android.app.Application
import io.github.bozkow.vibevisualizer.core.Vibe

class VibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Vibe.init(this)
        io.github.bozkow.vibevisualizer.spotify.Spotify.connected   // sets up Spotify, which listens to the Spotify app from the start
    }
}
