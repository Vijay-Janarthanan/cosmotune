package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cosmotune.music.data.canvas.CanvasVideoPicks
import com.cosmotune.music.data.canvas.OpenCanvasProvider
import com.cosmotune.music.data.canvas.VideoChoice
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.durationMillis
import com.cosmotune.music.ui.player.PlayerDrawer
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.clock
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState

/**
 * "Choose the video" (the Now Playing board, section E), opened from More: the videos found for the song as flat 60 dp rows (the
 * 16:9 still with its length, the title, "channel · length"), the one playing ([current]) in peach with a check, and "No video"
 * last under a hairline. A pick is the manual-video path of the lookup ([CanvasVideoPicks]: the lookup then measures that video
 * alone, with `videoId`), kept for the song; the host's remembered answer is dropped first, so the song is looked up again.
 */
@Composable
internal fun ChooseVideoSheet(song: Song, current: String?, hazeState: HazeState, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val choices by produceState<List<VideoChoice>?>(null, song.title, song.artist) {
        value = OpenCanvasProvider.videoChoices(song.title, song.artist, song.albumName, song.durationMillis() / 1_000L)
    }
    val picked = CanvasVideoPicks.pick(song.title, song.artist)
    val choose: (String?) -> Unit = { videoId ->
        PlayerPlatform.host.forgetCanvas(song)
        CanvasVideoPicks.set(song.title, song.artist, videoId)
        onDismiss()
    }
    PlayerDrawer(hazeState, "Choose the video", onDismiss, titleGap = 4.dp) {
        // The video on screen can come from a map measured before today's search (or a search that no longer returns it):
        // it is always listed first, so the sheet never says there is no video while one plays.
        val found = choices?.let { searched ->
            val playing = current?.takeIf { id -> picked != CanvasVideoPicks.NO_VIDEO && searched.none { it.videoId == id } }
            listOfNotNull(playing?.let { VideoChoice(it, "The video playing now", "", 0L) }) + searched
        }
        Text(
            when {
                found == null -> "Looking for this song's videos"
                found.isEmpty() -> "No videos found for this song"
                found.size == 1 -> "1 video found for this song"
                else -> "${found.size} videos found for this song"
            },
            style = fonts.body, color = look.dim, modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(Modifier.fillMaxWidth().testTag("nowplaying:choose-video")) {
            found.orEmpty().forEach { choice ->
                val on = picked != CanvasVideoPicks.NO_VIDEO && choice.videoId == (current ?: picked)
                VideoChoiceRow(
                    title = choice.title, line = listOfNotNull(choice.channel.takeIf { it.isNotBlank() }, choice.durationSec.takeIf { it > 0 }?.let { clock(it * 1000) }).joinToString(" · "),
                    on = on, fonts = fonts, look = look, onClick = { choose(choice.videoId) },
                    still = { m ->
                        Box(m) {
                            AsyncImage(choice.thumbnailUrl, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                            if (choice.durationSec > 0) Text(
                                clock(choice.durationSec * 1000), style = fonts.label, color = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp)
                                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    },
                )
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(look.ink.copy(alpha = 0.12f)))
            VideoChoiceRow(
                title = "No video", line = "Play the song with its cover", on = picked == CanvasVideoPicks.NO_VIDEO,
                fonts = fonts, look = look, onClick = { choose(CanvasVideoPicks.NO_VIDEO) },
                still = { m -> Box(m.background(look.chip), contentAlignment = Alignment.Center) { Icon(CosmoIcons.VideoOff, null, tint = look.dim, modifier = Modifier.size(22.dp)) } },
            )
            OrbitLabel(
                if (picked != null) "Your pick is kept for this song" else "The video is found by itself until you pick one",
                fonts, look.dim, Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
        }
    }
}

/** One 60 dp flat row: the still, the title and its line, and the pick mark at the end (a peach check when [on]). */
@Composable
private fun VideoChoiceRow(
    title: String, line: String, on: Boolean, fonts: OrbitFonts, look: OrbitLook, onClick: () -> Unit,
    still: @Composable (Modifier) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        still(Modifier.size(85.dp, 48.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f)) {
            Text(title, style = fonts.title, color = if (on) look.accent else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotBlank()) Text(line, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(24.dp).background(look.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(CosmoIcons.Check, "Chosen", tint = look.ground, modifier = Modifier.size(16.dp))
            } else Box(Modifier.size(22.dp).border(2.dp, look.dim.copy(alpha = 0.6f), CircleShape))
        }
    }
}
