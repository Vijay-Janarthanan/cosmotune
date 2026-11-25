package com.cosmotune.ui.home

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import com.cosmotune.ui.orbit.clock
import com.cosmotune.ui.orbit.OrbitRoomLine
import com.cosmotune.ui.orbit.OrbitSpace
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitHeaderSize
import com.cosmotune.ui.orbit.OrbitPageHeader
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import com.cosmotune.design.HomePlan
import com.cosmotune.design.TitleArt
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.model.playingShelfItemMatcher
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.archivo_variable
import com.cosmotune.music.ui.components.PullToRefresh
import com.cosmotune.music.ui.components.SignInBanner
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import androidx.compose.ui.platform.testTag
import com.cosmotune.music.ui.components.feedMoreSkeleton
import com.cosmotune.music.ui.components.feedSkeleton
import com.cosmotune.music.ui.components.recentlyPlayedSkeleton
import com.cosmotune.music.ui.components.MessageState
import androidx.compose.ui.graphics.vector.ImageVector
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle
import com.cosmotune.music.ui.screens.localizeCardSubtitle
import com.cosmotune.browse.BrowseSplit
import com.cosmotune.ui.browse.CosmoBrowse
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.room.LocalRoomLauncher
import com.cosmotune.ui.room.RoomLauncher
import com.cosmotune.ui.theme.CoverPaletteLoader
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.cosmoFontFamilies
import com.cosmotune.ui.orbit.*
import org.jetbrains.compose.resources.Font
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * A song another device of the same Cosmotune account was playing, which this one can carry on with: the host reads it from the
 * account (com.cosmotune.cloud.PlaybackHandoff). Home shows it in its top card while this device plays nothing.
 */
class ElsewhereOffer(
    val song: Song,
    /** Where it plays, as a listener says it: "your phone". */
    val where: String,
    val positionMs: Long,
    val durationMs: Long,
    val upNext: Int,
    /** Plays the song here from [positionMs], with the queue that came after it. */
    val onContinue: () -> Unit,
    /** Holding the card: no thanks, show the usual card again. */
    val onPutAside: () -> Unit,
)

val LocalElsewhere = androidx.compose.runtime.compositionLocalOf<ElsewhereOffer?> { null }

