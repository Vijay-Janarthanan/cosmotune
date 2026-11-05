package com.cosmotune.manga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.cosmotune.design.AppMode
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitModeSwitcher
import com.cosmotune.ui.kit.KitNavRail
import com.cosmotune.ui.shell.CosmoBottomChrome
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import kotlin.math.roundToInt

/*
 * The pieces the Manga and Read modes are drawn with that the shared kit (com.cosmotune.ui.kit) does not have yet, written to
 * docs/design DESIGN_SYSTEM.md (sections 3 and 4): the root top bar with its leading mode slot, the 2:3 cover card with two-line
 * titles and its badges, the pill search field, the chapter row, the cover-washed detail header, the sheet rows, the mode
 * scaffold (dock on a phone, rail on a tablet) and the type steps. They read the Cosmo palette and type only, so they can be
 * promoted to the kit as they are.
 */

/**
 * The mode switcher in the top-left slot of every root page: the shell's [KitModeSwitcher] (which draws nothing without the new
 * look). A host or a test may put something else there, or null for nothing.
 */
val LocalReadModeSwitcher = staticCompositionLocalOf<(@Composable () -> Unit)?> { { KitModeSwitcher() } }

/** The host's docked mini player (whatever is playing in any mode); drawn inside the dock above the tabs. Null: tabs only. */
val LocalReadMiniPlayer = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

/** The One type steps (DESIGN_SYSTEM.md 3.2) as the shell's [com.cosmotune.ui.theme.CosmoType] carries them. */
internal object ReadType {
    @Composable fun display() = LocalCosmoType.current.displayLarge
    @Composable fun headline() = LocalCosmoType.current.headline
    @Composable fun titleLarge() = LocalCosmoType.current.titleLarge
    @Composable fun titleMedium() = LocalCosmoType.current.titleMedium
    @Composable fun rowTitle() = LocalCosmoType.current.rowTitle
    @Composable fun body() = LocalCosmoType.current.bodyText
    @Composable fun meta() = LocalCosmoType.current.meta
    @Composable fun label() = LocalCosmoType.current.control
    @Composable fun overline() = LocalCosmoType.current.overline
    @Composable fun caption() = LocalCosmoType.current.caption
}

/** The radius table (DESIGN_SYSTEM.md 3.4), from [CosmoRadii]. */
internal object ReadShape {
    val xs = RoundedCornerShape(CosmoRadii.rXs)
    val sm = RoundedCornerShape(CosmoRadii.rSm)
    val md = RoundedCornerShape(CosmoRadii.rMd)
    val lg = RoundedCornerShape(CosmoRadii.rLg)
    val full = RoundedCornerShape(50)
}

/** The page side margin and the grid gutter on a compact window. */
internal val ReadMargin = 16.dp
internal val ReadGutter = 12.dp
/** The smallest 2:3 cover of a grid: three columns on a phone, as in Mihon. */
internal val ReadPosterMin = 104.dp

private val BAR_HEIGHT = 64.dp
private val TITLE_REGION = 48.dp

/** How far a page has scrolled its large title away, for [ReadTopBar]: 0 at the top, 1 once the title region has gone by. */
@Composable
internal fun rememberCollapseThreshold(): Float = with(LocalDensity.current) { TITLE_REGION.toPx() }

/**
 * A page's top bar (section 4.1): a 64 dp row (the mode slot or back on its start, the actions on its end) and, when [large], a
 * 48 dp region with the 34 sp light title under it, which folds away as the page scrolls ([collapse] 0..1, read in the layout and
 * draw phases only). Once folded, the small title stands beside the start slot and the bar takes the page's ground and a hairline.
 */
