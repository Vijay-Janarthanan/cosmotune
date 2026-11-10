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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
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
import com.cosmotune.ui.orbit.OrbitLiveCard
import com.cosmotune.ui.orbit.OrbitLiveComingRow
import com.cosmotune.ui.orbit.OrbitLivePill
import com.cosmotune.ui.orbit.OrbitLiveRow
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.OrbitToastPill
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
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamProtocol
import com.cosmotune.watch.model.StreamSource
import com.cosmotune.watch.plugin.BuiltInPlugins
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/*
 * "Live now" (Canvas repo, design/cosmotune/live-strip/board.html): the Live pill in every mode, the panel it opens (a panel from
 * the pill on the desktop, a sheet on the phone, a page on the TV), its reminders and its Settings section. The events come from
 * Watch's [com.cosmotune.watch.WatchRepository.liveEvents]; the pieces it draws with are the kit's (`ui/orbit/OrbitLive.kt`).
 */

/**
 * What a host hands "Live now": the events ([events], collected only while the pill is drawn), the person's choices, how a free
 * stream plays ([play]: Watch's player; null hides Watch) and, when the platform can, how a due reminder is told ([notify]: the
 * desktop's tray; false or null shows it in the app instead).
 */
class LiveNowHost(
    val events: () -> Flow<List<LiveEvent>>,
    val settings: LiveNowSettings,
    val play: ((LiveEvent, WatchOption) -> Unit)?,
    val notify: ((title: String, text: String) -> Boolean)? = null,
    val clock: () -> Long = System::currentTimeMillis,
)

/** The host's "Live now", or null where there is none (nothing is drawn). */
val LocalLiveNow = staticCompositionLocalOf<LiveNowHost?> { null }

/**
 * True where the pill belongs under each page's own top bar (the phone): the pages then draw [LiveNowStrip]. The desktop's pill is
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
    val summary = option.channel?.asSummary() ?: TitleSummary(option.target.key, TitleType.FILM, event.title, origin = event.source,
        posterUrl = event.imageUrl)
    val chartStream = event.link?.takeIf { option.target.key.namespace == BuiltInPlugins.YOUTUBE }?.let { link ->
        Stream(StreamSource(BuiltInPlugins.YOUTUBE, "YouTube", "YT"), "YouTube", url = link, protocol = StreamProtocol.YOUTUBE, isPublic = true)
    }
    return WatchRoute.Play(option.target, summary, stream = chartStream)
}

/** The events as they come, an empty list until the first answer. */
@Composable
private fun collectEvents(host: LiveNowHost): State<List<LiveEvent>> =
    produceState(emptyList(), host) { runCatching { host.events().collect { value = it } } }

/** The followed events, split, for [kind] (null: every kind). */
@Composable
private fun rememberLists(host: LiveNowHost, events: List<LiveEvent>, kind: LiveEventKind? = null): LiveNowLists {
    val sports by host.settings.sports.collectAsState()
    val teams by host.settings.teams.collectAsState()
    return remember(events, sports, teams, kind) { LiveNowLists.of(events, sports, teams, kind) }
}

/** A clock that moves every [stepMs], for "updated 20 s ago" and "Starting". */
@Composable
private fun ticking(host: LiveNowHost, stepMs: Long = 15_000): Long {
    val now by produceState(host.clock(), host) { while (true) { delay(stepMs); value = host.clock() } }
    return now
}

// ---- the pill ---------------------------------------------------------------------------------------------------------------

/**
 * The desktop's pill, in the top bar of every mode: nothing while the pill is off or nothing followed is live; a tap opens the
 * panel under it (560 dp, its end on the pill's), a second tap or a click outside closes it. Reminders are watched here, so they
 * come in every mode.
 */
