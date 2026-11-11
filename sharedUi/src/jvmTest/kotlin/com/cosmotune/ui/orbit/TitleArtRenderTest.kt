package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TitleArt
import com.cosmotune.music.ui.graphics.imageBitmapOf
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders every title art treatment, in dark and in light, over invented covers and titles (short, long, Tamil, Japanese), to
 * `build/title-art/` at the root of the checkout for a look by eye, and checks each cell drew its type.
 */
class TitleArtRenderTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val out = File(root, "build/title-art").apply { mkdirs() }

    private val titles = listOf("Lover", "Midnight Transit", "The Tortured Poets Department", "விண்ணைத்தாண்டி வருவாயா", "君の名は。")

    /** Invented covers: a sunset (the board's), a violet dusk, a pale and bright one, and a mint glow. */
    private val covers: List<Pair<String, TitleArtCover>> by lazy {
        listOf(
            "sunset" to cover { x, y -> mix(0xFFFFD9B8.toInt(), 0xFFD98A52.toInt(), 0xFF1A0D07.toInt(), (x + y) / 2f) },
            "dusk" to cover { x, y -> mix(0xFF9FB6FF.toInt(), 0xFF3B2F6B.toInt(), 0xFF140F26.toInt(), y * 0.8f + x * 0.2f) },
            "paper" to cover { x, y -> mix(0xFFFFFFFF.toInt(), 0xFFF4E9D8.toInt(), 0xFFE3C9A6.toInt(), x * 0.6f + y * 0.4f) },
            "mint" to cover { x, y -> mix(0xFFB9FFE9.toInt(), 0xFF1F4D45.toInt(), 0xFF06120F.toInt(), (hypot(x - 0.3f, y - 0.3f) / 0.9f).coerceIn(0f, 1f)) },
        )
    }

    private class Theme(val name: String, val ground: (TitleArtCover) -> Color, val ink: (TitleArtCover) -> Color)

    /** Dark: the cover's deep card with its pale ink, as `coverFill` gives. Light: the light page with dark ink (the mini player). */
    private val themes = listOf(
        Theme("dark", { vivid(it.stats.dark.let { d -> mixTwo(d, TitleArt.hsvToArgb(it.stats.hue, 0.7f, 0.5f), 0.7f) }).fill }, { CARD_INK }),
        Theme("light", { Color(0xFFF6F1EA) }, { Color(0xFF1D1416) }),
    )

    @Test fun coverCut() = sheet("cut", TitleArt.Style.CUT)

    @Test fun splitStack() = sheet("split", TitleArt.Style.SPLIT)

    @Test fun voicedSerif() = sheet("voiced-serif", TitleArt.Style.VOICED, genre = "Jazz")

    @Test fun voicedCondensed() = sheet("voiced-condensed", TitleArt.Style.VOICED, genre = "Hip-Hop/Rap")

    @Test fun voicedWide() = sheet("voiced-wide", TitleArt.Style.VOICED, genre = "Pop")

    @Test fun voicedMono() = sheet("voiced-mono", TitleArt.Style.VOICED, genre = "Electronic")

    @Test fun automatic() = sheet("auto-hero", style = null, genre = null)

    @Test fun arcTitle() = sheet("arc", TitleArt.Style.ARC, cell = 230.dp to 230.dp)

    /** E: the same title at the three points of its breath, side by side, so the width visibly moves and the height does not. */
    @Test fun breathing() {
        for (theme in themes) {
            val steps = listOf(0, TitleArt.BREATH_STEPS / 2, TitleArt.BREATH_STEPS - 1)
            val rows = listOf(TitleArt.Style.CUT, TitleArt.Style.SPLIT).flatMap { style -> titles.take(3).map { style to it } }
            val (sunsetName, sunset) = covers.first()
            render("breathing-${theme.name}", theme, 3 * 380, rows.size * 170) {
                Column {
                    rows.forEach { (style, title) ->
                        Row {
                            steps.forEach { step ->
                                Cell(theme, sunset, 380.dp, 170.dp) {
                                    AlbumTitleArt(
                                        TitleArtAlbum(title, sunsetName), sunset, theme.ink(sunset), theme.ground(sunset), place = TitleArt.Place.HERO,
                                        style = style, playing = true, maxSize = 64.sp, maxHeight = 140.dp, breathStep = step,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The mini player with its arc title round the disc, in its own 380 x 124 window, light and dark. */
    @Test fun miniPlayer() {
        for (theme in themes) {
            val dark = theme.name == "dark"
            render("mini-player-${theme.name}", theme, 380, 2 * 124) {
                Column {
                    for ((title, artist) in listOf("Golden Hour" to "Mira and the Coast", "விண்ணைத்தாண்டி வருவாயா" to "A. R. Rahman")) {
                        Box(Modifier.size(380.dp, 124.dp)) {
                            com.cosmotune.ui.player.MiniPlayerPanel(
                                title, artist, true, 0.4f, artwork = { m -> Box(m.background(Color(0xFFA85834))) },
                                onPlayPause = {}, onNext = {}, onPrevious = {}, onOpen = {}, dark = dark,
                            )
                        }
                    }
                }
            }
        }
    }

    /** The share video's end card title, centred over its ground. */
    @Test fun endCard() {
        for (theme in themes) {
            render("end-card-${theme.name}", theme, 2 * 360, 2 * 300) {
                Column {
                    listOf(titles.take(2), titles.drop(2).take(2)).forEach { pair ->
                        Row {
                            pair.forEachIndexed { i, title ->
                                val (name, cover) = covers[i + 1]
                                Box(Modifier.size(360.dp, 300.dp).padding(6.dp).background(theme.ground(cover)).padding(20.dp), contentAlignment = Alignment.Center) {
                                    AlbumTitleEndCard(TitleArtAlbum(title, name, artist = "Mira and the Coast"), theme.ink(cover), theme.ground(cover), cover = cover)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun theArcReadsTitleThenArtistInCapitals() {
        kotlin.test.assertEquals("LANTERN SEASON · MIRA AND THE COAST · ", arcText(" Lantern Season ", "Mira and the Coast"))
        kotlin.test.assertEquals("GNX · ", arcText("GNX", " "))
    }

    /** The compact song card heading, at its own size: what the phone's Now Playing shows. */
    @Test fun heading() = sheet("heading", style = null, place = TitleArt.Place.HEADING, cell = 340.dp to 90.dp, maxSize = 34, maxHeight = 60.dp, maxLines = 2)

    private fun sheet(
        name: String,
        style: TitleArt.Style?,
        genre: String? = null,
        place: TitleArt.Place = TitleArt.Place.HERO,
        cell: Pair<Dp, Dp> = 380.dp to 170.dp,
        maxSize: Int = 64,
        maxHeight: Dp = 140.dp,
        maxLines: Int = 3,
    ) {
        for (theme in themes) {
            render("$name-${theme.name}", theme, covers.size * cell.first.value.roundToInt(), titles.size * cell.second.value.roundToInt()) {
                Column {
                    titles.forEach { title ->
                        Row {
                            covers.forEach { (coverName, cover) ->
                                Cell(theme, cover, cell.first, cell.second) {
                                    AlbumTitleArt(
                                        TitleArtAlbum(title, coverName, artist = "Mira and the Coast", genre = genre), cover, theme.ink(cover), theme.ground(cover),
                                        place = place, style = style, maxSize = maxSize.sp, maxHeight = maxHeight, maxLines = maxLines,
                                        align = if (style == TitleArt.Style.VOICED) Alignment.CenterHorizontally else Alignment.Start,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Cell(theme: Theme, cover: TitleArtCover, w: Dp, h: Dp, content: @Composable () -> Unit) {
        Box(Modifier.size(w, h).padding(6.dp).clip(RoundedCornerShape(18.dp)).background(theme.ground(cover)).padding(14.dp), contentAlignment = Alignment.Center) {
            content()
        }
    }

    private fun render(name: String, theme: Theme, w: Int, h: Int, content: @Composable () -> Unit) {
        val density = 1.5f
        val dark = theme.name == "dark"
        val scene = ImageComposeScene((w * density).roundToInt(), (h * density).roundToInt(), Density(density)) {
            Box(Modifier.fillMaxSize().background(if (dark) Color.Black else Color.White)) { content() }
        }
        try {
            // Twice: the fonts are read on the first frame and the animated wash settles by the next.
            scene.render(0)
            val image = scene.render(2_000_000_000L)
            File(out, "$name.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
            val bitmap = Bitmap.makeFromImage(image)
            // The type drew: in dark, light pixels on the deep cards (the page round them is black); in light, dark pixels on the
            // light cards (the page round them is white).
            var inked = 0
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                val c = bitmap.getColor(x, y)
                val v = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                if (if (dark) v > 360 else v < 300) inked++
            }
            assertTrue(inked > 500, "$name drew its titles ($inked)")
        } finally {
            scene.close()
        }
    }

    private fun cover(paint: (Float, Float) -> Int): TitleArtCover {
        val n = 96
        val pixels = IntArray(n * n) { i -> paint((i % n) / (n - 1f), (i / n) / (n - 1f)) }
        val image = imageBitmapOf(pixels, n, n)
        return TitleArtCover(image, requireNotNull(TitleArt.coverStats(pixels)))
    }

    private fun mix(a: Int, b: Int, c: Int, t: Float): Int = if (t < 0.5f) mixTwo(a, b, t * 2f) else mixTwo(b, c, (t - 0.5f) * 2f)

    private fun mixTwo(a: Int, b: Int, t: Float): Int = TitleArt.over(a, b, t.coerceIn(0f, 1f))
}
