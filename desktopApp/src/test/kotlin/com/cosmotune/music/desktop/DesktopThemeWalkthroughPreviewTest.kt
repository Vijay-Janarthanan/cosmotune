package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.desktop.CosmoDesktopNavItem
import com.cosmotune.desktop.CosmoDesktopPlaylistItem
import com.cosmotune.desktop.LocalCosmoPlaying
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryPage
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PartyUi
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.QueueSidePanel
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.LocalThemeChoice
import com.cosmotune.ui.theme.ThemeChoice
import com.cosmotune.ui.theme.ThemeChoiceHost
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Not an assertion of the look: the desktop's main pages put together as the window frames them (the sidebar, the top bar, the page,
 * the floating player bar) at 1440 x 900, in the light and the dark theme, written under the worktree's build/theme for a person to
 * look at. Run with a `--tests` filter only. The look settings are a store of the test's own; the desktop's appearance settings are
 * read (never written).
 */
@OptIn(ExperimentalMaterial3Api::class)
class DesktopThemeWalkthroughPreviewTest {
    private val out = File("../build/theme/walkthrough").apply { mkdirs() }
    private val tag = System.getProperty("cosmotune.theme.tag") ?: "after"

    private fun cover(name: String, r: Int, g: Int, b: Int): String {
        val file = File(out, "covers/$name.png").apply { parentFile.mkdirs() }
        val image = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = java.awt.Color(r, g, b); fillRect(0, 0, 300, 300)
            color = java.awt.Color(minOf(r + 70, 255), minOf(g + 70, 255), minOf(b + 70, 255)); fillOval(60, 60, 180, 180)
            dispose()
        }
        ImageIO.write(image, "png", file)
        return file.path
    }

    private val art by lazy { listOf(cover("w1", 168, 92, 40), cover("w2", 30, 120, 116), cover("w3", 120, 60, 140), cover("w4", 60, 110, 40), cover("w5", 150, 40, 70)) }
    private val song by lazy { Song("np", "Golden Hour", "Mira and the Coast", art[0], "4:05", albumName = "Lantern Season") }
    private val queue by lazy {
        listOf(
            song,
            Song("u1", "Low Tide Letters", "Harbour Lights", art[1], "3:32"),
            Song("c1", "Lantern Season", "Mira and the Coast", art[2], "3:18"),
            Song("c2", "Paper Boats", "Mira and the Coast", art[3], "2:57"),
        )
    }

    private fun items(prefix: String, count: Int, songs: Boolean = false) = List(count) { i ->
        ShelfItem("$prefix ${i + 1}", "Fixture artist", art[i % art.size], if (songs) "v$prefix$i" else null, if (songs) null else "MPRE$prefix$i")
    }

    /** The hosts the shared pages ask for: the cover's address, settings at their defaults, no party, no downloads. */
    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val uiField = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousPlayer = playerField.get(null)
        val previousUi = uiField.get(null)
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) { homeRecentsViewType.value = value }
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            @Composable override fun downloadedIds() = emptySet<String>()
        })
        PlayerPlatform.install(stub(PlayerHost::class.java) { method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getParty" -> MutableStateFlow(PartyUi())
                "getSyncedLyrics" -> MutableStateFlow(true)
                "getLevel" -> MutableStateFlow(0.6f)
                else -> NOT_SET
            }
        })
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi) }
    }


    private object NOT_SET
    private val noop0: () -> Unit = {}
    private val noop1: (Any?) -> Unit = {}

    /**
     * An implementation of [type] that answers what a page asks: [answer] first, then a default by the return type (a flow of the
     * type's own default, an empty collection, false, zero, the first constant of an enum, a do-nothing function, a stub of an
     * interface, or a value made from a class's defaults). Nothing is stored and nothing reaches the desktop's settings.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, answer: (java.lang.reflect.Method, Array<Any?>?) -> Any? = { _, _ -> NOT_SET }): T =
        java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> return@newProxyInstance proxy === args?.getOrNull(0)
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "toString" -> return@newProxyInstance "stub of ${type.simpleName}"
            }
            val given = answer(method, args)
            if (given !== NOT_SET) return@newProxyInstance given
            // A suspending call answers nothing (null) at once.
            if (method.parameterTypes.lastOrNull() == kotlin.coroutines.Continuation::class.java) return@newProxyInstance null
            if (method.isDefault) return@newProxyInstance java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, *(args ?: emptyArray()))
            defaultOf(method.returnType, method.genericReturnType, answer)
        } as T

    private fun defaultOf(raw: Class<*>, generic: java.lang.reflect.Type, answer: (java.lang.reflect.Method, Array<Any?>?) -> Any?): Any? {
        fun argument(): Pair<Class<*>, java.lang.reflect.Type>? {
            val arg = (generic as? java.lang.reflect.ParameterizedType)?.actualTypeArguments?.firstOrNull() ?: return null
            val bare = when (arg) {
                is Class<*> -> arg
                is java.lang.reflect.ParameterizedType -> arg.rawType as Class<*>
                is java.lang.reflect.WildcardType -> (arg.upperBounds.firstOrNull() as? Class<*>) ?: Any::class.java
                else -> Any::class.java
            }
            return bare to arg
        }
        return when {
            raw == Void.TYPE || raw == Unit::class.java -> Unit
            kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(raw) ->
                MutableStateFlow(argument()?.let { (bare, type) -> defaultOf(bare, type, answer) })
            raw == java.lang.Boolean.TYPE || raw == java.lang.Boolean::class.java -> false
            raw == Integer.TYPE || raw == Integer::class.java -> 0
            raw == java.lang.Long.TYPE || raw == java.lang.Long::class.java -> 0L
            raw == java.lang.Float.TYPE || raw == java.lang.Float::class.java -> 0f
            raw == java.lang.Double.TYPE || raw == java.lang.Double::class.java -> 0.0
            raw == String::class.java -> ""
            List::class.java.isAssignableFrom(raw) -> emptyList<Any>()
            Set::class.java.isAssignableFrom(raw) -> emptySet<Any>()
            Map::class.java.isAssignableFrom(raw) -> emptyMap<Any, Any>()
            raw.isEnum -> raw.enumConstants.first()
            raw == kotlin.jvm.functions.Function0::class.java -> noop0
            raw == kotlin.jvm.functions.Function1::class.java -> noop1
            raw.isInterface -> stub(raw, answer)
            else -> runCatching { raw.getDeclaredConstructor().newInstance() }.getOrNull()
        }
    }

    /** A theme choice of the test's own, so the account popover's switch shows. */
    private class Choice(private val value: ThemeChoice) : ThemeChoiceHost {
        @Composable override fun collect() = value
        override fun choose(choice: ThemeChoice) = Unit
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) = runBlocking(Dispatchers.Swing) {
        val width = 1440
        val height = 900
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        val scene = ImageComposeScene(width, height, Density(1f), coroutineContext = Dispatchers.Swing) {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val shell = remember {
                val store = object : KeyValueStore {
                    override fun getBoolean(key: String, default: Boolean) = default
                    override fun putBoolean(key: String, value: Boolean) = Unit
                }
                CosmoShellState::class.java.getConstructor(CosmoLookSettings::class.java, Boolean::class.javaPrimitiveType)
                    .newInstance(CosmoLookSettings(store), dark)
            }
            val type = rememberCosmoType()
            val base = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(base), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                val lifecycleOwner = remember {
                    object : androidx.lifecycle.LifecycleOwner {
                        override val lifecycle = androidx.lifecycle.LifecycleRegistry.createUnsafe(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
                    }
                }
                CompositionLocalProvider(
                    LocalCosmoColors provides colors, LocalCosmoType provides type, LocalCosmoShell provides shell, LocalReducedMotion provides true,
                    LocalCosmoPlaying provides true, androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner,
                    // As the app provides it: the look's ink, white on the dark theme (DesktopApp's own override).
                    LocalContentColor provides (if (dark) Color.White else Color(colors.palette.textPrimary)),
                    LocalThemeChoice provides Choice(if (dark) ThemeChoice.DARK else ThemeChoice.LIGHT),
                ) { content() }
            }
        }
        var bytes: ByteArray? = null
        try {
            repeat(24) {
                bytes = scene.render(it * 100_000_000L).encodeToData(EncodedImageFormat.PNG)?.bytes
                delay(40)
            }
        } finally {
            scene.close()
        }
        File(out, "$name-${if (dark) "dark" else "light"}-$tag.png").writeBytes(bytes!!)
        assertTrue(bytes!!.size > 5_000)
    }

    private val nav get() = listOf(
        CosmoDesktopNavItem(CosmoIcons.Home, CosmoIcons.HomeFill, "Home", selected = true) {},
        CosmoDesktopNavItem(CosmoIcons.Explore, CosmoIcons.ExploreFill, "Explore", selected = false) {},
        CosmoDesktopNavItem(CosmoIcons.Bell, CosmoIcons.BellFill, "Releases", selected = false) {},
        CosmoDesktopNavItem(CosmoIcons.Library, CosmoIcons.LibraryFill, "Library", selected = false) {},
    )

    private val playlists get() = listOf(
        CosmoDesktopPlaylistItem("Liked songs", "214 songs", art[0], selected = false) {},
        CosmoDesktopPlaylistItem("Late drives", "38 songs", art[1], selected = false) {},
        CosmoDesktopPlaylistItem("Morning ferry", "22 songs", art[2], selected = false) {},
    )

    /** The window as DesktopFrame lays it out in the new look: the ambient ground, the sidebar, the top bar over the page, the bar. */
    @Composable
    private fun Frame(panel: Boolean = false, trailing: @Composable () -> Unit = {}, overlay: @Composable () -> Unit = {}, page: @Composable (PaddingValues) -> Unit) {
        Box(Modifier.fillMaxSize()) {
            com.cosmotune.ui.theme.AmbientBackground(Modifier.fillMaxSize())
            Row(Modifier.fillMaxSize()) {
                com.cosmotune.desktop.CosmoDesktopSidebar(
                    nav = nav, playlists = playlists, settingsSelected = false, onOpenSettings = {}, query = "", onQueryChange = {},
                    onSearch = {}, focusSearch = false, onSearchFocused = {},
                )
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    com.cosmotune.desktop.CosmoDesktopTopBar(compact = false, canGoBack = true, onBack = {}, accountAvatar = null, onOpenAccounts = {}, accountName = "Vijay")
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { page(PaddingValues(bottom = 120.dp)) }
                        trailing()
                    }
                }
            }
            com.cosmotune.desktop.CosmoDesktopPlayerBarHost(panel) { modifier ->
                com.cosmotune.ui.shell.CosmoPlayerBar(
                    song = song, isPlaying = true, isLoading = false, position = PlaybackPosition().apply { report(84_000L); advancing = false },
                    durationMs = 245_000L, liked = true, shuffle = false, repeatMode = 0, controlsLocked = false, lyricsOn = false, queueOn = panel,
                    onPlayPause = {}, onPrevious = {}, onNext = {}, onToggleShuffle = {}, onCycleRepeat = {}, onToggleLike = {}, onSeekFraction = {},
                    onLyrics = {}, onQueue = {}, onOutput = {}, onExpand = {}, onBlockedControl = {}, hazeState = null, modifier = modifier,
                )
            }
            overlay()
        }
    }

    private val themes = listOf(true, false)

    @Test fun homeAndLibraryAndTheSidePanel() = hosts {
        for (dark in themes) {
            render("home", dark) {
                Frame { padding ->
                    com.cosmotune.ui.home.OrbitHome(
                        state = UiState.Success(listOf(
                            HomeShelf("Recents", items("Recent", 8)),
                            HomeShelf("Your mixes", items("Mix", 8), subtitle = "Made for you"),
                            HomeShelf("Quick picks", items("Pick", 12, songs = true)),
                        )),
                        listState = rememberLazyListState(), onItemClick = { _, _ -> }, onRetry = {}, refreshing = false, onRefresh = {},
                        pullState = rememberPullToRefreshState(), contentPadding = padding, currentSong = song, isPlaying = true,
                        quickLinks = listOf(com.cosmotune.ui.home.HomeQuickLink("Liked songs", CosmoIcons.Heart) {}, com.cosmotune.ui.home.HomeQuickLink("Downloads", CosmoIcons.Download) {}),
                    )
                }
            }
            render("library", dark) {
                Frame { padding ->
                    com.cosmotune.ui.library.CosmoLibrary(
                        signedIn = true,
                        state = UiState.Success(LibraryPage(emptyList(), emptyList(), listOf(HomeShelf("Playlists", items("Playlist", 8)), HomeShelf("Albums", items("Album", 8))))),
                        listState = rememberLazyListState(), onShelfItemClick = {}, onShelfItemLongPress = {}, onNewPlaylist = {}, onShowAll = {},
                        replay = {}, onSignIn = {}, onRetry = {}, refreshing = false, onRefresh = {}, pullState = rememberPullToRefreshState(),
                        contentPadding = padding, links = emptyList(), deviceItems = emptyList(), sourceShelves = emptyList(),
                    )
                }
            }
            render("side-panel", dark) {
                Frame(panel = true, trailing = {
                    DesktopSidePanelColumn(panel = DesktopSidePanel.QUEUE, onClose = {}) {
                        QueueSidePanel(
                            queue = queue, queueIndex = 0, autoplayEnabled = true, onJumpTo = {}, onRemove = {}, onMove = { _, _ -> },
                            onClear = {}, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }) { padding ->
                    com.cosmotune.ui.home.OrbitHome(
                        state = UiState.Success(listOf(HomeShelf("Recents", items("Recent", 8)))),
                        listState = rememberLazyListState(), onItemClick = { _, _ -> }, onRetry = {}, refreshing = false, onRefresh = {},
                        pullState = rememberPullToRefreshState(), contentPadding = padding,
                    )
                }
            }
        }
    }

    @Test fun theAppearanceSettingsAndTheAccountPopover() = hosts {
        val accounts = listOf(
            DesktopGoogleAccount("a1", "Vijay", "vijay@example.com", profiles = listOf(DesktopYouTubeProfile("p1", "Vijay", "@vijay"), DesktopYouTubeProfile("p2", "Night Shift Radio", "@nightshift", isBrandAccount = true))),
        )
        for (dark in themes) {
            render("settings-appearance", dark) {
                Frame { padding ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp).padding(padding).widthIn(max = 760.dp)) {
                        com.cosmotune.ui.settings.CosmoSettingsGroup(header = "Appearance") {
                            DesktopThemeRows { _, _ -> true }
                            com.cosmotune.desktop.CosmoLookDesktopToggles { title, subtitle, checked, onChange -> SettingsToggle(title, subtitle, checked, onChange) }
                        }
                    }
                }
            }
            render("account-popover", dark) {
                Frame(overlay = {
                    DesktopAccountSelector(accounts, "a1", "p1", busy = false, onSelect = { _, _ -> }, onAddAccount = {}, onRemoveAccount = {}, onOpenSettings = {}, onDismiss = {})
                }) { padding ->
                    com.cosmotune.ui.home.OrbitHome(
                        state = UiState.Success(listOf(HomeShelf("Recents", items("Recent", 8)))),
                        listState = rememberLazyListState(), onItemClick = { _, _ -> }, onRetry = {}, refreshing = false, onRefresh = {},
                        pullState = rememberPullToRefreshState(), contentPadding = padding,
                    )
                }
            }
        }
    }

    @Test fun thePlaylistDialogs() = hosts {
        val local = listOf(DesktopPlaylist(title = "Late drives", songs = queue), DesktopPlaylist(title = "Morning ferry", songs = queue.take(2)))
        for (dark in themes) {
            render("dialog-add-to-playlist", dark) {
                Frame(overlay = {
                    DesktopPlaylistDialog(
                        song = song, accountPlaylists = emptyList(), localPlaylists = local, signedIn = false, canUseAccount = false, busy = false,
                        error = null, onPickAccount = {}, onPickLocal = {}, onCreate = { _, _ -> }, onDismiss = {},
                    )
                }) { padding ->
                    com.cosmotune.ui.home.OrbitHome(
                        state = UiState.Success(listOf(HomeShelf("Recents", items("Recent", 8)))),
                        listState = rememberLazyListState(), onItemClick = { _, _ -> }, onRetry = {}, refreshing = false, onRefresh = {},
                        pullState = rememberPullToRefreshState(), contentPadding = padding,
                    )
                }
            }
            render("dialog-new-playlist", dark) {
                Frame(overlay = {
                    DesktopPlaylistDialog(
                        song = null, accountPlaylists = emptyList(), localPlaylists = local, signedIn = true, canUseAccount = true, busy = false,
                        error = null, onPickAccount = {}, onPickLocal = {}, onCreate = { _, _ -> }, onDismiss = {},
                    )
                }) { padding ->
                    com.cosmotune.ui.home.OrbitHome(
                        state = UiState.Success(listOf(HomeShelf("Recents", items("Recent", 8)))),
                        listState = rememberLazyListState(), onItemClick = { _, _ -> }, onRetry = {}, refreshing = false, onRefresh = {},
                        pullState = rememberPullToRefreshState(), contentPadding = padding,
                    )
                }
            }
        }
    }

    @Test fun watchHome() = hosts {
        val repository = com.cosmotune.watch.FakeWatchRepository(now = System::currentTimeMillis)
        for (dark in themes) {
            render("watch-home", dark) {
                Box(Modifier.fillMaxSize()) {
                    com.cosmotune.ui.theme.AmbientBackground(Modifier.fillMaxSize())
                    com.cosmotune.ui.watch.ProvideWatch(repository = repository) {
                        com.cosmotune.ui.watch.WatchApp(showNavigation = false, wideLayout = true, contentPadding = PaddingValues(top = 56.dp, start = 220.dp))
                    }
                }
            }
        }
    }

    @Test fun nowPlaying() = hosts {
        for (dark in themes) {
            render("now-playing", dark) {
                Box(Modifier.fillMaxSize()) {
                    com.cosmotune.ui.nowplaying.CosmoNowPlaying(
                        song = song, isPlaying = true, isLoading = false, position = PlaybackPosition().apply { report(84_000L); advancing = false },
                        durationMs = 245_000L, audioVersionSwitching = false, qualityUpgraded = false, queue = queue, queueIndex = 0,
                        hasPrevious = false, hasNext = true, repeatMode = 0, shuffleEnabled = false, autoplayEnabled = true, signedIn = true,
                        accountName = "Vijay", likeStatus = com.cosmotune.music.data.model.LikeStatus.LIKE, onToggleLike = {}, onPlayPause = {},
                        onNext = {}, onPrevious = {}, onBlockedControl = {}, onSeek = {}, onSeekFraction = {}, onToggleShuffle = {}, onCycleRepeat = {},
                        onToggleAutoplay = {}, onJumpTo = {}, onRemoveFromQueue = {}, onMoveInQueue = { _, _ -> }, onClearQueue = {}, onOpenMenu = {},
                        onOpenAlbum = {}, onOpenArtist = { _, _ -> }, onOpenPlaybackSource = {}, onListenTogether = {}, lyrics = null, lyricsSource = null,
                        lyricsProviderStates = emptyMap(), onSelectLyricsProvider = {}, lyricsUnavailable = true, lyricsOffsetOpen = false,
                        onDismissLyricsOffset = {}, windowWidth = 1440.dp, windowHeight = 900.dp,
                    )
                }
            }
        }
    }
}
