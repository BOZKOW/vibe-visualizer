package io.github.bozkow.vibevisualizer.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.engine.Background
import io.github.bozkow.vibevisualizer.engine.CanvasVideo
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Visuals
import io.github.bozkow.vibevisualizer.ui.theme.Bg
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** The background: the cover, alive (a liquid warp of it, as Spicy Lyrics' Kawarp; or drifting layers), a still
 *  image, a color, or the Canvas video blurred while one plays. */
@Composable
fun BackgroundLayer(modifier: Modifier = Modifier) {
    val style = S.bgStyle
    val cvlive = CanvasVideo.cvlive
    val cvA by animateFloatAsState(if (cvlive) 1f else 0f, tween(800), label = "cv")
    Box(modifier.fillMaxSize().background(Bg)) {
        if (cvA < 1f) Box(Modifier.fillMaxSize()) {
            when {
                style == "dynamic" && !S.low -> if (Build.VERSION.SDK_INT >= 33) LiquidBackground() else LayersBackground()
                style == "dynamic" -> BakedBackground()
                style == "artist" || style == "cover" -> StillBackground()
                style == "color" -> Box(Modifier.fillMaxSize().background(Visuals.plainColor()))
                else -> {}
            }
        }
        if (cvA > 0f) {
            CanvasBlur(cvA)
            Shade(cvA)
        }
    }
}

/** The baked background as is (Low performance mode): the blurred cover, stretched, no motion. */
@Composable
private fun BakedBackground() {
    Visuals.bakeVer
    val bm = Visuals.baked ?: return
    val img = remember(bm) { bm.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        val s = maxOf(size.width, size.height)
        drawImage(img, IntOffset.Zero, IntSize(bm.width, bm.height), IntOffset(((size.width - s) / 2).toInt(), ((size.height - s) / 2).toInt()), IntSize(s.toInt(), s.toInt()), filterQuality = FilterQuality.High)
    }
}

/* ---- Kawarp-style liquid warp (API 33+): the blurred cover, slowly flowing, moving with the music ---- */
private const val LIQUID = """
uniform shader img;
uniform shader prev;
uniform float2 res;
uniform float t;
uniform float mixv;
uniform float sat;
uniform float dim;
uniform float pulse;
half4 main(float2 fc) {
    float2 uv = fc / res;
    float aspect = res.x / res.y;
    float2 p = uv - 0.5;
    if (aspect > 1.0) { p.y /= aspect; } else { p.x *= aspect; }
    float a = t * 0.045;
    p = float2(p.x * cos(a) - p.y * sin(a), p.x * sin(a) + p.y * cos(a));
    p *= 0.82 - pulse * 0.05;
    float2 q = p;
    q += 0.055 * float2(sin(p.y * 3.1 + t * 0.7) + sin(p.y * 1.7 - t * 0.4), cos(p.x * 2.9 - t * 0.6) + cos(p.x * 1.3 + t * 0.5));
    q += 0.05 * float2(sin((p.x + p.y) * 4.3 + t * 0.9), cos((p.x - p.y) * 3.7 - t * 1.1));
    q += 0.025 * float2(sin(length(p) * 9.0 - t * 1.3), cos(length(p) * 7.0 + t));
    float2 st = clamp(q + 0.5, 0.0, 1.0) * 128.0;
    half4 c = mix(prev.eval(st), img.eval(st), mixv);
    half l = dot(c.rgb, half3(0.299, 0.587, 0.114));
    c.rgb = mix(half3(l), c.rgb, sat);
    c.rgb *= (1.0 - dim);
    c.rgb += pulse * 0.035;
    c.rgb += (fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;
    return half4(c.rgb, 1.0);
}
"""

@RequiresApi(33)
@Composable
private fun LiquidBackground() {
    Visuals.bakeVer
    val src = Visuals.kwSrc ?: return
    val prev = Visuals.kwPrev ?: src
    val shader = remember { RuntimeShader(LIQUID) }
    fun bs(b: Bitmap) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
    val imgS = remember(src) { bs(src) }
    val prevS = remember(prev) { bs(prev) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(Modifier.fillMaxSize()) {
        E.bgTick.longValue
        shader.setInputShader("img", imgS); shader.setInputShader("prev", prevS)
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("t", Background.kwTime.toFloat() * 2.2f)
        shader.setFloatUniform("mixv", Visuals.kwFade(now()))
        shader.setFloatUniform("sat", (1.5f * S.sat / 170f))
        shader.setFloatUniform("dim", (1f - S.bri / 100f).coerceIn(0f, .85f))
        shader.setFloatUniform("pulse", Background.pulse.coerceIn(0f, 1f))
        drawRect(brush)
    }
}

/** API 31-32: the web app's own fallback, three soft layers of the blurred cover that drift, spin and swell with the bass. */
private val BGL = arrayOf(floatArrayOf(1f, 1f, .06f, .05f, 1.25f, 1f), floatArrayOf(.7f, -1f, .22f, .18f, .95f, .7f), floatArrayOf(1.25f, 1f, .24f, .22f, .8f, .55f))

@Composable
private fun LayersBackground() {
    Visuals.bakeVer
    val sp = Visuals.sprites.map { it?.asImageBitmap() }
    val prev = Visuals.spritesPrev.map { it?.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        E.bgTick.longValue
        val w = size.width; val h = size.height; val pulse = Background.pulse; val D = hypot(w, h) * (1 + pulse * .18f); val t = Background.bgT.toFloat(); val W2 = 6.2832f / 40
        fun layers(imgs: List<androidx.compose.ui.graphics.ImageBitmap?>, alpha: Float) {
            imgs.forEachIndexed { i, img ->
                img ?: return@forEachIndexed
                val l = BGL[i]; val s = D * l[4]
                val cx = w / 2 + cos(t * .11f * (i + 1) + i * 2) * w * l[2]; val cy = h / 2 + sin(t * .09f * (i + 1) + i) * h * l[3]
                withTransform({ translate(cx, cy); rotate((t * W2 * l[0] * l[1] + i * 2.1f) * 57.2958f, Offset.Zero) }) {
                    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset((-s / 2).toInt(), (-s / 2).toInt()), IntSize(s.toInt(), s.toInt()), alpha = l[5] * alpha, filterQuality = FilterQuality.High)
                }
            }
        }
        val fade = Visuals.fadeProgress(now())
        layers(sp, 1f)
        if (fade < 1f && prev.any { it != null }) layers(prev, 1 - fade)
        if (pulse > .02f) drawRect(Color.White.copy(alpha = min(.12f, pulse * .1f)))
    }
}

/** Settings → Background: Artist image or Cover art, blurred, with brightness and saturation. */
@Composable
private fun StillBackground() {
    val bm = Background.still ?: return
    val img = remember(bm) { bm.asImageBitmap() }
    val m = remember(S.bri, S.sat) { ColorMatrix().apply { setToSaturation(S.sat / 100f) }.also { cm -> val b = S.bri / 100f; cm.timesAssign(ColorMatrix(floatArrayOf(b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) } }
    Image(img, null, Modifier.fillMaxSize().scale(1.12f).blur((S.bgblur * .6f).dp), contentScale = ContentScale.Crop, colorFilter = ColorFilter.colorMatrix(m))
}

/** The Canvas video, blurred, as the background (from small frames of it). */
@Composable
private fun CanvasBlur(alpha: Float) {
    CanvasVideo.blurVer
    val bm = CanvasVideo.blurred ?: return
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { c ->
            val s = maxOf(size.width, size.height)
            val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() }
            c.nativeCanvas.drawBitmap(bm, null, android.graphics.RectF((size.width - s) / 2, (size.height - s) / 2, (size.width + s) / 2, (size.height + s) / 2), p)
        }
    }
}

@Composable
private fun Shade(alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = .15f * alpha), Color.Black.copy(alpha = .55f * alpha)), Offset(size.width * .7f, size.height * .5f), maxOf(size.width, size.height) * .8f))
    }
}
