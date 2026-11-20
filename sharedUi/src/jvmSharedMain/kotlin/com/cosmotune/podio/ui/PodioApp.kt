package com.cosmotune.podio.ui

import com.cosmotune.music.playback.rememberLivePlayheadOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.rememberUiCoroutineScope
import com.cosmotune.ui.orbit.OrbitInputField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.AppMode
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.ui.player.PlayerBackHandler
import com.cosmotune.podio.*
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.room.roomItem
import com.cosmotune.ui.shell.CosmoBottomChrome
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import java.io.File
import kotlinx.coroutines.*

data class PodioPlayback(val id: String? = null, val title: String = "", val author: String = "", val playing: Boolean = false,
    val loading: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val error: String? = null, val rate: Float = 1f)

/** A section's tab, labelled under its glyph. */
private fun tabOf(section: PodioSection): CosmoTab = when (section) {
    PodioSection.RADIO -> CosmoTab(section.label, CosmoIcons.Radio, CosmoIcons.Radio)
    PodioSection.PODCASTS -> CosmoTab(section.label, Icons.Outlined.Podcasts, Icons.Filled.Podcasts)
    PodioSection.GLOBE -> CosmoTab(section.label, Icons.Outlined.Public, Icons.Filled.Public)
    PodioSection.FOLLOWING -> CosmoTab(section.label, CosmoIcons.Heart, CosmoIcons.HeartFill)
    PodioSection.DOWNLOADS -> CosmoTab(section.label, CosmoIcons.Download, CosmoIcons.Downloaded)
}

/**
 * The bottom bar and the rail carry the same four sections: Radio, Podcasts, Following (with its count of new episodes) and
 * Downloads. The Globe is opened from Radio (full screen on a phone) and lights Radio.
 */
private val NAV_SECTIONS = listOf(PodioSection.RADIO, PodioSection.PODCASTS, PodioSection.FOLLOWING, PodioSection.DOWNLOADS)

/** [NAV_SECTIONS] as tabs, Following carrying [following] (the new episodes of the shows followed; 0 for none). */
private fun navTabs(following: Int): List<CosmoTab> = NAV_SECTIONS.map { section ->
    tabOf(section).let { if (section == PodioSection.FOLLOWING) it.copy(count = following) else it }
}

/**
 * The same account-free radio/podcast mode is used by Android and every desktop target, in Orbit (Canvas repo,
 * design/cosmotune/batch-be/board.html, section C), laid out by the window on the shared navigation
 * ([com.cosmotune.ui.shell.CosmoNavShell]): under 600 dp the page with the shared dock (the mini player over the four tabs, the
 * Globe full screen from Radio); from 600 dp the rail with the same four tabs and the full player bar along the foot. On a wide
 * desktop window ([showNavigation] false) the sidebar chooses the section ([ModeNav]) and the host's frame holds the page; a host
 * that keeps the section in [ModeNav] while it also shows the navigation (a narrow desktop window) passes [followModeNav].
 */
