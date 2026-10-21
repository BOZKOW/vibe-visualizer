package io.github.bozkow.vibevisualizer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.engine.E

/** Notification cards (what's new, an update, a desync, Low performance mode…), newest on top, three at most. */
@Composable
fun NoticeCards(modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 460.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        E.notes.toList().forEach { n -> key(n.id) { NoteCard(n) } }
    }
}

@Composable
private fun NoteCard(n: E.Note) {
    val vis = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(vis, enter = fadeIn() + slideInVertically { -it / 3 } + scaleIn(initialScale = .96f)) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), strong = true).padding(16.dp)) {
            Text(n.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (n.kind == "warn") Color(0xFFFFD27A) else Color.White)
            n.sub?.let { Text(it, fontSize = 12.sp, color = Color.White.copy(alpha = .6f), modifier = Modifier.padding(top = 2.dp)) }
            n.msg?.let { Text(it, fontSize = 13.5.sp, lineHeight = 19.sp, color = Color.White.copy(alpha = .85f), modifier = Modifier.padding(top = 6.dp)) }
            if (n.list.isNotEmpty()) Column(Modifier.padding(top = 6.dp)) { n.list.forEach { Text("•  $it", fontSize = 13.sp, color = Color.White.copy(alpha = .8f), lineHeight = 18.sp) } }
            FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                n.actions.forEach { a ->
                    val go = { E.notes.remove(n); a.fn?.invoke(); Unit }
                    if (a.pri) Button(onClick = go, contentPadding = ButtonDefaults.SmallContentPadding) { Text(a.label) }
                    else FilledTonalButton(onClick = go, contentPadding = ButtonDefaults.SmallContentPadding) { Text(a.label) }
                }
            }
        }
    }
}

/** Short messages at the bottom ("Spotify connected.", "Saved to Liked Songs."). Tap to dismiss. */
@Composable
fun Toasts(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        E.toasts.toList().forEach { t ->
            key(t.id) {
                val vis = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(vis, enter = fadeIn() + slideInVertically { it / 2 }) {
                    Text(t.msg, Modifier.widthIn(max = 420.dp).glass(RoundedCornerShape(18.dp), strong = true).clickable { E.toasts.remove(t) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}
