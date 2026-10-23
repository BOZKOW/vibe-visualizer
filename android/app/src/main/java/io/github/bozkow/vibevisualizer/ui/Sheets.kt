package io.github.bozkow.vibevisualizer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.PhotoSizeSelectSmall
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.ago
import io.github.bozkow.vibevisualizer.engine.Desync
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.CanvasVideo
import io.github.bozkow.vibevisualizer.media.Outputs
import io.github.bozkow.vibevisualizer.engine.FilePicker
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.engine.Ping
import io.github.bozkow.vibevisualizer.spotify.SpDevice
import io.github.bozkow.vibevisualizer.spotify.Spotify
import io.github.bozkow.vibevisualizer.spotify.SpotifyApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheets() {
    val s = E.sheet?.takeIf { it != "quick" } ?: return   // quick settings float on their own (Quick.kt)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = { E.sheet = null; Ping.open = false; E.poke() }
    ModalBottomSheet(onDismissRequest = close, sheetState = state, containerColor = if (S.glass) Color(0xFF0E0E14).copy(alpha = .72f) else MaterialTheme.colorScheme.surfaceContainer, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().then(if (S.glass) Modifier.fadeEdges(16.dp, 16.dp) else Modifier).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 18.dp).navigationBarsPadding()) {
            when (s) {
                "audio" -> AudioSheet()
                "net" -> NetSheet()
                "devices" -> DevicesSheet(close)
                "timing" -> TimingSheet()
            }
        }
    }
}

@Composable
private fun SheetTitle(t: String, note: String = "") {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(t, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.weight(1f))
        if (note.isNotEmpty()) Text(note, fontSize = 12.sp, color = Color.White.copy(alpha = .5f))
    }
}

/* ---- the ⋮ menu: everything that isn't on the player itself ---- */
@Composable
private fun MoreRow(icon: ImageVector, title: String, sub: String? = null, checked: Boolean? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = sub?.let { { Text(it, color = Color.White.copy(alpha = .6f)) } },
        leadingContent = { Icon(icon, null, tint = Color.White.copy(alpha = .85f)) },
        trailingContent = checked?.let { c -> { Switch(c, { E.haptic(); onClick() }) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { E.haptic(); onClick() },
    )
}

/* ---- what the bars hear ---- */
@Composable
private fun AudioSheet() {
    SheetTitle("Make the bars react to…")
    val cur = S.str("aCap")
    @Composable
    fun opt(k: String, icon: ImageVector, t: String, d: String) {
        val on = cur == k
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = .05f))
            .clickable {
                E.haptic()
                if (k != "off" && !Audio.permitted()) Perms.mic { ok -> if (ok) S.set("aCap", k) else E.toast("Without microphone access the bars can’t hear the phone", "warn") }
                else S.set("aCap", k)
            }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(26.dp), tint = Color.White)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                Text(d, fontSize = 12.5.sp, color = Color.White.copy(alpha = .65f), lineHeight = 17.sp)
            }
            RadioButton(selected = on, onClick = null)
        }
    }
    opt("phone", Icons.Rounded.PhoneAndroid, "Phone sound", "Everything this phone plays, the Spotify app included. Android asks for microphone access: that’s only how apps may read the sound’s spectrum. Nothing is recorded or sent.")
    opt("mic", Icons.Rounded.Mic, "Microphone", "The sound in the room, for music playing on a speaker nearby.")
    opt("off", Icons.Rounded.VolumeOff, "Nothing on this phone", "Only your own files, and Vibe Bridge, move the bars.")
    if (cur == "phone" && Audio.phone.error != null) Hint("Android didn’t let the app listen: ${Audio.phone.error}. Some phones only allow it while music plays; try again then.")
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Vibe Bridge", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
            Text("Real bars from the computer that plays the music, through your server.", fontSize = 12.5.sp, color = Color.White.copy(alpha = .65f))
        }
        Switch(S.b("bridge"), { S.set("bridge", it) })
    }
    val b = Audio.bridge
    Hint(when {
        !S.b("bridge") -> "Off."
        !Server.set -> "Needs your server (Settings → Help & about → Server)."
        Audio.bridgeOn -> "Connected through your server" + (if (b.device.isNotEmpty()) " · listening to ${b.device}" else "") + "."
        else -> "Not connected. On the computer that plays the music, start Vibe Bridge with:  vibe-bridge --server ${Server.base?.removePrefix("https://")}  (see the README)."
    })
    Spacer(Modifier.height(8.dp))
    Text(Audio.labelTip, fontSize = 13.sp, color = Color.White.copy(alpha = .8f))
}

