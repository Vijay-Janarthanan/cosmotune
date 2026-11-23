package com.cosmotune.ui.watch

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitModeSwitcher
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.watch.WatchRepository
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watch mode (Canvas repo, design/cosmotune/watch-orbit/board.html): films, series and live TV from the plugins that are on. The
 * page shown is the top of [WatchNav]'s stack (a film or series page, the player) or else the tab. On the desktop the sidebar
 * carries the tabs ([showNavigation] false); on the phone (and a narrow desktop window) the page starts with the mode pill and the
 * tabs stand in the shared navigation ([com.cosmotune.ui.shell.CosmoNavShell]: the bottom bar under 600 dp with the small player
 * above it, the rail from 600 dp), on every page but the player. Wide (840 dp of page
 * and up) lays cards side by side as the board does; narrower stacks them. [wideLayout], when set, decides that instead of the width (the TV,
 * which draws the pages larger, keeps the side-by-side layout).
 *
 * A host with its own chips and player (the TV) passes [side], the gutter rows start from (they still scroll out to the screen's
 * edges, which the host then keeps clear of [contentPadding]'s sides), turns the home's All, Films, Series and Live TV chips off
 * with [filterChips], keeps the page under its own player with [pageUnderPlayer] (so Back from the player lands where the page
 * was), and hears through [onScrolled] whether the page is scrolled away from its top.
 */
@Composable
fun WatchApp(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    showNavigation: Boolean = true,
    modifier: Modifier = Modifier,
    wideLayout: Boolean? = null,
    side: Dp? = null,
    filterChips: Boolean = true,
    pageUnderPlayer: Boolean = false,
    onScrolled: ((Boolean) -> Unit)? = null,
) {
    val tab by WatchNav.tab.collectAsState()
    val fullStack by WatchNav.stack.collectAsState()
    val stack = if (pageUnderPlayer && fullStack.lastOrNull() is WatchRoute.Play) fullStack.dropLast(1) else fullStack
    val pending by WatchNav.pendingInstall.collectAsState()
    val opened by com.cosmotune.watch.WatchLinks.pending.collectAsState()
    val fonts = orbitFonts()
    val look = orbitLook()
    val repository = watchRepository()
    val preferences by repository.preferences.collectAsState()
    val profileUnlocked by repository.profileUnlocked.collectAsState()
    // A source added, removed or switched: Home's rows and Live TV's channels kept from before are fetched again when next shown
    // (still showing what they had meanwhile), rather than leaving a new add-on's catalogues and channels out for minutes.
    val sources by repository.plugins.collectAsState()
    val switchedOn = sources.filter { it.enabled }.map { it.id }
    LaunchedEffect(repository, switchedOn) { if (WatchHomeMemory.sourcesChanged(switchedOn)) watchPages(repository).markStale("watch:") }
    if (!profileUnlocked) { WatchProfileGate(repository, fonts, look); return }
    val scope = rememberCoroutineScope()
    val keptPlayback = LocalWatchPlayback.current
    // Leaving Watch (for another mode) stops what the small player held, as leaving the player page did before; a film kept in
    // picture-in-picture or held by the room plays on.
    androidx.compose.runtime.DisposableEffect(keptPlayback, pageUnderPlayer) {
        onDispose { if (keptPlayback != null && !pageUnderPlayer) stopWhenLeaving(keptPlayback) }
    }
    val room = com.cosmotune.ui.room.LocalRoomLauncher.current
    val downloads = LocalWatchDownloads.current
    val actions = remember(repository, scope, room, downloads) { WatchActions(repository, scope, room, downloads) }
    androidx.compose.runtime.CompositionLocalProvider(com.cosmotune.ui.orbit.LocalWatchCardScale provides preferences.density.scale) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val top = stack.lastOrNull()
        // The navigation rule (CosmoNav): with [showNavigation], the bottom bar under 600 dp and the rail from 600 dp, on every page
        // but the player, which has the whole window.
        val navShown = showNavigation && top !is WatchRoute.Play
        val railShown = navShown && com.cosmotune.ui.shell.CosmoNav.usesRail(maxWidth)
        val pageWidth = if (railShown) maxWidth - com.cosmotune.ui.shell.CosmoNav.RAIL_WIDTH_DP.dp else maxWidth
        val wide = wideLayout ?: (pageWidth >= 840.dp)
        val header: (@Composable () -> Unit)? = when {
            top != null -> ({ WatchBackHeader(when { top is WatchRoute.Playlists -> "Watch playlists"; top is WatchRoute.Page && top.summary.type == TitleType.SERIES -> "Series"; else -> "Film" }, fonts, look) })
            // Downloads, Plugins and Settings are reached from the profile menu on the phone: they come back with a back button.
            showNavigation && tab in WATCH_MENU_PAGES -> ({ WatchBackHeader(if (tab == WatchTab.SETTINGS) "Watch settings" else tab.label, fonts, look) })
            showNavigation -> ({ WatchPhoneHeader(fonts, look) })
            else -> null
        }
        // The small player: a film or an episode closed while it plays goes on in the capsule at the foot of every page.
        val keepsPlaying = keptPlayback != null && !pageUnderPlayer
        val mini = keepsPlaying && top !is WatchRoute.Play && keptPlayback != null && watchMiniShown(keptPlayback)
        // What the dock takes from the foot of the window (its gap to the edge included), measured.
        var dockPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val dock = with(androidx.compose.ui.platform.LocalDensity.current) { dockPx.toDp() }
        val padding = when {
            // Beside the rail the page starts at the rail (which keeps the cut-out clear), and above the dock it ends at the dock.
            navShown -> PaddingValues(
                start = if (railShown) 0.dp else contentPadding.calculateStartPadding(LayoutDirection.Ltr), top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(LayoutDirection.Ltr), bottom = maxOf(contentPadding.calculateBottomPadding(), dock) + 8.dp,
            )
            !mini -> contentPadding
            else -> PaddingValues(
                start = contentPadding.calculateStartPadding(LayoutDirection.Ltr), top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(LayoutDirection.Ltr), bottom = contentPadding.calculateBottomPadding() + WATCH_MINI_ROOM,
            )
        }
        val frame = WatchFrame(
            wide = wide,
            side = side ?: 16.dp,
            padding = padding,
            header = if (onScrolled == null) header else ({ WatchScrollMark(contentPadding, onScrolled) { header?.invoke() } }),
            filterChips = filterChips,
        )
        val pages: @Composable () -> Unit = {
            androidx.compose.runtime.CompositionLocalProvider(LocalWatchKeepsPlaying provides keepsPlaying) {
            when (top) {
                is WatchRoute.Playlists -> WatchPlaylistsPage(top.id, actions, fonts, look, frame)
                is WatchRoute.Play -> WatchPlayerPage(top, actions, fonts, look, frame)
                is WatchRoute.Page -> WatchTitlePage(top.summary, actions, fonts, look, frame)
                null -> when (tab) {
                    WatchTab.HOME -> WatchHomePage(null, actions, fonts, look, frame)
                    WatchTab.FILMS -> WatchHomePage(com.cosmotune.watch.HomeFilter.FILMS, actions, fonts, look, frame)
                    WatchTab.SERIES -> WatchHomePage(com.cosmotune.watch.HomeFilter.SERIES, actions, fonts, look, frame)
                    WatchTab.LIVE -> WatchLivePage(actions, fonts, look, frame)
                    WatchTab.SEARCH -> WatchSearchPage(actions, fonts, look, frame)
                    WatchTab.MY_LIST -> WatchMyListPage(actions, fonts, look, frame)
                    WatchTab.DOWNLOADS -> WatchDownloadsPage(actions, fonts, look, frame)
                    WatchTab.PLUGINS -> WatchPluginsPage(fonts, look, frame)
                    WatchTab.SETTINGS -> WatchSettingsPage(fonts, look, frame)
                }
            }
            }
        }
        if (showNavigation) {
            // The bar or the rail: Home, Live TV, My list and Downloads, with Search as the round button beside them (the head of
            // the rail), as in Music (Films and Series light Home, whose own chips they are; a page opened over a tab lights that
            // tab; Search, Plugins and Settings light none). The small player stands above the bar, or alone along the foot beside
            // the rail.
            val lit = if (tab == WatchTab.FILMS || tab == WatchTab.SERIES) WatchTab.HOME else tab
            val tabs = watchNavTabs()
            val search = com.cosmotune.ui.shell.CosmoNavSearch("Search", selected = tab == WatchTab.SEARCH, onClick = WatchNav::tapSearch)
            com.cosmotune.ui.shell.CosmoNavShell(
                windowWidth = maxWidth,
                tabs = tabs,
                selectedIndex = WATCH_PHONE_TABS.indexOf(lit),
                onTabSelected = { WatchNav.go(WATCH_PHONE_TABS[it]) },
                navigation = navShown,
                search = search,
                bottomChrome = { showTabs, _ ->
                    if (top !is WatchRoute.Play) com.cosmotune.ui.shell.CosmoBottomChrome(
                        tabs = tabs, selectedIndex = WATCH_PHONE_TABS.indexOf(lit), onTabSelected = { WatchNav.go(WATCH_PHONE_TABS[it]) },
                        song = null, isPlaying = false, isLoading = false, position = remember { com.cosmotune.music.playback.PlaybackPosition() },
                        durationMs = 0L, onPlayPause = {}, onNext = {}, onExpand = {}, controlsLocked = false, onBlockedControl = {},
                        hazeState = null, onHeightChanged = { dockPx = it }, search = search,
                        modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = com.cosmotune.ui.shell.CosmoNav.dockMaxWidth(showTabs)).fillMaxWidth(),
                        // Without a shell (a render, a test) the dock is drawn as Music's, since it draws only for the stored mode.
                        mode = if (com.cosmotune.ui.theme.LocalCosmoShell.current == null) com.cosmotune.design.AppMode.MUSIC else com.cosmotune.design.AppMode.WATCH,
                        miniPlayer = if (mini && keptPlayback != null) ({ WatchMiniPlayer(keptPlayback, fonts, look, docked = true) }) else null,
                        showTabs = showTabs,
                        ownMedia = true,
                    )
                },
            ) {
                pages()
                // The phone's pages scroll under the status bar: a solid band of the ground keeps the clock readable and keeps the titles
                // from showing through under it; it fades out only below the bar.
                WatchStatusBand(look, Modifier.align(Alignment.TopCenter))
            }
        } else {
            pages()
            if (mini && keptPlayback != null) WatchMiniPlayer(keptPlayback, fonts, look,
                Modifier.align(Alignment.BottomCenter).padding(bottom = contentPadding.calculateBottomPadding() + 8.dp))
        }
        pending?.let { link -> WatchInstallSheet(link, fonts, look, onDone = { WatchNav.pendingInstall.value = null }) }
        // A link opened from outside (an intent, the system's URL handler, a second launch): its Install or pack sheet.
        when (val link = opened) {
            is com.cosmotune.watch.WatchLinks.Link.Install -> WatchInstallSheet(link.raw, fonts, look, onDone = { com.cosmotune.watch.WatchLinks.handled() })
            is com.cosmotune.watch.WatchLinks.Link.Pack -> ImportPackDialog(repository, fonts, look, initialLink = link.raw) { com.cosmotune.watch.WatchLinks.handled() }
            null -> Unit
        }
    }
}

}

