package io.github.bozkow.vibevisualizer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.remember
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.ui.theme.VibeTheme

@Composable
fun VibeRoot() {
    VibeTheme {
        // the frame loop: one clock for everything (the same one the rest of the app reads)
        // paused and untouched, it waits a little between frames (about 30 a second): less battery when the app stays open
        LaunchedEffect(Unit) { while (true) { if (E.idle()) kotlinx.coroutines.delay(25); withFrameNanos { E.frame(now()) } } }
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {   // a touch brings the controls back; while they're hidden, the first tap only does that
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                E.wakeTap = E.uiHidden || E.ambient
                E.poke()
            }
        }) {
            // while Settings or the setup guide cover the screen, the player isn't drawn at all (it would cost every frame)
            val setT = remember { MutableTransitionState(false) }; setT.targetState = E.settingsPage != null
            val wizT = remember { MutableTransitionState(false) }; wizT.targetState = E.wizard
            val covered = (setT.currentState && setT.isIdle) || (wizT.currentState && wizT.isIdle)
            // Glass: the player blurs behind the sheets
            val qBlur by animateFloatAsState(if (S.glass && E.sheet != null && !S.low) 22f else 0f, tween(260), label = "qblur")
            if (E.inPip) { BackgroundLayer(); PipCanvas(); PipScreen() }
            else {
                if (!covered) Box(Modifier.fillMaxSize().then(if (qBlur > .1f) Modifier.blur(qBlur.dp) else Modifier)) { BackgroundLayer(); PlayerScreen() }
                Snacks(Modifier.align(Alignment.BottomCenter).navBarsSpace().padding(bottom = 8.dp))
                if (S.b("fps")) Text(E.fpsText, Modifier.align(Alignment.TopEnd).statusBarsSpace().padding(top = 60.dp, end = 12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
                // Ambient mode: the waking touch only wakes (nothing under it is tapped or seeked)
                if (E.ambient) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume(); do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed }) }
                })
                Sheets()
                AskDialog()
                AnimatedVisibility(setT, enter = slideInHorizontally(tween(320)) { it }, exit = slideOutHorizontally(tween(260)) { it }) { SettingsScreen() }
                AnimatedVisibility(wizT, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it }) { SetupWizard() }
            }
        }
    }
}

/** Picture-in-picture: the lyrics in a small window over your other apps, with the bars under them. */
@Composable
private fun PipScreen() {
    Column(Modifier.fillMaxSize()) {
        if (Lyr.lines > 0) LyricsPane(Modifier.fillMaxWidth().weight(1f), compact = true, padStart = 12f, padEnd = 12f)
        else Box(Modifier.fillMaxWidth().weight(1f).padding(12.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Text(E.title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(E.artist, fontSize = 13.sp, color = Color.White.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BarsView(Modifier.fillMaxWidth().fillMaxHeight(.22f).padding(horizontal = 6.dp))
    }
}
