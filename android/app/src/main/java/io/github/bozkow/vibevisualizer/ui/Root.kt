package io.github.bozkow.vibevisualizer.ui

import androidx.compose.animation.AnimatedVisibility
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
        LaunchedEffect(Unit) { while (true) withFrameNanos { E.frame(now()) } }
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {   // a touch brings the controls back; while they're hidden, the first tap only does that
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                E.wakeTap = E.uiHidden
                E.poke()
            }
        }) {
            BackgroundLayer()
            if (E.inPip) PipScreen()
            else {
                PlayerScreen()
                Snacks(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp))
                if (S.b("fps")) Text(E.fpsText, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 60.dp, end = 12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White.copy(alpha = .7f))
                Sheets()
                AskDialog()
                AnimatedVisibility(E.settingsPage != null, enter = slideInHorizontally { it / 3 } + fadeIn(), exit = slideOutHorizontally { it / 3 } + fadeOut()) { SettingsScreen() }
                AnimatedVisibility(E.wizard, enter = fadeIn() + slideInVertically { it / 8 }, exit = fadeOut() + slideOutVertically { it / 8 }) { SetupWizard() }
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