@Composable
fun PodioApp(storage: File, playback: PodioPlayback, onPlay: (PodioItem, Long) -> Unit,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit,
    /** Leaves Podio for Music where the host supplies no account menu (the mode pill is the way between modes). */
    onMusic: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(), directory: PodioCatalogue? = null,
    /** False on the desktop, where the sidebar chooses Radio, Podcasts, Globe, Following and Downloads (see [ModeNav]). */
    showNavigation: Boolean = true,
    /** The section and the open station page are [ModeNav]'s even while the page shows its own navigation (the desktop). */
    followModeNav: Boolean = false,
    /** The desktop's volume (0..1) and a way to set it; null on the phone, where the volume keys do it. */
    volume: Float = 1f, onVolume: ((Float) -> Unit)? = null,
    /** Opens the application's settings; kept for hosts that pass it (the account menu reaches Settings). */
    @Suppress("UNUSED_PARAMETER") onSettings: (() -> Unit)? = null,
    sleepTimerMinutes: Int? = null, sleepAfterEpisode: Boolean = false,
    onSleepTimer: ((Int?) -> Unit)? = null, onSleepAfterEpisode: (() -> Unit)? = null,
    onDownloadEpisode: ((PodioItem) -> Unit)? = null, downloadedEpisodes: List<PodioItem> = emptyList(),
    onRemoveDownload: (suspend (PodioItem) -> Boolean)? = null,
    episodeDownloadStatus: Map<String, String> = emptyMap(), episodeDownloadsRunning: Set<String> = emptySet(),
    /** The native file dialog for "Import from another app" (OPML), where the host has one; export and share are in Settings › Podio. */
    onImportOpmlFile: (suspend () -> String?)? = null,
    remoteGlobeControls: Boolean = false,
    /**
     * Plays an episode followed by what is queued after it, so the player goes on by itself; null where the host plays one item at
     * a time ([onPlay]), and the queue then waits for the page.
     */
    onPlayQueue: ((PodioItem, Long, List<PodioItem>) -> Unit)? = null,
    /** Replaces what follows the playing episode in the player when the queue changes. */
    onUpcoming: ((List<PodioItem>) -> Unit)? = null) {
    val catalogue = remember(directory) { directory ?: PodioDefaults.catalogue }
    // The app's own catalogue keeps its pages for the process and on the disk; one a host or a test hands in keeps its own.
    val pages = remember(catalogue) { com.cosmotune.cache.PageCache.of(catalogue, persistent = directory == null) }
    val library = remember(storage) { PodioLibrary(storage) }
    val saved by library.state.collectAsUiState()
    // Feeds, saves and imports set the page's state on the UI thread (see rememberUiCoroutineScope), not on the worker that answers.
    val scope = rememberUiCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val host = PodioHost(onPlay, onDownloadEpisode, onRemoveDownload, onImportOpmlFile, remoteGlobeControls,
        copyText = { clipboard.setText(AnnotatedString(it)) }, onPlayQueue = onPlayQueue, onUpcoming = onUpcoming)
    val query = rememberSaveable { mutableStateOf("") }
    val language = rememberSaveable { mutableStateOf("") }
    val country = rememberSaveable { mutableStateOf("") }
    val genre = rememberSaveable { mutableStateOf("") }
    val searching = rememberSaveable { mutableStateOf(false) }
    val searchShows = rememberSaveable { mutableStateOf(false) }
    val satellite = rememberSaveable { mutableStateOf(false) }
    val globeFull = rememberSaveable { mutableStateOf(false) }
    val m = remember(catalogue, library) {
        PodioModel(catalogue, library, scope, host, query, language, country, genre, searching, searchShows, satellite, globeFull, pages)
    }
    m.host = host
    m.undoBar = com.cosmotune.ui.orbit.LocalOrbitUndo.current
    val queued by m.queue.state.collectAsUiState()

    // The section: the dock's or the rail's tab here, the sidebar's row on the desktop (the same channel in a narrow desktop window).
    val local = showNavigation && !followModeNav
    var localSection by rememberSaveable { mutableStateOf(PodioSection.RADIO) }
    val sidebarTab by ModeNav.podioTab.collectAsState()
    val sidebarSaved by ModeNav.podioSaved.collectAsState()
    val section = if (local) localSection else PodioFormat.section(sidebarTab, sidebarSaved)
    val globeView = rememberGlobeView()
    var compactLayout by remember { mutableStateOf(false) }
    fun chooseSection(next: PodioSection) {
        // The phone has no Globe tab: the Globe opens full screen over Radio.
        val shown = if (next == PodioSection.GLOBE && compactLayout) PodioSection.RADIO else next
        if (local) localSection = shown else { ModeNav.podioTab.value = shown.label; ModeNav.podioSaved.value = false }
        if (shown != next) m.globeFull = true
    }
    var localDetail by rememberSaveable { mutableStateOf(false) }
    val sidebarDetail by ModeNav.podioDetail.collectAsState()
    val detailOpen = if (local) localDetail else sidebarDetail
    fun setDetail(open: Boolean) { if (local) localDetail = open else ModeNav.podioDetail.value = open }
    val searchFocus = remember { FocusRequester() }
    fun openSearch() { m.searchShows = section == PodioSection.PODCASTS || section == PodioSection.FOLLOWING; m.searching = true }
    fun closeSearch() { m.searching = false; m.query = ""; m.browse() }
    LaunchedEffect(m.searching) { if (m.searching) { delay(50); runCatching { searchFocus.requestFocus() } } }

    // A change of section (from the tabs, or the desktop sidebar) leaves an open feed and a search, and starts the new section clean.
    LaunchedEffect(section) { m.browse(); m.query = ""; m.genre = ""; m.searching = false; m.globeFull = false; m.queueOpen = false }
    val stationsWanted = if (m.searching) !m.searchShows else section == PodioSection.RADIO || section == PodioSection.GLOBE
    val showsWanted = if (m.searching) m.searchShows else section == PodioSection.PODCASTS
    LaunchedEffect(stationsWanted, showsWanted, m.searching, m.language, m.country, m.genre, m.request) {
        // Network continuations can resume on a worker with an immediate effect dispatcher. Publish page state on the UI dispatcher.
        withContext(Dispatchers.Main.immediate) { if (stationsWanted || showsWanted) m.loadDirectory(stations = stationsWanted) }
    }
    var inboxRequest by remember { mutableIntStateOf(0) }
    // Following lists the new episodes of the followed shows, and the Podcasts home what was started of them; the Following tab
    // counts the new ones, so the list is asked for (from its cache while that is fresh) wherever something is followed.
    val inboxWanted = section == PodioSection.FOLLOWING || section == PodioSection.PODCASTS || saved.shows.isNotEmpty()
    LaunchedEffect(inboxWanted, saved.shows, saved.inboxEpisodesPerShow, inboxRequest) {
        if (inboxWanted) withContext(Dispatchers.Main.immediate) { m.loadInbox(saved.shows, force = inboxRequest > 0, episodeLimit = saved.inboxEpisodesPerShow) }
    }

    val live = playback.id?.startsWith("podio:radio:") == true
    val podio = playback.id?.startsWith("podio:") == true
    val episodeOn = playback.id?.startsWith("podio:episode:") == true
    // The episode playing with its show, as the queue keeps it (also after a restart), for its chapters and its show's speed.
    val playingEntry = queued.current?.takeIf { it.id == playback.id }?.let { InboxEpisode(it.episode, it.show) }
    // What follows the playing episode goes to the player whenever the queue (or Keep going's list) changes.
    val upcomingKey = queued.entries.map { it.id } to (if (queued.keepGoing) m.inbox.map { it.episode.id } + saved.seenEpisodes.size else emptyList<Any>())
    LaunchedEffect(upcomingKey, queued.keepGoing, episodeOn) {
        val replace = host.onUpcoming
        if (episodeOn && replace != null) replace(m.upcoming(queued))
    }
    // A show's own speed ("Use for this show") once its episode is on.
    LaunchedEffect(playback.id, playingEntry?.show?.id) {
        val speed = m.speedFor(playingEntry?.show?.id) ?: return@LaunchedEffect
        delay(400)
        if (kotlin.math.abs(playback.rate - speed) > 0.01f) onSpeed(speed)
    }
    LaunchedEffect(m.notice) { if (m.notice != null) { delay(5_000); m.notice = null } }
    fun openQueue() { m.playerSheet = false; m.queueOpen = true }
    val playingStation = if (live) (m.radio + saved.stations + listOfNotNull(m.selected)).firstOrNull { it.item().id == playback.id } else null
    // What the station is playing now, from its own stream; asked again every half minute while it plays.
    var song by remember(playback.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(playback.id, playingStation?.url) {
        val url = playingStation?.url ?: return@LaunchedEffect
        while (isActive) { song = catalogue.nowPlaying(url)?.takeIf { it != playingStation.name }; delay(30_000) }
    }
    val nowArt = m.lastPlayed?.takeIf { it.id == playback.id }?.artwork
    val sequence = playingStation?.let { station -> if (m.nearby.any { it.id == station.id }) m.nearby else m.radio }.orEmpty()
    val sequenceIndex = playingStation?.let { station -> sequence.indexOfFirst { it.id == station.id } } ?: -1
    val onAir = OnAir(playingStation, song,
        onPrevious = sequence.getOrNull(sequenceIndex - 1)?.takeIf { sequenceIndex > 0 }?.let { { m.playStation(it) } },
        onNext = sequence.getOrNull(sequenceIndex + 1)?.takeIf { sequenceIndex >= 0 }?.let { { m.playStation(it) } },
        onOpen = { setDetail(true) }, nextName = sequence.getOrNull(sequenceIndex + 1)?.takeIf { sequenceIndex >= 0 }?.name)
    val timer = PodioTimer(sleepTimerMinutes, sleepAfterEpisode, onSleepTimer, onSleepAfterEpisode)

    // Back: the open layer first, then the section's first tab; the host leaves the mode after that.
    PlayerBackHandler(enabled = section != PodioSection.RADIO && showNavigation) { chooseSection(PodioSection.RADIO) }
    PlayerBackHandler(enabled = m.globeFull) { m.globeFull = false }
    PlayerBackHandler(enabled = m.searching) { closeSearch() }
    PlayerBackHandler(enabled = m.requestedFeed != null) { m.browse() }
    PlayerBackHandler(enabled = m.queueOpen) { m.queueOpen = false }
    PlayerBackHandler(enabled = detailOpen && playingStation != null) { setDetail(false) }

    val palette = LocalCosmoColors.current.palette
    val look = orbitLook()
    val fonts = orbitFonts()
    Box(Modifier.fillMaxSize().background(Color(palette.backgroundBottom)).testTag("podio:root")) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = showNavigation && maxWidth < 600.dp
        SideEffect { compactLayout = compact }
        val pageHeight = maxHeight
        var dockPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        // The dock steps aside for the full-screen globe only (design system 4.16).
        val dockShown = compact && !m.globeFull
        val bottom = if (dockShown) with(density) { dockPx.toDp() } + 16.dp else 16.dp
        val head = rememberLivePlayheadOf(playback.positionMs, playback.playing && !playback.loading, playback.rate, playback.id)
        // A download being removed leaves the lists at once; its file goes when its Undo runs out.
        val shownDownloads = remember(downloadedEpisodes, m.removingDownloads) { downloadedEpisodes.filter { it.id !in m.removingDownloads } }
        val env = PodioEnv(saved, playback, shownDownloads, episodeDownloadStatus, episodeDownloadsRunning, bottom,
            // The page's head carries the account (or the way back to Music) at every width.
            onMusic,
            ::chooseSection, wide = !compact, onToggle = onToggle, head = head, onSeek = onSeek, queue = queued)
        // The player bar along the foot of the wide layouts: an episode anywhere; a station where no page shows its card.
        val stationCardShown = live && (detailOpen && playingStation != null ||
            (section == PodioSection.RADIO || section == PodioSection.GLOBE) && !m.searching && m.requestedFeed == null)
        val playerBar = !compact && podio && !stationCardShown

        val content: @Composable () -> Unit = {
            val station = playingStation
            when {
                detailOpen && station != null -> {
                    val here = remember(m.radio, station.id) { PodioPlaces.group(m.radio).firstOrNull { place -> place.stations.any { it.id == station.id } }?.stations.orEmpty().filter { it.id != station.id } }
                    RadioDetail(station, m, env, song, here, volume, onVolume, onPrevious = onAir.onPrevious, onNext = onAir.onNext, onBack = { setDetail(false) }, timer = timer, nextName = onAir.nextName)
                }
                m.queueOpen -> PodioQueuePage(m, env) { m.queueOpen = false }
                m.requestedFeed != null -> ShowPage(m, env)
                m.globeFull && compact -> RadioFullScreen(m, env, onAir, globeView, pageHeight, onToggle)
                m.searching -> SearchContent(m, env, searchFocus, ::closeSearch)
                section == PodioSection.RADIO -> RadioPage(m, env, onAir, ::openSearch, timer) { chooseSection(PodioSection.GLOBE) }
                section == PodioSection.GLOBE -> GlobePage(m, env, onAir, globeView)
                section == PodioSection.PODCASTS -> PodcastsPage(m, env, ::openSearch)
                section == PodioSection.FOLLOWING -> FollowingPage(m, env) { inboxRequest++ }
                else -> DownloadsPage(m, env)
            }
        }
        val banner: @Composable (Modifier) -> Unit = { place ->
            // A notice, or an error the page does not show in its own place (saving the library, the OPML files, a bad address).
            val pageShowsError = when {
                m.requestedFeed != null -> true
                m.searching -> if (m.searchShows) m.shows.isEmpty() else m.radio.isEmpty()
                section == PodioSection.RADIO || section == PodioSection.GLOBE -> m.radio.isEmpty()
                section == PodioSection.PODCASTS -> m.shows.isEmpty()
                else -> false
            }
            // "Removed · Undo" is the window's snackbar (LocalOrbitUndo), not a banner of Podio's own.
            val notice = m.notice
            val error = m.error?.takeIf { !pageShowsError }
            if (error != null) PodioBanner(error, "Dismiss", { m.error = null }, danger = true, look, fonts, modifier = place)
            else if (notice != null) PodioBanner(notice, "Dismiss", { m.notice = null }, danger = false, look, fonts, modifier = place)
        }

        if (!showNavigation) Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content(); banner(Modifier.align(Alignment.BottomCenter)) }
            if (playerBar) PodioPlayerBar(playback, playingStation, nowArt, onToggle, onSeek, onSpeed, timer, onAir.onOpen.takeIf { live },
                queued = queued.entries.size, onQueue = ::openQueue)
        } else {
            // The shared navigation (CosmoNav): under 600 dp the dock (Podio's mini player above the four tabs), from 600 dp the rail with
            // the same tabs and the full player bar under the page. The page leads with the mode pill at every width, as every mode's does.
            val tabs = navTabs(newEpisodes(m, env).size)
            val lit = NAV_SECTIONS.indexOf(if (section == PodioSection.GLOBE) PodioSection.RADIO else section)
            com.cosmotune.ui.shell.CosmoNavShell(
                windowWidth = maxWidth,
                tabs = tabs,
                selectedIndex = lit,
                onTabSelected = { chooseSection(NAV_SECTIONS[it]) },
                modifier = Modifier.padding(padding),
                // The full-screen globe has the whole window.
                navigation = !(compact && m.globeFull),
                bottomChrome = { showTabs, _ ->
                    if (showTabs) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        banner(Modifier)
                        // Without a shell (no theme above: a test, a preview) the dock is drawn as Music's, since it draws only for the stored mode.
                        CosmoBottomChrome(
                            tabs = tabs, selectedIndex = lit, onTabSelected = { chooseSection(NAV_SECTIONS[it]) },
                            song = null, isPlaying = playback.playing, isLoading = playback.loading, position = remember { PlaybackPosition() }, durationMs = playback.durationMs,
                            onPlayPause = onToggle, onNext = {}, onExpand = { if (live) setDetail(true) else m.playerSheet = true },
                            controlsLocked = false, onBlockedControl = {}, hazeState = null, onHeightChanged = { dockPx = it },
                            modifier = Modifier.fillMaxWidth(), mode = if (LocalCosmoShell.current == null) AppMode.MUSIC else AppMode.PODIO,
                            miniPlayer = if (podio) ({ PodioMiniPlayer(playback, playingStation, song, nowArt ?: playingStation?.artwork ?: playingEntry?.show?.artwork, onToggle,
                                onOpen = { if (live) setDetail(true) else m.playerSheet = true }, onSeek = onSeek, queued = queued.entries.size, onQueue = ::openQueue) }) else null,
                        )
                    }
                },
            ) {
                // One tree at every width (the player bar joins it beside the rail), so the page keeps its state when the window turns.
                Column(Modifier.fillMaxSize().then(if (compact) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom)))) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(LocalPodioModeSwitcher provides true) { content() }
                        // Beside the rail no dock carries the window's "Removed · Undo", so the page draws it over its foot.
                        if (!compact) Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 520.dp)) {
                            com.cosmotune.ui.orbit.OrbitUndoHost(com.cosmotune.ui.orbit.LocalOrbitUndo.current, Modifier.padding(horizontal = 16.dp))
                            banner(Modifier)
                        }
                    }
                    if (playerBar) PodioPlayerBar(playback, playingStation, nowArt, onToggle, onSeek, onSpeed, timer, onAir.onOpen.takeIf { live },
                        queued = queued.entries.size, onQueue = ::openQueue)
                }
            }
        }
        if (m.playerSheet && podio && !live) EpisodePlayerSheet(playback, nowArt ?: playingEntry?.item()?.artwork, onToggle, onSeek, onSpeed, timer,
            roomItem = (m.lastPlayed?.takeIf { it.id == playback.id } ?: playingEntry?.item())?.roomItem(),
            episode = playingEntry?.episode, show = playingEntry?.show?.title.orEmpty(), catalogue = catalogue,
            queued = queued.entries.size, onQueue = ::openQueue,
            showSpeed = m.speedFor(playingEntry?.show?.id), onShowSpeed = playingEntry?.let { entry -> { speed: Float? -> m.setShowSpeed(entry.show.id, speed) } }) { m.playerSheet = false }
        m.episodeSheet?.let { entry -> EpisodeSheet(m, env, entry) { m.episodeSheet = null } }
    }
    }
    if (m.addFeed) com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = { m.addFeed = false },
        title = "Open a podcast",
        content = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitInputField(m.feedAddress, { m.feedAddress = it }, "Podcast feed or show link", modifier = Modifier.fillMaxWidth())
            Text("RSS, Spotify or YouTube Music", style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = look.dim)
        } },
        confirmLabel = "Open podcast",
        onConfirm = { m.openLink(m.feedAddress) { if (section == PodioSection.RADIO) chooseSection(PodioSection.PODCASTS) } },
        dismissLabel = "Cancel",
        onDismiss = { m.addFeed = false },
    )
    if (m.importingOpml) com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = { m.importingOpml = false },
        title = "Import podcast subscriptions",
        content = { Column {
            Text("Paste an OPML export from your podcast player. Existing follows and listening positions are kept.")
            OrbitInputField(m.opmlText, { m.opmlText = it; m.opmlError = null }, "OPML document", singleLine = false, minLines = 5,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(top = 12.dp))
            m.opmlError?.let { Text(it, color = Color(palette.danger)) }
        } },
        confirmLabel = "Import subscriptions",
        onConfirm = { m.importSubscriptions(m.opmlText) { chooseSection(PodioSection.FOLLOWING) } },
        confirmEnabled = m.opmlText.isNotBlank(),
        dismissLabel = "Cancel",
        onDismiss = { m.importingOpml = false },
    )
}
