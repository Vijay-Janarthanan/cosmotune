package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.reading.data.ReadingRepository
import com.cosmotune.reading.ui.ReadApp
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.ModeGround
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import com.cosmotune.ui.watch.ProvideWatch
import com.cosmotune.ui.watch.WatchApp
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.watch.FakeWatchRepository
import java.awt.image.BufferedImage
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Watch, Read and Manga paint no ground of their own: they stand on what their host lays down. On the phone the window under them
 * is black in both themes, so in the light theme their dark ink stood on black and could hardly be read. The phone's host now lays
 * [ModeGround] under them (PhoneModeHost), and this draws each mode the way the phone does, inside [ModeGround] over a black box
 * standing for the window and with no ground of the test's own, then checks that the ground that shows is one the mode's ink reads
 * on, in the dark and the light palettes.
 */
class ModeGroundRenderTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private val width = 390
    private val height = 844

    /** The mode drawn on the phone's black window, inside [ModeGround] when [ground]; returns the ink of its palette. */
    private fun draw(dark: Boolean, ground: Boolean = true, content: @Composable () -> Unit): Color {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        var ink = Color.Unspecified
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()),
                typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true,
                    com.cosmotune.manga.ui.LocalReadModeSwitcher provides { com.cosmotune.ui.kit.KitModePill(com.cosmotune.design.AppMode.READ, onClick = {}) }) {
                    ink = orbitLook().ink
                    // The phone's window: android:windowBackground is black in every theme.
                    Box(Modifier.size(width.dp, height.dp).testTag("phone:window").background(Color.Black)) {
                        if (ground) ModeGround { content() } else content()
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        return ink
    }

    private fun shot(): BufferedImage = compose.onNodeWithTag("phone:window").captureToImage().toAwtImage().let { image ->
        BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
    }

    /**
     * The ground as it shows: the commonest colour of the two leftmost columns of pixels over the lower two thirds of the page,
     * inside every page's side margin and below the headings, so cards and words do not count.
     */
    private fun groundOf(image: BufferedImage): Color {
        val counts = HashMap<Int, Int>()
        for (x in 0 until 2) for (y in image.height / 3 until image.height - 4) counts.merge(image.getRGB(x, y) or (0xFF shl 24), 1, Int::plus)
        return Color(counts.maxBy { it.value }.key)
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun assertInkReads(what: String, dark: Boolean, ink: Color) {
        val ground = groundOf(shot())
        val ratio = contrast(ground, ink)
        assertTrue(ratio >= 4.5, "$what in the ${if (dark) "dark" else "light"} theme: ink $ink on ground $ground is only ${"%.2f".format(ratio)}:1")
        // The ground is the theme's own, not the window's black showing through.
        assertTrue(if (dark) luminance(ground) < 0.1 else luminance(ground) > 0.6, "$what in the ${if (dark) "dark" else "light"} theme stands on $ground")
    }

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val uiField = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousPlayer = playerField.get(null); val previousUi = uiField.get(null)
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) { homeRecentsViewType.value = value }
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            @Composable override fun downloadedIds() = emptySet<String>()
        })
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getSettings" -> java.lang.reflect.Proxy.newProxyInstance(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java.classLoader, arrayOf(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java)) { _, _, _ -> MutableStateFlow(false) }
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi); WatchNav.go(WatchTab.HOME) }
    }

    private fun offlineBooks() = ReadingRepository(temporary.newFolder(), client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
        okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(503).message("Offline").body("".toResponseBody(null)).build()
    }.build())

    @Test fun watchPagesStandOnTheirThemesGround() = hosts {
        for (dark in listOf(false, true)) for (tab in listOf(WatchTab.HOME, WatchTab.LIVE, WatchTab.FILMS, WatchTab.PLUGINS)) {
            val repository = FakeWatchRepository()
            WatchNav.go(tab)
            val ink = draw(dark) { ProvideWatch(repository = repository) { WatchApp(showNavigation = true) } }
            assertInkReads("Watch $tab", dark, ink)
        }
    }

    @Test fun readAndMangaStandOnTheirThemesGround() {
        for (dark in listOf(false, true)) {
            ModeNav.mangaTab.value = "Library"
            val books = offlineBooks()
            assertInkReads("Read", dark, draw(dark) { ReadApp(repository = books, showNavigation = true) })
            val manga = MangaRepository(MangaStore(temporary.newFolder()))
            assertInkReads("Manga", dark, draw(dark) { MangaApp(repository = manga, showNavigation = true) })
        }
    }

    /** The test sees the fault it guards against: without the ground, a light page's ink stands on the window's black. */
    @Test fun withoutTheGroundTheLightInkStandsOnBlack() = hosts {
        WatchNav.go(WatchTab.HOME)
        val repository = FakeWatchRepository()
        val ink = draw(dark = false, ground = false) { ProvideWatch(repository = repository) { WatchApp(showNavigation = true) } }
        val ground = groundOf(shot())
        assertTrue(contrast(ground, ink) < 4.5, "the bare window is black ($ground), the light ink ($ink) is lost on it")
    }
}