/** A link of Home that is not an item of the feed (Liked songs, Downloads): the host knows where it goes. Shown as a chip. */
class HomeQuickLink(val title: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Home in Orbit, the design language taken from the Cosmotune mark (docs: Canvas repo, design/cosmotune/home-orbit): covers are
 * round discs, where a song is up to is the orbit drawn round its disc, and the one main action of a card sits in a round bite cut
 * out of its corner, the cut that makes the logo's crescent. Cards are filled flat with one deep colour taken from their own cover.
 *
 * Every state of the feed: while it loads the greeting, the hero (when something plays) and the chips stand over skeleton shelves; an
 * error shows its message with Retry. The hero needs from the player [onPlayPause] and [playbackTime] (position and length in
 * milliseconds, read in draw).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitHome(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem, String) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    recentlyPlayedLoading: Boolean = false,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    discoveryControls: (@Composable () -> Unit)? = null,
    browse: CosmoBrowse? = null,
    quickLinks: List<HomeQuickLink> = emptyList(),
    onPlayPause: (() -> Unit)? = null,
    playbackTime: (() -> Pair<Long, Long>)? = null,
    /** Another device's song to offer in the top card; the desktop provides it as [LocalElsewhere]. */
    elsewhereOffer: ElsewhereOffer? = LocalElsewhere.current,
    continueEntries: List<ContinueEntry> = emptyList(),
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    // Home sections the listener turned off (Look and colours, Home sections), and the shelves offered there.
    val lookSettings = LocalCosmoShell.current?.settings
    val hiddenHome = lookSettings?.hiddenHome?.collectAsState()?.value.orEmpty()
    val allShelves = (state as? UiState.Success)?.data.orEmpty()
    LaunchedEffect(allShelves) { if (allShelves.isNotEmpty()) lookSettings?.rememberHomeShelves(allShelves.filter { it.items.isNotEmpty() }.map { it.title }) }
    // Songs by an artist the listener blocked, and explicit songs when hidden, are left off the shelves too.
    val blockedArtists = lookSettings?.blockedArtists?.collectAsState()?.value.orEmpty()
    val hideExplicit = lookSettings?.hideExplicit?.collectAsState()?.value ?: false
    val shelves = remember(allShelves, hiddenHome, blockedArtists, hideExplicit) {
        allShelves.filter { it.title !in hiddenHome }.map { shelf ->
            if (blockedArtists.isEmpty() && !hideExplicit) shelf
            else shelf.copy(items = shelf.items.filter { item -> item.song?.let(com.cosmotune.music.data.recommendations.MusicBlocks::allows) ?: true })
        }
    }
    val moods = remember(browse?.state) { (browse?.state as? UiState.Success)?.let { BrowseSplit.moods(it.data) }.orEmpty() }
    val room = LocalRoomLauncher.current
    // Another device's song, while this one plays nothing.
    val elsewhere = elsewhereOffer?.takeIf { !isPlaying }
    val resumableMusic = currentSong?.takeIf {
        !isPlaying && !it.videoId.startsWith("podio:") && !it.videoId.startsWith("read:audio:") &&
            elsewhere == null && onPlayPause != null && com.cosmotune.music.data.recommendations.MusicBlocks.allows(it)
    }
    val resume = listOfNotNull(
        resumableMusic?.let { ContinueEntry(it.title, "Music · ${it.artist}", requireNotNull(onPlayPause)) },
    ) + continueEntries

    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val side = if (wide) 28.dp else 16.dp

            val pick = pickUp(shelves, elsewhere).takeIf { com.cosmotune.settings.CosmoLookSettings.HOME_PICK_UP !in hiddenHome }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(key = "orbit:live") { com.cosmotune.ui.live.LiveNowStrip(Modifier.padding(horizontal = side).padding(top = 10.dp)) }
                item(key = "orbit:greeting") {
                    OrbitGreeting(fonts, look, Modifier.padding(start = side + 4.dp, end = side, top = 6.dp), onSurprise = rememberSurprise(shelves, onItemClick))
                }

                // The top card never repeats the song the mini player already shows: another device's song while this one plays
                // nothing, else where to pick up (the first album or playlist of a "listen again" shelf), else where to start.
                if (pick != null) item(key = "orbit:hero") {
                    PickUpCard(pick, elsewhere, onItemClick, onItemLongPress, Modifier.padding(horizontal = side).padding(top = 16.dp).fillMaxWidth())
                }

                if ((quickLinks.isNotEmpty() || moods.isNotEmpty()) && com.cosmotune.settings.CosmoLookSettings.HOME_CHIPS !in hiddenHome) {
                    item(key = "orbit:chips") { OrbitChips(quickLinks, moods, browse, fonts, look, side) }
                }
                if (resume.isNotEmpty()) item(key = "orbit:continue") {
                    ContinueShelf(resume, fonts, look, Modifier.padding(horizontal = side).padding(top = 18.dp))
                }

                if (state is UiState.Loading) {
                    if (recentlyPlayedLoading) recentlyPlayedSkeleton(listLayout = true)
                    feedSkeleton(firstIsHero = false)
                }
                if (state is UiState.Error) {
                    item(key = "orbit:error") { MessageState(state.message, actionLabel = "Retry", onAction = onRetry) }
                }

                fun LazyListScope.controls() {
                    if (discoveryControls != null) item(key = "orbit:discovery") { Column(Modifier.padding(horizontal = side - 16.dp, vertical = 12.dp)) { discoveryControls() } }
                }
                controls()

                val styles = planStyles(shelves)
                // The room, as one line, after the first shelf (Quick picks), where it no longer pushes the picks down.
                val roomAfter = shelves.indexOfFirst { it.items.isNotEmpty() }
                fun LazyListScope.signIn() {
                    if (!signedIn && onSignIn != null) item(key = "orbit:sign-in") {
                        SignInBanner(onSignIn = onSignIn, modifier = Modifier.padding(horizontal = side - 16.dp).padding(top = 18.dp))
                    }
                }
                if (roomAfter < 0) signIn()
                if (room != null && roomAfter < 0) item(key = "orbit:room") { OrbitRoomLine(room, fonts, Modifier.padding(horizontal = side).padding(top = OrbitSpace.section).fillMaxWidth()) }
                shelves.forEachIndexed { index, shelf ->
                    if (shelf.items.isEmpty()) return@forEachIndexed
                    item(key = "orbit:shelf:" + shelf.title + index) {
                        val open: (ShelfItem) -> Unit = { onItemClick(it, shelf.title) }
                        Column {
                            if (styles[index] == ShelfStyle.SONGS) {
                                OrbitQuickPicks(shelf, localizeShelfTitle(shelf.title), fonts, look, side, currentSong, isPlaying, open, onItemLongPress)
                                return@Column
                            }
                            OrbitSectionHeader(localizeShelfTitle(shelf.title), localizeShelfSubtitle(shelf.subtitle), fonts, look, Modifier.padding(horizontal = side + 4.dp).padding(top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent))
                            when (styles[index]) {
                                ShelfStyle.SONGS -> Unit
                                ShelfStyle.ARTISTS -> OrbitArtistRow(shelf.items, fonts, look, side, open)
                                ShelfStyle.RANKED -> OrbitRanked(shelf, wide, fonts, look, open, onItemLongPress, Modifier.padding(horizontal = side))
                                ShelfStyle.FEATURE -> OrbitFeature(shelf, wide, fonts, look, side, open, onItemLongPress)
                                ShelfStyle.DISCS -> OrbitDiscRow(shelf, fonts, look, side, open, onItemLongPress)
                                ShelfStyle.CARDS -> OrbitCardRow(shelf, fonts, side, open, onItemLongPress)
                            }
                        }
                    }
                    if (room != null && index == roomAfter) item(key = "orbit:room") {
                        OrbitRoomLine(room, fonts, Modifier.padding(horizontal = side).padding(top = OrbitSpace.section).fillMaxWidth())
                    }
                    if (index == roomAfter) signIn()
                }
                if (loadingMore) feedMoreSkeleton()
                item(key = "orbit:end") { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    if (onLoadMore != null && state is UiState.Success) {
        // CosmoHome's load-more effect, unchanged: re-checked on every layout change, so a short page does not dead-end the feed.
        val loadMore by rememberUpdatedState(onLoadMore)
        LaunchedEffect(listState, loadingMore) {
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (!loadingMore && total > 0 && lastVisible >= total - 3) loadMore()
            }
        }
    }
}

