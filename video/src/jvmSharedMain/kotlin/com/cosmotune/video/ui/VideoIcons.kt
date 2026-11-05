package com.cosmotune.video.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * Draws one icon on the 24 dp grid of `CosmoIcons`, at a stroke of 2 with round caps and joins. The helpers are a copy of
 * `CosmoIcons`'s own (that builder is internal to :sharedUi, and the set is not touched for this): a line is stroked, a filled
 * shape is filled and also stroked at 2 so its corners are round. Black is a placeholder: every call site tints the vector.
 */
private class VideoIconBuilder(private val b: ImageVector.Builder) {
    fun stroke(d: String, width: Float = 2f) {
        b.addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }

    fun fill(d: String) {
        b.addPath(addPathNodes(d), fill = SolidColor(Color.Black), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }

    fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false) {
        val d = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
        if (filled) fill(d) else stroke(d)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) {
        stroke(
            "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}" +
                "h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z",
        )
    }

    /** Draws [block] mirrored top to bottom about the middle of the grid: every y becomes 24 - y (one group, scale -1 about y = 12). */
    fun mirroredTopToBottom(block: VideoIconBuilder.() -> Unit) {
        b.addGroup(pivotY = 12f, scaleY = -1f)
        block()
        b.clearGroup()
    }
}

private fun icon(name: String, block: VideoIconBuilder.() -> Unit): Lazy<ImageVector> = lazy {
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).also { VideoIconBuilder(it).block() }.build()
}

private val THUMB_PATHS = listOf(
    "M7.5 10.5v9H5a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z",
    "M7.5 10.5 11 4.5a2 2 0 0 1 2.5 2.2L13 9.5h5.2a1.8 1.8 0 0 1 1.8 2.1l-1.2 6.4a1.8 1.8 0 0 1-1.8 1.5H7.5",
)

private fun VideoIconBuilder.captionsShapes() {
    rect(3.5f, 5.5f, 17f, 13f, 2.5f)
    stroke("M10.5 10.2a2.2 2.2 0 1 0 0 3.6")
    stroke("M16.5 10.2a2.2 2.2 0 1 0 0 3.6")
}

/**
 * The 22 icons Video mode adds (spec section 10; the shapes are the plan's, task Y-1). The board has no video icons: they follow
 * the rules of the set and the owner approves them on the first build.
 */
object VideoIcons {
    val Fullscreen by icon("fullscreen") { stroke("M4.5 9V4.5H9"); stroke("M15 4.5h4.5V9"); stroke("M19.5 15v4.5H15"); stroke("M9 19.5H4.5V15") }
    val FullscreenExit by icon("fullscreen-exit") { stroke("M9 4.5V9H4.5"); stroke("M15 4.5V9h4.5"); stroke("M19.5 15H15v4.5"); stroke("M4.5 15H9v4.5") }
    val Pip by icon("pip") { rect(3.5f, 5.5f, 17f, 13f, 2f); rect(12f, 11.5f, 6f, 4.5f, 1f) }
    val Captions by icon("captions") { captionsShapes() }
    val CaptionsOff by icon("captions-off") { captionsShapes(); stroke("m4 4 16 16") }
    val Speed by icon("speed") { stroke("M5.2 17.5a8 8 0 1 1 13.6 0"); stroke("m12 13.5 3.5-4.5") }
    val ThumbUp by icon("thumb-up") { THUMB_PATHS.forEach { stroke(it) } }
    val ThumbDown by icon("thumb-down") { mirroredTopToBottom { THUMB_PATHS.forEach { stroke(it) } } }
    val Comment by icon("comment") { stroke("M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 17h-9l-4.5 3.5V17H5a1.5 1.5 0 0 1-1.5-1.5V7A1.5 1.5 0 0 1 5 5.5z") }
    val Subscriptions by icon("subscriptions") { rect(4f, 9f, 16f, 11f, 2f); stroke("M6 6.5h12"); stroke("M8 4h8"); fill("M10.5 12.3v4.4l3.8-2.2z") }
    val Live by icon("live") {
        circle(12f, 12f, 2f, filled = true)
        stroke("M8.2 8.2a5.4 5.4 0 0 0 0 7.6"); stroke("M15.8 8.2a5.4 5.4 0 0 1 0 7.6")
        stroke("M5.4 5.4a9.3 9.3 0 0 0 0 13.2"); stroke("M18.6 5.4a9.3 9.3 0 0 1 0 13.2")
    }
    val SkipSegment by icon("skip-segment") { fill("M4.5 7.5l6 4.5-6 4.5z"); fill("M11.5 7.5l6 4.5-6 4.5z"); stroke("M19.5 6.5v11") }

    /** The mirror of `CosmoIcons.Replay`'s arc, which is "back". */
    val Forward by icon("forward") { stroke("M19.4 12a7.4 7.4 0 1 1-2.2-5.3"); stroke("M19.6 4.6v3.6H16") }
    val History by icon("history") { stroke("M4.6 12a7.4 7.4 0 1 0 2.2-5.3"); stroke("M4.4 4.6v3.6H8"); stroke("M12 8.5V12l2.5 1.5") }
    val PlaylistAdd by icon("playlist-add") { stroke("M4 6.5h11"); stroke("M4 11.5h11"); stroke("M4 16.5h7"); stroke("M17.5 13.5v7"); stroke("M14 17h7") }
    val Bookmark by icon("bookmark") { stroke("M7 4.5h10a1 1 0 0 1 1 1v14l-6-4-6 4v-14a1 1 0 0 1 1-1z") }
    val VolumeOff by icon("volume-off") { stroke("M4 9.6h3.2L11.5 6v12l-4.3-3.6H4z"); stroke("m15.5 9.5 5 5"); stroke("m20.5 9.5-5 5") }
    val Brightness by icon("brightness") {
        circle(12f, 12f, 3.5f)
        stroke("M12 3.5V5"); stroke("M12 19v1.5"); stroke("M3.5 12H5"); stroke("M19 12h1.5")
        stroke("m6 6 1 1"); stroke("m17 17 1 1"); stroke("m6 18 1-1"); stroke("m17 7 1-1")
    }
    val Shorts by icon("shorts") { rect(7f, 3.5f, 10f, 17f, 3f); fill("M10.8 9.8v4.4l3.6-2.2z") }
    val Hd by icon("hd") {
        rect(3.5f, 6f, 17f, 12f, 2.5f)
        stroke("M7.5 9.5v5"); stroke("M7.5 12h3"); stroke("M10.5 9.5v5"); stroke("M13.5 9.5v5h1.5a2.5 2.5 0 0 0 0-5z")
    }
    val Info by icon("info") { circle(12f, 12f, 8.25f); stroke("M12 11v5"); stroke("M12 8h.01") }
    val Upload by icon("upload") { stroke("M12 15V4.5"); stroke("m7.5 9 4.5-4.5 4.5 4.5"); stroke("M5 19.5h14") }

    /** Three lines: the guide's button (not in the plan's table of 22, which [all] lists). */
    val Menu by icon("menu") { stroke("M4 6.5h16"); stroke("M4 12h16"); stroke("M4 17.5h16") }

    /** Every icon with its name, in the order of the plan's table: 22. */
    val all: List<Pair<String, ImageVector>> by lazy {
        listOf(
            Fullscreen, FullscreenExit, Pip, Captions, CaptionsOff, Speed, ThumbUp, ThumbDown, Comment, Subscriptions, Live,
            SkipSegment, Forward, History, PlaylistAdd, Bookmark, VolumeOff, Brightness, Shorts, Hd, Info, Upload,
        ).map { it.name to it }
    }
}
