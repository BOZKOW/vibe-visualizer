package io.github.bozkow.vibevisualizer.lyrics

private val NON_LATIN = Regex("[^\\u0000-\\u024F\\u1E00-\\u1EFF\\u2000-\\u206F\\s\\d]")
fun nonLatin(t: String) = NON_LATIN.containsMatchIn(t)

val JA = Regex("[\\u3040-\\u30FF\\u31F0-\\u31FF\\uFF66-\\uFF9F]")
val HAN = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]")
val KO = Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]")
val CYR = Regex("[\\u0400-\\u052F\\u2DE0-\\u2DFF\\uA640-\\uA69F]")
val GR = Regex("[\\u0370-\\u03FF\\u1F00-\\u1FFF]")

private val RU_FAMILY = setOf("ru", "uk", "bg", "sr", "mk", "be", "kk")
private val AR_FAMILY = setOf("ar", "fa", "ur")
private val NON_LATIN_LANGS = Regex("^(ja|ko|zh|ru|uk|bg|sr|mk|be|kk|el|ar|fa|ur|he|th|hi)$")

/** Best guess of one line's language, for the "don't translate" list. */
fun lineLang(t: String, song: String?): String {
    if (Regex("[\\uAC00-\\uD7AF\\u1100-\\u11FF\\u3130-\\u318F]").containsMatchIn(t)) return "ko"
    if (Regex("[\\u3040-\\u30FF]").containsMatchIn(t)) return "ja"
    if (Regex("[\\u4E00-\\u9FFF]").containsMatchIn(t)) return if (song == "ja") "ja" else "zh"
    if (Regex("[\\u0400-\\u04FF]").containsMatchIn(t)) return if (song in RU_FAMILY) song!! else "ru"
    if (Regex("[\\u0370-\\u03FF]").containsMatchIn(t)) return "el"
    if (Regex("[\\u0600-\\u06FF]").containsMatchIn(t)) return if (song in AR_FAMILY) song!! else "ar"
    if (Regex("[\\u0590-\\u05FF]").containsMatchIn(t)) return "he"
    if (Regex("[\\u0E00-\\u0E7F]").containsMatchIn(t)) return "th"
    if (Regex("[\\u0900-\\u097F]").containsMatchIn(t)) return "hi"
    val s = (song ?: "").split('-')[0]   // Latin script: the song's own language, or English inside a non-Latin song
    return if (s.isEmpty() || NON_LATIN_LANGS.matches(s)) "en" else s
}

// offline guess of the language of Latin-script lyrics, from common words (nothing is sent anywhere)
private val STOPW = mapOf(
    "en" to "the and you to i it me my is in that of we your on for be all with this",
    "cs" to "je se na že to ne jsem si mi já ty co jak tak ale když",
    "sk" to "je sa na že to nie som si mi ja ty čo ako tak ale keď",
    "de" to "und ich die der das nicht du ist mich mir ein zu es sie wir",
    "es" to "que el la de y no me te en un una lo es mi tu por",
    "fr" to "je tu le la les de et que pas est un une moi toi dans",
    "pt" to "que eu o a de não me você é um uma em do da meu",
    "it" to "che e di non la il un mi ti io tu per è sono",
    "pl" to "i nie się w na to że jest mi ja ty co jak tak",
    "nl" to "de het en ik je een niet van is dat mijn jij",
    "sv" to "och jag du det är att inte en på mig min",
    "tr" to "bir ve bu ben sen ne de da için gibi çok",
    "id" to "aku kau dan yang di ini itu tak ku cinta",
    "ro" to "și eu tu nu de la că în mă te ce",
)
private val WORDSETS = STOPW.mapValues { it.value.split(' ').toSet() }
private val WORD_SPLIT = Regex("[^\\p{L}']+")

fun guessLatin(texts: List<String>): String {
    val sc = HashMap<String, Int>()
    for (t in texts) for (w in t.lowercase().split(WORD_SPLIT)) for ((k, set) in WORDSETS) if (w in set) sc[k] = (sc[k] ?: 0) + 1
    var best = "en"; var b = 0
    for ((k, v) in sc) if (v > b) { b = v; best = k }
    return best
}