@Composable
internal fun ReadTopBar(
    title: String,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
    large: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalCosmoColors.current.palette
    val ground = Color(palette.backgroundBottom)
    val ink = Color(palette.textPrimary)
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val c = collapse().coerceIn(0f, 1f)
                if (c > 0f) {
                    drawRect(ground.copy(alpha = c))
                    drawRect(ink.copy(alpha = 0.08f * c), topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f))
                }
            }
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(Modifier.fillMaxWidth().height(BAR_HEIGHT).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) KitIconButton(CosmoIcons.ArrowLeft, backLabel, onBack)
            else if (leading != null) Box(Modifier.padding(start = 12.dp, end = 4.dp)) { leading() }
            else Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = ReadType.titleLarge(),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    .graphicsLayer { alpha = if (large) ((collapse() - 0.6f) / 0.4f).coerceIn(0f, 1f) else collapse().coerceIn(0f, 1f) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        if (large) Box(
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val height = (placeable.height * (1f - collapse().coerceIn(0f, 1f))).roundToInt()
                    layout(placeable.width, height) { placeable.place(0, 0) }
                },
        ) {
            Text(
                title,
                style = LocalCosmoType.current.forText(ReadType.display(), title),
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = ReadMargin, end = ReadMargin, bottom = 8.dp)
                    .heightIn(min = 40.dp)
                    .graphicsLayer { alpha = 1f - collapse().coerceIn(0f, 1f) }
                    .semantics { heading() },
            )
        }
    }
}

/**
 * The trailing account slot of a root page: the host's account/profile button ([LocalModeProfileAction]); without one, and only
 * when [onMusic] is given, a way back to Music.
 */
@Composable
internal fun ReadAccountAction(onMusic: (() -> Unit)?) {
    val profile = LocalModeProfileAction.current
    if (profile != null) profile()
    else if (onMusic != null) KitIconButton(CosmoIcons.Waveform, "Music", onMusic)
}

/** A thin, sideways-scrolling row of chips: 16 dp at the start, 8 dp between, never wrapped (section 4.4). */
@Composable
internal fun ReadChipRow(modifier: Modifier = Modifier, inset: Dp = ReadMargin, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = inset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One chip of a [ReadChipRow]: the kit's chip, with a modifier for its test tag. */
@Composable
internal fun ReadChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) =
    KitChip(label, selected, onClick, modifier, icon)

/**
 * The pill search field (section 4.3): 48 dp, `surface2`, a search glyph, the [placeholder] until something is typed, and a clear
 * button. The placeholder is part of the field's node, so it is found by its text.
 */
@Composable
internal fun ReadSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: () -> Unit = {}) {
    val palette = LocalCosmoColors.current.palette
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ReadType.body().copy(color = Color(palette.textPrimary)),
        cursorBrush = SolidColor(Color(palette.accent)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 4.dp),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(ReadShape.full).background(Color(palette.surfaceVariant)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(CosmoIcons.Search, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = ReadType.body(), color = Color(palette.textTertiary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
                if (value.isNotEmpty()) KitIconButton(CosmoIcons.Close, "Clear search", { onValueChange("") })
            }
        },
    )
}

/** Where a series or book stands for a cover's badges: [count] top-left (unread chapters, "NEW"), [done] top-right, [progress] 0..1 along the bottom. */
internal data class ReadCoverBadges(val count: String? = null, val done: Boolean = false, val progress: Float? = null)

/**
 * A 2:3 cover card (section 4.7): the cover with up to two badges and a 3 dp progress bar along its bottom edge, then the title
 * on two lines and one line of [meta]. [cover] is anything Coil loads (a URL, a file); without one the initials stand in.
 */
@Composable
internal fun ReadPosterCard(
    title: String,
    meta: String?,
    cover: Any?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badges: ReadCoverBadges = ReadCoverBadges(),
    width: Dp? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val palette = LocalCosmoColors.current.palette
    Column(
        (if (width != null) modifier.width(width) else modifier.fillMaxWidth())
            .clip(ReadShape.sm)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick),
    ) {
        ReadCover(cover, title, Modifier.fillMaxWidth(), badges)
        Spacer(Modifier.height(8.dp))
        Text(title, style = LocalCosmoType.current.forText(ReadType.rowTitle(), title), color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!meta.isNullOrBlank()) Text(meta, style = ReadType.meta(), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
    }
}

