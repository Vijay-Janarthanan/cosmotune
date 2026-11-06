package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitModeSwitcher
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.watch.WatchRepository
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.Title
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Watch mode (Canvas repo, design/cosmotune/watch-orbit/board.html): films, series and live TV from the plugins that are on. The
 * page shown is the top of [WatchNav]'s stack (a film or series page, the player) or else the tab. On the desktop the sidebar
 * carries the tabs ([showNavigation] false); on the phone the page starts with the mode pill and the tabs as chips. Wide (840 dp
 * and up) lays cards side by side as the board does; narrower stacks them. [wideLayout], when set, decides that instead of the width (the TV,
 * which draws the pages larger, keeps the side-by-side layout).
 */
@Composable
fun WatchApp(contentPadding: PaddingValues = PaddingValues(0.dp), showNavigation: Boolean = true, modifier: Modifier = Modifier, wideLayout: Boolean? = null) {
    val tab by WatchNav.tab.collectAsState()
    val stack by WatchNav.stack.collectAsState()
    val pending by WatchNav.pendingInstall.collectAsState()
    val opened by com.cosmotune.watch.WatchLinks.pending.collectAsState()
    val fonts = orbitFonts()
    val look = orbitLook()
    val repository = watchRepository()
    val scope = rememberCoroutineScope()
    val room = com.cosmotune.ui.room.LocalRoomLauncher.current
    val downloads = LocalWatchDownloads.current
    val actions = remember(repository, scope, room, downloads) { WatchActions(repository, scope, room, downloads) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = wideLayout ?: (maxWidth >= 840.dp)
        val top = stack.lastOrNull()
        val frame = WatchFrame(
            wide = wide,
            side = if (wide) 16.dp else 16.dp,
            padding = contentPadding,
            header = when {
                top != null -> ({ WatchBackHeader(if (top is WatchRoute.Page && top.summary.type == TitleType.SERIES) "Series" else "Film", fonts, look) })
                showNavigation -> ({ WatchPhoneHeader(tab, fonts, look) })
                else -> null
            },
        )
        when (top) {
            is WatchRoute.Play -> WatchPlayerPage(top, actions, fonts, look, frame)
            is WatchRoute.Page -> WatchTitlePage(top.summary, actions, fonts, look, frame)
            null -> when (tab) {
                WatchTab.HOME -> WatchHomePage(null, actions, fonts, look, frame)
                WatchTab.FILMS -> WatchHomePage(com.cosmotune.watch.HomeFilter.FILMS, actions, fonts, look, frame)
                WatchTab.SERIES -> WatchHomePage(com.cosmotune.watch.HomeFilter.SERIES, actions, fonts, look, frame)
                WatchTab.LIVE -> WatchLivePage(actions, fonts, look, frame)
                WatchTab.SEARCH -> WatchSearchPage(actions, fonts, look, frame)
                WatchTab.MY_LIST -> WatchMyListPage(actions, fonts, look, frame)
                WatchTab.DOWNLOADS -> WatchDownloadsPage(actions, fonts, look, frame)
                WatchTab.PLUGINS -> WatchPluginsPage(fonts, look, frame)
            }
        }
        pending?.let { link -> WatchInstallSheet(link, fonts, look, onDone = { WatchNav.pendingInstall.value = null }) }
        // A link opened from outside (an intent, the system's URL handler, a second launch): its Install or pack sheet.
        when (val link = opened) {
            is com.cosmotune.watch.WatchLinks.Link.Install -> WatchInstallSheet(link.raw, fonts, look, onDone = { com.cosmotune.watch.WatchLinks.handled() })
            is com.cosmotune.watch.WatchLinks.Link.Pack -> ImportPackDialog(repository, fonts, look, initialLink = link.raw) { com.cosmotune.watch.WatchLinks.handled() }
            null -> Unit
        }
    }
}

/** How a page is laid out: wide or stacked, the side gutter, the host's padding and the phone's header (null on the desktop). */
internal class WatchFrame(val wide: Boolean, val side: Dp, val padding: PaddingValues, val header: (@Composable () -> Unit)?)

/**
 * What every page does with a title, an episode or a channel: open, play, keep, download, and play for the room ("Watch together")
 * through the host's [com.cosmotune.ui.room.RoomLauncher], each member's app then finding its own copy.
 */
