package com.cosmotune.desktop

import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ShellMetrics
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.orbit.OrbitNavChip
import com.cosmotune.ui.orbit.OrbitNavModeChip
import com.cosmotune.ui.orbit.OrbitNavPlaylist
import com.cosmotune.ui.orbit.OrbitNavRoomCard
import com.cosmotune.ui.orbit.OrbitNavSectionLabel
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.desktop.DesktopPlatform
import com.cosmotune.music.desktop.DesktopPlaylist
import com.cosmotune.music.desktop.DesktopStrings
import com.cosmotune.music.desktop.DesktopTitleBarDragArea
import com.cosmotune.music.desktop.DesktopTitleBarSetting
import com.cosmotune.music.desktop.DesktopWindowButtons
import kotlinx.coroutines.yield

/**
 * One row of the sidebar's navigation: its two icons (the second while it is [selected]), its [label], an [aside] at the end (the
 * queued downloads) and what a click does.
 */
@Immutable
internal data class CosmoDesktopNavItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String,
    val selected: Boolean,
    val aside: String? = null,
    val onClick: () -> Unit,
)

/** One playlist of the sidebar's library list: a cover (a queue glyph on a tonal tile without one), a name and a line under it. */
@Immutable
internal data class CosmoDesktopPlaylistItem(
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/**
 * The desktop's sidebar in the new look (`V2.deskSidebar`, `.d2-side`): 220 dp wide (the page padding and the player bar host
 * depend on it), no glass, no rule beside the page: it stands on the ambient ground. It is built of the Orbit kit's navigation
 * pieces (board v2, section 6; `com.cosmotune.ui.orbit.OrbitNav`). From the top: the window's own caption when the application
 * draws its frame and there is no separate title bar (the old rule), the mode switcher as a chip with the orbit mark, the search
 * field (40 dp, the cap "Ctrl K" outside macOS), the [nav] pages as 40 dp chips (the selected one solid ink with a small peach
 * dot), "Your playlists" with the [playlists] as small discs under it, the small indigo room card while a room is joined, and
 * Settings at the bottom, 14 dp above the window's edge: the player bar floats beside the sidebar (it starts 16 dp to its right), never under it, so no
 * band is kept for it (the board's 110 dp is for a bar that runs under the sidebar).
 *
 * It does what the old sidebar did with the same inputs: the field takes the focus when [focusSearch] is set (then says so with
 * [onSearchFocused]) and gets it back when the window comes back to the front. While Settings is open ([settingsSelected]) no
 * other row shows as selected: the old sidebar lit both.
 *
 * While the mode is Video ([com.cosmotune.video.host.desktopVideoShown]) the whole sidebar is Video's (`VideoDesktopSidebar`), which
 * gets the same window caption this one would have drawn; the arguments for Music's rows are not used.
 */
@Composable
internal fun CosmoDesktopSidebar(
    nav: List<CosmoDesktopNavItem>,
    playlists: List<CosmoDesktopPlaylistItem>,
    settingsSelected: Boolean,
    onOpenSettings: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
) {
    if (com.cosmotune.video.host.desktopVideoShown()) return VideoSidebar(focusSearch = focusSearch, onSearchFocused = onSearchFocused)
    if (com.cosmotune.video.host.desktopWatchShown()) return WatchSidebar()
    if (com.cosmotune.video.host.desktopMangaShown() || com.cosmotune.video.host.desktopPodioShown()) {
        // Podio and Manga keep the shape of Music's and Video's sidebar: the window caption, then rows. Which mode is open is chosen from the
        // profile menu, as everywhere else, so no row here switches mode.
        val podio = com.cosmotune.video.host.desktopPodioShown()
        val podioTab by com.cosmotune.ui.blocks.ModeNav.podioTab.collectAsState()
        val podioSaved by com.cosmotune.ui.blocks.ModeNav.podioSaved.collectAsState()
        val mangaTab by com.cosmotune.ui.blocks.ModeNav.mangaTab.collectAsState()
        val titleBarEnabled by DesktopTitleBarSetting.enabled.collectAsState()
        val inlineCaption = DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled
        Column(Modifier.width(ShellMetrics.DESKTOP_SIDEBAR_DP.dp).fillMaxHeight()) {
            if (inlineCaption) SidebarCaption()
            Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = if (inlineCaption) 4.dp else 16.dp, bottom = ShellMetrics.DESKTOP_BAR_MARGIN_DP.dp)) {
                // The mode menu heads every mode's sidebar (DESIGN_SYSTEM.md 4.2).
                OrbitNavModeChip()
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(NAV_LIST_GAP)) {
                    if (podio) {
                        fun go(tab: String, saved: Boolean) { com.cosmotune.ui.blocks.ModeNav.podioTab.value = tab; com.cosmotune.ui.blocks.ModeNav.podioSaved.value = saved }
                        // Podio's five sections (board batch-be, section C): Radio, Podcasts, the Globe, Following (new episodes, shows and
                        // stations you keep) and Downloads. The old "Episodes" and "Library" names, and the old saved rows, mean Following.
                        val following = podioSaved || podioTab == "Following" || podioTab == "Episodes" || podioTab == "Library" || podioTab == "Saved"
                        OrbitNavRow(androidx.compose.material.icons.Icons.Outlined.Radio, "Radio", null, podioTab == "Radio" && !following) { go("Radio", false) }
                        OrbitNavRow(androidx.compose.material.icons.Icons.Outlined.Podcasts, "Podcasts", null, podioTab == "Podcasts" && !following) { go("Podcasts", false) }
                        OrbitNavRow(androidx.compose.material.icons.Icons.Outlined.Public, "Globe", null, podioTab == "Globe" && !following) { go("Globe", false) }
                        OrbitNavRow(com.cosmotune.ui.components.CosmoIcons.Heart, "Following", null, following) { go("Following", false) }
                        OrbitNavRow(com.cosmotune.ui.components.CosmoIcons.Download, "Downloads", null, podioTab == "Downloads" && !following) { go("Downloads", false) }
                    } else {
                        // Manga's tabs (board D: Library, Updates, Browse, Sources, History) and Read's (Library, Browse, Bookmarks) as the
                        // sidebar's rows; the page listens to the same channel.
                        val read = com.cosmotune.ui.theme.LocalCosmoShell.current?.settings?.mode?.collectAsState()?.value == com.cosmotune.design.AppMode.READ
                        fun go(tab: String) { com.cosmotune.ui.blocks.ModeNav.mangaTab.value = tab }
                        OrbitNavRow(androidx.compose.material.icons.Icons.Outlined.CollectionsBookmark, "Library", null, mangaTab == "Library") { go("Library") }
                        if (!read) OrbitNavRow(com.cosmotune.ui.components.CosmoIcons.Bell, "Updates", null, mangaTab == "Updates") { go("Updates") }
                        OrbitNavRow(androidx.compose.material.icons.Icons.Outlined.Explore, "Browse", null, mangaTab == "Browse") { go("Browse") }
                        if (read) OrbitNavRow(androidx.compose.material.icons.Icons.Outlined.BookmarkBorder, "Bookmarks", null, mangaTab == "Bookmarks") { go("Bookmarks") }
                        else {
                            OrbitNavRow(com.cosmotune.ui.components.CosmoIcons.Settings, "Sources", null, mangaTab == "Sources") { go("Sources") }
                            OrbitNavRow(com.cosmotune.ui.components.CosmoIcons.Replay, "History", null, mangaTab == "History") { go("History") }
                        }
                    }
                }
            }
        }
        return
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusSearch) { if (focusSearch) { runCatching { focusRequester.requestFocus() }; onSearchFocused() } }
    val windowInfo = LocalWindowInfo.current
    var searchFocused by remember { mutableStateOf(false) }
    var restoreSearchFocus by remember { mutableStateOf(false) }
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) {
            restoreSearchFocus = searchFocused
        } else if (restoreSearchFocus) {
            // Let the desktop window finish its activation before asking Compose to put the native text input target back in focus.
            yield()
            runCatching { focusRequester.requestFocus() }
            restoreSearchFocus = false
        }
    }

    val titleBarEnabled by DesktopTitleBarSetting.enabled.collectAsState()
    val inlineCaption = DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled
    Column(Modifier.width(ShellMetrics.DESKTOP_SIDEBAR_DP.dp).fillMaxHeight()) {
        // The sidebar runs to the top of the window, so its head is the window's caption: the three buttons, and room to take hold of the window by.
        if (inlineCaption) SidebarCaption()
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = if (inlineCaption) 4.dp else 16.dp, bottom = ShellMetrics.DESKTOP_BAR_MARGIN_DP.dp),
        ) {
            // The first row is the mode menu (DESIGN_SYSTEM.md 4.2), the same in every mode.
            OrbitNavModeChip()
            Spacer(Modifier.height(8.dp))
            CosmoSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSubmit = onSearch,
                placeholder = DesktopStrings["search", "Search"],
                focusRequester = focusRequester,
                height = SEARCH_HEIGHT,
                keyHint = if (DesktopPlatform.isMac) null else "Ctrl K",
                onFocusChanged = { searchFocused = it },
            )
            Spacer(Modifier.height(SECTION_GAP))
            Column(verticalArrangement = Arrangement.spacedBy(NAV_LIST_GAP)) {
                nav.forEach { item ->
                    val selected = item.selected && !settingsSelected
                    OrbitNavRow(if (selected) item.selectedIcon else item.icon, item.label, item.aside, selected, item.onClick)
                }
            }
            Spacer(Modifier.height(SECTION_GAP))
            OrbitNavSectionLabel("Your playlists", Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 6.dp))
            val keys = remember(playlists) { stableKeys(playlists.map { it.title }) }
            // A focused row's ring lies outside it: the list is wider than its column by the room the ring needs, and its content is inset by the same.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).bleed(RING_ROOM),
                contentPadding = PaddingValues(horizontal = RING_ROOM, vertical = RING_ROOM),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (playlists.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = DesktopStrings["d_sidebar_no_playlists", "Your playlists will appear here"],
                            style = LocalCosmoType.current.body().copy(fontSize = EMPTY_SP.sp, lineHeight = EMPTY_SP.sp * 1.4f),
                            color = Color(LocalCosmoColors.current.palette.textTertiary),
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp),
                        )
                        nav.firstOrNull { it.icon == com.cosmotune.ui.components.CosmoIcons.Library }?.let { library ->
                            com.cosmotune.ui.components.CosmoButton(
                                text = DesktopStrings["library", "Library"],
                                onClick = library.onClick,
                                kind = com.cosmotune.ui.components.CosmoButtonKind.TEXT,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                } else {
                    itemsIndexed(playlists, key = { index, _ -> keys[index] }) { _, playlist ->
                        OrbitNavPlaylist(playlist.title, playlist.subtitle, playlist.imageUrl, playlist.selected, playlist.onClick)
                    }
                }
            }
            // While a room is joined, Home's indigo room card, small, at the foot (it draws nothing outside a room).
            OrbitNavRoomCard(Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(6.dp))
            OrbitNavRow(CosmoIcons.Settings, DesktopStrings["settings", "Settings"], null, settingsSelected, onOpenSettings)
        }
    }
}

