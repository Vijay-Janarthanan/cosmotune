/*
 * Source: FORK.md companion source record TV-0036
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.session.MediaController
import com.cosmotune.music.R
import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.settings.TvSettings
import com.cosmotune.music.playback.PlayerState
import com.cosmotune.music.ui.MainViewModel
import com.cosmotune.music.ui.tv.components.LocalTvChrome
import com.cosmotune.music.ui.tv.components.TvArtwork
import com.cosmotune.music.ui.tv.components.TvChrome
import com.cosmotune.music.ui.tv.components.TvTabIcon
import com.cosmotune.music.ui.tv.components.TvTabPill
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.music.ui.icons.CosmotuneIcons
import com.cosmotune.music.ui.tv.theme.TvDimensions
import androidx.compose.foundation.layout.Spacer
import com.cosmotune.music.ui.tv.components.tvClick
import com.cosmotune.music.ui.tv.components.tvLift
import com.cosmotune.music.ui.tv.dialogs.TvAccountDialog
import com.cosmotune.music.ui.tv.dialogs.TvUpdateDialog
import com.cosmotune.music.ui.tv.player.TvNowPlayingScreen
import com.cosmotune.music.ui.tv.screens.TvDetailScreen
import com.cosmotune.music.ui.tv.screens.TvExploreScreen
import com.cosmotune.music.ui.tv.screens.TvHomeScreen
import com.cosmotune.music.ui.tv.screens.TvLibraryScreen
import com.cosmotune.music.ui.tv.screens.TvSearchScreen
import com.cosmotune.music.ui.tv.screens.TvSettingsScreen
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.music.ui.tv.theme.TvThemeColors
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchTab
import kotlinx.coroutines.delay

/**
 * The chips at the top (board batch-be section E: Home, Search, Library, Watch, Radio), with Explore beside Search and Settings
 * as a round chip at the end. Radio is Podio's radio and podcasts.
 */
enum class TvDestination(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    EXPLORE("Explore"),
    LIBRARY("Library"),
    WATCH("Watch"),
    PODIO("Radio"),
    /** "Live now", the page the Live pill of the chips row opens (it is not a chip of its own). */
    LIVE("Live now"),
    SETTINGS("Settings"),
}

/** A pushed album, playlist or artist page. */
data class TvDetailRoute(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
)

