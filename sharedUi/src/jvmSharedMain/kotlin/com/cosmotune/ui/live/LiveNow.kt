package com.cosmotune.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cosmotune.design.AppMode
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSpotlightStrip
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.OrbitToastPill
import com.cosmotune.ui.orbit.SpotlightComingRow
import com.cosmotune.ui.orbit.SpotlightCrest
import com.cosmotune.ui.orbit.SpotlightDetailHead
import com.cosmotune.ui.orbit.SpotlightFreshness
import com.cosmotune.ui.orbit.SpotlightLineups
import com.cosmotune.ui.orbit.SpotlightMatchCard
import com.cosmotune.ui.orbit.SpotlightNewsRow
import com.cosmotune.ui.orbit.SpotlightScorecard
import com.cosmotune.ui.orbit.SpotlightShowRow
import com.cosmotune.ui.orbit.SpotlightStatBars
import com.cosmotune.ui.orbit.SpotlightTimeline
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.orbitFloatEdge
import com.cosmotune.ui.orbit.orbitFloatGround
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchRoute
import com.cosmotune.ui.watch.asSummary
import com.cosmotune.watch.live.SpotlightSchedule
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveFocus
import com.cosmotune.watch.model.MatchDetail
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/*
 * The Spotlight (Canvas repo, design/cosmotune/search-spotlight/board.html, section 4): the strip in every mode, the sheet it opens
 * (a panel from the strip on the desktop, a sheet on the phone, a page on the TV) with its tabs, a match's detail, the reminders and
 * goal alerts, and its Settings section. The events come from Watch's [com.cosmotune.watch.WatchRepository.liveEvents]; the pieces it
 * draws with are the kit's (`ui/orbit/OrbitLive.kt`).
 */

/**
 * What a host hands the Spotlight: the events for what the person follows ([events], collected only while the strip is drawn and the
 * window is on screen: [shown] and the lifecycle), the person's choices, how a free stream plays ([play]: Watch's player; null hides
 * Watch), a match's detail ([detail]; null leaves matches without one), and, when the platform can, how a reminder or a goal is told
 * ([notify]: the desktop's tray; false or null shows it in the app instead). [shown] is the host's own word on whether its window is
 * up (the desktop's, put away in the tray); a minimised window or an app in the background is read from the lifecycle.
 */
class LiveNowHost(
    val events: (com.cosmotune.watch.model.LiveInterest) -> Flow<List<LiveEvent>>,
    val settings: LiveNowSettings,
    val play: ((LiveEvent, WatchOption) -> Unit)?,
    val notify: ((title: String, text: String) -> Boolean)? = null,
    val clock: () -> Long = System::currentTimeMillis,
    val shown: Flow<Boolean> = flowOf(true),
    val detail: (suspend (LiveEvent) -> MatchDetail?)? = null,
) {
    private val focuses = MutableStateFlow<Map<Any, LiveFocus>>(emptyMap())
    private val refreshes = MutableStateFlow(0)
    private val goalFlow = MutableStateFlow<GoalAlert?>(null)
    private var seen: Map<String, LiveEvent> = emptyMap()

    /** How closely the person looks now: the closest of what is open (the strip, the sheet, a match's detail). */
    internal val focus: StateFlow<Map<Any, LiveFocus>> = focuses.asStateFlow()
    internal val refresh: StateFlow<Int> = refreshes.asStateFlow()

    /** The latest goal in a followed match, for [LiveNowReminders] to tell. */
    internal val goals: StateFlow<GoalAlert?> = goalFlow.asStateFlow()

    internal fun look(owner: Any, focus: LiveFocus) { focuses.value = focuses.value + (owner to focus) }
    internal fun lookAway(owner: Any) { focuses.value = focuses.value - owner }

    /** Reads the scores again now (the refresh button). */
    fun refreshNow() { refreshes.value += 1 }

    /** Each answer as it comes: Settings learns its sides, and a new goal in a followed match is told. */
    internal fun took(list: List<LiveEvent>) {
        settings.learn(list)
        val follows: (LiveEvent) -> Boolean = { it.followedBy(settings.teams.value, settings.competitions.value) }
        if (seen.isNotEmpty()) newGoals(seen.values.toList(), list, follows).lastOrNull()?.let { goalFlow.value = it }
        seen = seen + list.filter { it.isMatch }.associateBy { it.id }
    }
}

