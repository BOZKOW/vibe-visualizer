// Ported from Spicy Lyrics Mobile by TheX24 (github.com/spicylyrics/mobile, AGPL-3.0), unchanged but for the package.
package io.github.bozkow.vibevisualizer.lyrics

/**
 * Turns what a player reports into names a lyrics search can use. Players of local files often
 * report the file name as the title ("03 - Artist - Song (Official Video).mp3") with no artist
 * or "<unknown>". Video players report the video's title and channel; those lose the video's
 * extras. Other tagged tracks pass through unchanged.
 */
object TrackNameCleaner {
    data class Names(val title: String, val artist: String)

    private val extension = Regex(
        "\\.(mp3|flac|m4a|aac|ogg|oga|opus|wav|wma|aiff?|alac|ape|wv|mka|webm|mp4|m4v|mkv|3gp)$",
        RegexOption.IGNORE_CASE,
    )
    // "03 - ", "1-03. ", "07) ", "01 " (a bare number only when zero-padded, so "7 rings" stays).
    private val trackNumber = Regex("^(?:\\d{1,3}(?:-\\d{1,3})?\\s*[-.)_]\\s*|0\\d\\s+)(?=\\S)")
    // Download-site leftovers: "(Official Music Video)", "[Lyrics]", "(HD)". Kept narrow so real
    // title brackets ("(Remix)", "(feat. X)", "(Taylor's Version)") survive.
    private val junk = Regex(
        "\\s*[(\\[](?:official\\s+)?(?:music\\s+|lyric\\s+|lyrics\\s+)?(?:video|audio|visuali[sz]er|lyrics?|mv|m/v|hd|hq|4k)[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val artistSeparator = Regex("\\s+[-–—]\\s+")
    private val unknownArtists = setOf("", "<unknown>", "unknown", "unknown artist")
    // YouTube channel names that video players report as the artist: "RickAstleyVEVO", "Artist - Topic".
    private val channelSuffix = Regex("(?:VEVO|\\s+-\\s+Topic)$")

    fun clean(title: String, artist: String): Names {
        val rawTitle = title.trim()
        val artistKnown = artist.trim().lowercase() !in unknownArtists
        val fileName = extension.containsMatchIn(rawTitle)
        if (artistKnown && !fileName) return video(rawTitle, artist.trim())

        var name = rawTitle.replace(extension, "")
        // "Artist_-_Song" style names use underscores for every space.
        if (fileName && ' ' !in name) name = name.replace('_', ' ')
        name = name.replace(trackNumber, "").replace(junk, "").trim()

        if (artistKnown) return Names(name.ifEmpty { rawTitle }, artist.trim())
        val parts = name.split(artistSeparator, limit = 2)
        return if (parts.size == 2 && parts.all(String::isNotBlank)) {
            Names(title = parts[1].trim(), artist = parts[0].trim())
        } else {
            Names(title = name.ifEmpty { rawTitle }, artist = "")
        }
    }

    /**
     * A tagged track, which is often a video on YouTube Music: "Artist - Song (Official Video)" by
     * the channel "ArtistVEVO". The song's own names come out; anything else passes through.
     */
    private fun video(title: String, artist: String): Names {
        val channel = artist.replace(channelSuffix, "").trim().ifEmpty { artist }
        val name = title.replace(junk, "").trim().ifEmpty { title }
        val parts = name.split(artistSeparator, limit = 2)
        // Split only when the part before the dash is the channel itself ("RickAstley" vs "Rick Astley").
        if (parts.size == 2 && parts.all(String::isNotBlank) && squashed(parts[0]) == squashed(channel)) {
            return Names(title = parts[1].trim(), artist = parts[0].trim())
        }
        return Names(name, channel)
    }

    private fun squashed(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
}
