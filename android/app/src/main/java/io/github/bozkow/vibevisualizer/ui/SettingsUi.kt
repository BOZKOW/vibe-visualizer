package io.github.bozkow.vibevisualizer.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.audio.Audio
import io.github.bozkow.vibevisualizer.core.Defaults
import io.github.bozkow.vibevisualizer.core.LANGS
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.engine.Desync
import io.github.bozkow.vibevisualizer.engine.E
import io.github.bozkow.vibevisualizer.engine.FilePicker
import io.github.bozkow.vibevisualizer.engine.Lyr
import io.github.bozkow.vibevisualizer.engine.Ping
import io.github.bozkow.vibevisualizer.engine.Reports
import io.github.bozkow.vibevisualizer.engine.Sync
import io.github.bozkow.vibevisualizer.engine.Updates
import io.github.bozkow.vibevisualizer.spotify.Spotify
import io.github.bozkow.vibevisualizer.spotify.SpotifyApp
import kotlinx.coroutines.launch

object SettingsUi { var last = "look" }

/* ---- the settings, declared once: shown in their pages, and found by the search box ---- */
sealed class Item(val search: String) {
    class Head(val t: String) : Item(t)
    class Sub(val t: String) : Item(t)
    class Sw(val k: String, val t: String, val d: String = "") : Item("$t $d")
    class Sl(val k: String, val t: String, val min: Int, val max: Int, val step: Int = 1, val d: String = "", val fmt: (Int) -> String = { "$it%" }) : Item("$t $d")
    class Ch(val k: String, val t: String, val opts: List<Pair<String, String>>, val d: String = "") : Item("$t $d " + opts.joinToString(" ") { it.second })
    class Txt(val t: String) : Item(t)
    class Custom(text: String, val c: @Composable () -> Unit) : Item(text)
}
class Page(val id: String, val name: String, val icon: ImageVector, val items: List<Item>)

private val pct: (Int) -> String = { "$it%" }

