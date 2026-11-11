package com.cosmotune.music.ui.replay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cosmotune.music.R
import com.cosmotune.music.data.stats.ReplaySummary
import com.cosmotune.ui.replay.OrbitReplayRow
import com.cosmotune.ui.replay.OrbitStoryCard
import com.cosmotune.ui.replay.OrbitStoryChrome
import com.cosmotune.ui.replay.OrbitStoryColors
import com.cosmotune.ui.replay.OrbitStoryFacts
import com.cosmotune.ui.replay.OrbitStoryLeader
import com.cosmotune.ui.replay.OrbitStoryOrbit
import com.cosmotune.ui.replay.OrbitStoryRecap
import com.cosmotune.ui.replay.OrbitStoryWord
import com.cosmotune.ui.replay.orbitStoryColors
import kotlinx.coroutines.launch

/**
 * The Replay as a run of full-screen cards you tap through.
 *
 * ## Why a story rather than a scroll
 *
 * The page below this already has every number on it, ranked and scrollable, and
 * that is the right shape for looking something up. It is the wrong shape for
 * being *told* something: a scroll gives every fact the same weight and lets the
 * eye run past the one that matters. A story gives each fact the whole screen
 * and a few seconds of nobody else's attention.
 *
 * ## The layout
 *
 * Every card is an Orbit card: a flat ground in the colour of the cover it is
 * about (see [storyArtwork]), the sentence at the top in the wide face with the
 * number in it in full ink, and the thing that sentence is about as a disc in
 * its orbit — whose moon is the card's own clock. The sentence carries the
 * meaning, so a card that has lost its artwork to a slow connection still says
 * something.
 *
 * ## The mechanics people already know
 *
 * Tap the right of the screen to move on, the left to go back, hold anywhere to
 * stop the clock, swipe to move by hand, back or the cross to leave. None of it
 * is explained on screen: it is the same everywhere, and explaining it would be
 * the first thing on a page whose whole job is to be effortless.
 *
 * The auto-advance stops at the last card rather than closing — closing on a
 * timer takes the share button away from someone reaching for it.
 */
