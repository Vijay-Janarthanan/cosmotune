package com.cosmotune.ui.listening

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSongRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.ListeningNetwork
import com.cosmotune.listening.ListeningStore
import com.cosmotune.listening.ReleaseNotice
import com.cosmotune.listening.ReleaseWeek
import com.cosmotune.listening.WatchedArtist
import com.cosmotune.listening.releaseWeeks
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_releases
import com.cosmotune.music.sharedui.resources.cosmo_releases_check
import com.cosmotune.music.sharedui.resources.cosmo_releases_checking
import com.cosmotune.music.sharedui.resources.cosmo_releases_earlier
import com.cosmotune.music.sharedui.resources.cosmo_releases_empty_message
import com.cosmotune.music.sharedui.resources.cosmo_releases_empty_title
import com.cosmotune.music.sharedui.resources.cosmo_releases_failed
import com.cosmotune.music.sharedui.resources.cosmo_releases_follow
import com.cosmotune.music.sharedui.resources.cosmo_releases_hide_singles
import com.cosmotune.music.sharedui.resources.cosmo_releases_last_week
import com.cosmotune.music.sharedui.resources.cosmo_releases_manage
import com.cosmotune.music.sharedui.resources.cosmo_releases_none_message
import com.cosmotune.music.sharedui.resources.cosmo_releases_none_title
import com.cosmotune.music.sharedui.resources.cosmo_releases_this_week
import com.cosmotune.music.sharedui.resources.cosmo_type_album
import com.cosmotune.music.sharedui.resources.cosmo_type_single
import com.cosmotune.music.ui.components.songListSkeleton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitRecord
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitStatCard
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalCosmoColors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Music's third tab in Orbit (the Music board's 6): what the followed artists have put out, as flat list rows ("Album · Artist · Fri")
 * under mono week labels, from the same release inbox and the same checks as the listening tools ([ListeningStore],
 * [refreshDueReleases]), so what one marks read the other shows read, and the saved inbox is there offline. The heading "New releases."
 * with a mono count and the check as a round button at its end, then one row of chips with counts: All, New, Albums, Singles and EPs,
 * and the followed artists ([onManage], which opens the tools on their New releases page). [onOpenRelease] opens a release's page
 * with its id and title. An unread release wears a peach dot on its disc's edge; the Releases tab carries their count
 * ([rememberUnreadReleases]).
 *
 * [refreshKey] reads the file again when it changes: the tools hold a store of their own and have written to the file (a follow, a
 * check). [lookup] is the catalogue's release list ([ListeningNetwork.releases]); tests give their own.
 */
