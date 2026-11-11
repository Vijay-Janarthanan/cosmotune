package com.cosmotune.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.account.FakeVideoAccount
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.blocks.LocalThumbnailStandIn
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.MainCoroutineDispatcher

/**
 * What the screen previews have in common: the theme's colours and type built without the app's host, a thumbnail stand-in that paints
 * a distinct gradient with the video's title for every picture address of the fakes (so a PNG looks like a real feed), and the render
 * of a scene to `build/ui-preview/<name>.png`.
 */
internal object PreviewKit {
    /** The chrome's palette (the One palette), built the way the theme builds it; `CosmoColors` and its state are internal to `:sharedUi`, so by reflection. */
    fun colors(dark: Boolean): CosmoColors {
        val constructor = CosmoColors::class.java.declaredConstructors.single { it.parameterCount == 1 }
        constructor.isAccessible = true
        return constructor.newInstance(CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark), dark)) as CosmoColors
    }

    fun type(widthDp: Int): CosmoType {
        val constructor = CosmoType::class.java.declaredConstructors.single { it.parameterCount == 3 }
        constructor.isAccessible = true
        return constructor.newInstance(widthDp.toFloat(), FontFamily.Default, FontFamily.Default) as CosmoType
    }

    private val titleByAddress: Map<String, String> =
        (FakeVideoSource.videos + FakeVideoSource.shorts + FakeVideoSource.playlistItems + FakeVideoAccount.VIDEOS)
            .mapNotNull { item -> item.thumbnailUrl?.let { it to item.title } }
            .toMap()

    /** A gradient whose hue follows the address, with the video's title in white at its foot. */
    val standIn: @Composable (String, Modifier) -> Unit = { url, modifier ->
        val hue = Math.floorMod(url.hashCode() * 31, 360).toFloat()
        val brush = Brush.linearGradient(listOf(Color.hsv(hue, 0.62f, 0.80f), Color.hsv((hue + 55f) % 360f, 0.70f, 0.36f)))
        Box(modifier.background(brush), contentAlignment = Alignment.BottomStart) {
            Text(
                text = titleByAddress[url].orEmpty(),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }

    /** Draws [content] in a [widthDp] x [heightDp] scene over [providers], on the page's flat ground, and writes `build/ui-preview/[name].png`. */
    @OptIn(ExperimentalComposeUiApi::class)
    fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        dark: Boolean,
        frames: Int = 60,
        providers: Array<androidx.compose.runtime.ProvidedValue<*>> = emptyArray(),
        outDir: String = "build/ui-preview",
        content: @Composable () -> Unit,
    ) {
        RenderThread.begin()
        // The scene's effects run on the render thread too, so a page that resumes after a load writes its state between frames.
        val scene = ImageComposeScene(widthDp, heightDp, Density(1f), coroutineContext = RenderThread) {
            val colors = colors(dark)
            MaterialTheme {
                CompositionLocalProvider(
                    LocalCosmoColors provides colors,
                    LocalCosmoType provides type(widthDp),
                    LocalThumbnailStandIn provides standIn,
                    *providers,
                ) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
        var image: org.jetbrains.skia.Image? = null
        // frames over a while: the resources (strings, fonts) load on other threads
        repeat(frames) {
            RenderThread.drain()
            image = scene.render(it * 50_000_000L)
            Thread.sleep(25)
        }
        RenderThread.drain()
        val bytes = image!!.encodeToData(EncodedImageFormat.PNG)!!.bytes
        val bitmap = image!!.toComposeImageBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels)
        val distinct = pixels.asSequence().distinct().take(64).count()
        scene.close()
        RenderThread.end()
        File(outDir).mkdirs()
        File("$outDir/$name.png").writeBytes(bytes)
        check(bytes.size > 4_000 && distinct > 8) { "$name is blank (${bytes.size} bytes, $distinct colours)" }
    }
}

/**
 * The UI thread of a render: the thread that renders the scene. Work dispatched here from another thread (a page's load coming back
 * from Dispatchers.Default, a resource read) waits in a queue and runs between frames ([drain]); work already on the render thread
 * runs at once, as Dispatchers.Main.immediate does. A test that sets it as the main dispatcher has its pages write their state where
 * the scene lays them out, never on a worker beside a frame (which failed inside Compose's lazy lists). Outside a render, nothing runs
 * and what was queued is dropped at the next one.
 */
object RenderThread : MainCoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()

    @Volatile private var owner: Thread? = null

    override val immediate: MainCoroutineDispatcher get() = this

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread() !== owner

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
    }

    /** Makes the calling thread the render thread, with nothing left over from an earlier render. */
    fun begin() {
        queue.clear()
        owner = Thread.currentThread()
    }

    /** Runs what other threads handed over since the last frame, and what that hands over in turn. */
    fun drain() {
        check(Thread.currentThread() === owner) { "drained off the render thread" }
        while (true) (queue.poll() ?: return).run()
    }

    /** Ends the render: later work waits for the next one, which drops it. */
    fun end() {
        owner = null
    }
}
