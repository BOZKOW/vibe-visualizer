package io.github.bozkow.vibevisualizer.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import io.github.bozkow.vibevisualizer.BuildConfig
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Server
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.core.wall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Updates: this app's new versions from the GitHub Releases page (installed through Android's installer, which asks
 *  you first), and the web app on your server (Settings → Help & about), as in the web app. */
object Updates {
    const val REPO = "BOZKOW/vibe-visualizer"
    private val prefs by lazy { Vibe.app.getSharedPreferences("updates", Context.MODE_PRIVATE) }

    class Note(val title: String, val date: String, val points: List<String>, val tag: String, val beta: Boolean)
    class AppRel(val version: String, val tag: String, val apk: String, val note: Note)

    var appStat by mutableStateOf("Not checked yet."); private set
    var latest by mutableStateOf<AppRel?>(null); private set
    var notes by mutableStateOf<List<Note>>(emptyList()); private set
    var downloading by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var serverStat by mutableStateOf("Not checked yet."); private set
    var serverCan by mutableStateOf(false); private set
    var serverNew by mutableStateOf(false); private set
    var serverNotes by mutableStateOf<List<Note>>(emptyList()); private set

    fun verCmp(a: String, b: String): Int {
        fun p(x: String) = x.split(Regex("[.-]")).map { it.toIntOrNull() ?: 0 }
        val A = p(a); val B = p(b)
        for (i in 0 until maxOf(A.size, B.size)) { val d = (A.getOrNull(i) ?: 0) - (B.getOrNull(i) ?: 0); if (d != 0) return d }
        return 0
    }
    private val mine get() = BuildConfig.VERSION_NAME.substringBefore("-dev").substringBefore("-debug")