typealias TvOpenDetail = (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit

@Composable
fun TvApp(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    playerState: PlayerState,
    modifier: Modifier = Modifier,
) {
    val setupVersionCompleted by TvSettings.tvSetupVersionCompleted.collectAsState()
    var isRunningSetup by remember(setupVersionCompleted) { mutableStateOf(setupVersionCompleted == 0) }
    var isNowPlayingOpen by remember { mutableStateOf(false) }
    val googleAccounts by viewModel.googleAccounts.collectAsState()
    val activeAccountId by viewModel.activeAccountId.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()

    val profileAccess = remember { com.cosmotune.tv.TvProfileAccess() }
    val verifiedProfileId by profileAccess.verifiedProfileId.collectAsState()
    var showAccountDialog by remember { mutableStateOf(false) }

    val currentActiveProfileId = remember(googleAccounts, activeAccountId, activeProfileId) {
        if (activeProfileId != null) {
            activeProfileId
        } else {
            val account = googleAccounts.firstOrNull { it.accountId == activeAccountId }
                ?: googleAccounts.firstOrNull()
            if (account != null) {
                if (account.profiles.isNotEmpty()) {
                    account.profiles.firstOrNull { !it.isBrandAccount }?.profileId
                        ?: account.profiles.firstOrNull()?.profileId
                } else {
                    "default:${account.accountId}"
                }
            } else {
                null
            }
        }
    }
    val isCurrentProfileLocked = currentActiveProfileId != null && TvSettings.isProfileLocked(currentActiveProfileId)
    val profileNeedsUnlock = !profileAccess.allows(currentActiveProfileId, isCurrentProfileLocked, verifiedProfileId)
    val remoteAllowed = !isRunningSetup && !profileNeedsUnlock && !showAccountDialog
    val phoneRemote = com.cosmotune.tv.rememberTvPhoneRemote(viewModel, mediaController, playerState,
        activeAccountId, currentActiveProfileId, remoteAllowed, profileAccess)
    var showRemoteDialog by remember { mutableStateOf(false) }
    LaunchedEffect(remoteAllowed, activeAccountId, currentActiveProfileId) { if (!remoteAllowed) showRemoteDialog = false }

    LaunchedEffect(profileNeedsUnlock, showAccountDialog) {
        if (profileNeedsUnlock) {
            mediaController?.pause()
            if (!showAccountDialog) showAccountDialog = true
        }
    }

    // Offered once per launch, and not over first-time setup. Settings'
    // "Software Update" row reopens it after it is dismissed.
    val update by viewModel.updateAvailable.collectAsState()
    var updateDismissed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .tvCanvas(playerState.song?.thumbnailUrl.takeUnless { profileNeedsUnlock }),
    ) {
        AnimatedContent(
            targetState = isRunningSetup,
            transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(160))) },
            label = "tvAppView",
            modifier = Modifier.fillMaxSize(),
        ) { setup ->
            if (setup) {
                com.cosmotune.music.ui.tv.onboarding.TvSetupScreen(
                    viewModel = viewModel,
                    onComplete = { isRunningSetup = false },
                )
            } else if (profileNeedsUnlock) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TvAppMark(size = 96.dp) }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    // The pages stay alive under the player, so closing it comes
                    // back to the same tab, scroll position and focused card.
                    TvMainShell(
                        viewModel = viewModel,
                        mediaController = mediaController,
                        playerState = playerState,
                        covered = isNowPlayingOpen,
                        onOpenNowPlaying = { isNowPlayingOpen = true },
                        onOpenAccount = {
                            profileAccess.openAccountPicker()
                            showAccountDialog = true
                        },
                        onRunSetupAgain = { isRunningSetup = true },
                        onOpenRemote = { showRemoteDialog = true },
                    )
                    AnimatedVisibility(
                        visible = isNowPlayingOpen,
                        enter = scaleIn(initialScale = 0.94f, animationSpec = tween(260)) + fadeIn(tween(220)),
                        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.97f, animationSpec = tween(180)),
                    ) {
                        // The player places its own focus (scrubber or stage).
                        Box(modifier = Modifier.fillMaxSize().focusGroup()) {
                            TvNowPlayingScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                playerState = playerState,
                                onBack = { isNowPlayingOpen = false },
                            )
                        }
                    }
                }
            }
        }

        if (showAccountDialog && !isRunningSetup) {
            TvAccountDialog(
                viewModel = viewModel,
                onProfileUnlocked = profileAccess::verifiedSelection,
                accountCard = true,
                onDismiss = {
                    showAccountDialog = false
                },
            )
        }

        val pendingUpdate = update
        if (showRemoteDialog && remoteAllowed) com.cosmotune.tv.TvPhoneRemoteDialog(phoneRemote) { showRemoteDialog = false }
        if (pendingUpdate != null && !updateDismissed && !isRunningSetup && !showAccountDialog) {
            TvUpdateDialog(update = pendingUpdate, onDismiss = { updateDismissed = true })
        }
    }
}

/**
 * The page wears the cover (Orbit rule 10): the flat ground, washed from the top
 * in the playing cover's own colour, strong at the top and gone by four fifths
 * down (board `.wash`). Nothing playing, or the Pure Black theme: the ground alone.
 */
@Composable
private fun Modifier.tvCanvas(artworkUrl: String?): Modifier {
    val palette = TvThemeColors.current
    val url = artworkUrl.takeIf { palette.tintsCanvas }
    val (fill, _) = coverFill(url, "canvas")
    val tint = animateColorAsState(if (url == null) Color.Transparent else fill, tween(900), label = "canvasTint")
    val ground = palette.ground
    return drawBehind {
        drawRect(ground)
        val t = tint.value
        if (t.alpha > 0f) {
            drawRect(Brush.verticalGradient(0f to t.copy(alpha = 0.6f * t.alpha), 0.4f to t.copy(alpha = 0.2f * t.alpha), 0.8f to Color.Transparent))
        }
    }
}

