package io.github.bozkow.vibevisualizer.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.util.Locale

/* ================= settings (one store) =================
   The same settings, names and defaults as the web app (its DEF), so a settings backup copied from the web app can
   be pasted here and the other way round, plus a few that only make sense on a phone (the a… ones). */

val LANGS = listOf(
    "en" to "English", "cs" to "Czech", "sk" to "Slovak", "de" to "German", "es" to "Spanish", "fr" to "French",
    "it" to "Italian", "pt" to "Portuguese", "pl" to "Polish", "nl" to "Dutch", "sv" to "Swedish", "da" to "Danish",
    "no" to "Norwegian", "fi" to "Finnish", "hu" to "Hungarian", "ro" to "Romanian", "el" to "Greek", "tr" to "Turkish",
    "uk" to "Ukrainian", "ru" to "Russian", "ar" to "Arabic", "he" to "Hebrew", "hi" to "Hindi", "th" to "Thai",
    "vi" to "Vietnamese", "id" to "Indonesian", "tl" to "Filipino", "ja" to "Japanese", "ko" to "Korean",
    "zh-CN" to "Chinese (Simplified)", "zh-TW" to "Chinese (Traditional)",
)

private fun navLang(): String {
    val l = Locale.getDefault().toLanguageTag().lowercase()
    val m = LANGS.find { it.first.lowercase() == l } ?: LANGS.find { it.first.split('-')[0] == l.split('-')[0] }
    return m?.first ?: "en"
}

object Defaults {
    val S: Map<String, Any> = linkedMapOf(
        "bridge" to true, "xFocus" to false, "bgStyle" to "dynamic", "lfont" to "vv", "lsimple" to false, "lminimal" to false,
        "lhover" to true, "lduet" to true, "lcredLast" to false, "lsmooth" to true, "learly" to false, "learlyMs" to 250,
        "lseekFade" to true, "lnotice" to true, "volshow" to true, "fpscap" to "60", "credPos" to "corner", "cvNoArt" to false, "cvBlur" to false, "cvBlurAmt" to 50, "cvShrink" to true, "cvSpicy" to true, "cvBehind" to "dark",
        "capDev" to "", "romMode" to "replace", "tr" to false, "trTo" to navLang(), "trSkip" to "", "theme" to "cover",
        "c1" to "#ff5a5f", "c2" to "#4f8cff", "auto" to true, "boost" to 100, "resp" to 25, "blur" to 0, "bars" to 72,
        "gap" to 28, "glow" to false, "round" to true, "scale" to "1", "fps" to false, "bgblur" to 60, "bri" to 70,
        "sat" to 170, "bgspeed" to 100, "online" to true, "volume" to 100, "view" to "both",
        "lprov" to "spicy:1,spotify:1,lrclib:1,netease:1", "loff" to 0, "lsize" to 100, "lblur" to true,
        "canvasOn" to true, "low" to false, "perfAsk" to true, "anim" to "system", "lDesync" to "ask", "design" to "glass",
        "uiHide" to "idle", "xRefCred" to true, "xScrub" to true, "xSkeleton" to true, "xKeep" to true, "xBgPop" to false,
        "xRefSimple" to false, "ljoin" to false, "lbgBr" to false,
        // Android only
        "aCap" to "phone",      // what the bars hear: phone (everything the phone plays), mic, off
        "aKeepOn" to true,      // keep the screen on while music plays
        "aPip" to true,         // picture-in-picture when you leave the app while music plays
        "aHaptics" to true,     // a light tick on taps and toggles
        "aSession" to true,     // the Spotify app on this phone for timing and controls (needs notification access)
        "aLock" to false,       // show over the lock screen
        "aAmbient" to false,    // ambient mode: untouched while music plays, the lyrics and the song alone on black
        "aAmbientSec" to "30",  // how long untouched before it dims
        "aPipSize" to 100,      // the lyrics' size in picture-in-picture (%)
        "aWgSize" to "m",       // the home screen widget: lyrics size (s, m, l)
        "aWgSong" to true,      //   the song under the lyrics
        "aWgCtl" to true,       //   play / pause, previous, next
        "aWgBg" to "cover",     //   background: cover (blurred), black, clear
        "aHideStatus" to false, // hide the status bar (swipe down to see it)
        "aHideNav" to false,    // hide the navigation bar (swipe up to see it)
        "aTrSvc" to "google",   // the translation service: google, deepl, libre, openai (any OpenAI-compatible), gemini
        "aDesign" to "android", // the design: android (Material You, the cover's colors) or glass (frosted glass over the cover)
    )
}

