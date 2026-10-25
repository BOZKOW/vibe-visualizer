package io.github.bozkow.vibevisualizer.widget

import android.content.Context
import io.github.bozkow.vibevisualizer.core.S
import io.github.bozkow.vibevisualizer.core.Vibe

/** How one home screen widget looks (each has its own, set on its settings screen), as Spicy Lyrics Mobile's widget
 *  settings. Where the lyrics are from always shows: the sources ask for it. */
data class WidgetLook(
    val around: Int = 1,            // lines above and below the one sung now, 0 to 3
    val rom: Boolean = true,        // romanization under the line
    val tr: Boolean = false,        // the translation under the line
    val song: String = "bottom",    // the cover, title and artist: top | bottom | hidden
    val progress: Boolean = true,   // the time and the bar
    val controls: String = "all",   // none | play (play / pause) | all (previous, play / pause, next)
    val bg: String = "cover",       // cover (blurred) | color (the cover's colour) | black | clear (see-through)
    val dim: Int = 45,              // how dark the cover background is (%)
    val size: String = "m",         // s | m | l
    val center: Boolean = false,    // lines centred
) {
    /** The line sung now, the lines around it, romanization and translations (sp). */
    val sizes get() = when (size) { "s" -> Triple(17f, 13f, 11f); "l" -> Triple(25f, 18f, 14f); else -> Triple(21f, 15f, 12f) }
}

object WidgetLooks {
    private val prefs by lazy { Vibe.app.getSharedPreferences("widgets", Context.MODE_PRIVATE) }

    /** A widget placed before it had settings of its own takes the old shared ones. */
    private fun base() = WidgetLook(size = S.str("aWgSize").ifEmpty { "m" }, song = if (S.b("aWgSong")) "bottom" else "hidden",
        controls = if (S.b("aWgCtl")) "all" else "none", bg = when (S.str("aWgBg")) { "black" -> "black"; "clear" -> "clear"; else -> "cover" })

    fun read(id: Int): WidgetLook {
        val d = base(); fun k(n: String) = "$id.$n"
        return WidgetLook(
            around = prefs.getInt(k("around"), d.around).coerceIn(0, 3), rom = prefs.getBoolean(k("rom"), d.rom), tr = prefs.getBoolean(k("tr"), d.tr),
            song = prefs.getString(k("song"), d.song) ?: d.song, progress = prefs.getBoolean(k("progress"), d.progress),
            controls = prefs.getString(k("controls"), d.controls) ?: d.controls, bg = prefs.getString(k("bg"), d.bg) ?: d.bg,
            dim = prefs.getInt(k("dim"), d.dim).coerceIn(0, 90), size = prefs.getString(k("size"), d.size) ?: d.size, center = prefs.getBoolean(k("center"), d.center))
    }

    fun save(id: Int, l: WidgetLook) {
        fun k(n: String) = "$id.$n"
        prefs.edit().putInt(k("around"), l.around).putBoolean(k("rom"), l.rom).putBoolean(k("tr"), l.tr).putString(k("song"), l.song)
            .putBoolean(k("progress"), l.progress).putString(k("controls"), l.controls).putString(k("bg"), l.bg).putInt(k("dim"), l.dim)
            .putString(k("size"), l.size).putBoolean(k("center"), l.center).apply()
    }

    fun forget(ids: IntArray) {
        val pre = ids.map { "$it." }
        val e = prefs.edit(); prefs.all.keys.filter { key -> pre.any(key::startsWith) }.forEach(e::remove); e.apply()
    }
}
