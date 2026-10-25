package io.github.bozkow.vibevisualizer.ui

import android.os.BatteryManager
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Lyr
import kotlinx.coroutines.delay

/** Ambient mode, as a phone's always-on display: what's shown is up to you (Settings → Player screen → Ambient mode):
 *  the clock and the date, the battery, the cover, the song, the lyrics and the bars, on black or over the player
 *  dimmed. All of it drifts a few dp every minute, so nothing stays lit on the same pixels (OLED burn-in). */
@Composable
fun AmbientScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    // the slow drift: a new spot every minute, glided to over 4 s
    var to by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(S.b("aAmbShift")) {
        if (!S.b("aAmbShift")) { to = Offset.Zero; return@LaunchedEffect }
        while (true) { delay(60_000); with(d) { to = Offset((-12..12).random().dp.toPx(), (-16..16).random().dp.toPx()) } }
    }
    val drift by animateOffsetAsState(to, tween(4000), label = "ambdrift")
    val lyricsOn = S.b("aAmbLyrics") && Lyr.lines > 0
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { translationX = drift.x; translationY = drift.y }) {
        val side = maxWidth > maxHeight   // on its side: the clock and the song on the left, the lyrics on the right
        @Composable fun Clock(center: Boolean) {
            E.sec.intValue   // once a second
            val now = java.util.Date()
            val pat = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm"
            Column(horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
                if (S.b("aAmbClock")) {
                    Text(java.text.SimpleDateFormat(pat, java.util.Locale.getDefault()).format(now), fontSize = 72.sp, fontWeight = FontWeight.Light, color = Color.White, letterSpacing = (-1).sp)
                    val day = android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEMMMMd"), now).toString()
                    Text(day, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = .7f))
                }
                if (S.b("aAmbBattery")) {
                    val bm = remember { ctx.getSystemService(BatteryManager::class.java) }
                    val lvl = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    if (lvl in 0..100) Text((if (bm?.isCharging == true) "Charging · " else "") + "$lvl%", Modifier.padding(top = 4.dp), fontSize = 14.sp, color = Color.White.copy(alpha = .55f))
                }
            }
        }
        @Composable fun Song(center: Boolean) {
            val cover = S.b("aAmbCover"); val song = S.b("aAmbSong")
            if (!cover && !song) return
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start, modifier = Modifier.widthIn(max = 420.dp)) {
                if (cover) CoverArt(Modifier.size(if (song) 56.dp else 120.dp), corner = 12.dp, small = true)
                if (cover && song) Spacer(Modifier.width(14.dp))
                if (song) Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (center && !cover) Alignment.CenterHorizontally else Alignment.Start) {
                    Text(E.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (E.artist.isNotEmpty()) Text(E.artist, fontSize = 15.sp, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        @Composable fun Words(m: Modifier) {
            var h by remember { mutableFloatStateOf(0f) }
            Box(m.onSizeChanged { h = it.height.toFloat() }) { LyricsPane(Modifier.fillMaxSize(), areaHeightPx = h, padStart = 24f, padEnd = 24f) }
        }
        if (side) Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(if (lyricsOn) .42f else 1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = if (lyricsOn) Alignment.Start else Alignment.CenterHorizontally) {
                Clock(!lyricsOn); Song(!lyricsOn)
                if (S.b("aAmbBars")) BarsView(Modifier.fillMaxWidth().height(56.dp))
            }
            if (lyricsOn) Words(Modifier.weight(.58f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Clock(true)
            if (lyricsOn) Words(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp)) else Spacer(Modifier.weight(1f))
            Box(Modifier.padding(horizontal = 28.dp)) { Song(true) }
            if (S.b("aAmbBars")) BarsView(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp, vertical = 6.dp))
            if (!lyricsOn && !S.b("aAmbClock") && !S.b("aAmbCover") && !S.b("aAmbSong")) Text("", textAlign = TextAlign.Center)
        }
    }
}
