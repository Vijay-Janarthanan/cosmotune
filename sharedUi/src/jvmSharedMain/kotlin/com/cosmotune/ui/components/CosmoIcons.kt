package com.cosmotune.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Draws one icon from the design kit's SVG shapes (`ui/assets.js`), at the v2 stroke of 2 px on a 24 px grid, with round
 * caps and joins. Black is a placeholder: every call site tints the vector.
 */
internal class IconBuilder(private val b: ImageVector.Builder) {
    fun stroke(d: String, width: Float = 2f) { b.addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    fun fill(d: String) { b.addPath(addPathNodes(d), fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false, width: Float = 2f) {
        val d = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
        if (filled) fill(d) else stroke(d, width)
    }
    fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float, filled: Boolean = false) {
        val d = "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}" +
            "h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"
        if (filled) fill(d) else stroke(d)
    }
}

private fun icon(name: String, block: IconBuilder.() -> Unit): Lazy<ImageVector> = lazy {
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).also { IconBuilder(it).block() }.build()
}

private const val HOME = "M4.5 10.2 12 4l7.5 6.2V19a1 1 0 0 1-1 1h-4v-5.5h-5V20h-4a1 1 0 0 1-1-1z"
private const val HEART = "M12 19.6s-7.6-4.4-7.6-9.8A4.2 4.2 0 0 1 12 7.4a4.2 4.2 0 0 1 7.6 2.4c0 5.4-7.6 9.8-7.6 9.8z"
private val REPEAT = listOf("M4.5 11V9.5A2.5 2.5 0 0 1 7 7h12.5", "m17 4.5 2.5 2.5L17 9.5", "M19.5 13v1.5A2.5 2.5 0 0 1 17 17H4.5", "M7 19.5 4.5 17 7 14.5")

