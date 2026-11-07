package com.cosmotune.podio.ui

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
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitModeSwitcher
import com.cosmotune.ui.kit.KitNavRail
import com.cosmotune.ui.kit.KitTextButton
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

/** The phone's dock carries four sections (the Globe opens full screen from Radio); a tablet's rail carries all five. */
private val DOCK_SECTIONS = listOf(PodioSection.RADIO, PodioSection.PODCASTS, PodioSection.FOLLOWING, PodioSection.DOWNLOADS)
private val DOCK_TABS = DOCK_SECTIONS.map(::tabOf)
private val RAIL_TABS = PodioSection.entries.map(::tabOf)

/**
 * The same account-free radio/podcast mode is used by Android and every desktop target, in Orbit (Canvas repo,
 * design/cosmotune/batch-be/board.html, section C), laid out by the window: on a phone the page with the shared dock (the mini
 * player over four labelled tabs, the Globe full screen from Radio); from 600 dp the rail with all five sections and the player
 * bar along the foot. On the desktop ([showNavigation] false) the sidebar chooses the section ([ModeNav]) and the host's frame
 * holds the page.
 */
@Composable
fun PodioApp(storage: File, playback: PodioPlayback, onPlay: (PodioItem, Long) -> Unit,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit,
    /** Leaves Podio for Music where the host supplies no account menu (the mode pill is the way between modes). */
    onMusic: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(), directory: PodioCatalogue? = null,
    /** False on the desktop, where the sidebar chooses Radio, Podcasts, Globe, Following and Downloads (see [ModeNav]). */
    showNavigation: Boolean = true,
    /** The desktop's volume (0..1) and a way to set it; null on the phone, where the volume keys do it. */
    volume: Float = 1f, onVolume: ((Float) -> Unit)? = null,
    /** Opens the application's settings; kept for hosts that pass it (the account menu reaches Settings). */
    @Suppress("UNUSED_PARAMETER") onSettings: (() -> Unit)? = null,
    sleepTimerMinutes: Int? = null, sleepAfterEpisode: Boolean = false,
    onSleepTimer: ((Int?) -> Unit)? = null, onSleepAfterEpisode: (() -> Unit)? = null,
    onDownloadEpisode: ((PodioItem) -> Unit)? = null, downloadedEpisodes: List<PodioItem> = emptyList(),
    episodeDownloadStatus: Map<String, String> = emptyMap(), episodeDownloadsRunning: Set<String> = emptySet(),
    onImportOpmlFile: (suspend () -> String?)? = null, onExportOpmlFile: (suspend (String) -> Boolean)? = null,
    onShareOpmlFile: (suspend (String) -> Unit)? = null) {
    val catalogue = remember(directory) { directory ?: PodioDefaults.catalogue }
    // The app's own catalogue keeps its pages for the process and on the disk; one a host or a test hands in keeps its own.
    val pages = remember(catalogue) { com.cosmotune.cache.PageCache.of(catalogue, persistent = directory == null) }
    val library = remember(storage) { PodioLibrary(storage) }
    val saved by library.state.collectAsUiState()
    // Feeds, saves and imports set the page's state on the UI thread (see rememberUiCoroutineScope), not on the worker that answers.
    val scope = rememberUiCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val host = PodioHost(onPlay, onDownloadEpisode, onImportOpmlFile, onExportOpmlFile, onShareOpmlFile) { clipboard.setText(AnnotatedString(it)) }
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

    // The section: the dock's or the rail's tab here, the sidebar's row on the desktop.
    var localSection by rememberSaveable { mutableStateOf(PodioSection.RADIO) }
    val sidebarTab by ModeNav.podioTab.collectAsState()
    val sidebarSaved by ModeNav.podioSaved.collectAsState()
    val section = if (showNavigation) localSection else PodioFormat.section(sidebarTab, sidebarSaved)
    val globeView = rememberGlobeView()
    var compactLayout by remember { mutableStateOf(false) }
    fun chooseSection(next: PodioSection) {
        // The phone has no Globe tab: the Globe opens full screen over Radio.
        if (next == PodioSection.GLOBE && compactLayout) { localSection = PodioSection.RADIO; m.globeFull = true; return }
        if (showNavigation) localSection = next else { ModeNav.podioTab.value = next.label; ModeNav.podioSaved.value = false }
    }
    var localDetail by rememberSaveable { mutableStateOf(false) }
    val sidebarDetail by ModeNav.podioDetail.collectAsState()
    val detailOpen = if (showNavigation) localDetail else sidebarDetail
    fun setDetail(open: Boolean) { if (showNavigation) localDetail = open else ModeNav.podioDetail.value = open }
    val searchFocus = remember { FocusRequester() }
    fun openSearch() { m.searchShows = section == PodioSection.PODCASTS || section == PodioSection.FOLLOWING; m.searching = true }
    fun closeSearch() { m.searching = false; m.browse() }
    LaunchedEffect(m.searching) { if (m.searching) { delay(50); runCatching { searchFocus.requestFocus() } } }

    // A change of section (from the tabs, or the desktop sidebar) leaves an open feed and a search, and starts the new section clean.
    LaunchedEffect(section) { m.browse(); m.query = ""; m.genre = ""; m.searching = false; m.globeFull = false }
    val stationsWanted = if (m.searching) !m.searchShows else section == PodioSection.RADIO || section == PodioSection.GLOBE
    val showsWanted = if (m.searching) m.searchShows else section == PodioSection.PODCASTS
    LaunchedEffect(stationsWanted, showsWanted, m.language, m.country, m.genre, m.request) {
        // Network continuations can resume on a worker with an immediate effect dispatcher. Publish page state on the UI dispatcher.
        withContext(Dispatchers.Main.immediate) { if (stationsWanted || showsWanted) m.loadDirectory(stations = stationsWanted) }
    }
    var inboxRequest by remember { mutableIntStateOf(0) }
    // Following lists the new episodes of the followed shows, and the Podcasts home what was started of them.
    val inboxWanted = section == PodioSection.FOLLOWING || section == PodioSection.PODCASTS
    LaunchedEffect(inboxWanted, saved.shows, inboxRequest) {
        if (inboxWanted) withContext(Dispatchers.Main.immediate) { m.loadInbox(saved.shows, force = inboxRequest > 0) }
    }

    val live = playback.id?.startsWith("podio:radio:") == true
    val podio = playback.id?.startsWith("podio:") == true
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
        onOpen = { setDetail(true) })
    val timer = PodioTimer(sleepTimerMinutes, sleepAfterEpisode, onSleepTimer, onSleepAfterEpisode)

    // Back: the open layer first, then the section's first tab; the host leaves the mode after that.
    PlayerBackHandler(enabled = section != PodioSection.RADIO && showNavigation) { chooseSection(PodioSection.RADIO) }
    PlayerBackHandler(enabled = m.globeFull) { m.globeFull = false }
    PlayerBackHandler(enabled = m.searching) { closeSearch() }
    PlayerBackHandler(enabled = m.requestedFeed != null) { m.browse() }
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
        val bottom = if (compact) with(density) { dockPx.toDp() } + 16.dp else 16.dp
        val env = PodioEnv(saved, playback, downloadedEpisodes, episodeDownloadStatus, episodeDownloadsRunning, bottom,
            // The rail carries the account (or the way back to Music) on a tablet; the page's head does elsewhere.
            onMusic.takeIf { compact || !showNavigation },
            ::chooseSection, wide = !compact, onToggle = onToggle)
        // The player bar along the foot of the wide layouts: an episode anywhere; a station where no page shows its card.
        val stationCardShown = live && (detailOpen && playingStation != null ||
            (section == PodioSection.RADIO || section == PodioSection.GLOBE) && !m.searching && m.requestedFeed == null)
        val playerBar = !compact && podio && !stationCardShown

        val content: @Composable () -> Unit = {
            val station = playingStation
            when {
                detailOpen && station != null -> {
                    val here = remember(m.radio, station.id) { PodioPlaces.group(m.radio).firstOrNull { place -> place.stations.any { it.id == station.id } }?.stations.orEmpty().filter { it.id != station.id } }
                    RadioDetail(station, m, env, song, here, volume, onVolume, onPrevious = onAir.onPrevious, onNext = onAir.onNext, onBack = { setDetail(false) }, timer = timer)
                }
                m.requestedFeed != null -> ShowPage(m, env)
                m.globeFull && compact -> RadioFullScreen(m, env, onAir, globeView, pageHeight, onToggle)
                m.searching -> SearchContent(m, env, searchFocus, ::closeSearch)
                section == PodioSection.RADIO -> RadioPage(m, env, onAir, ::openSearch) { chooseSection(PodioSection.GLOBE) }
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
            val notice = m.notice
            val error = m.error?.takeIf { !pageShowsError }
            if (error != null) PodioBanner(error, "Dismiss", { m.error = null }, danger = true, look, fonts, modifier = place)
            else if (notice != null) PodioBanner(notice, "Dismiss", { m.notice = null }, danger = false, look, fonts, modifier = place)
        }

        if (!showNavigation) Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content(); banner(Modifier.align(Alignment.BottomCenter)) }
            if (playerBar) PodioPlayerBar(playback, playingStation, nowArt, onToggle, onSeek, onSpeed, timer, onAir.onOpen.takeIf { live })
        } else if (!compact) Row(Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            KitNavRail(RAIL_TABS, section.ordinal, { chooseSection(PodioSection.entries[it]) }, Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars), header = {
                KitModeSwitcher(collapsed = true)
                val account = LocalModeProfileAction.current
                if (account != null) account() else if (onMusic != null) KitTextButton("Music", onMusic)
                Spacer(Modifier.height(12.dp))
            })
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalModeProfileAction provides null) { content() }
                    banner(Modifier.align(Alignment.BottomCenter))
                }
                if (playerBar) PodioPlayerBar(playback, playingStation, nowArt, onToggle, onSeek, onSpeed, timer, onAir.onOpen.takeIf { live })
            }
        } else Box(Modifier.fillMaxSize().padding(padding)) {
            CompositionLocalProvider(LocalPodioModeSwitcher provides true) { content() }
            if (dockShown) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                banner(Modifier)
                // The shared dock (design system 4.16): Podio's mini player above four tabs. Without the new look's shell (a test,
                // a preview) it is drawn as Music's, since the dock draws only for the stored mode.
                CosmoBottomChrome(
                    tabs = DOCK_TABS, selectedIndex = DOCK_SECTIONS.indexOf(section).coerceAtLeast(0), onTabSelected = { chooseSection(DOCK_SECTIONS[it]) },
                    song = null, isPlaying = playback.playing, isLoading = playback.loading, position = remember { PlaybackPosition() }, durationMs = playback.durationMs,
                    onPlayPause = onToggle, onNext = {}, onExpand = { if (live) setDetail(true) else m.playerSheet = true },
                    controlsLocked = false, onBlockedControl = {}, hazeState = null, onHeightChanged = { dockPx = it },
                    modifier = Modifier.fillMaxWidth(), mode = if (LocalCosmoShell.current == null) AppMode.MUSIC else AppMode.PODIO,
                    miniPlayer = if (podio) ({ PodioMiniPlayer(playback, playingStation, song, nowArt ?: playingStation?.artwork, onToggle,
                        onOpen = { if (live) setDetail(true) else m.playerSheet = true }) }) else null,
                )
            }
        }
        if (m.playerSheet && podio && !live) EpisodePlayerSheet(playback, nowArt, onToggle, onSeek, onSpeed, timer,
            roomItem = m.lastPlayed?.takeIf { it.id == playback.id }?.roomItem()) { m.playerSheet = false }
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