/** The host's Spotlight, or null where there is none (nothing is drawn). */
val LocalLiveNow = staticCompositionLocalOf<LiveNowHost?> { null }

/**
 * True where the strip belongs under each page's own top bar (the phone): the pages then draw [LiveNowStrip]. The desktop's strip is
 * its top bar's ([LiveNowTopBarPill]) and the TV's its chips row's, so they leave this false.
 */
val LocalLiveNowInPage = staticCompositionLocalOf { false }

/**
 * The play every host uses unless it has its own: Watch's player page opens with the stream over whatever Watch showed, then Watch
 * becomes the mode (through [settings], the look's mode switch).
 */
fun watchModePlay(settings: CosmoLookSettings?): (LiveEvent, WatchOption) -> Unit = { event, option ->
    WatchNav.open(liveRoute(event, option))
    settings?.setMode(AppMode.WATCH)
}

/** Watch's player page for [option] of [event], titled by its channel (with its logo) or by the event. */
fun liveRoute(event: LiveEvent, option: WatchOption): WatchRoute.Play {
    val summary = option.channel?.asSummary() ?: TitleSummary(option.target.key, TitleType.FILM, event.title, origin = event.source, posterUrl = event.imageUrl)
    return WatchRoute.Play(option.target, summary)
}

/**
 * The events as they come, an empty list until the first answer. Collected only while the window is on screen (the host's
 * [LiveNowHost.shown] and the lifecycle: not in the tray, minimised or in the background), so nothing is read with nobody to see it;
 * the last answer stays shown and the reading picks up at once on return. The repository is told what is followed and how closely
 * the person looks ([liveInterest]): opening the sheet or a match reads again at once, and more often.
 */
@Composable
private fun collectEvents(host: LiveNowHost): State<List<LiveEvent>> {
    val sports by host.settings.sports.collectAsState()
    val teams by host.settings.teams.collectAsState()
    val competitions by host.settings.competitions.collectAsState()
    val focuses by host.focus.collectAsState()
    val refresh by host.refresh.collectAsState()
    val focus = focuses.values.maxByOrNull { it.ordinal } ?: LiveFocus.STRIP
    val interest = remember(sports, teams, competitions, focus, refresh) { liveInterest(sports, teams, competitions, focus, refresh) }
    val foreground = com.cosmotune.music.ui.rememberIsForeground()
    val shown by host.shown.collectAsState(true)
    val onScreen = foreground && shown
    val state = remember(host) { mutableStateOf(emptyList<LiveEvent>()) }
    LaunchedEffect(host, interest, onScreen) {
        if (!onScreen) return@LaunchedEffect
        try {
            host.events(interest).collect { state.value = it; host.took(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // A repository that fails leaves the last answer; the strip draws nothing until there is one.
        }
    }
    return state
}

/** Marks [focus] as looked at while this is drawn (the sheet open, a match open). */
@Composable
private fun LookingAt(host: LiveNowHost, focus: LiveFocus) {
    val owner = remember { Any() }
    DisposableEffect(host, focus) { host.look(owner, focus); onDispose { host.lookAway(owner) } }
}

/** A clock that moves every [stepMs], for the running minute, "Updated 12 s ago" and "in 2 h". */
@Composable
private fun ticking(host: LiveNowHost, stepMs: Long = 1_000): Long {
    val now by produceState(host.clock(), host, stepMs) { while (true) { delay(stepMs); value = host.clock() } }
    return now
}

@Composable
private fun rememberLists(host: LiveNowHost, events: List<LiveEvent>, now: Long): SpotlightLists {
    val sports by host.settings.sports.collectAsState()
    val teams by host.settings.teams.collectAsState()
    val competitions by host.settings.competitions.collectAsState()
    // Kept by the minute: a list's windows move with the clock, not with every tick.
    val minute = now / 60_000L
    return remember(events, sports, teams, competitions, minute) { SpotlightLists(events, sports, teams, competitions, now) }
}

// ---- the strip --------------------------------------------------------------------------------------------------------------

/**
 * The desktop's strip, in the top bar of every mode: nothing while it is off or there is nothing to show; a click opens the panel
 * under it (560 dp, its end on the strip's), a second click or a click outside closes it. Reminders and goals are told here, so they
 * come in every mode.
 */
@Composable
fun LiveNowTopBarPill(modifier: Modifier = Modifier) {
    val host = LocalLiveNow.current ?: return
    LiveNowReminders(host)
    val on by host.settings.pillOn.collectAsState()
    if (!on) return
    val events by collectEvents(host)
    val now = ticking(host, 10_000)
    val lists = rememberLists(host, events, now)
    if (lists.isEmpty) return
    val top = lists.headline()
    val fonts = orbitFonts()
    val look = orbitLook()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OrbitSpotlightStrip(top, lists.moreThan(top), now, fonts, look, onClick = { open = !open }, maxWidth = 420.dp)
        if (open) {
            Popup(popupPositionProvider = LivePanelPosition, onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                val density = LocalDensity.current
                val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
                LiveNowFloatingPanel(look, Modifier.width(560.dp).heightIn(max = (windowHeight - 96.dp).coerceAtLeast(320.dp))) {
                    LiveNowPanel(host, events, fonts, look, onClose = { open = false }, onPlayed = { open = false })
                }
            }
        }
    }
}

/**
 * The strip alone, for a host that opens the Spotlight its own way (the TV's chips row opens its page): nothing while it is off or
 * there is nothing to show. [interactionSource] lets the host draw its own focus.
 */
@Composable
fun LiveNowPillButton(onOpen: () -> Unit, modifier: Modifier = Modifier, interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null) {
    val host = LocalLiveNow.current ?: return
    val on by host.settings.pillOn.collectAsState()
    if (!on) return
    val events by collectEvents(host)
    val now = ticking(host, 10_000)
    val lists = rememberLists(host, events, now)
    if (lists.isEmpty) return
    val top = lists.headline()
    OrbitSpotlightStrip(top, lists.moreThan(top), now, orbitFonts(), orbitLook(), onClick = onOpen, modifier = modifier, interactionSource = interactionSource)
}

/** The ground of the desktop's panel (board `.pop`): radius 30, the menu's deep ground with its edge and shadow, 18 dp in. */
@Composable
fun LiveNowFloatingPanel(look: OrbitLook, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier.shadow(30.dp, shape, clip = false).clip(shape).background(orbitFloatGround(look)).border(1.dp, orbitFloatEdge(look), shape)
            .verticalScroll(rememberScrollState()).padding(18.dp),
    ) { content() }
}

