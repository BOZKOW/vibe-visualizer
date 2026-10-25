package io.github.bozkow.vibevisualizer.ui

import java.text.Normalizer

/** Settings search that doesn't need the exact words: any order, words half typed, a typo or two (a letter wrong,
 *  missing, extra or swapped), accents and dashes ignored, "lockscreen" for "lock screen". Each word you type has to
 *  match something in the setting, its section or its page; the closer the matches, the higher the setting ranks. */
object SearchMatch {
    private val ALIAS = mapOf(
        "pip" to "picture in picture", "bg" to "background", "colour" to "color", "colours" to "colors",
        "notif" to "notification", "fps" to "frames", "darkmode" to "dark", "ui" to "controls",
    )

    /** Lowercase words without accents or punctuation. */
    fun words(s: String): List<String> = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }

    /** What you typed as words to look for (short forms spelled out). */
    fun query(q: String): List<String> = words(q).flatMap { w -> ALIAS[w]?.let { words(it) } ?: listOf(w) }.distinct()

    /** How well one typed word matches one word of the setting (0: not at all). */
    internal fun word(q: String, w: String): Int {
        if (w == q) return 100
        if (w.startsWith(q)) return if (q.length >= 2) 90 else 60
        if (q.length >= 3 && w.contains(q)) return 70
        if (q.length < 4) return 0
        // a typo or two, in the whole word or in the part typed so far
        val allowed = if (q.length >= 7) 2 else 1
        val d = minOf(dist(q, w), dist(q, w.take(q.length)), dist(q, w.take(q.length + 1)))
        return if (d <= allowed) 64 - 12 * d else 0
    }

    /** How well [q] (from [query]) matches a setting: its own text, then its section and page (worth a little less).
     *  Null when a typed word matches nothing. */
    fun score(q: List<String>, text: String, context: String): Int? {
        if (q.isEmpty()) return null
        val own = words(text); val ctx = words(context)
        val ownPairs = own.zipWithNext { a, b -> a + b }; val ctxPairs = ctx.zipWithNext { a, b -> a + b }   // "lockscreen"
        var total = 0
        for (t in q) {
            val a = maxOf(own.maxOfOrNull { word(t, it) } ?: 0, ownPairs.maxOfOrNull { word(t, it) } ?: 0)
            val b = maxOf(ctx.maxOfOrNull { word(t, it) } ?: 0, ctxPairs.maxOfOrNull { word(t, it) } ?: 0) * 4 / 5
            val best = maxOf(a, b)
            if (best == 0) return null
            total += best
        }
        // the typed words in that order, right in the setting's name: first
        if (words(text).joinToString(" ").contains(q.joinToString(" "))) total += 40
        return total
    }

    /** Damerau-Levenshtein distance (a swap of two letters counts as one). */
    internal fun dist(a: String, b: String): Int {
        val n = a.length; val m = b.length
        if (n == 0) return m; if (m == 0) return n
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val c = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[n][m]
    }
}
