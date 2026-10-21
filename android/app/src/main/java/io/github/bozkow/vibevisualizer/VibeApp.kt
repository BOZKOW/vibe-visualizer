package io.github.bozkow.vibevisualizer

import android.app.Application
import io.github.bozkow.vibevisualizer.core.Vibe

class VibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Vibe.init(this)
    }
}