@Composable
fun ReplayStories(
    summary: ReplaySummary,
    start: ReplayStoryPage,
    onClose: () -> Unit,
    /** Shares the card that was on screen when the button was pressed. */
    onShare: (ReplayStoryPage) -> Unit,
    /**
     * Holds the clock while something is up in front of the story.
     *
     * The share sheet is the case: it covers the lower half of the card, takes a
     * second to draw its picture, and behind it the story was still counting
     * down — so by the time anyone had chosen an app, the card they were sending
     * was two cards further on than the one on screen.
     */
    paused: Boolean = false,
) {
    // A category with nothing in it has no card: an empty page in the middle of
    // a story reads as the app having lost something.
    val pages = remember(summary) {
        ReplayStoryPage.ordered.filter { page ->
            when (page) {
                ReplayStoryPage.ALBUMS -> summary.albums.isNotEmpty()
                ReplayStoryPage.GENRES -> summary.genres.isNotEmpty()
                else -> true
            }
        }
    }
    val pagerState = rememberPagerState(
        initialPage = pages.indexOf(start).coerceAtLeast(0),
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val current by remember { derivedStateOf { pagerState.settledPage } }

    /**
     * Where a tap sends the story.
     *
     * Two things here are the fix for two separate bugs, and both come from a
     * tap and the auto-advance being able to move the pager at the same time.
     *
     *  - **The step is taken from [PagerState.settledPage], not `currentPage`.**
     *    `currentPage` flips to the destination halfway through a scroll, so a
     *    tap landing during the auto-advance was computing "next" from the page
     *    the story was already on its way to — and skipping one. `settledPage`
     *    is the last page that actually came to rest, so the worst a mistimed
     *    tap can do is ask for the transition already in flight.
     *  - **It snaps rather than animating.** An `animateScrollToPage` that gets
     *    cancelled mid-flight — which is exactly what a second scroll request
     *    does to it — leaves the pager wherever it had got to, which is the
     *    story sitting between two cards with neither readable. A snap has no
     *    in-between state to be interrupted in. It is also what every story
     *    player does: tapping is meant to feel like turning a page, not like
     *    starting a transition.
     */
    fun goTo(target: Int, animate: Boolean) {
        val next = target.coerceIn(0, pages.lastIndex)
        scope.launch {
            progress.snapTo(0f)
            if (animate) pagerState.animateScrollToPage(next) else pagerState.scrollToPage(next)
        }
    }

    fun step(forward: Boolean) {
        val currentPage = pagerState.settledPage
        // On the last slide, forward tap closes the replay instead of staying put.
        if (forward && currentPage == pages.lastIndex) {
            scope.launch { onClose() }
            return
        }
        goTo(currentPage + if (forward) 1 else -1, animate = false)
    }

    LaunchedEffect(current) { progress.snapTo(0f) }
    LaunchedEffect(current, held, paused) {
        if (held || paused) return@LaunchedEffect
        // On the final slide: animate progress to 1f then close the replay.
        if (current == pages.lastIndex) {
            progress.animateTo(1f, tween(PAGE_MILLIS.toInt(), easing = LinearEasing))
            scope.launch { onClose() }
            return@LaunchedEffect
        }
        // Resumed from where the hold left it rather than restarted, so letting
        // go doesn't hand back a card that was nearly finished.
        val remaining = ((1f - progress.value) * PAGE_MILLIS).toInt().coerceAtLeast(0)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        // Handed to [goTo] rather than scrolled from here, and that is the whole
        // fix for a card that stopped three-quarters of the way across on its
        // own but slid cleanly when tapped. This effect is keyed on the current
        // page; `animateScrollToPage` flips that key at the halfway mark, which
        // cancels the effect — and with it the very animation that flipped it.
        // The pager was left wherever the cancellation caught it. [goTo] runs on
        // the composition's scope, which the page change has no bearing on.
        goTo(current + 1, animate = true)
    }

    val page = pages.getOrElse(current) { ReplayStoryPage.INTRO }
    val colors = orbitStoryColors(summary.storyArtwork(page), page.name)
    val ground by animateColorAsState(colors.fill, tween(320), label = "storyGround")

    // The system bars are kept outside the frame rather than padded for inside
    // it: the card is a fixed canvas and its own margins are part of the
    // design, so an inset applied within would move the headline on one phone
    // and not another. Fitting the whole card into the safe area instead leaves
    // it identical everywhere, and the room round it is the card's own colour,
    // so the screen is one flat ground rather than a card on black.
    Box(
        Modifier
            .fillMaxSize()
            .background(ground)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        StoryFrame {
            Stage(
                pages = pages,
                pagerState = pagerState,
                summary = summary,
                current = current,
                progress = progress,
                onHold = { held = it },
                onStep = ::step,
                onClose = onClose,
                onShare = onShare,
                colors = colors,
                page = page,
            )
        }
    }
}

/**
 * Holds a story to 9:16 whatever shape the screen is.
 *
 * A story is a fixed canvas, not a responsive layout: the type sizes, the
 * orbit and the room the headline is allowed are all set against one set of
 * proportions, and a 20:9 phone stretches that into a column with a hole in the
 * middle while a tablet flattens it. It is also the shape the shared image comes
 * out as, so a card the user sends looks like the card they were looking at when
 * they tapped share.
 *
 * Fitted rather than filled — whichever of width and height runs out first is
 * the one that sets the size. Letterboxing is the honest failure here: cropping
 * would take the headline or the share button off the edge of the screen on the
 * exact devices most likely to be running this.
 */
@Composable
private fun StoryFrame(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitsByWidth = maxWidth / maxHeight < STORY_ASPECT
        Box(
            Modifier
                .then(if (fitsByWidth) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                .aspectRatio(STORY_ASPECT),
        ) {
            content()
        }
    }
}

/** The story card itself, inside whatever box [StoryFrame] gave it. */
@Composable
private fun Stage(
    pages: List<ReplayStoryPage>,
    pagerState: PagerState,
    summary: ReplaySummary,
    current: Int,
    progress: Animatable<Float, *>,
    onHold: (Boolean) -> Unit,
    onStep: (Boolean) -> Unit,
    onClose: () -> Unit,
    onShare: (ReplayStoryPage) -> Unit,
    colors: OrbitStoryColors,
    page: ReplayStoryPage,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onHold(true)
                        tryAwaitRelease()
                        onHold(false)
                    },
                    // Supplied, and deliberately empty. Without an
                    // `onLongPress`, `detectTapGestures` has no notion of a long
                    // press at all and reports every press-and-release as a tap
                    // — so holding to pause the story turned the page the
                    // instant the finger came off it, which is the opposite of
                    // what holding is for. Handing it a callback is what makes
                    // it draw the line at the long-press timeout.
                    onLongPress = {},
                    // Every tap is a step; where it lands is worked out in
                    // [ReplayStories.step], which owns the pager.
                    onTap = { offset -> onStep(offset.x >= size.width * BACK_ZONE) },
                )
            },
    ) {
        // Each card paints its own ground, so a swipe slides one flat colour
        // off and the next one on.
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
            // The moon on a card's orbit is its clock: full on the cards already
            // seen, still on the ones to come.
            val ring: () -> Float? = {
                when {
                    index < current -> 1f
                    index > current -> 0f
                    else -> progress.value
                }
            }
            StoryPage(page = pages[index], summary = summary, ring = ring)
        }

        // The furniture that doesn't move between cards, drawn over the pager
        // rather than inside each page so it stays put while the cards slide
        // under it — a header that swipes with its page reads as eight headers
        // rather than one.
        //
        // The share button is in it on every card, not only the last one: the
        // card somebody wants to send is whichever one surprised them. It sends
        // *this* card; the whole-Replay picture is what the button at the foot
        // of the Replay page produces.
        val label = summary.localizedLabel(LocalContext.current)
        OrbitStoryChrome(
            // "Replay 26" for a year, which is the shape everyone knows this by.
            // A month or "All time" has no two-digit form and is spelt out
            // rather than truncated into nonsense.
            label = if (label.length == 4 && label.all { it.isDigit() }) {
                "Replay ${label.takeLast(2)}"
            } else {
                "Replay · $label"
            },
            count = pages.size,
            current = current,
            progress = progress.value,
            colors = colors,
            shareLabel = stringResource(R.string.share_my_replay),
            onShare = { onShare(page) },
            closeLabel = stringResource(R.string.close_replay),
            onClose = onClose,
        )
    }
}