/** Under the strip, its end on the strip's end, 8 dp below; held inside the window 16 dp from its edges. */
private object LivePanelPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = 16
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val x = (if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left).coerceIn(minOf(margin, maxX), maxX)
        return IntOffset(x, anchorBounds.bottom + 8)
    }
}

/**
 * The phone's strip, under a page's own top bar ([LocalLiveNowInPage]): the whole width, "+n" at the far end; a tap opens the
 * Spotlight as a sheet. Draws nothing on the desktop and the TV, while it is off, or while there is nothing to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveNowStrip(modifier: Modifier = Modifier) {
    if (!LocalLiveNowInPage.current) return
    val host = LocalLiveNow.current ?: return
    val on by host.settings.pillOn.collectAsState()
    if (!on) return
    val events by collectEvents(host)
    val now = ticking(host, 10_000)
    val lists = rememberLists(host, events, now)
    if (lists.isEmpty) return
    val top = lists.headline()
    val fonts = orbitFonts()
    val look = orbitLook()
    var open by rememberSaveable { mutableStateOf(false) }
    OrbitSpotlightStrip(top, lists.moreThan(top), now, fonts, look, onClick = { open = true }, modifier = modifier.fillMaxWidth(), stretch = true, height = 40.dp)
    if (open) {
        com.cosmotune.ui.sheets.SurfaceSheetSwitch(onDismissRequest = { open = false }) {
            LiveNowPanel(host, events, fonts, look, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp), onPlayed = { open = false })
        }
    }
}

// ---- the sheet --------------------------------------------------------------------------------------------------------------

/**
 * The Spotlight itself (board section 4, the sheet): "Spotlight." with how many are live and how fresh the scores are (and a quiet
 * "Scores may be behind" when a live match's source stopped answering), the refresh button, the tabs (Top, World, the country,
 * Football, Cricket, Coming up), and the tab's lists. A match opens its detail in place (with a way back); a story opens its article.
 * [onClose] adds a close button (the desktop's panel); [onPlayed] is called after Watch is pressed. Not lazy: the panel, the sheet and
 * the TV page scroll it.
 */
