package io.github.bozkow.vibevisualizer.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.media.Local
import io.github.bozkow.vibevisualizer.spotify.Spotify
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What the bars hear and how they move: the web app's sample(), beatTick() and idle wave. */
object Audio {
    val data = FloatArray(1024)            // the analyser's bins, 0-255 (as getByteFrequencyData)
    private val glide = FloatArray(1024)
    private val frame = FloatArray(1024)
    private val pcm = FloatArray(2048)
    private val analyser = Analyser()
    val phone = PhoneSound()
    val mic = Mic()
    val bridge = BridgeRelay()

    // bars
    var N = 0; private set
    var vals = FloatArray(0); private set
    private var raw = FloatArray(0)
    private var edges = IntArray(0)
    private var bandRate = 0
    var bass = 0f; private set
    var mxv = 0f; private set
    private var peak = .3f
    var idleK = 0f; private set
    private var silentFor = 0.0
    var realAudio = false; private set

    // the background follows the music: loudness, bass hits, tempo
    var bgEnergy = 0f; private set
    var bgKick = 0f; private set
    var bgTempo = 0f; private set
    private var bassSlow = 0f
    private var lastOnset = 0.0
    private val onsetGaps = ArrayList<Double>()

    var label by mutableStateOf("No audio"); private set      // what the bars follow (the audio chip)
    var labelTip by mutableStateOf(""); private set
    var bridgeOn by mutableStateOf(false); private set

    fun permitted() = Vibe.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun setupBands() {
        N = if (S.low) min(S.bars, 48) else S.bars
        vals = FloatArray(N); raw = FloatArray(N)
        bandRate = curRate()
        val lo = 2.0; val hi = min(1023.0, Math.round(11000.0 / (bandRate / 2048.0)).toDouble())   // up to ~11 kHz: above that most music is nearly silent
        edges = IntArray(N + 1) { i -> floor(lo * (hi / lo).pow(i.toDouble() / N)).toInt() }
    }

    private fun curRate() = if (E.src == "file" && Local.playing()) FileTap.sampleRate else 48000

    /** Starts or stops the capture the settings ask for (phone sound or microphone) and the bridge. */
    fun applyCapture() {
        val cap = S.str("aCap")
        if (cap == "phone" && permitted() && E.visible) phone.start() else phone.stop()
        if (cap == "mic" && permitted() && E.visible) mic.start(Vibe.app) else mic.stop()
        val relay = Server.base?.replaceFirst("https://", "wss://")?.replaceFirst("http://", "ws://")?.plus("/api/bridge/listen")
        if (S.b("bridge") && relay != null && E.visible) bridge.start(relay) else bridge.stop()
    }

    fun stopAll() { phone.stop(); mic.stop(); bridge.stop() }

    private fun filePlaying() = E.src == "file" && Local.playing()
    private fun phoneLive() = phone.running && now() - phone.heardAt < 1500
    private fun micLive() = mic.running
    fun liveAudio() = filePlaying() || micLive() || phoneLive()
    fun bridgeLive() = bridge.live()

    /** Seconds the measured sound lags behind what you hear (for the desync detection). */
    fun lag(): Double = when {
        filePlaying() -> 0.0
        micLive() -> .06
        phoneLive() -> .05
        bridgeLive() -> .22
        else -> 0.0
    }

    fun sampleRate() = if (filePlaying()) FileTap.sampleRate else 48000

    fun sample(dt: Double, t: Double) {
        if (bandRate != curRate()) setupBands()
        val aA = 1 - exp(-dt / (6 + S.i("resp") * .5)); val aR = 1 - exp(-dt / (30 + S.i("resp") * 3.5)); val boost = S.i("boost") / 100f
        val fp = filePlaying(); val ml = !fp && micLive(); val pl = !fp && !ml && phoneLive()
        val viaBridge = !fp && !ml && !pl && bridgeLive()
        val live = fp || ml || pl || viaBridge
        realAudio = live
        if (live) {
            val tau = .35.pow(dt / 16.7).toFloat()   // the analyser smooths once per read: scaled to the frame time, the same at 60 and 120 fps
            when {
                fp -> { val pos = Local.positionMs(); FileTap.sync(pos); FileTap.read(pos, pcm); analyser.compute(pcm, tau, data) }
                ml -> { mic.ring.read(mic.ring.written, pcm); analyser.compute(pcm, tau, data) }
                else -> {   // Visualizer and Vibe Bridge readings come 20-50 times a second: glide between them instead of jumping
                    if (pl) phone.copy(frame) else bridge.copy(frame)
                    val k = (1 - exp(-dt / 20)).toFloat()
                    for (i in 0 until 1024) { glide[i] += (frame[i] - glide[i]) * k; data[i] = glide[i] }
                }
            }
            var mx = 0f
            for (i in 0 until N) {
                var s = 0f; val e0 = edges[i]; val e1 = max(edges[i + 1], e0 + 1)
                for (k in e0 until e1) s += data[k]
                val r = (s / (e1 - e0) / 255f).toDouble().pow(1.5).toFloat() * (1 + (i.toDouble() / N).pow(1.4).toFloat() * 2.2f)   // treble lifted, it's much quieter
                raw[i] = r; if (r > mx) mx = r
            }
            silentFor = if (mx < .004f) silentFor + dt else 0.0   // a source that's connected but silent
            var gain = 1.3f
            if (S.b("auto")) { peak += (mx - peak) * (1 - exp(-dt / (if (mx > peak) 50 else 2500))).toFloat(); gain = min(8f, .8f / max(peak, .1f)) }
            gain *= boost
            for (i in 0 until N) { val v = min(raw[i] * gain, 1.1f); vals[i] += (v - vals[i]) * (if (v > vals[i]) aA else aR).toFloat() }
        } else if (E.src == "spotify" && Spotify.spPlaying()) {
            val s = t / 1000   // ambient motion: the Spotify app's sound isn't heard
            for (i in 0 until N) {
                val x = i.toDouble() / N
                val v = ((.12 + .5 * (1 - x * .75)) * (.55 + .45 * sin(s * 1.7 + i * .42)) * (.6 + .4 * sin(s * .6 + i * .17 + 1.3)) * boost).toFloat()
                vals[i] += (v - vals[i]) * aA.toFloat()
            }
        } else { val d = exp(-dt / 180).toFloat(); for (i in 0 until N) vals[i] *= d }
        val idle = if (live) silentFor > 900 else !(E.src == "spotify" && Spotify.spPlaying())
        if (!live) silentFor = 0.0
        idleK += ((if (idle) 1f else 0f) - idleK) * (1 - exp(-dt / (if (idle) 700 else 200))).toFloat()
        idleWave(dt, t)
        var b = 0f; val bn = max(3, N / 12); var m = 0f
        for (i in 0 until N) { if (vals[i] > m) m = vals[i]; if (i < bn) b += vals[i] }
        mxv = m
        bass += ((if (idleK > .5f) 0f else b / bn) - bass) * (1 - exp(-dt / 60)).toFloat()   // the wave isn't music: it doesn't move the background or count as beats
        updLabel()
    }