/* ---- the ping meter ---- */
@Composable
private fun NetSheet() {
    DisposableEffect(Unit) { Ping.open = true; Ping.kick(); onDispose { Ping.open = false } }
    E.sec.intValue
    SheetTitle("Ping meter", if (Server.set) "updates every 3 s" else "")
    val h = Ping.health
    val T = h?.optJSONObject("timing")
    @Composable
    fun row(name: String, detail: String, v: String, lvl: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(when (lvl) { "good" -> Color(0xFF1ED760); "ok" -> Color(0xFFFFC94D); "bad" -> Color(0xFFFF6B6B); else -> Color.White.copy(alpha = .3f) }, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                Text(detail, fontSize = 12.sp, color = Color.White.copy(alpha = .55f))
            }
            Text(v, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, fontFamily = FontFamily.Monospace)
        }
    }
    if (Server.set) {
        Net.stats["app"].let { n -> if (n == null) row("App server", "nginx on your server · checking…", "–", "") else { val ok = n.status != null && n.status < 500; row("App server", "nginx on your server · " + if (ok) "live" else "not answering", if (ok && n.ms != null) "${n.ms} ms" else "—", if (ok) Ping.lvl(n.ms) else "bad") } }
        Net.stats["helper"].let { n -> if (n == null) row("Server helper", "lyrics, Canvas, settings · checking…", "–", "") else { val ok = n.status != null && n.status < 500; row("Server helper", "lyrics, Canvas, settings · " + if (ok) "live" else "not answering", if (ok && n.ms != null) "${n.ms} ms" else "—", if (ok) Ping.lvl(n.ms) else "bad") } }
        if (h != null && !h.optBoolean("spicy_key")) row("Spicy Lyrics API", "no API key on the server yet", "off", "")
        else T?.optJSONObject("spicy").let { sp -> if (sp == null) row("Spicy Lyrics API", "via your server · not used yet", "–", "") else { val ok = sp.optInt("status") in 1..499 && sp.optInt("status") != 429; row("Spicy Lyrics API", "via your server · last request " + ago(System.currentTimeMillis() - sp.optLong("age_s") * 1000), "${sp.optLong("ms")} ms", if (ok) Ping.lvl(sp.optLong("ms")) else "bad") } }
        if (h != null && !h.optBoolean("logged_in")) row("Spotify Canvas", "guest session (no sp_dc cookie), Canvas off", "off", "")
        else T?.optJSONObject("canvas").let { cv -> if (cv == null) row("Spotify Canvas", "via your server · not used yet", "–", "") else { val ok = cv.optInt("status") in 1..499; row("Spotify Canvas", "via your server · last request " + ago(System.currentTimeMillis() - cv.optLong("age_s") * 1000), "${cv.optLong("ms")} ms", if (ok) Ping.lvl(cv.optLong("ms")) else "bad") } }
    } else row("Your server", "not set: Spicy Lyrics, Canvas and Spotify’s lyrics need it", "–", "")
    @Composable
    fun lastRow(k: String, name: String, desc: String, idle: String) {
        val n = Net.stats[k]
        if (n == null) { row(name, "$desc · $idle", "–", ""); return }
        val ok = n.status != null && n.status < 500 && n.status != 429
        row(name, "$desc · last request " + ago(n.at) + if (ok) "" else " (failed" + (n.status?.let { ", HTTP $it" } ?: "") + ")", n.ms?.let { "$it ms" } ?: "—", if (ok) Ping.lvl(n.ms) else "bad")
    }
    lastRow("spotify", "Spotify Web API", "now playing", if (Spotify.connected) "waiting for the first check" else "not connected")
    if (SpotifyApp.connected.value) row("Spotify app on this phone", "exact timing and controls, no requests", "live", "good")
    lastRow("lrclib", "LRCLIB", "lyrics", "not used yet")
    if (Server.set) { lastRow("spotifylyr", "Spotify lyrics", "via your server", "not used yet"); lastRow("netease", "NetEase Cloud Music", "via your server", "not used yet") }
    lastRow("google", "Google Translate", "translation & romanization", if (S.tr || S.romMode != "off") "not used yet" else "off")
    Spacer(Modifier.height(6.dp))
    Hint("Your server is pinged live. Outside services are timed on real requests only, so they show the last one.")
}