@Composable
fun CosmoReleasesPage(
    file: File,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onOpenRelease: (String, String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    refreshKey: Any? = null,
    lookup: suspend (WatchedArtist) -> List<ReleaseNotice> = ListeningNetwork::releases,
    now: () -> Long = System::currentTimeMillis,
) {
    // The store this page read last (for this file) is there in the first frame, so coming back to Releases shows the inbox at
    // once; the file is read again behind it.
    var store by remember(file) { mutableStateOf(ReleasesMemory.last(file)) }
    LaunchedEffect(file, refreshKey) {
        val read = withContext(Dispatchers.IO) { runCatching { ListeningStore(file) }.getOrNull() }
        if (read != null || store == null) store = read
        read?.let { ReleasesMemory.keep(file, it) }
    }
    val state = store?.state?.collectAsState()?.value
    val scope = rememberCoroutineScope()
    var filterName by rememberSaveable { mutableStateOf(ReleaseFilter.ALL.name) }
    val filter = ReleaseFilter.entries.firstOrNull { it.name == filterName } ?: ReleaseFilter.ALL
    var checking by remember { mutableStateOf(false) }
    var failures by remember { mutableIntStateOf(0) }
    val inbox = state?.inbox.orEmpty()
    val weeks = remember(inbox, filter) { releaseWeeks(inbox.filter(filter::keeps), now()) }
    val thisWeek = stringResource(Res.string.cosmo_releases_this_week)
    val lastWeek = stringResource(Res.string.cosmo_releases_last_week)
    val earlier = stringResource(Res.string.cosmo_releases_earlier)
    val single = stringResource(Res.string.cosmo_type_single)
    val album = stringResource(Res.string.cosmo_type_album)
    val fonts = orbitFonts()
    val look = orbitLook()
    val open: (ReleaseNotice) -> Unit = { release ->
        val loaded = store
        scope.launch {
            // Read first, so the record is read when the person comes back; a failed write does not stop the page opening.
            if (loaded != null) withContext(Dispatchers.IO) { runCatching { loaded.markRead(release.id) } }
            onOpenRelease(release.id, release.title)
        }
    }

    val check: () -> Unit = {
        val loaded = store
        if (loaded != null && !checking) scope.launch {
            checking = true
            failures = 0
            try { failures = refreshDueReleases(loaded, lookup, force = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failures = loaded.state.value.artists.size.coerceAtLeast(1) }
            finally { checking = false }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = if (maxWidth >= 840.dp) 28.dp else 16.dp
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("music:releases"), contentPadding = contentPadding) {
            val followed = state?.artists?.size ?: 0
            if (showTitle) item(key = "title") {
                val unread = inbox.count { !it.read }
                val artists = "following $followed ${if (followed == 1) "artist" else "artists"}"
                OrbitPageHeading(
                    title = stringResource(Res.string.cosmo_releases),
                    label = when {
                        state == null -> "New music"
                        unread > 0 -> "$unread new · $artists"
                        else -> artists
                    },
                    fonts = fonts, look = look,
                    modifier = Modifier.padding(start = side + 4.dp, end = side, top = 6.dp),
                ) {
                    if (state != null && followed > 0) {
                        OrbitRoundButton(
                            CosmoIcons.Replay, stringResource(if (checking) Res.string.cosmo_releases_checking else Res.string.cosmo_releases_check), look.ink,
                            onClick = check, modifier = Modifier.testTag("music:releases:check"), enabled = !checking, onInk = look.ground, touch = 48.dp,
                        )
                    }
                }
            }
            if (state == null) {
                songListSkeleton(count = 9, keyPrefix = "skeleton:releases")
                return@LazyColumn
            }
            if (state.artists.isNotEmpty()) {
                item(key = "controls") {
                    ReleaseChips(inbox, filter, { filterName = it.name }, followed, onManage, fonts, look, side)
                }
            }
            if (failures > 0) item(key = "failed") { ReleaseFailure(failures, fonts, side) }
            if (weeks.isEmpty()) {
                item(key = "empty") {
                    val nobody = state.artists.isEmpty()
                    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = side, vertical = 12.dp)) {
                        OrbitStatCard(
                            label = "Releases",
                            title = stringResource(if (nobody) Res.string.cosmo_releases_empty_title else Res.string.cosmo_releases_none_title),
                            line = stringResource(if (nobody) Res.string.cosmo_releases_empty_message else Res.string.cosmo_releases_none_message),
                            fonts = fonts, look = look,
                        )
                        if (nobody) {
                            OrbitSelectChip(
                                stringResource(Res.string.cosmo_releases_follow), selected = true, fonts = fonts, look = look, onClick = onManage,
                                icon = CosmoIcons.Bell, modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
            for ((week, releases) in weeks) {
                item(key = "week:$week") {
                    OrbitLabel(
                        when (week) {
                            ReleaseWeek.THIS_WEEK -> thisWeek
                            ReleaseWeek.LAST_WEEK -> lastWeek
                            ReleaseWeek.EARLIER -> earlier
                        },
                        fonts, look.dim, Modifier.padding(start = side + 4.dp, end = side, top = 14.dp, bottom = 6.dp),
                    )
                }
                items(releases, key = { release -> "releases:${release.artistId}:${release.id}" }) { release ->
                    ReleaseRow(release, kind = when (release.single) { true -> single; false -> album; null -> null }, fonts, look, Modifier.padding(horizontal = side)) { open(release) }
                }
            }
        }
    }
}

/** The Releases page's chips: what the list shows. */
internal enum class ReleaseFilter(val label: String) {
    ALL("All"), NEW("New"), ALBUMS("Albums"), SINGLES("Singles and EPs");

    /** Whether [release] is in this filter's list (a release the catalogue did not file stays under All and New only). */
    fun keeps(release: ReleaseNotice): Boolean = when (this) {
        ALL -> true
        NEW -> !release.read
        ALBUMS -> release.single == false
        SINGLES -> release.single == true
    }
}

/**
 * One row of chips with their counts (the Music board's 6): All, New, Albums, Singles and EPs (the two kinds only when the inbox
 * knows them), and the followed artists with their count, which opens the tools' New releases page.
 */
@Composable
private fun ReleaseChips(
    inbox: List<ReleaseNotice>,
    filter: ReleaseFilter,
    onFilter: (ReleaseFilter) -> Unit,
    followed: Int,
    onManage: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    side: Dp,
) {
    val kinds = inbox.any { it.single != null }
    OrbitChipRow(side, Modifier.padding(top = 14.dp)) {
        ReleaseFilter.entries.filter { kinds || it == ReleaseFilter.ALL || it == ReleaseFilter.NEW }.forEach { entry ->
            OrbitSelectChip(
                entry.label, selected = entry == filter, fonts = fonts, look = look, onClick = { onFilter(entry) }, haptic = entry != filter,
                count = inbox.count(entry::keeps), modifier = Modifier.testTag("music:releases:filter:${entry.name.lowercase()}"),
            )
        }
        OrbitSelectChip(
            stringResource(Res.string.cosmo_releases_manage), false, fonts, look, onManage, Modifier.testTag("music:releases:manage"),
            icon = CosmoIcons.User, count = followed,
        )
    }
}

@Composable
private fun ReleaseFailure(failures: Int, fonts: OrbitFonts, side: Dp) {
    val palette = LocalCosmoColors.current.palette
    Text(
        stringResource(Res.string.cosmo_releases_failed, failures),
        style = fonts.body,
        color = Color(palette.danger),
        modifier = Modifier.fillMaxWidth().padding(horizontal = side + 6.dp, vertical = 8.dp),
    )
}

/**
 * A release as a flat list row: its cover as the disc, its title, and "Album · Artist · Fri" (the day it was found); unread, a peach
 * dot on the disc's edge.
 */
@Composable
private fun ReleaseRow(release: ReleaseNotice, kind: String?, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier, onClick: () -> Unit) {
    val day = remember(release.discoveredAt) {
        java.time.Instant.ofEpochMilli(release.discoveredAt).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.ENGLISH))
    }
    val disc = com.cosmotune.ui.orbit.orbitRowDisc()
    OrbitSongRow(
        title = release.title,
        subtitle = listOfNotNull(kind, release.artist.takeIf { it.isNotBlank() }, day).joinToString(" · "),
        cover = release.artwork, fonts = fonts, look = look, onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("music:releases:row:${release.id}"),
        discContent = {
            Box(Modifier.size(disc)) {
                com.cosmotune.ui.orbit.Disc(release.artwork, disc)
                if (!release.read) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(look.ground).padding(3.dp)
                            .clip(CircleShape).background(if (look.dark) com.cosmotune.ui.orbit.PEACH else Color(0xFFE0662C))
                            .semantics { contentDescription = "New" },
                    )
                }
            }
        },
    )
}

/** The store the Releases page read last, by file, for the process: its state is the inbox shown when the page comes back. */
internal object ReleasesMemory {
    private var file: String? = null
    private var store: ListeningStore? = null

    @Synchronized fun last(of: File): ListeningStore? = store.takeIf { file == of.absolutePath }

    @Synchronized fun keep(of: File, read: ListeningStore) { file = of.absolutePath; store = read }
}

/**
 * How many releases of [file]'s inbox are still unread: the Releases tab's count in the bottom bar and the rail. The store the page
 * read last is counted at once; the file is read again (off the main thread) whenever [refreshKey] changes, so a release opened on
 * the page, or a check, shows in the count when the person moves on.
 */
@Composable
fun rememberUnreadReleases(file: File, refreshKey: Any? = null): Int {
    var store by remember(file) { mutableStateOf(ReleasesMemory.last(file)) }
    LaunchedEffect(file, refreshKey) {
        val read = withContext(Dispatchers.IO) { runCatching { ListeningStore(file) }.getOrNull() } ?: return@LaunchedEffect
        store = read
        ReleasesMemory.keep(file, read)
    }
    val state = store?.state?.collectAsState()?.value ?: return 0
    return state.inbox.count { !it.read }
}
