package io.github.bozkow.vibevisualizer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import io.github.bozkow.vibevisualizer.core.S
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bozkow.vibevisualizer.engine.E

private class SnackLook(val s: E.Snack) : SnackbarVisuals {
    override val message = s.msg
    override val actionLabel = s.action?.label
    override val withDismissAction = false
    override val duration = if (s.long || s.action != null) SnackbarDuration.Long else SnackbarDuration.Short
}

/** Short messages, one at a time, at the bottom: Android's snackbar. Swipe one away to dismiss it. */
@Composable
fun Snacks(modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    val cur = E.snacks.firstOrNull()
    LaunchedEffect(cur?.id) {
        val s = cur ?: return@LaunchedEffect
        val r = host.showSnackbar(SnackLook(s))
        E.snacks.remove(s)
        if (r == SnackbarResult.ActionPerformed) s.action?.fn?.invoke() else s.onDismiss?.invoke()
    }
    SnackbarHost(host, modifier) { data ->
        val st = rememberSwipeToDismissBoxState()
        SwipeToDismissBox(st, backgroundContent = {}, onDismiss = { data.dismiss() }) {
            val err = (data.visuals as? SnackLook)?.s?.kind == "err"
            Snackbar(
                Modifier.padding(horizontal = 12.dp).widthIn(max = 560.dp),
                action = data.visuals.actionLabel?.let { l -> { TextButton(onClick = { E.haptic(); data.performAction() }) { Text(l, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) } } },
                shape = RoundedCornerShape(16.dp),
                containerColor = if (err) Color(0xFF3A1F22) else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = Color.White,
            ) { Text(data.visuals.message, fontSize = 14.sp) }
        }
    }
}

/** A question you asked for (allow installs, forget this device, reconnect Spotify): Android's dialog. */
@Composable
fun AskDialog() {
    val a = E.ask
    val pri = a?.actions?.firstOrNull { it.pri }
    val other = a?.actions?.firstOrNull { !it.pri }
    // the app's own dialog (Android's can't open out of the button that asked)
    PopDialog(a != null, onDismiss = { E.ask = null }, title = a?.title ?: "", text = a?.msg ?: "",
        confirm = pri?.let { it.label to { E.ask = null; it.fn?.invoke(); Unit } }, dismiss = other?.let { it.label to { E.ask = null; it.fn?.invoke(); Unit } })
}

/** The phone is getting warm (or hot), as Android says: a warning over the whole screen, the player blurred behind
 *  it, offering Low performance mode. Back is "Not now". */
@Composable
fun HeatWarning() {
    val w = io.github.bozkow.vibevisualizer.engine.Perf.warn.takeIf { !S.low } ?: 0
    val vis = androidx.compose.runtime.remember { androidx.compose.animation.core.MutableTransitionState(false) }; vis.targetState = w > 0
    val last = androidx.compose.runtime.remember { object { var w = 1; var c: Double? = null } }
    if (w > 0 && last.w != w) { last.w = w }
    if (w > 0 && !vis.currentState) last.c = io.github.bozkow.vibevisualizer.engine.Perf.batteryC()
    if (w > 0) androidx.activity.compose.BackHandler { io.github.bozkow.vibevisualizer.engine.Perf.warnDone(false) }
    if (!vis.currentState && !vis.targetState) return
    val hot = last.w >= 2
    androidx.compose.animation.AnimatedVisibility(vis, enter = androidx.compose.animation.EnterTransition.None, exit = androidx.compose.animation.ExitTransition.None) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().animateEnterExit(enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)))
                .background(Color.Black.copy(alpha = .62f)).clickable(androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {})
            Column(Modifier.animateEnterExit(enter = popIn(androidx.compose.ui.graphics.TransformOrigin.Center, .6f), exit = popOut(androidx.compose.ui.graphics.TransformOrigin.Center, .8f))
                .statusBarsSpace().navBarsSpace().padding(32.dp).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val tint = if (hot) Color(0xFFFF5449) else Color(0xFFFFD27A)
                Box(Modifier.size(96.dp).clip(CircleShape).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.DeviceThermostat, null, Modifier.size(52.dp), tint = tint)
                }
                Text(if (hot) "Your phone is hot" else "Your phone is getting warm", Modifier.padding(top = 24.dp), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
                last.c?.let { Text("Battery at " + Math.round(it) + " °C", Modifier.padding(top = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = tint) }
                Text("Low performance mode keeps it cooler: the moving background and the live blurs rest, and the app draws fewer frames a second. You can turn it off again in Settings → Performance.",
                    Modifier.padding(top = 14.dp), fontSize = 15.sp, lineHeight = 21.sp, color = Color.White.copy(alpha = .75f), textAlign = TextAlign.Center)
                Button(onClick = { E.haptic(); io.github.bozkow.vibevisualizer.engine.Perf.warnDone(true) }, Modifier.padding(top = 28.dp).fillMaxWidth().height(54.dp)) { Text("Turn on Low performance mode", fontSize = 16.sp) }
                TextButton(onClick = { io.github.bozkow.vibevisualizer.engine.Perf.warnDone(false) }, Modifier.padding(top = 6.dp)) { Text("Not now", fontSize = 15.sp, color = Color.White.copy(alpha = .8f)) }
            }
        }
    }
}

/** The heat warning in the picture-in-picture window: taps there reach only the window's own buttons, which offer
 *  Low performance mode, play / pause and "Not now" while it shows. */
@Composable
fun PipHeat() {
    val w = io.github.bozkow.vibevisualizer.engine.Perf.warn.takeIf { !S.low } ?: 0
    androidx.compose.animation.AnimatedVisibility(w > 0, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        val tint = if (w >= 2) Color(0xFFFF5449) else Color(0xFFFFD27A)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)).padding(10.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Rounded.DeviceThermostat, null, Modifier.size(30.dp), tint = tint)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(if (w >= 2) "Your phone is hot" else "Your phone is getting warm", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 2)
                    Text("Low performance mode suggested · tap the window for its buttons", fontSize = 11.sp, color = Color.White.copy(alpha = .75f), maxLines = 2)
                }
            }
        }
    }
}
