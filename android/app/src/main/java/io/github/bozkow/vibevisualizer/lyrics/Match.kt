package io.github.bozkow.vibevisualizer.lyrics

import java.text.Normalizer
import java.util.Locale

/** Telling whether another catalogue's answer is the same song, as Spicy Lyrics Mobile by TheX24 does
 *  (github.com/spicylyrics/mobile, AGPL-3.0: SpotifyTrackMatcher.normalize, GeniusSongPages.otherVersion). */
object Match {
    /** A name with case, accents, "&" and punctuation evened out. */
    fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private val SEPARATOR = Regex("\\s+[-–—]\\s+")
    private val TRAILING_BRACKETS = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$")

    /** A title, also without a trailing " - Remastered 2011" or "(feat. X)" (Spicy Lyrics Mobile's LyricsFolderIndex). */
    fun titles(title: String): Set<String> = setOf(key(title), key(title.split(SEPARATOR, limit = 2).first()), key(title.replace(TRAILING_BRACKETS, "")))
        .filterTo(LinkedHashSet(), String::isNotBlank)

    /** [theirs] is [ours], or [ours] without its version note. */
    fun sameTitle(theirs: String, ours: String): Boolean = key(theirs).let { it.isNotEmpty() && it in titles(ours) }

    private val BRACKETED = Regex("[(\\[{].*?[)\\]}]")
    /** Version notes that keep the recording: "(Deluxe Version)", "(Taylor's Version)", "(2015 Remaster)". */
    private val SAME_RECORDING = Regex("""deluxe|expanded|edition|taylor|explicit|clean|remaster|anniversary|original|album|single""", RegexOption.IGNORE_CASE)
    /** What names another version of a song in its title. */
    private val VERSION = Regex("""\b(?:ver(?:sion)?\b\.?|remix|live|acoustic|instrumental|inst\.|sped up|slowed|tv size|demo)""", RegexOption.IGNORE_CASE)

    /** [theirs] names a version (in brackets or after a dash) that [ours] doesn't. */
    fun otherVersion(theirs: String, ours: String): Boolean {
        val extras = BRACKETED.findAll(theirs).map { it.value } + listOfNotNull(theirs.substringAfter(" - ", "").takeIf(String::isNotEmpty))
        return extras.filterNot(SAME_RECORDING::containsMatchIn)
            .any { extra -> VERSION.findAll(extra).any { it.value.lowercase() !in ours.lowercase() } }
    }
}