private fun pages(): List<Page> = listOf(
    Page("look", "Appearance", Icons.Rounded.Palette, listOf(
        Item.Head("Design"),
        Item.Ch("design", "Design", listOf("glass" to "Glass", "classic" to "Classic (the original look)"), "Glass: frosted glass everywhere. Classic: the original, simpler look."),
        Item.Ch("uiHide", "Controls on the Lyrics and Visualizer screens", listOf("idle" to "Hide while music plays", "never" to "Always visible"), "While music plays, the controls under the lyrics and the visualizer slide away after a few seconds; a tap brings them back."),
        Item.Head("Colors"),
        Item.Ch("theme", "Theme", listOf("cover" to "Cover colors", "aurora" to "Aurora", "sunset" to "Sunset", "neon" to "Neon", "ice" to "Ice", "mono" to "Mono", "custom" to "Custom")),
        Item.Custom("Custom colors") { CustomColors() },
        Item.Head("Background"),
        Item.Ch("bgStyle", "Background", listOf("dynamic" to "Dynamic (animated cover)", "artist" to "Artist image", "cover" to "Cover art", "color" to "Color", "none" to "None"), "Artist image needs Spotify; other songs use the cover art."),
        Item.Sl("bgblur", "Blur", 0, 100), Item.Sl("bri", "Brightness", 20, 140), Item.Sl("sat", "Saturation", 0, 300),
        Item.Sl("bgspeed", "Motion speed (0 = still)", 0, 300, fmt = { if (it == 0) "Still" else String.format("%.2g×", it / 100.0) }),
        Item.Head("Cover"),
        Item.Sw("online", "Search cover online for your own files"),
        Item.Custom("Choose cover image") { Buttons("Choose cover image…" to { FilePicker.image?.invoke() }) },
    )),
    Page("viz", "Visualizer", VIcons.bars, listOf(
        Item.Head("Visualizer"),
        Item.Custom("Audio for the bars phone sound microphone") { AudioChoice() },
        Item.Sw("bridge", "Vibe Bridge", "Bars from the computer that plays the music, through your server: it hears everything that computer plays, the Spotify app included."),
        Item.Custom("bridge status") { Hint(if (!S.b("bridge")) "Off." else if (Audio.bridgeOn) "Connected through your server" + (Audio.bridge.device.takeIf { it.isNotEmpty() }?.let { " · listening to $it" } ?: "") + "." else "Not connected. Start Vibe Bridge with --server ${Server.base?.removePrefix("https://") ?: "<your server>"} on the computer that plays the music.") },
        Item.Sw("auto", "Auto sensitivity"),
        Item.Sl("boost", "Sensitivity / boost", 30, 300), Item.Sl("resp", "Smoothness (low = snappier)", 0, 100), Item.Sl("blur", "Motion blur", 0, 90),
        Item.Sl("bars", "Bar count", 24, 128, fmt = { "$it" }), Item.Sl("gap", "Bar gap", 0, 70),
        Item.Sw("glow", "Glow"), Item.Sw("round", "Rounded bars"),
    )),
    Page("lyrics", "Lyrics", VIcons.lyrics, listOf(
        Item.Head("Lyrics"),
        Item.Custom("Sources, in priority order Spicy Lyrics LRCLIB NetEase AMLL Spotify your lyrics files") { Sources() },
        Item.Sl("loff", "Timing offset (ms)", -3000, 3000, 50, "Lyrics behind the music? Raise it. While a song plays, ⋮ → Lyrics timing moves them too.", fmt = { (if (it > 0) "+" else "") + it + " ms" }),
        Item.Ch("lDesync", "Desync detection", listOf("ask" to "Ask before fixing", "auto" to "Fix automatically", "off" to "Off"), "Listens to the music and checks that the singing lines up with the lyrics. Needs real sound for the bars (phone sound, Vibe Bridge, the microphone or your own file). A fix only applies to the song playing."),
        Item.Custom("desync status") { if (Desync.stat.isNotEmpty()) Hint(Desync.stat) },
        Item.Sl("lsize", "Text size", 70, 150), Item.Sw("lblur", "Blur inactive lines"),
        Item.Ch("credPos", "Lyrics credits", listOf("corner" to "Bottom-left corner", "lyrics" to "Under the lyrics"), "The provider, maker and uploader are always shown while lyrics are on screen (required by Spicy Lyrics)."),
        Item.Sub("Style"),
        Item.Ch("lfont", "Lyrics font", listOf("vv" to "Vibe Visualizer", "spicy" to "Spicy Lyrics", "system" to "System font"), "Spicy Lyrics: the display cut Spicy Lyrics shows lyrics in."),
        Item.Sw("lsimple", "Simple lyrics mode", "Drop the extra effects: words only fill, without growing, lifting or glowing."),
        Item.Sw("ljoin", "Join split words", "Show a word sung in syllables as one word, when its parts line up evenly. From Spicy Lyrics 6.4.50."),
        Item.Sw("lminimal", "Minimal lyrics mode", "Hide lines that have already been sung."),
        Item.Sw("lhover", "Highlight on press", "Show a soft box behind a line while your finger is on it."),
        Item.Sw("lduet", "Duet line padding", "Indent each singer’s lines toward their side, so duets read as two columns."),
        Item.Sw("lbgBr", "Background vocals in brackets", "Show background vocals as (like this), as Spicy Lyrics Mobile can."),
        Item.Sw("lcredLast", "Credits follow the last singer", "With credits under the lyrics: when a song ends on a line sung from the right, the credits move to the right too."),
        Item.Sub("Scrolling"),
        Item.Sw("lsmooth", "Smooth scrolling", "Glide from line to line instead of jumping."),
        Item.Sw("learly", "Early scroll", "Start scrolling to the next line just before it’s sung."),
        Item.Sl("learlyMs", "Early scroll time (ms)", 0, 800, 10, "How far ahead of the line the scroll starts.", fmt = { "$it ms" }),
        Item.Sw("lseekFade", "Seek fade-in compensation", "Tapping a line starts Spotify 300 ms before it, so its fade-in doesn’t clip the first word."),
        Item.Sub("Interface"),
        Item.Sw("lnotice", "No lyrics notice", "Say so when a song has no lyrics."),
        Item.Sw("volshow", "Volume slider"),
        Item.Head("Your lyrics files"),
        Item.Custom("Add lyrics files use a file for this song remove all") { MyFiles() },
        Item.Head("Spicy Lyrics"),
        Item.Custom("Spicy Lyrics status test connection API key") { SpicyStatus() },
    )),
    Page("tr", "Translation", VIcons.translate, listOf(
        Item.Head("Translation & romanization"),
        Item.Ch("romMode", "Romanization (Japanese, Korean, Chinese, Russian…)", listOf("replace" to "Instead of the original line", "below" to "Below the original line", "off" to "Off")),
        Item.Sw("tr", "Translate lyrics"),
        Item.Ch("trTo", "Translate into", LANGS),
        Item.Custom("Don’t translate lines in") { SkipLangs() },
        Item.Txt("Lines already in the language you translate into are never translated. Translation and romanization use Google Translate’s free web service (unofficial), so lyric lines are sent to Google. Japanese, Chinese, Korean, Cyrillic and Greek are romanized on the phone first."),
    )),
    Page("cache", "Lyrics cache", Icons.Rounded.Storage, listOf(
        Item.Head("Lyrics cache"),
        Item.Custom("Last lookup try another source copy debug info") { LookupLog() },
        Item.Custom("Clear this song’s cache clear stored lyrics reset this song’s lyrics") { CacheButtons() },
    )),
    Page("spotify", "Spotify & Canvas", VIcons.spotify, listOf(
        Item.Head("Spotify"),
        Item.Custom("Client ID redirect URI connect disconnect") { SpotifySection() },
        Item.Sub("The Spotify app on this phone"),
        Item.Sw("aSession", "Exact timing and controls from the Spotify app", "When the Spotify app on this phone plays, the lyrics follow its player exactly, song changes show at once, fewer requests go to Spotify, and play, pause, skip and seek work without Premium."),
        Item.Custom("notification access") { SessionStatus() },
        Item.Head("Canvas"),
        Item.Sw("canvasOn", "Canvas", "Spotify’s short looping video plays as the background behind the player (found by your server)."),
        Item.Sw("cvNoArt", "Hide the cover while a Canvas plays", "Only the Canvas, no cover in front of it."),
        Item.Custom("Choose canvas video Canvas endpoint") { CanvasSection() },
    )),
    Page("perf", "Performance", VIcons.bolt, listOf(
        Item.Head("Performance"),
        Item.Sw("low", "Low performance mode"),
        Item.Sw("perfAsk", "Suggest Low performance mode when needed", "Watches how smoothly the app runs while a song plays. If the phone can’t keep up, or the battery is low, a card offers to turn Low performance mode on."),
        Item.Ch("fpscap", "Limit frame rate", listOf("0" to "Off (as fast as the screen)", "30" to "30 fps", "60" to "60 fps", "90" to "90 fps", "120" to "120 fps"), "Caps how often the animations redraw, to save battery."),
        Item.Ch("anim", "Animations", listOf("system" to "Follow the system setting", "on" to "Always on", "off" to "Off"), "“Follow the system setting” turns the animations off when Android’s Remove animations is on (Settings → Accessibility)."),
        Item.Sw("fps", "Show FPS"),
        Item.Custom("Reset all settings") { Buttons("Reset all settings" to { Vibe.settings.resetAll(); E.toast("Settings reset", "ok") }) },
    )),
    Page("android", "Phone", Icons.Rounded.PhoneAndroid, listOf(
        Item.Head("Phone"),
        Item.Sw("aKeepOn", "Keep the screen on while music plays"),
        Item.Sw("aPip", "Picture-in-picture", "Leaving the app while music plays keeps the lyrics in a small window over your other apps."),
        Item.Sw("aLock", "Show over the lock screen", "The player shows on the lock screen while it’s open (Keep the screen on, docked in a stand)."),
        Item.Sw("aHaptics", "Haptic feedback", "A light tick on taps and toggles."),
        Item.Custom("Notification access battery app info permissions") { PhoneLinks() },
    )),
    Page("exp", "Experiments", Icons.Rounded.Science, listOf(
        Item.Head("Experiments"),
        Item.Txt("Ideas being tried out. They may change, or go away, in a later version."),
        Item.Sw("xRefCred", "Refined credits", "With credits under the lyrics: quiet text right below the last line: the songwriters, then the source. Dim until the last line is sung. From Spicy Lyrics 6.4.50."),
        Item.Sw("xScrub", "Scrub preview", "Dragging the progress bar shows the time it would jump to. From Spicy Lyrics 6.4.50."),
        Item.Sw("xSkeleton", "Lyrics loading skeleton", "While lyrics load, faint placeholder lines and a small “Loading lyrics” label instead of the big message. From Spicy Lyrics 6.4.0."),
        Item.Sw("xKeep", "Keep overlapping lines", "In Minimal lyrics mode, a line that ends while one above it is still being sung stays on screen, dimmed. From Spicy Lyrics 6.4.50."),
        Item.Sw("xBgPop", "Background vocal pop-out", "Background vocals stay tucked under their line until it’s sung, then slide out while the lines below make room. From Spicy Lyrics 6.4.50."),
        Item.Sw("xRefSimple", "Refreshed simple lyrics mode", "In Simple lyrics mode, the sung part fades in behind one soft edge, words float up as they’re sung, and the letters of a short held word swell, spread, lift and glow. From Spicy Lyrics 6.4.50."),
        Item.Sw("xFocus", "Focus mode", "The line being sung is sharp in the middle and stays until it’s sung to the end. The next line flies in from the bottom 200 ms before it’s sung; finished lines fly up and disappear."),
    )),
    Page("help", "Help & about", Icons.AutoMirrored.Rounded.Help, listOf(
        Item.Head("Help"),
        Item.Sub("Your server"),
        Item.Custom("Your server address Tailscale") { ServerField() },
        Item.Sub("App updates"),
        Item.Custom("Updates check for updates update now release notes") { AppUpdates() },
        Item.Sub("Your server’s web app"),
        Item.Custom("server web app update") { ServerUpdates() },
        Item.Sub("Error log"),
        Item.Custom("Error log show errors clear") { ErrorLog() },
        Item.Sub("Back up settings"),
        Item.Custom("Copy my settings paste settings") { Backup() },
        Item.Custom("Open setup guide forget this device") { Buttons("Open setup guide" to { E.settingsPage = null; E.wizard = true }, "Forget this device" to { E.ask = E.Ask("Forget this device?", "Deletes everything Vibe Visualizer saved for this phone: settings, the Spotify login, saved lyrics and your lyrics files, here and on your server.",
            listOf(E.Act("Cancel"), E.Act("Forget", pri = true) { Sync.forget { E.settingsPage = null; E.wizard = true } })) }) },
        Item.Custom("About license AGPL Spicy Lyrics unofficial source code") { About() },
    )),
)

/** Settings: categories (with a search box), each a page; two panes on a wide screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val all = remember { pages() }
    var page by remember { mutableStateOf<String?>(E.settingsPage?.takeIf { p -> all.any { it.id == p } }) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(E.settingsPage) { E.settingsPage?.takeIf { p -> all.any { it.id == p } }?.let { page = it } }
    LaunchedEffect(page) { page?.let { SettingsUi.last = it; if (it == "cache") Lyr.refreshCounts() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        BackHandler { if (q.isNotEmpty()) q = "" else if (!wide && page != null && E.settingsPage == null) page = null else if (!wide && page != null) { page = null } else E.settingsPage = null }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                if (wide || page == null || q.isNotEmpty()) {
                    Column(Modifier.then(if (wide) Modifier.width(300.dp) else Modifier.fillMaxWidth()).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { E.settingsPage = null }) { Icon(Icons.Rounded.Close, "Close settings") }
                            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        TextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp)), singleLine = true,
                            placeholder = { Text("Search settings") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                        if (q.isNotEmpty() && !wide) SearchResults(all, q, Modifier.weight(1f))
                        else LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                            items(all, key = { it.id }) { p ->
                                val on = page == p.id && wide
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(50)).background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { E.haptic(); q = ""; page = p.id }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(p.icon, null, Modifier.size(22.dp), tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else Color.White.copy(alpha = .85f))
                                    Spacer(Modifier.width(16.dp))
                                    Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                if (wide || (page != null && q.isEmpty())) {
                    if (wide && q.isNotEmpty()) SearchResults(all, q, Modifier.weight(1f))
                    else {
                        val p = all.find { it.id == (page ?: "look") } ?: all[0]
                        // Android's shared-axis move: the old page is gone before the new one comes in, nothing overlaps
                        AnimatedContent(p, Modifier.weight(1f), transitionSpec = { (slideInHorizontally(tween(260)) { it / 12 } + fadeIn(tween(200, delayMillis = 90))) togetherWith fadeOut(tween(90)) }, label = "page") { pg ->
                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (!wide) IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } else Spacer(Modifier.width(16.dp))
                                    Text(pg.name, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                ItemsList(pg.items.filterIndexed { i, it -> !(i == 0 && it is Item.Head && pg.items.count { x -> x is Item.Head } == 1) }, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResults(all: List<Page>, q: String, modifier: Modifier) {
    val ql = q.trim().lowercase()
    val hits = all.mapNotNull { p -> val its = p.items.filter { it !is Item.Head && it !is Item.Sub && it.search.lowercase().contains(ql) }; if (its.isEmpty()) null else p to its }
    if (hits.isEmpty()) { Text("No setting matches that.", Modifier.padding(24.dp), color = Color.White.copy(alpha = .6f)); return }
    LazyColumn(modifier.navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
        hits.forEach { (p, its) ->
            item(key = "h" + p.id) { SectionLabel(p.name) }
            items(its.size, key = { p.id + it }) { i -> ItemView(its[i]) }
        }
    }
}

@Composable
private fun ItemsList(items: List<Item>, modifier: Modifier) {
    val st = rememberLazyListState()
    LazyColumn(modifier.navigationBarsPadding(), state = st, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 48.dp)) {
        items(items.size) { i -> Box(Modifier.widthIn(max = 760.dp)) { ItemView(items[i]) } }
    }
}

@Composable
private fun ResetBtn(k: String) {
    if (Vibe.settings.changed(k)) IconButton(onClick = { E.haptic(); Vibe.settings.reset(k) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Restore, "Reset to default", Modifier.size(18.dp), tint = Color.White.copy(alpha = .6f)) }
}

@Composable
fun ItemView(it: Item) {
    when (it) {
        is Item.Head -> Text(it.t, Modifier.padding(top = 22.dp, bottom = 6.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        is Item.Sub -> SectionLabel(it.t)
        is Item.Txt -> Hint(it.t)
        is Item.Custom -> Box(Modifier.padding(vertical = 4.dp)) { it.c() }
        is Item.Sw -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { E.haptic(); S.set(it.k, !S.b(it.k)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(it.t, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                if (it.d.isNotEmpty()) Text(it.d, fontSize = 13.sp, lineHeight = 18.sp, color = Color.White.copy(alpha = .6f), modifier = Modifier.padding(top = 2.dp))
            }
            ResetBtn(it.k)
            Spacer(Modifier.width(8.dp))
            Switch(S.b(it.k), { v -> E.haptic(); S.set(it.k, v) })
        }
        is Item.Sl -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it.t, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                ResetBtn(it.k)
                Text(it.fmt(S.i(it.k)), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            val range = (it.max - it.min).toFloat()
            Slider((S.i(it.k) - it.min) / range, { f -> S.set(it.k, it.min + (Math.round(f * range / it.step) * it.step)) }, steps = 0)
            if (it.d.isNotEmpty()) Hint(it.d)
        }
        is Item.Ch -> {
            var open by remember { mutableStateOf(false) }
            val cur = it.opts.find { o -> o.first == S.str(it.k) }?.second ?: S.str(it.k)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Box {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.t, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(cur, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                        }
                        ResetBtn(it.k)
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White.copy(alpha = .6f))
                    }
                    DropdownMenu(open, { open = false }, shape = RoundedCornerShape(18.dp)) {
                        it.opts.forEach { (v, l) -> DropdownMenuItem(text = { Text(l, fontWeight = if (v == S.str(it.k)) FontWeight.Bold else FontWeight.Normal) }, onClick = { open = false; E.haptic(); S.set(it.k, v) }) }
                    }
                }
                if (it.d.isNotEmpty()) Hint(it.d)
            }
        }
    }
}

/* ---- custom rows ---- */

@Composable
fun Buttons(vararg b: Pair<String, () -> Unit>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { b.forEach { (l, f) -> FilledTonalButton(onClick = { E.haptic(); f() }) { Text(l) } } }
}

@Composable
private fun CustomColors() {
    val sw = listOf("#ff5a5f", "#ff8a3d", "#ffd23d", "#19f5b0", "#19e3ff", "#4f8cff", "#7a5cff", "#c24bff", "#ff2bd6", "#ffffff")
    Column {
        Text("Custom colors", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        listOf("c1" to "Color 1", "c2" to "Color 2").forEach { (k, l) ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l, fontSize = 13.sp, color = Color.White.copy(alpha = .7f), modifier = Modifier.width(64.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sw.forEach { h -> val c = Color(android.graphics.Color.parseColor(h)); val on = S.str(k).equals(h, true)
                        Box(Modifier.size(if (on) 30.dp else 26.dp).clip(CircleShape).background(c).clickable { S.set(k, h); if (S.str("theme") != "custom") S.set("theme", "custom") }) }
                }
            }
        }
    }
}

@Composable
private fun AudioChoice() {
    val opts = listOf("phone" to "Phone sound (everything this phone plays)", "mic" to "Microphone", "off" to "Nothing on this phone")
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Audio for the bars", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(opts.find { it.first == S.str("aCap") }?.second ?: "", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(Audio.labelTip, fontSize = 12.5.sp, color = Color.White.copy(alpha = .55f))
            }
            Icon(Icons.Rounded.KeyboardArrowDown, null)
        }
        DropdownMenu(open, { open = false }) {
            opts.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false
                if (k != "off" && !Audio.permitted()) Perms.mic { ok -> if (ok) S.set("aCap", k) } else S.set("aCap", k) }) }
        }
    }
}