    private fun points(body: String) = body.lines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }.map { it.drop(2).trim() }.take(20)

    /** The newest app release on GitHub (android-v…, not the old WebView app's). */
    fun checkApp(manual: Boolean) {
        if (manual) appStat = "Checking…"
        Vibe.scope.launch {
            try {
                val r = Net.fetch("https://api.github.com/repos/$REPO/releases?per_page=50", "github")
                if (!r.ok) throw Exception(if (r.code == 403 || r.code == 429) "GitHub is busy right now (too many requests from your connection). Try again in a few minutes" else "HTTP ${r.code}")
                val a = r.jsonArray() ?: JSONArray()
                // this app's releases; the old WebView app's (0.2-0.7 and its 1.0.0) are titled "old, out of support"
                val rels = (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.optBoolean("draft") && it.optString("tag_name").startsWith("android-v") }
                    .filter { !it.optString("name").contains("out of support", ignoreCase = true) }
                    .sortedByDescending { it.optString("published_at") }
                notes = rels.take(10).map { note(it) }
                val newest = rels.firstOrNull()
                if (newest == null) { appStat = "Version ${BuildConfig.VERSION_NAME} · no app releases on GitHub yet."; return@launch }
                val v = newest.optString("tag_name").removePrefix("android-v")
                val apk = newest.optJSONArray("assets")?.let { as_ -> (0 until as_.length()).map { as_.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") } ?: ""
                val rel = AppRel(v, newest.optString("tag_name"), apk, note(newest))
                val newer = verCmp(v, mine) > 0
                latest = if (newer && apk.isNotEmpty()) rel else null
                appStat = "Version ${BuildConfig.VERSION_NAME}" + if (newer) " · new version $v${if (rel.note.beta) " beta" else ""} is out" else " · the newest"
                if (newer && apk.isNotEmpty() && (manual || prefs.getString("skip", "") != rel.tag) && E.settingsPage != "help")
                    E.snack(E.Snack("Version $v is available", "ok", E.Act("Update") { install(rel) }, long = true, onDismiss = { prefs.edit().putString("skip", rel.tag).apply() }))
            } catch (e: Exception) { appStat = "Couldn’t check for updates: " + e.message; if (manual) E.toast("Couldn’t check for updates", "warn") }
        }
    }

    private fun note(o: JSONObject) = Note(o.optString("name").ifEmpty { o.optString("tag_name") }, o.optString("published_at"), points(o.optString("body")), o.optString("tag_name"), o.optBoolean("prerelease"))

    fun canInstall(): Boolean = Vibe.app.packageManager.canRequestPackageInstalls()

    /** Downloads the new APK and hands it to Android's installer (it installs over this version, keeping your data). */
    fun install(rel: AppRel) {
        if (downloading) return
        if (!canInstall()) {
            E.ask = E.Ask("Allow app updates", "Android needs your OK once: turn on “Allow from this source” for Vibe Visualizer, then press Update again.",
                listOf(E.Act("Not now"), E.Act("Open settings", pri = true) {
                    E.activity?.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + Vibe.app.packageName)))
                }))
            return
        }
        downloading = true; progress = 0f
        prefs.edit().putString("whatsnew", rel.tag).apply()
        Vibe.scope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val dir = File(Vibe.app.cacheDir, "updates").apply { mkdirs() }; dir.listFiles()?.forEach { it.delete() }
                    val out = File(dir, "vibe-visualizer-${rel.version}.apk")
                    Net.http.newCall(Request.Builder().url(rel.apk).build()).execute().use { r ->
                        if (!r.isSuccessful) throw Exception("HTTP ${r.code}")
                        val body = r.body; val total = body.contentLength().toFloat()
                        body.byteStream().use { inp -> out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024); var n: Int; var got = 0L
                            while (inp.read(buf).also { n = it } > 0) { o.write(buf, 0, n); got += n; if (total > 0) progress = got / total }
                        } }
                    }
                    out
                }
                val uri = FileProvider.getUriForFile(Vibe.app, Vibe.app.packageName + ".files", f)
                (E.activity ?: Vibe.app).startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { E.toast("The update didn’t download: " + e.message) }
            finally { downloading = false }
        }
    }

    /** Right after an update: what changed. */
    fun whatsNew() {
        val last = prefs.getString("ran", "") ?: ""
        prefs.edit().putString("ran", BuildConfig.VERSION_NAME).apply()
        if (last.isEmpty() || last == BuildConfig.VERSION_NAME) return
        Vibe.scope.launch {
            kotlinx.coroutines.delay(1500)
            runCatching {
                val r = Net.fetch("https://api.github.com/repos/$REPO/releases/tags/android-v$mine")
                r.json()?.let(::note) ?: return@launch
                E.snack(E.Snack("Updated to ${BuildConfig.VERSION_NAME}", "ok", E.Act("What’s new") { E.openSettings("help") }, long = true))
            }
        }
    }

    /* ---- the web app on your server ---- */
    fun checkServer(manual: Boolean) {
        val base = Server.base ?: run { serverStat = "Set your server first."; return }
        if (manual) serverStat = "Checking…"
        Vibe.scope.launch {
            try {
                val r = Net.fetch("$base/api/version", noCache = true); val v = r.json() ?: JSONObject()
                if (!r.ok) throw Exception(if (r.code == 404) "your server’s helper is too old for updates (see the README)" else v.optString("error").ifEmpty { "HTTP ${r.code}" })
                val cur = v.optJSONObject("installed"); val lat = v.optJSONObject("latest")
                if (lat == null || lat.has("error")) throw Exception(lat?.optString("error")?.ifEmpty { null } ?: "GitHub didn’t answer")
                val newer = cur == null || cur.optString("sha") != lat.optString("sha")
                fun fmtV(o: JSONObject) = "“" + o.optString("message") + "”"
                serverNew = newer; serverCan = v.optBoolean("can_update")
                serverStat = (if (cur != null) "Installed: " + fmtV(cur) + ". " else "") + if (newer) "New version: " + fmtV(lat) + "." else "Your server has the newest web app."
                val rec = v.optJSONArray("recent")?.takeIf { it.length() > 0 } ?: v.optJSONArray("notes") ?: JSONArray()
                serverNotes = (0 until rec.length()).map { rec.getJSONObject(it) }.map { o ->
                    Note(o.optString("title"), o.optString("date"), o.optJSONArray("points")?.let { p -> (0 until p.length()).map { p.optString(it) } } ?: emptyList(), o.optString("sha"), false) }
            } catch (e: Exception) { serverStat = "Couldn’t check your server: " + e.message }
        }
    }

    fun updateServer() {
        val base = Server.base ?: return
        serverStat = "Downloading the new version onto your server…"
        Vibe.scope.launch {
            try {
                val r = Net.fetch("$base/api/update", method = "POST"); val v = r.json() ?: JSONObject()
                if (!r.ok) throw Exception(v.optString("error").ifEmpty { "HTTP ${r.code}" })
                serverStat = if (v.optBoolean("updated")) "Your server’s web app was updated." else "Already up to date."
                serverNew = false
            } catch (e: Exception) { serverStat = "Update failed: " + e.message }
        }
    }

    /** Looks once in the background, a few hours apart at most. */
    fun auto() {
        if (wall() - prefs.getLong("at", 0) < 6 * 3600_000L) return
        prefs.edit().putLong("at", wall()).apply()
        checkApp(false)
    }
}

