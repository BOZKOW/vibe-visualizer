package io.github.bozkow.vibevisualizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.FilePicker
import io.github.bozkow.vibevisualizer.spotify.Spotify

/** The home screen (the logo opens it): the start page, with a way back to the player. */
@Composable
fun HomeScreen() {
    Box(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppLogo(88.dp)
            Spacer(Modifier.height(18.dp))
            Text("Vibe Visualizer", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.6).sp, color = Color.White)
            Text("Play your own songs, or follow what you’re playing on Spotify, with its cover, an audio-reactive visualizer and word-synced lyrics.",
                Modifier.padding(top = 10.dp), fontSize = 15.sp, lineHeight = 21.sp, color = Color.White.copy(alpha = .72f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (E.src != "none") Button(onClick = { E.haptic(); E.leaveHome() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Spacer(Modifier.padding(3.dp)); Text("Back to player") }
                FilledTonalButton(onClick = { E.haptic(); FilePicker.pickAudio() }) { Icon(Icons.Rounded.AudioFile, null); Spacer(Modifier.padding(3.dp)); Text("Choose audio files") }
                val spOn = Spotify.connected
                (if (spOn && E.src == "spotify") null else true)?.let {
                    Button(onClick = { E.haptic(); if (spOn) E.setSource("spotify") else E.spLogin() }) {
                        Icon(VIcons.spotify, null); Spacer(Modifier.padding(3.dp)); Text(if (spOn) "Use Spotify" else "Connect Spotify")
                    }
                }
                if (spOn && E.src == "spotify") FilledTonalButton(onClick = {}, enabled = false) { Text("Spotify is connected") }
            }
            val probs = E.problems(true)
            if (probs.isNotEmpty()) { Spacer(Modifier.height(20.dp)); Problems(probs.take(3), false) }
            Spacer(Modifier.height(20.dp))
            Hint("You can also open a song from your file manager with Vibe Visualizer, or share one to it, with its .lrc or .ttml lyrics file.")
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { E.leaveHome(); E.wizard = true }) { Text("Setup guide") }
                Text("·", color = Color.White.copy(alpha = .4f))
                TextButton(onClick = { E.leaveHome(); E.openSettings() }) { Text("Settings") }
                Text("·", color = Color.White.copy(alpha = .4f))
                TextButton(onClick = { E.leaveHome(); E.openSettings("help") }) { Text("What’s new") }
            }
        }
    }
}