/** How a page is laid out: wide or stacked, the side gutter, the host's padding and the phone's header (null on the desktop). */
internal class WatchFrame(val wide: Boolean, val side: Dp, val padding: PaddingValues, val header: (@Composable () -> Unit)?, val filterChips: Boolean = true)

/**
 * The first item of every page's list, wrapped round its [content] (the page's header, or nothing): tells [onScrolled] whether the
 * list has moved off its top, from where this item sits against the list's top padding, and that it has once it scrolls away.
 */
@Composable
private fun WatchScrollMark(padding: PaddingValues, onScrolled: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val report by androidx.compose.runtime.rememberUpdatedState(onScrolled)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rest = with(density) { padding.calculateTopPadding().toPx() - 24.dp.toPx() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { report(true) } }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { report(it.positionInParent().y < rest) }) { content() }
}

/**
 * What every page does with a title, an episode or a channel: open, play, keep, download, and play for the room ("Watch together")
 * through the host's [com.cosmotune.ui.room.RoomLauncher], each member's app then finding its own copy.
 */
internal class WatchActions(
    val repository: WatchRepository,
    private val scope: CoroutineScope,
    private val room: com.cosmotune.ui.room.RoomLauncher? = null,
    val downloads: com.cosmotune.watch.WatchDownloads? = null,
) {
    fun open(summary: TitleSummary) = WatchNav.openTitle(summary)

    fun playTrailer(summary: TitleSummary, trailer: com.cosmotune.watch.model.Trailer) {
        if (!trailer.youTubeKey.matches(Regex("[A-Za-z0-9_-]{11}"))) return
        val key = com.cosmotune.watch.model.WatchKey.item("trailer", trailer.youTubeKey)
        val card = summary.copy(key = key, name = "${summary.name} · Trailer", badge = "TRAILER")
        val target = PlayTarget.Item(key, "trailer", trailer.youTubeKey, card.name)
        val stream = Stream(com.cosmotune.watch.model.StreamSource("trailer", "YouTube", "TRAILER"), trailer.name,
            url = "https://www.youtube.com/watch?v=${trailer.youTubeKey}", protocol = com.cosmotune.watch.model.StreamProtocol.YOUTUBE)
        WatchNav.open(WatchRoute.Play(target, card, stream))
    }

    /** Play: a film at once (resumed), a series at its next episode. */
    fun play(summary: TitleSummary) {
        if (summary.type == TitleType.FILM) {
            WatchNav.open(WatchRoute.Play(repository.targetOf(summary), summary))
        } else scope.launch {
            val next = repository.nextUp(summary.key)
            if (next == null) WatchNav.openTitle(summary) else playEpisode(summary, next.episode)
        }
    }

    fun playEpisode(series: TitleSummary, episode: Episode) {
        val target = PlayTarget.EpisodeOf(episode.key, series.ids, series.name, episode.season, episode.number, episode.ids, episode.name, series.year)
        WatchNav.open(WatchRoute.Play(target, series, episode = episode))
    }

    fun playEpisode(series: Title, episode: Episode) =
        WatchNav.open(WatchRoute.Play(repository.targetOf(series, episode), series.summary, episode = episode))

    fun playStream(summary: TitleSummary, target: PlayTarget, stream: Stream) = WatchNav.open(WatchRoute.Play(target, summary, stream))

    fun playChannel(channel: com.cosmotune.watch.model.Channel) = WatchNav.playChannel(PlayTarget.Live(channel), channel.asSummary())

    fun setInMyList(summary: TitleSummary, on: Boolean) { scope.launch { repository.setInMyList(summary, on) } }

    fun favourite(channel: com.cosmotune.watch.model.Channel, on: Boolean) { scope.launch { repository.setFavouriteChannel(channel.key, on) } }

    fun launch(block: suspend CoroutineScope.() -> Unit) { scope.launch(block = block) }

    /**
     * "Watch together" works where the host offers rooms: outside a room it asks to make or join one first, inside it plays for
     * everyone, and it is drawn off only in a room whose host keeps the controls to themselves.
     */
    val watchTogetherReady: Boolean get() = room != null && (!room.inRoom || room.canControl)

    /** Plays [target] for the room: the room item carries its key, ids and this device's release (never a server or a token). */
    fun watchTogether(target: PlayTarget, summary: TitleSummary?, stream: Stream? = null, durationMs: Long? = null) {
        val launcher = room ?: return
        launcher.playInRoom(com.cosmotune.ui.room.roomWatchItem(target, summary, stream, durationMs))
    }

    /** "Watch together" on a film's or a series' page: the film with its best copy, a series at its next (or first) episode. */
    fun watchTogether(summary: TitleSummary, page: Title?, best: Stream?) {
        if (summary.type == TitleType.FILM) { watchTogether(repository.targetOf(summary), summary, best); return }
        scope.launch {
            val episode = runCatching { repository.nextUp(summary.key)?.episode }.getOrNull()
                ?: runCatching {
                    val season = page?.seasons?.map { it.number }?.filter { it > 0 }?.minOrNull() ?: 1
                    repository.episodes(summary.key, season).firstOrNull()
                }.getOrNull()
                ?: return@launch
            val target = page?.let { repository.targetOf(it, episode) }
                ?: PlayTarget.EpisodeOf(episode.key, summary.ids, summary.name, episode.season, episode.number, episode.ids, episode.name, summary.year)
            watchTogether(target, summary)
        }
    }
}