/**
 * Watch's sidebar (board, every section): the mode chip, then Home, Films, Series, Live TV, Search, My list and Downloads as chips,
 * and under "Sources" the Plugins chip with how many are on. The page listens to [com.cosmotune.ui.watch.WatchNav].
 */
@Composable
private fun WatchSidebar() {
    val tab by com.cosmotune.ui.watch.WatchNav.tab.collectAsState()
    val stack by com.cosmotune.ui.watch.WatchNav.stack.collectAsState()
    val repository = com.cosmotune.ui.watch.LocalWatchRepository.current ?: androidx.compose.runtime.remember { DesktopWatch.repository.also(com.cosmotune.ui.watch.WatchBackend::install) }
    val plugins by repository.plugins.collectAsState()
    val titleBarEnabled by DesktopTitleBarSetting.enabled.collectAsState()
    val inlineCaption = DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled
    // A page opened over a tab lights the tab it was opened from, as the board's film page lights Films.
    val lit = if (stack.isEmpty()) tab else stack.firstNotNullOfOrNull { (it as? com.cosmotune.ui.watch.WatchRoute.Page)?.summary?.type }
        ?.let { if (it == com.cosmotune.watch.model.TitleType.SERIES) com.cosmotune.ui.watch.WatchTab.SERIES else com.cosmotune.ui.watch.WatchTab.FILMS } ?: tab
    Column(Modifier.width(ShellMetrics.DESKTOP_SIDEBAR_DP.dp).fillMaxHeight()) {
        if (inlineCaption) SidebarCaption()
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = if (inlineCaption) 4.dp else 16.dp, bottom = ShellMetrics.DESKTOP_BAR_MARGIN_DP.dp)) {
            OrbitNavModeChip()
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(NAV_LIST_GAP)) {
                fun go(t: com.cosmotune.ui.watch.WatchTab) = com.cosmotune.ui.watch.WatchNav.go(t)
                val icons = androidx.compose.material.icons.Icons.Outlined
                OrbitNavRow(CosmoIcons.Home, "Home", null, lit == com.cosmotune.ui.watch.WatchTab.HOME) { go(com.cosmotune.ui.watch.WatchTab.HOME) }
                OrbitNavRow(icons.Movie, "Films", null, lit == com.cosmotune.ui.watch.WatchTab.FILMS) { go(com.cosmotune.ui.watch.WatchTab.FILMS) }
                OrbitNavRow(icons.Tv, "Series", null, lit == com.cosmotune.ui.watch.WatchTab.SERIES) { go(com.cosmotune.ui.watch.WatchTab.SERIES) }
                OrbitNavRow(icons.LiveTv, "Live TV", null, lit == com.cosmotune.ui.watch.WatchTab.LIVE) { go(com.cosmotune.ui.watch.WatchTab.LIVE) }
                OrbitNavRow(CosmoIcons.Search, "Search", null, lit == com.cosmotune.ui.watch.WatchTab.SEARCH) { go(com.cosmotune.ui.watch.WatchTab.SEARCH) }
                OrbitNavRow(icons.BookmarkBorder, "My list", null, lit == com.cosmotune.ui.watch.WatchTab.MY_LIST) { go(com.cosmotune.ui.watch.WatchTab.MY_LIST) }
                OrbitNavRow(CosmoIcons.Download, "Downloads", null, lit == com.cosmotune.ui.watch.WatchTab.DOWNLOADS) { go(com.cosmotune.ui.watch.WatchTab.DOWNLOADS) }
            }
            OrbitNavSectionLabel("Sources", Modifier.padding(start = 12.dp, end = 2.dp, top = 20.dp, bottom = 6.dp))
            OrbitNavRow(CosmoIcons.Settings, "Plugins", "${plugins.count { it.enabled }} on", lit == com.cosmotune.ui.watch.WatchTab.PLUGINS) {
                com.cosmotune.ui.watch.WatchNav.go(com.cosmotune.ui.watch.WatchTab.PLUGINS)
            }
            Spacer(Modifier.weight(1f))
            OrbitNavRoomCard(Modifier.padding(top = 8.dp))
        }
    }
}