@Composable
private fun Sources() {
    val list = Lyr.provList()
    Column(Modifier.animateContentSize()) {
        Text("Sources, in priority order", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        list.forEachIndexed { i, p ->
            val pr = Lyr.PROVS.getValue(p.k)
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = if (p.on) .06f else .02f)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(pr.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White.copy(alpha = if (p.on) 1f else .5f))
                    Text(pr.desc, fontSize = 11.5.sp, color = Color.White.copy(alpha = .5f))
                }
                IconButton(onClick = { val l = list.toMutableList(); l[i] = l[i - 1].also { l[i - 1] = l[i] }; Lyr.setProv(l) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { val l = list.toMutableList(); l[i] = l[i + 1].also { l[i + 1] = l[i] }; Lyr.setProv(l) }, enabled = i < list.size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                Switch(p.on, { v -> Lyr.setProv(list.map { x -> if (x.k == p.k) x.copy(on = v) else x }) })
            }
        }
        Hint("The first source that has the song is used. The arrows reorder, the switch turns a source off.")
    }
}

@Composable
private fun MyFiles() {
    LaunchedEffect(Unit) { Lyr.refreshCounts() }
    Column {
        Buttons("Add lyrics files…" to { FilePicker.lyrics?.invoke(false) }, "Use a file for this song…" to { FilePicker.lyrics?.invoke(true) }, "Remove all" to { Lyr.clearFiles() })
        Text(if (Lyr.filesCount == 0) "No lyrics files added yet." else if (Lyr.filesCount == 1) "1 lyrics file saved on this phone." else "${Lyr.filesCount} lyrics files saved on this phone.", Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Color.White)
        Hint("Your own TTML or LRC files, kept on this phone. Name them Artist - Title.ttml, or include the Spotify track id, and they’re used whenever that song plays (Spotify or your own file). “Use a file for this song” keeps it for the song playing. Turn “Your lyrics files” on or off, or move it, in the list of sources above.")
    }
}

