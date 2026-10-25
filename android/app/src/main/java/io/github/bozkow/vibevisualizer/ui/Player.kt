package io.github.bozkow.vibevisualizer.ui

import android.graphics.Matrix
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.clockLeft
import io.github.bozkow.vibevisualizer.core.fmtTime
import io.github.bozkow.vibevisualizer.core.wall
import io.github.bozkow.vibevisualizer.engine.CanvasVideo
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.FilePicker
import io.github.bozkow.vibevisualizer.engine.Images
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.engine.Updates
import io.github.bozkow.vibevisualizer.engine.Visuals
import io.github.bozkow.vibevisualizer.media.Local
import io.github.bozkow.vibevisualizer.spotify.Spotify

private val textShadow = Shadow(Color.Black.copy(alpha = .45f), androidx.compose.ui.geometry.Offset(0f, 2f), 14f)
private val dim = Color.White.copy(alpha = .68f)

/** The player, laid out as a music app: Now Playing (cover, title, controls), full-screen Lyrics and Visualizer with
 *  compact controls, or side by side on a wide screen (landscape, tablets, a foldable open). */
@Composable
fun PlayerScreen() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp || (maxWidth > maxHeight && maxHeight < 520.dp)
        if (E.wide != wide) E.wide = wide
        BackHandler(enabled = !wide && E.screen != "player" && E.settingsPage == null && !E.wizard) { E.show("player") }
        if (CanvasVideo.active) CanvasBackdrop(wide, centred = wide && soloWide())
        AmbientBlack()
        val pad = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.navigationBarsIgnoringVisibility).union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal))
        when {
            E.src == "none" -> Box(pad) { EmptyState() }
            wide -> Box(pad) { WideLayout() }
            // Lyrics and the Visualizer grow out of the button that opened them
            else -> AnimatedContent(E.screen, pad, transitionSpec = { (fadeIn(tween(320)) + scaleIn(spring(.86f, 400f), initialScale = if (Pop.calm) 1f else .9f, transformOrigin = Pop.origin())) togetherWith fadeOut(tween(220)) }, label = "screen") { s ->
                when (s) { "lyrics" -> LyricsScreen(); "viz" -> VizScreen(); else -> NowPlaying() }
            }
        }
    }
}

/* ================= Now Playing ================= */
@Composable
private fun NowPlaying(noLyricsNote: Boolean = false) {
    val hidden = E.uiHidden
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // while music plays untouched, the controls slide away (as in Spicy Lyrics Mobile): the cover, the song and the
        // seek bar stay; a tap brings the rest back
        AnimatedVisibility(!hidden, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) { Header() }
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp).ambientOut(), contentAlignment = Alignment.Center) {
            if (!(CanvasVideo.cvmode && S.b("cvNoArt"))) CoverArt(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true).canvasShrink(), corner = 22.dp)
        }
        if (S.view == "both") BarsView(Modifier.fillMaxWidth().height(48.dp).padding(bottom = 6.dp))
        TitleRow(big = true)
        if (noLyricsNote) NoLyricsNote(Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SeekRow()
        AnimatedVisibility(!hidden, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
            Column { Transport(big = true); if (S.b("volshow")) VolumeRow(); BottomBar() }
        }
        if (hidden) Spacer(Modifier.height(16.dp))
    }
}

/* ================= Lyrics ================= */
@Composable
private fun LyricsScreen() {
    // a song without lyrics: the Lyrics screen looks like Now Playing (big cover, the song, the seek bar, controls that
    // slide away), with a short note under the song; the arrow at the top goes back to Now Playing
    Crossfade(noLyrics(), label = "nolyrics") { big -> if (big) NowPlaying(noLyricsNote = true) else LyricsWithLyrics() }
}

@Composable
private fun LyricsWithLyrics() {
    val hidden = E.uiHidden
    Column(Modifier.fillMaxSize()) {
        // Ambient mode: the lyrics take the whole page and the song docks at the bottom
        AnimatedVisibility(!E.ambient, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(tween(900))) { MiniHeader() }
        if (E.ambient) Spacer(Modifier.height(8.dp))
        var areaH by remember { mutableFloatStateOf(0f) }
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { areaH = it.height.toFloat() }) {
            LyricsPane(Modifier.fillMaxSize(), areaHeightPx = areaH, padStart = 24f, padEnd = 24f)
        }
        if (S.view == "both") BarsView(Modifier.fillMaxWidth().height(if (hidden) 96.dp else 64.dp).padding(horizontal = 12.dp))
        LyricsCredits()
        AnimatedVisibility(E.ambient, enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(tween(900)), exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) { TitleRow(big = false); SeekRow() }
        }
        AnimatedVisibility(!hidden, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
            Column(Modifier.padding(horizontal = 24.dp)) { SeekRow(); Transport(big = false); BottomBar(big = false) }
        }
    }
}