@Composable
fun LiveNowPanel(
    host: LiveNowHost,
    events: List<LiveEvent>,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onPlayed: () -> Unit = {},
    titleSize: Dp = 26.dp,
) {
    LookingAt(host, LiveFocus.SHEET)
    val now = ticking(host)
    val lists = rememberLists(host, events, now)
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val opened = openId?.let { id -> events.firstOrNull { it.id == id } }
    val play = host.play
    val watch: (LiveEvent) -> (() -> Unit)? = { e -> if (play == null) null else firstWatch(e)?.let { o -> { play(e, o); onPlayed() } } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (opened != null) {
            MatchDetailView(host, opened, now, fonts, look, onBack = { openId = null }, onClose = onClose, onWatch = watch(opened))
            return@Column
        }
        SheetHeading(host, events, lists, now, fonts, look, titleSize, onClose)
        val tabs = lists.tabs()
        var tab by rememberSaveable { mutableStateOf(SpotlightTab.TOP) }
        val shownTab = tab.takeIf { it in tabs } ?: SpotlightTab.TOP
        if (tabs.isEmpty()) {
            WatchMessage("Spotlight", "Nothing to show yet", "Live scores and the world's news come here. Check your connection, or try again in a minute.", fonts, look, Modifier.fillMaxWidth())
            return@Column
        }
        OrbitChipRow(0.dp) {
            tabs.forEach { t -> OrbitSelectChip(lists.tabName(t), t == shownTab, fonts, look, onClick = { tab = t }, haptic = false) }
        }
        val uri = LocalUriHandler.current
        val open: (LiveEvent) -> Unit = { e -> if (e.isMatch && host.detail != null) openId = e.id else e.link?.let { runCatching { uri.openUri(it) } } }
        val reminders by host.settings.reminders.collectAsState()
        val remind: (LiveEvent) -> (() -> Unit)? = { e -> if (e.isMatch && e.startMs != null) ({ host.settings.toggleReminder(e) }) else null }
        when (shownTab) {
            SpotlightTab.TOP -> {
                MatchCards(lists.topMatches.take(6), now, fonts, look, open, watch)
                lists.shows.forEach { e -> SpotlightShowRow(e, fonts, look, onWatch = watch(e)) }
                if (lists.world.isNotEmpty()) {
                    if (lists.topMatches.isNotEmpty() || lists.shows.isNotEmpty()) Section("World", fonts, look)
                    lists.world.take(5).forEach { e -> key(e.id) { SpotlightNewsRow(e, now, fonts, look, onClick = { open(e) }) } }
                }
            }
            SpotlightTab.WORLD -> {
                lists.world.take(20).forEach { e -> key(e.id) { SpotlightNewsRow(e, now, fonts, look, onClick = { open(e) }) } }
                if (lists.editors.isNotEmpty()) {
                    Section("In the news", fonts, look)
                    lists.editors.forEach { e -> key(e.id) { SpotlightNewsRow(e, now, fonts, look, onClick = { open(e) }) } }
                }
            }
            SpotlightTab.COUNTRY -> lists.country.forEach { e -> key(e.id) { SpotlightNewsRow(e, now, fonts, look, onClick = { open(e) }) } }
            SpotlightTab.FOOTBALL, SpotlightTab.CRICKET -> {
                val kind = if (shownTab == SpotlightTab.FOOTBALL) LiveEventKind.FOOTBALL else LiveEventKind.CRICKET
                val live = lists.live(kind)
                val upcoming = lists.upcoming(kind)
                val finished = lists.finished(kind)
                if (live.isNotEmpty()) { Section("Live now", fonts, look, first = true); MatchCards(live, now, fonts, look, open, watch) }
                if (upcoming.isNotEmpty()) {
                    Section("Upcoming", fonts, look, first = live.isEmpty())
                    upcoming.take(12).forEach { e -> key(e.id) { SpotlightComingRow(e, now, reminders.any { it.id == e.id }, remind(e), onClick = { open(e) }, fonts, look) } }
                }
                if (finished.isNotEmpty()) {
                    Section("Just finished", fonts, look, first = live.isEmpty() && upcoming.isEmpty())
                    MatchCards(finished.take(12), now, fonts, look, open, watch)
                }
            }
            SpotlightTab.COMING -> lists.coming.forEach { e ->
                key(e.id) { SpotlightComingRow(e, now, reminders.any { it.id == e.id }, remind(e), onClick = { open(e) }, fonts, look) }
            }
        }
        Credits(events, shownTab, fonts, look)
    }
}

