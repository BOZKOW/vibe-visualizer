package io.github.bozkow.vibevisualizer.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.bozkow.vibevisualizer.ui.theme.VibeTheme

/** One widget's settings (opened when it's placed, from its own Settings when you touch and hold it, or from the
 *  app's Settings → Phone): what it shows and how, with the widget itself as the preview. */
class WidgetSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        setContent { VibeTheme { Setup(id) { look -> WidgetLooks.save(id, look); Widgets.refresh(); setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish() } } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(id: Int, done: (WidgetLook) -> Unit) {
    var look by remember { mutableStateOf(WidgetLooks.read(id)) }
    val size = remember { Widgets.sizeDp(id) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E12))) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Widget", Modifier.padding(top = 18.dp, bottom = 14.dp), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            // the widget itself, as it will look
            BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF2A2A33)).padding(12.dp), contentAlignment = Alignment.Center) {
                val w = minOf(size.first.toFloat(), maxWidth.value); val h = size.second.toFloat()
                val rv = remember(look) { runCatching { Widgets.views(id, look, w.toInt() to h.toInt()) }.getOrNull() }
                AndroidView({ FrameLayout(it) }, Modifier.size(w.dp, h.dp), update = { fl ->
                    fl.removeAllViews(); rv?.let { r -> runCatching { fl.addView(r.apply(fl.context, fl)) } }
                })
            }
            Section("Lyrics")
            Choice("Lines around the one sung now", listOf("0" to "None", "1" to "1", "2" to "2", "3" to "3"), look.around.toString()) { look = look.copy(around = it.toInt()) }
            Toggle("Romanization", "Under the line, for songs in another script", look.rom) { look = look.copy(rom = it) }
            Toggle("Translation", "Under the line, when the lyrics are translated", look.tr) { look = look.copy(tr = it) }
            Choice("Text size", listOf("s" to "Small", "m" to "Medium", "l" to "Large"), look.size) { look = look.copy(size = it) }
            Toggle("Centred", "The lines in the middle instead of from the left", look.center) { look = look.copy(center = it) }
            Section("Song and controls")
            Choice("The song", listOf("top" to "At the top", "bottom" to "At the bottom", "hidden" to "Hidden"), look.song) { look = look.copy(song = it) }
            Choice("Controls", listOf("none" to "None", "play" to "Play / pause", "all" to "Previous, play / pause, next"), look.controls) { look = look.copy(controls = it) }
            Toggle("Time and progress bar", "", look.progress) { look = look.copy(progress = it) }
            Section("Background")
            Choice("", listOf("cover" to "Blurred cover", "color" to "Cover colour", "black" to "Black", "clear" to "See-through"), look.bg) { look = look.copy(bg = it) }
            if (look.bg == "cover" || look.bg == "color") {
                Text("Darker: ${look.dim}%", Modifier.padding(top = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Slider(look.dim / 90f, { look = look.copy(dim = Math.round(it * 90 / 5f) * 5) })
            }
            Text("Where the lyrics are from always shows under them: the sources ask for it.", Modifier.padding(top = 14.dp), fontSize = 13.sp, color = Color.White.copy(alpha = .55f))
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 28.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { look = WidgetLook() }) { Text("Reset") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = { done(look) }, Modifier.height(48.dp)) { Text("Done", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun Section(t: String) = Text(t.uppercase(), Modifier.padding(top = 22.dp, bottom = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Color.White.copy(alpha = .55f))

@Composable
private fun Toggle(t: String, d: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
            if (d.isNotEmpty()) Text(d, fontSize = 13.sp, color = Color.White.copy(alpha = .6f))
        }
        Switch(on, set)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(t: String, opts: List<Pair<String, String>>, cur: String, set: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (t.isNotEmpty()) Text(t, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            opts.forEach { (k, l) -> FilterChip(selected = k == cur, onClick = { set(k) }, label = { Text(l) }) }
        }
    }
}
