package com.cosmotune.music.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import com.cosmotune.design.CoverArt
import com.cosmotune.music.data.model.CARD_ART_PX
import com.cosmotune.music.data.model.artworkAt
import com.cosmotune.music.ui.graphics.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/** One bounded cache for Compose covers and the desktop shell's dock and taskbar art. */
internal object DesktopArtworkCache {
    internal val budgetBytes: Long =
        (Runtime.getRuntime().maxMemory() / 6).coerceIn(32L * 1024 * 1024, 128L * 1024 * 1024)

    fun imageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(coil3.svg.SvgDecoder.Factory())
            add(com.cosmotune.ui.providers.CatalogueArtworkFetcher.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(budgetBytes).build() }
        .diskCache {
            DiskCache.Builder().directory(DesktopMediaCache.directory.resolveSibling("artwork").toString().toPath())
                .maxSizeBytes(128L * 1024 * 1024).build()
        }
        .build()

    suspend fun clear() = withContext(Dispatchers.IO) {
        val loader = SingletonImageLoader.get(PlatformContext.INSTANCE)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    suspend fun load(url: String?, px: Int = CARD_ART_PX): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        val context = PlatformContext.INSTANCE
        val result = SingletonImageLoader.get(context).execute(request(context, url, px))
        return (result as? SuccessResult)?.image?.toImageBitmap()
    }

    internal fun request(context: PlatformContext, url: String?, px: Int?, scale: Scale = Scale.FILL): ImageRequest {
        val builder = ImageRequest.Builder(context)
        if (px == null) return builder.data(url).build()
        val tier = CoverArt.px(px.toFloat(), 1f)
        return builder.data(url.artworkAt(tier)).size(tier).scale(scale).build()
    }
}

private val placeholder = ColorPainter(Color(0xFF2B2B35))

/** Artwork, decoded to the requested cache rung or to the destination's measured size. */
@Composable
fun DesktopArtwork(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    px: Int? = null,
) {
    val context = LocalPlatformContext.current
    val request = remember(context, url, px, contentScale) {
        DesktopArtworkCache.request(context, url, px, if (contentScale == ContentScale.Crop) Scale.FILL else Scale.FIT)
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        placeholder = placeholder,
        fallback = placeholder,
        error = placeholder,
    )
}