@Composable
private fun SpicyStatus() {
    var stat by remember { mutableStateOf("Checking…") }
    LaunchedEffect(Ping.health) { stat = if (!Server.set) "Needs your server (Help & about → Your server)." else Ping.health?.let { if (it.optBoolean("spicy_key")) "API key saved on your server. Press Test to check it." else "No API key on your server yet. LRCLIB is used meanwhile." } ?: "Your server could not be reached." }
    Column {
        Text(stat, fontSize = 13.5.sp, color = Color.White)
        Buttons("Test connection" to { Lyr.testSpicy { stat = it } })
        Hint("Spicy Lyrics needs your own API key. Create an application in the Spicy Lyrics developer dashboard, copy its secret key (sl_sk_…, shown only once) and save it on your server as config/spicy_key (see the README). It stays there and never reaches the phone.")
        Hint("Every lyric shown credits its source, and community syncs credit their uploader and maker, as the Spicy Lyrics attribution rules require.")
        Row { TextButton(onClick = { openUrl("https://developers.spicylyrics.org/dashboard") }) { Text("Dashboard") }; TextButton(onClick = { openUrl("https://developers.spicylyrics.org/docs/attribution") }) { Text("Attribution rules") } }
    }
}

@Composable
private fun SkipLangs() {
    val sel = S.trSkip.split(',').filter { it.isNotEmpty() }.toSet()
    Column {
        Text("Don’t translate lines in (pick any)", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LANGS.filter { it.first != "zh-TW" }.forEach { (c, n) ->
                val k = c.split('-')[0]; val on = k in sel
                FilterChip(on, { val s = sel.toMutableSet(); if (on) s.remove(k) else s.add(k); S.set("trSkip", s.joinToString(",")) }, label = { Text(if (c == "zh-CN") "Chinese" else n) })
            }
        }
    }
}