/* ================= Visualizer ================= */
@Composable
private fun VizScreen() {
    val hidden = E.uiHidden
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Header(Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (!(CanvasVideo.cvmode && S.b("cvNoArt"))) Box(Modifier.fillMaxWidth(.5f).aspectRatio(1f).ambientOut()) { CoverArt(Modifier.fillMaxSize(), corner = 18.dp) }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.padding(horizontal = 24.dp)) { TitleRow(big = false, center = true) }
            }
            BarsView(Modifier.fillMaxWidth().fillMaxHeight(.44f).align(Alignment.BottomCenter).padding(horizontal = 10.dp))
        }
        AnimatedVisibility(!hidden, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
            Column(Modifier.padding(horizontal = 24.dp)) { SeekRow(); Transport(big = false); BottomBar(big = false) }
        }
    }
}

/* ================= side by side ================= */
@Composable
private fun WideLayout() {
    BoxWithConstraints(Modifier.fillMaxSize()) { if (maxHeight < 560.dp) LandscapePhone() else Tablet() }
}

/** A tablet or an open foldable: Now Playing on the left, the lyrics (or the bars) on the right. */
@Composable
private fun Tablet() {
    val solo = soloWide()   // no lyrics: Now Playing alone, the cover big
    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center) {
        Column(Modifier.fillMaxHeight().then(if (solo) Modifier.widthIn(max = 560.dp) else Modifier.weight(.42f).widthIn(max = 480.dp)), horizontalAlignment = Alignment.CenterHorizontally) {
            Header(Modifier.ambientOut())
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp).ambientOut(), contentAlignment = Alignment.Center) {
                if (!(CanvasVideo.cvmode && S.b("cvNoArt"))) CoverArt(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true).canvasShrink(), corner = 22.dp)
            }
            if (solo && S.view == "both") BarsView(Modifier.fillMaxWidth().height(56.dp).padding(bottom = 6.dp))
            TitleRow(big = true)
            if (solo) NoLyricsNote()
            SeekRow()
            Column(Modifier.ambientOut()) {
                Transport(big = true)
                if (S.b("volshow")) VolumeRow()
                BottomBar()
            }
        }
        if (!solo) {
            Spacer(Modifier.width(32.dp))
            RightPane(Modifier.fillMaxHeight().weight(.58f))
        }
    }
}

/** A phone on its side, laid out as Spicy Lyrics Mobile's: with lyrics (or the bars), the cover or the Canvas on the
 *  left with the song and the seek bar under it, the lyrics on the right; without lyrics, all in the middle. The
 *  controls come and go as in portrait. */
@Composable
private fun LandscapePhone() {
    Crossfade(soloWide(), label = "landsolo") { solo -> if (solo) LandscapeSolo() else LandscapeSplit() }
}

private val showCtl = expandVertically(expandFrom = Alignment.Top) + fadeIn()
private val hideCtl = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()

/** The cover (a Canvas takes its place: the video fills that side), the song and the seek bar on the left. */
@Composable
private fun LandscapeSplit() {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxHeight().weight(.42f).padding(top = 8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.BottomStart) {
                    ArtUnlessCanvas()
                }
                TitleRow(big = true)
                Spacer(Modifier.height(4.dp))
                SeekRow()
            }
            Spacer(Modifier.width(20.dp))
            RightPane(Modifier.fillMaxHeight().weight(.58f))
        }
        // the controls under each side: play / pause under the song, the round buttons under the lyrics
        AnimatedVisibility(!E.uiHidden, enter = showCtl, exit = hideCtl) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(.42f)) { Transport(big = false) }
                Spacer(Modifier.width(20.dp))
                Box(Modifier.weight(.58f), contentAlignment = Alignment.Center) { Box(Modifier.widthIn(max = 320.dp)) { BottomBar(big = false) } }
            }
        }
    }
}

/** The cover, shrinking away while a Canvas plays (the video is the picture then). */
@Composable
private fun ArtUnlessCanvas() {
    AnimatedVisibility(!CanvasVideo.cvmode, Modifier.ambientOut(), enter = fadeIn() + scaleIn(initialScale = .92f), exit = fadeOut() + scaleOut(targetScale = .92f)) {
        CoverArt(Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true), corner = 16.dp)
    }
}

/* ================= Ambient mode ================= */

/** Fades out (slowly) while Ambient mode dims the screen, back at once on a touch. */
@Composable
private fun Modifier.ambientOut(): Modifier {
    val k by animateFloatAsState(if (E.ambient) 0f else 1f, tween(if (E.ambient) 1500 else 300), label = "ambout")
    return graphicsLayer { alpha = k }
}

/** Ambient mode's black, over the background and the Canvas, under the lyrics and the song. */
@Composable
private fun AmbientBlack() {
    val k by animateFloatAsState(if (E.ambient) 1f else 0f, tween(if (E.ambient) 1500 else 300), label = "ambblack")
    if (k > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = k }.background(Color.Black))
}