    // no sound: a soft wave rolls across the resting dots instead of a flat row; it fades away as soon as music comes in
    private fun idleWave(dt: Double, t: Double) {
        if (idleK < .005f || E.reduceMotion()) return
        val s = t / 1000; val a = (1 - exp(-dt / 140)).toFloat()
        for (i in 0 until N) {
            val x = if (N > 1) i.toDouble() / (N - 1) else 0.0
            val w = ((.035 + .1 * (.5 + .5 * sin(s * 2.1 - x * 10))) * (.72 + .28 * sin(s * .8 + x * 5)) * idleK).toFloat()
            if (vals[i] < w) vals[i] += (w - vals[i]) * a
        }
    }

    // beats: a bass hit clearly above the recent bass level; drives the background push
    fun beatTick(t: Double, dt: Double) {
        bgEnergy += (mxv - bgEnergy) * (1 - exp(-dt / 500)).toFloat()
        bassSlow += (bass - bassSlow) * (1 - exp(-dt / 350)).toFloat()
        if (bass - bassSlow > .07f && t - lastOnset > 240) {
            if (lastOnset > 0 && t - lastOnset < 1500) { onsetGaps.add(t - lastOnset); if (onsetGaps.size > 16) onsetGaps.removeAt(0) }
            lastOnset = t; bgKick = 1f; bgTempo = 0f
        }
        bgKick *= exp(-dt / 260).toFloat()
        // the overall speed follows the tempo estimated from those hits (120 BPM = normal speed)
        if (bgTempo == 0f) {
            bgTempo = 1f
            if (onsetGaps.size > 5) { val g = onsetGaps.sorted(); val med = g[g.size / 2]; bgTempo = ((60000 / med) / 120).toFloat().coerceIn(.6f, 1.7f) }
        }
        if (t - lastOnset > 4000 && onsetGaps.isNotEmpty()) { onsetGaps.clear(); bgTempo = 0f }
    }

    fun dropBars() { vals.fill(0f); peak = .3f }

    private fun updLabel() {
        val br = bridge.live() || (bridge.on && now() - bridge.at < 1500)
        if (br != bridgeOn) {
            if (br) E.toast("Vibe Bridge connected through your server: the bars follow the computer’s sound.", "ok", 3500)
            else E.toast("Vibe Bridge stopped. The bars go back to the other sources.", "warn", 3500)
            bridgeOn = br
        }
        val (t, tip) = when {
            filePlaying() -> "This file" to "Bars follow: the file that’s playing."
            micLive() -> "Microphone" to "Bars follow: the microphone (the sound in the room)."
            phoneLive() -> "Phone sound" to "Bars follow: everything this phone plays, the Spotify app included."
            bridgeLive() -> "Vibe Bridge" to ("Bars follow: Vibe Bridge through your server" + (if (bridge.device.isNotEmpty()) " · listening to ${bridge.device}" else "") + ".")
            E.src == "spotify" && Spotify.spPlaying() -> "Ambient" to (if (phone.running) "Bars follow: a gentle ambient motion. Nothing is playing on this phone; start Vibe Bridge on the computer that plays the music for real bars."
                else "Bars follow: a gentle ambient motion (Spotify’s audio can’t be read). Allow “Phone sound” for real bars from the Spotify app on this phone.")
            phone.running -> "Phone sound" to "Waiting for sound: the bars follow everything this phone plays."
            else -> "No audio" to "Nothing for the bars to follow. Tap to choose what the bars hear."
        }
        if (t != label) label = t
        if (tip != labelTip) labelTip = tip
    }
}
