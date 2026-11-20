package com.cosmotune.manga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import com.cosmotune.ui.orbit.OrbitCardChip
import com.cosmotune.ui.orbit.OrbitCardColors
import com.cosmotune.ui.orbit.OrbitProgressLine
import com.cosmotune.ui.orbit.OrbitRoundButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.ui.kit.KitModeSwitcher
import com.cosmotune.ui.orbit.OrbitBackHeader
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.OrbitRowCover
import com.cosmotune.ui.orbit.OrbitSearchPill
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.WatchBadge
import com.cosmotune.ui.orbit.WatchCard
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchPicture
import com.cosmotune.ui.orbit.WatchProgressLine
import com.cosmotune.ui.orbit.WatchSkeleton
import androidx.compose.ui.platform.testTag
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalCosmoColors

/*
 * The Manga and Read modes drawn from the Orbit kit (Canvas repo, design/cosmotune/batch-be/board.html, section D): the frame
 * with the mode pill and the tabs as chips, the page heading, the back head, covers that keep their tall shape with the
 * "continue" moon off the corner, the chapter and cover rows, the search pill, chips, buttons and messages. Every piece here is a
 * thin wrapper that hands the mode's words to a kit piece; nothing is drawn twice.
 */

/** The mode pill at the start of a phone page's head; a host or a test may put something else there, or null for nothing. */
val LocalReadModeSwitcher = staticCompositionLocalOf<(@Composable () -> Unit)?> { { KitModeSwitcher() } }

/** The Orbit type steps as the Manga and Read pages name them. */
internal object ReadType {
    @Composable fun display() = orbitFonts().greeting
    @Composable fun headline() = orbitFonts().heroTitle
    @Composable fun titleLarge() = orbitFonts().section
    @Composable fun titleMedium() = orbitFonts().title.copy(fontSize = 17.sp, lineHeight = 21.sp)
    @Composable fun rowTitle() = orbitFonts().title
    @Composable fun body() = orbitFonts().body
    @Composable fun meta() = orbitFonts().body.copy(fontSize = 12.5.sp, lineHeight = 16.sp)
    @Composable fun label() = orbitFonts().chip
    @Composable fun overline() = orbitFonts().label
    @Composable fun caption() = orbitFonts().body.copy(fontSize = 11.5.sp, lineHeight = 15.sp)
}

/** The rounding of the few plain surfaces left (sheet rows, banners). */
internal object ReadShape {
    val sm = RoundedCornerShape(16.dp)
    val md = RoundedCornerShape(22.dp)
    val full = CircleShape
}

/** The page side margin and the grid gutter. */
internal val ReadMargin = 16.dp
internal val ReadGutter = 16.dp
/** The smallest cover of a grid: three columns on a phone. */
internal val ReadPosterMin = 100.dp

/**
 * A mode's frame. With [showNavigation] (the phone, the tablet, a narrow desktop window) the mode's tabs stand in the shared
 * navigation ([com.cosmotune.ui.shell.CosmoNavShell]: the bottom bar under 600 dp, the rail from 600 dp) with Music's small player
 * ([com.cosmotune.ui.shell.LocalCosmoMusicMini]) above the bar or along the foot beside the rail, and a root page ([showHead])
 * starts with the mode pill and the account button. Without it (the desktop's sidebar carries the tabs) the page alone. [counts]
 * puts a count on a tab (Manga's Updates). The page gets the room it keeps free at its bottom (the dock or the navigation bar,
 * and a gutter).
 */
