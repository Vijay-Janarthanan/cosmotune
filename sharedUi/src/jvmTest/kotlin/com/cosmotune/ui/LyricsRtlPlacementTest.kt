package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.lyrics.LyricAlignment
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.LyricPlayhead
import com.cosmotune.ui.nowplaying.CosmoLyricsView
import com.cosmotune.ui.nowplaying.LyricsStyle
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BC-33, drawn for real: the new look's lyrics place a line on the side its own text is written from, whatever the app's
 * language. Hebrew and Arabic in an English (left-to-right) app stand against the right edge, English in a Hebrew
 * (right-to-left) app against the left, and a duet's second voice in Hebrew on the left. The view is rendered on an offscreen
 * Skia surface and the ink's horizontal extent is read back. Before the fix a Hebrew line sat in a box placed on the app's start
 * side, so its right end stopped well short of the right edge.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LyricsRtlPlacementTest {
    private val widthPx = 400
    private val hebrew = "שלום עולם"
    private val arabic = "مرحبا بالعالم"
    private val english = "Hello world"

    /** A default for a value of [type]: false, zero, an empty text, or null. */
    private fun defaultOf(type: java.lang.reflect.Type): Any? = when (type) {
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
        java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
        java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
        java.lang.Float.TYPE, java.lang.Float::class.java -> 0f
        String::class.java -> ""
        else -> null
    }

    /** Answers every call of an interface with a default: flows of defaults, nested interfaces as more of the same. */
    private fun <T> fake(type: Class<T>): T = type.cast(java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        when {
            method.name == "equals" -> proxy === args?.getOrNull(0)
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "toString" -> "fake ${type.simpleName}"
            kotlinx.coroutines.flow.StateFlow::class.java.isAssignableFrom(method.returnType) -> {
                val generic = method.genericReturnType as? java.lang.reflect.ParameterizedType
                var argument = generic?.actualTypeArguments?.firstOrNull()
                if (argument is java.lang.reflect.WildcardType) argument = argument.upperBounds.first()
                kotlinx.coroutines.flow.MutableStateFlow(argument?.let(::defaultOf))
            }
            method.returnType.isInterface && method.returnType.name.startsWith("com.cosmotune") -> fake(method.returnType)
            else -> defaultOf(method.returnType)
        }
    })

    /** The player host the view asks its settings of, faked with defaults; the previous host is put back. */
    private fun <T> withPlayerHost(block: () -> T): T {
        val field = com.cosmotune.music.ui.player.PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = field.get(null)
        com.cosmotune.music.ui.player.PlayerPlatform.install(fake(com.cosmotune.music.ui.player.PlayerHost::class.java))
        try { return block() } finally { field.set(null, previous) }
    }

    /** The x range of pixels that are not the background, over the whole view, or null when nothing is drawn. */
    private fun inkRange(lines: List<LyricLine>, direction: LayoutDirection): IntRange? = withPlayerHost { inkRangeInstalled(lines, direction) }

    private fun inkRangeInstalled(lines: List<LyricLine>, direction: LayoutDirection): IntRange? {
        val colors = CosmoColors(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val type = CosmoType(400f, FontFamily.Default, FontFamily.Default)
        val playhead = LyricPlayhead(PlaybackPosition(), mutableIntStateOf(0))
        val scene = ImageComposeScene(width = widthPx, height = 600, density = Density(1f)) {
            CompositionLocalProvider(
                LocalLayoutDirection provides direction,
                LocalCosmoColors provides colors,
                LocalCosmoType provides type,
                LocalReducedMotion provides true,
            ) {
                Box(Modifier.fillMaxSize()) {
                    CosmoLyricsView(
                        lines = lines,
                        subLines = null,
                        trackKey = "rtl",
                        playhead = playhead,
                        isPlaying = false,
                        onSeekToLine = {},
                        style = LyricsStyle.Portrait,
                        topPadding = 0.dp,
                    )
                }
            }
        }
        scene.render(0)
        val image = scene.render(500_000_000L)
        val bitmap = Bitmap.makeFromImage(image)
        scene.close()
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if ((bitmap.getColor(x, y) ushr 24) > 120) {
                if (x < min) min = x
                if (x > max) max = x
            }
        }
        return if (max < min) null else min..max
    }

    /** A plain-text song (no timings), one line. */
    private fun plain(text: String, alignment: LyricAlignment = LyricAlignment.Start) = listOf(LyricLine(0L, text, alignment = alignment))

    private fun assertOnTheRight(range: IntRange?, what: String) {
        if (range == null) return // no font with the script on this machine: nothing to measure
        assertTrue(range.last >= widthPx - widthPx / 12, "$what ends against the right edge (${range.last} of $widthPx)")
        assertTrue(range.first > widthPx / 4, "$what does not start at the left (${range.first})")
    }

    private fun assertOnTheLeft(range: IntRange?, what: String) {
        requireNotNull(range) { "$what was drawn" }
        assertTrue(range.first <= widthPx / 12, "$what starts against the left edge (${range.first})")
        assertTrue(range.last < widthPx * 3 / 4, "$what does not run to the right (${range.last})")
    }

    @Test fun hebrewInAnEnglishAppStandsOnTheRight() = assertOnTheRight(inkRange(plain(hebrew), LayoutDirection.Ltr), "Hebrew")

    @Test fun arabicInAnEnglishAppStandsOnTheRight() = assertOnTheRight(inkRange(plain(arabic), LayoutDirection.Ltr), "Arabic")

    @Test fun englishInAHebrewAppStandsOnTheLeft() = assertOnTheLeft(inkRange(plain(english), LayoutDirection.Rtl), "English")

    @Test fun englishInAnEnglishAppStaysOnTheLeft() = assertOnTheLeft(inkRange(plain(english), LayoutDirection.Ltr), "English")

    @Test fun hebrewInAHebrewAppStandsOnTheRight() = assertOnTheRight(inkRange(plain(hebrew), LayoutDirection.Rtl), "Hebrew")

    @Test fun aHebrewDuetsSecondVoiceStandsOnTheLeft() {
        // The second voice of a duet goes to the far end of its own reading direction: the left, for Hebrew. Alone on a page
        // with no first voice, so the ink that reaches the left edge is the second voice's.
        val duet = listOf(LyricLine(0L, "שני קולות", alignment = LyricAlignment.End))
        val range = inkRange(duet, LayoutDirection.Ltr) ?: return
        assertTrue(range.first <= widthPx / 12, "the second voice starts against the left edge (${range.first})")
        assertTrue(range.last < widthPx * 3 / 4, "and does not reach the right (${range.last})")
    }
}