class Settings(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    // one state per setting: a screen that reads a setting is only redrawn when that one changes
    private val v = HashMap<String, MutableState<Any>>().also { m -> Defaults.S.forEach { (k, d) -> m[k] = mutableStateOf(d) } }
    private val main = Handler(Looper.getMainLooper())
    private val saveRun = Runnable { sp.edit().putString("S", toJson().toString()).apply() }
    /** No settings were saved yet when the app started: a new install (only then may your server's settings come in). */
    val fresh = !sp.contains("S")
    var onChange: ((String) -> Unit)? = null

    init {
        sp.getString("S", null)?.let { runCatching { fromJson(JSONObject(it), emptySet(), notify = false) } }
        // once, for settings saved by 0.0.7 or older: 60 fps unless a frame rate was chosen (120 Hz heats the phone up),
        // and the bars in every view
        if (sp.getInt("mig", 0) < 1) {
            if (sp.contains("S")) { if (s("fpscap") == "0") v.getValue("fpscap").value = "60"; v.getValue("view").value = "both" }
            sp.edit().putString("S", toJson().toString()).putInt("mig", 1).apply()
        }
        // once (0.1.4): One UI is gone; whoever chose it gets Glass
        if (sp.getInt("mig", 0) < 2) {
            if (s("aDesign") == "oneui") v.getValue("aDesign").value = "glass"
            sp.edit().putString("S", toJson().toString()).putInt("mig", 2).apply()
        }
    }

    operator fun get(k: String): Any = v[k]?.value ?: Defaults.S.getValue(k)
    fun b(k: String): Boolean = get(k) as Boolean
    fun i(k: String): Int = (get(k) as Number).toInt()
    fun s(k: String): String = get(k) as String
    fun changed(k: String) = get(k) != Defaults.S[k]

    fun set(k: String, value: Any) {
        val d = Defaults.S[k] ?: return
        val x: Any = when (d) { is Int -> (value as Number).toInt(); else -> value }
        val st = v[k] ?: return
        if (st.value == x) return
        st.value = x
        persist()
        onChange?.invoke(k)
    }

    fun reset(k: String) = set(k, Defaults.S.getValue(k))
    fun resetAll() { Defaults.S.keys.forEach { k -> if (k != "volume") reset(k) } }

    private fun persist() { main.removeCallbacks(saveRun); main.postDelayed(saveRun, 300) }
    /** Writes the settings now and waits for it (leaving the app, before an update replaces it): nothing changed in the
     *  last moments is lost when the process ends. */
    fun flush() { main.removeCallbacks(saveRun); sp.edit().putString("S", toJson().toString()).commit() }

    fun toJson(): JSONObject = JSONObject().also { o -> Defaults.S.keys.forEach { k -> o.put(k, get(k)) } }

    /** Takes every known setting of the right type (as the web app does); returns how many. */
    fun fromJson(o: JSONObject, skip: Set<String>, notify: Boolean = true): Int {
        var n = 0
        for ((k, d) in Defaults.S) {
            if (k in skip || !o.has(k)) continue
            val x = o.opt(k)
            val ok: Any? = when (d) {
                is Boolean -> x as? Boolean
                is Int -> (x as? Number)?.toInt()
                is String -> x as? String
                else -> null
            }
            if (ok != null) { if (notify) set(k, ok) else v[k]?.value = ok; n++ }
        }
        // the old "Use system font" checkbox
        if (o.optBoolean("sysFont", false) && !o.has("lfont")) { if (notify) set("lfont", "system") else v["lfont"]?.value = "system" }
        return n
    }
}

/** Things that aren't settings: your server, the Spotify Client ID, the Canvas endpoint, the Spotify login. */
class Config(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val sec = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    // the translation services' addresses, keys and models: kept on this phone, out of backups (and not wiped with the
    // Spotify login, which clears "secrets")
    private val tk = ctx.getSharedPreferences("trkeys", Context.MODE_PRIVATE)
    var trVer by mutableStateOf(0); private set
    fun trKey(name: String): String = tk.getString(name, "") ?: ""
    fun saveTrKey(name: String, v: String) { tk.edit().putString(name, v.trim()).apply(); trVer++ }

    var server by mutableStateOf(sp.getString("server", "") ?: "")
        private set
    var spotId by mutableStateOf(sp.getString("spotId", "") ?: "")
        private set
    var canvasUrl by mutableStateOf(sp.getString("canvasUrl", null) ?: "/api/canvas?id={id}")
        private set
    var setupDone by mutableStateOf(sp.getBoolean("setup", false))
        private set

    // the Spotify login (PKCE): access token, refresh token, when it runs out, what it may do
    var spAccess by mutableStateOf(sec.getString("access", "") ?: "")
        private set
    var spRefresh: String = sec.getString("refresh", "") ?: ""
        private set
    var spExp: Long = sec.getLong("exp", 0)
        private set
    var spScope: String = sec.getString("scope", "") ?: ""
        private set

    fun saveServer(u: String) { server = normServer(u); sp.edit().putString("server", server).apply() }
    fun saveSpotId(id: String) { spotId = id.trim(); sp.edit().putString("spotId", spotId).apply() }
    fun saveCanvasUrl(u: String) { canvasUrl = u; sp.edit().putString("canvasUrl", u).apply() }
    fun saveSetup(done: Boolean) { setupDone = done; sp.edit().putBoolean("setup", done).apply() }

    fun setSpotify(access: String, refresh: String?, expiresIn: Long, scope: String?) {
        spAccess = access
        if (!refresh.isNullOrEmpty()) spRefresh = refresh
        spExp = System.currentTimeMillis() + expiresIn * 1000 - 30000
        if (!scope.isNullOrEmpty()) spScope = scope
        sec.edit().putString("access", spAccess).putString("refresh", spRefresh).putLong("exp", spExp).putString("scope", spScope).apply()
    }

    /** A login kept on your server by the old app or the web app on this phone: only its refresh token is needed. */
    fun adoptSpotify(refresh: String, scope: String) {
        spRefresh = refresh; spScope = scope; spExp = 0; spAccess = "pending"
        sec.edit().putString("access", spAccess).putString("refresh", spRefresh).putLong("exp", 0).putString("scope", spScope).apply()
    }

    fun clearSpotify() {
        spAccess = ""; spRefresh = ""; spExp = 0; spScope = ""
        sec.edit().clear().apply()
    }

    fun forgetAll() {
        sp.edit().clear().apply(); sec.edit().clear().apply(); tk.edit().clear().apply()
        server = ""; spotId = ""; canvasUrl = "/api/canvas?id={id}"; setupDone = false; clearSpotify()
    }

    companion object {
        fun normServer(u: String): String {
            var s = u.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
            // only the address: a pasted link to a page on the server (…/index.html, …/#settings) still means the server
            return Regex("^(https?://[^/?#\\s]+)", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.lowercase() ?: s.trimEnd('/')
        }
    }
}
