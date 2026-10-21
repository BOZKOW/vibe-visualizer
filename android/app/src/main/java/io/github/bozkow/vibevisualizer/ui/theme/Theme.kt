package io.github.bozkow.vibevisualizer.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.em
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.github.bozkow.vibevisualizer.core.S
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.engine.Visuals

private var interFamily: FontFamily? = null

/** Inter, the web app's font, at every weight it uses (a variable font, so one file). */
@Composable
fun inter(): FontFamily {
    val ctx = LocalContext.current
    return interFamily ?: FontFamily(listOf(400, 500, 600, 700, 800).map { w ->
        Font("fonts/inter.ttf", ctx.assets, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }).also { interFamily = it }
}

val Bg = Color(0xFF07070C)

private fun typography(f: FontFamily): Typography {
    val b = Typography()
    fun TextStyle.i() = copy(fontFamily = f)
    return Typography(
        displayLarge = b.displayLarge.i(), displayMedium = b.displayMedium.i(), displaySmall = b.displaySmall.i(),
        headlineLarge = b.headlineLarge.i().copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-.5).sp),
        headlineMedium = b.headlineMedium.i().copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-.4).sp),
        headlineSmall = b.headlineSmall.i().copy(fontWeight = FontWeight.Bold),
        titleLarge = b.titleLarge.i().copy(fontWeight = FontWeight.Bold), titleMedium = b.titleMedium.i().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = b.titleSmall.i().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = b.bodyLarge.i().copy(letterSpacing = 0.sp), bodyMedium = b.bodyMedium.i().copy(letterSpacing = 0.sp), bodySmall = b.bodySmall.i().copy(letterSpacing = 0.sp),
        labelLarge = b.labelLarge.i().copy(fontWeight = FontWeight.SemiBold), labelMedium = b.labelMedium.i().copy(fontWeight = FontWeight.SemiBold),
        labelSmall = b.labelSmall.i().copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Always dark, like the web app. Android: neutral tones follow your wallpaper (Material You); the accent follows the
 *  cover, as the web app's --acc does, and blends over when the song changes. One UI: Samsung's dark greys and blue,
 *  the phone's own font (Samsung's on a Galaxy), rounder corners. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val base = remember { runCatching { dynamicDarkColorScheme(ctx) }.getOrDefault(darkColorScheme()) }
    val acc by animateColorAsState(Visuals.accent, tween(900), label = "accent")
    val one = S.oneUi
    val scheme = remember(acc, base, one) { if (one) oneUiScheme(base) else accentScheme(base, acc) }
    val typo = if (one) typography(FontFamily.Default) else typography(inter())
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), shapes = if (one) OneShapes else Shapes(), typography = typo) {
        // text sized by hand everywhere: its line height follows its size, no extra letter spacing
        CompositionLocalProvider(LocalTextStyle provides typo.bodyLarge.copy(lineHeight = 1.38.em, letterSpacing = 0.sp), content = content)
    }
}

/** One UI's blue (its dark-mode accent) and greys. */
val OneBlue = Color(0xFF3E91FF)
val OneCard = Color(0xFF1C1C1C)
private val OneShapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(26.dp))

private fun oneUiScheme(b: ColorScheme): ColorScheme = b.copy(
    primary = OneBlue, onPrimary = Color.White, primaryContainer = Color(0xFF1A3A66), onPrimaryContainer = Color(0xFFD7E7FF),
    secondary = OneBlue, onSecondary = Color.White, secondaryContainer = Color(0xFF23324A), onSecondaryContainer = Color.White,
    tertiary = OneBlue, onTertiary = Color.White,
    background = Color.Black, onBackground = Color(0xFFFAFAFA), surface = Color(0xFF171717), onSurface = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFFA6A6A6), outline = Color(0xFF4A4A4A), outlineVariant = Color(0xFF2C2C2C),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF171717), surfaceContainer = OneCard,
    surfaceContainerHigh = Color(0xFF242424), surfaceContainerHighest = Color(0xFF2C2C2C),
)

private fun accentScheme(b: ColorScheme, acc: Color): ColorScheme {
    val dark = Color(0xFF0E0E14)
    return b.copy(
        primary = acc, onPrimary = Color(0xFF111116),
        primaryContainer = lerp(dark, acc, .32f), onPrimaryContainer = lerp(Color.White, acc, .25f),
        secondaryContainer = lerp(dark, acc, .22f), onSecondaryContainer = lerp(Color.White, acc, .2f),
        surface = lerp(Color(0xFF101016), b.surface, .4f), background = Bg,
        surfaceContainerLowest = Color(0xFF0B0B10), surfaceContainerLow = acc.copy(alpha = .04f).compositeOver(Color(0xFF121218)),
        surfaceContainer = acc.copy(alpha = .06f).compositeOver(Color(0xFF16161D)), surfaceContainerHigh = acc.copy(alpha = .07f).compositeOver(Color(0xFF1C1C24)),
        surfaceContainerHighest = acc.copy(alpha = .08f).compositeOver(Color(0xFF23232C)),
    )
}