@Composable
internal fun ReadModeFrame(
    tabs: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    showNavigation: Boolean,
    showHead: Boolean,
    onMusic: (() -> Unit)?,
    showAccount: Boolean,
    modifier: Modifier = Modifier,
    counts: Map<String, Int> = emptyMap(),
    content: @Composable (PaddingValues) -> Unit,
) {
    if (!showNavigation) {
        // The desktop's sidebar carries the tabs: the page alone.
        Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content(PaddingValues(bottom = 24.dp)) }
        }
        return
    }
    val names = tabs.map { mangaText(if (it == MangaTabs.SOURCES) "More" else it) }
    val navTabs = remember(tabs, names, counts) { tabs.mapIndexed { index, tab -> readNavTab(tab, names[index], counts[tab]) } }
    val music = com.cosmotune.ui.shell.LocalCosmoMusicMini.current
    val stored = com.cosmotune.ui.theme.LocalCosmoShell.current?.settings?.mode?.collectAsState()?.value
    var dockPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val idle = remember { com.cosmotune.music.playback.PlaybackPosition() }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        com.cosmotune.ui.shell.CosmoNavShell(
            windowWidth = maxWidth,
            tabs = navTabs,
            selectedIndex = tabs.indexOf(selected),
            onTabSelected = { onSelect(tabs[it]) },
            bottomChrome = { showTabs, _ ->
                // Music's small player above the bar (Read and Manga play nothing of their own): music that keeps playing has its
                // control on screen. The dock belongs to whichever of the two modes is stored, so it always draws here.
                com.cosmotune.ui.shell.CosmoBottomChrome(
                    tabs = navTabs, selectedIndex = tabs.indexOf(selected), onTabSelected = { onSelect(tabs[it]) },
                    song = music?.song, isPlaying = music?.isPlaying == true, isLoading = music?.isLoading == true,
                    position = music?.position ?: idle, durationMs = music?.durationMs ?: 0L,
                    onPlayPause = { music?.onPlayPause?.invoke() }, onNext = { music?.onNext?.invoke() }, onExpand = { music?.onExpand?.invoke() },
                    controlsLocked = false, onBlockedControl = {}, hazeState = null, onHeightChanged = { dockPx = it },
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = com.cosmotune.ui.shell.CosmoNav.dockMaxWidth(showTabs)).fillMaxWidth(),
                    mode = stored ?: com.cosmotune.design.AppMode.MUSIC,
                    showTabs = showTabs,
                    ownMedia = true,
                )
            },
        ) {
            // The status bar is kept clear on every page, not only the tabs' (a series, a catalogue and a search have no tab head to
            // carry it), so no page's head is drawn under the clock.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                if (showHead) {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = ReadMargin), verticalAlignment = Alignment.CenterVertically) {
                            LocalReadModeSwitcher.current?.invoke()
                            Spacer(Modifier.weight(1f))
                            if (showAccount) ReadAccountAction(onMusic)
                        }
                        com.cosmotune.ui.live.LiveNowStrip(Modifier.padding(horizontal = ReadMargin).padding(top = 12.dp))
                    }
                }
                // The page ends above the dock (its gap to the window's edge included), with the gutter.
                val dock = with(density) { dockPx.toDp() }
                val bottom = maxOf(dock, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) + 24.dp
                Box(Modifier.weight(1f).fillMaxWidth()) { content(PaddingValues(bottom = bottom)) }
            }
        }
    }
}

/** A tab of Read's or Manga's bar and rail: its [name], its two icons and its count (Manga's Updates: the new chapters). */
private fun readNavTab(tab: String, name: String, count: Int?): com.cosmotune.ui.shell.CosmoTab {
    val outlined = androidx.compose.material.icons.Icons.Outlined
    val filled = androidx.compose.material.icons.Icons.Filled
    val (icon, chosen) = when (tab) {
        MangaTabs.UPDATES -> CosmoIcons.Bell to CosmoIcons.BellFill
        MangaTabs.BROWSE -> CosmoIcons.Explore to CosmoIcons.ExploreFill
        MangaTabs.HISTORY -> CosmoIcons.Clock to CosmoIcons.Clock
        MangaTabs.SOURCES -> CosmoIcons.More to CosmoIcons.More
        BookTabs.BOOKMARKS -> outlined.BookmarkBorder to filled.Bookmark
        else -> CosmoIcons.Library to CosmoIcons.LibraryFill
    }
    return com.cosmotune.ui.shell.CosmoTab(name, icon, chosen, count = count)
}

/**
 * A tab's heading (Home's greeting, the shared [OrbitPageHeading]): the mono [label] with the page's round [actions] (+, search) at
 * the end of its row, and the 30 sp [title] with its peach full stop under them, at every width.
 */
@Composable
internal fun ReadPageHead(title: String, label: String, modifier: Modifier = Modifier, actions: (@Composable RowScope.() -> Unit)? = null) {
    OrbitPageHeading(title, label, orbitFonts(), orbitLook(), modifier.fillMaxWidth().padding(start = ReadMargin + 4.dp, end = ReadMargin, top = 18.dp, bottom = 8.dp).testTag("read:head"),
        trailing = actions?.let { a -> { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = a) } })
}

/** The head of a page pushed over a tab: back, the mono [kind], then the page's round [actions]. */
@Composable
internal fun ReadBackHead(kind: String, onBack: () -> Unit, modifier: Modifier = Modifier, backLabel: String = "Back", actions: (@Composable RowScope.() -> Unit)? = null) {
    OrbitBackHeader(mangaText(kind), onBack, modifier.padding(horizontal = ReadMargin).padding(top = 8.dp), backLabel = mangaText(backLabel), trailing = actions)
}

