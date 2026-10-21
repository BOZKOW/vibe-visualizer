package io.github.bozkow.vibevisualizer.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.now
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A ring of mono samples written by one thread and read by the frame loop. */
class Ring(bits: Int = 17) {
    private val cap = 1 shl bits; private val mask = cap - 1
    private val buf = FloatArray(cap)
    @Volatile var written = 0L      // frames written in total
        private set

    fun write(x: Float) { buf[(written and mask.toLong()).toInt()] = x; written++ }
    fun clear() { written = 0L; buf.fill(0f) }

    /** The n samples that end at frame [end] (clamped to what's kept). */
    fun read(end: Long, out: FloatArray) {
        val n = out.size; val w = written
        val e = end.coerceIn(min(w, n.toLong()), w)
        val oldest = max(0L, w - cap + 1)
        for (i in 0 until n) { val f = e - n + i; out[i] = if (f < oldest || f < 0) 0f else buf[(f and mask.toLong()).toInt()] }
    }
}

/** Your own files: ExoPlayer hands every decoded sample to this tap (no permission needed). The tap runs ahead of
 *  what you hear by the audio buffer, so the samples are found by the playback position, not taken as they come. */
@OptIn(UnstableApi::class)
object FileTap : TeeAudioProcessor.AudioBufferSink {
    val ring = Ring()
    @Volatile var sampleRate = 48000
        private set
    @Volatile var flushes = 0
        private set
    @Volatile private var flushFrame = 0L
    private var channels = 2; private var encoding = C.ENCODING_PCM_16BIT
    private var baseMs = 0.0; private var seenFlush = -1

    override fun flush(sampleRateHz: Int, channelCount: Int, enc: Int) {
        sampleRate = sampleRateHz; channels = max(1, channelCount); encoding = enc
        flushFrame = ring.written; flushes++
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val ch = channels
        when (encoding) {
            C.ENCODING_PCM_16BIT -> while (b.remaining() >= 2 * ch) { var s = 0f; repeat(ch) { s += b.short / 32768f }; ring.write(s / ch) }
            C.ENCODING_PCM_FLOAT -> while (b.remaining() >= 4 * ch) { var s = 0f; repeat(ch) { s += b.float }; ring.write(s / ch) }
            C.ENCODING_PCM_24BIT -> while (b.remaining() >= 3 * ch) { var s = 0f; repeat(ch) { val v = (b.get().toInt() and 0xff) or ((b.get().toInt() and 0xff) shl 8) or (b.get().toInt() shl 16); s += v / 8388608f }; ring.write(s / ch) }
            C.ENCODING_PCM_32BIT -> while (b.remaining() >= 4 * ch) { var s = 0f; repeat(ch) { s += b.int / 2147483648f }; ring.write(s / ch) }
            else -> b.position(b.limit())
        }
    }

    /** Main thread, every frame: a new stream (start, seek) begins at the player's position then. */
    fun sync(posMs: Double) { val f = flushes; if (f != seenFlush) { seenFlush = f; baseMs = posMs } }
    /** The next song of the queue started without a break: its samples follow straight after the last one's. */
    fun nextItem(oldDurMs: Double) { flushFrame += ((oldDurMs - baseMs) * sampleRate / 1000).roundToInt(); baseMs = 0.0 }

    fun read(posMs: Double, out: FloatArray) = ring.read(flushFrame + ((posMs - baseMs) * sampleRate / 1000).toLong(), out)
}

/** Everything the phone plays (the Spotify app too), through Android's Visualizer on the output mix. It turns the
 *  spectrum into the same 1024 bins the bars use; only the spectrum is read, nothing is recorded. (From the old app.) */
class PhoneSound : Visualizer.OnDataCaptureListener {
    private var vis: Visualizer? = null
    private val out = FloatArray(1024)
    private var smooth = FloatArray(0)
    @Volatile var seq = 0; private set
    @Volatile var heardAt = 0.0; private set     // last time there was any sound
    var error: String? = null; private set
    val running get() = vis != null