@Composable
private fun SheetHeading(host: LiveNowHost, events: List<LiveEvent>, lists: SpotlightLists, now: Long, fonts: OrbitFonts, look: OrbitLook, titleSize: Dp, onClose: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString { append("Spotlight"); withStyle(SpanStyle(color = look.accent)) { append(".") } },
                style = fonts.section.copy(fontSize = titleSize.value.sp, lineHeight = (titleSize.value + 2).sp), color = look.ink,
            )
            val live = lists.live().size
            val scores = events.filter { it.isMatch }
            val updated = updatedText((scores.ifEmpty { events }).maxOfOrNull { it.updatedAtMs } ?: 0, now)
            Spacer(Modifier.height(6.dp))
            SpotlightFreshness(listOfNotNull("$live live".takeIf { live > 0 }, updated).joinToString(" · ").ifBlank { null }, SpotlightSchedule.isBehind(events, now), fonts, look)
        }
        WatchRoundButton(CosmoIcons.Replay, "Refresh", look, onClick = host::refreshNow)
        if (onClose != null) WatchRoundButton(CosmoIcons.Close, "Close", look, onClose)
    }
}

@Composable
private fun Section(title: String, fonts: OrbitFonts, look: OrbitLook, first: Boolean = false) {
    OrbitLabel(title, fonts, look.dim, Modifier.padding(start = 2.dp, top = if (first) 0.dp else 8.dp))
}

/** Cards in place: each keyed by its match, so a goal changes its card without the list jumping. */
@Composable
private fun MatchCards(list: List<LiveEvent>, now: Long, fonts: OrbitFonts, look: OrbitLook, open: (LiveEvent) -> Unit, watch: (LiveEvent) -> (() -> Unit)?) {
    list.forEach { e -> key(e.id) { SpotlightMatchCard(e, now, fonts, look, onClick = { open(e) }, onWatch = watch(e)) } }
}

/** Where it all comes from, said once under the lists: the outlets, ESPN, Wikipedia (CC BY-SA) and Wikidata as they are used. */
@Composable
private fun Credits(events: List<LiveEvent>, tab: SpotlightTab, fonts: OrbitFonts, look: OrbitLook) {
    val parts = buildList {
        if (events.any { it.isMatch && it.source == "ESPN" }) add("Scores: ESPN")
        if (events.any { it.source == "OpenLigaDB" }) add("OpenLigaDB")
        val outlets = events.filter { it.kind == LiveEventKind.NEWS && !it.local && it.source != "Wikipedia" }.flatMap { it.outlets }.distinct()
        if (outlets.isNotEmpty() && tab != SpotlightTab.COUNTRY) add("News: " + outlets.take(5).joinToString(", "))
        if (tab == SpotlightTab.COUNTRY && events.any { it.local }) add("Google News")
        if (events.any { it.source == "Wikipedia" } && (tab == SpotlightTab.WORLD || tab == SpotlightTab.TOP)) add("In the news: Wikipedia, CC BY-SA")
        if (events.any { it.kind == LiveEventKind.EVENT } && tab == SpotlightTab.COMING) add("Events: Wikidata, Wikipedia")
    }
    if (parts.isEmpty()) return
    Text(parts.joinToString(" · "), style = fonts.body.copy(fontSize = 11.sp, lineHeight = 14.sp), color = look.dim.copy(alpha = 0.85f), modifier = Modifier.padding(start = 2.dp, top = 8.dp))
}

// ---- a match's detail -------------------------------------------------------------------------------------------------------

/**
 * A match opened (board, third sheet): back, the head with each side and the pill, then for football the Events timeline, Stats as
 * paired bars and Line-ups; for cricket the scorecard. Read when it opens and every 15 s while the match is live.
 */