/** No lyrics: the cover in the middle with the seek bar right under it, then the song big ("artist · year"); with a
 *  Canvas, the video in the middle and just the song and the seek bar at the bottom. */
@Composable
private fun LandscapeSolo() {
    val art = !CanvasVideo.cvmode
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
            ArtUnlessCanvas()
        }
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (S.view == "both") BarsView(Modifier.fillMaxWidth().height(40.dp).padding(bottom = 4.dp))
            if (art) { SeekRow(inline = true); TitleRow(big = true, center = true, year = true) }
            else { TitleRow(big = true, center = true); Spacer(Modifier.height(4.dp)); SeekRow() }
        }
        AnimatedVisibility(!E.uiHidden, enter = showCtl, exit = hideCtl) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                NoLyricsNote()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Transport(big = false) }
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.widthIn(max = 300.dp)) { BottomBar(big = false) }
                }
            }
        }
        if (E.uiHidden) Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun RightPane(modifier: Modifier) {
    Column(modifier) {
        var areaH by remember { mutableFloatStateOf(0f) }
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { areaH = it.height.toFloat() }) {
            if (E.screen == "viz") BarsView(Modifier.fillMaxSize().padding(vertical = 16.dp))
            else LyricsPane(Modifier.fillMaxSize(), areaHeightPx = areaH, padStart = 8f, padEnd = 16f)
        }
        if (E.screen != "viz" && S.view == "both") BarsView(Modifier.fillMaxWidth().height(64.dp))
        LyricsCredits()
    }
}

/* ================= nothing playing yet ================= */
@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Header()
        Spacer(Modifier.weight(1f))
        AppLogo(96.dp)
        Spacer(Modifier.height(20.dp))
        Text("Nothing playing", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("Play a song from your phone, or connect Spotify to follow what you’re listening to, with its cover, Canvas and lyrics.",
            Modifier.padding(top = 8.dp).widthIn(max = 420.dp), fontSize = 15.sp, color = dim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Button(onClick = { E.haptic(); FilePicker.pickAudio() }, Modifier.fillMaxWidth().widthIn(max = 360.dp).height(52.dp)) { Text("Play songs from your phone", fontSize = 15.sp) }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = { E.haptic(); if (Spotify.connected) E.setSource("spotify") else E.spLogin() }, Modifier.fillMaxWidth().widthIn(max = 360.dp).height(52.dp)) {
            Icon(VIcons.spotify, null, Modifier.size(20.dp)); HSpace(8.dp); Text(if (Spotify.connected) "Use Spotify" else "Connect Spotify", fontSize = 15.sp)
        }
        E.problems(true).firstOrNull()?.let { p -> Spacer(Modifier.height(24.dp)); ProblemState(p, Modifier.widthIn(max = 360.dp), compact = true) }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { E.wizard = true }) { Text("Setup guide") }
            TextButton(onClick = { E.openSettings() }) { Text("Settings") }
            TextButton(onClick = { E.openSettings("help") }) { Text("What’s new") }
        }
    }
}

/* ================= parts ================= */

/** Where the music comes from, and the ⋮ menu. */
@Composable
private fun Header(modifier: Modifier = Modifier) {
    E.sec.intValue
    val sp = E.src == "spotify"
    val status = if (Updates.downloading) "Downloading the update · " + (Updates.progress * 100).toInt() + "%" else E.statusLine()
    val top = when (E.src) { "spotify" -> "PLAYING FROM SPOTIFY"; "file" -> "PLAYING ON THIS PHONE"; else -> "VIBE VISUALIZER" }
    val sub = status ?: when (E.src) { "spotify" -> Spotify.device?.name?.let { "on $it" } ?: if (Spotify.meta == null) "Nothing playing" else ""; "file" -> Local.position(); else -> "" }
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { if (E.screen != "player" && !E.wide && E.src != "none") ScreenBack() }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(top, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = dim, maxLines = 1)
            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (status != null) Color(0xFFFFD27A) else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // the round Settings button is right below while something plays; before that, this is the way in
        if (E.src == "none") MoreButton() else Box(Modifier.size(48.dp))
    }
}

/** Lyrics: a small cover, the song, and the ⋮ menu. */
@Composable
private fun MiniHeader(showCover: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showCover) {   // with no lyrics the big cover is right below
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))) { CoverArt(Modifier.fillMaxSize(), corner = 10.dp, small = true) }
            Spacer(Modifier.width(12.dp))
        } else Spacer(Modifier.width(8.dp))
        // not a way back: only the Lyrics button (or Back) leaves the lyrics
        Column(Modifier.weight(1f)) {
            Text(E.title, style = TextStyle(shadow = textShadow), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val st = E.statusLine()
            if (st != null) Text(st, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFFD27A), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else if (E.artist.isNotEmpty()) Text(E.artist, fontSize = 14.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (Lyr.needTr) SmallToggle(VIcons.translate, "Translate lyrics", S.tr) { toggleTr() }
        if (Lyr.needRom) SmallToggle(null, "Romanize lyrics", S.romMode != "off", text = "あa") { toggleRom() }
        MoreButton()
    }
}

@Composable
private fun ScreenBack() {
    IconButton(onClick = { E.show("player") }, Modifier.semantics { contentDescription = "Back to Now Playing" }) {
        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(30.dp), tint = Color.White)
    }
}

