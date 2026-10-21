package io.github.bozkow.vibevisualizer.engine

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import io.github.bozkow.vibevisualizer.core.Vibe
import io.github.bozkow.vibevisualizer.lyrics.MyLyrics
import io.github.bozkow.vibevisualizer.lyrics.fromText
import io.github.bozkow.vibevisualizer.lyrics.nameKeys
import io.github.bozkow.vibevisualizer.lyrics.songKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Files you pick or share with the app: songs, lyrics files, a cover image, a Canvas video. */
object Files {
    class Sorted(val audio: List<Uri>, val lyrics: List<Uri>, val images: List<Uri>, val videos: List<Uri>)
    class Tags(val title: String?, val artist: String?, val album: String?, val durationMs: Long, val picture: ByteArray?)

    private val LYR = Regex("\\.(lrc|ttml|xml|txt)$", RegexOption.IGNORE_CASE)
    private val AUD = Regex("\\.(mp3|wav|ogg|m4a|flac|aac|opus|weba|wma|alac|aiff?)$", RegexOption.IGNORE_CASE)
    private val VID = Regex("\\.(mp4|webm|mov|m4v)$", RegexOption.IGNORE_CASE)

    fun name(u: Uri): String = runCatching {
        Vibe.app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/') ?: "audio"

    private fun type(u: Uri) = runCatching { Vibe.app.contentResolver.getType(u) }.getOrNull() ?: ""

    suspend fun sort(uris: List<Uri>): Sorted = withContext(Dispatchers.IO) {
        val a = ArrayList<Uri>(); val l = ArrayList<Uri>(); val i = ArrayList<Uri>(); val v = ArrayList<Uri>()
        for (u in uris) {
            val n = name(u); val t = type(u)
            when {
                LYR.containsMatchIn(n) || t.contains("ttml") -> l.add(u)
                t.startsWith("image/") -> i.add(u)
                t.startsWith("video/") || VID.containsMatchIn(n) -> v.add(u)
                t.startsWith("audio/") || AUD.containsMatchIn(n) || t == "application/ogg" -> a.add(u)
            }
        }
        Sorted(a, l, i, v)
    }

    /** A file name without the clutter: extension, track numbers, "(Official Video)", underscores. */
    fun clean(n: String): String = n.replace(Regex("\\.[^.]+$"), "").replace(Regex("_+"), " ")
        .replace(Regex("\\[[^]]*]|\\((?:official|lyrics?|audio|video|hd|hq|remaster|visuali[sz]er|music|explicit)[^)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^\\s*\\d{1,3}[\\s.\\-–]+"), "").replace(Regex("\\s+"), " ").trim()

    suspend fun tags(u: Uri): Tags = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(Vibe.app, u)
            Tags(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifEmpty { null },
                (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim()?.ifEmpty { null },
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.ifEmpty { null },
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L, r.embeddedPicture)
        } catch (e: Exception) { Tags(null, null, null, 0, null) }
        finally { runCatching { r.release() } }
    }

    suspend fun readText(u: Uri): String? = withContext(Dispatchers.IO) { runCatching { Vibe.app.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }

    /** Your lyrics files: kept on this phone and used whenever their song plays. One file while a song plays (or picked
     *  with "Use a file for this song") is kept for that song too, and shown right away. */
    suspend fun addLyrics(uris: List<Uri>, forSong: Boolean) {
        var ok = 0; var bad = 0
        val song = if (forSong) Lyr.curSong() else null
        for (u in uris) {
            val n = name(u); val text = readText(u)
            val r = text?.let { runCatching { fromText(it) }.getOrNull() }
            if (text == null || r == null || r.lines.isEmpty()) { bad++; continue }
            MyLyrics.putKeys((nameKeys(n) + (song?.let { songKeys(it) } ?: emptyList())).toSet(), n, text)
            ok++
            if (forSong && uris.size == 1) Lyr.applyFile(r, n)
        }
        if (forSong && uris.size == 1) {
            if (ok == 1) E.toast(if (song != null) "Lyrics saved for “${song.title}”" else "Lyrics file added", "ok")
            else E.toast("No lyrics found in that file", "warn")
        } else {
            E.toast("$ok" + (if (ok == 1) " lyrics file" else " lyrics files") + " added" + if (bad > 0) " · $bad couldn’t be read" else "", "ok")
            Lyr.reloadCurrent()
        }
        Lyr.refreshCounts()
    }
}

/** The system file and photo pickers (wired up by MainActivity). */
object FilePicker {
    var audio: (() -> Unit)? = null
    var lyrics: ((forSong: Boolean) -> Unit)? = null
    var image: (() -> Unit)? = null
    var video: (() -> Unit)? = null
    fun pickAudio() = audio?.invoke()
}
