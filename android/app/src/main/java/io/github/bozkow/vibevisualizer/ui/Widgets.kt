package io.github.bozkow.vibevisualizer.ui

import io.github.bozkow.vibevisualizer.core.S
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import kotlin.math.PI
import kotlin.math.sin

private val playMorph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }

/** Material 3 Expressive shape morphing: a circle that turns into a cookie while music plays. */
fun morphShape(progress: Float): Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = playMorph.toPath(progress.coerceIn(0f, 1f)).asComposePath()
        val m = androidx.compose.ui.graphics.Matrix(); m.scale(size.width, size.height); p.transform(m)
        return Outline.Generic(p)
    }
}

/** A seek bar whose played part is a wave while music plays (as Android's own media controls), with a pill thumb. */
@Composable
fun WavySeek(frac: Float, wavy: Boolean, onDrag: (Float) -> Unit, onDone: (Float) -> Unit, enabled: Boolean) {
    val tr = rememberInfiniteTransition(label = "wave")
    val phase by tr.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    val amp by animateFloatAsState(if (wavy) 1f else 0f, tween(450), label = "amp")
    val drag by rememberUpdatedState(onDrag); val done by rememberUpdatedState(onDone)
    var last by remember { mutableFloatStateOf(0f) }
    val one = S.oneUi
    Canvas(Modifier.fillMaxWidth().height(30.dp)
        .semantics {
            contentDescription = "Seek"; progressBarRangeInfo = ProgressBarRangeInfo(frac, 0f..1f)
            if (enabled) setProgress { v -> done(v.coerceIn(0f, 1f)); true }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures { o -> done((o.x / size.width).coerceIn(0f, 1f)) }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); drag(last) }, onDragEnd = { done(last) }, onDragCancel = { done(last) }) { ch, _ ->
                last = (ch.position.x / size.width).coerceIn(0f, 1f); drag(last)
            }
        }) {
        val y = size.height / 2; val w = size.width; val x = (frac.coerceIn(0f, 1f) * w)
        if (one) {   // One UI (Samsung Music): a thin line and a round thumb, no wave
            val t = 3.dp.toPx()
            drawLine(Color.White.copy(alpha = if (enabled) .25f else .12f), Offset(0f, y), Offset(w, y), t, StrokeCap.Round)
            if (x > 0) drawLine(Color.White, Offset(0f, y), Offset(x, y), t, StrokeCap.Round)
            if (enabled) drawCircle(Color.White, 6.dp.toPx(), Offset(x, y))
            return@Canvas
        }
        val sw = 4.dp.toPx(); val gap = 5.dp.toPx(); val a = 3.dp.toPx() * amp; val wl = 26.dp.toPx()
        if (x + gap < w) drawLine(Color.White.copy(alpha = if (enabled) .25f else .12f), Offset(x + gap, y), Offset(w, y), sw, StrokeCap.Round)
        if (x - gap > 0) {
            val p = Path(); var px = 0f
            p.moveTo(0f, y + sin(-phase) * a)
            while (px < x - gap) { px = minOf(px + 3f, x - gap); p.lineTo(px, y + sin(px / wl * 2 * PI.toFloat() - phase) * a) }
            drawPath(p, Color.White, style = Stroke(sw, cap = StrokeCap.Round))
        }
        if (enabled) drawRoundRect(Color.White, Offset(x - 2.dp.toPx(), y - 10.dp.toPx()), Size(4.dp.toPx(), 20.dp.toPx()), CornerRadius(2.dp.toPx()))
    }
}