/* ---- where Spotify plays ---- */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DevicesSheet(close: () -> Unit) {
    var list by remember { mutableStateOf<List<SpDevice>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var switching by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { try { list = Spotify.devices() } catch (e: Exception) { err = e.message } }
    SheetTitle("Play on", list?.let { "${it.size} device" + if (it.size == 1) "" else "s" } ?: "")
    when {
        err != null -> Hint(err!!)
        list == null -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        list!!.isEmpty() -> Hint("No devices found. Open Spotify on a phone, computer or speaker.")
        else -> list!!.forEach { d ->
            val icon = when (d.type) { "Computer" -> Icons.Rounded.Computer; "Smartphone" -> Icons.Rounded.PhoneAndroid; "TV" -> Icons.Rounded.Tv; else -> Icons.Rounded.Speaker }
            val col = if (d.active) Color(0xFF1ED760) else Color.White
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = if (d.active) .08f else .04f))
                .clickable(enabled = !d.restricted) {
                    if (d.active) { close(); return@clickable }
                    switching = d.id
                    scope.launch { if (Spotify.transfer(d)) close() else switching = null }
                }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(22.dp), tint = col)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontWeight = FontWeight.Bold, color = col, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(when { switching == d.id -> "Switching…"; d.active -> "Playing here"; d.restricted -> "Can’t be controlled"; else -> d.type }, fontSize = 12.sp, color = col.copy(alpha = .6f))
                }
                if (d.volume != null && d.supportsVolume) Text("${d.volume}%", fontSize = 12.sp, color = Color.White.copy(alpha = .6f))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Hint("Only devices with Spotify open show up. Switching keeps the song going.")
}

/* ---- lyrics timing ---- */
@Composable
private fun TimingSheet() {
    SheetTitle("Lyrics timing")
    val off = S.loff
    Text("All songs: " + (if (off > 0) "+" else "") + off + " ms" + if (off > 0) " (earlier)" else if (off < 0) " (later)" else "", fontWeight = FontWeight.Bold, color = Color.White)
    Hint("Lyrics behind the music? Move them earlier.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { Lyr.nudge(-100) }) { Text("Later") }
        Slider(off / 3000f, { S.set("loff", (Math.round(it * 3000 / 50f) * 50)) }, Modifier.weight(1f).padding(horizontal = 10.dp), valueRange = -1f..1f)
        OutlinedButton(onClick = { Lyr.nudge(100) }) { Text("Earlier") }
    }
    Spacer(Modifier.height(14.dp))
    LaunchedEffect(Unit) { Outputs.refresh() }
    val oo = Outputs.offMs
    Text(Outputs.route.label + ": " + (if (oo > 0) "+" else "") + oo + " ms", fontWeight = FontWeight.Bold, color = Color.White)
    Hint(if (Outputs.here(E.src)) "Headphones, a car or a speaker can run behind: each output keeps its own timing, used whenever it plays (as Spicy Lyrics Mobile does)."
        else "The music plays on another device now, so this phone’s output timing isn’t used.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Outputs.set(oo - 50) }) { Text("−50 ms") }
        OutlinedButton(onClick = { Outputs.set(oo + 50) }) { Text("+50 ms") }
        if (oo != 0) FilledTonalButton(onClick = { Outputs.set(0) }) { Text("Reset") }
    }
    Spacer(Modifier.height(14.dp))
    val so = Lyr.songOffMs
    Text("This song: " + (if (so > 0) "+" else "") + so + " ms", fontWeight = FontWeight.Bold, color = Color.White)
    Hint("Only for the song that’s playing, on top of the setting above (as Spicy Lyrics Mobile’s song delay).")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Lyr.songOffSet(so - 100) }) { Text("−100 ms") }
        OutlinedButton(onClick = { Lyr.songOffSet(so + 100) }) { Text("+100 ms") }
        if (so != 0) FilledTonalButton(onClick = { Lyr.songOffSet(0) }) { Text("Reset") }
    }
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { Lyr.resync() }) { Icon(VIcons.resync, null, Modifier.size(18.dp)); HSpace(6.dp); Text("Resync now") }
        Spacer(Modifier.width(12.dp))
        Text(if (Spotify.appTiming()) "Timing comes from the Spotify app on this phone." else "", fontSize = 12.sp, color = Color.White.copy(alpha = .6f), modifier = Modifier.weight(1f))
    }
    if (Desync.stat.isNotEmpty()) { Spacer(Modifier.height(10.dp)); Hint("Desync detection: " + Desync.stat) }
}

@Suppress("unused") private val keepBorder = Modifier.border(0.dp, Color.Transparent)