@Composable
private fun MatchDetailView(host: LiveNowHost, event: LiveEvent, now: Long, fonts: OrbitFonts, look: OrbitLook, onBack: () -> Unit, onClose: (() -> Unit)?, onWatch: (() -> Unit)?) {
    LookingAt(host, LiveFocus.DETAIL)
    val read = host.detail
    var detail by remember(event.id) { mutableStateOf<MatchDetail?>(null) }
    var failed by remember(event.id) { mutableStateOf(false) }
    val live = event.isLive
    LaunchedEffect(event.id, live, read) {
        if (read == null) return@LaunchedEffect
        while (true) {
            val got = runCatching { read(event) }.getOrNull()
            if (got != null) detail = got else if (detail == null) failed = true
            if (!live) break
            delay(SpotlightSchedule.DETAIL_LIVE_MS)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchRoundButton(CosmoIcons.ArrowLeft, "Back", look, onBack)
        Text(event.title, style = fonts.title, color = look.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onClose != null) WatchRoundButton(CosmoIcons.Close, "Close", look, onClose)
    }
    SpotlightDetailHead(event, now, fonts, look)
    if (onWatch != null) WatchCardChip("Watch", look.ink, fonts, onWatch, icon = CosmoIcons.Play, iconGap = 6.dp)
    val football = detail?.football
    val cricket = detail?.cricket
    when {
        football != null -> {
            var part by rememberSaveable(event.id) { mutableStateOf(0) }
            val parts = listOfNotNull("Events".takeIf { football.timeline.isNotEmpty() }, "Stats".takeIf { football.stats.isNotEmpty() }, "Line-ups".takeIf { football.lineups.isNotEmpty() })
            if (parts.isEmpty()) {
                Text(if (event.status == LiveEventStatus.UPCOMING) "Line-ups and events come here once the match is near." else "Nothing more about this match yet.",
                    style = fonts.body, color = look.dim)
            } else {
                val chosen = parts.getOrElse(part) { parts.first() }
                OrbitChipRow(0.dp) { parts.forEachIndexed { i, p -> OrbitSelectChip(p, p == chosen, fonts, look, onClick = { part = i }, haptic = false) } }
                when (chosen) {
                    "Events" -> SpotlightTimeline(football.timeline, event.teams, fonts, look)
                    "Stats" -> SpotlightStatBars(football.stats, fonts, look)
                    else -> SpotlightLineups(football.lineups, fonts, look)
                }
            }
        }
        cricket != null -> SpotlightScorecard(cricket, fonts, look)
        failed || read == null -> Text("The match's details could not be read just now.", style = fonts.body, color = look.dim)
        else -> Text("Reading the match…", style = fonts.body, color = look.dim)
    }
    Text("Data: ${event.source}", style = fonts.body.copy(fontSize = 11.sp), color = look.dim.copy(alpha = 0.85f), modifier = Modifier.padding(top = 4.dp))
}

/**
 * The Spotlight as a page of its own (the TV's): the sheet at the page's width, scrolling. Watch takes the person to Watch's player
 * through the host's [LiveNowHost.play].
 */
@Composable
fun LiveNowPage(modifier: Modifier = Modifier, contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
    val host = LocalLiveNow.current ?: return
    val events by collectEvents(host)
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(modifier.verticalScroll(rememberScrollState()).padding(contentPadding)) {
        LiveNowPanel(host, events, fonts, look, modifier = Modifier.widthIn(max = 880.dp), titleSize = 38.dp)
    }
}

// ---- reminders and goals ----------------------------------------------------------------------------------------------------

/**
 * Tells what is due: five minutes before a reminded match, and a goal in a followed match while the app is in front. The platform
 * is told when it can ([LiveNowHost.notify], the desktop's tray); otherwise a toast comes up at the foot of the window for ten
 * seconds. Only while the app runs; nothing is read in the background for it.
 */
@Composable
fun LiveNowReminders(host: LiveNowHost) {
    var due by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host) {
        while (true) {
            host.settings.takeDue(host.clock()).forEach { r ->
                val text = "Starts ${startText(r.startMs, host.clock()).lowercase().replace("starting", "now")}"
                val told = runCatching { host.notify?.invoke(r.title, text) == true }.getOrDefault(false)
                if (!told) due = "${r.title} starts ${startText(r.startMs, host.clock()).lowercase().replace("starting", "now")}"
            }
            delay(30_000)
        }
    }
    val goal by host.goals.collectAsState()
    LaunchedEffect(goal) {
        val g = goal ?: return@LaunchedEffect
        val told = runCatching { host.notify?.invoke(g.title, g.text) == true }.getOrDefault(false)
        if (!told) due = "${g.title} · ${g.text}"
    }
    val shown = due ?: return
    LaunchedEffect(shown) { delay(10_000); if (due == shown) due = null }
    val look = orbitLook()
    Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -120), onDismissRequest = { due = null }) {
        OrbitToastPill(shown, orbitFonts(), look, Modifier.padding(horizontal = 16.dp), actionIcon = CosmoIcons.Close, actionLabel = "Dismiss", onAction = { due = null })
    }
}

