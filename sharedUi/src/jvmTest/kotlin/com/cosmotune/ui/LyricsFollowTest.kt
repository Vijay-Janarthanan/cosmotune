package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * The list follows the line being sung, drawn for real: a line-synced Tamil song (LRCLIB's timings), played through, keeps the
 * sung line near the list's anchor a third of the way down. Before the fix it slid to the bottom edge and out of view about
 * fifteen seconds after the first line.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LyricsFollowTest {
    private val widthPx = 824
    private val heightPx = 900
    private val frameNanos = 16_000_000L

    private val tamil = listOf(
        35540L to "கண்ணான கண்ணே",
        37250L to "கண்ணான கண்ணே",
        38970L to "என் மீது சாய வா",
        42670L to "புண்னான நெஞ்சை",
        44840L to "பொன்னான கையால்",
        46570L to "பூ போல நீவ வா",
        50570L to "நான் காத்து நின்றேன்",
        52380L to "காலங்கள் தோறும்",
        54030L to "என் ஏக்கம் தீருமா",
        57730L to "நான் பார்த்து நின்றேன்",
        59680L to "பொன் வானம் எங்கும்",
        61560L to "என் மின்னல் தோன்றுமா",
        64120L to "தண்ணீராய் மேகம் தூறும்",
        66870L to "கண்ணீர் சேரும்",
    ).map { (ms, text) -> LyricLine(ms, text) }

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


    /** Where the accent-filled ink (the line being sung) sits, as a y range, after each line has played a second; null when none is on screen. */
    private fun sungRows(lines: List<LyricLine>, first: List<LyricLine> = lines, replaceAt: Int = 0): List<IntRange?> = withPlayerHost {
        val brand = CosmoBrandPalettes.of(true)
        val colors = CosmoColors(CosmoPaletteState(brand, true))
        val accent = brand.accentStrong
        val type = CosmoType(400f, FontFamily.Default, FontFamily.Default)
        val main = Queue()
        val shown = androidx.compose.runtime.mutableStateOf(if (replaceAt > 0) first else lines)
        val position = PlaybackPosition()
        val playhead = LyricPlayhead(position, mutableIntStateOf(0))
        val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(2f), coroutineContext = main + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> throw AssertionError("the lyrics view failed", e) }) {
            CompositionLocalProvider(
                LocalCosmoColors provides colors,
                LocalCosmoType provides type,
                LocalReducedMotion provides false,
                androidx.lifecycle.compose.LocalLifecycleOwner provides Still,
            ) {
                Box(Modifier.fillMaxSize()) {
                    CosmoLyricsView(shown.value, null, "follow", playhead, isPlaying = false, onSeekToLine = {}, style = LyricsStyle.Portrait, topPadding = 0.dp)
                }
            }
        }
        var now = 0L
        fun frame(): org.jetbrains.skia.Image { main.drain(); return scene.render(now).also { main.drain() } }
        frame()
        val out = ArrayList<IntRange?>()
        for ((index, line) in lines.withIndex()) {
            if (index == replaceAt) shown.value = lines
            for (step in 0 until 75) {
                position.report(line.timeMs + step * 16L)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                now += frameNanos
                if (step < 74) frame()
            }
            now += frameNanos
            val image = frame()
            val bitmap = Bitmap.makeFromImage(image)
            var top = Int.MAX_VALUE
            var bottom = Int.MIN_VALUE
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width step 2) {
                val c = bitmap.getColor(x, y)
                if (close(c, accent)) { if (y < top) top = y; if (y > bottom) bottom = y }
            }
            out += if (bottom < top) null else top..bottom
        }
        scene.close()
        out
    }

    /** The main thread: work handed to it waits for the next frame, as it would on a phone, rather than running inside the one in progress. */
    private class Queue : kotlinx.coroutines.CoroutineDispatcher() {
        private val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { var n = 0; while (n++ < 10_000) (tasks.poll() ?: return).run() }
    }

    /** A lifecycle that is over: settings read with a lifecycle keep their first value, and nothing waits on a main thread. */
    private object Still : androidx.lifecycle.LifecycleOwner {
        override val lifecycle: androidx.lifecycle.Lifecycle = object : androidx.lifecycle.Lifecycle() {
            override val currentState: State get() = State.DESTROYED
            override fun addObserver(observer: androidx.lifecycle.LifecycleObserver) {}
            override fun removeObserver(observer: androidx.lifecycle.LifecycleObserver) {}
        }
    }

    private fun close(c: Int, target: Int): Boolean {
        if ((c ushr 24) < 200) return false
        fun ch(v: Int, s: Int) = (v ushr s) and 0xFF
        return listOf(16, 8, 0).all { kotlin.math.abs(ch(c, it) - ch(target, it)) < 28 }
    }

    private fun assertFollowed(rows: List<IntRange?>) {
        rows.forEachIndexed { index, range ->
            requireNotNull(range) { "line $index is on screen (${rows})" }
            assertTrue(range.last < heightPx * 2 / 3, "line $index sits near the anchor, not at the bottom ($range of $heightPx; $rows)")
        }
    }

    @Test
    fun aLineSyncedTamilSongKeepsTheSungLineInView() = assertFollowed(sungRows(tamil))

    /**
     * The lyrics of the playing song are replaced by another source's (here a romanised file with two lines to each of these)
     * while it plays. The list followed the first lines' numbering for the rest of the song and fell a line further behind at
     * each one, until no sung line was on screen.
     */
    @Test
    fun theSungLineStaysInViewWhenTheLyricsAreReplacedMidSong() {
        val paired = tamil.chunked(2).map { pair -> LyricLine(pair.first().timeMs, pair.joinToString(", ") { "line at ${it.timeMs}" }) }
        assertFollowed(sungRows(tamil, first = paired, replaceAt = 4))
    }
}