@Composable
private fun LookupLog() {
    Column {
        SectionLabel("Last lookup")
        Hint("What each lyrics source said for the song that’s playing, in the order they were asked.")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .05f)).padding(12.dp)) {
            if (Lyr.lookupLog.isEmpty()) Text("No lookup yet.", fontSize = 13.sp, color = Color.White.copy(alpha = .6f))
            else {
                Text(Lyr.lookupSong, fontSize = 12.sp, color = Color.White.copy(alpha = .6f))
                Lyr.lookupLog.forEach { x -> Row(Modifier.padding(top = 6.dp)) { Text(Lyr.PROVS[x.p]?.name ?: x.p, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White, modifier = Modifier.width(150.dp)); Text(x.st + (x.ms?.let { " · $it ms" } ?: ""), fontSize = 12.5.sp, color = Color.White.copy(alpha = .7f)) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Buttons("Try another source for this song" to { Lyr.other() }, "Copy debug info" to { Lyr.copy(Lyr.debugInfo()); E.copied("Debug info") })
    }
}

@Composable
private fun CacheButtons() {
    LaunchedEffect(Unit) { Lyr.refreshCounts() }
    Column {
        Text(when (Lyr.savedCount) { 0 -> "No songs’ lyrics saved on this phone."; 1 -> "1 song’s lyrics saved on this phone."; else -> "${Lyr.savedCount} songs’ lyrics saved on this phone." }, Modifier.padding(vertical = 8.dp), fontSize = 13.sp, color = Color.White)
        @Composable fun row(t: String, d: String, b: String, f: () -> Unit) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(t, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White); Text(d, fontSize = 12.5.sp, color = Color.White.copy(alpha = .6f)) }
                Spacer(Modifier.width(10.dp)); FilledTonalButton(onClick = { E.haptic(); f() }) { Text(b) }
            }
        }
        row("Clear this song’s cache", "Remove everything saved for the song that’s playing (lyrics, romanization, translation) and fetch it again.", "Clear") { Lyr.clearSong() }
        row("Clear stored lyrics", "Delete all lyrics saved on this phone. They’re normally kept for up to 7 days.", "Clear") { Lyr.clearStored() }
        row("Reset this song’s lyrics", "Drop the playing song’s lyrics from memory only, so they load again.", "Reset") { Lyr.resetSong() }
    }
}