internal class WatchActions(
    val repository: WatchRepository,
    private val scope: CoroutineScope,
    private val room: com.cosmotune.ui.room.RoomLauncher? = null,
    val downloads: com.cosmotune.watch.WatchDownloads? = null,
) {
    fun open(summary: TitleSummary) = WatchNav.openTitle(summary)

    /** Play: a film at once (resumed), a series at its next episode. */
    fun play(summary: TitleSummary) {
        if (summary.type == TitleType.FILM) {
            WatchNav.open(WatchRoute.Play(repository.targetOf(summary), summary))
        } else scope.launch {
            val next = repository.nextUp(summary.key)
            if (next == null) WatchNav.openTitle(summary) else playEpisode(summary, next.episode)
        }
    }

    fun playEpisode(series: TitleSummary, episode: Episode) {
        val target = PlayTarget.EpisodeOf(episode.key, series.ids, series.name, episode.season, episode.number, episode.ids, episode.name, series.year)
        WatchNav.open(WatchRoute.Play(target, series, episode = episode))
    }

    fun playEpisode(series: Title, episode: Episode) =
        WatchNav.open(WatchRoute.Play(repository.targetOf(series, episode), series.summary, episode = episode))

    fun playStream(summary: TitleSummary, target: PlayTarget, stream: Stream) = WatchNav.open(WatchRoute.Play(target, summary, stream))

    fun playChannel(channel: com.cosmotune.watch.model.Channel) = WatchNav.open(WatchRoute.Play(repository.targetOf(channel), channel.asSummary()))

    fun setInMyList(summary: TitleSummary, on: Boolean) { scope.launch { repository.setInMyList(summary, on) } }

    fun favourite(channel: com.cosmotune.watch.model.Channel, on: Boolean) { scope.launch { repository.setFavouriteChannel(channel.key, on) } }

    fun launch(block: suspend CoroutineScope.() -> Unit) { scope.launch(block = block) }

    /**
     * "Watch together" works where the host offers rooms: outside a room it asks to make or join one first, inside it plays for
     * everyone, and it is drawn off only in a room whose host keeps the controls to themselves.
     */
    val watchTogetherReady: Boolean get() = room != null && (!room.inRoom || room.canControl)

    /** Plays [target] for the room: the room item carries its key, ids and this device's release (never a server or a token). */
    fun watchTogether(target: PlayTarget, summary: TitleSummary?, stream: Stream? = null, durationMs: Long? = null) {
        val launcher = room ?: return
        launcher.playInRoom(com.cosmotune.ui.room.roomWatchItem(target, summary, stream, durationMs))
    }

    /** "Watch together" on a film's or a series' page: the film with its best copy, a series at its next (or first) episode. */
    fun watchTogether(summary: TitleSummary, page: Title?, best: Stream?) {
        if (summary.type == TitleType.FILM) { watchTogether(repository.targetOf(summary), summary, best); return }
        scope.launch {
            val episode = runCatching { repository.nextUp(summary.key)?.episode }.getOrNull()
                ?: runCatching {
                    val season = page?.seasons?.map { it.number }?.filter { it > 0 }?.minOrNull() ?: 1
                    repository.episodes(summary.key, season).firstOrNull()
                }.getOrNull()
                ?: return@launch
            val target = page?.let { repository.targetOf(it, episode) }
                ?: PlayTarget.EpisodeOf(episode.key, summary.ids, summary.name, episode.season, episode.number, episode.ids, episode.name, summary.year)
            watchTogether(target, summary)
        }
    }
}

/** The top wash of a page (board `.wash`): its colour strong at the top, gone by three quarters down the window. */
@Composable
internal fun WatchWash(color: Color, look: OrbitLook, modifier: Modifier = Modifier) =
    com.cosmotune.ui.orbit.OrbitPageWash(color, modifier, height = 620.dp, look = look, darkAlpha = 0.55f, lightAlpha = 0.22f, tail = 0.33f)

/** The phone's head of every tab: the mode pill (and the account button when the host gives one), then the tabs as chips. */
@Composable
private fun WatchPhoneHeader(tab: WatchTab, fonts: OrbitFonts, look: OrbitLook) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            KitModeSwitcher()
            Spacer(Modifier.weight(1f))
            LocalModeProfileAction.current?.invoke()
        }
        OrbitChipRow(16.dp, Modifier.padding(top = 12.dp)) {
            WatchTab.entries.forEach { t ->
                OrbitSelectChip(t.label, t == tab, fonts, look, onClick = { WatchNav.go(t) }, haptic = false,
                    icon = if (t == WatchTab.PLUGINS) CosmoIcons.Settings else null)
            }
        }
    }
}

/** The head of a page opened over a tab (board: the round back button and the mono kind). */
@Composable
private fun WatchBackHeader(kind: String, fonts: OrbitFonts, look: OrbitLook) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WatchRoundButton(CosmoIcons.ChevronLeft, "Back", look, onClick = { WatchNav.back() })
        OrbitLabel(kind, fonts, look.dim)
    }
}
