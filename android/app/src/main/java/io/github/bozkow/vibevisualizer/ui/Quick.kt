package io.github.bozkow.vibevisualizer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.PhotoSizeSelectSmall
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.engine.CanvasVideo
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Lyr

/** Quick settings: what you change while listening, in a panel that floats over the player (One UI 9's floating Quick
 *  Settings panel; Spicy Lyrics Mobile has one too), before the full Settings. The player blurs behind it. */
@Composable
fun QuickPanel(vis: MutableTransitionState<Boolean>) {
    val close = { E.sheet = null; E.poke() }
    if (vis.targetState) BackHandler { close() }
    AnimatedVisibility(vis, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).pointerInput(Unit) { detectTapGestures { close() } })
    }
    AnimatedVisibility(vis, enter = slideInVertically(tween(300)) { -it / 4 } + fadeIn(tween(220)) + scaleIn(tween(300), initialScale = .96f),
        exit = slideOutVertically(tween(200)) { -it / 5 } + fadeOut(tween(160)) + scaleOut(tween(200), targetScale = .97f)) {
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), contentAlignment = Alignment.TopCenter) {
            val shape = RoundedCornerShape(30.dp)
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 760.dp).shadow(28.dp, shape).clip(shape)
                .background(if (S.oneUi) Color(0xFF1E1E1E).copy(alpha = .94f) else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f))
                .clickable(remember { MutableInteractionSource() }, null) {}   // taps inside don't close it
                .verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick settings", Modifier.weight(1f).padding(start = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .1f)).clickable { E.haptic(); close(); E.openSettings() }
                        .semantics { contentDescription = "All settings" }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Settings, null, Modifier.size(22.dp), tint = Color.White) }
                }
                Spacer(Modifier.height(14.dp))
                // the tiles, two by two
                val tiles = buildList<@Composable (Modifier) -> Unit> {
                    if (Lyr.needTr) add { m -> Tile(m, Icons.Rounded.Translate, "Translate", S.tr) { S.set("tr", !S.tr) } }
                    if (Lyr.needRom) add { m -> Tile(m, VIcons.lyrics, "Romanization", S.romMode != "off") { toggleRom() } }
                    add { m -> Tile(m, VIcons.bars, "Bars everywhere", S.view == "both") { S.set("view", if (S.view == "both") "lyrics" else "both") } }
                    add { m -> Tile(m, Icons.Rounded.Videocam, "Canvas", S.canvasOn) { S.set("canvasOn", !S.canvasOn) } }
                    if (CanvasVideo.active || S.b("cvBlur")) add { m -> Tile(m, Icons.Rounded.BlurOn, "Blur Canvas", S.b("cvBlur")) { S.set("cvBlur", !S.b("cvBlur")) } }
                    if (CanvasVideo.active) add { m -> Tile(m, Icons.Rounded.PhotoSizeSelectSmall, "Smaller cover", S.b("cvShrink")) { S.set("cvShrink", !S.b("cvShrink")) } }
                    add { m -> Tile(m, Icons.Rounded.LightMode, "Keep screen on", S.b("aKeepOn")) { S.set("aKeepOn", !S.b("aKeepOn")) } }
                    add { m -> Tile(m, Icons.Rounded.PictureInPicture, "Picture-in-picture", S.b("aPip")) { S.set("aPip", !S.b("aPip")) } }
                    add { m -> Tile(m, Icons.Rounded.Bolt, "Low performance", S.low) { S.set("low", !S.low) } }
                }
                tiles.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { it(Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                // thick sliders, as One UI 9's brightness and volume
                ThickSlider(Icons.Rounded.TextFields, "Lyrics size", S.i("lsize"), 70, 150) { S.set("lsize", it) }
                Spacer(Modifier.height(8.dp))
                ThickSlider(Icons.Rounded.BrightnessMedium, "Background brightness", S.bri, 20, 140) { S.set("bri", it) }
                if (S.b("cvBlur")) { Spacer(Modifier.height(8.dp)); ThickSlider(Icons.Rounded.BlurOn, "Canvas blur", S.i("cvBlurAmt"), 5, 100) { S.set("cvBlurAmt", it) } }
                Spacer(Modifier.height(14.dp))
                Text("Design", Modifier.padding(start = 4.dp, bottom = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .08f)).padding(4.dp)) {
                    listOf("android" to "Android", "oneui" to "One UI").forEach { (k, l) ->
                        val on = S.str("aDesign") == k
                        Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(50)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { E.haptic(); S.set("aDesign", k) }, contentAlignment = Alignment.Center) {
                            Text(l, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (Lyr.needSync) QuickRow(Icons.Rounded.Timer, "Lyrics timing", "Earlier, later, this output, resync") { E.sheet = "timing" }
                if (Lyr.lines > 0) QuickRow(Icons.Rounded.SwapHoriz, "Wrong lyrics? Try another source", Lyr.label.ifEmpty { null }) { close(); Lyr.other() }
                QuickRow(Icons.Rounded.Settings, "All settings", null) { close(); E.openSettings() }
            }
        }
    }
}

/** A One UI quick-panel tile: lit (the accent) when on. */
@Composable
private fun Tile(modifier: Modifier, icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(modifier.height(64.dp).clip(RoundedCornerShape(22.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .08f))
        .clickable { E.haptic(); onClick() }.padding(horizontal = 14.dp).semantics { contentDescription = label + if (on) ", on" else ", off" }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (on) MaterialTheme.colorScheme.onPrimary else Color.White)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) MaterialTheme.colorScheme.onPrimary else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (on) "On" else "Off", fontSize = 12.sp, color = (if (on) MaterialTheme.colorScheme.onPrimary else Color.White).copy(alpha = .7f))
        }
    }
}

@Composable
private fun QuickRow(icon: ImageVector, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { E.haptic(); onClick() }.padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .85f))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = Color.White.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp), tint = Color.White.copy(alpha = .5f))
    }
}

/** A thick slider, as One UI 9's brightness and volume: a pill that fills from the left, its icon and name inside. */
@Composable
fun ThickSlider(icon: ImageVector, label: String, value: Int, min: Int, max: Int, set: (Int) -> Unit) {
    val frac = ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    fun at(x: Float, w: Int) = (min + (x / w).coerceIn(0f, 1f) * (max - min)).toInt()
    Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = .1f))
        .pointerInput(min, max) { detectTapGestures { o -> set(at(o.x, size.width)) } }
        .pointerInput(min, max) { detectHorizontalDragGestures { ch, _ -> set(at(ch.position.x, size.width)) } }
        .semantics { contentDescription = "$label, $value" }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(maxOf(frac, .001f)).background(Color.White.copy(alpha = .88f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (frac > .08f) Color(0xFF111111) else Color.White)
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (frac > .3f) Color(0xFF111111) else Color.White, maxLines = 1)
            Text("$value%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (frac > .95f) Color(0xFF111111) else Color.White.copy(alpha = .8f))
        }
    }
}
