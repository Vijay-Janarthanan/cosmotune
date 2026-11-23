package com.cosmotune.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.credits.SongCredit
import com.cosmotune.music.data.credits.SongCredits
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPickerSheet
import com.cosmotune.ui.orbit.OrbitSheetTitle
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Song info (from upstream 1.10, GPL-3.0): a song's album and length, then its credits as YouTube Music lists them (who performed,
 * wrote and produced it). A song's menu calls [open] on the phone and the desktop alike; [SongInfoHost], once in each app's root,
 * shows the sheet.
 */
object SongInfo {
    val shown = MutableStateFlow<Song?>(null)

    fun open(song: Song) { shown.value = song }
}

/** Where [SongInfo]'s sheet is drawn: once in each app's root, over everything. */
@Composable
fun SongInfoHost() {
    val song by SongInfo.shown.collectAsState()
    song?.let { SongInfoSheet(it) { SongInfo.shown.value = null } }
}

/** What the credits part of the sheet shows: still asking, the credits, or none. */
sealed interface SongCreditsState {
    data object Loading : SongCreditsState
    data class Found(val credits: List<SongCredit>) : SongCreditsState
    data object None : SongCreditsState
}

/** The sheet's rows above the credits: Album, Length; a row with nothing to say is left out. */
fun songInfoFacts(song: Song): List<Pair<String, String>> = listOfNotNull(
    song.albumName?.takeIf { it.isNotBlank() }?.let { "Album" to it },
    song.durationText?.takeIf { it.isNotBlank() }?.let { "Length" to it },
)

@Composable
fun SongInfoSheet(song: Song, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val credits by produceState<SongCreditsState>(SongCreditsState.Loading, song.videoId) {
        val found = SongCredits.forSong(song.videoId)
        value = if (found.isEmpty()) SongCreditsState.None else SongCreditsState.Found(found)
    }
    OrbitPickerSheet(onDismiss) {
        OrbitSheetTitle(title = song.title, label = "Song info", subtitle = song.artist)
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).testTag("song-info"),
        ) {
            val row: @Composable (String, String) -> Unit = { label, value ->
                // 8 dp from the label to what it names, 16 dp to the next pair.
                OrbitLabel(label, fonts, look.dim)
                Text(value, style = fonts.body, color = look.ink, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            }
            songInfoFacts(song).forEach { (label, value) -> row(label, value) }
            when (val state = credits) {
                SongCreditsState.Loading -> Text("Looking up the credits…", style = fonts.body, color = look.dim, modifier = Modifier.padding(bottom = 16.dp))
                SongCreditsState.None -> Text("YouTube Music has no credits for this song.", style = fonts.body, color = look.dim, modifier = Modifier.padding(bottom = 16.dp))
                is SongCreditsState.Found -> state.credits.forEach { credit -> row(credit.role, credit.names.joinToString("\n")) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
