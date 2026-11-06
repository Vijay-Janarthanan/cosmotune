package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.cosmotune.watch.FakeWatchRepository
import com.cosmotune.watch.WatchPlayer
import com.cosmotune.watch.WatchRepository
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * What Watch's screens are handed by the app hosts: the repository, the player and its picture, a QR drawer, and the clock. Every
 * one is a composition local with a sensible default, so a screen (or a render test) works with nothing provided.
 */

/**
 * The repository Watch uses when the host provides none through [LocalWatchRepository]: the host's real one once it has
 * [install]ed it (the desktop's `DesktopWatch.repository`, the phone's `AndroidWatch.repository(context)`), else the in-memory
 * fake, which stays for previews and render tests only.
 */
object WatchBackend {
    @Volatile private var installed: WatchRepository? = null
    private val fake: WatchRepository by lazy { FakeWatchRepository(now = System::currentTimeMillis) }

    val repository: WatchRepository get() = installed ?: fake

    /** The host's real repository, set once at start-up. */
    fun install(repository: WatchRepository) { installed = repository }
}

/** The repository the Watch screens call; null means [WatchBackend.repository]. */
val LocalWatchRepository = staticCompositionLocalOf<WatchRepository?> { null }

@Composable
internal fun watchRepository(): WatchRepository = LocalWatchRepository.current ?: WatchBackend.repository

/** How the platform's video picture fills its box: whole (letterboxed) or cut to fill. */
enum class WatchSurfaceScale { FIT, CROP }

/**
 * The Watch player and the platform's picture of its engine ([WatchPlayer.engine]): the phone's Media3 view or the desktop's
 * libmpv surface, drawn by the host the way Video mode draws it.
 */
class WatchPlayback(val player: WatchPlayer, val surface: @Composable (scale: WatchSurfaceScale, modifier: Modifier) -> Unit)

/** The player, provided by the host once it has one; null shows the player page's "not connected" card. */
val LocalWatchPlayback = staticCompositionLocalOf<WatchPlayback?> { null }

/** Draws a QR code of a payload at a size (the hosts already ship zxing); null hides the code and keeps the link. */
val LocalWatchQrCode = staticCompositionLocalOf<(@Composable (payload: String, size: Dp) -> Unit)?> { null }

/** The time the guide and "live now" are laid out around (a render test passes the fake's fixed evening). */
val LocalWatchClock = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/** Everything at once, for a host or a test. */
@Composable
fun ProvideWatch(
    repository: WatchRepository? = null,
    playback: WatchPlayback? = null,
    qrCode: (@Composable (String, Dp) -> Unit)? = null,
    clock: (() -> Long)? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalWatchRepository provides repository,
        LocalWatchPlayback provides playback,
        LocalWatchQrCode provides qrCode,
        LocalWatchClock provides (clock ?: System::currentTimeMillis),
        content = content,
    )
}

// ---- navigation -------------------------------------------------------------------------------------------------------------

/** Watch's pages, in the sidebar's order (board: Home, Films, Series, Live TV, Search, My list, Downloads; Plugins under Sources). */
enum class WatchTab(val label: String) {
    HOME("Home"), FILMS("Films"), SERIES("Series"), LIVE("Live TV"), SEARCH("Search"), MY_LIST("My list"), DOWNLOADS("Downloads"), PLUGINS("Plugins"),
}

/** A page opened over a tab: a film or series page, or the player. */
sealed interface WatchRoute {
    data class Page(val summary: TitleSummary) : WatchRoute
    data class Play(val target: PlayTarget, val summary: TitleSummary? = null, val stream: Stream? = null, val episode: Episode? = null) : WatchRoute
}

/**
 * Where Watch is: the tab and the pages opened over it, newest last. Global like the other modes' [com.cosmotune.ui.blocks.ModeNav],
 * so the desktop sidebar and the phone's back key drive the same state the pages read.
 */
object WatchNav {
    val tab = MutableStateFlow(WatchTab.HOME)
    val stack = MutableStateFlow<List<WatchRoute>>(emptyList())

    /** An install link waiting for its sheet (`stremio://…/manifest.json`, an https manifest, `cosmotune://watch/addon?url=`). */
    val pendingInstall = MutableStateFlow<String?>(null)

    fun go(to: WatchTab) { tab.value = to; stack.value = emptyList() }
    fun open(route: WatchRoute) { stack.value = stack.value + route }
    fun openTitle(summary: TitleSummary) = open(WatchRoute.Page(summary))

    /** Closes the top page; false when there was none (the host may then leave the mode). */
    fun back(): Boolean {
        val now = stack.value
        if (now.isEmpty()) {
            if (tab.value != WatchTab.HOME) { tab.value = WatchTab.HOME; return true }
            return false
        }
        stack.value = now.dropLast(1)
        return true
    }

    /** Hosts call this for a one-tap install link that reached the app. */
    fun openInstallLink(link: String) { pendingInstall.value = link }

    /** Whether [link] is one the install sheet reads. */
    fun isInstallLink(link: String): Boolean {
        val l = link.trim().lowercase()
        return l.startsWith("stremio://") || l.startsWith("cosmotune://watch/addon") || (l.startsWith("https://") && l.endsWith("/manifest.json"))
    }
}

/** A channel as a summary card, so the player has a title to show. */
internal fun Channel.asSummary(): TitleSummary = TitleSummary(key, TitleType.FILM, name, posterUrl = logoUrl, origin = pluginId)

/** "1h 52m", "48 min". */
internal fun runtimeText(min: Int?): String? = when {
    min == null || min <= 0 -> null
    min >= 60 -> "${min / 60}h ${"%02d".format(min % 60)}m"
    else -> "$min min"
}

/** "24 min left", "1h 12m left". */
internal fun leftText(ms: Long): String {
    val min = ((ms + 59_999) / 60_000).toInt()
    return if (min >= 60) "${min / 60}h ${"%02d".format(min % 60)}m left" else "$min min left"
}

/** "0:40:12" for a position in a film. */
internal fun longClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

internal fun WatchKey.isSeries(): Boolean = kind == WatchKey.Kind.SERIES
