package io.github.bozkow.vibevisualizer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Images
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.lyrics.Credit
import io.github.bozkow.vibevisualizer.lyrics.Person
import kotlin.math.abs
import kotlin.math.roundToInt

/** The lyrics area: the lyrics themselves (scroll with a finger, tap a line to jump there), or a message where they go
 *  (looking for them, none, an error, problems). */
@Composable
fun LyricsPane(modifier: Modifier = Modifier, areaHeightPx: Float = 0f, compact: Boolean = false, padStart: Float = 22f, padEnd: Float = 30f) {
    val gc = LocalGraphicsContext.current
    val view = Lyr.view
    val dens = LocalDensity.current
    val conf = LocalConfiguration.current
    remember(gc) { view.releaser = { gc.releaseGraphicsLayer(it) }; 0 }
    val n = Lyr.lines
    // problems never sit on the lyrics: with lyrics on screen they're a line in the header; without, the empty state
    val prob = if (n == 0) E.problems(false).firstOrNull() else null
    BoxWithConstraints(modifier) {
        val wDp = maxWidth.value
        // the lyrics' size: big and bold on a phone, as the web app sizes them on a computer (times Text size)
        val baseSp = when { compact -> 17f * S.i("aPipSize").coerceIn(60, 200) / 100f; wDp < 600 -> 30f; else -> (wDp * .033f).coerceIn(24f, 52f) }
        val fontPx = with(dens) { (baseSp * S.lsize / 100f).sp.toPx() }
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { sz -> view.size(sz.width.toFloat(), sz.height.toFloat(), if (areaHeightPx > 0) areaHeightPx else sz.height.toFloat(),
                    conf.screenWidthDp * dens.density, conf.screenHeightDp * dens.density, dens.density, fontPx, padStart * dens.density, padEnd * dens.density) }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragged = false; var lastY = down.position.y; val slop = 8.dp.toPx()
                        val idx = view.lineAt(down.position.y); view.pressed = idx; view.dragged = false; view.bump()
                        while (true) {
                            val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val dy = ch.position.y - lastY
                            if (!dragged && abs(ch.position.y - down.position.y) > slop) { dragged = true; view.dragged = true; view.pressed = -1; E.poke() }
                            if (dragged) { view.freeBy(dy); lastY = ch.position.y; ch.consume() }
                            else if (ch.positionChange() != Offset.Zero) Unit
                        }
                        view.pressed = -1; view.bump()
                        if (!dragged && idx >= 0 && !E.wakeTap) Lyr.tap(idx)
                        view.dragged = false
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                view.tick.longValue
                view.draw(this) { gc.createGraphicsLayer() }
                // the clear band: sharp between two fades (wider for a line too tall for it)
                val h = size.height
                if (h > 0) {
                    val t = (view.bandTop() / h).coerceIn(0.001f, .98f); val b = (view.bandBottom() / h).coerceIn(t + .001f, .999f)
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, t to Color.Black, b to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }
            }
            // Refined credits (Spicy Lyrics 6.4.50): right under the last line
            if (n > 0 && S.credPos == "lyrics" && S.b("xRefCred")) {
                val c = Lyr.credit
                if (c != null) {
                    // Focus mode: the lines fly in from the bottom and away at the top, so the credits wait for the end (the
                    // corner ones show meanwhile) and then come in the middle, where the lines were; pinned to the foot
                    // they sat on the lines flying in there
                    val focus = S.xFocus && !view.isStatic
                    val a by animateFloatAsState(if (focus) (if (view.allsung) 1f else 0f) else if (view.allsung || view.isStatic) 1f else .55f, tween(600), label = "lcr")
                    if (a > 0f) Box(Modifier.fillMaxWidth().then(if (focus) Modifier.align(Alignment.CenterStart) else Modifier.offset { IntOffset(0, view.creditsY.roundToInt()) })
                        .padding(start = padStart.dp, end = padEnd.dp).alpha(a)) {
                        RefinedCredits(c, view.lineCount() > 0 && Lyr.L.lastOrNull { !it.dots }?.opp == true && S.b("lcredLast"))
                    }
                }
            }
        }
        // where the lyrics go: messages and problems
        val m = Lyr.msg
        if (prob != null) ProblemState(prob, Modifier.align(Alignment.CenterStart).padding(start = padStart.dp, end = padEnd.dp))
        else if (n == 0 && m != null) LyricsMessage(m, Modifier.fillMaxSize().padding(start = padStart.dp, end = padEnd.dp), compact)
        AnimatedVisibility(view.lyFree, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            FilledTonalButton(onClick = { view.freeEnd() }, shape = pillShape) {
                Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(18.dp)); HSpace(6.dp); Text("Back to the lyrics being sung")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsMessage(m: Lyr.Msg, modifier: Modifier, compact: Boolean) {
    Column(modifier, verticalArrangement = if (m.kind == "load" && S.b("xSkeleton")) Arrangement.Top else Arrangement.Center) {
        if (m.kind == "load" && S.b("xSkeleton")) {   // loading skeleton (Spicy Lyrics 6.4.0): faint placeholder lines and a small label
            val tr = rememberInfiniteTransition(label = "skel")
            val x by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "skx")
            Box(Modifier.height(if (compact) 8.dp else 40.dp))
            Text("Loading lyrics", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .5f), modifier = Modifier.padding(bottom = 8.dp))
            listOf(.78f, .62f, .88f, .54f, .7f).forEachIndexed { k, w ->
                Box(Modifier.fillMaxWidth(w).padding(vertical = 7.dp).height(if (compact) 12.dp else 22.dp).clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = .07f), Color.White.copy(alpha = .14f), Color.White.copy(alpha = .07f)), startX = (x * 2 - 1 - k * .12f) * 900f, endX = (x * 2 - k * .12f) * 900f)))
            }
        } else if (m.kind == "load") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(36.dp), color = Color.White.copy(alpha = .7f))
                Spacer(Modifier.width(12.dp))
                Text(m.msg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .7f))
            }
        } else EmptyLines(VIcons.lyrics, m.msg, m.sub, m.actions.firstOrNull { it.pri } ?: m.actions.firstOrNull(), m.actions.drop(1).firstOrNull()?.takeIf { m.actions.size > 1 }, compact)
    }
}