@Composable
private fun SmallToggle(icon: ImageVector?, desc: String, on: Boolean, text: String? = null, onClick: () -> Unit) {
    IconButton(onClick = { E.haptic(); onClick() }, Modifier.semantics { contentDescription = desc },
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (on) Color.White else Color.Transparent, contentColor = if (on) Color(0xFF111116) else Color.White)) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp)) else Text(text ?: "", fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MoreButton() {
    IconButton(onClick = { E.haptic(); E.openSettings() }, Modifier.semantics { contentDescription = "Settings" }) { Icon(Icons.Rounded.MoreVert, null, tint = Color.White) }
}

@Composable
private fun TitleRow(big: Boolean, center: Boolean = false, year: Boolean = false) {
    val ver = E.trackVer
    val a = remember { Animatable(1f) }
    LaunchedEffect(ver) { if (!S.low && !E.reduceMotion()) { a.snapTo(0f); a.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) } }
    Row(Modifier.fillMaxWidth().graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 10f * density }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
            Scrolling(E.title, if (big) 27.sp else 19.sp, FontWeight.Bold, Color.White, (-.3).sp)
            val by = if (year && E.year.isNotEmpty()) listOf(E.artist, E.year).filter { it.isNotEmpty() }.joinToString(" · ") else E.artist
            if (by.isNotEmpty()) Scrolling(by, if (big) 19.sp else 15.sp, FontWeight.Medium, dim)
        }
        if (E.src == "spotify" && !center) LikeButton()
    }
}

/** A name that scrolls when it's too long for its row, fading out at both sides. */
@Composable
private fun Scrolling(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, color: Color, spacing: androidx.compose.ui.unit.TextUnit = 0.sp) {
    var boxW by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var textW by remember(text) { androidx.compose.runtime.mutableIntStateOf(0) }
    val over = boxW > 0 && textW > boxW
    val m = with(androidx.compose.ui.platform.LocalDensity.current) { 20.dp.roundToPx() }
    // too long: the scrolling reaches 20 dp into the margin on the left, so the name slides on past where it starts and
    // fades out there instead of being cut in a hard line; at rest it starts where it always does, its first letter
    // whole. The right edge fades too, showing there's more. A name that fits isn't moved (a centred one would sit
    // off centre)
    val scroll = if (over) Modifier.layout { mm, c -> val p = mm.measure(c.copy(minWidth = 0, maxWidth = c.maxWidth + m)); layout(c.maxWidth, p.height) { p.place(-m, 0) } }
        .fadeSides(20.dp, 28.dp).basicMarquee(iterations = 3, initialDelayMillis = 2500).padding(start = 20.dp)
        else Modifier.basicMarquee(iterations = 3, initialDelayMillis = 2500)
    Box(Modifier.onSizeChanged { boxW = it.width }) {
        Text(text, scroll, style = TextStyle(shadow = textShadow),
            fontSize = size, fontWeight = weight, letterSpacing = spacing, color = color, maxLines = 1, onTextLayout = { textW = it.size.width })
    }
}

@Composable
private fun LikeButton() {
    Spotify.version
    val liked = Spotify.liked == true
    val pop = remember { Animatable(1f) }
    LaunchedEffect(liked) { if (liked && !S.low && !E.reduceMotion()) { pop.snapTo(.6f); pop.animateTo(1f, spring(.35f, Spring.StiffnessMedium)) } }
    // the icon's edge lines up with the title's right edge (the touch area reaches past it)
    IconButton(onClick = { E.haptic(); Spotify.toggleLike() }, enabled = Spotify.meta?.ep == false,
        modifier = Modifier.offset(x = 12.dp).semantics { contentDescription = if (liked) "Remove from Liked Songs" else "Save to Liked Songs" }) {
        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, Modifier.size(28.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            tint = if (liked) Color(0xFF1ED760) else Color.White)
    }
}