/** The 30 icons that the shell, Home, Search, Library and Now Playing need; the rest come with part two. */
object CosmoIcons {
    val Home by icon("home") { stroke(HOME) }
    val HomeFill by icon("home-fill") { fill(HOME) }
    val Search by icon("search") { circle(10.5f, 10.5f, 6.25f); stroke("m15.2 15.2 4.8 4.8") }
    val SearchFill by icon("search-fill") { circle(10.5f, 10.5f, 6.25f, width = 2.6f); stroke("m15.2 15.2 4.8 4.8", 2.6f) }
    val Library by icon("library") { rect(3.5f, 6.5f, 11f, 13f, 1f); stroke("M14.5 8.2a6.5 6.5 0 0 1 0 9.6"); stroke("M17.2 6a9.5 9.5 0 0 1 0 14") }
    val LibraryFill by icon("library-fill") { rect(3.5f, 6.5f, 11f, 13f, 1f, filled = true); stroke("M14.5 8.2a6.5 6.5 0 0 1 0 9.6"); stroke("M17.2 6a9.5 9.5 0 0 1 0 14") }
    val Grid by icon("grid") { rect(4f, 4f, 6.5f, 6.5f, 1f); rect(13.5f, 4f, 6.5f, 6.5f, 1f); rect(4f, 13.5f, 6.5f, 6.5f, 1f); rect(13.5f, 13.5f, 6.5f, 6.5f, 1f) }
    val Play by icon("play") { fill("M8 5.6v12.8a.8.8 0 0 0 1.2.7l10.3-6.4a.8.8 0 0 0 0-1.4L9.2 4.9A.8.8 0 0 0 8 5.6z") }
    val Pause by icon("pause") { rect(6.5f, 5f, 3.6f, 14f, 1f, filled = true); rect(13.9f, 5f, 3.6f, 14f, 1f, filled = true) }
    val Next by icon("next") { fill("M5.5 6.3v11.4a.7.7 0 0 0 1.1.6l8.4-5.7a.7.7 0 0 0 0-1.2L6.6 5.7a.7.7 0 0 0-1.1.6z"); stroke("M18.5 5.5v13") }
    val Previous by icon("prev") { fill("M18.5 6.3v11.4a.7.7 0 0 1-1.1.6L9 12.6a.7.7 0 0 1 0-1.2l8.4-5.7a.7.7 0 0 1 1.1.6z"); stroke("M5.5 5.5v13") }
    val Shuffle by icon("shuffle") {
        stroke("M3.5 7.5h3c1.9 0 3 .9 4 2.4l3 4.2c1 1.5 2.1 2.4 4 2.4h3"); stroke("M3.5 16.5h3c1.2 0 2.1-.4 2.8-1.1")
        stroke("M14.7 8.6c.7-.7 1.6-1.1 2.8-1.1h3"); stroke("m18 5 2.5 2.5L18 10"); stroke("m18 14 2.5 2.5L18 19")
    }
    val Repeat by icon("repeat") { REPEAT.forEach { stroke(it) } }
    val RepeatOne by icon("repeat-one") { REPEAT.forEach { stroke(it) }; stroke("M11.2 10.6 12.4 10v4.4") }
    val Heart by icon("heart") { stroke(HEART) }
    val HeartFill by icon("heart-fill") { fill(HEART) }
    val More by icon("more") { circle(5.5f, 12f, .9f, true); circle(12f, 12f, .9f, true); circle(18.5f, 12f, .9f, true) }
    val MoreVertical by icon("more-v") { circle(12f, 5.5f, .9f, true); circle(12f, 12f, .9f, true); circle(12f, 18.5f, .9f, true) }
    val Queue by icon("queue") { stroke("M4 6.5h12"); stroke("M4 11.5h12"); stroke("M4 16.5h6.5"); fill("M14.5 14.2v5.6a.5.5 0 0 0 .8.4l4.2-2.8a.5.5 0 0 0 0-.8l-4.2-2.8a.5.5 0 0 0-.8.4z") }
    val Lyrics by icon("lyrics") { stroke("M4 7h8.5"); stroke("M4 12h8.5"); stroke("M4 17h11"); stroke("M17.5 13.5V5.2l3 .9"); circle(15.9f, 13.6f, 1.7f) }
    val Devices by icon("devices") { rect(3.5f, 3.5f, 10.5f, 17f, 2.5f); circle(8.75f, 14.25f, 2.75f); circle(8.75f, 7.6f, .6f, true); rect(16.5f, 9f, 4f, 10f, 1.2f) }
    val Together by icon("together") {
        circle(12f, 12f, 2f); stroke("M8.3 8.3a5.2 5.2 0 0 0 0 7.4"); stroke("M15.7 8.3a5.2 5.2 0 0 1 0 7.4")
        stroke("M5.9 5.9a8.6 8.6 0 0 0 0 12.2"); stroke("M18.1 5.9a8.6 8.6 0 0 1 0 12.2")
    }
    val ChevronDown by icon("chevron-down") { stroke("m6 9.5 6 6 6-6") }
    val ChevronLeft by icon("chevron-left") { stroke("M14.5 6 8.5 12l6 6") }
    val ChevronRight by icon("chevron-right") { stroke("m9.5 6 6 6-6 6") }
    val Check by icon("check") { stroke("m5 12.5 4.5 4.5L19 7.5") }
    val Video by icon("video") { rect(3f, 5.5f, 18f, 13f, 3f); fill("M10.2 9.4v5.2a.4.4 0 0 0 .6.3l4-2.6a.4.4 0 0 0 0-.6l-4-2.6a.4.4 0 0 0-.6.3z") }
    val VideoOff by icon("video-off") { rect(3f, 5.5f, 18f, 13f, 3f); stroke("m4 4 16 16") }
    val Expand by icon("expand") { stroke("M14 4.5h5.5V10"); stroke("M10 19.5H4.5V14"); stroke("m19.5 4.5-6 6"); stroke("m4.5 19.5 6-6") }
    val Volume by icon("volume") { stroke("M4 9.6h3.2L11.5 6v12l-4.3-3.6H4z"); stroke("M15 9.2a4 4 0 0 1 0 5.6"); stroke("M17.6 6.6a7.6 7.6 0 0 1 0 10.8") }

    val all: Map<String, ImageVector> by lazy {
        listOf(Home, HomeFill, Search, SearchFill, Library, LibraryFill, Grid, Play, Pause, Next, Previous, Shuffle, Repeat, RepeatOne,
            Heart, HeartFill, More, MoreVertical, Queue, Lyrics, Devices, Together, ChevronDown, ChevronLeft, ChevronRight, Check,
            Video, VideoOff, Expand, Volume).associateBy { it.name }
    }
}
