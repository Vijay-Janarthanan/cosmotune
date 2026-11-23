package com.cosmotune.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchMessage
import com.cosmotune.ui.orbit.WatchRoundButton
import com.cosmotune.ui.orbit.WatchRowFrame
import com.cosmotune.ui.orbit.WatchSkeleton
import com.cosmotune.watch.torrent.TorrentFile
import com.cosmotune.watch.torrent.TorrentItems
import com.cosmotune.watch.torrent.TorrentListing

/** .torrent files picked on this device, waiting for their page (a route holds only the short key). */
internal object WatchTorrentInbox {
    private val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    fun put(bytes: ByteArray): String = java.util.UUID.randomUUID().toString().also { files[it] = bytes }
    fun get(key: String?): ByteArray? = key?.let(files::get)
}

/** Plays [file] of [listing] from the torrent; the rest of its videos follow in name order. */
internal fun playTorrentFile(listing: TorrentListing, file: TorrentFile) {
    WatchNav.open(WatchRoute.Play(TorrentItems.target(listing, file), TorrentItems.summary(listing, file), TorrentItems.stream(listing, file)))
}

/**
 * A torrent the listener opened (a magnet link or info hash pasted into Search, or a .torrent file): its name and size, Play all
 * (the videos straight through in name order), Download, and every file as a row, videos with Play and a tick for downloading,
 * the other files with their tick only. Nothing is searched for: the torrent is only what the listener gave.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchTorrentPage(route: WatchRoute.Torrent, actions: WatchActions, fonts: OrbitFonts, look: OrbitLook, frame: WatchFrame) {
    val repository = actions.repository
    val result by produceState<Result<TorrentListing>?>(null, route) {
        value = runCatching {
            repository.openTorrent(route.text, WatchTorrentInbox.get(route.fileKey)) ?: error("That is not a magnet link, an info hash or a .torrent file.")
        }
    }
    val listing = result?.getOrNull()
    var chosen by remember(listing) { mutableStateOf(listing?.videos?.map { it.index }?.toSet().orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("watch:torrent"), contentPadding = frame.padding) {
        frame.header?.let { header -> item(key = "torrent:header") { header() } }
        item(key = "torrent:heading") {
            OrbitPageHeading(listing?.name ?: "Torrent", listOfNotNull("Torrent", listing?.let { "${it.files.size} files" }, listing?.files?.sumOf { it.length }?.let(::sizeText)).joinToString(" · "),
                fonts, look, Modifier.padding(horizontal = frame.side + 6.dp).padding(top = if (frame.wide) 24.dp else 18.dp), maxLines = 2)
        }
        when {
            result == null -> items(4) { WatchSkeleton(look, Modifier.padding(horizontal = frame.side).padding(top = 8.dp).fillMaxWidth().height(62.dp), radius = 16.dp) }
            listing == null -> item(key = "torrent:failed") {
                WatchMessage("Torrent", "This torrent did not open", result?.exceptionOrNull()?.message ?: "The torrent engine could not read it.", fonts, look,
                    Modifier.padding(horizontal = frame.side).padding(top = 16.dp), action = "Open Plugins", onAction = { WatchNav.go(WatchTab.PLUGINS) })
            }
            else -> {
                item(key = "torrent:actions") {
                    Column(Modifier.padding(horizontal = frame.side + 6.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listing.videos.firstOrNull()?.let { first ->
                                WatchCardChip(if (listing.videos.size > 1) "Play all · ${listing.videos.size} videos" else "Play", look.ink, fonts,
                                    onClick = { playTorrentFile(listing, first) }, solid = true, onSolid = look.ground, icon = CosmoIcons.Play)
                            }
                            val downloads = actions.downloads
                            if (downloads != null && downloads.canTorrent) WatchCardChip("Download ${chosen.size} of ${listing.files.size}", look.ink, fonts, enabled = chosen.isNotEmpty(),
                                icon = CosmoIcons.Download, onClick = {
                                    message = if (downloads.startTorrent(listing, chosen.toList()) != null) "Downloading. Follow it in Downloads." else "This torrent could not be downloaded here."
                                })
                        }
                        Text(message ?: "Only download and share what you have the right to. Peers can see your address while a torrent runs.",
                            style = fonts.body, color = look.dim, modifier = Modifier.widthIn(max = 640.dp))
                    }
                }
                items(listing.files.sortedWith(compareBy(com.cosmotune.watch.torrent.NaturalOrder) { it.path }), key = { "torrent:file:${it.index}" }) { file ->
                    TorrentFileRow(file, file.index in chosen, fonts, look, Modifier.padding(horizontal = frame.side).padding(top = 8.dp),
                        onChoose = { chosen = if (file.index in chosen) chosen - file.index else chosen + file.index },
                        onPlay = if (file.video) ({ playTorrentFile(listing, file) }) else null)
                }
            }
        }
        item(key = "torrent:end") { androidx.compose.foundation.layout.Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun TorrentFileRow(file: TorrentFile, chosen: Boolean, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier, onChoose: () -> Unit, onPlay: (() -> Unit)?) {
    WatchRowFrame(look, modifier, onClick = onPlay ?: onChoose) {
        WatchRoundButton(CosmoIcons.Check, if (chosen) "Leave ${file.name} out of the download" else "Download ${file.name}", look, onClick = onChoose, on = chosen, size = 34.dp)
        Column(Modifier.weight(1f)) {
            Text(file.name, style = fonts.title.copy(fontSize = 14.sp), color = if (file.video) look.ink else look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(file.path.substringBeforeLast('/', "").takeIf { it.isNotBlank() }, sizeText(file.length)).joinToString(" · "),
                style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onPlay != null) WatchRoundButton(CosmoIcons.Play, "Play ${file.name}", look, onClick = onPlay)
    }
}