/** Shuffle, previous, play / pause (its shape morphs), next, repeat. */
@Composable
private fun Transport(big: Boolean) {
    Spotify.version
    val sp = E.src == "spotify"
    val main = if (big) 56.dp else 42.dp
    Row(Modifier.fillMaxWidth().padding(vertical = if (big) 6.dp else 0.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (sp) CtlIcon(Icons.Rounded.Shuffle, "Shuffle", size = 24.dp, enabled = Spotify.spState != null && "toggling_shuffle" !in Spotify.disallows, on = Spotify.shuffle) { Spotify.toggleShuffle() }
        else CtlIcon(if (Local.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (Local.muted) "Unmute" else "Mute", enabled = E.src == "file") { Local.muted = !Local.muted }
        CtlIcon(Icons.Rounded.FastRewind, "Previous", size = main, enabled = E.src != "none") { E.prev() }
        PlayButton(if (big) 76.dp else 58.dp)
        CtlIcon(Icons.Rounded.FastForward, "Next", size = main, enabled = E.canNext()) { E.next() }
        if (sp) {
            val r = Spotify.repeat
            CtlIcon(if (r == "track") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat: " + when (r) { "context" -> "all"; "track" -> "this song"; else -> "off" },
                size = 24.dp, enabled = Spotify.spState != null, on = r != "off") { Spotify.cycleRepeat() }
        } else Box(Modifier.size(48.dp))
    }
}

/** Lyrics · where it plays · Visualizer: the music app's bottom row. */
@Composable
private fun BottomBar(big: Boolean = true) {
    val sp = E.src == "spotify"
    val d = if (big) 54.dp else 44.dp
    Row(Modifier.fillMaxWidth().padding(top = if (big) 6.dp else 2.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BarIcon(VIcons.lyrics, "Lyrics", E.screen == "lyrics" && !E.wide || E.wide && E.screen != "viz", d) { E.show(if (E.screen == "lyrics" && !E.wide) "player" else "lyrics") }
        if (sp) BarIcon(Icons.Rounded.Cast, Spotify.device?.let { "Playing on ${it.name}" } ?: "Choose where Spotify plays", false, d) { E.openSheet("devices") }
        else BarIcon(Icons.Rounded.GraphicEq, "What the bars hear", false, d) { E.openSheet("audio") }
        BarIcon(VIcons.bars, "Visualizer", E.screen == "viz", d) { E.show(if (E.screen == "viz") (if (E.wide) "lyrics" else "player") else "viz") }
        BarIcon(VIcons.resync, "Resync the lyrics", false, d) { Lyr.resync() }
        BarIcon(Icons.Rounded.Settings, "Settings", false, d) { E.openSettings() }
    }
}

/** A round outlined button (filled softly while it's the screen you're on). */
@Composable
private fun BarIcon(icon: ImageVector, desc: String, on: Boolean, d: Dp = 54.dp, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val k by animateFloatAsState(if (on) 1f else 0f, tween(260), label = "baron")   // the highlight fades between screens
    Box(Modifier.size(d).pressScale(src).clip(CircleShape).background(Color.White.copy(alpha = .16f * k))
        .border(1.dp, Color.White.copy(alpha = .32f + .23f * k), CircleShape).clickable(src, androidx.compose.material3.ripple()) { E.haptic(); onClick() }
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(d * .42f), tint = Color.White)
    }
}

@Composable
private fun CtlIcon(icon: ImageVector, desc: String, size: Dp = 24.dp, enabled: Boolean = true, on: Boolean = false, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val tint by androidx.compose.animation.animateColorAsState(if (on) MaterialThemeAccent() else Color.White, tween(220), label = "ctlon")   // shuffle / repeat light up softly
    IconButton(onClick = { E.haptic(); onClick() }, enabled = enabled, interactionSource = src, modifier = Modifier.size(maxOf(48.dp, size + 12.dp)).pressScale(src, .86f).semantics { contentDescription = desc },
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = tint, disabledContentColor = Color.White.copy(alpha = .3f))) {
        Icon(icon, null, Modifier.size(size))
    }
}

@Composable private fun MaterialThemeAccent() = androidx.compose.material3.MaterialTheme.colorScheme.primary

/** Play / pause: big and round; while music plays it morphs into a soft cookie shape (Material 3 Expressive). */
@Composable
private fun PlayButton(size: Dp) {
    val playing = E.playingState
    val pop = remember { Animatable(1f) }
    LaunchedEffect(playing) { if (!S.low && !E.reduceMotion()) { pop.snapTo(.86f); pop.animateTo(1f, spring(.45f, Spring.StiffnessMedium)) } }
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(Modifier.size(size).pressScale(src, .88f).clip(CircleShape).clickable(src, androidx.compose.material3.ripple()) { E.haptic(); E.toggle() }.semantics { contentDescription = if (playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
        // play and pause swap with a quick scale and fade
        AnimatedContent(playing, transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(220), initialScale = .7f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(160), targetScale = .7f)) }, label = "playicon") { p ->
            Icon(if (p) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(size).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = Color.White)
        }
    }
}