/** The top wash of a page (board `.wash`): its colour strong at the top, gone by three quarters down the window. */
@Composable
internal fun WatchWash(color: Color, look: OrbitLook, modifier: Modifier = Modifier) =
    com.cosmotune.ui.orbit.OrbitPageWash(color, modifier, height = 620.dp, look = look, darkAlpha = 0.55f, lightAlpha = 0.22f, tail = 0.33f)

/** The tabs of the bottom bar and the rail, in their order; Films and Series are Home's own chips, and Search is the round button beside them. */
internal val WATCH_PHONE_TABS = listOf(WatchTab.HOME, WatchTab.LIVE, WatchTab.MY_LIST, WatchTab.DOWNLOADS)

/** [WATCH_PHONE_TABS] as the bar's and the rail's tabs: each its name and its two icons (outlined at rest, filled when chosen). */
@Composable
private fun watchNavTabs(): List<com.cosmotune.ui.shell.CosmoTab> = remember {
    val outlined = androidx.compose.material.icons.Icons.Outlined
    val filled = androidx.compose.material.icons.Icons.Filled
    WATCH_PHONE_TABS.map { tab ->
        when (tab) {
            WatchTab.HOME -> com.cosmotune.ui.shell.CosmoTab(tab.label, CosmoIcons.Home, CosmoIcons.HomeFill)
            WatchTab.LIVE -> com.cosmotune.ui.shell.CosmoTab(tab.label, outlined.LiveTv, filled.LiveTv)
            WatchTab.DOWNLOADS -> com.cosmotune.ui.shell.CosmoTab(tab.label, CosmoIcons.Download, CosmoIcons.Download)
            else -> com.cosmotune.ui.shell.CosmoTab(tab.label, outlined.BookmarkBorder, filled.Bookmark)
        }
    }
}