/** The empty state where the lyrics go: an icon, a few words, and the one thing to do about it (as Android apps do). */
@Composable
fun ProblemState(p: E.Prob, modifier: Modifier = Modifier, compact: Boolean = false) {
    val icon = when (p.id) { "offline" -> Icons.Rounded.CloudOff; "server" -> Icons.Rounded.Dns; "wait" -> Icons.Rounded.HourglassTop; else -> VIcons.spotify }
    Column(modifier) { EmptyLines(icon, p.t, p.s, p.act, null, compact, tint = if (p.k == "err") Color(0xFFFFB4AB) else Color(0xFFFFD27A)) }
}

@Composable
private fun EmptyLines(icon: ImageVector, title: String, sub: String, act: E.Act?, act2: E.Act?, compact: Boolean, tint: Color = Color.White.copy(alpha = .6f)) {
    Icon(icon, null, Modifier.size(if (compact) 24.dp else 34.dp), tint = tint)
    Spacer(Modifier.height(10.dp))
    Text(title, fontSize = if (compact) 16.sp else 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
    if (sub.isNotEmpty()) Text(sub, Modifier.padding(top = 4.dp).widthIn(max = 460.dp), fontSize = if (compact) 13.sp else 15.sp, color = Color.White.copy(alpha = .65f))
    if (act != null || act2 != null) Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        act?.let { a -> FilledTonalButton(onClick = { E.haptic(); a.fn?.invoke() }) { Text(a.label) } }
        act2?.let { a -> TextButton(onClick = { E.haptic(); a.fn?.invoke() }) { Text(a.label) } }
    }
}

