package io.github.bozkow.vibevisualizer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.Config
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.FilePicker
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.engine.Ping
import io.github.bozkow.vibevisualizer.engine.Sync
import io.github.bozkow.vibevisualizer.spotify.Spotify
import io.github.bozkow.vibevisualizer.spotify.SpotifyApp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The first-run setup guide: what you want to use, your server, Spotify, lyrics, sound for the bars, a few choices. */
@Composable
fun SetupWizard() {
    var useFile by remember { mutableStateOf(true) }
    var useSp by remember { mutableStateOf(Vibe.cfg.spotId.isNotEmpty() || true) }
    val steps = listOf("welcome", "server") + (if (useSp) listOf("spotify") else emptyList()) + listOf("lyrics", "sound", "finish")
    var i by remember { mutableIntStateOf(0) }
    val cur = steps[i.coerceIn(0, steps.size - 1)]
    val done = { Vibe.cfg.saveSetup(true); E.wizard = false }
    BackHandler { if (i > 0) i-- else done() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { steps.indices.forEach { k -> Box(Modifier.size(if (k == i) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (k <= i) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .2f))) } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = done) { Text("Skip setup") }
            }
            AnimatedContent(cur, Modifier.weight(1f), transitionSpec = { (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut()) }, label = "wz") { st ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        when (st) {
                            "welcome" -> Welcome(useFile, useSp, { useFile = it }, { useSp = it })
                            "server" -> ServerStep()
                            "spotify" -> SpotifyStep()
                            "lyrics" -> LyricsStep()
                            "sound" -> SoundStep()
                            else -> FinishStep()
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (cur == "finish" && (useFile || (useSp && !Spotify.connected))) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (useFile) FilledTonalButton(onClick = { done(); FilePicker.pickAudio() }, Modifier.weight(1f)) { Text("Play songs", maxLines = 1) }
                    if (useSp && !Spotify.connected) FilledTonalButton(onClick = { done(); E.spLogin() }, Modifier.weight(1f)) { Text("Connect Spotify", maxLines = 1) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (i > 0) TextButton(onClick = { i-- }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (i >= steps.size - 1) { done(); if (Spotify.connected) E.setSource("spotify") } else i++ }, enabled = !(cur == "welcome" && !useFile && !useSp)) {
                        Text(if (i >= steps.size - 1) "Start" else "Next")
                    }
                }
            }
        }
    }
}

@Composable private fun H(t: String) = Text(t, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.4).sp, color = Color.White, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
@Composable private fun P(t: String) = Text(t, fontSize = 15.sp, lineHeight = 21.sp, color = Color.White.copy(alpha = .78f), modifier = Modifier.padding(bottom = 8.dp))
@Composable private fun Step(n: Int, t: String, ok: Boolean = false, body: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (ok) Color(0xFF1ED760) else Color.White.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            if (ok) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(26.dp), tint = Color(0xFF0B3D1E)) else Text("$n", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(t, fontSize = 14.5.sp, lineHeight = 20.sp, color = Color.White); body() }
    }
}

@Composable
private fun Welcome(f: Boolean, s: Boolean, setF: (Boolean) -> Unit, setS: (Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { AppLogo(80.dp, Modifier.padding(top = 20.dp)) }
    H("Welcome to Vibe Visualizer")
    P("A quick setup to get you going. Pick what you want to use; you can change anything later in Settings.")
    @Composable fun opt(on: Boolean, t: String, d: String, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(20.dp)).background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = .06f)).clickable { set(!on) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White); Text(d, fontSize = 13.sp, color = Color.White.copy(alpha = .7f)) }
            Checkbox(on, null)
        }
    }
    opt(f, "Your own audio files", "MP3, FLAC, WAV and more, with cover art and synced lyrics. They keep playing in the background.", setF)
    opt(s, "Spotify", "Follow what you’re playing on Spotify, with its cover, Canvas and lyrics.", setS)
}