// ---- greeting -------------------------------------------------------------------------------------------------------------------

@Composable
private fun OrbitGreeting(fonts: OrbitFonts, look: OrbitLook, modifier: Modifier, onSurprise: (() -> Unit)?) {
    // The clock in the line moves on with the minute (it stood at the time Home was first drawn), and the greeting with the hour.
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            val current = LocalDateTime.now()
            value = current
            kotlinx.coroutines.delay(((60 - current.second) * 1000L).coerceAtLeast(1000L))
        }
    }
    val part = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Late night"
    }
    val line = remember(now) { now.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)) }
    OrbitPageHeading(part, line, fonts, look, modifier) {
        if (onSurprise != null) OrbitRoundButton(CosmoIcons.Shuffle, "Surprise me", look.ink, onSurprise, Modifier.testTag("music:home:surprise"), onInk = look.ground, touch = 48.dp)
    }
}

/** "Surprise me": plays a song picked at random from Home's song shelves; null while there are none. */
@Composable
private fun rememberSurprise(shelves: List<HomeShelf>, onItemClick: (ShelfItem, String) -> Unit): (() -> Unit)? {
    val songs = remember(shelves) { shelves.flatMap { shelf -> shelf.items.filter { it.videoId != null }.map { it to shelf.title } } }
    if (songs.isEmpty()) return null
    return { songs.random().let { (item, shelf) -> onItemClick(item, shelf) } }
}

// ---- the pick-up card -------------------------------------------------------------------------------------------------------

/** What Home's top card offers: an item of the feed to pick up or start from, with its mono line. */
private class PickUp(val item: ShelfItem?, val label: String, val shelfTitle: String)

private val PICK_UP_WORDS = Regex("\\b(listen again|recent|recently|back|keep listening|pick up|jump back)\\b", RegexOption.IGNORE_CASE)

/**
 * Home's top card: [elsewhere] (another device's song) first; else the first album or playlist of a shelf that is the person's own
 * recent listening ("Listen again"), "PICK UP · PLAYLIST"; else the first item of the first shelf, "START HERE · QUICK PICKS".
 * Never the loaded song: the mini player shows that already.
 */
