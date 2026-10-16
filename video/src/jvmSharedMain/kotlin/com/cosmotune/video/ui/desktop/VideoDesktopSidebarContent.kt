package com.cosmotune.video.ui.desktop

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.design.ColorMix
import com.cosmotune.design.ControlMetrics
import com.cosmotune.design.CssGradient
import com.cosmotune.design.ShellMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.blocks.CosmoSearchField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_drawer_settings
import com.cosmotune.video.resources.video_downloads
import com.cosmotune.video.resources.video_history
import com.cosmotune.video.resources.video_no_subscriptions
import com.cosmotune.video.resources.video_search
import com.cosmotune.video.resources.video_shorts
import com.cosmotune.video.resources.video_tab_home
import com.cosmotune.video.resources.video_tab_subscriptions
import com.cosmotune.video.resources.video_watch_later
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.yield
import org.jetbrains.compose.resources.stringResource
import com.music.bitchord.sharedui.resources.Res as SharedRes
import com.music.bitchord.sharedui.resources.playlists

/**
 * The body of the desktop's Video sidebar (spec 5.1), in the look of Music's own (`CosmoDesktopSidebar`, which this copies and does
 * not change): 220 dp wide and no glass, standing on the ambient ground. From the top: the window's caption when the host hands one
 * ([caption]), the search field (44 dp, "Ctrl K" outside macOS), the navigation rows (44 dp, radius 22: Home, Shorts, Subscriptions,
 * History, Watch later, Playlists, Downloads), the "Subscriptions" heading with the channels of the library under it (the one
 * with the newest upload first), and Settings at the bottom. The row of the page on show is selected with the tonal fill.
 *
 * The field takes the focus when [focusSearch] is set (then says so with [onSearchFocused]), when the search shortcut asks
 * through [DesktopVideoState], and again when the window comes back to the front. Enter pushes the search page.
 */
@Composable
internal fun VideoDesktopSidebarContent(focusSearch: Boolean, onSearchFocused: () -> Unit, caption: (@Composable () -> Unit)?) {
    val navigator = DesktopVideoState.navigator
    val route = navigator.top

    val focusRequester = remember { FocusRequester() }
    val asked = focusSearch || DesktopVideoState.focusSearch
    LaunchedEffect(asked) {
        if (asked) {
            runCatching { focusRequester.requestFocus() }
            DesktopVideoState.focusSearch = false
            if (focusSearch) onSearchFocused()
        }
    }
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

    val subscriptions = DesktopVideoState.graph?.library?.subscriptions?.collectAsState()?.value.orEmpty()
    val channels = remember(subscriptions) {
        subscriptions.sortedWith(compareByDescending<Subscription> { it.lastUploadMs ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() })
    }
    val nav = sidebarNav(navigator, route)

    Column(Modifier.width(ShellMetrics.DESKTOP_SIDEBAR_DP.dp).fillMaxHeight()) {
        caption?.invoke()
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = if (caption != null) 4.dp else 16.dp, bottom = ShellMetrics.DESKTOP_BAR_MARGIN_DP.dp),
        ) {
            CosmoSearchField(
                query = DesktopVideoState.query,
                onQueryChange = { DesktopVideoState.query = it },
                onSubmit = { submitSidebarSearch(navigator, DesktopVideoState.query) },
                placeholder = stringResource(Res.string.video_search),
                focusRequester = focusRequester,
                height = SEARCH_HEIGHT,
                keyHint = if (IS_MAC) null else "Ctrl K",
                onFocusChanged = { searchFocused = it },
            )
            Spacer(Modifier.height(SECTION_GAP))
            Column(verticalArrangement = Arrangement.spacedBy(NAV_LIST_GAP)) {
                nav.forEach { item -> SidebarNavRow(if (item.selected) item.selectedIcon else item.icon, item.label, item.selected, item.onClick) }
            }
            Spacer(Modifier.height(SECTION_GAP))
            Text(
                text = stringResource(Res.string.video_tab_subscriptions),
                style = LocalCosmoType.current.label.copy(fontSize = HEADING_SP.sp, fontWeight = FontWeight(HEADING_WEIGHT)),
                color = Color(LocalCosmoColors.current.palette.textPrimary),
                maxLines = 1,
                modifier = Modifier.padding(start = 14.dp, end = 2.dp, top = 8.dp, bottom = 4.dp),
            )
            // A focused row's ring lies outside it: the list is wider than its column by the room the ring needs, and its content is inset by the same.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).bleed(RING_ROOM),
                contentPadding = PaddingValues(horizontal = RING_ROOM, vertical = RING_ROOM),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (channels.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(Res.string.video_no_subscriptions),
                            style = LocalCosmoType.current.body().copy(fontSize = EMPTY_SP.sp, lineHeight = EMPTY_SP.sp * 1.4f),
                            color = Color(LocalCosmoColors.current.palette.textTertiary),
                            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp),
                        )
                    }
                } else {
                    items(channels, key = { it.channelId }) { channel ->
                        SidebarChannelRow(channel, selected = route is VideoRoute.Channel && route.channelId == channel.channelId) {
                            navigator.push(VideoRoute.Channel(channel.channelId))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SidebarNavRow(
                icon = CosmoIcons.Settings,
                label = stringResource(Res.string.video_drawer_settings),
                selected = route == VideoRoute.Settings,
                onClick = { navigator.push(VideoRoute.Settings) },
            )
        }
    }
}

/** Enter in the sidebar's search field. */
internal fun submitSidebarSearch(navigator: VideoNavigator, query: String) {
    navigator.push(VideoRoute.Search(query))   // Y-18 may replace this line
}

