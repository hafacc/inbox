package cc.hafa.inbox.data

import android.content.Context
import cc.hafa.inbox.core.contacts.sizedPhotoUrl
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import java.util.concurrent.ConcurrentHashMap

/** The image loader's caches of contact photos, keyed by sized URL. */
object PhotoCache {
    // Pixel sizes requested in this run, to find every cached size of a photo.
    private val sizes: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Remember that photos are drawn at [px] pixels. */
    fun noteSize(px: Int) {
        sizes += px
    }

    /** Drop a photo the contacts no longer use, at every size drawn. */
    fun forget(context: Context, url: String) {
        val loader = SingletonImageLoader.get(context)
        for (px in sizes) {
            val key = sizedPhotoUrl(url, px)
            loader.diskCache?.remove(key)
            loader.memoryCache?.remove(MemoryCache.Key(key))
        }
    }
}
