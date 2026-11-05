package com.cosmotune.ui.listening

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitActionRow
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.kit.KitEmptyState
import com.cosmotune.ui.kit.KitMediaRow
import com.cosmotune.ui.kit.KitSkeletonRows
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Music's third tab (DESIGN_SYSTEM.md 5.2, 6.1): what the followed artists have put out, from the same release inbox and the same
 * checks as the listening tools ([ListeningStore], [refreshDueReleases]), so what one marks read the other shows read, and the saved
 * inbox is there offline. A vertical list grouped by week, "Hide singles", a check, and a way to the followed artists ([onManage],
 * which opens the tools on their New releases page). [onOpenRelease] opens a release's page with its id and title.
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
    var store by remember(file) { mutableStateOf<ListeningStore?>(null) }
    LaunchedEffect(file, refreshKey) {
        store = withContext(Dispatchers.IO) { runCatching { ListeningStore(file) }.getOrNull() }
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

    LazyColumn(state = listState, modifier = modifier.fillMaxSize().testTag("music:releases"), contentPadding = contentPadding) {
        if (showTitle) item(key = "title") { CosmoPageHeader(title = stringResource(Res.string.cosmo_releases)) }
        if (state == null) {
            item(key = "loading") { KitSkeletonRows() }
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
                )
            }
        }
        if (failures > 0) item(key = "failed") { ReleaseFailure(failures) }
        if (weeks.isEmpty()) {
            item(key = "empty") {
                if (state.artists.isEmpty()) {
                    KitEmptyState(
                        title = stringResource(Res.string.cosmo_releases_empty_title),
                        message = stringResource(Res.string.cosmo_releases_empty_message),
                        icon = CosmoIcons.Bell,
                        actionLabel = stringResource(Res.string.cosmo_releases_follow),
                        onAction = onManage,
                    )
                } else {
                    KitEmptyState(
                        title = stringResource(Res.string.cosmo_releases_none_title),
                        message = stringResource(Res.string.cosmo_releases_none_message),
                        icon = CosmoIcons.Bell,
                    )
                }
            }
        }
        for ((week, releases) in weeks) {
            item(key = "week:$week") {
                CosmoOverline(
                    when (week) {
                        ReleaseWeek.THIS_WEEK -> thisWeek
                        ReleaseWeek.LAST_WEEK -> lastWeek
                        ReleaseWeek.EARLIER -> earlier
                    },
                )
            }
            items(releases, key = { "release:${it.artistId}:${it.id}" }) { release ->
                ReleaseRow(release, kind = when (release.single) { true -> single; false -> album; null -> null }) {
                    val loaded = store
                    scope.launch {
                        // Read first, so the row is read when the person comes back; a failed write does not stop the page opening.
                        if (loaded != null) withContext(Dispatchers.IO) { runCatching { loaded.markRead(release.id) } }
                        onOpenRelease(release.id, release.title)
                    }
                }
            }
        }
    }
}

/** "Hide singles" (only when there is one to hide), the check and the way to the followed artists, in one scrolling row. */
@Composable
private fun ReleaseControls(
    hasSingles: Boolean,
    hideSingles: Boolean,
    onHideSingles: () -> Unit,
    checking: Boolean,
    onCheck: () -> Unit,
    onManage: () -> Unit,
) {
    KitActionRow(Modifier.padding(top = 12.dp)) {
        if (hasSingles) KitChip(stringResource(Res.string.cosmo_releases_hide_singles), hideSingles, onHideSingles, Modifier.testTag("music:releases:hide-singles"))
        KitButton(
            text = stringResource(if (checking) Res.string.cosmo_releases_checking else Res.string.cosmo_releases_check),
            onClick = onCheck,
            modifier = Modifier.testTag("music:releases:check"),
            kind = KitButtonKind.TONAL,
            enabled = !checking,
            dense = true,
        )
        KitButton(stringResource(Res.string.cosmo_releases_manage), onManage, Modifier.testTag("music:releases:manage"), kind = KitButtonKind.TONAL, dense = true)
    }
}

@Composable
private fun ReleaseFailure(failures: Int) {
    val palette = LocalCosmoColors.current.palette
    Text(
        stringResource(Res.string.cosmo_releases_failed, failures),
        style = LocalCosmoType.current.meta,
        color = Color(palette.danger),
        modifier = Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 8.dp),
    )
}

/** A release: a 64 dp square cover, its title, "Album · Artist", and the accent dot while it is unread. */
@Composable
private fun ReleaseRow(release: ReleaseNotice, kind: String?, onClick: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    Row(Modifier.fillMaxWidth().testTag("music:releases:row:${release.id}"), verticalAlignment = Alignment.CenterVertically) {
        KitMediaRow(
            title = release.title,
            subtitle = listOfNotNull(kind, release.artist.takeIf { it.isNotBlank() }).joinToString(" · "),
            onClick = onClick,
            modifier = Modifier.weight(1f),
            artUrl = release.artwork,
            artHeight = 64.dp,
        )
        if (!release.read) {
            Box(Modifier.padding(end = CosmoSpacing.margin).size(8.dp).clip(CircleShape).background(Color(palette.accent)))
        }
    }
}
