package com.cosmotune.music.desktop

import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.data.DebugLog
import com.cosmotune.music.data.TrackLog
import com.cosmotune.music.data.canvas.OpenCanvasProvider
import com.cosmotune.music.data.innertube.InnerTubeXResolver
import com.cosmotune.music.data.innertube.StreamResolver
import com.cosmotune.music.data.innertube.potoken.PoTokenGenerator
import com.cosmotune.music.data.lyrics.LyricsTranslation
import com.cosmotune.music.data.spotify.SpotifyLibrary
import com.cosmotune.music.data.spotify.SharedPlaylistLink
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cosmotune.desktopapp.generated.resources.Res
import cosmotune.desktopapp.generated.resources.logo
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

internal val desktopSharedPlaylist = MutableStateFlow<String?>(null)

private fun offerDesktopLink(link: String) {
    val playlist = SharedPlaylistLink.fromOpenUri(link)
    if (playlist != null) desktopSharedPlaylist.value = playlist
    else com.cosmotune.desktop.DesktopWatch.offer(link)
}

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--provider-auth-browser"))) { ProviderVerificationBrowserMain.run(); return }
    // A Watch add-on link the system opened Cosmotune with (stremio://..., cosmotune://watch/...): its Install sheet waits.
    val openLink = args.firstOrNull {
        com.cosmotune.watch.InstallLinks.isInstallLink(it) || com.cosmotune.watch.InstallLinks.isPackLink(it) ||
            SharedPlaylistLink.fromOpenUri(it) != null
    }
    openLink?.let(::offerDesktopLink)
    // macOS hands URL-scheme opens (the bundle declares stremio:// and cosmotune://) to a running app through this handler.
    runCatching {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_OPEN_URI)) {
            java.awt.Desktop.getDesktop().setOpenURIHandler { event -> offerDesktopLink(event.uri.toString()) }
        }
    }
    // Cosmotune already running: the link goes to that process (its Install sheet opens there) and this one ends.
    val instance = com.cosmotune.desktop.DesktopSingleInstance.acquire(
        link = openLink,
        onLink = ::offerDesktopLink,
        onActivate = DesktopWindowVisibility::show,
    ) ?: return
    // An exception nobody caught used to leave no line anywhere: a window that froze mid-song had nothing to read afterwards.
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        com.cosmotune.diagnostics.CrashNotes.record(failure)
        DesktopTrackLog.log("uncaught on '${thread.name}': ${failure.javaClass.name}: ${failure.message}\n" +
            failure.stackTrace.take(30).joinToString("\n") { "    at $it" })
    }
    com.cosmotune.diagnostics.CrashNotes.configure(com.cosmotune.desktop.DesktopWatch.dataDir.parentFile)
    val shutdown = Thread({ instance.close() }, "cosmotune-instance-cleanup")
    Runtime.getRuntime().addShutdownHook(shutdown)
    try {
        instance.use { runDesktop() }
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
    }
}

