package io.github.bozkow.vibevisualizer.engine

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bozkow.vibevisualizer.BuildConfig
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.wall
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The ping meter: your server pinged live, outside services timed on real requests. */
object Ping {
    var health by mutableStateOf<JSONObject?>(null); private set
    var label by mutableStateOf("– ms"); private set
    var level by mutableStateOf(""); private set            // good | ok | bad
    var serverDown by mutableStateOf(false); private set
    private var fails = 0
    private var job: Job? = null
    private var kickAt = 0L
    var open = false                                        // the ping meter sheet is showing: ping every 3 s

    fun lvl(ms: Long?) = when { ms == null -> ""; ms < 150 -> "good"; ms < 400 -> "ok"; else -> "bad" }

    private suspend fun ping() {
        val base = Server.base
        if (base == null) { label = "no server"; level = ""; serverDown = false; return }
        val app = runCatching { Net.fetch("$base/manifest.webmanifest", "app", "HEAD", noCache = true) }.getOrNull()
        health = runCatching { Net.fetch("$base/api/health", "helper", noCache = true).takeIf { it.ok }?.json() }.getOrNull()
        val up = (app != null && app.code < 400) || health != null   // the server answered one of the two
        fails = if (up) 0 else fails + 1
        if (up) { if (app != null && app.code < 400) { label = "${app.ms} ms"; level = lvl(app.ms) } else { label = "–"; level = "ok" }; serverDown = false }
        else if (fails >= 2 || !Net.online.value) { label = "offline"; level = "bad"; serverDown = true }   // one lost request isn't "offline"
    }

    fun loop() {
        job?.cancel()
        job = Vibe.scope.launch {
            while (true) {
                if (E.visible) ping()
                delay(if (fails > 0) 2000 else if (open) 3000 else 10000)   // 2 s after a failure, so problems show and clear quickly
            }
        }
    }

    fun kick() { if (wall() - kickAt > 2000) { kickAt = wall(); loop() } }   // something just failed: check now
}

/** Errors the app runs into are saved on your server (Settings → Help & about → Error log), never sent anywhere else. */
object Reports {
    private var seen = 0L

    fun report(kind: String, msg: String, where: String = "", stack: String = "") {
        val base = Server.base ?: return
        val body = JSONObject().put("kind", kind).put("msg", msg).put("where", where).put("stack", stack.take(1500))
            .put("version", "android " + BuildConfig.VERSION_NAME).put("ua", "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}")
            .put("view", S.view).put("src", E.src)
        Vibe.scope.launch { runCatching { Net.fetch("$base/api/report", null, "POST", body = body.toString().toByteArray(), contentType = "application/json") } }
    }

    fun appError(msg: String, where: String, stack: String) {
        report("error", msg, where, stack)
        if (wall() - seen < 8000) return   // at most one card every few seconds
        seen = wall()
        E.snack(E.Snack("Something went wrong: " + msg.take(120), "err"))
    }

    suspend fun load(): List<Pair<String, String>> {
        val base = Server.base ?: throw IllegalStateException("No server set")
        val j = Net.fetch("$base/api/report", noCache = true).json() ?: throw IllegalStateException("not JSON")
        val a = j.optJSONArray("errors") ?: return emptyList()
        return (0 until minOf(20, a.length())).map { i -> val e = a.getJSONObject(i)
            e.optString("msg").ifEmpty { "(no message)" } to (java.text.DateFormat.getDateTimeInstance().format(e.optLong("at") * 1000) + (e.optString("where").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")) }
    }

    suspend fun clear() { Server.base?.let { runCatching { Net.fetch("$it/api/report", null, "DELETE") } } }
}
