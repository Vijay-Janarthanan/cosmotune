package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.cosmotune.ui.orbit.clock
import com.cosmotune.watch.model.SeekPreviews

/**
 * The seek thumbnail over a dragged seek bar: the frame of [timeMs] cut from its trickplay sheet (one image request per sheet,
 * at the sheet's own size), 160 x 90 with 12 dp corners, the time under it, on a dark card. It sits above the line, over the
 * controls, never over the picture.
 */
@Composable
internal fun WatchSeekThumb(previews: SeekPreviews, timeMs: Long) {
    val frame = previews.frameAt(timeMs)
    val url = previews.sheets.getOrNull(frame.sheet)
    val context = LocalPlatformContext.current
    // Thumbnails drawn on this device are files; a server's are web addresses.
    val request = remember(url, context) { url?.let { ImageRequest.Builder(context).data(if (it.startsWith("file:")) java.io.File(java.net.URI(it)) else it).size(coil3.size.Size.ORIGINAL).build() } }
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE6141012)).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (request != null) {
            val painter = rememberAsyncImagePainter(request)
            Box(
                Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).drawBehind {
                    val sheet = painter.intrinsicSize
                    if (sheet.isUnspecified || sheet.width <= 0f || previews.frameWidth <= 0 || previews.frameHeight <= 0) return@drawBehind
                    val sx = size.width / previews.frameWidth
                    val sy = size.height / previews.frameHeight
                    translate(-frame.column * size.width, -frame.row * size.height) {
                        with(painter) { draw(Size(sheet.width * sx, sheet.height * sy)) }
                    }
                },
            )
        }
        Text(clock(timeMs), color = Color.White, style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    }
}
