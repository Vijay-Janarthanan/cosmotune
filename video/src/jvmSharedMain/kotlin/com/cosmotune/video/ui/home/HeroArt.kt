package com.cosmotune.video.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.cosmotune.music.ui.graphics.argbPixels
import com.cosmotune.music.ui.graphics.forPixelAccess
import com.cosmotune.music.ui.graphics.toImageBitmap
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.ui.blocks.ThumbnailBadge
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.blocks.badgeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colour card's picture, filling its 16:9 slot. A landscape picture is cropped to it ([ThumbnailFrame]). A Short or a picture
 * with the portrait frame set in plain side bands (the site's 16:9 still of a vertical video) stands as that portrait frame in the
 * middle over a blurred, darkened, enlarged copy of itself, so no empty bands show beside it.
 */
@Composable
internal fun HeroArt(item: VideoItem, modifier: Modifier, shape: Shape) {
    val url = item.thumbnailUrl
    val portrait = rememberPortraitArt(item)
    if (url == null || !portrait) {
        ThumbnailFrame(url, modifier, shape, badge = badgeText(item), badgeLive = item.isLive)
        return
    }
    // A true portrait picture is centred as it is; a 16:9 still with bands is enlarged so its middle fills the blur.
    val zoom = if (ShortsFilter.isShort(item) && PORTRAIT_PICTURES.any { url.contains(it, ignoreCase = true) }) 1f else BAND_ZOOM
    Box(modifier.clip(shape)) {
        AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().graphicsLayer { scaleX = zoom; scaleY = zoom }.blur(18.dp)
                .drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = 0.22f)) },
        )
        // A 9:16 box: Crop shows the picture's middle at its full height, which is the vertical frame in both cases.
        ThumbnailFrame(url, Modifier.fillMaxHeight().aspectRatio(9f / 16f).align(Alignment.Center), shape = androidx.compose.ui.graphics.RectangleShape)
        badgeText(item)?.let { badge ->
            ThumbnailBadge(badge, item.isLive, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp))
        }
    }
}

/** Whether [item]'s picture is a vertical frame: a Short, or a 16:9 still whose sides are plain bands ([hasSideBands]). */
@Composable
private fun rememberPortraitArt(item: VideoItem): Boolean {
    val url = item.thumbnailUrl
    val short = ShortsFilter.isShort(item)
    var banded by remember(url) { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    LaunchedEffect(url, short) {
        if (url == null || short) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(url).size(SAMPLE_PX).forPixelAccess().build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toImageBitmap() ?: return@LaunchedEffect
        banded = withContext(Dispatchers.Default) { hasSideBands(bitmap.argbPixels(), bitmap.width, bitmap.height) }
    }
    return short || banded
}

/**
 * Whether a [width] x [height] picture ([argb] pixels, row by row) is a vertical frame set in plain bands: its outer fifth on
 * each side is soft (much less change from one pixel to the next than in the middle, as a flat or blurred band has) while the
 * middle has detail. A landscape still has detail right to its sides.
 */
internal fun hasSideBands(argb: IntArray, width: Int, height: Int): Boolean {
    if (width < 16 || height < 8 || argb.size < width * height) return false
    fun luma(p: Int): Double = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
    // The mean change between neighbouring pixels across and down a stretch of columns: its detail.
    fun detail(x0: Int, x1: Int): Double {
        var sum = 0.0; var n = 0
        for (y in height / 10 until height - height / 10 - 1) for (x in x0 until x1 - 1) {
            val here = luma(argb[y * width + x])
            sum += kotlin.math.abs(luma(argb[y * width + x + 1]) - here) + kotlin.math.abs(luma(argb[(y + 1) * width + x]) - here); n++
        }
        return if (n == 0) 0.0 else sum / n
    }
    val left = detail((width * 0.03).toInt(), (width * 0.25).toInt())
    val right = detail((width * 0.75).toInt(), (width * 0.97).toInt())
    val middle = detail(width * 2 / 5, width * 3 / 5)
    return middle > MIN_DETAIL && left < middle * BAND_SHARE && right < middle * BAND_SHARE
}

/** The site's portrait stills of a Short. */
private val PORTRAIT_PICTURES = listOf("oardefault", "oar2")

/**
 * How much a banded 16:9 still is enlarged behind it: its portrait middle (16:9 over 9:16, about 3.2) and a little more, past the
 * frame's own edges, so the blur is the picture's colour.
 */
private const val BAND_ZOOM = 4.2f

/**
 * A side counts as a band when it has under this share of the middle's detail: the site's bands are soft blurs of the frame (about
 * a third to a half of its detail, measured), a landscape still has about as much detail at its sides as in its middle.
 */
private const val BAND_SHARE = 0.55

/** The least detail (mean change between neighbours, 0 to 510) a middle needs to be a picture rather than a plain card. */
private const val MIN_DETAIL = 6.0

/** The width the picture is read at to look for bands. */
private const val SAMPLE_PX = 96
