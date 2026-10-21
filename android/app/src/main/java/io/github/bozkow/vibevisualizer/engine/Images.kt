package io.github.bozkow.vibevisualizer.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import io.github.bozkow.vibevisualizer.core.Net
import io.github.bozkow.vibevisualizer.core.Vibe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover art, artist photos, avatars: downloaded once (OkHttp's disk cache), decoded small enough for the screen. */
object Images {
    private val mem = object : LruCache<String, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount }

    fun cached(url: String): Bitmap? = mem.get(url)

    suspend fun load(url: String, max: Int = 1000): Bitmap? {
        if (url.isEmpty()) return null
        mem.get("$url@$max")?.let { return it }
        val bytes: ByteArray = if (url.startsWith("content:") || url.startsWith("file:") || url.startsWith("android.resource:"))
            withContext(Dispatchers.IO) { runCatching { Vibe.app.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() } }.getOrNull() } ?: return null
        else runCatching { Net.fetch(url).takeIf { it.ok }?.body }.getOrNull() ?: return null
        val bm = decode(bytes, max) ?: return null
        mem.put("$url@$max", bm)
        return bm
    }

    suspend fun decode(bytes: ByteArray, max: Int = 1000): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1; while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }.getOrNull()
    }
}
