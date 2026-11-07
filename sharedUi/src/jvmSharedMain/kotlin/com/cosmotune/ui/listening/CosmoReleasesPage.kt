package com.cosmotune.ui.listening

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
import com.cosmotune.ui.orbit.orbitRecordGrid
import com.cosmotune.ui.theme.LocalCosmoColors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Music's third tab in Orbit (Canvas repo, design/cosmotune/batch-a/board.html, section 1): what the followed artists have put out,
 * as records with the play moon on their edge, under mono week labels, from the same release inbox and the same checks as the
 * listening tools ([ListeningStore], [refreshDueReleases]), so what one marks read the other shows read, and the saved inbox is there
 * offline. The heading "Releases." with a mono count, then "Hide singles", a check and a way to the followed artists ([onManage],
 * which opens the tools on their New releases page) as chips. [onOpenRelease] opens a release's page with its id and title; the moon
 * opens it too (its page has Play). An unread release wears a small ink dot on its disc's edge.
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
    var hideSingles by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var failures by remember { mutableIntStateOf(0) }
    val inbox = state?.inbox.orEmpty()
    val weeks = remember(inbox, hideSingles) { releaseWeeks(inbox, now(), hideSingles) }
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

    BoxWithConstraints(modifier.fillMaxSize()) {
        val side = if (maxWidth >= 840.dp) 28.dp else 16.dp
        val (columns, cell) = orbitRecordGrid(maxWidth, side)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("music:releases"), contentPadding = contentPadding) {
            if (showTitle) item(key = "title") {
                val unread = inbox.count { !it.read }
                val followed = state?.artists?.size ?: 0
                val artists = "$followed ${if (followed == 1) "artist" else "artists"} followed"
                OrbitPageHeading(
                    title = stringResource(Res.string.cosmo_releases),
                    label = when {
                        state == null -> "New music"
                        unread > 0 -> "$unread new · $artists"
                        else -> artists
                    },
                    fonts = fonts, look = look,
                    modifier = Modifier.padding(start = side, end = side, top = 12.dp, bottom = 6.dp),
                )
            }
            if (state == null) {
                songListSkeleton(count = 6, keyPrefix = "skeleton:releases")
                return@LazyColumn
            }
            if (state.artists.isNotEmpty()) {
                item(key = "controls") {
                    ReleaseControls(
                        hasSingles = inbox.any { it.single == true },
                        hideSingles = hideSingles,
                        onHideSingles = { hideSingles = !hideSingles },
                        checking = checking,
                        onCheck = {
                            val loaded = store
                            if (loaded != null && !checking) scope.launch {
                                checking = true
                                failures = 0
                                try { failures = refreshDueReleases(loaded, lookup, force = true) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { failures = loaded.state.value.artists.size.coerceAtLeast(1) }
                                finally { checking = false }
                            }
                        },
                        onManage = onManage,
                        fonts = fonts, look = look, side = side,
                    )
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
                        fonts, look.dim, Modifier.padding(start = side + 6.dp, end = side, top = 22.dp, bottom = 12.dp),
                    )
                }
                items(releases.chunked(columns), key = { row -> "releases:${row.first().artistId}:${row.first().id}" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        row.forEach { release ->
                            ReleaseRecord(release, kind = when (release.single) { true -> single; false -> album; null -> null }, cell, fonts, look) { open(release) }
                        }
                    }
                }
            }
        }
    }
}

/** "Hide singles" (only when there is one to hide), the check and the way to the followed artists, as one row of chips. */
@Composable
private fun ReleaseControls(
    hasSingles: Boolean,
    hideSingles: Boolean,
    onHideSingles: () -> Unit,
    checking: Boolean,
    onCheck: () -> Unit,
    onManage: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    side: Dp,
) {
    OrbitChipRow(side, Modifier.padding(top = 12.dp)) {
        if (hasSingles) OrbitSelectChip(stringResource(Res.string.cosmo_releases_hide_singles), hideSingles, fonts, look, onHideSingles, Modifier.testTag("music:releases:hide-singles"))
        OrbitSelectChip(
            stringResource(if (checking) Res.string.cosmo_releases_checking else Res.string.cosmo_releases_check), false, fonts, look,
            onClick = { if (!checking) onCheck() }, modifier = Modifier.testTag("music:releases:check"), icon = CosmoIcons.Replay,
        )
        OrbitSelectChip(stringResource(Res.string.cosmo_releases_manage), false, fonts, look, onManage, Modifier.testTag("music:releases:manage"), icon = CosmoIcons.Bell)
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

/** A release as a record ([OrbitRecord]): its cover as a disc with the play moon, its title, "Album · Artist"; unread, a dot on the disc's edge. */
@Composable
private fun ReleaseRecord(release: ReleaseNotice, kind: String?, size: Dp, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit) {
    Box(Modifier.testTag("music:releases:row:${release.id}")) {
        OrbitRecord(
            title = release.title,
            subtitle = listOfNotNull(kind, release.artist.takeIf { it.isNotBlank() }).joinToString(" · "),
            cover = release.artwork,
            fonts = fonts,
            look = look,
            onClick = onClick,
            size = size,
        )
        if (!release.read) {
            Box(
                Modifier.padding(start = size * 0.1f, top = size * 0.1f).size(14.dp).clip(CircleShape).background(look.ground).padding(3.dp)
                    .clip(CircleShape).background(look.ink),
            )
        }
    }
}

/** The store the Releases page read last, by file, for the process: its state is the inbox shown when the page comes back. */
internal object ReleasesMemory {
    private var file: String? = null
    private var store: ListeningStore? = null

    @Synchronized fun last(of: File): ListeningStore? = store.takeIf { file == of.absolutePath }

    @Synchronized fun keep(of: File, read: ListeningStore) { file = of.absolutePath; store = read }
}
