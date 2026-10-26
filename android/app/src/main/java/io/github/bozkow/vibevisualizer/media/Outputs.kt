package io.github.bozkow.vibevisualizer.media

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.spotify.Spotify
import io.github.bozkow.vibevisualizer.spotify.SpotifyApp
import java.security.MessageDigest
import java.util.Locale

/** The lyrics timing of each audio output: Bluetooth headphones, a car or a speaker can run behind the phone's own
 *  speaker, so each keeps its own setting, used whenever it plays. A guess at the route, not a measurement of when
 *  the sound is heard. Ported from Spicy Lyrics Mobile by TheX24 (github.com/spicylyrics/mobile, AGPL-3.0:
 *  AudioOutputProfiles). */
object Outputs {
    data class Route(val key: String, val label: String)

    var route by mutableStateOf(Route("device:2:phone speaker", "Phone speaker")); private set
    /** This output's offset in ms, like the general one: positive shows the lyrics earlier. */
    var offMs by mutableIntStateOf(0); private set

    private lateinit var am: AudioManager
    private var router: MediaRouter? = null
    private val prefs by lazy { app.getSharedPreferences("outdelay", Context.MODE_PRIVATE) }
    private lateinit var app: Context
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        am = app.getSystemService(AudioManager::class.java)
        router = app.getSystemService(MediaRouter::class.java)
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
        }, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        val r = runCatching { current() }.getOrNull() ?: return
        if (r != route) { route = r; offMs = prefs.getInt("delay:" + r.key, 0) }
    }

    fun set(ms: Int) {
        offMs = ms.coerceIn(-5000, 5000)
        prefs.edit().apply { if (offMs == 0) remove("delay:" + route.key) else putInt("delay:" + route.key, offMs) }.apply()
    }

    /** Whether the sound comes out of this phone: your own files, or the Spotify app playing here (not on a computer
     *  or speaker it controls), and so whether this output's timing applies. */
    fun here(src: String): Boolean = when (src) {
        "file" -> true
        "spotify" -> Spotify.appTiming() && SpotifyApp.local
        "media" -> MediaApps.local
        else -> false
    }

    /** The offset for the lyrics now (ms). */
    fun lyricsOffMs(src: String): Int = if (offMs != 0 && here(src)) offMs else 0

    private fun current(): Route {
        val routed = if (Build.VERSION.SDK_INT >= 33) runCatching { am.getAudioDevicesForAttributes(media) }.getOrDefault(emptyList())
            .filterNot { it.type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX } else emptyList()
        if (routed.isNotEmpty()) {
            val devices = routed.sortedWith(compareBy<AudioDeviceInfo> { it.type }.thenBy { it.productName.toString() })
            if (devices.size == 1) return devices.single().asRoute()
            val labels = devices.map { it.asRoute().label }.distinct()
            val keys = devices.map { it.asRoute().key }.distinct()
            return Route("multi:" + keys.joinToString("+"), labels.joinToString(" + "))
        }
        // Android 12: the framework's media router knows the live audio route
        val r = runCatching { router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO) }.getOrNull()
        val raw = r?.name?.toString()?.trim().orEmpty()
        val name = if (raw.isBlank() || raw.equals("Phone", ignoreCase = true)) "Phone speaker" else raw
        return Route("route:" + name.lowercase(Locale.ROOT), name)
    }

    @SuppressLint("InlinedApi")
    private fun AudioDeviceInfo.asRoute(): Route {
        val kind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call audio"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Audio output"
        }
        val product = productName?.toString()?.trim().orEmpty()
        val builtin = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val label = if (builtin) kind else product.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) } ?: kind
        val bluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER || type == AudioDeviceInfo.TYPE_HEARING_AID
        // a pair of headphones by its address (hashed, never stored as is), so two of the same model keep their own
        val address = if (bluetooth) runCatching { this.address.trim() }.getOrNull().orEmpty() else ""
        val id = if (address.isNotBlank()) MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) } else label.lowercase(Locale.ROOT)
        return Route("device:$type:$id", label)
    }
}