@Composable
private fun TvMainShell(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    playerState: PlayerState,
    covered: Boolean,
    onOpenNowPlaying: () -> Unit,
    onOpenAccount: () -> Unit,
    onRunSetupAgain: () -> Unit,
    onOpenRemote: () -> Unit,
) {
    val chrome = remember { TvChrome() }
    // What had focus when the player opened over this shell; it gets it back on close.
    var focusBeforePlayer by remember { mutableStateOf<FocusRequester?>(null) }
    val openNowPlaying: () -> Unit = {
        focusBeforePlayer = chrome.lastFocused
        onOpenNowPlaying()
    }
    val stateHolder = rememberSaveableStateHolder()
    var destination by remember { mutableStateOf(TvDestination.HOME) }
    val detailStack = remember { mutableStateListOf<TvDetailRoute>() }
    val tabFocus = remember { FocusRequester() }
    var barHasFocus by remember { mutableStateOf(false) }
    // The card each pushed page was opened from, so Back can hand focus back to it.
    val returnFocus = remember { mutableListOf<FocusRequester?>() }

    val signedIn by viewModel.signedIn.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    LaunchedEffect(signedIn, activeProfileId) {
        if (!signedIn) {
            if (detailStack.isNotEmpty()) {
                detailStack.clear()
                returnFocus.clear()
                viewModel.clearDetail()
            }
            chrome.contentScrolled = false
            destination = TvDestination.HOME
        } else if (detailStack.isNotEmpty()) {
            detailStack.clear()
            returnFocus.clear()
            viewModel.clearDetail()
        }
    }

    var pendingReturn by remember { mutableStateOf<FocusRequester?>(null) }
    var hadDetail by remember { mutableStateOf(false) }
    val select: (TvDestination) -> Unit = { next ->
        if (detailStack.isNotEmpty()) {
            detailStack.clear()
            returnFocus.clear()
            viewModel.clearDetail()
            // Left by a chip, not by Back: focus stays on the chips, so the card that opened the page is not given it back.
            hadDetail = false
            pendingReturn = null
        }
        if (next != destination) {
            chrome.contentScrolled = false
            destination = next
        }
    }
    val openDetail: TvOpenDetail = { browseId, title, subtitle, thumb, type ->
        returnFocus.add(chrome.lastFocused)
        detailStack.add(TvDetailRoute(browseId, title, subtitle, thumb, type))
    }
    // A Watch install or pack link opened from another app ([com.cosmotune.music.watch.AndroidWatch.consume]) opens Watch, where its
    // sheet waits ([com.cosmotune.ui.watch.WatchApp] draws it).
    val watchLink by com.cosmotune.watch.WatchLinks.pending.collectAsState()
    LaunchedEffect(watchLink != null) { if (watchLink != null) select(TvDestination.WATCH) }
    // Live now: the pill in the chips row while something followed is live, its page, and the reminders. Watch plays in Watch's player.
    val liveContext = androidx.compose.ui.platform.LocalContext.current
    val liveSettings = com.cosmotune.ui.live.rememberLiveNowSettings()
    val live = remember(liveSettings, liveContext) {
        com.cosmotune.ui.live.LiveNowHost(
            events = { com.cosmotune.music.watch.AndroidWatch.repository(liveContext).liveEvents() },
            settings = liveSettings,
            play = { event, option ->
                com.cosmotune.ui.watch.WatchNav.go(com.cosmotune.ui.watch.WatchTab.LIVE)
                com.cosmotune.ui.watch.WatchNav.open(com.cosmotune.ui.live.liveRoute(event, option))
                select(TvDestination.WATCH)
            },
        )
    }
    com.cosmotune.ui.live.LiveNowReminders(live)
    val activeDetail = detailStack.lastOrNull()
    val contentFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    val popDetail: () -> Unit = {
        detailStack.removeAt(detailStack.lastIndex)
        pendingReturn = returnFocus.removeLastOrNull()
        chrome.epoch++
        viewModel.closeDetail()
    }
    // Leaving the last pushed page: put focus back on the card that opened it.
    LaunchedEffect(activeDetail == null) {
        if (activeDetail != null) {
            hadDetail = true
            barHasFocus = false
        } else if (hadDetail) {
            hadDetail = false
            delay(16)
            val back = pendingReturn
            pendingReturn = null
            if (back == null || !runCatching { back.requestFocus() }.getOrDefault(false)) {
                runCatching { contentFocus.requestFocus() }
            }
        }
    }

    // tvOS Back: pop a pushed page; otherwise go back up to the tab bar; from
    // there, back to Home; from Home's tab, leave the app.
    BackHandler(enabled = !covered && (activeDetail != null || !barHasFocus || destination != TvDestination.HOME)) {
        when {
            activeDetail != null -> popDetail()
            !barHasFocus -> {
                chrome.scrollToTopRequests++
                runCatching { tabFocus.requestFocus() }
            }
            else -> select(TvDestination.HOME)
        }
    }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { tabFocus.requestFocus() }
    }
    // The tab bar only ever sits over a page at its top: taking focus sends the
    // page home, rather than leaving the bar floating over scrolled content.
    LaunchedEffect(barHasFocus) {
        if (barHasFocus) chrome.scrollToTopRequests++
        chrome.pageFocused = !barHasFocus
    }
    var wasCovered by remember { mutableStateOf(false) }
    LaunchedEffect(covered) {
        if (covered) {
            wasCovered = true
        } else if (wasCovered) {
            wasCovered = false
            delay(40)
            val back = focusBeforePlayer
            focusBeforePlayer = null
            if (back == null || !runCatching { back.requestFocus() }.getOrDefault(false)) {
                runCatching { tabFocus.requestFocus() }
            }
        }
    }

    // The bar floats over pushed pages too, stepping aside as they scroll.
    val barVisible = !chrome.contentScrolled || barHasFocus
    val barProgress by animateFloatAsState(if (barVisible) 1f else 0f, tween(260), label = "barProgress")

    CompositionLocalProvider(LocalTvChrome provides chrome, com.cosmotune.ui.live.LocalLiveNow provides live) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (covered) 0f else 1f }
                .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
                .focusGroup(),
        ) {
            // The tab's page stays composed under a pushed page — not drawn, and
            // fenced off from focus — so Back lands on the card that pushed it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (activeDetail == null) 1f else 0f }
                    .focusProperties { onEnter = { if (detailStack.isNotEmpty()) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Down from the tab bar enters the page at its top, never at
                        // a row remembered from further down.
                        .focusRequester(contentFocus)
                        .focusGroup(),
                ) {
                    stateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            TvDestination.PODIO -> com.cosmotune.music.ui.tv.screens.TvPodioScreen(mediaController, playerState, openNowPlaying)
                            TvDestination.WATCH -> com.cosmotune.music.ui.tv.screens.TvWatchScreen()
                            TvDestination.LIVE -> com.cosmotune.music.ui.tv.screens.TvLiveScreen()
                            TvDestination.HOME -> TvHomeScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                playerState = playerState,
                                onNavigateToDetail = openDetail,
                                onNavigateToNowPlaying = openNowPlaying,
                            )
                            TvDestination.EXPLORE -> TvExploreScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                onNavigateToDetail = openDetail,
                                onNavigateToNowPlaying = openNowPlaying,
                            )
                            TvDestination.LIBRARY -> TvLibraryScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                playerState = playerState,
                                onNavigateToDetail = openDetail,
                                onNavigateToNowPlaying = openNowPlaying,
                                onOpenAccount = onOpenAccount,
                            )
                            TvDestination.SEARCH -> TvSearchScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                onNavigateToDetail = openDetail,
                                onNavigateToNowPlaying = openNowPlaying,
                            )
                            TvDestination.SETTINGS -> TvSettingsScreen(
                                viewModel = viewModel,
                                onOpenAccount = onOpenAccount,
                                onRunSetupAgain = onRunSetupAgain,
                                onOpenRemote = onOpenRemote,
                            )
                        }
                    }
                }
            }

            if (activeDetail != null) {
                androidx.compose.runtime.key(activeDetail) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(detailFocus)
                            .focusGroup(),
                    ) {
                    TvDetailScreen(
                        browseId = activeDetail.browseId,
                        initialTitle = activeDetail.title,
                        initialSubtitle = activeDetail.subtitle,
                        initialThumbnailUrl = activeDetail.thumbnailUrl,
                        type = activeDetail.type,
                        viewModel = viewModel,
                        mediaController = mediaController,
                        playerState = playerState,
                        onNavigateToDetail = openDetail,
                        onNavigateToNowPlaying = openNowPlaying,
                        onBack = popDetail,
                    )
                    }
                }
            }

            TvTopBar(
                destination = destination,
                viewModel = viewModel,
                playerState = playerState,
                tabFocus = tabFocus,
                // Down from the bar goes into whatever page is on top.
                contentFocus = if (activeDetail != null) detailFocus else contentFocus,
                onSelect = select,
                onOpenNowPlaying = openNowPlaying,
                onOpenAccount = onOpenAccount,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f)
                    .graphicsLayer {
                        alpha = barProgress
                        translationY = -(1f - barProgress) * 72.dp.toPx()
                    }
                    .onFocusChanged { barHasFocus = it.hasFocus },
            )
        }
    }
}

