package io.github.bozkow.vibevisualizer.ui

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
