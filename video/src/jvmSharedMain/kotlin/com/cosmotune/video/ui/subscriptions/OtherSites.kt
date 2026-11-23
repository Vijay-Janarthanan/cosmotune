package com.cosmotune.video.ui.subscriptions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.LocalBundle
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_other_add
import com.cosmotune.video.resources.video_other_bad
import com.cosmotune.video.resources.video_other_hint
import com.cosmotune.video.resources.video_other_note
import com.cosmotune.video.resources.video_other_title
import com.cosmotune.media.segments.TtlCache
import com.cosmotune.video.source.peertube.PeerTubeClient
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Channels on other video sites in the one feed: PeerTube's federated sites (any address). Their newest videos join the Subscriptions
 * feed by date, and play as files from their own site in the file player.
 */
internal object OtherSites {
    val client = PeerTubeClient()
    private val pages = TtlCache<String, List<VideoItem>>(15 * 60_000L, 128, System::currentTimeMillis)

    /** The newest videos of every channel in [channels] (their addresses), newest first; a site that does not answer gives none. */
    suspend fun videos(channels: Collection<String>): List<VideoItem> = coroutineScope {
        channels.map { url ->
            async {
                pages.get(url) ?: PeerTubeClient.parseChannel(url)?.let { ref -> client.videos(ref).also { if (it.isNotEmpty()) pages.put(url, it) } }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.id }.sortedByDescending { it.uploadedAtMs ?: 0L }
    }

    /** Plays [item] (a PeerTube video) in the file player; false when its site gave no file. */
    fun play(item: VideoItem, graph: VideoGraph, navigator: VideoNavigator, scope: CoroutineScope, onFailed: () -> Unit) {
        scope.launch {
            val url = client.fileUrl(item.id)
            if (url == null) {
                onFailed()
                return@launch
            }
            graph.playback.playLocal(item, LocalBundle(MediaInput.LocalFiles(muxedPath = url, videoPath = null, audioPath = null)))
            navigator.push(VideoRoute.LocalPlayer(item.id))
        }
    }
}

/** The newest videos of the followed channels on other sites, fetched while the page is open. */
@Composable
internal fun rememberOtherSiteVideos(channels: Map<String, String>): List<VideoItem> {
    val videos by produceState(emptyList(), channels.keys) { value = OtherSites.videos(channels.keys) }
    return videos
}

/** The feed's row that opens [OtherSitesSheet]: "Channels on other sites" and how many are followed. */
@Composable
internal fun OtherSitesRow(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier) {
        CosmoSettingsRow(
            icon = CosmoIcons.Explore,
            title = stringResource(Res.string.video_other_title),
            subtitle = if (count == 0) stringResource(Res.string.video_other_note) else count.toString(),
            onClick = onOpen,
        )
    }
}

/**
 * The channels followed on other sites (a tap on one stops following it) and the field to follow one more: a channel's address
 * ("https://site/c/name") or its handle ("name@site"). The site is asked for the channel's name before it is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OtherSitesSheet(graph: VideoGraph, channels: Map<String, String>, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var bad by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Text(stringResource(Res.string.video_other_title), style = fonts.title, color = look.ink, modifier = Modifier.padding(horizontal = CosmoSpacing.margin))
            Spacer(Modifier.height(12.dp))
            for ((url, name) in channels) {
                CosmoSettingsRow(
                    icon = CosmoIcons.Close,
                    title = name.ifBlank { url },
                    subtitle = url,
                    onClick = { graph.settings.update { it.copy(otherChannels = it.otherChannels - url) } },
                )
            }
            Column(Modifier.padding(horizontal = CosmoSpacing.margin)) {
                Spacer(Modifier.height(12.dp))
                OrbitInputField(
                    value = text,
                    onValueChange = { text = it; bad = false },
                    placeholder = stringResource(Res.string.video_other_hint),
                    fonts = fonts,
                    look = look,
                    modifier = Modifier.fillMaxWidth(),
                    error = bad,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(if (bad) Res.string.video_other_bad else Res.string.video_other_note), style = fonts.label, color = if (bad) look.accent else look.dim)
                Spacer(Modifier.height(16.dp))
                OrbitActionPill(
                    label = stringResource(Res.string.video_other_add),
                    enabled = !busy && text.isNotBlank(),
                    onClick = {
                        val ref = PeerTubeClient.parseChannel(text)
                        if (ref == null) {
                            bad = true
                        } else {
                            busy = true
                            scope.launch {
                                val name = OtherSites.client.channelName(ref)
                                busy = false
                                if (name == null) {
                                    bad = true
                                } else {
                                    graph.settings.update { it.copy(otherChannels = it.otherChannels + (ref.url to name)) }
                                    text = ""
                                }
                            }
                        }
                    },
                    fonts = fonts,
                    look = look,
                    primary = true,
                )
            }
        }
    }
}
