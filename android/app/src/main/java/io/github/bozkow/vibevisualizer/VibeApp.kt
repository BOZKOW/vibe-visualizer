package io.github.bozkow.vibevisualizer

import android.app.Application
import io.github.bozkow.vibevisualizer.core.Vibe

class VibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Vibe.init(this)
        io.github.bozkow.vibevisualizer.spotify.Spotify.connected   // sets up Spotify, which listens to the Spotify app from the start
        io.github.bozkow.vibevisualizer.widget.Widgets.refresh()      // placed home screen widgets follow the music
        // Low performance mode follows the phone's Power saving (and catches up with a change made while the app was closed)
        androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) = io.github.bozkow.vibevisualizer.engine.Perf.saverCheck()
        }, android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        io.github.bozkow.vibevisualizer.engine.Perf.saverCheck()
        // the widgets' lyrics are drawn for the way the screen is turned: again when it turns
        registerComponentCallbacks(object : android.content.ComponentCallbacks {
            private var o = resources.configuration.orientation
            override fun onConfigurationChanged(c: android.content.res.Configuration) { if (c.orientation != o) { o = c.orientation; io.github.bozkow.vibevisualizer.widget.Widgets.refresh() } }
            @Deprecated("") override fun onLowMemory() {}
        })
    }
}
