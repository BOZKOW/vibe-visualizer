package io.github.bozkow.vibevisualizer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.engine.Visuals
import io.github.bozkow.vibevisualizer.lyrics.fromText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Renders the screens at a Galaxy S25's size (and a small phone, and landscape) into build/screens/, to check that
 *  everything fits. Not a pass/fail test: the pictures are looked at. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w384dp-h832dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun song(src: String = "file", playing: Boolean = true) {
        S.set("bgStyle", "color"); S.set("perfAsk", false)
        val bm = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bm).drawRect(0f, 0f, 300f, 300f, Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, 0xFF6A3DE8.toInt(), 0xFFE8743D.toInt(), Shader.TileMode.CLAMP) })
        Visuals.setCover(bm)
        E.src = src; E.title = "A Song With Quite a Long Title Here"; E.artist = "Some Artist feat. Another One"
        E.playingState = playing; E.clockOverride = 10.2
        val t = javaClass.classLoader!!.getResource("test.ttml")!!.readText()
        Lyr.applyFile(fromText(t), "test.ttml")
    }

    private fun run(ms: Int) {
        rule.mainClock.autoAdvance = false
        repeat(ms / 16) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); rule.mainClock.advanceTimeByFrame(); ShadowLooper.idleMainLooper() }
    }

    private fun shot(name: String) = captureScreenRoboImage("build/screens/$name.png")

    @Test fun nowPlaying() { song("spotify"); E.screen = "player"; rule.setContent { VibeRoot() }; run(1500); shot("1-now-playing") }
    @Test fun nowPlayingFile() { song("file"); S.set("volshow", true); E.screen = "player"; rule.setContent { VibeRoot() }; run(1500); shot("2-now-playing-file") }
    @Test fun lyrics() { song("spotify"); E.screen = "lyrics"; S.set("view", "both"); rule.setContent { VibeRoot() }; run(2500); shot("3-lyrics") }
    @Test fun viz() { song("file"); E.screen = "viz"; rule.setContent { VibeRoot() }; run(1500); shot("4-visualizer") }
    @Test fun more() { song("spotify"); E.screen = "player"; E.sheet = "more"; rule.setContent { VibeRoot() }; run(1500); shot("5-more-menu") }
    @Test fun empty() { E.src = "none"; E.screen = "player"; S.set("bgStyle", "color"); rule.setContent { VibeRoot() }; run(800); shot("6-empty") }
    @Test fun settings() { song(); E.settingsPage = null; rule.setContent { VibeRoot() }; run(200); E.openSettings("lyrics"); run(1200); shot("7-settings-lyrics") }
    @Test fun wizard() { E.src = "none"; S.set("bgStyle", "color"); E.wizard = true; rule.setContent { VibeRoot() }; run(1000); shot("8-setup") }
    @Config(qualifiers = "w360dp-h740dp-xxhdpi") @Test fun smallPhone() { song("spotify"); E.screen = "player"; S.set("volshow", true); rule.setContent { VibeRoot() }; run(1500); shot("9-small-phone") }
    @Config(qualifiers = "w832dp-h384dp-xxhdpi") @Test fun landscape() { song("spotify"); E.screen = "lyrics"; rule.setContent { VibeRoot() }; run(2000); shot("10-landscape") }
}