/** The sidebar's head when the application draws its own window frame: the window's three buttons, and room to take hold of the window by. */
@Composable
private fun SidebarCaption() {
    DesktopTitleBarDragArea(Modifier.fillMaxWidth().height(CAPTION_HEIGHT)) {
        Box(
            Modifier.fillMaxSize().padding(start = if (DesktopPlatform.isMac) 16.dp else 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            DesktopWindowButtons()
        }
    }
}

/**
 * Video's sidebar in this one's place. It keeps the window's caption where this sidebar would have drawn it (the same rule as
 * [CosmoDesktopSidebar]'s `inlineCaption`): without it a window that draws its own frame and has no separate title bar would lose
 * its buttons and its drag area while the mode is Video.
 */
@Composable
private fun VideoSidebar(focusSearch: Boolean, onSearchFocused: () -> Unit) {
    val titleBarEnabled by DesktopTitleBarSetting.enabled.collectAsState()
    val inlineCaption = DesktopPlatform.drawsOwnWindowFrame && !titleBarEnabled
    com.cosmotune.video.ui.desktop.VideoDesktopSidebar(
        focusSearch = focusSearch,
        onSearchFocused = onSearchFocused,
        caption = if (inlineCaption) CAPTION else null,
    )
}

/** [SidebarCaption] as the lambda that Video's sidebar takes, made once. */
private val CAPTION: @Composable () -> Unit = { SidebarCaption() }

/**
 * The sidebar's list of playlists: the account's first (selected while that page is open), then the ones kept on this computer.
 * An account playlist is selected and opened as the old sidebar did it, by the id of the open collection.
 */
internal fun cosmoSidebarPlaylists(
    accountPlaylists: List<ShelfItem>,
    localPlaylists: List<DesktopPlaylist>,
    openedCollectionId: String?,
    onOpenAccountPlaylist: (ShelfItem) -> Unit,
    onOpenLocalPlaylist: (DesktopPlaylist) -> Unit,
): List<CosmoDesktopPlaylistItem> =
    accountPlaylists.map { playlist ->
        CosmoDesktopPlaylistItem(
            title = playlist.title,
            subtitle = playlist.subtitle.takeIf { it.isNotBlank() },
            imageUrl = playlist.thumbnailUrl,
            selected = openedCollectionId == playlist.browseId,
            onClick = { onOpenAccountPlaylist(playlist) },
        )
    } + localPlaylists.map { playlist ->
        CosmoDesktopPlaylistItem(
            title = playlist.title,
            subtitle = songCount(playlist.songs.size),
            imageUrl = playlist.songs.firstOrNull()?.thumbnailUrl,
            selected = openedCollectionId == playlist.id,
            onClick = { onOpenLocalPlaylist(playlist) },
        )
    }

private fun songCount(count: Int): String? = when (count) {
    0 -> null
    1 -> DesktopStrings.format("d_sidebar_one_song", count, fallback = "%1\$d song")
    else -> DesktopStrings.format("d_sidebar_n_songs", count, fallback = "%1\$d songs")
}

/** One key per row that stays with its playlist: its name, with a number after the second row of the same name. */
internal fun stableKeys(titles: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return titles.map { title ->
        val times = (seen[title] ?: 0) + 1
        seen[title] = times
        if (times == 1) title else "$title#$times"
    }
}

// ---- Rows --------------------------------------------------------------------------------------------------------------------

/** A page of the navigation: the Orbit kit's 40 dp chip ([OrbitNavChip]), the selected one solid ink with its small peach dot. */
@Composable
private fun OrbitNavRow(icon: ImageVector, label: String, aside: String?, selected: Boolean, onClick: () -> Unit) {
    OrbitNavChip(icon, label, selected, onClick, aside = aside)
}

/** Lays [content] out [by] wider on each side than its parent allows, and places it back: room for a focus ring inside a scrolling list's clip. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

private val SEARCH_HEIGHT = 40.dp
private val SECTION_GAP = 12.dp
private val CAPTION_HEIGHT = 34.dp
private val NAV_LIST_GAP = 2.dp
private const val EMPTY_SP = 13f
private val RING_ROOM = 6.dp
