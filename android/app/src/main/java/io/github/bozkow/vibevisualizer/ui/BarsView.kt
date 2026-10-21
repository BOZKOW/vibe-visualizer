package io.github.bozkow.vibevisualizer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipRect
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Visuals
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The bar visualizer, drawn as the web app draws it: a soft dark edge so the bars never melt into the background, an
 *  optional glow, rounded bars that rest as small dots, the colors of the cover, and optional motion blur. */
@Composable
fun BarsView(modifier: Modifier = Modifier) {
    val st = remember { BarsState() }
    Canvas(modifier) {
        E.barsTick.longValue
        val n = Audio.N; val vals = Audio.vals; val pal = Visuals.pal
        if (n == 0 || vals.size < n || pal.size < n || size.width <= 0f) return@Canvas
        val W = size.width; val H = size.height
        val glow = S.b("glow") && !S.low
        val edge = ceil(W / n * (if (glow) .5f else .18f)) + 1; val step = (W - edge * 2) / n; val gp = step * S.i("gap") / 100f
        val bw = max(1f, step - gp); val maxH = H * .9f; val by = H * .97f; val rd = S.b("round") && bw > 2; val r = bw / 2
        val brush = st.brush(pal, W)
        // one path, emptied and refilled for each pass (a drawn path is copied when it's recorded), and the bars added
        // without a new object each
        fun pass(v: FloatArray, gr: Float): Path {
            val p = st.path; p.rewind(); val ap = p.asAndroidPath(); val hMin = if (rd) bw else 3f
            for (i in 0 until n) {
                val h = max(hMin, v[i] * maxH); val x = edge + i * step + gp / 2 - gr; val y = by - h - gr; val hh = h + gr * 2; val ww = bw + gr * 2
                if (rd) { val cr = min(r + gr, hh / 2); ap.addRoundRect(x, y, x + ww, y + hh, cr, cr, android.graphics.Path.Direction.CW) } else ap.addRect(x, y, x + ww, y + hh, android.graphics.Path.Direction.CW)
            }
            return p
        }
        // motion blur: the last frames, fading (the web app fades the canvas a little every frame)
        val blur = if (S.low) 0 else S.i("blur")
        if (blur > 0) {
            st.push(vals, n)
            val keep = blur / 100f * .9f
            var a = .95f * keep
            for (k in 1 until st.hist.size) { if (a < .03f) break; drawPath(pass(st.hist[st.hist.size - 1 - k], 0f), brush, alpha = a); a *= keep }
        } else st.hist.clear()
        if (!S.low) clipRect(0f, 0f, W, H) {
            drawPath(pass(vals, max(1f, bw * .14f)), Color.Black, alpha = .14f)
            if (glow) drawPath(pass(vals, bw * .4f), brush, alpha = .16f)
        }
        drawPath(pass(vals, 0f), brush, alpha = .95f)
    }
}

private class BarsState {
    private var key: IntArray? = null; private var w = 0f; private var b: Brush? = null
    val hist = ArrayList<FloatArray>()
    val path = Path()
    fun brush(pal: IntArray, width: Float): Brush {
        if (b != null && key === pal && w == width) return b!!
        key = pal; w = width
        val n = pal.size
        b = Brush.horizontalGradient(*Array(n) { i -> ((i + .5f) / n) to Color(pal[i]) }, startX = 0f, endX = width)
        return b!!
    }
    fun push(v: FloatArray, n: Int) { hist.add(v.copyOf(n)); while (hist.size > 40) hist.removeAt(0) }
    @Suppress("unused") fun pow(x: Float) = x.pow(1)
}
