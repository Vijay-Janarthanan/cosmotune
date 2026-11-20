package com.cosmotune.ui.orbit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.playingRowMatcher
import com.cosmotune.music.ui.components.MessageState
import com.cosmotune.music.ui.screens.localizeShelfSubtitle
import com.cosmotune.music.ui.screens.localizeShelfTitle
import com.cosmotune.ui.bridge.DropdownMenu
import com.cosmotune.ui.bridge.DropdownMenuItem
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.pages.ArtistSignatureMark
import com.cosmotune.ui.pages.rememberArtistVisuals
import com.cosmotune.ui.pages.rememberMotionAllowed
import com.cosmotune.ui.theme.LocalReducedMotion

/*
 * The artist page in Orbit (the Music board's 5), one piece for both hosts: the compact page header in the artist's own colours with
 * the moving portrait in its round portrait inside the dashed orbit, then the top songs as flat rows (with About beside them on a wide
 * window), music videos, live performances, albums, singles and EPs, featured on, fans also like, and the signature, all at the one
 * section rhythm.
 */

/** One line of a More menu. */
class OrbitMenuAction(val label: String, val icon: ImageVector?, val onClick: () -> Unit)

/** How an artist's feed shelf is drawn, in the order the page shows them. */
internal enum class ArtistShelfKind { VIDEOS, LIVE, RECORDS, CARDS, ARTISTS }

private val LIVE_WORDS = Regex("\\b(live|concerts?|sessions?|performances?)\\b", RegexOption.IGNORE_CASE)
private val CARD_WORDS = Regex("\\b(featured|playlists?|appears)\\b", RegexOption.IGNORE_CASE)

/**
 * The artist feed's shelves, each with how it is drawn, in the page's order: videos (a shelf of nothing but videos; "live" in its
 * title makes it the live performances), then albums, singles and EPs as records, then featured-on and playlists as colour cards,
 * then other artists as portraits. Shelves of a kind keep the feed's order; empty ones are left out.
 */
internal fun artistShelves(sections: List<HomeShelf>): List<Pair<ArtistShelfKind, HomeShelf>> =
    sections.filter { it.items.isNotEmpty() }.map { shelf ->
        val kind = when {
            shelf.items.all { it.browseId?.startsWith("UC") == true } -> ArtistShelfKind.ARTISTS
            shelf.items.all { it.videoId != null && it.browseId == null } ->
                if (LIVE_WORDS.containsMatchIn(shelf.title)) ArtistShelfKind.LIVE else ArtistShelfKind.VIDEOS
            CARD_WORDS.containsMatchIn(shelf.title) -> ArtistShelfKind.CARDS
            else -> ArtistShelfKind.RECORDS
        }
        kind to shelf
    }.sortedBy { it.first.ordinal }

/**
 * An artist's page. [listeners] is the count line ("2.4M monthly listeners"), [stats] the numbers About shows. While the songs load
 * ([loading]) a skeleton stands under the card; [error] shows its message with [onRetry]. [following] null hides Follow. [playing]
 * says the bite shows pause ([onPlay] decides what a tap does). A tap on a top song is [onSongClick] with its place among [songs];
 * [onSongMore] adds a more button to each (the desktop), [onSongSwipe] queues on a swipe (the phone). [onShowAll] opens a shelf in
 * full; [afterTopSongs] is drawn under the top songs (the latest release with its save toggle); [menu] fills the card's More.
 * [margin] is the page's side margin where the host keeps its own (the desktop's 28); else Home's rule for the width.
 */