/** The account slot of a phone page: the host's account button ([LocalModeProfileAction]); without one, and given [onMusic], back to Music. */
@Composable
internal fun ReadAccountAction(onMusic: (() -> Unit)?) {
    val profile = LocalModeProfileAction.current
    if (profile != null) profile()
    else if (onMusic != null) ReadIconButton(CosmoIcons.Waveform, "Music", onMusic)
}

/** A sideways-scrolling row of chips, the gutter inside the scroll. */
@Composable
internal fun ReadChipRow(modifier: Modifier = Modifier, inset: Dp = ReadMargin, content: @Composable RowScope.() -> Unit) = OrbitChipRow(inset, modifier, content)

/** A chip that can be the chosen one (an Orbit chip that says it is selected). */
@Composable
internal fun ReadChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    OrbitSelectChip(label, selected, orbitFonts(), orbitLook(), onClick, modifier.semantics { this.selected = selected }, icon = icon)

/** A chip that only acts ("History", "Language: English"), or a quiet badge when it has no [onClick] (a genre). */
@Composable
internal fun ReadPill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    if (onClick != null) OrbitSelectChip(text, false, orbitFonts(), orbitLook(), onClick, modifier, icon = icon)
    else WatchBadge(text, orbitFonts(), orbitLook().dim, modifier)
}

/** The page's search pill ([OrbitSearchPill]) with the page's side margin. */
@Composable
internal fun ReadSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    OrbitSearchPill(value, onValueChange, onSearch, placeholder, orbitFonts(), orbitLook(), focusRequester, modifier.padding(horizontal = ReadMargin, vertical = 4.dp))
}

/**
 * A book or a series as a tall cover ([WatchCard]): the cover, the reading line under it in the accent when [progress] is known,
 * the title on two lines and one [meta] line. [onContinue] hangs the "continue" moon off the cover's corner. [cover] is anything
 * Coil loads (an address, a file); [key] picks the stand-in colour until it loads.
 */
@Composable
internal fun ReadCoverCard(
    title: String,
    meta: String?,
    cover: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    progress: Float? = null,
    onContinue: (() -> Unit)? = null,
    key: String = title,
) {
    WatchCard(title, meta, cover, key, orbitFonts(), orbitLook(), onClick, modifier, width = width, progress = progress, onPlay = onContinue)
}

/** A tall cover on its own (a page's lead, a header): [WatchPicture] at 2:3. */
@Composable
internal fun ReadCover(cover: Any?, title: String, modifier: Modifier = Modifier, radius: Dp = 12.dp) {
    WatchPicture(cover, title, modifier.aspectRatio(2f / 3f).semantics { contentDescription = "Cover of $title" }, radius = radius)
}

/** Covers loading: the grid's geometry as quiet blocks. */
@Composable
internal fun ReadSkeletonGrid(modifier: Modifier = Modifier, columns: Int = 3, rows: Int = 2) {
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                repeat(columns) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WatchSkeleton(look, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        WatchSkeleton(look, Modifier.fillMaxWidth(0.8f).height(12.dp), radius = 6.dp)
                    }
                }
            }
        }
    }
}

/** Rows loading: the flat row's own shape ([OrbitRowSkeleton]), where the rows will land. */
@Composable
internal fun ReadSkeletonRows(count: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 8.dp)) {
        repeat(count) { com.cosmotune.ui.orbit.OrbitRowSkeleton(it) }
    }
}

/** A line of an options or actions sheet: 56 dp, an icon in the dim ink, the label, an optional end. */
@Composable
internal fun ReadSheetRow(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val look = orbitLook()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp)
            .clip(ReadShape.sm)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = look.dim, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Text(label, style = ReadType.rowTitle(), color = look.ink, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A sheet's title with an optional "Done" chip at its end. */
@Composable
internal fun ReadSheetHeader(title: String, onDone: (() -> Unit)? = null, doneLabel: String = "Done") {
    val look = orbitLook()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = ReadType.titleLarge(), color = look.ink, modifier = Modifier.weight(1f).padding(end = 12.dp).semantics { heading() })
        if (onDone != null) ReadButton(doneLabel, onDone)
    }
}

/** A mono group label ("TODAY", "ENGLISH"), 24 dp above and 8 below. */
@Composable
internal fun ReadOverline(text: String, modifier: Modifier = Modifier, inset: Dp = ReadMargin) {
    OrbitLabel(text, orbitFonts(), orbitLook().dim, modifier.padding(start = inset + 4.dp, end = inset, top = 24.dp, bottom = 8.dp).semantics { heading() })
}