/** A 2:3 cover with its badges, as [ReadPosterCard] draws it; also the lead of rows and the detail headers. */
@Composable
internal fun ReadCover(cover: Any?, title: String, modifier: Modifier = Modifier, badges: ReadCoverBadges = ReadCoverBadges()) {
    val palette = LocalCosmoColors.current.palette
    Box(modifier.aspectRatio(2f / 3f).clip(ReadShape.sm).background(Color(palette.surfaceVariant))) {
        Text(titleInitials(title), style = ReadType.titleLarge(), color = Color(palette.textTertiary), modifier = Modifier.align(Alignment.Center))
        if (cover != null) AsyncImage(model = cover, contentDescription = "Cover of $title", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        badges.count?.let { count ->
            Text(
                count,
                style = ReadType.caption().copy(fontWeight = FontWeight(600)),
                color = Color(palette.onAccent),
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(ReadShape.full).background(Color(palette.accent)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        if (badges.done) Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.56f)),
            contentAlignment = Alignment.Center,
        ) { Icon(CosmoIcons.Check, contentDescription = "Downloaded", tint = Color.White, modifier = Modifier.size(16.dp)) }
        badges.progress?.takeIf { it > 0f }?.let { fraction ->
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.4f))) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Color(palette.accent)))
            }
        }
    }
}

/** A grid of 2:3 covers loading (section 4.15): the same geometry in `surface2`, shimmering unless motion is reduced. */
@Composable
internal fun ReadSkeletonGrid(modifier: Modifier = Modifier, columns: Int = 3, rows: Int = 2) {
    Column(modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(ReadGutter)) {
                repeat(columns) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CosmoShimmer(Modifier.fillMaxWidth().aspectRatio(2f / 3f), ReadShape.sm)
                        CosmoShimmer(Modifier.fillMaxWidth(0.8f).height(12.dp), ReadShape.xs)
                        CosmoShimmer(Modifier.fillMaxWidth(0.5f).height(10.dp), ReadShape.xs)
                    }
                }
            }
        }
    }
}

/**
 * The detail header's cover wash (section 2.3, a content-colour zone): the cover drawn from a 12 px sample and stretched, so it
 * reads as a soft blur of its colours without a blur pass, at low strength, fading into the page's ground by its bottom. Only the
 * header area takes it; the list below is the plain ground.
 */
@Composable
internal fun ReadCoverWash(cover: Any?, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val ground = Color(palette.backgroundBottom)
    Box(modifier.fillMaxWidth().height(320.dp).clipToBounds()) {
        if (cover != null) {
            val context = LocalPlatformContext.current
            val request = remember(cover, context) { ImageRequest.Builder(context).data(cover).size(12, 18).build() }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                alpha = if (LocalCosmoColors.current.dark) 0.42f else 0.30f, modifier = Modifier.matchParentSize())
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(ground.copy(alpha = 0.35f), ground))))
    }
}

/** A row of an action or options sheet (section 4.13): 56 dp, a 24 dp glyph in secondary ink, the label. */
@Composable
internal fun ReadSheetRow(label: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    val palette = LocalCosmoColors.current.palette
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Text(label, style = ReadType.rowTitle(), color = Color(palette.textPrimary), modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A sheet's title row with an optional "Done" at its end (the sheet's own close for long forms). */
@Composable
internal fun ReadSheetHeader(title: String, onDone: (() -> Unit)? = null, doneLabel: String = "Done") {
    val palette = LocalCosmoColors.current.palette
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = ReadType.titleLarge(), color = Color(palette.textPrimary), modifier = Modifier.weight(1f).semantics { heading() })
        if (onDone != null) KitButton(doneLabel, onDone, kind = KitButtonKind.QUIET)
    }
}

/** An overline group header (section 4.10): 11 sp capitals, 24 dp above, 8 dp below. */
@Composable
internal fun ReadOverline(text: String, modifier: Modifier = Modifier, inset: Dp = ReadMargin) {
    Text(text.uppercase(), style = ReadType.overline(), color = Color(LocalCosmoColors.current.palette.textSecondary),
        modifier = modifier.padding(start = inset, end = inset, top = 24.dp, bottom = 8.dp).semantics { heading() })
}

/** A 1 dp hairline, inset by [start] to line up with the text column of the rows it parts. */
@Composable
internal fun ReadHairline(modifier: Modifier = Modifier, start: Dp = ReadMargin) {
    Box(modifier.fillMaxWidth().padding(start = start).height(1.dp).background(Color(LocalCosmoColors.current.palette.textPrimary).copy(alpha = 0.08f)))
}

/** What a chapter's download is doing, for the trailing glyph of [ReadChapterRow]. */
internal enum class ReadDownload { NONE, AVAILABLE, RUNNING, DONE }

