package com.cosmotune.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
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
    /** A fill with no stroke whose overlapping sub-paths cut holes (even-odd): a vector has one tint, so a knocked-out shape is a hole. */
    fun cutOut(d: String) { b.addPath(addPathNodes(d), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black), stroke = null) }
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
private const val GEAR = "M10.43 5.18L10.63 2.85L13.37 2.85L13.57 5.18A7.0 7.0 0 0 1 15.71 6.06L17.50 4.56L19.44 6.50L17.94 8.29A7.0 7.0 0 0 1 18.82 10.43L21.15 10.63L21.15 13.37L18.82 13.57A7.0 7.0 0 0 1 17.94 15.71L19.44 17.50L17.50 19.44L15.71 17.94A7.0 7.0 0 0 1 13.57 18.82L13.37 21.15L10.63 21.15L10.43 18.82A7.0 7.0 0 0 1 8.29 17.94L6.50 19.44L4.56 17.50L6.06 15.71A7.0 7.0 0 0 1 5.18 13.57L2.85 13.37L2.85 10.63L5.18 10.43A7.0 7.0 0 0 1 6.06 8.29L4.56 6.50L6.50 4.56L8.29 6.06A7.0 7.0 0 0 1 10.43 5.18z"

/**
 * The disc of `downloaded` (circle 12 12, r 8.5) and, cut out of it, the outline of the arrow the asset strokes in the ground
 * colour (`M12 7.5v7` and `m9 11.8 3 3 3-3`, here at the Compose set's width of 2): one closed outline of the union of the shaft
 * and the two arms, with round caps and a round join, so the even-odd hole has no overlap that would fill itself in again.
 */
private const val DOWNLOADED = "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0z" +
    "M11 7.5a1 1 0 0 1 2 0V12.3858L14.2929 11.0929a1 1 0 0 1 1.4142 1.4142L12.7071 15.5071a1 1 0 0 1 -1.4142 0" +
    "L8.2929 12.5071a1 1 0 0 1 1.4142 -1.4142L11 12.3858z"
private val REPEAT = listOf("M4.5 11V9.5A2.5 2.5 0 0 1 7 7h12.5", "m17 4.5 2.5 2.5L17 9.5", "M19.5 13v1.5A2.5 2.5 0 0 1 17 17H4.5", "M7 19.5 4.5 17 7 14.5")

/** The 52 icons of the new look: the 30 of the shell, Home, Search, Library and Now Playing, and the 22 that part two's screens add. */
object CosmoIcons {
    val Canvas by icon("canvas") { rect(5f, 3f, 14f, 18f, 2f); circle(9f, 8f, 1f); stroke("M5 17l5-5 3 3 3-4 3 4") }
    val CanvasOff by icon("canvas-off") { rect(5f, 3f, 14f, 18f, 2f); stroke("M3 3l18 18") }
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