@Composable
fun ServerField(onDone: () -> Unit = {}) {
    var t by remember { mutableStateOf(Vibe.cfg.server.removePrefix("https://")) }
    var stat by remember { mutableStateOf("") }
    var h by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    fun test() {
        Vibe.cfg.saveServer(t)
        val b = Server.base ?: run { stat = "Enter your server’s address."; return }
        stat = "Checking your server…"; h = null
        scope.launch {
            try {
                val r = Net.fetch("$b/api/health", noCache = true)
                h = r.json(); stat = if (r.ok && h != null) "✓ Your server answers." else "Your server answered, but not like Vibe Visualizer (HTTP ${r.code})."
                if (r.ok) { Sync.importFromServer { Lyr.refreshCounts() }; Ping.kick(); onDone() }
            } catch (e: Exception) {
                stat = "Couldn’t reach it: " + (e.message ?: "") + when { Net.privateDns.value -> ". Private DNS is on: it hides Tailscale names (Settings → Connections → More connection settings → Private DNS → Off)."; !Net.vpn.value -> ". Is Tailscale connected on this phone?"; else -> "." }
            }
        }
    }
    OutlinedTextField(t, { t = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Your server") }, placeholder = { Text("name.your-tailnet.ts.net") }, singleLine = true, prefix = { Text("https://") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { test() }))
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { test() }) { Text("Test and save") }
        Spacer(Modifier.width(12.dp))
        Text(stat, fontSize = 13.sp, color = Color.White.copy(alpha = .8f), modifier = Modifier.weight(1f))
    }
    h?.let { j ->
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(if (j.optBoolean("spicy_key")) "✓ Spicy Lyrics key found" else "Spicy Lyrics key not set yet", j.optBoolean("spicy_key"))
            Tag(if (j.optBoolean("logged_in")) "✓ Spotify cookie works" else "Spotify cookie not set (optional)", j.optBoolean("logged_in"))
            Tag("✓ LRCLIB and NetEase ready", true)
        }
    }
}

@Composable
fun Tag(t: String, ok: Boolean) {
    Text(t, Modifier.clip(RoundedCornerShape(50)).background(if (ok) Color(0xFF1ED760).copy(alpha = .18f) else Color.White.copy(alpha = .08f)).padding(horizontal = 12.dp, vertical = 6.dp),
        fontSize = 12.5.sp, color = if (ok) Color(0xFF8CF2B4) else Color.White.copy(alpha = .75f), fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ServerStep() {
    H("Your Vibe Visualizer server")
    P("Spicy Lyrics, Spotify’s own lyrics and Canvas videos, and NetEase lyrics come from your server, over Tailscale. Keep Tailscale connected on this phone. Your keys stay on the server; the app never sees them.")
    ServerField()
    Spacer(Modifier.height(10.dp))
    Hint("Without a server, LRCLIB, AMLL and your own lyrics files still work, and so does everything else. Settings you kept on the server for this phone (from the old app) come along when it’s found.")
}

@Composable
private fun SpotifyStep() {
    H("Connect Spotify")
    P("Vibe Visualizer talks to Spotify through its official Web API, with the free developer app you made for the web app (or a new one: it takes about two minutes).")
    val redirect = Spotify.redirectUri()
    var id by remember { mutableStateOf(Vibe.cfg.spotId) }
    Step(1, "Create an app in the Spotify Developer Dashboard (any name; tick Web API), or open the one you have.") {
        TextButton(onClick = { openUrl("https://developer.spotify.com/dashboard") }) { Text("Developer Dashboard") }
    }
    Step(2, "Make sure this Redirect URI is in the app’s settings (Settings → Edit → Redirect URIs). " + if (Server.set) "It’s your server’s address, the same the web app uses: Spotify comes back to your server’s page, which hands the login to this app." else "") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(redirect, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .08f)).padding(10.dp), fontSize = 13.sp, color = Color.White)
            IconButton(onClick = { Lyr.copy(redirect); E.copied("Redirect URI") }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
        }
    }
    Step(3, "Paste the Client ID (Settings → Basic Information). No client secret is needed (PKCE).", ok = id.length >= 32) {
        OutlinedTextField(id, { id = it.trim(); Vibe.cfg.saveSpotId(id) }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, label = { Text("Client ID") })
    }
    Step(4, "Your Spotify account must be listed under the app’s User Management (development mode).")
    Step(5, "Connect and allow access. Play, pause, skip and seek through the Web API need Spotify Premium; with the Spotify app on this phone they work without it.", ok = Spotify.connected) {
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { E.spLogin() }, enabled = !Spotify.connected && id.isNotEmpty()) { Text(if (Spotify.connected) "Connected ✓" else "Connect now") }
        }
    }
}