// ── One card ────────────────────────────────────────────────────────────────

@Composable
private fun StoryPage(page: ReplayStoryPage, summary: ReplaySummary, ring: () -> Float?) {
    val context = LocalContext.current
    val colors = orbitStoryColors(summary.storyArtwork(page), page.name)
    val headline = summary.storyHeadline(context, page).map { it.text to it.bold }
    OrbitStoryCard(colors, headline) {
        when (page) {
            ReplayStoryPage.INTRO ->
                OrbitStoryOrbit(storyCovers(summary), colors, ring, stringResource(R.string.replay_private_counting))
            ReplayStoryPage.MINUTES ->
                OrbitStoryOrbit(storyCovers(summary), colors, ring, minutesLine(summary))
            ReplayStoryPage.SONGS -> Leaderboard(summary.songRows(STORY_ROWS), colors, ring)
            ReplayStoryPage.ARTISTS -> Leaderboard(summary.artistRows(STORY_ROWS), colors, ring)
            ReplayStoryPage.ALBUMS -> Leaderboard(summary.albumRows(STORY_ROWS), colors, ring, titleArt = true)
            ReplayStoryPage.GENRES -> {
                val rows = summary.genreRows(STORY_ROWS)
                val lead = rows.firstOrNull() ?: return@OrbitStoryCard
                OrbitStoryWord(
                    word = lead.title,
                    line = formatListening(context, lead.ms),
                    rest = rows.drop(1).map { it.orbit(context).copy(subtitle = formatListening(context, it.ms)) },
                    colors = colors,
                    ring = ring,
                )
            }
            ReplayStoryPage.HABITS -> OrbitStoryFacts(
                cover = summary.storyArtwork(page),
                facts = buildList {
                    if (summary.distinctAlbums > 0) {
                        add(grouped(summary.distinctAlbums.toLong()) to stringResource(R.string.different_albums))
                    }
                    summary.busiestDay?.let {
                        add(
                            formatDay(context, it) to
                                stringResource(R.string.your_biggest_day, formatListening(context, summary.busiestDayMs)),
                        )
                    }
                    summary.peakHour?.let {
                        add(formatHour(context, it) to stringResource(R.string.when_you_listen_the_most))
                    }
                },
                colors = colors,
                ring = ring,
            )
            ReplayStoryPage.SUMMARY -> OrbitStoryRecap(
                cover = summary.storyArtwork(page),
                lines = buildList {
                    add(stringResource(R.string.minutes) to formatMinutes(summary.totalMs))
                    summary.songs.firstOrNull()?.let { add(stringResource(R.string.top_song) to it.song.title) }
                    summary.artists.firstOrNull()?.let { add(stringResource(R.string.top_artist) to it.title) }
                    summary.albums.firstOrNull()?.let { add(stringResource(R.string.top_album) to it.title) }
                    summary.genres.firstOrNull()?.let { add(stringResource(R.string.top_genre) to it.title) }
                },
                footnote = stringResource(R.string.tap_share_for_picture),
                colors = colors,
                ring = ring,
            )
        }
    }
}