    // part two: the 22 icons the screens add (names and shapes from `ui/assets.js`)
    val Download by icon("download") { stroke("M12 4v10.5"); stroke("m7.5 10 4.5 4.5 4.5-4.5"); stroke("M5 19.5h14") }
    val Downloaded by icon("downloaded") { cutOut(DOWNLOADED) }
    val ChevronUp by icon("chevron-up") { stroke("m6 14.5 6-6 6 6") }
    val Plus by icon("plus") { stroke("M12 5v14"); stroke("M5 12h14") }
    val Close by icon("close") { stroke("m6.5 6.5 11 11"); stroke("m17.5 6.5-11 11") }
    val Settings by icon("settings") { stroke(GEAR); circle(12f, 12f, 2.75f) }
    val Equaliser by icon("equaliser") {
        stroke("M6 4v8"); stroke("M6 16.5V20"); stroke("M12 4v1.5"); stroke("M12 10v10"); stroke("M18 4v6"); stroke("M18 14.5V20")
        circle(6f, 14.25f, 2.25f); circle(12f, 7.75f, 2.25f); circle(18f, 12.25f, 2.25f)
    }
    val VolumeLow by icon("volume-low") { stroke("M4 9.6h3.2L11.5 6v12l-4.3-3.6H4z"); stroke("M15 9.2a4 4 0 0 1 0 5.6") }
    val Timer by icon("timer") { stroke("M19 14.6A7.6 7.6 0 0 1 9.4 5a7.6 7.6 0 1 0 9.6 9.6z") }
    val Share by icon("share") {
        stroke("M12 14.5V4"); stroke("M8 7.5 12 3.5l4 4")
        stroke("M8 10.5H6.5a1.5 1.5 0 0 0-1.5 1.5v6.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V12a1.5 1.5 0 0 0-1.5-1.5H16")
    }
    val Replay by icon("replay") { stroke("M4.6 12a7.4 7.4 0 1 0 2.2-5.3"); stroke("M4.4 4.6v3.6H8"); stroke("M9.6 15v-2.4"); stroke("M12 15V9.6"); stroke("M14.4 15v-3.6") }
    val Cellular by icon("cellular") { stroke("M5 19.5v-2.5"); stroke("M9.5 19.5v-6"); stroke("M14 19.5v-9.5"); stroke("M18.5 19.5v-14") }
    val Clock by icon("clock") { circle(12f, 12f, 8.25f); stroke("M12 7.5V12l3 2") }
    val Pin by icon("pin") { stroke("M9 4h6"); stroke("M10 4v5.5L7 13.5h10l-3-4V4"); stroke("M12 13.5V20") }
    val Sort by icon("sort") { stroke("M4 7h16"); stroke("M7 12h10"); stroke("M10 17h4") }
    val Waveform by icon("waveform") { stroke("M4 11v2"); stroke("M7 8.5v7"); stroke("M10 5.5v13"); stroke("M13 8.5v7"); stroke("M16 7v10"); stroke("M19 10.5v3") }
    val Translate by icon("translate") {
        stroke("M3.5 6h8.5"); stroke("M7.7 4v2"); stroke("M5.2 6c.9 3 3 5.2 5.9 6.4"); stroke("M10.4 6c-.8 3-2.9 5.3-5.9 6.6")
        stroke("m12.8 20 3.6-9 3.6 9"); stroke("M14 17h4.8")
    }
    val ArrowLeft by icon("arrow-left") { stroke("M19 12H5.5"); stroke("M11 6.5 5.5 12l5.5 5.5") }
    val Radio by icon("radio") { rect(3.5f, 8.5f, 17f, 11.5f, 2.5f); circle(15.25f, 14.25f, 2.5f); stroke("M7 12.5h3.5"); stroke("M7 16h3.5"); stroke("m7.5 8.5 8-5") }
    val Bell by icon("bell") { stroke("M6.5 16.5V11a5.5 5.5 0 0 1 11 0v5.5l1.5 2H5z"); stroke("M10 20.5a2 2 0 0 0 4 0") }
    val User by icon("user") { circle(12f, 8.5f, 3.75f); stroke("M4.75 20a7.25 7.25 0 0 1 14.5 0") }
    val List by icon("list") { stroke("M8.5 6.5H20"); stroke("M8.5 12H20"); stroke("M8.5 17.5H20"); stroke("M4 6.5h.5"); stroke("M4 12h.5"); stroke("M4 17.5h.5") }

    val all: Map<String, ImageVector> by lazy {
        listOf(Canvas, CanvasOff, Home, HomeFill, Search, SearchFill, Library, LibraryFill, Grid, Play, Pause, Next, Previous, Shuffle, Repeat, RepeatOne,
            Heart, HeartFill, More, MoreVertical, Queue, Lyrics, Devices, Together, ChevronDown, ChevronLeft, ChevronRight, Check,
            Video, VideoOff, Expand, Volume,
            Download, Downloaded, ChevronUp, Plus, Close, Settings, Equaliser, VolumeLow, Timer, Share, Replay, Cellular, Clock, Pin,
            Sort, Waveform, Translate, ArrowLeft, Radio, Bell, User, List).associateBy { it.name }
    }
}