@Composable
private fun pickUp(shelves: List<HomeShelf>, elsewhere: ElsewhereOffer?): PickUp? {
    val first = shelves.firstOrNull { it.items.isNotEmpty() }
    val from = localizeShelfTitle(first?.title.orEmpty())
    return remember(shelves, elsewhere, from) {
        if (elsewhere != null) return@remember PickUp(null, "", "")
        val again = shelves.takeUnless { first?.sourceId?.startsWith("discovery:") == true }
            ?.firstOrNull { PICK_UP_WORDS.containsMatchIn(it.title) }?.let { shelf ->
            shelf.items.firstOrNull { it.browseId != null && it.browseId?.startsWith("UC") != true }?.let { it to shelf.title }
        }
        when {
            again != null -> PickUp(again.first, "Pick up · ${kindOf(again.first)}", again.second)
            first != null -> PickUp(first.items.first(), "Start here · $from", first.title)
            else -> null
        }
    }
}

private fun kindOf(item: ShelfItem): String = when {
    item.videoId != null && item.browseId == null -> "song"
    item.browseId?.startsWith("MPRE") == true -> "album"
    else -> "playlist"
}

/**
 * The pick-up card: the compact header in its pick-up size ([OrbitHeaderSize.PICK_UP], 160 dp, no actions), in the item's own colour.
 * For another device's song the mono line says where it plays and how far it got; Play (or a tap) carries on with it, a hold puts it
 * aside. For an item a tap opens (or plays) it and a hold opens its menu.
 */
@Composable
private fun PickUpCard(pick: PickUp, elsewhere: ElsewhereOffer?, onItemClick: (ShelfItem, String) -> Unit, onItemLongPress: ((ShelfItem) -> Unit)?, modifier: Modifier) {
    if (elsewhere != null) {
        val song = elsewhere.song
        val next = if (elsewhere.upNext > 0) " · ${elsewhere.upNext} up next" else ""
        val time = if (elsewhere.durationMs > 0) " · ${clock(elsewhere.positionMs)} / ${clock(elsewhere.durationMs)}" else ""
        OrbitPageHeader(
            cover = song.thumbnailUrl, label = "On ${elsewhere.where}$time$next", title = song.title, subtitle = song.artist,
            playing = false, onPlay = elsewhere.onContinue, modifier = modifier.testTag("music:home:hero"), size = OrbitHeaderSize.PICK_UP,
            onClick = elsewhere.onContinue, onLongClick = elsewhere.onPutAside,
        )
        return
    }
    val item = pick.item ?: return
    val open = { onItemClick(item, pick.shelfTitle) }
    OrbitPageHeader(
        cover = item.thumbnailUrl, label = pick.label, title = item.title, subtitle = localizeCardSubtitle(item.subtitle).ifBlank { null },
        playing = false, onPlay = open, modifier = modifier.testTag("music:home:hero"), size = OrbitHeaderSize.PICK_UP,
        onClick = open, onLongClick = onItemLongPress?.let { held -> { held(item) } },
    )
}

// ---- chips: the quick links, then the moods -------------------------------------------------------------------------------------

@Composable
private fun OrbitChips(links: List<HomeQuickLink>, moods: List<MoodGenre>, browse: CosmoBrowse?, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    LazyRow(Modifier.padding(top = 16.dp), contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(links, key = { _, l -> "link:" + l.title }) { _, link ->
            OrbitChip(link.title, fonts, look, onClick = link.onClick) { Icon(link.icon, null, tint = look.accent, modifier = Modifier.size(18.dp)) }
        }
        if (browse != null) itemsIndexed(moods, key = { _, m -> "mood:" + m.browseId + m.params }) { _, mood ->
            OrbitChip(mood.title, fonts, look, onClick = { browse.onOpen(mood) }) {
                if (mood.thumbnailUrl != null) Disc(mood.thumbnailUrl, 24.dp)
                else Box(Modifier.size(24.dp).clip(CircleShape).background(mood.stripeColor?.let { Color(it.toInt()) } ?: look.accent))
            }
        }
    }
}


/**
 * A song shelf as Quick picks (the Music board's 1): pages of four flat rows that swipe sideways, the next page peeking at the edge,
 * under the shelf's heading with a dot for each page (the one in view long). Every song of the shelf is a swipe away, none behind
 * "Show all".
 */
