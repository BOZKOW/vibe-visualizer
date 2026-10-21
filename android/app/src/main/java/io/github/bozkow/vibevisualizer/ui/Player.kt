package io.github.bozkow.vibevisualizer.ui

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.fmtTime
import io.github.bozkow.vibevisualizer.core.now
import io.github.bozkow.vibevisualizer.core.wall
import io.github.bozkow.vibevisualizer.engine.CanvasVideo
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.FilePicker
import io.github.bozkow.vibevisualizer.engine.Images
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.engine.NextUp
import io.github.bozkow.vibevisualizer.engine.Ping
import io.github.bozkow.vibevisualizer.engine.Visuals
import io.github.bozkow.vibevisualizer.media.Local
import io.github.bozkow.vibevisualizer.spotify.Spotify

private val textShadow = Shadow(Color.Black.copy(alpha = .5f), androidx.compose.ui.geometry.Offset(0f, 2f), 12f)

/** The player: cover and title, lyrics, bars and the controls, laid out for a phone held upright, or side by side on a
 *  wide screen (landscape, a tablet, a foldable open). */
@Composable
fun PlayerScreen() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp || (maxWidth > maxHeight && maxHeight < 520.dp)
        val cv = CanvasVideo.cvmode
        CanvasPane(wide)
        if (wide) WideLayout(cv) else TallLayout(cv)
    }
}

/* ================= phone, upright ================= */
@Composable
private fun TallLayout(cv: Boolean) {
    val hidden = E.uiHidden
    val view = S.view
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
        Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars))
        AnimatedVisibility(!hidden, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) { TopBar(false) }
        if (view == "viz") {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!(cv && S.b("cvNoArt"))) Cover(Modifier.fillMaxWidth(.62f).aspectRatio(1f))
                Spacer(Modifier.height(16.dp))
                TitleBlock(center = true, big = true, modifier = Modifier.padding(horizontal = 24.dp))
                NextUpCard(Modifier.padding(top = 12.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = if (cv) 140.dp else 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!(cv && S.b("cvNoArt"))) { Cover(Modifier.size(64.dp)); Spacer(Modifier.width(14.dp)) }
                TitleBlock(center = false, big = false, modifier = Modifier.weight(1f))
            }
            NextUpCard(Modifier.padding(start = 20.dp, top = 4.dp))
        }
        var areaH by remember { mutableFloatStateOf(0f) }
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { areaH = it.height.toFloat() }) {
            val barsH by animateFloatAsState(when (view) { "viz" -> .62f; "both" -> if (Lyr.lines > 0 || Lyr.msg != null) .22f else .5f; else -> 0f }, tween(550), label = "barsH")
            if (view != "viz") {
                val bottom = with(LocalDensity.current) { (areaH * barsH).toDp() }
                LyricsPane(Modifier.fillMaxSize().padding(bottom = if (S.xFocus) 0.dp else bottom), areaHeightPx = areaH)
            }
            if (barsH > .01f) BarsView(Modifier.fillMaxWidth().fillMaxHeight(barsH).align(Alignment.BottomCenter).padding(horizontal = 8.dp))
        }
        CornerCreditsRow()
        AnimatedVisibility(!hidden, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()) {
            Dock(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp))
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/* ================= side by side (landscape, tablets, foldables) ================= */
@Composable
private fun WideLayout(cv: Boolean) {
    val hidden = E.uiHidden
    val view = S.view
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        AnimatedVisibility(!hidden, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { TopBar(true) }
        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            Column(Modifier.fillMaxHeight().weight(if (view == "viz") 1f else .4f), verticalArrangement = Arrangement.Center, horizontalAlignment = if (cv || view == "viz") Alignment.CenterHorizontally else Alignment.Start) {
                if (!(cv && S.b("cvNoArt"))) BoxWithConstraints(Modifier.weight(1f, fill = false)) {
                    val s = minOf(maxWidth, maxHeight * .72f, 360.dp)
                    Cover(Modifier.size(if (cv) minOf(s, 220.dp) else s))
                }
                Spacer(Modifier.height(14.dp))
                TitleBlock(center = cv || view == "viz", big = true)
                NextUpCard(Modifier.padding(top = 12.dp))
            }
            if (view != "viz") {
                Spacer(Modifier.width(24.dp))
                var areaH by remember { mutableFloatStateOf(0f) }
                Box(Modifier.weight(.6f).fillMaxHeight().onSizeChanged { areaH = it.height.toFloat() }) {
                    val barsH by animateFloatAsState(if (view == "both") .26f else 0f, tween(550), label = "barsW")
                    val bottom = with(LocalDensity.current) { (areaH * barsH).toDp() }
                    LyricsPane(Modifier.fillMaxSize().padding(bottom = if (S.xFocus) 0.dp else bottom), areaHeightPx = areaH)
                    if (barsH > .01f) BarsView(Modifier.fillMaxWidth().fillMaxHeight(barsH).align(Alignment.BottomCenter))
                }
            }
        }
        if (view == "viz") BarsView(Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { CornerCreditsRow(Modifier) }
            AnimatedVisibility(!hidden, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                Dock(Modifier.widthIn(max = 720.dp).padding(bottom = 10.dp), wide = true)
            }
            Box(Modifier.weight(1f))
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}

@Composable
private fun CornerCreditsRow(modifier: Modifier = Modifier) {
    val c = Lyr.credit
    val show = c != null && Lyr.lines > 0 && S.view != "viz" && (S.credPos == "corner" || !S.b("xRefCred") || S.xFocus)
    AnimatedVisibility(show, modifier, enter = fadeIn(tween(600)), exit = fadeOut()) {
        if (c != null) CornerCredits(c, Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), alignEnd = S.credPos == "lyrics" && S.b("lcredLast") && Lyr.L.lastOrNull { !it.dots }?.opp == true)
    }
}

