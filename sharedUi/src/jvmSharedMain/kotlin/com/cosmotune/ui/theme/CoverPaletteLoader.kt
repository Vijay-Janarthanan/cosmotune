package com.cosmotune.ui.theme

import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.cosmotune.theme.CoverColors
import com.cosmotune.theme.CoverPalette
import com.cosmotune.theme.CoverPaletteSpec
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.graphics.argbPixels
import com.music.bitchord.ui.graphics.forPixelAccess
import com.music.bitchord.ui.graphics.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The palette of a cover, once per cover: decoded at 64 px by Coil (through the same cache the rows use), read back as
 * pixels, and run through the engine on [Dispatchers.Default]. An LRU of 64 palettes, per theme and per intensity (Settings >
 * Appearance), keyed by the picture's URL (the picture at a URL does not change), so a track heard again, or fetched ahead as the
 * next one, costs nothing.
 */
internal object CoverPaletteLoader {
    private const val DECODE_PX = 64
    private const val CACHE_ENTRIES = 64

    // Reached from the main thread only (the follower's effects and the load below), so no lock.
    private val cache = object : LinkedHashMap<String, CoverColors>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CoverColors>) = size > CACHE_ENTRIES
    }

    /**
     * The palette of the cover at [url], or null when the picture could not be loaded. [intensity] is the engine's multiplier of the
     * chroma budget and the accent chroma (`Intensity.multiplier`: Calm 0.7, Balanced 1.0, Rich 1.25). Main thread; the work runs on
     * Default.
     */
    suspend fun load(context: PlatformContext, url: String, dark: Boolean, intensity: Double = 1.0): CoverColors? {
        val key = (if (dark) "d:" else "l:") + intensity + ":" + url
        cache[key]?.let { return it }
        val request = ImageRequest.Builder(context).data(url.artworkAt(ROW_ART_PX)).size(DECODE_PX).forPixelAccess().build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap() ?: return null
        val palette = withContext(Dispatchers.Default) {
            CoverPalette.fromPixels(bitmap.argbPixels(), bitmap.width, bitmap.height, dark = dark, spec = CoverPaletteSpec(intensity = intensity))
        }
        cache[key] = palette
        return palette
    }
}