/**
 * The chips at the top (board batch-be section E): Home, Search, Explore,
 * Library, Watch and Radio as Orbit chips from the safe margin, the selected one
 * solid ink; at the far end Now Playing (the playing cover as a small disc with
 * its orbit), Settings and the account. Home, Explore, Library and Radio switch
 * as focus lands on them, as TV tabs do; Search and Watch act on select.
 *
 * D-pad: Left and Right walk the chips in this order and stop at the ends; Down
 * from any of them enters the page on top ([contentFocus]); coming back up lands
 * on the selected chip ([tabFocus], by [focusRestorer]).
 */
@Composable
private fun TvTopBar(
    destination: TvDestination,
    viewModel: MainViewModel,
    playerState: PlayerState,
    tabFocus: FocusRequester,
    contentFocus: FocusRequester,
    onSelect: (TvDestination) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val googleAccounts by viewModel.googleAccounts.collectAsState()
    val activeAccountId by viewModel.activeAccountId.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val account by viewModel.account.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()

    val avatarUrl = remember(googleAccounts, activeAccountId, activeProfileId, account) {
        googleAccounts.firstOrNull { it.accountId == activeAccountId }
            ?.profiles?.firstOrNull { it.profileId == activeProfileId }
            ?.avatar
            ?: account?.thumbnailUrl
    }

    // Pages fill the screen from its top edge so they can scroll under the chips,
    // which puts them outside a plain "below" search from a chip. Point Down at them.
    val downToContent = Modifier.focusProperties { down = contentFocus }
    val chips = listOf(TvDestination.HOME, TvDestination.SEARCH, TvDestination.EXPLORE, TvDestination.LIBRARY, TvDestination.WATCH, TvDestination.PODIO)
    val onFocusTabs = setOf(TvDestination.HOME, TvDestination.EXPLORE, TvDestination.LIBRARY, TvDestination.WATCH, TvDestination.PODIO)
    // In Watch, Watch's own tabs follow the Watch chip in this one row (the Watch chip is Watch's home), rather than a second row.
    val watchTab by WatchNav.tab.collectAsState()
    val inWatch = destination == TvDestination.WATCH

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 26.dp - ChipRowBleed, start = TvDimensions.SafeMarginHorizontal - ChipRowBleed, end = TvDimensions.SafeMarginHorizontal)
            .focusRestorer(tabFocus)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val chipScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .weight(1f)
                .chipEdgeFade(chipScroll)
                .horizontalScroll(chipScroll)
                .padding(ChipRowBleed),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips.forEach { tab ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                val selected = destination == tab && (tab != TvDestination.WATCH || watchTab == WatchTab.HOME)
                if (tab in onFocusTabs) {
                    LaunchedEffect(focused) {
                        if (focused && !selected) {
                            delay(TabSwitchDelayMs)
                            if (tab == TvDestination.WATCH && destination == TvDestination.WATCH) WatchNav.go(WatchTab.HOME) else onSelect(tab)
                        }
                    }
                }
                TvTabPill(
                    label = tab.label,
                    selected = selected,
                    interactionSource = interaction,
                    onClick = { if (tab == TvDestination.WATCH && inWatch) WatchNav.go(WatchTab.HOME) else onSelect(tab) },
                    modifier = downToContent.then(if (selected) Modifier.focusRequester(tabFocus) else Modifier),
                )
                if (tab == TvDestination.WATCH && inWatch) {
                    WatchTab.entries.filter { it != WatchTab.HOME }.forEach { sub ->
                        val subInteraction = remember { MutableInteractionSource() }
                        val subFocused by subInteraction.collectIsFocusedAsState()
                        val subSelected = watchTab == sub
                        if (sub != WatchTab.SEARCH) {
                            LaunchedEffect(subFocused) {
                                if (subFocused && !subSelected) {
                                    delay(TabSwitchDelayMs)
                                    WatchNav.go(sub)
                                }
                            }
                        }
                        TvTabPill(
                            label = sub.label,
                            selected = subSelected,
                            interactionSource = subInteraction,
                            onClick = { WatchNav.go(sub) },
                            modifier = downToContent.then(if (subSelected) Modifier.focusRequester(tabFocus) else Modifier),
                        )
                    }
                }
            }
        }
        // The Live pill after the chips, while something followed is live: it opens the Live now page.
        TvLiveChip(
            selected = destination == TvDestination.LIVE,
            onClick = { onSelect(TvDestination.LIVE) },
            modifier = downToContent.then(if (destination == TvDestination.LIVE) Modifier.focusRequester(tabFocus) else Modifier),
        )
        TvNowPlayingChip(
            playerState = playerState,
            onClick = onOpenNowPlaying,
            modifier = downToContent,
        )
        TvTabIcon(
            icon = Icons.Rounded.Settings,
            contentDescription = "Settings",
            selected = destination == TvDestination.SETTINGS,
            onClick = { onSelect(TvDestination.SETTINGS) },
            modifier = downToContent.then(if (destination == TvDestination.SETTINGS) Modifier.focusRequester(tabFocus) else Modifier),
        )
        TvAvatarButton(
            imageUrl = if (signedIn) avatarUrl else null,
            onClick = onOpenAccount,
            modifier = downToContent,
        )
    }
}