/** The seek bar: a wave while music plays (as Android's own media controls), the time it would jump to while dragging. */
@Composable
private fun SeekRow(inline: Boolean = false) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val glide by animateFloatAsState(E.seekFrac, tween(260, easing = androidx.compose.animation.core.LinearEasing), label = "seek")
    val frac = drag ?: if (kotlin.math.abs(E.seekFrac - glide) > .02f) E.seekFrac else glide   // a jump (seek, new song) isn't glided
    val dur = E.curDur()
    val bar = @Composable { m: Modifier -> ThickSeek(frac, onDrag = { f -> drag = f; E.seekDrag(true) }, onDone = { f -> if (drag == null) E.seekDrag(true); drag = null; E.seekFraction(f); E.seekDrag(false) }, enabled = E.src != "none" && dur > 0, modifier = m.quietTouch()) }
    val now = @Composable { Text(fmtTime(if (drag != null) frac * dur else E.curTime()), fontSize = 14.sp, color = if (drag != null && S.b("xScrub")) Color.White else dim, fontWeight = FontWeight.Medium) }
    val end = @Composable { Text(if (drag != null && S.b("xScrub")) "-" + fmtTime(dur - frac * dur) else fmtTime(dur), fontSize = 14.sp, color = dim, fontWeight = FontWeight.Medium) }
    // inline: the times on either side of the bar (under a cover in landscape)
    if (inline) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { now(); Spacer(Modifier.width(12.dp)); bar(Modifier.weight(1f)); Spacer(Modifier.width(12.dp)); end() }
    else Column(Modifier.fillMaxWidth()) {
        bar(Modifier)
        Row(Modifier.fillMaxWidth()) { now(); Spacer(Modifier.weight(1f)); end() }
    }
}

@Composable
private fun VolumeRow() {
    val sp = E.src == "spotify"
    val dev = Spotify.device
    val canVol = !sp || (dev != null && dev.supportsVolume && dev.volume != null)
    val v = if (sp) (dev?.volume ?: 0) else S.i("volume")
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = if (canVol) .7f else .3f))
        Slider(value = v / 100f, onValueChange = { f -> val x = (f * 100).toInt(); if (sp) Spotify.setVolume(x) else S.set("volume", x); E.poke() }, enabled = canVol,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp).semantics { contentDescription = if (sp) "Spotify volume" else "Volume" },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .22f), disabledInactiveTrackColor = Color.White.copy(alpha = .1f)))
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = if (canVol) .7f else .3f))
    }
}

@Composable
private fun LyricsCredits() {
    val c = Lyr.credit
    val show = c != null && Lyr.lines > 0 && (S.credPos == "corner" || !S.b("xRefCred"))
    AnimatedVisibility(show, enter = fadeIn(tween(600)), exit = fadeOut()) {
        if (c != null) CornerCredits(c, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), alignEnd = S.credPos == "lyrics" && S.b("lcredLast") && Lyr.L.lastOrNull { !it.dots }?.opp == true)
    }
}

/* ================= cover and Canvas ================= */

/** While a Canvas plays, the cover glides to a smaller size in the middle, so more of the Canvas shows (as the web app's
 *  cover shrinks to the middle); it grows back when there's none. Drawn smaller, not laid out again, so nothing jumps. */
@Composable
private fun Modifier.canvasShrink(): Modifier {
    val k by animateFloatAsState(if (CanvasVideo.cvmode && S.b("cvShrink")) .56f else 1f, if (S.low || E.reduceMotion()) tween(0) else spring(.85f, Spring.StiffnessVeryLow), label = "cvcover")
    return graphicsLayer { scaleX = k; scaleY = k }
}

/** The cover, in its frame (the Canvas plays behind the player, not in here). */
@Composable
fun CoverArt(modifier: Modifier, corner: Dp, small: Boolean = false) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.then(if (small) Modifier else Modifier.shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)).clip(shape).background(Color.White.copy(alpha = .08f))) {
        Cover(Modifier.fillMaxSize())
    }
}

/** The cover: a new one condenses out of a blur while the old one melts away (swells, sinks and blurs). */
@Composable
fun Cover(modifier: Modifier = Modifier) {
    val ver = Visuals.coverVer
    var cur by remember { mutableStateOf(Visuals.cover?.asImageBitmap()) }
    var prev by remember { mutableStateOf<ImageBitmap?>(null) }
    val k = remember { Animatable(1f) }
    val calm = S.low || E.reduceMotion()
    LaunchedEffect(ver) {
        val nb = Visuals.cover?.asImageBitmap()
        if (nb === cur) return@LaunchedEffect
        prev = if (calm) null else cur; cur = nb
        k.snapTo(0f); k.animateTo(1f, tween(if (calm) 1 else 1100, easing = FastOutSlowInEasing)); prev = null
    }
    val p = k.value
    Box(modifier, contentAlignment = Alignment.Center) {
        if (cur == null && prev == null) Icon(VIcons.bars, null, Modifier.fillMaxSize(.36f), tint = Color.White.copy(alpha = .35f))
        prev?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = 1 - p; scaleX = 1 + .14f * p; scaleY = 1 + .2f * p; translationY = size.height * .07f * p }.blur((22 * p).dp), contentScale = ContentScale.Crop) }
        cur?.let {
            val sc = if (p < .6f) .9f + (1.015f - .9f) * (p / .6f) else 1.015f - .015f * ((p - .6f) / .4f)
            Image(it, "Album cover", Modifier.fillMaxSize().graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (prev == null) 1f else (p * 1.6f).coerceAtMost(1f) }.blur((16 * (1 - (p / .6f).coerceAtMost(1f))).dp), contentScale = ContentScale.Crop)
        }
    }
}

