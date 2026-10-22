package com.cosmotune.desktop

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.ColorMix
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.CssGradient
import com.cosmotune.design.ShellMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoColors
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
 * depend on it), no glass, no rule beside the page: it stands on the ambient ground. From the top: the window's own caption
 * when the application draws its frame and there is no separate title bar (the old rule), the search field (44 dp, the cap
 * "Ctrl K" outside macOS), the [nav] rows, the Library heading with the [playlists] under it, and Settings at the bottom, 14 dp
 * above the window's edge: the player bar floats beside the sidebar (it starts 16 dp to its right), never under it, so no
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
                    SidebarNavRow(if (selected) item.selectedIcon else item.icon, item.label, item.aside, selected, item.onClick)
                }
            }
            Spacer(Modifier.height(SECTION_GAP))
            Text(
                text = DesktopStrings["library", "Library"],
                style = LocalCosmoType.current.label.copy(fontSize = LIBRARY_SP.sp, fontWeight = FontWeight(LIBRARY_WEIGHT)),
                color = Color(LocalCosmoColors.current.palette.textPrimary),
                maxLines = 1,
                modifier = Modifier.padding(start = 14.dp, end = 2.dp, top = 8.dp, bottom = 4.dp),
            )
            val keys = remember(playlists) { stableKeys(playlists.map { it.title }) }
            // A focused row's ring lies outside it: the list is wider than its column by the room the ring needs, and its content is inset by the same.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).bleed(RING_ROOM),
                contentPadding = PaddingValues(horizontal = RING_ROOM, vertical = RING_ROOM),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (playlists.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = DesktopStrings["d_sidebar_no_playlists", "Your playlists will appear here"],
                            style = LocalCosmoType.current.body().copy(fontSize = EMPTY_SP.sp, lineHeight = EMPTY_SP.sp * 1.4f),
                            color = Color(LocalCosmoColors.current.palette.textTertiary),
                            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp),
                        )
                        nav.firstOrNull { it.icon == com.cosmotune.ui.components.CosmoIcons.Library }?.let { library ->
                            com.cosmotune.ui.components.CosmoButton(
                                text = DesktopStrings["library", "Library"],
                                onClick = library.onClick,
                                kind = com.cosmotune.ui.components.CosmoButtonKind.TEXT,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                } else {
                    itemsIndexed(playlists, key = { index, _ -> keys[index] }) { _, playlist -> SidebarPlaylistRow(playlist) }
                }
            }
            Spacer(Modifier.height(8.dp))
            SidebarNavRow(CosmoIcons.Settings, DesktopStrings["settings", "Settings"], null, settingsSelected, onOpenSettings)
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

/** `.d2-nav button`: 44 dp, radius 22, the icon and the label 12 dp apart; selected, the tonal fill with `onAccentContainer`. */
@Composable
private fun SidebarNavRow(icon: ImageVector, label: String, aside: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.collectIsHoveredAsState()
    val pressed = interaction.collectIsPressedAsState()
    val shape = remember { RoundedCornerShape(NAV_RADIUS) }
    val ink = Color(if (selected) palette.onAccentContainer else palette.textSecondary)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = NAV_HEIGHT)
            .cosmoFocusRing(interaction, NAV_RADIUS)
            .clip(shape)
            .drawBehind { drawRowFill(colors, selected, pressed.value, hovered.value) }
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = NAV_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NAV_GAP),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(NAV_ICON))
        Text(
            text = label,
            style = type.forText(type.label.copy(fontSize = NAV_LABEL_SP.sp, fontWeight = FontWeight(NAV_LABEL_WEIGHT)), label),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (aside != null) {
            Text(
                text = aside,
                style = type.label.copy(fontSize = ASIDE_SP.sp),
                color = Color(palette.accentStrong),
                maxLines = 1,
            )
        }
    }
}