/** The Live pill in the TV's look: the shared pill ([com.cosmotune.ui.live.LiveNowPillButton]) on the dark Orbit look, the TV's ring for focus. */
@Composable
private fun TvLiveChip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    com.cosmotune.ui.theme.CosmoLookTheme(dark = true, legacy = { content -> content() }) {
        com.cosmotune.ui.live.LiveNowPillButton(
            onOpen = onClick,
            interactionSource = interaction,
            modifier = modifier.tvLift(interaction, CircleShape, focusedScale = 1f, ringGap = if (selected) 5.dp else 3.dp),
        )
    }
}

/**
 * How long a chip that switches on focus waits for focus to stay before it switches: long enough that walking along the chips
 * at a normal pace does not open (and load) every page passed on the way, which on a TV also piled up the feeds' memory.
 */
private const val TabSwitchDelayMs = 350L

/** The chips fade out at an edge they carry on past, rather than being cut there. */
private fun Modifier.chipEdgeFade(scroll: androidx.compose.foundation.ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = 56.dp.toPx() / size.width
        val start = if (scroll.value > 0) 0f else 1f
        val end = if (scroll.value < scroll.maxValue) 0f else 1f
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = start),
                fade to Color.Black,
                1f - fade to Color.Black,
                1f to Color.Black.copy(alpha = end),
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/** Room round the scrolling chips for the focus ring, taken back out of the row's margins so the chips stay where they were. */
private val ChipRowBleed = 10.dp