/** The Canvas is the background: the video fills the screen behind the player and fades into the blurred Canvas toward
 *  the bottom, so the controls stay readable. Behind the lyrics it's dimmed right down, so the words stay sharp. On a
 *  wide screen it fills the left side and fades out to the right, as in the web app. The view is there from the start:
 *  the video's first frame (which reveals it) can only be drawn into one. */
@Composable
private fun CanvasBackdrop(wide: Boolean, centred: Boolean) {
    val behindLyrics = !wide && E.screen == "lyrics" && !noLyrics()
    val a by animateFloatAsState(if (!CanvasVideo.cvmode) 0f else if (behindLyrics) .25f else 1f, tween(800), label = "canvas")
    // side by side without lyrics, the video glides to the middle and fades out on both sides (moved, not laid out
    // again: the video's crop is set for its size)
    val c by animateFloatAsState(if (centred) 1f else 0f, if (S.low || E.reduceMotion()) tween(0) else tween(700, easing = FastOutSlowInEasing), label = "cvcentre")
    // behind the top bar, the song and the controls: the dark gradient, the Canvas blurred there, both or nothing
    val behind = S.str("cvBehind")
    val dark = behind == "dark" || behind == "both" || (behind == "blur" && S.low)   // Low performance: no blur, the gradient instead
    val frost = (behind == "blur" || behind == "both") && !S.low && !S.b("cvBlur")      // the whole Canvas blurred already: nothing to add
    // the blur reaches up to the song while the controls show, and sinks with them when they hide
    val sink by animateFloatAsState(if (E.uiHidden) 1f else 0f, tween(600, easing = FastOutSlowInEasing), label = "cvsink")
    val from = when { wide -> .52f; behindLyrics -> .6f; else -> .5f } + .12f * sink
    val d = androidx.compose.ui.platform.LocalDensity.current
    val r = with(d) { (S.i("cvBlurAmt").coerceIn(5, 100) / 100f * 48f).dp.toPx() }
    Box((if (wide) Modifier.fillMaxHeight().fillMaxWidth(.68f) else Modifier.fillMaxSize()).alpha(maxOf(a, .01f))
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; if (wide) translationX = size.width * (.16f / .68f) * c }.drawWithContent {
            drawContent()
            val shade = Brush.verticalGradient(0f to Color.Black.copy(alpha = .4f), .2f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = .45f))
            if (wide) {
                // side by side: the shade fades out with the video (drawn after the fade it stopped in a hard line at the
                // edge), over a long, eased fade into the blurred Canvas behind
                if (dark) drawRect(shade)
                val s = .3f + .4f * c   // where the fade starts: long beside the lyrics, short in the middle
                drawRect(Brush.horizontalGradient(0f to Color.Black, *Array(6) { i -> (s + (1 - s) * EASE_AT[i]) to Color.Black.copy(alpha = EASE_A[i]) }), blendMode = BlendMode.DstIn)
                // in the middle, the same fade on the left
                if (c > 0f) drawRect(Brush.horizontalGradient(*Array(6) { k -> val j = 5 - k; .3f * (1 - EASE_AT[j]) to Color.Black.copy(alpha = 1 - c * (1 - EASE_A[j])) }, 1f to Color.Black), blendMode = BlendMode.DstIn)
            } else {
                val fade = arrayOf(0f to Color.Black, .45f to Color.Black, .58f to Color.Black.copy(alpha = .85f), .74f to Color.Black.copy(alpha = .5f), .88f to Color.Black.copy(alpha = .18f), 1f to Color.Transparent)
                drawRect(Brush.verticalGradient(*fade), blendMode = BlendMode.DstIn)
                if (dark) drawRect(shade)
            }
        }) {
        Box(Modifier.fillMaxSize().graphicsLayer { if (frost) renderEffect = frostBands(size.height, r, if (wide) .05f else .09f, from) }) { CanvasVideoView() }
    }
}

/** The Canvas frosted where the controls are: a blurred copy of it over the sharp one, shown through a mask that's
 *  opaque at the top (down to [top], under the top bar), clear in the middle, and opaque again from a little below
 *  [from] (under the song, the seek bar and the controls), each edge a soft ramp. One blur pass on the GPU. */