/** `.d2-li`: a 40 dp cover and two lines, padding 6 10, radius 16, the cover and the text 12 dp apart; selected, the tonal fill. */
@Composable
private fun SidebarPlaylistRow(item: CosmoDesktopPlaylistItem) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.collectIsHoveredAsState()
    val pressed = interaction.collectIsPressedAsState()
    val shape = remember { RoundedCornerShape(ITEM_RADIUS) }
    val title = Color(if (item.selected) palette.onAccentContainer else palette.textPrimary)
    val subtitle = Color(if (item.selected) palette.onAccentContainer else palette.textSecondary)
    Row(
        Modifier
            .fillMaxWidth()
            .cosmoFocusRing(interaction, ITEM_RADIUS)
            .clip(shape)
            .drawBehind { drawRowFill(colors, item.selected, pressed.value, hovered.value) }
            .selectable(selected = item.selected, interactionSource = interaction, indication = null, role = Role.Button, onClick = item.onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = ITEM_PADDING_X, vertical = ITEM_PADDING_Y),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ITEM_GAP),
    ) {
        if (item.imageUrl != null) {
            CoverImage(url = item.imageUrl, size = COVER_SIZE, radius = COVER_RADIUS, shadow = false)
        } else {
            // No picture yet: a tonal tile with the queue glyph, the size of a cover.
            Box(
                Modifier
                    .size(COVER_SIZE)
                    .clip(SuperellipseShape(COVER_RADIUS))
                    .drawBehind { drawRect(tonalBrush(colors)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(CosmoIcons.Queue, contentDescription = null, tint = Color(palette.onAccentContainer), modifier = Modifier.size(NAV_ICON))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = type.forText(type.label.copy(fontSize = ITEM_TITLE_SP.sp, fontWeight = FontWeight(ITEM_TITLE_WEIGHT)), item.title),
                color = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle != null) {
                Text(
                    text = item.subtitle,
                    style = type.forText(type.body().copy(fontSize = ITEM_SUBTITLE_SP.sp, lineHeight = ITEM_SUBTITLE_SP.sp * 1.3f), item.subtitle),
                    color = subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---- Drawing -----------------------------------------------------------------------------------------------------------------

/** What lies behind a row: the tonal fill while it is selected, else a wash of ink while the pointer is on it or presses it. */
private fun DrawScope.drawRowFill(colors: CosmoColors, selected: Boolean, pressed: Boolean, hovered: Boolean) {
    if (selected) {
        drawRect(tonalBrush(colors))
    } else if (pressed || hovered) {
        drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = if (pressed) PRESSED_WASH else HOVER_WASH))
    }
}

/**
 * The tonal fill of the B controls: `mix(accentContainer, accent, 0.09)` to `accentContainer` at 160 degrees, interpolated in
 * OKLab. The shared one is internal to `sharedUi`, so this is its twin; the colours are the current palette's, read while drawing.
 */
private fun DrawScope.tonalBrush(colors: CosmoColors): Brush {
    val container = colors.live(CoverField.ACCENT_CONTAINER).toArgb()
    val start = ColorMix.mix(container, colors.live(CoverField.ACCENT).toArgb(), ControlMetrics.TONAL_START_MIX)
    val line = FloatArray(4)
    CssGradient.endpoints(TONAL_ANGLE, size.width, size.height, line)
    return Brush.linearGradient(
        ColorMix.stops(start, container, TONAL_STOPS).map { Color(it) },
        start = Offset(line[0], line[1]),
        end = Offset(line[2], line[3]),
    )
}

/** Lays [content] out [by] wider on each side than its parent allows, and places it back: room for a focus ring inside a scrolling list's clip. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

private val SEARCH_HEIGHT = 44.dp
private val SECTION_GAP = 14.dp
private val CAPTION_HEIGHT = 34.dp
private val NAV_HEIGHT = 44.dp
private val NAV_RADIUS = 22.dp
private val NAV_PADDING = 14.dp
private val NAV_GAP = 12.dp
private val NAV_ICON = 20.dp
private val NAV_LIST_GAP = 2.dp
private const val NAV_LABEL_SP = 14.5f
private const val NAV_LABEL_WEIGHT = 650
private const val ASIDE_SP = 12f
private const val LIBRARY_SP = 15f
private const val LIBRARY_WEIGHT = 700
private val ITEM_PADDING_X = 10.dp
private val ITEM_PADDING_Y = 6.dp
private val ITEM_RADIUS = 16.dp
private val ITEM_GAP = 12.dp
private val COVER_SIZE = 40.dp
private val COVER_RADIUS = 10.dp
private const val ITEM_TITLE_SP = 14f
private const val ITEM_TITLE_WEIGHT = 650
private const val ITEM_SUBTITLE_SP = 12.5f
private const val EMPTY_SP = 13f
private val RING_ROOM = 6.dp
private const val TONAL_ANGLE = 160f
private const val TONAL_STOPS = 5
private const val HOVER_WASH = 0.08f
private const val PRESSED_WASH = 0.12f
