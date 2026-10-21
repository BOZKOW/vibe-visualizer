package io.github.bozkow.vibevisualizer.ui

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.engine.E

/** A frosted surface: Glass (translucent, a rim of light) or Classic (the original, flatter look). */
fun Modifier.glass(shape: Shape, strong: Boolean = false): Modifier {
    val classic = S.design == "classic"
    return this.clip(shape)
        .background(if (classic) Color(0xFF16161C).copy(alpha = if (strong) .92f else .78f) else Color(0xFF0C0C12).copy(alpha = if (strong) .62f else .42f), shape)
        .then(if (classic) Modifier else Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .09f), Color.White.copy(alpha = .03f))), shape))
        .border(1.dp, Color.White.copy(alpha = if (classic) .08f else .14f), shape)
}

/** An icon from an SVG path (24×24 unless said otherwise). */
fun svgIcon(d: String, size: Float = 24f): ImageVector = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = size, viewportHeight = size)
    .addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.White)).build()

object VIcons {
    val spotify = svgIcon("M12 2a10 10 0 100 20 10 10 0 000-20zm4.6 14.4a.6.6 0 01-.9.2c-2.4-1.5-5.4-1.8-8.9-1a.6.6 0 11-.3-1.2c3.9-.9 7.2-.5 9.9 1.1.3.2.4.6.2.9zm1.2-2.7a.8.8 0 01-1.1.3c-2.7-1.7-6.9-2.2-10.1-1.2a.8.8 0 11-.5-1.5c3.7-1.1 8.3-.6 11.4 1.3.4.3.5.8.3 1.1zm.1-2.8C14.7 9 9.4 8.8 6.3 9.7a1 1 0 11-.6-1.9c3.5-1.1 9.4-.9 13.1 1.3a1 1 0 01-.9 1.8z")
    val lyrics = svgIcon("M4 6h16v2H4zm0 5h12v2H4zm0 5h16v2H4z")
    val bars = svgIcon("M4 14h3v6H4zm6.5-6h3v12h-3zM17 4h3v16h-3z")
    val both = svgIcon("M4 5h16v2H4zm0 4h10v2H4zM4 17h2.5v3H4zm4.5-3H11v6H8.5zm4.5 1.5h2.5V20H13zm4.5-3.5H20v8h-2.5z")
    val sliders = svgIcon("M4 7h10v2H4zm12 0h4v2h-4zM4 15h4v2H4zm6 0h10v2H10zM14 5h2v6h-2zM8 13h2v6H8z")
    val bolt = svgIcon("M13 2L4 14h6l-1 8 9-12h-6l1-8z")
    val resync = svgIcon("M12 4V1L8 5l4 4V6a6 6 0 016 6 6 6 0 01-.7 2.8l1.46 1.46A8 8 0 0020 12a8 8 0 00-8-8zm0 14a6 6 0 01-6-6c0-1 .25-1.97.7-2.8L5.24 7.74A8 8 0 004 12a8 8 0 008 8v3l4-4-4-4v3z")
    val translate = svgIcon("M12.87 15.07l-2.54-2.51.03-.03A17.52 17.52 0 0014.07 6H17V4h-7V2H8v2H1v2h11.17C11.5 7.92 10.44 9.75 9 11.35 8.07 10.32 7.3 9.19 6.69 8h-2c.73 1.63 1.73 3.17 2.98 4.56l-5.09 5.02L4 19l5-5 3.11 3.11.76-2.04zM18.5 10h-2L12 22h2l1.12-3h4.75L21 22h2l-4.5-12zm-2.62 7l1.62-4.33L19.12 17h-3.24z")
    val heart = svgIcon("M16.5 3A6 6 0 0012 5.1 6 6 0 007.5 3 5.5 5.5 0 002 8.5c0 3.8 3.4 6.9 8.6 11.5l1.4 1.4 1.4-1.3C18.6 15.4 22 12.3 22 8.5A5.5 5.5 0 0016.5 3zm-4.4 15.6l-.1.1-.1-.1C7.1 14.2 4 11.4 4 8.5 4 6.5 5.5 5 7.5 5c1.5 0 3 1 3.6 2.4h1.8C13.5 6 15 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.9-3.1 5.7-7.9 10.1z")
    val heartOn = svgIcon("M12 21.4l-1.4-1.4C5.4 15.4 2 12.3 2 8.5A5.5 5.5 0 017.5 3 6 6 0 0112 5.1 6 6 0 0116.5 3 5.5 5.5 0 0122 8.5c0 3.8-3.4 6.9-8.6 11.5z")
    val devices = svgIcon("M4 6h18V4H4a2 2 0 00-2 2v11H0v3h14v-3H4V6zm19 2h-6a1 1 0 00-1 1v10a1 1 0 001 1h6a1 1 0 001-1V9a1 1 0 00-1-1zm-1 9h-4v-7h4v7z")
}

/** The logo: the web app's gradient tile with five bars. */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 64f
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF8B7CFF), Color(0xFFFF5FA2), Color(0xFF36C9FF)), Offset.Zero, Offset(this.size.width, this.size.height)), cornerRadius = CornerRadius(16 * s))
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f), Color.Transparent), 0f, this.size.height / 2), cornerRadius = CornerRadius(16 * s))
        val bars = listOf(floatArrayOf(9.5f, 13f, 20f, .78f), floatArrayOf(19f, 19f, 22f, .9f), floatArrayOf(28.75f, 25f, 26f, 1f), floatArrayOf(38.5f, 19f, 22f, .9f), floatArrayOf(48f, 13f, 20f, .78f))
        bars.forEach { b -> drawRoundRect(Color.White.copy(alpha = b[3]), Offset(b[0] * s, b[1] * s), Size(6.5f * s, b[2] * s), CornerRadius(3.25f * s)) }
    }
}

@Composable
fun SectionLabel(t: String, modifier: Modifier = Modifier) {
    Text(t.uppercase(), modifier.padding(top = 16.dp, bottom = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White.copy(alpha = .45f))
}

@Composable
fun Hint(t: String, modifier: Modifier = Modifier) { Text(t, modifier.padding(vertical = 4.dp), fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = .6f)) }

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

fun openUrl(url: String) {
    val a = E.activity ?: return
    runCatching {
        CustomTabsIntent.Builder().setShowTitle(true).setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(0xFF07070C.toInt()).build()).build().launchUrl(a, Uri.parse(url))
    }.onFailure { runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
}

val pillShape = RoundedCornerShape(50)
val cardShape = RoundedCornerShape(24.dp)
fun circle() = CircleShape
@Suppress("unused") private val keepTheme = MaterialTheme