private fun frostBands(h: Float, r: Float, top: Float, from: Float): androidx.compose.ui.graphics.RenderEffect {
    val blur = android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)
    val on = android.graphics.Color.BLACK; val off = android.graphics.Color.TRANSPARENT
    val mask = android.graphics.RenderEffect.createShaderEffect(android.graphics.LinearGradient(0f, 0f, 0f, maxOf(h, 1f),
        intArrayOf(on, on, off, off, on, on), floatArrayOf(0f, top, top + .09f, from, minOf(from + .14f, .99f), 1f), android.graphics.Shader.TileMode.CLAMP))
    val frosted = android.graphics.RenderEffect.createBlendModeEffect(blur, mask, android.graphics.BlendMode.DST_IN)
    return android.graphics.RenderEffect.createBlendModeEffect(android.graphics.RenderEffect.createOffsetEffect(0f, 0f), frosted, android.graphics.BlendMode.SRC_OVER).asComposeRenderEffect()
}

private val EASE_AT = floatArrayOf(0f, .214f, .429f, .629f, .814f, 1f)   // the eased fade's steps (where, how opaque)
private val EASE_A = floatArrayOf(1f, .9f, .66f, .38f, .14f, 0f)

/** The Canvas video filling its box: sharp, or (Blur the Canvas) drawn at a quarter of the size, blurred there and
 *  stretched back, which is cheap, unlike a full-screen blur every frame. */
@Composable
private fun CanvasVideoView() {
    if (S.b("cvBlur")) BoxWithConstraints(Modifier.fillMaxSize()) {
        val fw = constraints.maxWidth; val fh = constraints.maxHeight
        val sw = (fw / 4).coerceAtLeast(1); val sh = (fh / 4).coerceAtLeast(1)
        val r = S.i("cvBlurAmt").coerceIn(5, 100) / 100f * 14f   // in quarter-size pixels: up to ~56 on the screen
        Box(Modifier.layout { m, _ -> val p = m.measure(Constraints.fixed(sw, sh)); layout(fw, fh) { p.place(0, 0) } }
            .graphicsLayer { transformOrigin = TransformOrigin(0f, 0f); scaleX = fw / sw.toFloat(); scaleY = fh / sh.toFloat(); renderEffect = BlurEffect(r, r, TileMode.Clamp); compositingStrategy = CompositingStrategy.Offscreen }) {
            CanvasView()
        }
    } else CanvasView()
}

/** Picture-in-picture: the Canvas behind the lyrics there too, darkened so they stay readable. */
@Composable
fun PipCanvas() {
    if (!CanvasVideo.active) return
    val a by animateFloatAsState(if (CanvasVideo.cvmode) 1f else 0f, tween(800), label = "pipcanvas")
    Box(Modifier.fillMaxSize().alpha(maxOf(a, .01f)).drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = .45f)) }) { CanvasVideoView() }
}

@Composable
private fun CanvasView() {
    AndroidView({ ctx -> TextureView(ctx).also { CanvasVideo.bindView(it) } }, Modifier.fillMaxSize(), update = { tv -> tv.post { coverFit(tv) } }, onRelease = { CanvasVideo.unbind(it) })
}

/** The lookup is done and found no lyrics (and nothing's wrong with the connection): the cover takes their place. */
private fun noLyrics() = Lyr.empty && Lyr.lines == 0 && E.problems(false).isEmpty()

/** Side by side with no lyrics (and not the Visualizer): the song alone, in the middle. */
private fun soloWide() = noLyrics() && E.screen != "viz"

/** Under the big cover: why there are no lyrics, and the one thing to do about it. */
@Composable
private fun NoLyricsNote(modifier: Modifier = Modifier) {
    val m = Lyr.msg ?: return
    val act = m.actions.firstOrNull { it.pri } ?: m.actions.firstOrNull()
    Row(modifier.padding(top = 2.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(VIcons.lyrics, null, Modifier.size(16.dp), tint = dim)
        Spacer(Modifier.width(8.dp))
        Text(m.msg, Modifier.weight(1f, fill = false), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        act?.let { a -> TextButton(onClick = { E.haptic(); a.fn?.invoke() }) { Text(a.label, fontSize = 13.sp) } }
    }
}

/** object-fit: cover for the TextureView (Canvas videos are 9:16). */
private fun coverFit(tv: TextureView) {
    val vw = 720f; val vh = 1280f
    val w = tv.width.toFloat(); val h = tv.height.toFloat(); if (w <= 0 || h <= 0) return
    val s = maxOf(w / vw, h / vh); val m = Matrix(); m.setScale(vw * s / w, vh * s / h, w / 2, h / 2); tv.setTransform(m)
}

private var lastRom = "replace"
fun toggleTr() {
    val n = io.github.bozkow.vibevisualizer.core.LANGS.find { it.first == S.trTo }?.second ?: S.trTo
    @Suppress("UNUSED_VARIABLE") val unused = n
    S.set("tr", !S.tr)
}
fun toggleRom() {
    if (S.romMode != "off") lastRom = S.romMode
    val on = S.romMode == "off"; S.set("romMode", if (on) lastRom else "off")
}

@Suppress("unused") private val keep = listOf(Server, ColumnScope::class)