    @Synchronized fun start(): Boolean {
        if (vis != null) return true
        error = null
        return try {
            vis = Visualizer(0).apply {
                enabled = false
                captureSize = min(1024, Visualizer.getCaptureSizeRange()[1])
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED   // the same level at any volume
                setDataCaptureListener(this@PhoneSound, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
            true
        } catch (t: Throwable) { error = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: ""); stop(); false }
    }

    @Synchronized fun stop() {
        vis?.let { runCatching { it.enabled = false }; runCatching { it.release() } }
        vis = null
    }

    override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) {}

    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRateMilliHz: Int) {
        // fft: [Re0, Re(n/2), Re1, Im1, Re2, Im2, ...], n/2 bins over 0 .. rate/2
        val f = fft ?: return
        val n = f.size / 2; if (n < 2) return
        val half = samplingRateMilliHz / 2000f
        synchronized(this) {
            if (smooth.size != n) smooth = FloatArray(n)
            var any = false
            for (k in 0 until n) {
                val m = if (k == 0) abs(f[0].toFloat()) else hypot(f[2 * k].toFloat(), f[2 * k + 1].toFloat())
                smooth[k] = smooth[k] * .35f + m * .65f
                if (m > 1.5f) any = true
            }
            for (i in 0 until 1024) {
                val x = (i * 24000f / 1024) / half * n; val j = x.toInt()
                val m = if (j >= n - 1) 0f else smooth[j] + (smooth[j + 1] - smooth[j]) * (x - j)
                val db = 20f * log10(max(m, 1e-3f) / 128f)
                out[i] = ((db + 48f) / 48f * 255f).coerceIn(0f, 255f)
            }
            if (any) heardAt = now()
            seq++
        }
    }

    @Synchronized fun copy(dst: FloatArray) = System.arraycopy(out, 0, dst, 0, 1024)
}

/** The microphone: the room's sound (a speaker playing nearby). */
class Mic {
    val ring = Ring(15)
    @Volatile private var rec: AudioRecord? = null
    private var th: Thread? = null
    val sampleRate = 48000
    var error: String? = null; private set
    val running get() = rec != null

    fun permitted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(ctx: Context): Boolean {
        if (rec != null) return true
        if (!permitted(ctx)) { error = "no microphone permission"; return false }
        return try {
            val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val r = AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(min, 8192))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("microphone busy") }
            r.startRecording(); rec = r
            th = Thread({
                val b = FloatArray(1024)
                while (rec === r) { val n = r.read(b, 0, b.size, AudioRecord.READ_BLOCKING); if (n > 0) for (i in 0 until n) ring.write(b[i]) else if (n < 0) break }
            }, "vv-mic").apply { isDaemon = true; start() }
            true
        } catch (t: Throwable) { error = t.message ?: t.javaClass.simpleName; stop(); false }
    }

    fun stop() { val r = rec; rec = null; r?.let { runCatching { it.stop() }; runCatching { it.release() } }; th = null }
}

/** Vibe Bridge through your server: the computer playing the music sends its analyser frames, every device listens. */
class BridgeRelay {
    private var ws: WebSocket? = null
    private var url: String? = null
    private val buf = FloatArray(1024)
    @Volatile var at = 0.0; private set          // last frame
    @Volatile var on = false; private set        // connected to the relay
    @Volatile var device = ""; private set
    private var want = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun start(u: String) {
        want = true
        if (ws != null && u == url) return
        stop(); want = true; url = u
        open()
    }

    private fun open() {
        val u = url ?: return
        if (!want || ws != null) return
        ws = Net.http.newWebSocket(Request.Builder().url(u).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { on = true }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { val m = JSONObject(text); if (m.has("device")) device = m.optString("device"); if (m.optString("type") == "bye") at = 0.0 }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val a = bytes.toByteArray(); val n = min(1024, a.size)
                synchronized(buf) { for (i in 0 until n) buf[i] = (a[i].toInt() and 0xff).toFloat() }
                at = now()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone(webSocket)
        })
    }

    private fun gone(w: WebSocket) {
        main.post { if (ws === w) { ws = null; on = false; if (want) main.postDelayed({ open() }, 5000) } }
    }

    fun stop() { want = false; ws?.let { val w = it; ws = null; runCatching { w.cancel() } }; on = false; at = 0.0 }
    fun live() = on && now() - at < 600
    fun copy(dst: FloatArray) = synchronized(buf) { System.arraycopy(buf, 0, dst, 0, 1024) }
}
