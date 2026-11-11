package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cosmotune.lyrics.styles.LyricStyle
import com.cosmotune.music.data.lyrics.LyricAlignment
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricSyllable
import com.cosmotune.music.data.lyrics.LyricWord
import com.cosmotune.music.data.lyrics.LyricsSource
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerSettingsSource
import com.cosmotune.theme.CoverPalette
import com.cosmotune.ui.nowplaying.CosmoLyricStage
import com.cosmotune.ui.nowplaying.LyricStageSong
import com.cosmotune.ui.nowplaying.LyricStageFonts
import com.cosmotune.ui.nowplaying.LyricVideoLook
import com.cosmotune.ui.nowplaying.LyricVideoFrames
import com.cosmotune.ui.nowplaying.LyricsStyle
import com.cosmotune.ui.nowplaying.CosmoLyricsView
import com.cosmotune.music.ui.player.rememberLyricPlayhead
import com.cosmotune.ui.nowplaying.rememberLyricStageFonts
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not an assertion of the look: the six lyric styles of the approved board (`lyrics-titles/board.html`), each drawn offscreen at
 * a phone's lyrics panel size in the dark and the light palettes of a warm cover, at three moments of the board's own invented
 * verse, and written under build/lyrics-styles for a person to look at. Reduced motion is drawn once too, and the share video's
 * frames. Each picture is checked only for having been drawn and for not being blank.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LyricStylesVisualReviewTest {
    private val out = File("../build/lyrics-styles").apply { mkdirs() }

    /** The board's verse (its word end times, each word starting where the one before ended), ten seconds into the song. */
    private val verse: List<LyricLine> = run {
        val base = 10_000L
        val raw = listOf(
            0L to listOf("We" to 300L, "drew" to 700L, "a" to 850L, "map" to 1_300L, "across" to 1_900L, "the" to 2_050L, "night" to 3_000L),
            3_200L to listOf("every" to 3_700L, "light" to 4_300L, "a" to 4_400L, "place" to 4_900L, "we've" to 5_300L, "been" to 6_200L),
            6_400L to listOf("hold" to 6_900L, "the" to 7_050L, "line," to 7_700L, "hold" to 8_100L, "it" to 8_250L, "long" to 9_800L),
            10_000L to listOf("till" to 10_400L, "the" to 10_550L, "morning" to 11_400L, "finds" to 11_900L, "us" to 12_100L, "in" to 13_200L),
        )
        raw.mapIndexed { index, (start, words) ->
            var at = start
            val timed = words.map { (text, end) ->
                val syllables = if (text == "morning") {
                    listOf(LyricSyllable(base + at, base + at + 250, 0, 3), LyricSyllable(base + at + 250, base + end, 3, 7))
                } else emptyList()
                LyricWord(base + at, base + end, text, syllables).also { at = end }
            }
            LyricLine(
                timeMs = base + start,
                text = words.joinToString(" ") { it.first },
                words = timed,
                alignment = if (index == 1) LyricAlignment.End else LyricAlignment.Start,
                background = if (index == 2) LyricLine(base + 7_100, "(hold the line)", listOf(
                    LyricWord(base + 7_100, base + 7_400, "(hold"), LyricWord(base + 7_400, base + 7_600, "the"), LyricWord(base + 7_600, base + 8_200, "line)"),
                )) else null,
            )
        }
    }

    /** At "map", in the held "night", and in the long held "long". */
    private val moments = listOf("map" to 11_100L, "night" to 12_600L, "long" to 19_000L)

    private val song = LyricStageSong("Lantern Season", "Mira and the Coast", "Lantern Season", null)

    private fun colors(dark: Boolean): CosmoColors {
        // A warm brown cover, the board's first card: the palette the page wears comes from its colours.
        val pixels = IntArray(64 * 64) { if ((it / 64 + it % 64) % 3 == 0) 0xFFEFA66F.toInt() else 0xFF6B3F24.toInt() }
        return CosmoColors(CosmoPaletteState(CoverPalette.fromPixels(pixels, 64, 64, dark), dark))
    }

    private fun hosts(block: () -> Unit) {
        val field = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = field.get(null)
        val settings = java.lang.reflect.Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader, arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getLyricsOffsetMs" -> MutableStateFlow(0)
                "getLyricsSourceOrder" -> MutableStateFlow(emptyList<LyricsSource>())
                else -> MutableStateFlow(false)
            }
        } as PlayerSettingsSource
        val host = java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            when (method.name) {
                "getSettings" -> settings
                "rememberRemoteArtworkUrl" -> null
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost
        PlayerPlatform.install(host)
        try { block() } finally { field.set(null, previous) }
    }

    private object Still : LifecycleOwner {
        override val lifecycle: Lifecycle = object : Lifecycle() {
            override val currentState: State get() = State.DESTROYED
            override fun addObserver(observer: LifecycleObserver) {}
            override fun removeObserver(observer: LifecycleObserver) {}
        }
    }

    /** Draws [content] at 390 by 470 dp over the page's ground, waits for the fonts, and writes it as [name]. */
    private fun shoot(name: String, dark: Boolean, reduced: Boolean = false, width: Int = 780, height: Int = 940, content: @Composable () -> Unit) {
        val colors = colors(dark)
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCosmoColors provides colors, LocalCosmoType provides rememberCosmoType(), LocalReducedMotion provides reduced,
                    LocalLifecycleOwner provides Still,
                ) {
                    val ground = Color(colors.palette.backgroundTop)
                    val glow = Color(colors.palette.backgroundMid)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(glow, ground)))) { content() }
                }
            }
        }
        try {
            var nanos = 0L
            var image = scene.render(nanos)
            // The fonts are read off the classpath on their own coroutine: draw until they are in.
            repeat(8) {
                Thread.sleep(60)
                repeat(10) { nanos += 16_000_000L; image = scene.render(nanos) }
            }
            write(image, "$name-${if (dark) "dark" else "light"}")
        } finally {
            scene.close()
        }
    }

    private fun write(image: Image, name: String) {
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("no PNG for $name")
        val file = File(out, "$name.png")
        file.writeBytes(png.bytes)
        assertTrue(file.length() > 2_000, "$name was drawn")
    }

    private fun stage(style: LyricStyle, at: Long) {
        for (dark in listOf(true, false)) shoot("${style.key}-${moments.first { it.second == at }.first}", dark) {
            val clock = remember { mutableLongStateOf(at) }
            CosmoLyricStage(style, verse, null, clock, isPlaying = false, onSeek = {}, song = song, modifier = Modifier.fillMaxSize().padding(16.dp))
        }
    }

    @Test fun orbitRead() = hosts {
        for ((label, at) in moments) for (dark in listOf(true, false)) shoot("orbit_read-$label", dark) {
            val position = remember { PlaybackPosition().apply { report(at) } }
            CosmoLyricsView(
                lines = verse, subLines = null, trackKey = "verse", playhead = rememberLyricPlayhead(position), isPlaying = false,
                onSeekToLine = {}, style = LyricsStyle.Portrait, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), emptyText = "",
            )
        }
    }

    @Test fun orbital() = hosts { for ((_, at) in moments) stage(LyricStyle.ORBITAL, at) }

    @Test fun stretch() = hosts { for ((_, at) in moments) stage(LyricStyle.STRETCH, at) }

    @Test fun callAndAnswer() = hosts { for ((_, at) in moments) stage(LyricStyle.CALL_AND_ANSWER, at) }

    @Test fun pulse() = hosts { for ((_, at) in moments) stage(LyricStyle.PULSE, at) }

    @Test fun poster() = hosts { for ((_, at) in moments) stage(LyricStyle.POSTER, at) }

    @Test fun reducedMotionIsColourOnly() = hosts {
        for (style in listOf(LyricStyle.STRETCH, LyricStyle.ORBITAL, LyricStyle.PULSE)) {
            shoot("${style.key}-reduced", dark = true, reduced = true) {
                val clock = remember { mutableLongStateOf(19_000L) }
                CosmoLyricStage(style, verse, null, clock, isPlaying = true, onSeek = {}, song = song, modifier = Modifier.fillMaxSize().padding(16.dp))
            }
        }
    }

    @Test fun tvFullScreen() = hosts {
        for (style in listOf(LyricStyle.ORBITAL, LyricStyle.STRETCH)) {
            shoot("tv-${style.key}", dark = true, width = 1920, height = 1080, content = {
                val clock = remember { mutableLongStateOf(12_600L) }
                CosmoLyricStage(style, verse, null, clock, isPlaying = false, onSeek = {}, song = song, modifier = Modifier.fillMaxSize().padding(48.dp), fullScreen = true)
            })
        }
    }

    /** The share video's frames, drawn the way the export draws them: off any window, into a bitmap, at 720 by 1280. */
    @Test fun shareVideoFrames() = hosts {
        for (look in listOf(LyricVideoLook.POSTER, LyricVideoLook.STRETCH, LyricVideoLook.ORBITAL)) {
            var fonts: LyricStageFonts? = null
            var resolver: androidx.compose.ui.text.font.FontFamily.Resolver? = null
            // The share sheet's composition, where the bundled fonts load: drawn until they are in, and not written.
            val scene = ImageComposeScene(width = 40, height = 40, density = Density(1f)) {
                fonts = rememberLyricStageFonts()
                resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
            }
            try {
                var nanos = 0L
                repeat(8) { Thread.sleep(60); repeat(4) { nanos += 16_000_000L; scene.render(nanos) } }
            } finally {
                scene.close()
            }
            val frames = LyricVideoFrames(look, verse, song, requireNotNull(fonts), requireNotNull(resolver), coverFill = Color(0xFF6B3F24), coverInk = Color(0xFFFFE3CC), cover = null)
            for ((label, at) in moments) {
                val pixels = IntArray(LyricVideoFrames.WIDTH * LyricVideoFrames.HEIGHT)
                frames.render(at, pixels)
                val bitmap = org.jetbrains.skia.Bitmap().apply {
                    allocPixels(org.jetbrains.skia.ImageInfo.makeN32(LyricVideoFrames.WIDTH, LyricVideoFrames.HEIGHT, org.jetbrains.skia.ColorAlphaType.PREMUL))
                }
                val bytes = ByteArray(pixels.size * 4)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    bytes[i * 4] = (c and 0xFF).toByte(); bytes[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
                    bytes[i * 4 + 2] = (c shr 16 and 0xFF).toByte(); bytes[i * 4 + 3] = (c ushr 24).toByte()
                }
                bitmap.installPixels(bytes)
                assertTrue(pixels.distinct().size > 8, "the ${look.name} frame at $label is not blank")
                write(Image.makeFromBitmap(bitmap), "video-${look.name.lowercase()}-$label")
            }
        }
    }
}