/* ---- credits, as Spicy Lyrics lays them out (its attribution rules want them on screen with the lyrics) ---- */

fun profileUrl(x: Person): String? = if (x.url?.startsWith("https://") == true) x.url else x.id?.let { "https://spicylyrics.org/uid/" + java.net.URLEncoder.encode(it, "UTF-8") }

@Composable
fun Avatar(url: String?, size: Int) {
    if (url == null || !url.startsWith("https://")) return
    val bm by produceState<android.graphics.Bitmap?>(Images.cached("$url@96"), url) { value = Images.load(url, 96) }
    bm?.let { Image(it.asImageBitmap(), null, Modifier.size(size.dp).clip(CircleShape)) }
}

@Composable
private fun Who(x: Person, prefix: String) {
    val u = profileUrl(x)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(prefix, color = Color.White.copy(alpha = .6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.then(if (u != null) Modifier.clickable { openUrl(u) } else Modifier), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("@" + x.username, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Avatar(x.avatar, 16)
        }
    }
}

/** The small credit (bottom-left corner, or under the lyrics): the provider, and who made and uploaded community lyrics. */
@Composable
fun CornerCredits(c: Credit, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        if (c.source == "file") { Text("From your file: " + c.name, color = Color.White.copy(alpha = .7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold); return@Column }
        // the songwriters first, as under the lyrics (when the source names them)
        if (c.writers.isNotEmpty()) Text("Written by " + c.writers.joinToString(", "), color = Color.White.copy(alpha = .7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = if (alignEnd) TextAlign.End else TextAlign.Start)
        val name = c.source?.let { Lyr.PROVIDERS[it] ?: it } ?: "Unknown"
        Row {
            Text("Provided by: ", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(name + (c.via?.let { " ($it)" } ?: ""), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = when (c.source) { "lrclib" -> Modifier.clickable { openUrl("https://lrclib.net") }; "unison" -> Modifier.clickable { openUrl("https://unison.boidu.dev") }; else -> Modifier })
        }
        if (c.source == "spicy_lyrics") {
            c.maker?.let { Who(it, "Made by ") }
            c.uploader?.let { Who(it, "Uploaded by ") }   // the uploader as the uploader, the maker only when one is given (Spicy Lyrics terms s.6)
        }
        if (c.source == "unison") c.uploader?.let { Who(it, "Synced by ") }
    }
}

@Composable
private fun RefinedCredits(c: Credit, right: Boolean) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (right) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (c.writers.isNotEmpty()) Text("Written by " + c.writers.joinToString(", "), color = Color.White.copy(alpha = .7f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = if (right) TextAlign.End else TextAlign.Start)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {   // room between wrapped lines, so an underline never meets the avatar below
            val st = Modifier
            when {
                c.source == "file" -> Text("From your file: " + c.name, color = Color.White.copy(alpha = .55f), fontSize = 12.sp, modifier = st)
                c.source == "unison" && c.uploader != null -> {
                    Text("Unison community lyrics", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                    Text("·", color = Color.White.copy(alpha = .4f), fontSize = 12.sp); Who(c.uploader, "Synced by")
                }
                c.source == "spicy_lyrics" && (c.maker != null || c.uploader != null) -> {
                    Text("Spicy Lyrics community lyrics", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                    c.maker?.let { Text("·", color = Color.White.copy(alpha = .4f), fontSize = 12.sp); Who(it, "Made by") }
                    c.uploader?.let { Text("·", color = Color.White.copy(alpha = .4f), fontSize = 12.sp); Who(it, "Uploaded by") }
                }
                else -> Text("Provided by " + (c.source?.let { Lyr.PROVIDERS[it] ?: it } ?: "an unknown source") + (c.via?.let { " via $it" } ?: ""), color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
            }
        }
        Box(Modifier.height(24.dp))
    }
}

@Suppress("unused") private val keepFull = Modifier.fillMaxHeight()
