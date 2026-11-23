package com.cosmotune.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.SongTags
import com.cosmotune.music.data.model.Song
import com.cosmotune.settings.Extras
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitPickerSheet
import com.cosmotune.ui.orbit.OrbitSelectChip
import com.cosmotune.ui.orbit.OrbitSheetTitle
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Ratings, moods and tags (the idea from Namida, see NOTICE.md), on the phone and the desktop: a song's menu has Rate and tag,
 * which opens [SongTagsDialog] (one to five stars and any moods or tags); Library's Moods and tags tile opens [SongTagsSheet], the
 * pages: Top rated, Rated, and one per tag, each playable. The songs are this device's own, kept in the extras settings.
 */

/** Opens the Rate and tag dialog for a song; [SongInfoHost] draws it, once in each app's root. */
object SongTagging {
    val editing = MutableStateFlow<Song?>(null)

    fun open(song: Song) { if (song.videoId.isNotBlank()) editing.value = song }
}

/** The Rate and tag dialog for [song]: stars, the moods and tags in use (and some to start with), a field for a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongTagsDialog(song: Song, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val all by Extras.settings.songTags.collectAsState()
    val saved = remember(song.videoId) { SongTags.of(all, song.videoId) }
    var rating by remember(song.videoId) { mutableStateOf(saved?.rating ?: 0) }
    var tags by remember(song.videoId) { mutableStateOf(saved?.tags.orEmpty()) }
    var typed by remember { mutableStateOf("") }
    val offered = remember(all, tags) { (tags + SongTags.allTags(all) + SongTags.SUGGESTED).distinctBy { it.lowercase() }.take(24) }
    fun addTyped() {
        val tag = SongTags.tidyTag(typed)
        if (tag.isNotEmpty() && tags.none { it.equals(tag, ignoreCase = true) } && tags.size < SongTags.MAX_TAGS) tags = tags + tag
        typed = ""
    }
    CosmoAlertDialog(
        onDismissRequest = onDismiss,
        title = "Rate and tag",
        message = "${song.title} · ${song.artist}",
        confirmLabel = "Save",
        onConfirm = {
            val pending = SongTags.tidyTag(typed).takeIf { it.isNotEmpty() && tags.none { t -> t.equals(it, ignoreCase = true) } }
            Extras.settings.setSongTags(SongTags.set(all, song, rating, tags + listOfNotNull(pending), System.currentTimeMillis()))
            onDismiss()
        },
        dismissLabel = "Cancel",
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("song-tags:dialog")) {
            OrbitLabel("Your rating", fonts, look.dim)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (star in 1..5) {
                    CosmoIconButton(
                        if (star <= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (star == 1) "1 star" else "$star stars",
                        onClick = { rating = if (rating == star) 0 else star },
                        size = 40.dp,
                        tint = if (star <= rating) look.accent else look.dim,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            OrbitLabel("Moods and tags", fonts, look.dim)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tag in offered) {
                    val on = tags.any { it.equals(tag, ignoreCase = true) }
                    OrbitChip(tag, fonts, look, onClick = {
                        tags = if (on) tags.filterNot { it.equals(tag, ignoreCase = true) } else (tags + tag).take(SongTags.MAX_TAGS)
                    }, selected = on, leading = null)
                }
            }
            Spacer(Modifier.height(12.dp))
            OrbitTextField(typed, { typed = it.take(SongTags.MAX_TAG) }, "Add your own", fonts, look, Modifier.fillMaxWidth().testTag("song-tags:field"),
                onDone = ::addTyped)
        }
    }
}

/**
 * Moods and tags: chips for Top rated, Rated and each tag in use, and the chosen page's songs as rows. A tap plays the page from
 * that song ([onPlay]); a hold opens Rate and tag for it.
 */
@Composable
fun SongTagsSheet(onDismiss: () -> Unit, onPlay: ((List<Song>, Int) -> Unit)?) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val all by Extras.settings.songTags.collectAsState()
    val tags = remember(all) { SongTags.allTags(all) }
    val pages = remember(tags, all) {
        listOfNotNull(
            SongTags.Page.TopRated.takeIf { all.any { it.rating >= 4 } },
            SongTags.Page.Rated.takeIf { all.any { it.rating > 0 } },
        ) + tags.map { SongTags.Page.Tag(it) }
    }
    var chosen by remember { mutableStateOf<SongTags.Page?>(null) }
    val page = chosen?.takeIf { it in pages } ?: pages.firstOrNull()
    val songs = remember(all, page) { page?.let { SongTags.page(all, it) }.orEmpty() }
    OrbitPickerSheet(onDismiss) {
        OrbitSheetTitle(title = "Moods and tags", label = "Library", subtitle = if (all.size == 1) "1 song" else "${all.size} songs")
        OrbitChipRow(18.dp, Modifier.padding(top = 4.dp, bottom = 8.dp).testTag("song-tags:pages")) {
            for (option in pages) {
                val title = when (option) {
                    SongTags.Page.TopRated -> "Top rated"
                    SongTags.Page.Rated -> "Rated"
                    is SongTags.Page.Tag -> option.name
                }
                OrbitSelectChip(title, selected = option == page, fonts = fonts, look = look, onClick = { chosen = option }, count = SongTags.page(all, option).size)
            }
        }
        if (songs.isEmpty()) {
            Text("Rate or tag a song from its menu and it shows here.", style = fonts.body, color = look.dim, modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            itemsIndexed(songs, key = { _, song -> song.id }) { index, tagged ->
                val stars = if (tagged.rating > 0) "★".repeat(tagged.rating) else null
                OrbitSongRow(
                    title = tagged.title,
                    subtitle = listOfNotNull(stars, tagged.artist).joinToString(" · "),
                    cover = tagged.thumbnailUrl,
                    fonts = fonts,
                    look = look,
                    onClick = onPlay?.let { play -> { play(songs.map { it.toSong() }, index); onDismiss() } },
                    onLongClick = { SongTagging.open(tagged.toSong()) },
                    modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