/**
 * A number one with its runners-up.
 *
 * The same card for songs, artists and albums, because the shape of the fact is
 * identical in all three and only the noun changes. The ranked four underneath
 * are the reason this is worth opening twice: the hero is the answer, the list
 * is the evidence.
 */
@Composable
private fun ColumnScope.Leaderboard(rows: List<ReplayRow>, colors: OrbitStoryColors, ring: () -> Float?, titleArt: Boolean = false) {
    val context = LocalContext.current
    val lead = rows.firstOrNull() ?: return
    OrbitStoryLeader(
        lead = leadRow(lead),
        leadLine = "${formatListening(context, lead.ms)} · " +
            context.replayCount(lead.plays, R.plurals.replay_play_count),
        rest = rows.drop(1).map { it.orbit(context).copy(subtitle = formatListening(context, it.ms), plays = 0) },
        colors = colors,
        ring = ring,
        // The albums' card shows its number one as the album's title art (the lyrics-titles board's placement table).
        titleArt = titleArt,
    )
}

/** The lead row as the shared one, its own subtitle (who) kept apart from the line under it. */
private fun leadRow(row: ReplayRow) = OrbitReplayRow(
    key = row.key,
    rank = row.rank,
    title = row.title,
    subtitle = row.subtitle.orEmpty(),
    cover = row.artworkUrl,
    plays = row.plays,
)

/**
 * The covers and faces the opening cards' orbit is made of: the top songs'
 * covers first (the first is the big disc), then artists' faces that are not
 * already there. Artists come back as faces and releases as covers, which is the
 * only label these need.
 */
private fun storyCovers(summary: ReplaySummary): List<String> {
    val covers = summary.songs.mapNotNull { it.song.thumbnailUrl }.distinct().take(3)
    val faces = summary.artists.mapNotNull { it.artworkUrl }.distinct().filterNot { it in covers }.take(3)
    return covers + faces
}

/** The line under the minutes card's orbit. */
@Composable
private fun minutesLine(summary: ReplaySummary): String {
    val context = LocalContext.current
    // "That's 0 hours" is what an unconditional hours line says for the first
    // afternoon of listening, and it reads as the page failing to count rather
    // than as a small number. Under an hour, the plays are the only figure worth
    // restating.
    return buildString {
        if (summary.hours >= 1) {
            append(context.getString(R.string.replay_hours_across, grouped(summary.hours)))
            append(", ")
        } else {
            append(context.getString(R.string.replay_across))
            append(" ")
        }
        append(
            context.resources.getQuantityString(
                R.plurals.replay_play_count,
                summary.totalPlays,
                grouped(summary.totalPlays.toLong()),
            ),
        )
        val peak = summary.peakHour
        if (peak != null) {
            append(", mostly around ")
            append(formatHour(context, peak))
        }
        append(".")
    }
}

/** How far down a chart a card goes. */
private const val STORY_ROWS = 5

/**
 * The shape of a story card, everywhere. See [StoryFrame] for why it is fixed
 * rather than following the screen.
 */
private const val STORY_ASPECT = 9f / 16f

/** How long a card holds before moving on, unless a finger is on the screen. */
private const val PAGE_MILLIS = 14_000f

/** The share of the width that means "back" — the left edge, as everywhere else. */
private const val BACK_ZONE = 0.32f
