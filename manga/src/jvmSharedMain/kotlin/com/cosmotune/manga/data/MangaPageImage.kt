package com.cosmotune.manga.data

import java.io.File
import kotlin.math.abs

/** A rectangle inside an image, in pixels: left and top inclusive, right and bottom exclusive. */
data class MangaPageBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The part of a page worth showing once its plain margins are cut (Mihon's "crop borders"): from each edge, rows and columns
 * that are all the colour of the page's corner (white or black paper, within [tolerance]) are dropped. [pixels] are ARGB, row by
 * row. A page that is margin almost everywhere, or whose margins are thin, comes back whole; a cut never takes more than a
 * third of a side, so a mostly white panel is not mistaken for paper.
 */
fun mangaContentBounds(pixels: IntArray, width: Int, height: Int, tolerance: Int = 24): MangaPageBounds {
    val whole = MangaPageBounds(0, 0, width, height)
    if (width < 16 || height < 16 || pixels.size < width * height) return whole
    val paper = pixels[0]
    fun plain(color: Int): Boolean = abs(((color shr 16) and 0xff) - ((paper shr 16) and 0xff)) <= tolerance &&
        abs(((color shr 8) and 0xff) - ((paper shr 8) and 0xff)) <= tolerance && abs((color and 0xff) - (paper and 0xff)) <= tolerance
    val luminance = ((paper shr 16) and 0xff) * 0.3 + ((paper shr 8) and 0xff) * 0.59 + (paper and 0xff) * 0.11
    if (luminance in 40.0..215.0) return whole // a coloured corner is artwork, not paper
    fun rowPlain(y: Int) = (0 until width step 2).all { plain(pixels[y * width + it]) }
    fun columnPlain(x: Int) = (0 until height step 2).all { plain(pixels[it * width + x]) }
    var top = 0; while (top < height / 3 && rowPlain(top)) top++
    var bottom = height; while (bottom > height - height / 3 && rowPlain(bottom - 1)) bottom--
    var left = 0; while (left < width / 3 && columnPlain(left)) left++
    var right = width; while (right > width - width / 3 && columnPlain(right - 1)) right--
    // A page that is paper as far as every limit (a blank page, one flat colour) is shown as it is.
    if (top == height / 3 && left == width / 3 && bottom == height - height / 3 && right == width - width / 3) return whole
    val cut = MangaPageBounds(left, top, right, bottom)
    // Margins under 2 % on every side are not worth a re-encode.
    return if (cut.width * cut.height >= width * height * 0.96) whole else cut
}

/** How a page is prepared before it is shown: its margins cut, and a wide page turned to stand upright on a tall screen. */
data class MangaPageTreatment(val crop: Boolean = false, val rotateWide: Boolean = false, /** 1 the left half, 2 the right half, 0 all of it. */ val half: Int = 0) {
    val none: Boolean get() = !crop && !rotateWide && half == 0
    val suffix: String get() = (if (crop) "c" else "") + (if (rotateWide && half == 0) "r" else "") + (if (half != 0) "h$half" else "")
}

/** [bounds] narrowed to its left (1) or right (2) half for a split wide page; as it is for 0. */
fun MangaPageBounds.half(half: Int): MangaPageBounds = when (half) {
    1 -> copy(right = left + width / 2)
    2 -> copy(left = left + width / 2)
    else -> this
}

/**
 * The page file to show for [source] under [treatment]: a prepared copy next to it ("<name>.<suffix>.png"), made once and kept
 * as long as the page; [source] itself when nothing needed changing or the platform could not read the image.
 */
fun mangaTreatedPage(source: File, treatment: MangaPageTreatment): File {
    if (treatment.none) return source
    val target = File(source.parentFile, source.nameWithoutExtension + "." + treatment.suffix + ".treated")
    if (target.isFile && target.length() > 0 && target.lastModified() >= source.lastModified()) return target
    val done = runCatching { MangaPageImages.treat(source, target, treatment) }.getOrDefault(false)
    return if (done && target.isFile) target else source
}

/** The platform's image decoder and encoder, for [mangaTreatedPage]. */
expect object MangaPageImages {
    /** Writes [source] prepared under [treatment] to [target]; false when there was nothing to change or it could not be read. */
    fun treat(source: File, target: File, treatment: MangaPageTreatment): Boolean

    /** The width and height of the image in [file], without decoding all of it where the platform allows; null if unreadable. */
    fun size(file: File): Pair<Int, Int>?
}