/** A titled group of a page ([OrbitPageSection]), with "Show all" at its end when [onSeeAll] is given. */
@Composable
internal fun ReadSection(title: String, modifier: Modifier = Modifier, subtitle: String = "", onSeeAll: (() -> Unit)? = null) {
    OrbitPageSection(mangaText(title), modifier.padding(start = ReadMargin + 4.dp, end = ReadMargin - 8.dp, top = 28.dp, bottom = 12.dp), subtitle = subtitle,
        action = if (onSeeAll != null) mangaText("Show all") else null, onAction = onSeeAll)
}

/** What a chapter's download is doing, for the end of [ReadChapterRow]. */
internal enum class ReadDownload { NONE, AVAILABLE, RUNNING, DONE }

/**
 * A chapter as a flat row (board frame 11, 60 dp or 52 with Compact rows): the chapter's [number] as a small tile where a disc would be (peach for the [next] chapter,
 * the peach row), the title (dim once read) over the [meta] line with the peach dot in front while it is unread or [fresh], and the
 * download at the end (a ring while it runs, the filled mark once done). Tap opens it; a hold is [onLongClick] (Series: select).
 * With [selected] not null the row is choosing: the tile becomes the check and a tap toggles it.
 */
@Composable
internal fun ReadChapterRow(
    title: String,
    meta: String,
    read: Boolean,
    fresh: Boolean,
    download: ReadDownload,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    next: Boolean = false,
    number: String? = null,
    selected: Boolean? = null,
    longClickLabel: String = "Chapter actions",
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    // The row's own press (a tap reads, a hold selects or opens the chapter's actions) is a plain combined click, clipped to the row.
    OrbitSongRow(
        title = title, subtitle = meta, cover = null, fonts = fonts, look = look, onClick = null,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = mangaText(longClickLabel))
            .contextClick(onLongClick)
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        now = next, discSize = 34.dp,
        titleContent = {
            Text(title, style = fonts.title, color = when { next -> look.accent; read -> look.dim; else -> look.ink }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        subtitleLeading = if (!read || fresh) ({ Box(Modifier.size(7.dp).clip(CircleShape).background(look.accent).semantics { contentDescription = if (fresh) "New" else "Unread" }) }) else null,
        discContent = {
            when {
                selected != null -> com.cosmotune.ui.orbit.OrbitCheckMark(selected, look)
                number != null -> Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (next) look.accent else look.chip), contentAlignment = Alignment.Center) {
                    Text(number, style = fonts.label.copy(fontSize = if (number.length > 3) 10.sp else 12.sp), maxLines = 1,
                        color = if (next) androidx.compose.ui.graphics.Color(0xFF2A100A) else if (read) look.dim else look.ink)
                }
                read -> Icon(CosmoIcons.Check, contentDescription = "Read", tint = look.dim, modifier = Modifier.size(15.dp))
                else -> Box(Modifier.size(6.dp).clip(CircleShape).background(look.ink.copy(alpha = 0.22f)))
            }
        },
        trailing = { ReadDownloadButton(download, onDownload) },
    )
}

/** The download at a row's end: start, progress, or remove. */
@Composable
internal fun ReadDownloadButton(download: ReadDownload, onDownload: () -> Unit) {
    val look = orbitLook()
    when (download) {
        ReadDownload.NONE -> Spacer(Modifier.width(8.dp))
        ReadDownload.RUNNING -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "Downloading" }, color = look.accent, strokeWidth = 2.dp)
        }
        ReadDownload.AVAILABLE -> OrbitRoundButton(CosmoIcons.Download, mangaText("Download"), look.ink, onDownload, size = 36.dp)
        ReadDownload.DONE -> OrbitRoundButton(CosmoIcons.Downloaded, mangaText("Remove download"), look.ink, onDownload, size = 36.dp, on = true, onInk = look.ground)
    }
}

/**
 * A cover row (Updates, History, a catalogue's books), flat at the list's row height: the tall cover where a song's disc would be, the [title], the [subtitle],
 * and [trailing] at the end. Tap is [onClick]; [highlighted] is the peach row.
 */
@Composable
internal fun ReadCoverRow(
    title: String,
    subtitle: String,
    cover: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    key: String = title,
    highlighted: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    OrbitSongRow(
        title = title, subtitle = subtitle, cover = null, fonts = orbitFonts(), look = orbitLook(), onClick = onClick, modifier = modifier.fillMaxWidth(),
        now = highlighted, discContent = { OrbitRowCover(cover, key, width = if (com.cosmotune.ui.orbit.orbitCompactRows()) 30.dp else 32.dp) }, trailing = trailing,
    )
}

