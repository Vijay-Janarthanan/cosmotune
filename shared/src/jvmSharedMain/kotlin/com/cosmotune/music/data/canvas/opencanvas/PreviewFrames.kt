package com.opencanvas.core.stream

import okhttp3.OkHttpClient
import okhttp3.Request

/** A decoded image as 0xAARRGGBB pixels, row by row. */
class PreviewImage(val width: Int, val height: Int, val argb: IntArray)

/**
 * Whether a YouTube upload is a still picture, from its storyboard: the small preview frames YouTube shows over the seek bar,
 * a few seconds apart, in one sheet. A poster uploaded with the song as its sound (a label's or an artist's plain "Song" upload)
 * shows the same frame after its opening seconds; a video song changes. Unlike
 * the frame sizes of the file, this does not depend on how the upload was encoded: a full-HD poster with an animated intro
 * measures like a video (72 kbit/s, motion 0.6), a re-upload of a real video can measure like a poster.
 *
 * Opening the sheet (WebP) is the platform's: [decoder] is set by the app at start-up; without one nothing is decided here.
 */
object PreviewFrames {
    /** Turns the sheet's bytes into pixels: Skia on the desktop, BitmapFactory on Android. */
    @Volatile var decoder: ((ByteArray) -> PreviewImage?)? = null

    /** One storyboard sheet: its address and how the frames are laid out in it. */
    data class Sheet(val url: String, val frameWidth: Int, val frameHeight: Int, val frames: Int, val columns: Int, val rows: Int)

    /**
     * The sheet to read from a storyboard spec (`base|w#h#count#cols#rows#interval#name#sigh|…`, one level per part after the
     * base): the smallest level whose frames are at least 48 px wide and fit one sheet, so the whole video is in one image.
     */
    fun sheet(spec: String): Sheet? {
        val parts = spec.split('|')
        if (parts.size < 2 || !parts[0].startsWith("https://")) return null
        val levels = parts.drop(1).mapIndexedNotNull { index, level ->
            val f = level.split('#')
            if (f.size < 8) return@mapIndexedNotNull null
            val w = f[0].toIntOrNull() ?: return@mapIndexedNotNull null
            val h = f[1].toIntOrNull() ?: return@mapIndexedNotNull null
            val count = f[2].toIntOrNull() ?: return@mapIndexedNotNull null
            val cols = f[3].toIntOrNull() ?: return@mapIndexedNotNull null
            val rows = f[4].toIntOrNull() ?: return@mapIndexedNotNull null
            Triple(index, f, intArrayOf(w, h, count, cols, rows))
        }
        val pick = levels.firstOrNull { (_, _, n) -> n[0] >= MIN_FRAME_WIDTH } ?: levels.lastOrNull() ?: return null
        val (index, f, n) = pick
        val name = f[6].replace("\$M", "0")
        val url = parts[0].replace("\$L", index.toString()).replace("\$N", name) + "&sigh=" + f[7]
        return Sheet(url, n[0], n[1], minOf(n[2], n[3] * n[4]), n[3], n[4])
    }

    /** Each frame of [sheet] in [image] as an 8×8 grid of brightness (0–255); frames that fall outside the image are left out. */
    fun grids(image: PreviewImage, sheet: Sheet): List<DoubleArray> = (0 until sheet.frames).mapNotNull { i ->
        val x0 = (i % sheet.columns) * sheet.frameWidth
        val y0 = (i / sheet.columns) * sheet.frameHeight
        if (x0 + sheet.frameWidth > image.width || y0 + sheet.frameHeight > image.height) return@mapNotNull null
        DoubleArray(GRID * GRID) { cell ->
            val x = x0 + ((cell % GRID) + 0.5) * sheet.frameWidth / GRID
            val y = y0 + ((cell / GRID) + 0.5) * sheet.frameHeight / GRID
            val p = image.argb[y.toInt() * image.width + x.toInt()]
            0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        }
    }

    /**
     * True when the frames after the opening ones typically barely change (a still picture, or a few pictures in turn), false
     * when they keep changing, null when there are too few frames to tell.
     */
    fun isStill(grids: List<DoubleArray>): Boolean? {
        if (grids.size < MIN_FRAMES) return null
        val body = grids.drop(maxOf(1, grids.size / 10))
        val changes = body.zipWithNext { a, b -> a.indices.sumOf { kotlin.math.abs(a[it] - b[it]) } / a.size }.sorted()
        return changes[changes.size / 2] < STILL_MEDIAN_CHANGE
    }

    /** The same, read from YouTube for a storyboard [spec]; null when it cannot be read or decided. */
    internal suspend fun isStill(http: OkHttpClient, spec: String): Boolean? {
        val decode = decoder ?: return null
        val sheet = sheet(spec) ?: return null
        val answer = runCatching { http.newCall(Request.Builder().url(sheet.url).build()).fetch(limit = MAX_SHEET_BYTES) }.getOrNull() ?: return null
        if (!answer.isSuccessful) return null
        val image = runCatching { decode(answer.body) }.getOrNull() ?: return null
        return isStill(grids(image, sheet))
    }

    private const val GRID = 8
    /** The smallest frames read: at 48 px compression noise alone moves a still picture by as much as a dark video changes. */
    private const val MIN_FRAME_WIDTH = 80
    private const val MIN_FRAMES = 6
    /**
     * The typical (median) brightness change (0–255) between one preview frame and the next, below which an upload is a still
     * picture. Measured at 80 px and wider: still posters 3.5 and 6.2, video songs 16.6 (a dark scene) to 72.9.
     */
    const val STILL_MEDIAN_CHANGE = 10.0
    private const val MAX_SHEET_BYTES = 2L * 1024 * 1024
}