@Composable
fun OrbitArtistPage(
    name: String,
    photo: String?,
    listeners: String?,
    description: String?,
    stats: List<Pair<String, String>>,
    songs: List<Song>,
    sections: List<HomeShelf>,
    currentSong: Song?,
    isPlaying: Boolean,
    listState: LazyListState,
    contentPadding: PaddingValues,
    following: Boolean?,
    onToggleFollow: (() -> Unit)?,
    playing: Boolean,
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    onRadio: (() -> Unit)?,
    onSongClick: (Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onItemClick: (ShelfItem, HomeShelf) -> Unit,
    modifier: Modifier = Modifier,
    onSongSwipe: ((Song) -> Unit)? = null,
    onSongMore: ((Song) -> Unit)? = null,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onShowAll: ((HomeShelf) -> Unit)? = null,
    menu: List<OrbitMenuAction> = emptyList(),
    afterTopSongs: (@Composable () -> Unit)? = null,
    loading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    margin: Dp? = null,
    artistIdentity: String? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val visuals = rememberArtistVisuals(name, artistIdentity)
    val art = visuals.art
    // The artist's own colours: Apple's for the picture when it has them, else the photo's.
    val photoFill = coverFill(photo, name)
    val colors = art?.let { vivid(it.background) }?.let { OrbitCardColors(it.fill, it.ink) } ?: OrbitCardColors(photoFill.first, photoFill.second)
    val shelves = remember(sections) { artistShelves(sections) }
    val blurb = description?.takeIf { it.isNotBlank() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val side = margin ?: orbitPageSide(maxWidth)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 32.dp),
        ) {
            item(key = "artist:card", contentType = "card") {
                ArtistCard(
                    name = name, photo = photo, clip = art?.videoUrl?.let { CanvasArtwork(it) }, hero = art?.heroUrl, listeners = listeners,
                    colors = colors, following = following, onToggleFollow = onToggleFollow,
                    albums = shelves.firstOrNull { it.first == ArtistShelfKind.RECORDS }?.second?.items?.size ?: 0,
                    onRadio = onRadio.takeIf { songs.isNotEmpty() }, onShuffle = onShuffle.takeIf { songs.isNotEmpty() }, menu = menu,
                    playing = playing, onPlay = onPlay.takeIf { songs.isNotEmpty() }, fonts = fonts, look = look,
                    modifier = Modifier.padding(horizontal = side).fillMaxWidth().testTag("music:artist:card"),
                    identity = artistIdentity ?: name,
                )
            }
            if (loading) orbitRowSkeletons("skeleton:artist", side, rows = 4)
            if (error != null) item(key = "artist:error") { MessageState(error, actionLabel = onRetry?.let { "Retry" }, onAction = onRetry) }

            val about: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    OrbitSectionHeader("About", "From the artist's page", fonts, look, Modifier.padding(start = 4.dp, top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent))
                    OrbitAboutCard(blurb, stats, Modifier.fillMaxWidth())
                }
            }
            val hasAbout = blurb != null || stats.isNotEmpty()
            if (songs.isNotEmpty()) {
                item(key = "artist:top", contentType = "top") {
                    Row(Modifier.padding(horizontal = side), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        TopSongs(songs, currentSong, isPlaying, fonts, look, onSongClick, onSongLongPress, onSongSwipe, onSongMore, Modifier.weight(1f))
                        if (wide && hasAbout) about(Modifier.width(330.dp))
                    }
                }
            }
            if (hasAbout && (!wide || songs.isEmpty())) item(key = "artist:about", contentType = "about") { about(Modifier.padding(horizontal = side)) }
            if (afterTopSongs != null) item(key = "artist:after-top") { Box(Modifier.padding(horizontal = side).padding(top = 20.dp)) { afterTopSongs() } }

            itemsIndexed(shelves, key = { index, (_, shelf) -> "artist:shelf:$index:${shelf.title}" }, contentType = { _, (kind, _) -> kind }) { _, (kind, shelf) ->
                val open: (ShelfItem) -> Unit = { onItemClick(it, shelf) }
                Column {
                    val showAll = onShowAll?.takeIf { shelf.items.size > SHELF_PREVIEW }?.let { { it(shelf) } }
                    OrbitPageSection(
                        localizeShelfTitle(shelf.title), Modifier.padding(start = side + 4.dp, end = side).padding(top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent),
                        subtitle = localizeShelfSubtitle(shelf.subtitle), action = "Show all".takeIf { showAll != null }, onAction = showAll,
                    )
                    when (kind) {
                        ArtistShelfKind.VIDEOS -> OrbitVideoRow(shelf.items, false, fonts, look, side, open, onItemLongPress, if (wide) 300.dp else 260.dp)
                        ArtistShelfKind.LIVE -> OrbitVideoRow(shelf.items, true, fonts, look, side, open, onItemLongPress, if (wide) 300.dp else 260.dp)
                        ArtistShelfKind.RECORDS -> OrbitRecordRow(shelf.items, fonts, look, side, open, onItemLongPress)
                        ArtistShelfKind.CARDS -> OrbitColourCardRow(shelf.items, fonts, side, open, onItemLongPress)
                        ArtistShelfKind.ARTISTS -> OrbitPortraitRow(shelf.items, fonts, look, side, open, onItemLongPress, size = if (wide) 110.dp else 92.dp)
                    }
                }
            }

            if (!loading && error == null && songs.isEmpty() && shelves.isEmpty() && !hasAbout) {
                item(key = "artist:empty") { MessageState("Nothing here yet") }
            }
            if (visuals.signature != null) {
                item(key = "artist:signature") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = OrbitSpace.section)) {
                        OrbitSectionHeader("Signature", "", fonts, look, Modifier.padding(start = 6.dp, bottom = 10.dp))
                        ArtistSignatureMark(visuals.signature, name, Modifier.align(Alignment.CenterHorizontally).padding(12.dp))
                    }
                }
            }
        }
    }
}

/** A shelf of more than this many cards also offers "Show all" in its heading. */
private const val SHELF_PREVIEW = 5

/**
 * A top song's second line: its album, never the title over again. A single (its album named as the song is) says "Single" with
 * its artists; a song with no album known says its artists.
 */