/** One navigation row: its two icons (the second while it is [selected]), its [label] and what a click does. */
@Immutable
private data class SidebarNavItem(
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

/** The seven rows (spec 5.1), the selected one following the page on show. Home, Subscriptions and Playlists are the three tab roots. */
@Composable
private fun sidebarNav(navigator: VideoNavigator, route: VideoRoute): List<SidebarNavItem> {
    val home = stringResource(Res.string.video_tab_home)
    val shorts = stringResource(Res.string.video_shorts)
    val subscriptions = stringResource(Res.string.video_tab_subscriptions)
    val history = stringResource(Res.string.video_history)
    val watchLater = stringResource(Res.string.video_watch_later)
    val playlists = stringResource(SharedRes.string.playlists)
    val downloads = stringResource(Res.string.video_downloads)
    return listOf(
        SidebarNavItem(CosmoIcons.Home, CosmoIcons.HomeFill, home, route == VideoRoute.Home) { navigator.push(VideoRoute.Home) },
        SidebarNavItem(VideoIcons.Shorts, VideoIcons.Shorts, shorts, route is VideoRoute.Shorts) { navigator.push(VideoRoute.Shorts(ShortsOrigin.Home)) },
        SidebarNavItem(VideoIcons.Subscriptions, VideoIcons.Subscriptions, subscriptions, route == VideoRoute.Subscriptions) { navigator.push(VideoRoute.Subscriptions) },
        SidebarNavItem(VideoIcons.History, VideoIcons.History, history, route == VideoRoute.History) { navigator.push(VideoRoute.History) },
        SidebarNavItem(CosmoIcons.Clock, CosmoIcons.Clock, watchLater, route == VideoRoute.WatchLater) { navigator.push(VideoRoute.WatchLater) },
        // The Library page is where "Your playlists" are listed: this row is the third tab's root, under the name the sidebar gives it.
        SidebarNavItem(CosmoIcons.Queue, CosmoIcons.Queue, playlists, route == VideoRoute.Library || route == VideoRoute.SavedPlaylists || route is VideoRoute.LocalPlaylist) { navigator.push(VideoRoute.Library) },
        SidebarNavItem(CosmoIcons.Download, CosmoIcons.Download, downloads, route == VideoRoute.Downloads) { navigator.push(VideoRoute.Downloads) },
    )
}

// ---- Rows --------------------------------------------------------------------------------------------------------------------

/** `.d2-nav button`: 44 dp, radius 22, the icon and the label 12 dp apart; selected, the tonal fill with `onAccentContainer`. */
@Composable
private fun SidebarNavRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
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
    }
}

/** A followed channel: a 36 dp avatar circle and its name (14 sp, weight 650); padding 6 10, radius 16, the tonal fill while its page is open. */
@Composable
private fun SidebarChannelRow(channel: Subscription, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val interaction = remember { MutableInteractionSource() }
    val hovered = interaction.collectIsHoveredAsState()
    val pressed = interaction.collectIsPressedAsState()
    val shape = remember { RoundedCornerShape(ITEM_RADIUS) }
    val title = Color(if (selected) palette.onAccentContainer else palette.textPrimary)
    Row(
        Modifier
            .fillMaxWidth()
            .cosmoFocusRing(interaction, ITEM_RADIUS)
            .clip(shape)
            .drawBehind { drawRowFill(colors, selected, pressed.value, hovered.value) }
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = ITEM_PADDING_X, vertical = ITEM_PADDING_Y),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ITEM_GAP),
    ) {
        ChannelAvatar(channel.avatarUrl, channel.name)
        Text(
            text = channel.name,
            style = type.forText(type.label.copy(fontSize = ITEM_TITLE_SP.sp, fontWeight = FontWeight(ITEM_TITLE_WEIGHT)), channel.name),
            color = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A circle with the picture at [url] over a tonal disc with the channel's first letter (also what a failed load leaves). */
@Composable
private fun ChannelAvatar(url: String?, name: String) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    Box(
        Modifier
            .size(AVATAR)
            .clip(CircleShape)
            .drawBehind { drawCircle(colors.live(CoverField.ACCENT_CONTAINER)) },
        contentAlignment = Alignment.Center,
    ) {
        val initial = remember(name) { name.trim().take(1).uppercase() }
        Text(text = initial, style = type.label.copy(fontSize = INITIAL_SP.sp, fontWeight = FontWeight.Bold), color = Color(palette.onAccentContainer))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
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
 * OKLab. The shared one is internal to `sharedUi`, so this is its twin (as the desktop sidebar's is); the colours are the current
 * palette's, read while drawing.
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

private val IS_MAC = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

private val SEARCH_HEIGHT = 44.dp
private val SECTION_GAP = 14.dp
private val NAV_HEIGHT = 44.dp
private val NAV_RADIUS = 22.dp
private val NAV_PADDING = 14.dp
private val NAV_GAP = 12.dp
private val NAV_ICON = 20.dp
private val NAV_LIST_GAP = 2.dp
private const val NAV_LABEL_SP = 14.5f
private const val NAV_LABEL_WEIGHT = 650
private const val HEADING_SP = 15f
private const val HEADING_WEIGHT = 700
private val ITEM_PADDING_X = 10.dp
private val ITEM_PADDING_Y = 6.dp
private val ITEM_RADIUS = 16.dp
private val ITEM_GAP = 12.dp
private val AVATAR = 36.dp
private const val INITIAL_SP = 15f
private const val ITEM_TITLE_SP = 14f
private const val ITEM_TITLE_WEIGHT = 650
private const val EMPTY_SP = 13f
private val RING_ROOM = 6.dp
private const val TONAL_ANGLE = 160f
private const val TONAL_STOPS = 5
private const val HOVER_WASH = 0.08f
private const val PRESSED_WASH = 0.12f