/** The pages the profile menu opens on the phone (they are not tabs there). */
internal val WATCH_MENU_PAGES = setOf(WatchTab.PLUGINS, WatchTab.SETTINGS)

/**
 * The phone's head of every tab: the mode pill, the Watch profile button (its menu holds Plugins and Settings) and the
 * account button when the host gives one, then the Live pill. The tabs themselves are the bottom bar's (or the rail's).
 */
@Composable
private fun WatchPhoneHeader(fonts: OrbitFonts, look: OrbitLook) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KitModeSwitcher()
            Spacer(Modifier.weight(1f))
            WatchProfileButton(fonts, look)
            LocalModeProfileAction.current?.invoke()
        }
        // The Live pill under the top bar, when something followed is live (the phone's; nothing elsewhere).
        com.cosmotune.ui.live.LiveNowStrip(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp))
    }
}

/**
 * The Watch profile button: the viewer's initial in a disc of their own colour. Its menu names the viewer (Switch profile goes to
 * the profiles in Watch settings), then Plugins with how many are on, and Settings (Downloads is a tab of its own).
 */
@Composable
private fun WatchProfileButton(fonts: OrbitFonts, look: OrbitLook) {
    val repository = watchRepository()
    val profiles by repository.profiles.collectAsState()
    val active by repository.activeProfile.collectAsState()
    val plugins by repository.plugins.collectAsState()
    val name = profiles.firstOrNull { it.id == active }?.name ?: "Watch"
    val (fill, ink) = com.cosmotune.ui.orbit.coverFill(null, "profile:$active")
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(fill)
                .clickable(onClickLabel = "Open the Watch menu", role = Role.Button) { open = true }
                .semantics { contentDescription = "$name, Watch menu" },
            contentAlignment = Alignment.Center,
        ) { Text(name.take(1).uppercase(), style = fonts.title, color = ink) }
        com.cosmotune.ui.orbit.OrbitMenu(
            open, onDismiss = { open = false }, fonts = fonts, look = look, header = name,
            items = listOf(
                com.cosmotune.ui.orbit.OrbitMenuItem("Switch profile", if (profiles.size <= 1) "Add a viewer" else "${profiles.size} profiles",
                    androidx.compose.material.icons.Icons.Rounded.Person) { WatchNav.go(WatchTab.SETTINGS) },
                com.cosmotune.ui.orbit.OrbitMenuItem("Plugins", "${plugins.count { it.enabled }} on", androidx.compose.material.icons.Icons.Rounded.Extension) { WatchNav.go(WatchTab.PLUGINS) },
                com.cosmotune.ui.orbit.OrbitMenuItem("Settings", null, CosmoIcons.Settings) { WatchNav.go(WatchTab.SETTINGS) },
            ),
        )
    }
}

/**
 * The band behind the status bar: the page's ground, solid over the bar itself so nothing scrolled under it shows through, then
 * fading out over 14 dp below it.
 */
@Composable
private fun WatchStatusBand(look: OrbitLook, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val bar = androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density)
    if (bar <= 0) return
    val fade = with(density) { 14.dp.toPx() }
    Box(
        modifier.fillMaxWidth().height(with(density) { (bar + fade).toDp() })
            .background(Brush.verticalGradient(0f to look.ground, bar / (bar + fade) to look.ground, 1f to look.ground.copy(alpha = 0f))),
    )
}

/** The head of a page opened over a tab: the shared [com.cosmotune.ui.orbit.OrbitBackHeader], the round back button and the mono kind. */
@Composable
@Suppress("UNUSED_PARAMETER")
private fun WatchBackHeader(kind: String, fonts: OrbitFonts, look: OrbitLook) {
    com.cosmotune.ui.orbit.OrbitBackHeader(kind, { WatchNav.back() }, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).testTag("watch:back-head"))
}
