package com.hdrstacker.studio

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads grid/preview thumbnails through the MediaStore thumbnail provider,
 * which also handles RAW files (via their embedded previews) that a plain
 * bitmap decode would choke on. Backed by an in-memory LRU cache so scrolling
 * back through the grid doesn't re-decode.
 */
object Thumbnails {
    private const val CACHE_BYTES = 64 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val key = "$uri#$sizePx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    uri,
                    Size(sizePx, sizePx),
                    CancellationSignal(),
                )
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }
}