internal fun topSongLine(song: Song): String {
    fun plain(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    val album = song.albumName?.trim()?.takeIf { it.isNotEmpty() }
    if (album != null && plain(album) != plain(song.title)) return album
    val artist = song.artist.trim().takeIf { it.isNotEmpty() && plain(it) != plain(song.title) }
    return when {
        album != null -> if (artist != null) "Single · $artist" else "Single"
        else -> artist ?: "Song"
    }
}

/** The first top songs shown (the Music board's 5: four); the rest behind "Show all" in the heading. */
private const val TOP_SHOWN = 4

@Composable
private fun TopSongs(
    songs: List<Song>, current: Song?, isPlaying: Boolean, fonts: OrbitFonts, look: OrbitLook,
    onClick: (Int) -> Unit, onLongClick: (Song) -> Unit, onSwipe: ((Song) -> Unit)?, onMore: ((Song) -> Unit)?, modifier: Modifier,
) {
    var all by rememberSaveable(songs.size) { mutableStateOf(false) }
    val shown = if (all) songs else songs.take(TOP_SHOWN)
    // The row that was played lights, not every namesake: see [playingRowMatcher].
    val isCurrentRow = playingRowMatcher(songs, current)
    Column(modifier) {
        OrbitPageSection(
            "Top songs", Modifier.padding(start = 4.dp, top = OrbitSpace.section, bottom = OrbitSpace.sectionToContent),
            action = (if (all) "Show less" else "Show all").takeIf { songs.size > TOP_SHOWN }, onAction = { all = !all },
        )
        shown.forEachIndexed { index, song ->
            val now = isCurrentRow(song)
            val row: @Composable (Modifier) -> Unit = { m ->
                OrbitSongRow(
                    title = song.title, subtitle = topSongLine(song), cover = song.thumbnailUrl, fonts = fonts, look = look,
                    onClick = { onClick(index) }, modifier = m.fillMaxWidth(), now = now, playing = now && isPlaying, onLongClick = { onLongClick(song) },
                    trailing = {
                        if (onMore != null) OrbitRoundButton(CosmoIcons.MoreVertical, "More", look.dim, { onMore(song) }, size = 40.dp, touch = 48.dp)
                        else song.durationText?.let { Text(it, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp)) }
                    },
                )
            }
            if (onSwipe != null) OrbitSwipeToQueue({ onSwipe(song) }, look, fonts, Modifier.testTag("music:artist:top:$index")) { row(Modifier) }
            else row(Modifier.testTag("music:artist:top:$index"))
        }
    }
}

/**
 * The artist card: the compact page header ([OrbitPageHeader]) in the artist's colour, the round portrait (the moving portrait
 * looping silently in it when Apple has one and the motion rules allow, the photo otherwise) at 104 dp inside the dashed 120 dp
 * orbit, "ARTIST · 2.4M MONTHLY LISTENERS", the name, whether it is followed and how many albums, and one row of round actions:
 * Shuffle, Radio, Follow (solid while following) and More; Play in the bite.
 */
@Composable
private fun ArtistCard(
    name: String, photo: String?, clip: CanvasArtwork?, hero: String?, listeners: String?, colors: OrbitCardColors,
    following: Boolean?, onToggleFollow: (() -> Unit)?, onRadio: (() -> Unit)?, onShuffle: (() -> Unit)?, menu: List<OrbitMenuAction>,
    playing: Boolean, onPlay: (() -> Unit)?, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier,
    identity: String, albums: Int,
) {
    val allowed = clip != null && rememberMotionAllowed()
    val subtitle = listOfNotNull(
        when (following) { true -> "Following"; false -> "Not followed"; null -> null },
        albums.takeIf { it > 0 }?.let { if (it == 1) "1 album" else "$it albums" },
    ).joinToString(" · ")
    OrbitPageHeader(
        cover = photo, label = listOfNotNull("Artist", listeners).joinToString(" · "), title = name, subtitle = subtitle.ifBlank { null },
        playing = playing, onPlay = onPlay, modifier = modifier, colors = colors,
        lead = { c ->
            Box(Modifier.size(OrbitHeaderSize.PAGE.disc), contentAlignment = Alignment.Center) {
                OrbitMotionDisc(photo, 104.dp, clip, allowed, overStill = hero, identity = identity)
                Canvas(Modifier.fillMaxSize()) {
                    val r = this.size.minDimension / 2 - 1.dp.toPx()
                    drawCircle(c.ink.copy(alpha = 0.6f), r, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
                }
            }
        },
    ) { c ->
        if (onShuffle != null) OrbitHeaderButton(CosmoIcons.Shuffle, "Shuffle", c, onShuffle, Modifier.testTag("music:artist:shuffle"))
        if (onRadio != null) OrbitHeaderButton(CosmoIcons.Radio, "Radio", c, onRadio, Modifier.testTag("music:artist:radio"))
        if (following != null && onToggleFollow != null) {
            OrbitHeaderButton(
                if (following) CosmoIcons.Check else CosmoIcons.Plus, if (following) "Following" else "Follow", c, onToggleFollow,
                Modifier.testTag("music:artist:follow"), on = following,
            )
        }
        if (menu.isNotEmpty()) {
            Box {
                var open by remember { mutableStateOf(false) }
                OrbitHeaderButton(CosmoIcons.MoreVertical, "More", c, { open = true }, Modifier.testTag("music:artist:more"))
                OrbitMenu(open = open, onDismiss = { open = false }, items = menu.map { OrbitMenuItem(it.label, icon = it.icon, onClick = it.onClick) }, fonts = fonts, look = look)
            }
        }
    }
}