/**
 * Now Playing at the end of the chips: the playing cover as a small disc with
 * its orbit (rule 2), or a plain round chip with the note while nothing plays.
 */
@Composable
private fun TvNowPlayingChip(playerState: PlayerState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val song = playerState.song
    if (song == null) {
        TvTabIcon(icon = CosmotuneIcons.MusicNote, contentDescription = "Now Playing", selected = false, onClick = onClick, modifier = modifier)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    val duration = playerState.durationMs
    val position = playerState.position
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = modifier
                .size(30.dp)
                .tvLift(interaction, CircleShape, focusedScale = 1f, ringGap = 8.dp)
                .tvClick(interaction, onClick = onClick),
        ) {
            TvArtwork(url = song.thumbnailUrl, px = 120, shape = CircleShape, modifier = Modifier.fillMaxSize())
        }
        OrbitRing(
            40.dp, TvGlass.Peach,
            progress = { if (duration > 0) position.positionMs.toFloat() / duration else null },
            spinning = playerState.isPlaying,
            stroke = 1.5.dp,
            moon = 3.dp,
        )
    }
}

/** The account's picture at the end of the chips; opens Account. */
@Composable
private fun TvAvatarButton(
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(40.dp)
            .tvLift(interaction, CircleShape, focusedScale = 1f, ringGap = 3.dp)
            .tvClick(interaction, onClick = onClick)
            .background(TvGlass.Fill),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = "Account",
                tint = TvGlass.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        } else {
            TvArtwork(url = imageUrl, px = 256, shape = CircleShape, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The Cosmotune mark, for the few places that show the app itself. */
@Composable
fun TvAppMark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 96.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.225f))
            .background(
                Brush.linearGradient(listOf(Color(0xFFFF5468), Color(0xFFE3173A))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = "Cosmotune",
            tint = Color.White,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}