private fun runDesktop() {
    com.cosmotune.providers.ProviderMediaTools.installed = DesktopProviderMediaTools
    DesktopProviderVerificationBrowser.install()
    DesktopMangaVerificationBrowser.install()
    com.cosmotune.manga.ui.MangaTextPlatform.language = DesktopStrings.language
    com.cosmotune.manga.ui.MangaTextPlatform.lookup = { key, fallback -> DesktopStrings[key, fallback] }
    com.cosmotune.manga.ui.DesktopMangaLibrary.startUpdates()
    Runtime.getRuntime().addShutdownHook(Thread({ com.cosmotune.manga.ui.DesktopMangaLibrary.stopUpdates() }, "Manga updates shutdown"))
    com.cosmotune.desktop.TorConnection.install()
    // The listener's proxy and offline mode (Extras), right after Tor has chosen the routing.
    DesktopExtras.install()
    coil3.SingletonImageLoader.setSafe(DesktopArtworkCache::imageLoader)
    com.cosmotune.ui.pages.ArtistVisualSettings.install(DesktopSignatureSettings.enabled, DesktopSignatureSettings.url,
        java.io.File(com.cosmotune.desktop.desktopListeningFile().parentFile, "artist-visuals"))
    // Before any WebView exists, so the sign-in window can start over with an empty jar.
    DesktopWebCookies.install()
    DesktopWindowVisibility.install()
    // The player is the phone's, from the shared UI module; this is what it reads underneath.
    PlayerPlatform.install(DesktopPlayerHost)
    com.cosmotune.ui.sleep.SleepTimerUi.control = DesktopSleepTimerControl
    com.cosmotune.music.ui.AppUi.install(DesktopAppUiHost)
    // The data layer both apps share logs through here, and keeps translated
    // lyrics beside the rest of the desktop's cache.
    DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    LyricsTranslation.cacheDir = DesktopMediaCache.directory.toFile()
    // The last content of every main page, shown at once on the next start while the page refreshes (PageCache).
    com.cosmotune.cache.PageCache.configure(DesktopMediaCache.directory.resolveSibling("pages").toFile())
    com.cosmotune.audio.LibraryLoudness.attach(com.cosmotune.audio.LibraryAnalysisStore.beside(com.cosmotune.desktop.desktopListeningFile()))
    // Listens kept while offline in an earlier session.
    DesktopScrobbling.flushPending()
    com.cosmotune.music.data.recommendations.LocalRecommendations.configure(
        DesktopMediaCache.directory.resolve("recommendations").toFile(), desktopRecommendationData())
    // LK-06: the Last.fm radio feed exists only while the listener opted in and is signed in.
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
        kotlinx.coroutines.flow.combine(DesktopScrobbleSettings.lastfmRecommendations, DesktopScrobbleSettings.lastfmSessionKey,
            DesktopScrobbleSettings.lastfmUsername, DesktopScrobbleSettings.lastfmApiKey) { optedIn, session, user, _ ->
            com.cosmotune.music.data.recommendations.LastFmDiscovery.configured(optedIn, session, user, DesktopScrobbling.discoveryApiKey)
        }.collect { com.cosmotune.music.data.recommendations.LocalRecommendations.useLastFm(it) }
    }
    DesktopDownloadQueue.restore(java.io.File(desktopRecommendationData().parentFile, "downloads/queue.json"))
    com.cosmotune.providers.ProviderHub.configure(java.io.File(desktopRecommendationData().parentFile, "providers"), cache = DesktopMediaCache.directory.resolve("provider-artwork").toFile())
    // Where cloud sync remembers how far it got; the session itself is in the credential store.
    DesktopCloud.configure(java.io.File(desktopRecommendationData().parentFile, "cloud"))
    // The Spotify library both apps share, on the tokens the web player mints in JavaFX.
    SpotifyLibrary.auth = SpotifyLibrary.Auth {
        DesktopSpotifyToken.accessToken()?.let { SpotifyLibrary.Tokens(it, DesktopSpotifyToken.clientToken()) }
    }
    // Desktop follows its own language picker (zh-Hant / zh-Hans preserved).
    com.cosmotune.music.data.innertube.Innertube.appLanguage = { DesktopStrings.resolvedTag() }
    // YouTube playback is the phone's StreamResolver over InnerTubeX, with BotGuard PoTokens
    // minted in JavaFX's WebView where the phone uses Android's.
    TrackLog.echo = { level, tag, message, error ->
        DesktopTrackLog.log("$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
    }
    StreamResolver.maxKbps = {
        DesktopStreamClient.ceiling.get() ?: DesktopSourceRegistry.ceiling(null).maxKbps
    }
    InnerTubeXResolver.init(
        filesDir = DesktopMediaCache.directory.toFile(),
        store = DesktopInnerTubeXStore,
        poTokenProvider = PoTokenGenerator(
            createMinter = { DesktopPoTokenWebView.getNewPoTokenGenerator() },
            available = { DesktopPoTokenWebView.available },
        ).asTokenProvider(),
    )
    // Where the music-video canvas keeps the song-to-video maps it measured.
    OpenCanvasProvider.configure(DesktopMediaCache.directory.resolve("opencanvas").toFile())
    // The public "apps open right now" count; see Presence.
    DesktopPresence.install()
    desktopMain()
}

private fun desktopRecommendationData(): java.io.File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase(java.util.Locale.ROOT)
    return when {
        "win" in os -> java.io.File(System.getenv("APPDATA") ?: home, "Cosmotune/recommendations")
        "mac" in os -> java.io.File(home, "Library/Application Support/Cosmotune/recommendations")
        else -> java.io.File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "cosmotune/recommendations")
    }
}