/* ================= top bar ================= */
@Composable
fun TopBar(wide: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(pillShape).clickable { E.haptic(); E.goHome() }.padding(6.dp).semantics { contentDescription = "Vibe Visualizer: open the welcome page" }, verticalAlignment = Alignment.CenterVertically) {
                AppLogo(30.dp)
                if (wide) { Spacer(Modifier.width(10.dp)); Text("Vibe Visualizer", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White) }
            }
            Spacer(Modifier.width(8.dp))
            if (!wide) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ViewSwitch() } else { Spacer(Modifier.width(8.dp)); ViewSwitch() }
            if (wide) Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) { Chips() }
            IconButton(onClick = { E.haptic(); E.openSettings() }, Modifier.semantics { contentDescription = "Settings" }) { Icon(VIcons.sliders, null, tint = Color.White) }
        }
        if (!wide) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chips() }
    }
}

/** The three views, with a pill that glides to the chosen one. */
@Composable
fun ViewSwitch() {
    val items = listOf(Triple("viz", "Visualizer", VIcons.bars), Triple("lyrics", "Lyrics", VIcons.lyrics), Triple("both", "Both", VIcons.both))
    val sel = items.indexOfFirst { it.first == S.view }.coerceAtLeast(0)
    val itemW = 86.dp
    val x by animateDpAsState(itemW * sel, spring(.62f, Spring.StiffnessMediumLow), label = "pill")
    Box(Modifier.glass(pillShape).padding(3.dp)) {
        Box(Modifier.offset(x = x).width(itemW).height(34.dp).shadow(6.dp, pillShape).background(Color.White, pillShape))
        Row {
            items.forEachIndexed { i, (k, label, icon) ->
                val on = i == sel
                Row(Modifier.width(itemW).height(34.dp).clip(pillShape).clickable { E.haptic(); S.set("view", k) }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(15.dp), tint = if (on) Color(0xFF111116) else Color.White.copy(alpha = .85f))
                    Spacer(Modifier.width(5.dp))
                    Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color(0xFF111116) else Color.White.copy(alpha = .85f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Chips() {
    E.sec.intValue
    val sp = Spotify
    val spLabel = when {
        !sp.connected -> "Connect Spotify"
        E.src != "spotify" -> "Spotify"
        wall() < sp.backoff -> "Spotify · waiting " + io.github.bozkow.vibevisualizer.core.clockLeft((sp.backoff - wall()) / 1000.0)
        sp.isPlaying -> "Spotify · playing"
        sp.meta != null -> "Spotify · paused"
        else -> "Spotify · idle"
    }
    VChip(spLabel, { if (!sp.connected) E.spLogin() else if (E.src != "spotify") E.setSource("spotify") else E.openSettings("spotify") },
        dot = if (sp.errMsg.isNotEmpty() && E.src == "spotify") Color(0xFFFF6B6B) else if (E.src == "spotify" && sp.isPlaying) Color(0xFF1ED760) else Color.White.copy(alpha = .4f), glowDot = E.src == "spotify" && sp.isPlaying)
    VChip(Audio.label, { E.sheet = "audio" }, icon = Icons.Rounded.GraphicEq, dot = if (Audio.bridgeOn) Color(0xFF1ED760) else null, desc = Audio.labelTip)
    if (io.github.bozkow.vibevisualizer.core.Server.set) VChip(Ping.label, { E.sheet = "net"; Ping.open = true; Ping.kick() },
        dot = when (Ping.level) { "good" -> Color(0xFF1ED760); "ok" -> Color(0xFFFFC94D); "bad" -> Color(0xFFFF6B6B); else -> Color.White.copy(alpha = .4f) })
    if (Lyr.needTr) VChip("", { toggleTr() }, icon = VIcons.translate, on = S.tr, desc = "Translate lyrics")
    if (Lyr.needRom) VChip("あa", { toggleRom() }, on = S.romMode != "off", desc = "Romanize lyrics")
    if (Lyr.needSync) VChip("", { E.haptic(); Lyr.resync() }, icon = VIcons.resync, desc = "Resync lyrics")
    if (Lyr.needSync) VChip("", { E.sheet = "timing" }, icon = Icons.Rounded.Timer, desc = "Lyrics timing")
    VChip("", { S.set("low", !S.low); E.toast(if (S.low) "Low performance mode on" else "Low performance mode off", "ok", 1800) }, icon = VIcons.bolt, on = S.low, desc = "Low performance mode")
    VChip("", { FilePicker.pickAudio() }, icon = Icons.Rounded.FolderOpen, desc = "Open audio files")
    VChip("", { E.fullscreen = !E.fullscreen }, icon = if (E.fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, on = E.fullscreen, desc = "Fullscreen")
}

private var lastRom = "replace"
fun toggleTr() {
    val n = io.github.bozkow.vibevisualizer.core.LANGS.find { it.first == S.trTo }?.second ?: S.trTo
    val on = !S.tr; S.set("tr", on); E.toast(if (on) "Translation on · into $n" else "Translation off", "ok", 1800)
}
fun toggleRom() {
    if (S.romMode != "off") lastRom = S.romMode
    val on = S.romMode == "off"; S.set("romMode", if (on) lastRom else "off"); E.toast(if (on) "Romanization on" else "Romanization off", "ok", 1800)
}

/* ================= cover and title ================= */

/** The cover: a new one condenses out of a blur while the old one melts away (swells, sinks and blurs). */
@Composable
fun Cover(modifier: Modifier = Modifier) {
    val ver = Visuals.coverVer
    var cur by remember { mutableStateOf<ImageBitmap?>(null) }
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
    val corner = RoundedCornerShape(18.dp)
    Box(modifier.shadow(18.dp, corner, ambientColor = Color.Black, spotColor = Color.Black).clip(corner).background(Color.White.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
        if (cur == null && prev == null) Text("♪", fontSize = 28.sp, color = Color.White.copy(alpha = .5f))
        prev?.let { Image(it, null, Modifier.fillMaxSize().graphicsLayer { alpha = 1 - p; scaleX = 1 + .14f * p; scaleY = 1 + .2f * p; translationY = size.height * .07f * p }.blur((22 * p).dp), contentScale = ContentScale.Crop) }
        cur?.let {
            val sc = if (p < .6f) .9f + (1.015f - .9f) * (p / .6f) else 1.015f - .015f * ((p - .6f) / .4f)
            Image(it, "Album cover", Modifier.fillMaxSize().graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (prev == null) 1f else (p * 1.6f).coerceAtMost(1f) }.blur((16 * (1 - (p / .6f).coerceAtMost(1f))).dp), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun TitleBlock(center: Boolean, big: Boolean, modifier: Modifier = Modifier) {
    val ver = E.trackVer
    val a = remember { Animatable(1f) }
    LaunchedEffect(ver) { if (!S.low && !E.reduceMotion()) { a.snapTo(0f); a.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) } }
    Column(modifier.graphicsLayer { alpha = a.value; translationY = (1 - a.value) * 14f * density }, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(E.title, style = TextStyle(shadow = textShadow), fontSize = if (big) 26.sp else 19.sp, lineHeight = if (big) 29.sp else 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-.5).sp,
            color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = if (center) TextAlign.Center else TextAlign.Start)
        if (E.artist.isNotEmpty()) Text(E.artist, style = TextStyle(shadow = textShadow), fontSize = if (big) 16.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .88f),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        if (E.status.isNotEmpty() && E.status != "Spotify") Text(E.status, fontSize = 12.sp, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Next up: rises in like a drop in the last 30 seconds; melts away. */
@Composable
fun NextUpCard(modifier: Modifier = Modifier) {
    val it = NextUp.item
    AnimatedVisibility(NextUp.shown && it != null, modifier, enter = fadeIn() + scaleIn(initialScale = .85f) + slideInVertically { it / 2 }, exit = fadeOut() + scaleOut(targetScale = .9f)) {
        if (it != null) Row(Modifier.widthIn(max = 360.dp).glass(RoundedCornerShape(18.dp)).padding(start = 7.dp, end = 14.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            val bm by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, it.img) { value = Images.load(it.img, 160) }
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .1f))) { bm?.let { b -> Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("NEXT UP", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White.copy(alpha = .45f))
                Text(it.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(it.artist, fontSize = 12.sp, color = Color.White.copy(alpha = .7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/* ================= the Canvas video ================= */
@Composable
private fun CanvasPane(wide: Boolean) {
    val on = CanvasVideo.cvmode
    val a by animateFloatAsState(if (on) 1f else 0f, tween(800), label = "cvpane")
    if (a <= 0f && !CanvasVideo.hasVideo) { DisposableEffect(Unit) { onDispose { } }; return }
    Box(Modifier.fillMaxSize()) {
        Box((if (wide) Modifier.fillMaxHeight().fillMaxWidth(.56f) else Modifier.fillMaxWidth().fillMaxHeight(.5f))
            .alpha(a).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // a long, eased fade: the video melts into the background
                val stops = arrayOf(0f to Color.Black, .4f to Color.Black, .5f to Color.Black.copy(alpha = .93f), .61f to Color.Black.copy(alpha = .76f), .72f to Color.Black.copy(alpha = .5f), .83f to Color.Black.copy(alpha = .25f), .93f to Color.Black.copy(alpha = .08f), 1f to Color.Transparent)
                drawRect(if (wide) Brush.horizontalGradient(*stops) else Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
                drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .4f), .22f to Color.Transparent))
            }) {
            AndroidView({ ctx -> TextureView(ctx).also { CanvasVideo.bindView(it) } }, Modifier.fillMaxSize(),
                update = { tv -> tv.post { coverFit(tv) } }, onRelease = { CanvasVideo.bindView(null) })
        }
    }
}

/** object-fit: cover for the TextureView. */
private fun coverFit(tv: TextureView) {
    val vw = 720f; val vh = 1280f   // Canvas videos are 9:16
    val w = tv.width.toFloat(); val h = tv.height.toFloat(); if (w <= 0 || h <= 0) return
    val s = maxOf(w / vw, h / vh); val m = Matrix(); m.setScale(vw * s / w, vh * s / h, w / 2, h / 2); tv.setTransform(m)
}

/* ================= controls ================= */
@Composable
fun Dock(modifier: Modifier = Modifier, wide: Boolean = false) {
    Spotify.version
    val sp = E.src == "spotify"
    Column(modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), strong = true).padding(horizontal = 14.dp, vertical = 8.dp)) {
        SeekRow()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (sp) {
                val liked = Spotify.liked == true
                val likeOk = Spotify.meta?.ep == false
                CtlIcon(if (liked) VIcons.heartOn else VIcons.heart, if (liked) "Remove from Liked Songs" else "Save to Liked Songs", enabled = likeOk, on = liked, tint = if (liked) Color(0xFF1ED760) else null) { Spotify.toggleLike() }
                CtlIcon(Icons.Rounded.Shuffle, "Shuffle", enabled = Spotify.spState != null && "toggling_shuffle" !in Spotify.disallows, on = Spotify.shuffle) { Spotify.toggleShuffle() }
            } else {
                CtlIcon(if (Local.muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Mute", enabled = E.src == "file") { Local.muted = !Local.muted }
            }
            CtlIcon(Icons.Rounded.SkipPrevious, "Previous", size = 30.dp, enabled = E.src != "none") { E.prev() }
            PlayButton()
            CtlIcon(Icons.Rounded.SkipNext, "Next", size = 30.dp, enabled = E.canNext()) { E.next() }
            if (sp) {
                val r = Spotify.repeat
                CtlIcon(if (r == "track") Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat: " + when (r) { "context" -> "all"; "track" -> "this song"; else -> "off" },
                    enabled = Spotify.spState != null, on = r != "off") { Spotify.cycleRepeat() }
                CtlIcon(VIcons.devices, Spotify.device?.let { "Playing on ${it.name} · choose where Spotify plays" } ?: "Choose where Spotify plays") { E.sheet = "devices" }
            } else CtlIcon(Icons.Rounded.FolderOpen, "Open audio files") { FilePicker.pickAudio() }
        }
        if (S.b("volshow") && E.src != "none") VolumeRow()
    }
}

@Composable
private fun CtlIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: Dp = 22.dp, enabled: Boolean = true, on: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    IconButton(onClick = { E.haptic(); onClick() }, enabled = enabled, modifier = Modifier.semantics { contentDescription = desc },
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (on && tint == null) Color.White.copy(alpha = .16f) else Color.Transparent, contentColor = tint ?: Color.White, disabledContentColor = Color.White.copy(alpha = .3f))) {
        Icon(icon, null, Modifier.size(size))
    }
}

/** Play / pause: a big button whose shape morphs (a cookie while music plays, a circle when paused). */
@Composable
private fun PlayButton() {
    val playing = E.playingState
    val m by animateFloatAsState(if (playing) 1f else 0f, spring(.55f, Spring.StiffnessLow), label = "morph")
    val shape = remember(m) { morphShape(m) }
    val acc = MaterialTheme.colorScheme.primary
    Box(Modifier.size(62.dp).clip(shape).background(Color.White).clickable { E.toggle() }.semantics { contentDescription = if (playing) "Pause" else "Play" }, contentAlignment = Alignment.Center) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(32.dp), tint = Color(0xFF111116))
    }
    @Suppress("UNUSED_EXPRESSION") acc
}

/** The seek bar: a wave while music plays (as Android's own media controls), the time it would jump to while dragging. */
@Composable
private fun SeekRow() {
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: E.seekFrac
    val dur = E.curDur()
    Column(Modifier.fillMaxWidth()) {
        WavySeek(frac, E.playingState && !S.low && !E.reduceMotion(), onDrag = { f -> drag = f; E.seeking = true; E.poke() }, onDone = { f -> drag = null; E.seeking = false; E.seekFraction(f) }, enabled = E.src != "none" && dur > 0)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(fmtTime(if (drag != null) frac * dur else E.curTime()), fontSize = 11.5.sp, color = Color.White.copy(alpha = if (drag != null && S.b("xScrub")) 1f else .7f), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(dur), fontSize = 11.5.sp, color = Color.White.copy(alpha = .7f), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun VolumeRow() {
    val sp = E.src == "spotify"
    val dev = Spotify.device
    val canVol = !sp || (dev != null && dev.supportsVolume && dev.volume != null)
    val v = if (sp) (dev?.volume ?: 0) else S.i("volume")
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (v == 0) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (sp) "Spotify volume" else "Volume", Modifier.size(18.dp), tint = Color.White.copy(alpha = if (canVol) .8f else .3f))
        Slider(value = v / 100f, onValueChange = { f -> val x = (f * 100).toInt(); if (sp) Spotify.setVolume(x) else S.set("volume", x); E.poke() }, enabled = canVol,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .22f)))
        Text(if (canVol) "$v%" else "", fontSize = 11.5.sp, color = Color.White.copy(alpha = .7f), modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }
    @Suppress("unused") val keep = Vibe
}
