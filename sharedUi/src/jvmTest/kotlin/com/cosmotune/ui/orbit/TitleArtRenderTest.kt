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
import androidx.compose.ui.graphics.toArgb
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

    // ---- Cover Cut over photographic covers ------------------------------------------------------------------------------------

    /**
     * Procedural covers with the structure of real artwork, not flat gradients: a landscape (sky, sun, ridges, grain), a neon
     * night (stripes and bokeh on black), a film poster (a red burst on black, heavy grain), a pastel portrait (soft blobs and a
     * dark figure), and the indigo dusk the flat set had, which read dim.
     */
    private val photos: List<Pair<String, TitleArtCover>> by lazy {
        listOf(
            "landscape" to photo(1) { x, y, n ->
                val sky = mixTwo(0xFF2E5FA8.toInt(), 0xFFF2A65A.toInt(), y * 1.3f)
                val sun = hypot(x - 0.68f, y - 0.42f) < 0.11f
                val ridge = 0.55f + 0.08f * kotlin.math.sin(x * 9f) + 0.05f * kotlin.math.sin(x * 23f + 1f)
                val near = 0.72f + 0.06f * kotlin.math.sin(x * 5f + 2f)
                val base = when {
                    y > near -> mixTwo(0xFF1B2416.toInt(), 0xFF0B0F09.toInt(), (y - near) * 3f)
                    y > ridge -> mixTwo(0xFF3C4A6B.toInt(), 0xFF26304A.toInt(), (y - ridge) * 4f)
                    sun -> 0xFFFFF1C9.toInt()
                    else -> sky
                }
                grain(base, n, 18)
            },
            "neon" to photo(2) { x, y, n ->
                val stripe = ((x + y) * 7f % 1f + 1f) % 1f
                var c = 0xFF07060C.toInt()
                if (stripe < 0.12f) c = mixTwo(0xFFFF3D9A.toInt(), 0xFF07060C.toInt(), stripe * 6f)
                if (stripe in 0.5f..0.56f) c = 0xFF2FE6FF.toInt()
                for ((bx, by, r) in listOf(Triple(0.2f, 0.3f, 0.08f), Triple(0.75f, 0.7f, 0.12f), Triple(0.55f, 0.2f, 0.05f))) {
                    val d = hypot(x - bx, y - by) / r
                    if (d < 1f) c = mixTwo(0xFFFFD36B.toInt(), c, d * d)
                }
                grain(c, n, 10)
            },
            "poster" to photo(3) { x, y, n ->
                val d = hypot(x - 0.5f, y - 0.62f)
                val rays = kotlin.math.sin(kotlin.math.atan2(y - 0.62f, x - 0.5f) * 8f) > 0.3f
                val base = when {
                    d < 0.16f -> 0xFFFFE08A.toInt()
                    rays && d < 0.7f -> mixTwo(0xFFE2261C.toInt(), 0xFF5A0A06.toInt(), d)
                    else -> mixTwo(0xFF2A0604.toInt(), 0xFF000000.toInt(), d)
                }
                grain(base, n, 40)
            },
            "pastel" to photo(4) { x, y, n ->
                var c = mixTwo(0xFFF7E3EC.toInt(), 0xFFDDEBF7.toInt(), x)
                for ((bx, by, col) in listOf(Triple(0.3f, 0.3f, 0xFFF4B6C9.toInt()), Triple(0.75f, 0.45f, 0xFFB9D7F2.toInt()), Triple(0.4f, 0.8f, 0xFFF9E1A8.toInt()))) {
                    c = mixTwo(c, col, (1f - hypot(x - bx, y - by) / 0.35f).coerceIn(0f, 1f) * 0.8f)
                }
                val head = hypot((x - 0.62f) * 1.6f, y - 0.42f) < 0.14f
                val body = y > 0.58f && kotlin.math.abs(x - 0.62f) < 0.12f + (y - 0.58f) * 0.5f
                grain(if (head || body) 0xFF2B2230.toInt() else c, n, 8)
            },
            "dusk" to covers[1].second,
        )
    }

    /** Cover Cut, big, over each photographic cover: the cover's detail has to show through the letters. */
    @Test fun coverCutOverPhotographs() {
        for (theme in themes) {
            render("cut-photo-${theme.name}", theme, photos.size * 300, 3 * 190) {
                Column {
                    listOf("Lover", "Midnight Transit", "கண்ணாளனே").forEach { title ->
                        Row {
                            photos.forEach { (name, cover) ->
                                Cell(theme, cover, 300.dp, 190.dp) {
                                    AlbumTitleArt(
                                        TitleArtAlbum(title, name), cover, theme.ink(cover), theme.ground(cover), style = TitleArt.Style.CUT,
                                        maxSize = 110.sp, maxHeight = 160.dp, align = Alignment.CenterHorizontally,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Measured, not assumed: the inside of every Cover Cut letter (where the same title drawn in plain ink is fully ink, so no
     * anti-aliased edge counts) holds 3:1 against its card at its darkest tenth and 4.5:1 at its middle, over every cover in
     * dark and in light. The indigo dusk card, which read dim, is among them.
     */
    @Test fun coverCutLettersHoldTheirContrast() {
        val failures = mutableListOf<String>()
        for (theme in themes) for ((name, cover) in photos + covers) {
            val ink = theme.ink(cover)
            val ground = theme.ground(cover)
            val w = 360
            val h = 150
            fun shot(withCover: Boolean): Bitmap {
                val scene = ImageComposeScene(w, h, Density(1f)) {
                    Box(Modifier.fillMaxSize().background(ground).padding(10.dp)) {
                        AlbumTitleArt(TitleArtAlbum("LOVER", name), if (withCover) cover else null, ink, ground, style = TitleArt.Style.CUT, maxSize = 120.sp, maxHeight = 130.dp)
                    }
                }
                try {
                    scene.render(0)
                    return Bitmap.makeFromImage(scene.render(2_000_000_000L))
                } finally {
                    scene.close()
                }
            }
            val mask = shot(withCover = false)
            val art = shot(withCover = true)
            val inkArgb = ink.toArgb()
            val ratios = ArrayList<Double>()
            for (y in 0 until h) for (x in 0 until w) {
                if (near(mask.getColor(x, y), inkArgb)) ratios += com.cosmotune.design.Contrast.ratio(art.getColor(x, y) or (0xFF shl 24), ground.toArgb())
            }
            assertTrue(ratios.size > 2_000, "$name ${theme.name}: the letters were found (${ratios.size} px)")
            ratios.sort()
            val floor = ratios[ratios.size / 10]
            val middle = ratios[ratios.size / 2]
            println("cover cut contrast ${theme.name} $name: tenth %.2f, middle %.2f".format(floor, middle))
            if (floor < 3.0 || middle < 4.5) failures += "${theme.name} $name: tenth %.2f, middle %.2f".format(floor, middle)
        }
        assertTrue(failures.isEmpty(), "Cover Cut letters under contrast: $failures")
    }

    private fun near(a: Int, b: Int): Boolean =
        listOf(16, 8, 0).all { kotlin.math.abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) <= 2 }

    /** A 256 px cover painted by [paint] from (x, y) in 0..1 and a repeatable noise value in -1..1 (seeded by [seed]). */
    private fun photo(seed: Long, paint: (Float, Float, Float) -> Int): TitleArtCover {
        val n = 256
        val random = java.util.Random(seed)
        val pixels = IntArray(n * n) { i -> paint((i % n) / (n - 1f), (i / n) / (n - 1f), random.nextFloat() * 2f - 1f) }
        return requireNotNull(TitleArtCover.of(imageBitmapOf(pixels, n, n)))
    }

    /** [argb] with film grain of up to [amount] levels. */
    private fun grain(argb: Int, noise: Float, amount: Int): Int {
        val d = (noise * amount).roundToInt()
        fun ch(shift: Int) = (((argb shr shift) and 0xFF) + d).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
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
        return requireNotNull(TitleArtCover.of(image))
    }

    private fun mix(a: Int, b: Int, c: Int, t: Float): Int = if (t < 0.5f) mixTwo(a, b, t * 2f) else mixTwo(b, c, (t - 0.5f) * 2f)

    private fun mixTwo(a: Int, b: Int, t: Float): Int = TitleArt.over(a, b, t.coerceIn(0f, 1f))
}