@Composable
private fun SpotifySection() {
    var id by remember { mutableStateOf(Vibe.cfg.spotId) }
    Column {
        OutlinedTextField(id, { id = it.trim(); Vibe.cfg.saveSpotId(id) }, Modifier.fillMaxWidth(), label = { Text("Client ID") }, placeholder = { Text("From developer.spotify.com/dashboard") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        Text("Redirect URI to register", fontSize = 13.sp, color = Color.White.copy(alpha = .7f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Spotify.redirectUri(), Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            TextButton(onClick = { Lyr.copy(Spotify.redirectUri()); E.copied("Redirect URI") }) { Text("Copy URI") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!Spotify.connected) Button(onClick = { E.spLogin() }) { Text("Connect") }
            else FilledTonalButton(onClick = { Spotify.disconnect(); if (E.src == "spotify") { E.setSource("none"); E.title = ""; E.artist = ""; Lyr.clear(""); E.goHome() } }) { Text("Disconnect") }
        }
        Text(if (!Spotify.connected) (if (id.isNotEmpty()) "Client ID saved. Press Connect." else "Not connected.") else if (E.src == "spotify") (if (Spotify.meta != null) "Connected · " + (if (Spotify.isPlaying) "playing" else "paused") else "Connected · nothing playing") else "Connected. Tap the Spotify chip to use it.",
            Modifier.padding(top = 8.dp), fontSize = 13.5.sp, color = Color.White)
        Hint("Setup: in the Spotify dashboard, add the Redirect URI above to your app (with a server set, it’s your server’s address, the one the web app already uses), paste the Client ID and press Connect. In development mode, add your own account under the app’s User Management.")
        Hint("Bars for the Spotify app on this phone come from “Phone sound” (Visualizer settings). For music playing on a computer, run Vibe Bridge there.")
    }
}

@Composable
private fun SessionStatus() {
    LaunchedEffect(Unit) { SpotifyApp.refresh(Vibe.app) }
    Column {
        Text(when {
            !SpotifyApp.installed(Vibe.app) -> "The Spotify app isn’t on this phone."
            !SpotifyApp.access.value -> "Needs notification access (no notification is read; it only lets the app see the Spotify app’s player)."
            SpotifyApp.connected.value -> "✓ Connected to the Spotify app" + if (Spotify.appTiming()) " · timing comes from it now" else ""
            else -> "Allowed. It connects when the Spotify app plays."
        }, fontSize = 13.5.sp, color = Color.White)
        if (!SpotifyApp.access.value) Buttons("Open notification access" to { Perms.notificationAccess() })
    }
}

@Composable
private fun CanvasSection() {
    var u by remember { mutableStateOf(Vibe.cfg.canvasUrl) }
    Column {
        Buttons("Choose canvas video…" to { FilePicker.video?.invoke() })
        OutlinedTextField(u, { u = it; Vibe.cfg.saveCanvasUrl(it) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Canvas endpoint") }, placeholder = { Text("/api/canvas?id={id}") }, singleLine = true)
        Hint("Spotify’s public API doesn’t include Canvas videos; your server finds them (with its sp_dc cookie). Another source works too: a URL that returns a video, or JSON with a url field. Placeholders: {id} {uri} {title} {artist}. An address starting with / is on your server.")
    }
}

@Composable
private fun PhoneLinks() {
    val ctx = E.activity ?: return
    Column {
        Buttons(
            "Notification access" to { Perms.notificationAccess() },
            "Battery: don’t restrict" to { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
            "App info & permissions" to { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) },
        )
        Hint("Microphone access is only used to read the spectrum of what the phone plays (or the room, with Microphone); nothing is recorded or sent.")
    }
}

@Composable
private fun AppUpdates() {
    LaunchedEffect(Unit) { if (Updates.appStat == "Not checked yet.") Updates.checkApp(false) }
    Column {
        Text(Updates.appStat, fontSize = 13.5.sp, color = Color.White)
        if (Updates.downloading) LinearProgressIndicator({ Updates.progress }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
        if (Updates.stage.isNotEmpty()) Text(Updates.stage + if (Updates.downloading) " " + (Updates.progress * 100).toInt() + "%" else "", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            FilledTonalButton(onClick = { Updates.checkApp(true) }) { Text("Check for updates") }
            Updates.latest?.let { r -> Button(onClick = { Updates.install(r) }, enabled = !Updates.downloading) { Text("Update now") } }
        }
        Hint("Update now downloads the new version from GitHub and installs it over this one; your settings and saved lyrics stay. The first time, Android asks you to allow it; after that, updates install by themselves.")
        if (Updates.notes.isNotEmpty()) {
            SectionLabel("Release notes")
            Updates.notes.forEachIndexed { i, n -> NoteView(n, i < 2) }
        }
    }
}

@Composable
private fun NoteView(n: Updates.Note, open: Boolean) {
    var o by remember { mutableStateOf(open) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .05f)).clickable { o = !o }.padding(12.dp)) {
        Row { Text(n.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f)); Text(n.date.take(10), fontSize = 12.sp, color = Color.White.copy(alpha = .5f)) }
        if (o) n.points.forEach { Text("•  $it", fontSize = 13.sp, color = Color.White.copy(alpha = .8f), modifier = Modifier.padding(top = 3.dp)) }
        else if (n.points.isNotEmpty()) Text("${n.points.size} change" + (if (n.points.size > 1) "s" else "") + " ›", fontSize = 12.sp, color = Color.White.copy(alpha = .5f))
    }
}

@Composable
private fun ServerUpdates() {
    Column {
        Text(Updates.serverStat, fontSize = 13.5.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            FilledTonalButton(onClick = { Updates.checkServer(true) }, enabled = Server.set) { Text("Check") }
            if (Updates.serverNew && Updates.serverCan) Button(onClick = { Updates.updateServer() }) { Text("Update now") }
        }
        Hint("The web app your computers open lives on your server; this downloads its newest version from GitHub onto the server (its settings, keys and saved lyrics stay).")
        Updates.serverNotes.take(5).forEachIndexed { i, n -> NoteView(n, i == 0) }
    }
}

@Composable
private fun ErrorLog() {
    var rows by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var stat by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column {
        Hint("Errors Vibe Visualizer runs into are saved on your server (not sent anywhere else), so they can be looked at and fixed.")
        rows?.let { r -> if (r.isEmpty()) Text("No errors saved.", fontSize = 13.sp, color = Color.White) else r.forEach { (m, w) -> Column(Modifier.padding(vertical = 4.dp)) { Text(m, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White); Text(w, fontSize = 11.5.sp, color = Color.White.copy(alpha = .5f)) } } }
        if (stat.isNotEmpty()) Text(stat, fontSize = 13.sp, color = Color.White)
        Buttons("Show errors" to { stat = "Loading…"; scope.launch { try { rows = Reports.load(); stat = "" } catch (e: Exception) { stat = "Couldn’t load the error log (${e.message})." } } },
            "Clear" to { scope.launch { Reports.clear(); rows = emptyList(); stat = "Cleared." } })
    }
}

@Composable
private fun Backup() {
    Column {
        Hint("Copy your settings (not your logins) to paste them on another device, or into the web app.")
        Buttons("Copy my settings" to { Lyr.copy(Sync.backupText()); E.copied("Settings") },
            "Paste settings" to {
                val cm = Vibe.app.getSystemService(android.content.ClipboardManager::class.java)
                val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(Vibe.app)?.toString() ?: ""
                val n = Sync.restore(t)
                if (n < 0) E.toast("That isn’t a copy of Vibe Visualizer’s settings", "warn") else E.toast("$n settings restored", "ok")
            })
    }
}

@Composable
private fun About() {
    Column(Modifier.padding(top = 12.dp)) {
        HorizontalDivider(color = Color.White.copy(alpha = .1f))
        Text("Vibe Visualizer for Android " + io.github.bozkow.vibevisualizer.BuildConfig.VERSION_NAME, Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold, color = Color.White)
        Hint("Vibe Visualizer is free software under the GNU AGPL-3.0. Its lyrics animation is ported from Spicy Lyrics by Spikerko and contributors. Vibe Visualizer is an unofficial project, not made by or affiliated with Spicy Lyrics. Inter font by Rasmus Andersson (OFL). Japanese readings by kuromoji (Apache 2.0).")
        Row { TextButton(onClick = { openUrl("https://github.com/BOZKOW/vibe-visualizer") }) { Text("Source code") }; TextButton(onClick = { openUrl("https://www.gnu.org/licenses/agpl-3.0.html") }) { Text("License") }; TextButton(onClick = { openUrl("https://github.com/Spikerko/spicy-lyrics") }) { Text("Spicy Lyrics") } }
    }
    @Suppress("unused") val keep = listOf(Defaults, Net)
}
