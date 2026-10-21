package io.github.bozkow.vibevisualizer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** The same frequency data a browser's Web Audio AnalyserNode gives (fftSize 2048, Blackman window, smoothing,
 *  getByteFrequencyData between -100 and -30 dB), so the web app's bar maths carries over unchanged. */
class Analyser(private val n: Int = 2048) {
    private val half = n / 2
    private val win = FloatArray(n) { i -> (0.42 - 0.5 * cos(2 * PI * i / n) + 0.08 * cos(4 * PI * i / n)).toFloat() }
    private val re = FloatArray(n); private val im = FloatArray(n)
    private val smooth = FloatArray(half)
    private val rev = IntArray(n).also { r -> val bits = Integer.numberOfTrailingZeros(n); for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits) }
    private val cosT = FloatArray(half) { cos(-2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(half) { sin(-2 * PI * it / n).toFloat() }
    private val minDb = -100f; private val maxDb = -30f

    fun reset() { smooth.fill(0f) }

    /** samples: the newest n samples (mono, -1..1); tau: smoothing (0.35 at 60 fps, scaled to the frame time). */
    fun compute(samples: FloatArray, tau: Float, out: FloatArray) {
        for (i in 0 until n) { val j = rev[i]; re[j] = samples[i] * win[i]; im[j] = 0f }
        var size = 2
        while (size <= n) {
            val hs = size / 2; val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + hs) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val xr = re[j + hs] * wr - im[j + hs] * wi; val xi = re[j + hs] * wi + im[j + hs] * wr
                    re[j + hs] = re[j] - xr; im[j + hs] = im[j] - xi; re[j] += xr; im[j] += xi
                    k += step
                }
                i += size
            }
            size *= 2
        }
        val inv = 1f / n
        for (k in 0 until half) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * inv
            val s = tau * smooth[k] + (1 - tau) * mag
            smooth[k] = if (s.isFinite()) s else 0f
            val db = if (smooth[k] > 0f) 20f * log10(smooth[k]) else -1000f
            out[k] = (255f * (db - minDb) / (maxDb - minDb)).coerceIn(0f, 255f)
        }
    }
}