@Composable
private fun LyricsStep() {
    H("Lyrics sources")
    P("Vibe Visualizer tries these in order and uses the first that has the song. Change the order or switch sources off in Settings → Lyrics.")
    val h = Ping.health
    Step(1, "Spicy Lyrics: the best ones, word by word, with background vocals and duets. Needs your key in the server’s config folder (spicy_key).", ok = h?.optBoolean("spicy_key") == true)
    Step(2, "Spotify’s own lyrics and Canvas videos (optional, unofficial): the server’s sp_dc cookie.", ok = h?.optBoolean("logged_in") == true)
    Step(3, "LRCLIB and NetEase Cloud Music: no setup. AMLL TTML DB too. You can also add your own .lrc or .ttml files.", ok = true)
    Step(4, "Translation and romanization: the あa and translate buttons at the top of the lyrics (they appear when a song needs them). Pick the language in Settings → Translation.", ok = true)
    Step(5, "Timing: the lyrics follow Spotify closely (exactly, with the Spotify app on this phone). ⋮ → Lyrics timing moves them earlier or later.", ok = true)
}

@Composable
private fun SoundStep() {
    H("Sound for the bars")
    P("The bars react to sound. Your own files work straight away. For Spotify, the phone can listen to what it plays.")
    val perm = remember { mutableStateOf(Audio.permitted()) }
    Step(1, "Phone sound: everything this phone plays, the Spotify app included. Android asks for microphone access, which is only how apps may read the sound’s spectrum; nothing is recorded.", ok = perm.value) {
        if (!perm.value) FilledTonalButton(onClick = { Perms.mic { ok -> perm.value = ok; if (ok) S.set("aCap", "phone") } }, Modifier.padding(top = 6.dp)) { Text("Allow") }
    }
    val acc = SpotifyApp.access.value
    Step(2, "Exact timing and controls from the Spotify app on this phone (no Premium needed for play, pause, skip and seek): allow notification access. No notification is read; it only lets the app see the Spotify app’s player.", ok = acc) {
        if (!acc) FilledTonalButton(onClick = { Perms.notificationAccess() }, Modifier.padding(top = 6.dp)) { Text("Open notification access") }
    }
    Step(3, "Music on your computer: run Vibe Bridge there with your server’s address, and the bars here follow it too.", ok = Audio.bridgeOn)
    LaunchedEffect(Unit) { SpotifyApp.refresh(Vibe.app) }
}

@Composable
private fun FinishStep() {
    H("You’re all set")
    P("Last couple of preferences.")
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Bars under the lyrics", fontWeight = FontWeight.Bold, color = Color.White); Text("The visualizer as a strip below the lyrics, on the Lyrics screen.", fontSize = 13.sp, color = Color.White.copy(alpha = .65f)) }
        Switch(S.view == "both", { S.set("view", if (it) "both" else "lyrics") })
    }
    Text("Lyrics credits", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("corner" to "Bottom-left corner", "lyrics" to "Under the lyrics").forEach { (k, l) ->
            if (S.credPos == k) Button(onClick = {}) { Text(l) } else FilledTonalButton(onClick = { S.set("credPos", k) }) { Text(l) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Low performance mode", fontWeight = FontWeight.Bold, color = Color.White); Text("Turns off blur and glow effects. Useful on older phones.", fontSize = 13.sp, color = Color.White.copy(alpha = .65f)) }
        Switch(S.low, { S.set("low", it) })
    }
    Text("Good to know", fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 16.dp))
    Hint("Lyrics and Visualizer are buttons under the player · tap a lyric line to jump there · swipe the lyrics to look around · tap the screen to bring the controls back · ⋮ has translation, timing and the rest.")
}

@Suppress("unused") private val keepCfg = Config