@Composable
fun LiveNowTopBarPill(modifier: Modifier = Modifier) {
    val host = LocalLiveNow.current ?: return
    LiveNowReminders(host)
    val on by host.settings.pillOn.collectAsState()
    if (!on) return
    val events by collectEvents(host)
    val lists = rememberLists(host, events)
    val top = lists.headline(ticking(host))
    if (lists.isEmpty) return
    val fonts = orbitFonts()
    val look = orbitLook()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OrbitLivePill(top, top?.let(lists::moreFor) ?: 0, fonts, look, onClick = { open = !open })
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
 * The pill alone, for a host that opens "Live now" its own way (the TV's chips row opens its page): nothing while it is off or
 * nothing followed is live. [interactionSource] lets the host draw its own focus.
 */
@Composable
fun LiveNowPillButton(onOpen: () -> Unit, modifier: Modifier = Modifier, interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null) {
    val host = LocalLiveNow.current ?: return
    val on by host.settings.pillOn.collectAsState()
    if (!on) return
    val events by collectEvents(host)
    val lists = rememberLists(host, events)
    val top = lists.headline(ticking(host))
    if (lists.isEmpty) return
    OrbitLivePill(top, top?.let(lists::moreFor) ?: 0, orbitFonts(), orbitLook(), onClick = onOpen, modifier = modifier, interactionSource = interactionSource)
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

/** Under the pill, its end on the pill's end, 8 dp below; held inside the window 16 dp from its edges. */
private object LivePanelPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = 16
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(0)
        val x = (if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left).coerceIn(minOf(margin, maxX), maxX)
        return IntOffset(x, anchorBounds.bottom + 8)
    }
}

/**
 * The phone's pill, under a page's own top bar ([LocalLiveNowInPage]): the whole width, the count at the far end; a tap opens
 * "Live now" as a sheet. Draws nothing on the desktop and the TV, while the pill is off, or while nothing followed is live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveNowStrip(modifier: Modifier = Modifier) {
    if (!LocalLiveNowInPage.current) return
    val host = LocalLiveNow.current ?: return
    val on by host.settings.pillOn.collectAsState()
    if (!on) return
    val events by collectEvents(host)
    val lists = rememberLists(host, events)
    val top = lists.headline(ticking(host))
    if (lists.isEmpty) return
    val fonts = orbitFonts()
    val look = orbitLook()
    var open by rememberSaveable { mutableStateOf(false) }
    OrbitLivePill(top, top?.let(lists::moreFor) ?: 0, fonts, look, onClick = { open = true }, modifier = modifier.fillMaxWidth(), stretch = true, height = 40.dp)
    if (open) {
        com.cosmotune.ui.sheets.SurfaceSheetSwitch(onDismissRequest = { open = false }) {
            LiveNowPanel(host, events, fonts, look, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), onPlayed = { open = false })
        }
    }
}

// ---- the panel --------------------------------------------------------------------------------------------------------------

/**
 * "Live now" itself (board section 1, the panel and the sheet): the heading with the count and how fresh the scores are, the filter
 * chips (All and the sports followed), the top live event as its colour card, the other live events as rows, "Coming up" with
 * Remind, what just finished, and where the scores come from. [onClose] adds a close button (the desktop's panel); [onPlayed] is
 * called after Watch is pressed (the panel or the sheet then goes). Not lazy: the panel, the sheet and the TV page scroll it.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val sports by host.settings.sports.collectAsState()
    val reminders by host.settings.reminders.collectAsState()
    var kind by rememberSaveable { mutableStateOf<LiveEventKind?>(null) }
    val lists = rememberLists(host, events, kind)
    val now = ticking(host)
    val play = host.play
    val uri = LocalUriHandler.current
    val watch: (LiveEvent) -> (() -> Unit)? = { e -> if (play == null) null else e.watchOptions.firstOrNull()?.let { o -> { play(e, o); onPlayed() } } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildAnnotatedString { append("Spotlight"); withStyle(SpanStyle(color = look.accent)) { append(".") } },
                    style = fonts.section.copy(fontSize = titleSize.value.sp, lineHeight = (titleSize.value + 2).sp), color = look.ink,
                )
                val count = listOfNotNull(
                    "${lists.live.size} live",
                    lists.upcoming.size.takeIf { it > 0 }?.let { "$it coming up" },
                    updatedText(events.maxOfOrNull { it.updatedAtMs } ?: 0, now),
                ).joinToString(" · ")
                OrbitLabel(count, fonts, look.dim, Modifier.padding(top = 6.dp))
            }
            if (onClose != null) WatchRoundButton(CosmoIcons.Close, "Close", look, onClose)
        }
        OrbitChipRow(0.dp, Modifier.padding(top = 4.dp, bottom = 4.dp)) {
            OrbitSelectChip("All", kind == null, fonts, look, onClick = { kind = null }, haptic = false)
            LiveEventKind.entries.filter { k -> k in sports || events.any { it.kind == k && it.link != null } }.forEach { k ->
                OrbitSelectChip(k.label(), kind == k, fonts, look, onClick = { kind = k.takeIf { kind != k } }, haptic = false)
            }
        }
        val top = lists.top
        if (top == null && lists.isEmpty) {
            WatchMessage("Nothing live", "Nothing on right now", "Matches and live shows you follow come here as they start. Choose sports and teams in Settings.", fonts, look,
                Modifier.fillMaxWidth())
        } else if (top != null && (top.kind != LiveEventKind.SHOW || lists.featured.isEmpty())) {
            OrbitLiveCard(top, fonts, look, onWatch = watch(top))
            lists.live.drop(1).forEach { e -> OrbitLiveRow(e, fonts, look, onWatch = watch(e)) }
        }
        if (lists.featured.isNotEmpty()) {
            OrbitLabel("In the spotlight", fonts, look.dim, Modifier.padding(start = 6.dp, top = 10.dp))
            lists.featured.forEach { e ->
                OrbitLiveRow(e, fonts, look, onWatch = watch(e) ?: e.link?.let { link -> { runCatching { uri.openUri(link) } } })
            }
            if (top?.kind == LiveEventKind.SHOW) {
                OrbitLabel("Live shows", fonts, look.dim, Modifier.padding(start = 6.dp, top = 10.dp))
                lists.live.forEach { e -> OrbitLiveRow(e, fonts, look, onWatch = watch(e)) }
            }
        }
        if (lists.upcoming.isNotEmpty()) {
            OrbitLabel("Coming up", fonts, look.dim, Modifier.padding(start = 6.dp, top = 10.dp))
            lists.upcoming.forEach { e ->
                OrbitLiveComingRow(e, now, reminders.any { it.id == e.id }, onRemind = if (e.startMs != null) ({ host.settings.toggleReminder(e) }) else null, fonts, look)
            }
        }
        if (lists.finished.isNotEmpty()) {
            OrbitLabel("Just finished", fonts, look.dim, Modifier.padding(start = 6.dp, top = 10.dp))
            lists.finished.take(3).forEach { e -> OrbitLiveRow(e, fonts, look, onWatch = null) }
        }
        val sources = events.filter { it.link == null && it.kind != LiveEventKind.SHOW }.map { it.source }.filter { it.isNotBlank() }.distinct()
        if (sources.isNotEmpty()) {
            OrbitLabel("Scores from " + sources.joinToString(" · "), fonts, look.dim.copy(alpha = 0.8f), Modifier.padding(start = 6.dp, top = 8.dp))
        }
    }
}

/**
 * "Live now" as a page of its own (the TV's): the panel at the page's width, scrolling. Watch takes the person to Watch's player
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

// ---- reminders --------------------------------------------------------------------------------------------------------------

/**
 * Watches the reminders set on "Coming up" rows: five minutes before a start the platform is told ([LiveNowHost.notify], the
 * desktop's tray) or, where it cannot be, a toast comes up at the foot of the window for ten seconds. Only while the app runs.
 */
@Composable
fun LiveNowReminders(host: LiveNowHost) {
    var due by remember { mutableStateOf<LiveReminder?>(null) }
    LaunchedEffect(host) {
        while (true) {
            host.settings.takeDue(host.clock()).forEach { r ->
                val text = "Starts ${startText(r.startMs, host.clock()).lowercase().replace("starting", "now")}"
                val told = runCatching { host.notify?.invoke(r.title, text) == true }.getOrDefault(false)
                if (!told) due = r
            }
            delay(30_000)
        }
    }
    val shown = due ?: return
    LaunchedEffect(shown) { delay(10_000); if (due == shown) due = null }
    val look = orbitLook()
    Popup(alignment = Alignment.BottomCenter, offset = IntOffset(0, -120), onDismissRequest = { due = null }) {
        OrbitToastPill("${shown.title} starts ${startText(shown.startMs, host.clock()).lowercase().replace("starting", "now")}", orbitFonts(), look,
            Modifier.padding(horizontal = 16.dp), actionIcon = CosmoIcons.Close, actionLabel = "Dismiss", onAction = { due = null })
    }
}

// ---- Settings ---------------------------------------------------------------------------------------------------------------

/**
 * Settings > Live now: the pill on or off, the sports followed, and the teams followed as chips (the ones chosen first, then
 * suggestions; a name can be added). Kept on this device ([LiveNowSettings]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveNowSettingsSection(settings: LiveNowSettings = rememberLiveNowSettings(), modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val on by settings.pillOn.collectAsState()
    val sports by settings.sports.collectAsState()
    val teams by settings.teams.collectAsState()
    var typed by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Spotlight", style = fonts.title, color = look.ink)
                Text("Live scores, major headlines and trending episodes and videos in the top bar.",
                    style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = look.dim)
            }
            OrbitSwitch(on, settings::setPillOn, look, "Spotlight")
        }
        OrbitLabel("Live events", fonts, look.dim, Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveNowSettings.followedKinds.forEach { k ->
                OrbitSelectChip(k.label(), k in sports, fonts, look, onClick = { settings.setSport(k, k !in sports) }, icon = if (k in sports) CosmoIcons.Check else null, haptic = false)
            }
        }
        OrbitLabel(if (teams.isEmpty()) "Teams · every team until you choose" else "Teams you follow", fonts, look.dim, Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shown = teams + SUGGESTED_TEAMS.filter { s -> teams.none { it.equals(s, ignoreCase = true) } }
            shown.forEach { t ->
                val followed = teams.any { it.equals(t, ignoreCase = true) }
                OrbitSelectChip(t, followed, fonts, look, onClick = { settings.setTeam(t, !followed) }, icon = if (followed) CosmoIcons.Check else null, haptic = false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrbitTextField(typed, { typed = it }, "Add a team", fonts, look, Modifier.weight(1f))
            WatchCardChip("Add", look.ink, fonts, onClick = { settings.setTeam(typed, true); typed = "" }, icon = CosmoIcons.Plus, enabled = typed.isNotBlank())
        }
        Spacer(Modifier.height(2.dp))
    }
}