@Composable
private fun OrbitQuickPicks(
    shelf: HomeShelf, title: String, fonts: OrbitFonts, look: OrbitLook, side: Dp, current: Song?, playing: Boolean,
    onClick: (ShelfItem) -> Unit, onLongPress: ((ShelfItem) -> Unit)?,
) {
    val pages = remember(shelf.items) { shelf.items.chunked(QUICK_PICKS_PER_PAGE) }
    val isPlaying = remember(shelf.items, current) { playingShelfItemMatcher(shelf.items, current) }
    val state = rememberLazyListState()
    val page by remember(state) { derivedStateOf { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > 0 && state.canScrollForward.not()) 1 else 0 } }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = side + 4.dp).padding(top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent), verticalAlignment = Alignment.CenterVertically) {
            OrbitSectionHeader(title, "", fonts, look, Modifier.weight(1f))
            if (pages.size > 1) PageDots(pages.size, page.coerceIn(0, pages.lastIndex), look)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A page is the width less the gutter and a 40 dp peek of the next; never wider than a row needs to be.
            val pageWidth = (maxWidth - side * 2 - if (pages.size > 1) 40.dp else 0.dp).coerceAtMost(420.dp)
            LazyRow(
                state = state,
                contentPadding = PaddingValues(horizontal = side),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
                modifier = Modifier.testTag("music:home:quick-picks"),
            ) {
                itemsIndexed(pages, key = { index, _ -> "qp:${shelf.title}:$index" }) { _, items ->
                    Column(Modifier.width(pageWidth)) {
                        items.forEach { item ->
                            OrbitSongRow(
                                item.title, item.subtitle, item.thumbnailUrl, fonts, look, onClick = { onClick(item) }, modifier = Modifier.fillMaxWidth(),
                                now = isPlaying(item), playing = playing, onLongClick = onLongPress?.let { { it(item) } },
                                trailing = { if (onLongPress != null) OrbitRoundButton(CosmoIcons.MoreVertical, "More", look.dim, { onLongPress(item) }, size = 40.dp, onInk = look.ground) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val QUICK_PICKS_PER_PAGE = 4

/** One dot a page, the one in view a 16 dp bar. */
@Composable
private fun PageDots(count: Int, current: Int, look: OrbitLook) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.semantics { contentDescription = "Page ${current + 1} of $count" }) {
        repeat(count.coerceAtMost(8)) { i ->
            Box(Modifier.size(width = if (i == current) 16.dp else 6.dp, height = 6.dp).clip(CircleShape).background(if (i == current) look.ink else look.ink.copy(alpha = 0.28f)))
        }
    }
}

@Composable
private fun OrbitArtistRow(items: List<ShelfItem>, fonts: OrbitFonts, look: OrbitLook, side: Dp, onClick: (ShelfItem) -> Unit) =
    OrbitPortraitRow(items, fonts, look, side, onClick)

// ---- which layout a shelf gets --------------------------------------------------------------------------------------------------

private enum class ShelfStyle { SONGS, ARTISTS, RANKED, FEATURE, DISCS, CARDS }

private val RANKED_WORDS = Regex("\\b(top|charts?|trending|hot|viral)\\b", RegexOption.IGNORE_CASE)

/**
 * One layout per kind of shelf, so the page does not repeat itself: songs as rows, artists as portraits, a chart as a ranked list,
 * and the other albums and playlists taking turns as a featured card, a row of records and a row of colour cards.
 */
private fun planStyles(shelves: List<HomeShelf>): List<ShelfStyle> {
    var turn = 0
    return shelves.map { s ->
        when {
            s.items.isNotEmpty() && s.items.all { it.browseId?.startsWith("UC") == true } -> ShelfStyle.ARTISTS
            s.items.isNotEmpty() && s.items.all { it.videoId != null } -> ShelfStyle.SONGS
            RANKED_WORDS.containsMatchIn(s.title) -> ShelfStyle.RANKED
            else -> when (turn++ % 3) {
                0 -> if (s.items.size >= 3) ShelfStyle.FEATURE else ShelfStyle.DISCS
                1 -> ShelfStyle.DISCS
                else -> ShelfStyle.CARDS
            }
        }
    }
}

/** Playlists: tall cards filled with their cover's colour (the kit's [OrbitColourCardRow]). */
@Composable
private fun OrbitCardRow(shelf: HomeShelf, fonts: OrbitFonts, side: Dp, onClick: (ShelfItem) -> Unit, onLongPress: ((ShelfItem) -> Unit)?) =
    OrbitColourCardRow(shelf.items, fonts, side, onClick, onLongPress)

private val FeatureBite = BiteShape(corner = 30.dp, bite = 34.dp, inset = 40.dp)

/** The first item large, in its cover's colour, with the bite holding its play button; the rest follow as small records. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrbitFeature(shelf: HomeShelf, wide: Boolean, fonts: OrbitFonts, look: OrbitLook, side: Dp, onClick: (ShelfItem) -> Unit, onLongPress: ((ShelfItem) -> Unit)?) {
    val first = shelf.items.first()
    val (fill, ink) = coverFill(first.thumbnailUrl, first.title)
    Column {
        Box(Modifier.padding(horizontal = side).fillMaxWidth().height(if (wide) 236.dp else 214.dp)) {
            Box(
                Modifier.fillMaxSize().clip(FeatureBite).background(fill)
                    .combinedClickable(onClick = { onClick(first) }, onLongClick = onLongPress?.let { { it(first) } }),
            ) {
                Disc(first.thumbnailUrl, if (wide) 196.dp else 170.dp, Modifier.align(Alignment.CenterEnd).offset(x = if (wide) (-104).dp else 46.dp))
                Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp, end = if (wide) 330.dp else 150.dp)) {
                    Text("FEATURED", style = fonts.label, color = ink.copy(alpha = 0.72f))
                    // The featured album's title art, in the plain title's room (three lines of 24 sp).
                    AlbumTitleArt(
                        TitleArtAlbum(first.title, first.thumbnailUrl, artist = first.subtitle), ink = ink, ground = fill,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth(), place = TitleArt.Place.FEATURE,
                        maxSize = 32.sp, minSize = 18.sp, maxLines = 3, maxHeight = 78.dp,
                    )
                    if (first.subtitle.isNotBlank()) Text(first.subtitle, style = fonts.body, color = ink.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(13.dp).size(54.dp).clip(CircleShape)
                    .background(if (look.dark) PEACH else Color(0xFF1D1416)).clickable { onClick(first) },
                contentAlignment = Alignment.Center,
            ) { Icon(CosmoIcons.Play, contentDescription = "Play", tint = if (look.dark) Color(0xFF2A100A) else PEACH, modifier = Modifier.size(22.dp)) }
        }
        val rest = shelf.items.drop(1)
        if (rest.isNotEmpty()) {
            LazyRow(Modifier.padding(top = 16.dp), contentPadding = PaddingValues(horizontal = side + 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(rest, key = { i, it -> (it.browseId ?: it.videoId ?: it.title) + i }) { _, item ->
                    Column(Modifier.width(112.dp).combinedClickable(onClick = { onClick(item) }, onLongClick = onLongPress?.let { { it(item) } })) {
                        Disc(item.thumbnailUrl, 112.dp)
                        Text(item.title, style = fonts.body.copy(fontWeight = FontWeight(700)), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}

/** Albums as records: the cover as a disc with the play moon on its edge (the kit's [OrbitRecordRow]). */
@Composable
private fun OrbitDiscRow(shelf: HomeShelf, fonts: OrbitFonts, look: OrbitLook, side: Dp, onClick: (ShelfItem) -> Unit, onLongPress: ((ShelfItem) -> Unit)?) =
    OrbitRecordRow(shelf.items, fonts, look, side, onClick, onLongPress)

/** A chart: big narrow numbers, the first in peach, five at first (ten in two columns when wide), the rest behind "Show all". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrbitRanked(shelf: HomeShelf, wide: Boolean, fonts: OrbitFonts, look: OrbitLook, onClick: (ShelfItem) -> Unit, onLongPress: ((ShelfItem) -> Unit)?, modifier: Modifier) {
    var all by rememberSaveable(shelf.title) { mutableStateOf(false) }
    val first = if (wide) 10 else 5
    val shown = if (all) shelf.items else shelf.items.take(first)
    val rows: @Composable (List<IndexedValue<ShelfItem>>, Modifier) -> Unit = { part, m ->
        Column(m) {
            part.forEach { (i, item) ->
                OrbitRankedRow(i + 1, item.title, item.subtitle, item.thumbnailUrl, fonts, look, onClick = { onClick(item) }, onLongClick = onLongPress?.let { { it(item) } })
            }
        }
    }
    Column(modifier) {
        val indexed = shown.withIndex().toList()
        if (wide) {
            val half = (indexed.size + 1) / 2
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                rows(indexed.take(half), Modifier.weight(1f))
                rows(indexed.drop(half), Modifier.weight(1f))
            }
        } else rows(indexed, Modifier.fillMaxWidth())
        if (shelf.items.size > first) OrbitShowAll(all, shelf.items.size, fonts, look, onToggle = { all = !all }, modifier = Modifier.padding(top = 6.dp))
    }
}
