package com.cosmotune.ui.home

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
import com.cosmotune.music.ui.components.feedMoreSkeleton
import com.cosmotune.music.ui.components.feedSkeleton
import com.cosmotune.music.ui.components.recentlyPlayedSkeleton
import com.cosmotune.music.ui.components.MessageState
import androidx.compose.ui.graphics.vector.ImageVector
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle
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
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val shelves = (state as? UiState.Success)?.data.orEmpty()
    val moods = remember(browse?.state) { (browse?.state as? UiState.Success)?.let { BrowseSplit.moods(it.data) }.orEmpty() }
    val room = LocalRoomLauncher.current

    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState, modifier = modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            val side = if (wide) 28.dp else 16.dp

            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                item(key = "orbit:greeting") { OrbitGreeting(fonts, look, Modifier.padding(start = side + 6.dp, end = side, top = 18.dp)) }

                if (currentSong != null || shelves.isNotEmpty()) item(key = "orbit:hero") {
                    val hero = rememberHero(shelves, currentSong, isPlaying)
                    val heroCard: @Composable (Modifier) -> Unit = { m ->
                        OrbitHero(
                            hero = hero, fonts = fonts, look = look, playbackTime = playbackTime.takeIf { hero.isCurrent },
                            onBite = { if (hero.isCurrent) onPlayPause?.invoke() else hero.item?.let { onItemClick(it, hero.shelfTitle) } },
                            onCard = { if (!hero.isCurrent) hero.item?.let { onItemClick(it, hero.shelfTitle) } else onPlayPause?.invoke() },
                            modifier = m,
                        )
                    }
                    if (wide && room != null) {
                        Row(Modifier.padding(horizontal = side).padding(top = 24.dp).height(HERO_HEIGHT), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            heroCard(Modifier.weight(1.7f).fillMaxHeight())
                            OrbitRoomCard(room, fonts, Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        heroCard(Modifier.padding(horizontal = side).padding(top = 24.dp).fillMaxWidth().height(HERO_HEIGHT))
                    }
                }

                if (quickLinks.isNotEmpty() || moods.isNotEmpty()) {
                    item(key = "orbit:chips") { OrbitChips(quickLinks, moods, browse, fonts, look, side) }
                }

                if (!wide && room != null) {
                    item(key = "orbit:room") { OrbitRoomCard(room, fonts, Modifier.padding(horizontal = side).padding(top = 22.dp).fillMaxWidth().height(172.dp)) }
                }

                if (!signedIn && onSignIn != null) {
                    item(key = "orbit:sign-in") { SignInBanner(onSignIn = onSignIn, modifier = Modifier.padding(horizontal = side - 16.dp).padding(top = 18.dp)) }
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

                val styles = planStyles(shelves)
                shelves.forEachIndexed { index, shelf ->
                    if (index == CONTROLS_AFTER) controls()
                    if (shelf.items.isEmpty()) return@forEachIndexed
                    item(key = "orbit:shelf:" + shelf.title + index) {
                        val open: (ShelfItem) -> Unit = { onItemClick(it, shelf.title) }
                        Column {
                            OrbitSectionHeader(localizeShelfTitle(shelf.title), localizeShelfSubtitle(shelf.subtitle), fonts, look, Modifier.padding(horizontal = side + 6.dp).padding(top = 34.dp, bottom = 14.dp))
                            when (styles[index]) {
                                ShelfStyle.SONGS -> OrbitSongRows(shelf, wide, fonts, look, currentSong, isPlaying, open, onItemLongPress, Modifier.padding(horizontal = side))
                                ShelfStyle.ARTISTS -> OrbitArtistRow(shelf.items, fonts, look, side, open)
                                ShelfStyle.RANKED -> OrbitRanked(shelf, wide, fonts, look, open, onItemLongPress, Modifier.padding(horizontal = side))
                                ShelfStyle.FEATURE -> OrbitFeature(shelf, wide, fonts, look, side, open, onItemLongPress)
                                ShelfStyle.DISCS -> OrbitDiscRow(shelf, fonts, look, side, open, onItemLongPress)
                                ShelfStyle.CARDS -> OrbitCardRow(shelf, fonts, side, open, onItemLongPress)
                            }
                        }
                    }
                }
                if (shelves.size <= CONTROLS_AFTER) controls()
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

private val HERO_HEIGHT = 280.dp
private const val CONTROLS_AFTER = 3

// ---- greeting -------------------------------------------------------------------------------------------------------------------

@Composable
private fun OrbitGreeting(fonts: OrbitFonts, look: OrbitLook, modifier: Modifier) {
    val now = remember { LocalDateTime.now() }
    val part = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Late night"
    }
    val line = remember(now) { now.format(DateTimeFormatter.ofPattern("EEE dd MMM — HH:mm", Locale.ENGLISH)).uppercase(Locale.ENGLISH) }
    Column(modifier) {
        Text(line, style = fonts.label, color = look.dim)
        Spacer(Modifier.height(10.dp))
        Text(buildAnnotatedString { append(part); withStyle(SpanStyle(color = look.accent)) { append(".") } }, style = fonts.greeting, color = look.ink)
    }
}

// ---- hero -----------------------------------------------------------------------------------------------------------------------

private class Hero(val title: String, val subtitle: String, val cover: String?, val label: String, val isCurrent: Boolean, val playing: Boolean, val item: ShelfItem?, val shelfTitle: String)

@Composable
private fun rememberHero(shelves: List<HomeShelf>, current: Song?, playing: Boolean): Hero {
    val shelf = remember(shelves) { shelves.firstOrNull { s -> s.items.firstOrNull()?.videoId != null } ?: shelves.firstOrNull { it.items.isNotEmpty() } }
    val from = localizeShelfTitle(shelf?.title.orEmpty()).uppercase()
    return remember(shelf, current, playing, from) {
        if (current != null) {
            Hero(current.title, current.artist, current.thumbnailUrl, if (playing) "NOW ORBITING" else "PAUSED", true, playing, null, "")
        } else {
            val item = shelf?.items?.firstOrNull()
            Hero(item?.title.orEmpty(), item?.subtitle.orEmpty(), item?.thumbnailUrl, "START HERE · $from", false, false, item, shelf?.title.orEmpty())
        }
    }
}

@Composable
private fun OrbitHero(hero: Hero, fonts: OrbitFonts, look: OrbitLook, playbackTime: (() -> Pair<Long, Long>)?, onBite: () -> Unit, onCard: () -> Unit, modifier: Modifier) {
    val (fill, ink) = coverFill(hero.cover, hero.title)
    BoxWithConstraints(modifier) {
        val disc = if (maxWidth < 380.dp) 124.dp else 148.dp
        Box(
            Modifier.fillMaxSize().clip(HeroBite)
                .background(Brush.linearGradient(listOf(fill, fill.copy(alpha = 0.82f).compositeOver(Color.Black))))
                .clickable(onClick = onCard),
        ) {
            Text(hero.label, style = fonts.label, color = ink.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 22.dp, top = 22.dp, end = disc + 40.dp))
            Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(disc + 30.dp), contentAlignment = Alignment.Center) {
                Disc(hero.cover, disc)
                OrbitRing(
                    disc + 30.dp, ink,
                    progress = playbackTime?.let { time -> { time().let { (p, d) -> if (d > 0) p.toFloat() / d else null } } },
                    spinning = hero.playing,
                )
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 22.dp, end = 104.dp)) {
                if (playbackTime != null) {
                    val (p, d) = playbackTime()
                    if (d > 0) Text("${clock(p)} / ${clock(d)}", style = fonts.label, color = ink.copy(alpha = 0.72f), modifier = Modifier.padding(bottom = 10.dp))
                }
                Text(hero.title, style = fonts.heroTitle, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(hero.subtitle, style = fonts.body.copy(fontSize = 15.sp), color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(13.dp).size(66.dp).clip(CircleShape)
                .background(if (look.dark) PEACH else Color(0xFF1D1416)).clickable(onClick = onBite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (hero.isCurrent && hero.playing) CosmoIcons.Pause else CosmoIcons.Play, contentDescription = if (hero.playing) "Pause" else "Play",
                tint = if (look.dark) Color(0xFF2A100A) else PEACH, modifier = Modifier.size(26.dp))
        }
    }
}


// ---- chips: the quick links, then the moods -------------------------------------------------------------------------------------

@Composable
private fun OrbitChips(links: List<HomeQuickLink>, moods: List<MoodGenre>, browse: CosmoBrowse?, fonts: OrbitFonts, look: OrbitLook, side: Dp) {
    LazyRow(Modifier.padding(top = 20.dp), contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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


/** A song shelf as rows with round covers: four at first (eight in two columns when wide), the rest behind "Show all". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrbitSongRows(
    shelf: HomeShelf, wide: Boolean, fonts: OrbitFonts, look: OrbitLook, current: Song?, playing: Boolean,
    onClick: (ShelfItem) -> Unit, onLongPress: ((ShelfItem) -> Unit)?, modifier: Modifier,
) {
    var all by rememberSaveable(shelf.title) { mutableStateOf(false) }
    val columns = if (wide) 2 else 1
    val first = if (wide) 8 else 4
    val shown = if (all) shelf.items else shelf.items.take(first)
    val isPlaying = remember(shelf.items, current) { playingShelfItemMatcher(shelf.items, current) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.chunked(columns).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    OrbitSongRow(
                        item.title, item.subtitle, item.thumbnailUrl, fonts, look, onClick = { onClick(item) }, modifier = Modifier.weight(1f),
                        now = isPlaying(item), playing = playing, onLongClick = onLongPress?.let { { it(item) } }, trailingText = item.song?.durationText,
                    )
                }
                if (pair.size < columns) Spacer(Modifier.weight(1f))
            }
        }
        if (shelf.items.size > first) OrbitShowAll(all, shelf.items.size, fonts, look, onToggle = { all = !all })
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
                    Text(first.title, style = fonts.heroTitle.copy(fontSize = 24.sp), color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
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
