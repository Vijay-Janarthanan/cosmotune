package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.ui.player.rememberRemoteArtworkUrl
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRowTime
import com.cosmotune.ui.orbit.OrbitSectionHeader
import com.cosmotune.ui.orbit.OrbitSongRow

/**
 * Related: where this song leads. Its artists (each opens their page), its album, where it is playing from, and the songs AutoPlay
 * has lined up after it (a tap plays one), with AutoPlay's switch. Built from what the player already knows; nothing is fetched.
 */
@Composable
internal fun NowPlayingRelated(
    song: Song,
    queue: List<Song>,
    queueIndex: Int,
    sourceLabel: String,
    autoplayEnabled: Boolean,
    controlsLocked: Boolean,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenOrigin: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onToggleAutoplay: () -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    header: Boolean = true,
) {
    val artists = remember(song) {
        song.artists.filter { it.name.isNotBlank() }.map { it.browseId to it.name }
            .ifEmpty { listOf(song.artistId to song.artist).filter { it.second.isNotBlank() } }
    }
    val similar = remember(queue, queueIndex) {
        queue.withIndex().drop(queueIndex + 1).filter { it.value.queueTier == QueueTier.AUTOPLAY }.take(SIMILAR_SHOWN)
    }
    Column(modifier) {
        if (header) {
            OrbitSectionHeader("Related", "Where this song leads", fonts, look, Modifier)
            Spacer(Modifier.height(14.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(artists, key = { "a:" + it.second }) { (id, name) ->
                OrbitSongRow(name, "Artist", null, fonts, look, onClick = { onOpenArtist(id, name) }, icon = CosmoIcons.User, height = 64.dp, modifier = Modifier.fillMaxWidth())
            }
            val album = song.albumId
            if (album != null) item(key = "album") {
                OrbitSongRow(song.albumName ?: "The album", "Album", song.thumbnailUrl, fonts, look, onClick = { onOpenAlbum(album) }, height = 64.dp, modifier = Modifier.fillMaxWidth())
            }
            item(key = "source") {
                OrbitSongRow(sourceLabel, "Playing from", null, fonts, look, onClick = onOpenOrigin, icon = CosmoIcons.List, height = 64.dp, modifier = Modifier.fillMaxWidth())
            }
            item(key = "autoplay") {
                OrbitSongRow(
                    if (autoplayEnabled) "AutoPlay is on" else "AutoPlay is off",
                    if (autoplayEnabled) "Similar songs keep playing after the queue" else "Turn it on to keep the music going",
                    null, fonts, look, onClick = onToggleAutoplay.takeUnless { controlsLocked }, icon = CosmoIcons.Radio, height = 64.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (similar.isNotEmpty()) {
                item(key = "h:similar") {
                    Text("SIMILAR, UP NEXT", style = fonts.label, color = look.dim, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                }
                items(similar, key = { "s:" + it.index + ":" + (it.value.queueEntryId ?: it.value.videoId) }) { (index, next) ->
                    OrbitSongRow(
                        next.title, next.artist, rememberRemoteArtworkUrl(next), fonts, look,
                        onClick = { onJumpTo(index) }.takeUnless { controlsLocked }, height = 64.dp, modifier = Modifier.fillMaxWidth(),
                    ) { OrbitRowTime(next.durationText, fonts, look) }
                }
            }
        }
    }
}

private const val SIMILAR_SHOWN = 12