/**
 * A chapter row (section 4.6, "Chapter"): a 16 dp column with the accent dot of a new or unread chapter, the title (tertiary ink
 * once read), the [meta] line, and the download glyph at the end. Tap opens the chapter; long-press opens its actions.
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
    current: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Chapter actions")
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (fresh || (!read && current)) Box(Modifier.size(8.dp).clip(CircleShape).background(Color(palette.accent)))
        }
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = LocalCosmoType.current.forText(ReadType.rowTitle(), title),
                color = Color(if (read) palette.textTertiary else if (current) palette.accentStrong else palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) Text(meta, style = ReadType.meta(), color = Color(if (read) palette.textTertiary else palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (download) {
            ReadDownload.NONE -> Spacer(Modifier.width(8.dp))
            ReadDownload.RUNNING -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "Downloading" }, color = Color(palette.textSecondary), strokeWidth = 2.dp)
            }
            ReadDownload.AVAILABLE -> KitIconButton(CosmoIcons.Download, "Download", onDownload)
            ReadDownload.DONE -> KitIconButton(CosmoIcons.Downloaded, "Remove download", onDownload)
        }
    }
}

/** The empty, error and verification states share one centred column; this is its tonal action under a message. */
@Composable
internal fun ReadInlineBanner(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    val palette = LocalCosmoColors.current.palette
    Row(
        modifier.fillMaxWidth().padding(horizontal = ReadMargin, vertical = 6.dp).clip(ReadShape.md).background(Color(palette.surfaceVariant)).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(palette.danger)))
        Spacer(Modifier.width(12.dp))
        Text(text, style = ReadType.body(), color = Color(palette.textPrimary), modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (actionLabel != null && onAction != null) KitButton(actionLabel, onAction, kind = KitButtonKind.QUIET)
        if (onDismiss != null) KitIconButton(CosmoIcons.Close, "Dismiss", onDismiss)
    }
}

/**
 * A mode's frame (sections 4.16, 4.17): on a phone the page with the shared dock ([CosmoBottomChrome], its labelled tabs and the
 * host's mini player) over its bottom; from 600 dp the tabs as a rail on the start edge; with [showNavigation] false (the desktop,
 * whose sidebar carries the tabs) the page alone. [showTabs] false hides them (the reader). The page gets the room it must keep
 * free at its bottom.
 */
@Composable
internal fun ReadModeScaffold(
    tabs: List<CosmoTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    showNavigation: Boolean,
    showTabs: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val palette = LocalCosmoColors.current.palette
    BoxWithConstraints(modifier.fillMaxSize().background(Color(palette.backgroundBottom))) {
        val wide = maxWidth >= 600.dp
        when {
            !showNavigation || !showTabs -> content(PaddingValues())
            wide -> Row(Modifier.fillMaxSize()) {
                KitNavRail(tabs, selectedIndex, onSelect, Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) { content(PaddingValues(bottom = 16.dp)) }
            }
            else -> {
                var dockPx by remember { mutableIntStateOf(0) }
                val density = LocalDensity.current
                val clearance = if (dockPx <= 0) 112.dp else with(density) { dockPx.toDp() } + 16.dp
                content(PaddingValues(bottom = clearance))
                val position = remember { PlaybackPosition() }
                val mode = LocalCosmoShell.current?.settings?.mode?.collectAsState()?.value ?: AppMode.MUSIC
                CosmoBottomChrome(
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    onTabSelected = onSelect,
                    song = null,
                    isPlaying = false,
                    isLoading = false,
                    position = position,
                    durationMs = 0L,
                    onPlayPause = {},
                    onNext = {},
                    onExpand = {},
                    controlsLocked = false,
                    onBlockedControl = {},
                    hazeState = null,
                    onHeightChanged = { dockPx = it },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    // The dock draws for the mode on screen, whichever of Read and Manga the host stores it as.
                    mode = mode,
                    miniPlayer = LocalReadMiniPlayer.current,
                )
            }
        }
    }
}

/** A titled column for a sheet's body, with the sheet's side padding. */
@Composable
internal fun ReadSheetSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = ReadType.overline(), color = Color(LocalCosmoColors.current.palette.textSecondary))
        content()
    }
}

/** Lets a full-width row (a chip row) inside a padded grid run to the window's edges, under the grid's side margins. */
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