private fun desktopMain() = application {
    // Closing puts the window away rather than ending the process, while there is a tray icon to
    // bring it back from — see [DesktopWindowVisibility].
    val visible by DesktopWindowVisibility.visible.collectAsState()
    val state = rememberWindowState(width = 1_220.dp, height = 780.dp)
    LaunchedEffect(visible) {
        if (visible && state.isMinimized) {
            state.isMinimized = false
        }
    }
    // Hoisted so the player can fill the screen and the caption buttons can maximize — see
    // [DesktopWindowMode].
    val placement by DesktopWindowMode.placement.collectAsState()
    LaunchedEffect(placement) { state.placement = placement }
    // ...and back, for the times the window is moved between placements by something that is not
    // us; see [DesktopWindowMode.adopt].
    LaunchedEffect(state) {
        snapshotFlow { state.placement }.collect(DesktopWindowMode::adopt)
    }
    val maximized by DesktopWindowMode.maximized.collectAsState()
    LaunchedEffect(maximized) {
        if (DesktopPlatform.isMac) {
            DesktopMacFrame.updateCornerRadius(maximized)
        }
    }
    // Ctrl/Cmd + 1 to 6 switch modes (DESIGN_SYSTEM.md 4.2), in every mode.
    val lookSettings = com.cosmotune.ui.settings.rememberCosmoLookSettings()
    Window(
        onCloseRequest = { if (DesktopWindowVisibility.onCloseRequest()) exitApplication() },
        onPreviewKeyEvent = { event -> com.cosmotune.ui.kit.modeShortcut(event) { lookSettings.setMode(it) } },
        // Space plays and pauses (Music, Video, Watch) once nothing focused has claimed it: a text field keeps its spaces
        // and a button its press. See [DesktopSpaceKey].
        onKeyEvent = { event -> DesktopSpaceKey.onKeyEvent(event, lookSettings.mode.value) },
        visible = visible,
        title = "Cosmotune",
        icon = painterResource(Res.drawable.logo),
        state = state,
        // No system title bar on Windows, where the application draws its own instead — see
        // [DesktopWindowChrome].
        undecorated = DesktopPlatform.drawsOwnWindowFrame,
        // For Mica and Acrylic behind the sidebar and top bar: only a transparent window lets
        // DWM's material show through the chrome. Fixed at creation, so it is on wherever the
        // material is possible and the chosen one can be switched live — see [DesktopWindowBackdrop].
        transparent = DesktopWindowBackdrop.available,
        resizable = true,
    ) {
        val composeWindow = window
        val openingSize = remember { state.size }
        LaunchedEffect(composeWindow) {
            DesktopWindowVisibility.attachWindow(composeWindow)
            // The buttons and the cover under the taskbar's hover preview; Windows only.
            DesktopWindowsTaskbar.install(composeWindow)
            // AWT's default is white, and it is what shows for the frame or two a moved or resized
            // window takes to repaint: a white band along its edges.
            // Not over a transparent window, whose clear background is what the material shows
            // through; DWM moves that window, so there is no band to hide.
            // Over an opaque window the floor is the theme's own (the effect below keeps it on the theme).
            if (DesktopWindowBackdrop.available) {
                composeWindow.background = java.awt.Color(0, 0, 0, 0)
                composeWindow.contentPane.background = java.awt.Color(0, 0, 0, 0)
                (composeWindow.contentPane as? javax.swing.JComponent)?.isOpaque = false
                composeWindow.rootPane.isOpaque = false
            }
            // AWT measures this in device pixels while Compose's window state is in dp. Keeping
            // the scale in the conversion makes the usable minimum consistent on every display.
            val transform = composeWindow.graphicsConfiguration.defaultTransform
            composeWindow.minimumSize = Dimension(
                (MIN_WINDOW_WIDTH_DP * transform.scaleX).roundToInt(),
                (MIN_WINDOW_HEIGHT_DP * transform.scaleY).roundToInt(),
            )
            // Setting AWT's minimum during the first composition can resize the native peer to
            // that minimum without updating Compose's state. Compose would then keep laying out a
            // 1220dp surface inside a 900dp window, cropping the right edge and putting its resize
            // boundary off-screen until a maximize/restore cycle synchronised the two. Re-assert
            // the requested opening size on the native peer so both layers start in agreement.
            composeWindow.size = Dimension(
                (openingSize.width.value * transform.scaleX).roundToInt(),
                (openingSize.height.value * transform.scaleY).roundToInt(),
            )
            if (DesktopPlatform.isWindows && DesktopWindowsFrame.install("Cosmotune")) {
                DesktopWindowBackdrop.apply()
            } else if (DesktopPlatform.isMac && DesktopMacFrame.install(composeWindow)) {
                DesktopWindowBackdrop.apply()
            }
        }
        // The floor follows the theme (black in dark, the light ground in light), so a resize shows no band of the other theme.
        val windowDark = rememberDesktopDark()
        LaunchedEffect(composeWindow, windowDark) {
            if (!DesktopWindowBackdrop.available) {
                val floor = if (windowDark) java.awt.Color.BLACK else java.awt.Color(0xF7, 0xEF, 0xE9)
                composeWindow.background = floor
                composeWindow.contentPane.background = floor
            }
        }
        val actions = remember {
            DesktopWindowActions(
                minimize = {
                    if (!DesktopWindowsFrame.minimize()) state.isMinimized = true
                },
                toggleMaximize = {
                    if (!DesktopWindowsFrame.toggleMaximize()) {
                        // An undecorated AWT window otherwise maximizes to the monitor bounds on
                        // Windows when the native frame bridge is unavailable. Give AWT the
                        // monitor's usable work area before Compose switches the placement.
                        if (DesktopPlatform.isWindows && !DesktopWindowMode.maximized.value) {
                            val screen = composeWindow.graphicsConfiguration.bounds
                            val insets = Toolkit.getDefaultToolkit().getScreenInsets(
                                composeWindow.graphicsConfiguration,
                            )
                            composeWindow.maximizedBounds = Rectangle(
                                screen.x + insets.left,
                                screen.y + insets.top,
                                screen.width - insets.left - insets.right,
                                screen.height - insets.top - insets.bottom,
                            )
                        }
                        DesktopWindowMode.toggleMaximized()
                    }
                },
                // The same door the system's close button went through, so the tray keeps the
                // process alive exactly as it did before.
                close = { if (DesktopWindowVisibility.onCloseRequest()) exitApplication() },
            )
        }
        CompositionLocalProvider(
            LocalDesktopWindowActions provides actions,
            LocalDesktopWindowScope provides this,
            com.cosmotune.video.player.smooth.LocalVideoHostWindow provides composeWindow,
            com.cosmotune.ui.watch.LocalWatchFullscreen provides { exit, _, content ->
                DisposableEffect(Unit) {
                    val previous = DesktopWindowMode.placement.value
                    val entered = !DesktopWindowMode.fullScreen.value
                    if (entered) DesktopWindowMode.toggle()
                    onDispose {
                        if (entered) {
                            DesktopWindowMode.exit()
                            DesktopWindowMode.adopt(previous)
                        }
                    }
                }
                com.cosmotune.video.ui.watch.WatchFullWindow(exit, false, content)
            },
            com.cosmotune.ui.shell.LocalWindowCaptionArea provides { modifier, content ->
                val fullscreen by DesktopWindowMode.fullScreen.collectAsState()
                if (fullscreen) androidx.compose.foundation.layout.Box(modifier) { content() }
                else DesktopTitleBarDragArea(modifier, content)
            },
        ) {
            CosmotuneDesktopApp()
        }
    }
    // The compact always-on-top mini player, beside the main window.
    DesktopMiniPlayerWindow()
    // What a click on the tray icon opens.
    DesktopTrayFlyoutWindow()
    // The taskbar's buttons and cover, and the Dock menu, follow what is playing.
    LaunchedEffect(Unit) {
        DesktopMiniPlayer.state.collect { now ->
            DesktopWindowsTaskbar.update(now)
            DesktopMacDock.update(now)
        }
    }
}

private const val MIN_WINDOW_WIDTH_DP = 900.0
private const val MIN_WINDOW_HEIGHT_DP = 600.0