/** A problem or a note across the page: a row in the ink's quiet fill with the danger dot, an optional action and a close. */
@Composable
internal fun ReadInlineBanner(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    val look = orbitLook()
    val danger = Color(LocalCosmoColors.current.palette.danger)
    Row(
        modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 6.dp).clip(ReadShape.md).background(look.row).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(danger))
        Spacer(Modifier.width(12.dp))
        Text(text, style = ReadType.body(), color = look.ink, modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp, end = 12.dp))
        if (actionLabel != null && onAction != null) ReadButton(actionLabel, onAction, kind = ReadKind.TONAL)
        if (onDismiss != null) { Spacer(Modifier.width(6.dp)); ReadIconButton(CosmoIcons.Close, "Dismiss", onDismiss) }
    }
}

/** An empty page or list: the mono [label], a wide [title], the [message] and an optional action. */
@Composable
internal fun ReadEmpty(title: String, message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null, label: String = "Nothing here yet") {
    Box(modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 12.dp)) {
        WatchMessage(mangaText(label), mangaText(title), mangaText(message), orbitFonts(), orbitLook(), Modifier.fillMaxWidth(), action = actionLabel?.let { mangaText(it) }, onAction = onAction)
    }
}

/** A failure with a way to try again. */
@Composable
internal fun ReadError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, title: String = "Something went wrong", retryLabel: String = "Try again") =
    ReadEmpty(title, message, modifier, actionLabel = retryLabel, onAction = onRetry, label = "Could not load")

/** How a [ReadButton] stands out: solid ink, or the ink's quiet fill. */
internal enum class ReadKind { FILLED, TONAL }

/** A pill button: solid ink with the ground's colour for its words ([ReadKind.FILLED]), or tonal. */
@Composable
internal fun ReadButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ReadKind = ReadKind.FILLED, icon: ImageVector? = null, enabled: Boolean = true) {
    val look = orbitLook()
    OrbitCardChip(text, OrbitCardColors(look.ground, look.ink), onClick = { if (enabled) onClick() },
        modifier = modifier.alpha(if (enabled) 1f else 0.45f).semantics { if (!enabled) disabled() }, icon = icon, solid = kind == ReadKind.FILLED)
}

/** A quiet pill button for a secondary action ("Cancel download", "Remove"). */
@Composable
internal fun ReadTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    ReadButton(text, onClick, modifier, kind = ReadKind.TONAL, enabled = enabled)

/** A round tonal button with its [label] as its description. */
@Composable
internal fun ReadIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false) {
    val look = orbitLook()
    OrbitRoundButton(icon, mangaText(label), look.ink, onClick, modifier, on = on, onInk = look.ground)
}

/** A reading line: how far through, in the accent. */
@Composable
internal fun ReadProgressBar(fraction: Float, modifier: Modifier = Modifier) = WatchProgressLine(fraction, orbitLook(), modifier)

/** A titled column of a sheet's body, with the sheet's side padding. */
@Composable
internal fun ReadSheetSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OrbitLabel(title, orbitFonts(), orbitLook().dim)
        content()
    }
}

/** A 1 dp hairline, inset by [start]. */
@Composable
internal fun ReadHairline(modifier: Modifier = Modifier, start: Dp = ReadMargin) {
    Box(modifier.fillMaxWidth().padding(start = start, end = ReadMargin).height(1.dp).background(orbitLook().ink.copy(alpha = 0.08f)))
}

/** Lets a full-width row (a chip row, a card) inside a padded grid run to the window's edges, under the grid's side margins. */
internal fun Modifier.readBleed(margin: Dp = ReadMargin): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = (margin * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * A level on a line with its moon ([OrbitProgressLine]), snapped to [steps] stops between [range]'s ends and reported all the way
 * through a drag. [label] is what a screen reader hears with the value.
 */
@Composable
internal fun ReadSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int, label: String, modifier: Modifier = Modifier) {
    val span = range.endInclusive - range.start
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    OrbitProgressLine(
        progress = { fraction },
        ink = orbitLook().accent,
        modifier = modifier.padding(vertical = 4.dp).semantics { contentDescription = "$label ${"%.2f".format(value)}" },
        onSeek = { f ->
            val stops = steps + 1
            val snapped = if (steps > 0) Math.round(f * stops) / stops.toFloat() else f
            onChange(range.start + span * snapped)
        },
        live = true,
    )
}