// ---- Settings ---------------------------------------------------------------------------------------------------------------

/**
 * Settings > Spotlight: the strip on or off, the sports shown, and what is followed: the teams and competitions chosen (tap to drop
 * one), and a search over the teams and competitions the Spotlight has seen (and ESPN's football competitions) to add more. Follows
 * come first everywhere and a goal in a followed match is told. Kept on this device ([LiveNowSettings]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveNowSettingsSection(settings: LiveNowSettings = rememberLiveNowSettings(), modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val on by settings.pillOn.collectAsState()
    val sports by settings.sports.collectAsState()
    val teams by settings.teams.collectAsState()
    val competitions by settings.competitions.collectAsState()
    val knownTeams by settings.knownTeams.collectAsState()
    val knownCompetitions by settings.knownCompetitions.collectAsState()
    var typed by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Spotlight", style = fonts.title, color = look.ink)
                Text("Live scores and the world's main stories at the top of every mode.", style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = look.dim)
            }
            OrbitSwitch(on, settings::setPillOn, look, "Spotlight")
        }
        OrbitLabel("Sports", fonts, look.dim, Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveNowSettings.followedKinds.sortedBy { it.ordinal }.forEach { k ->
                OrbitSelectChip(k.label(), k in sports, fonts, look, onClick = { settings.setSport(k, k !in sports) }, icon = if (k in sports) CosmoIcons.Check else null, haptic = false)
            }
        }
        OrbitLabel(if (teams.isEmpty() && competitions.isEmpty()) "Follow · listed first, and told when they score" else "You follow", fonts, look.dim, Modifier.padding(top = 8.dp))
        if (teams.isNotEmpty() || competitions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                competitions.forEach { id ->
                    val name = knownCompetitions.firstOrNull { it.id.equals(id, true) }?.name ?: id
                    OrbitSelectChip(name, true, fonts, look, onClick = { settings.setCompetition(id, false) }, icon = CosmoIcons.Check, haptic = false)
                }
                teams.forEach { t -> OrbitSelectChip(t, true, fonts, look, onClick = { settings.setTeam(t, false) }, icon = CosmoIcons.Check, haptic = false) }
            }
        }
        OrbitTextField(typed, { typed = it }, "Find a team or competition", fonts, look, Modifier.fillMaxWidth().padding(top = 4.dp))
        val query = typed.trim()
        if (query.isEmpty()) {
            OrbitLabel("Competitions", fonts, look.dim, Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                knownCompetitions.filter { c -> competitions.none { it.equals(c.id, true) } }.take(16).forEach { c ->
                    OrbitSelectChip(c.name, false, fonts, look, onClick = { settings.setCompetition(c.id, true) }, icon = CosmoIcons.Plus, haptic = false)
                }
            }
        } else {
            val q = query.lowercase()
            val compHits = knownCompetitions.filter { it.name.lowercase().contains(q) && competitions.none { f -> f.equals(it.id, true) } }.take(4)
            val teamHits = knownTeams.filter { t -> (t.name.lowercase().contains(q) || t.shortName?.lowercase() == q) && teams.none { it.equals(t.name, true) } }.take(8)
            compHits.forEach { c ->
                FollowRow(c.name, if (c.kind == LiveEventKind.CRICKET) "Cricket" else "Football", null, fonts, look) { settings.setCompetition(c.id, true); typed = "" }
            }
            teamHits.forEach { t ->
                FollowRow(t.name, listOfNotNull(t.competition, t.kind.label()).joinToString(" · "), t, fonts, look) { settings.setTeam(t.name, true); typed = "" }
            }
            if (compHits.isEmpty() && teamHits.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Not seen yet. Follow it by its name?", style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    WatchCardChip("Follow", look.ink, fonts, onClick = { settings.setTeam(query, true); typed = "" }, icon = CosmoIcons.Plus)
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun FollowRow(title: String, line: String, team: KnownTeam?, fonts: OrbitFonts, look: OrbitLook, onFollow: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (team != null) SpotlightCrest(com.cosmotune.watch.model.LiveTeam(team.name, team.shortName, team.logoUrl), team.name, 28.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(line, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        WatchCardChip("Follow", look.ink, fonts, onClick = onFollow, icon = CosmoIcons.Plus)
    }
}
