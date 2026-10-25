package io.github.bozkow.vibevisualizer.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.engine.E
import kotlinx.coroutines.launch

/** Menus open out of the button that opened them: the root notes where each touch goes down, and Settings, its
 *  pages, the sheets and the dialogs grow from that point (and shrink back into it when they close). */
object Pop {
    var at = Offset.Unspecified; var time = 0L; var root = IntSize.Zero

    fun touched(p: Offset) { at = p; time = SystemClock.uptimeMillis() }

    /** The last touch as a point of [box] (root pixels; the whole screen when null), or the middle when nothing was
     *  touched just now (a key, a shortcut, something the app opened by itself). */
    fun origin(box: Rect? = null): TransformOrigin {
        val b = box ?: Rect(0f, 0f, root.width.toFloat(), root.height.toFloat())
        if (!at.isSpecified || SystemClock.uptimeMillis() - time > 1500 || b.width <= 0f || b.height <= 0f) return TransformOrigin.Center
        return TransformOrigin(((at.x - b.left) / b.width).coerceIn(0f, 1f), ((at.y - b.top) / b.height).coerceIn(0f, 1f))
    }

    val calm get() = S.low || E.reduceMotion()
}

/** Grows out of [o] with a soft spring (a fade when animations are off). */
fun popIn(o: TransformOrigin, from: Float = .08f): EnterTransition =
    if (Pop.calm) fadeIn(tween(150)) else scaleIn(spring(.86f, 400f), initialScale = from, transformOrigin = o) + fadeIn(tween(150))

/** Shrinks back into [o]. */
fun popOut(o: TransformOrigin, to: Float = .08f): ExitTransition =
    if (Pop.calm) fadeOut(tween(120)) else scaleOut(tween(240, easing = FastOutSlowInEasing), targetScale = to, transformOrigin = o) + fadeOut(tween(170, delayMillis = 70))

/** The point a menu opened from, kept for as long as it's open (so it closes back into the same button). */
@Composable
fun rememberPopOrigin(open: Boolean, box: () -> Rect? = { null }): TransformOrigin {
    val h = remember { object { var o = TransformOrigin.Center; var was = false } }
    if (open && !h.was) h.o = Pop.origin(box())
    h.was = open
    return h.o
}

/** Rounded while it's small, square once it fills its place. */
@Composable
fun AnimatedVisibilityScope.popClip(radius: Dp = 120.dp): Modifier {
    val r by transition.animateDp(label = "popr") { if (it == EnterExitState.Visible) 0.dp else radius }
    return if (r > 0.5.dp) Modifier.clip(RoundedCornerShape(r)) else Modifier
}

/** Places where a touch doesn't bring the hidden controls back (the seek bar: touched then, it only seeks). */
object Quiet {
    private val areas = HashMap<Any, Rect>()
    fun has(p: Offset) = areas.values.any { it.contains(p) }
    fun set(k: Any, r: Rect?) { if (r == null) areas.remove(k) else areas[k] = r }
}

@Composable
fun Modifier.quietTouch(): Modifier {
    val k = remember { Any() }
    androidx.compose.runtime.DisposableEffect(k) { onDispose { Quiet.set(k, null) } }
    return onGloballyPositioned { Quiet.set(k, it.boundsInRoot()) }
}

/** Where a box sits on the screen, for [Pop.origin]. */
fun Modifier.popBounds(into: (Rect) -> Unit): Modifier = onGloballyPositioned { into(it.boundsInRoot()) }

/** A sheet at the bottom of the screen that pops out of the button that opened it. A tap beside it, Back or a pull
 *  down on its handle closes it. [key] is what it shows (null: closed); [content] keeps showing the last one while it
 *  closes. */
@Composable
fun <T : Any> PopSheet(key: T?, onDismiss: () -> Unit, color: Color, content: @Composable (T) -> Unit) {
    val vis = remember { MutableTransitionState(false) }; vis.targetState = key != null
    val last = remember { object { var k: Any? = null } }; if (key != null) last.k = key
    val o = rememberPopOrigin(key != null)
    if (key != null) BackHandler(onBack = onDismiss)
    if (!vis.currentState && !vis.targetState) return
    @Suppress("UNCHECKED_CAST") val k = (key ?: last.k) as T? ?: return
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val d = LocalDensity.current
    androidx.compose.runtime.LaunchedEffect(key != null) { if (key != null) drag.snapTo(0f) }   // pulled away last time: back in place
    // one transition for the dim behind and the sheet, so both finish before it's gone
    AnimatedVisibility(vis, enter = EnterTransition.None, exit = ExitTransition.None) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxH = maxHeight * .9f
            Box(Modifier.fillMaxSize().animateEnterExit(enter = fadeIn(tween(220)), exit = fadeOut(tween(220))).background(Color.Black.copy(alpha = .38f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
            Column(Modifier.align(Alignment.BottomCenter).animateEnterExit(enter = popIn(o, .12f), exit = popOut(o, .12f)).then(popClip(64.dp))
                .widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxH).graphicsLayer { translationY = drag.value }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(color)
                .clickable(remember { MutableInteractionSource() }, null) {}) {   // taps on the sheet stay on it
                // the handle: pull it down to close
                Box(Modifier.fillMaxWidth().height(28.dp).draggable(rememberDraggableState { dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }, Orientation.Vertical,
                    onDragStopped = { v -> if (drag.value > with(d) { 96.dp.toPx() } || v > 1800f) onDismiss() else drag.animateTo(0f, spring(.8f, 500f)) }), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .4f)))
                }
                content(k)
            }
        }
    }
}

/** A question in the app's own dialog, popping out of the button that asked it. */
@Composable
fun PopDialog(open: Boolean, onDismiss: () -> Unit, title: String, text: String, confirm: Pair<String, () -> Unit>?, dismiss: Pair<String, () -> Unit>?) {
    val vis = remember { MutableTransitionState(false) }; vis.targetState = open
    val last = remember { object { var t = ""; var x = ""; var c: Pair<String, () -> Unit>? = null; var d: Pair<String, () -> Unit>? = null } }
    if (open) { last.t = title; last.x = text; last.c = confirm; last.d = dismiss }
    val o = rememberPopOrigin(open)
    if (open) BackHandler(onBack = onDismiss)
    if (!vis.currentState && !vis.targetState) return
    AnimatedVisibility(vis, enter = EnterTransition.None, exit = ExitTransition.None) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().animateEnterExit(enter = fadeIn(tween(200)), exit = fadeOut(tween(200))).background(Color.Black.copy(alpha = .45f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
            Column(Modifier.animateEnterExit(enter = popIn(o, .2f), exit = popOut(o, .2f)).padding(32.dp).widthIn(min = 280.dp, max = 560.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(remember { MutableInteractionSource() }, null) {}.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 18.dp)) {
                Text(last.t, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(last.x, Modifier.padding(top = 16.dp), fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                    last.d?.let { (l, f) -> TextButton(onClick = f) { Text(l) } }
                    last.c?.let { (l, f) -> TextButton(onClick = f) { Text(l, fontWeight = FontWeight.Bold) } }
                }
            }
        }
    }
}