/** Settings kept on your server: a new phone takes over what the old app (or the web app) kept for it, and your
 *  Spotify Client ID comes along from your other devices. This app keeps its own settings, so it only reads them. */
object Sync {
    fun importFromServer(after: () -> Unit = {}) {
        val base = Server.base ?: return
        Vibe.scope.launch {
            runCatching {
                val r = Net.fetch("$base/api/prefs", noCache = true)
                val p = r.json() ?: return@runCatching
                if (r.ok) {
                    p.optJSONObject("S")?.let { Vibe.settings.fromJson(it, setOf("volume", "capDev")) }
                    p.optJSONObject("C")?.let { c ->
                        c.optString("spotId").takeIf { it.isNotEmpty() && Vibe.cfg.spotId.isEmpty() }?.let { Vibe.cfg.saveSpotId(it) }
                        c.optString("canvasUrl").takeIf { it.isNotEmpty() }?.let { Vibe.cfg.saveCanvasUrl(it) }
                    }
                    p.optJSONObject("SP")?.let { sp -> val rf = sp.optString("refresh"); if (rf.isNotEmpty() && Vibe.cfg.spAccess.isEmpty()) Vibe.cfg.adoptSpotify(rf, sp.optString("scope")) }
                }
                p.optString("spotId").takeIf { it.isNotEmpty() && Vibe.cfg.spotId.isEmpty() }?.let { Vibe.cfg.saveSpotId(it) }   // a new device: your Spotify Client ID comes along
            }
            after()
        }
    }

    fun backupText(): String = JSONObject().put("vibeVisualizerSettings", 1).put("S", Vibe.settings.toJson()).put("C", JSONObject().put("canvasUrl", Vibe.cfg.canvasUrl)).toString()

    fun restore(t: String): Int {
        val o = runCatching { JSONObject(t) }.getOrNull()
        if (o == null || !o.has("vibeVisualizerSettings") || !o.has("S")) return -1
        val n = Vibe.settings.fromJson(o.getJSONObject("S"), setOf("volume"))
        o.optJSONObject("C")?.optString("canvasUrl")?.takeIf { it.isNotEmpty() }?.let { Vibe.cfg.saveCanvasUrl(it) }
        return n
    }

    fun forget(done: () -> Unit) {
        Vibe.scope.launch {
            Server.base?.let { b -> runCatching { Net.fetch("$b/api/prefs", method = "DELETE") } }
            io.github.bozkow.vibevisualizer.lyrics.LyricsCache.clear()
            io.github.bozkow.vibevisualizer.lyrics.MyLyrics.clear()
            Vibe.cfg.forgetAll(); Vibe.settings.resetAll(); S.set("volume", 100)
            done()
        }
    }
}
