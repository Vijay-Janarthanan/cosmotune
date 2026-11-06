package com.cosmotune.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cosmotune.music.R
import com.cosmotune.music.data.model.PlaylistPrivacy
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UserPlaylist
import com.cosmotune.music.ui.icons.CosmotuneIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.OrbitSheetTitle
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoActionRow
import com.cosmotune.ui.sheets.CosmoPickRow
import com.cosmotune.ui.sheets.CosmoSheetGap
import com.cosmotune.ui.sheets.CosmoSheetHeader
import com.cosmotune.ui.sheets.CosmoSheetHeading
import java.util.Locale

/**
 * Where a track goes: one of the account's playlists, or a new one.
 *
 * Two panels in one sheet rather than a sheet that opens a dialog. Creating a
 * playlist here is nearly always in service of adding the track that opened
 * this — so the form comes back to the same place, and the create request
 * carries the track with it instead of leaving a new empty playlist behind
 * for the user to add to a second time.
 *
 * [song] is null when the flow started from the Library tab rather than from a
 * track, which is the one case where the header has no track to draw and
 * "New playlist" is the whole point of the sheet.
 *
 * Playlists are ticked rather than picked: one track often belongs in more
 * than one list, and a sheet that closed on the first tap meant opening it
 * again for each. [onAdd] gets every ticked playlist at once.
 */
@Composable
fun PlaylistPickerSheet(
    playlists: List<UserPlaylist>,
    loading: Boolean,
    onAdd: (List<UserPlaylist>) -> Unit,
    onCreate: (String, PlaylistPrivacy) -> Unit,
    modifier: Modifier = Modifier,
    song: Song? = null,
    startCreating: Boolean = false,
) {
    var creating by remember { mutableStateOf(startCreating) }
    // By id rather than by value: the list is re-fetched under an open sheet,
    // and a refreshed row must stay ticked.
    var selected by remember { mutableStateOf(emptySet<String>()) }

    if (creating) {
        NewPlaylistForm(
            // Nowhere to go back to when the sheet opened straight onto the
            // form; the sheet's own dismiss is the way out.
            onBack = if (startCreating) null else ({ creating = false }),
            onCreate = onCreate,
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxWidth()) {
        if (song != null) {
            CosmoSheetHeader(title = song.title, subtitle = song.artist, imageUrl = song.thumbnailUrl, explicit = song.isExplicit == true)
        }
        CosmoSheetHeading(
            stringResource(if (song != null) R.string.add_to_playlist else R.string.your_playlists),
        )

        CosmoActionRow(
            icon = CosmotuneIcons.Plus,
            label = stringResource(R.string.new_playlist),
            onClick = { creating = true },
        )

        when {
            // Only while there is nothing to show: re-fetching under a list
            // that is already up would replace it with a spinner for no gain.
            loading && playlists.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                OrbitRing(40.dp, orbitLook().ink, progress = null, spinning = true)
            }

            playlists.isEmpty() -> Text(
                text = stringResource(R.string.no_playlists_yet),
                style = orbitFonts().body,
                color = orbitLook().dim,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            )

            else -> {
                CosmoSheetGap(menu = false)
                // Capped so a long list can't push the sheet past the screen;
                // it scrolls inside the sheet instead.
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(playlists, key = { it.playlistId }) { playlist ->
                        val ticked = playlist.playlistId in selected
                        PlaylistRow(
                            playlist = playlist,
                            selected = ticked,
                            onClick = {
                                selected = if (ticked) selected - playlist.playlistId else selected + playlist.playlistId
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OrbitSheetButton(
                    label = if (selected.isEmpty()) {
                        stringResource(R.string.add_to_playlist)
                    } else {
                        pluralStringResource(R.plurals.add_to_playlists_count, selected.size, selected.size)
                    },
                    onClick = { onAdd(playlists.filter { it.playlistId in selected }) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlaylistRow(playlist: UserPlaylist, selected: Boolean, onClick: () -> Unit) {
    CosmoPickRow(
        title = playlist.title,
        subtitle = playlist.subtitle,
        imageUrl = playlist.thumbnailUrl,
        selected = selected,
        onClick = onClick,
    )
}

/**
 * Name and visibility, and nothing else. YouTube also takes a description,
 * which nobody fills in from a phone at the moment of saving a song.
 */
@Composable
private fun NewPlaylistForm(
    onBack: (() -> Unit)?,
    onCreate: (String, PlaylistPrivacy) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val fonts = orbitFonts()
    val look = orbitLook()

    // The form exists to be typed into; opening it with the keyboard already
    // up saves the tap that would otherwise always follow.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        if (name.isNotBlank()) {
            focusManager.clearFocus()
            onCreate(name, privacy)
        }
    }

    // As above: the keyboard is up from the moment this opens, and "Create
    // playlist" is below the fold without this.
    Column(
        modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        OrbitSheetTitle(
            title = stringResource(R.string.new_playlist),
            subtitle = stringResource(R.string.saved_to_youtube_music_account),
            leading = onBack?.let { back ->
                { OrbitRoundButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), look.ink, back) }
            },
        )

        OrbitTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.playlist_name),
            fonts = fonts,
            look = look,
            onDone = submit,
            trailing = if (name.isNotEmpty()) {
                { OrbitRoundButton(Icons.Rounded.Close, stringResource(R.string.clear_name), look.ink, { name = "" }, size = 32.dp) }
            } else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
        )

        CosmoSheetHeading(stringResource(R.string.who_can_see_it))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaylistPrivacy.entries.forEach { option ->
                PrivacyPill(
                    icon = option.icon,
                    label = option.label,
                    selected = option == privacy,
                    onClick = { privacy = option },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        OrbitSheetButton(
            label = stringResource(R.string.create_playlist),
            onClick = submit,
            enabled = name.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * The rename panel of [BrowseActionsSheet], which swaps itself out for this
 * rather than opening a dialog over itself — same reason the create form lives
 * inside [PlaylistPickerSheet].
 */
@Composable
internal fun RenamePlaylistForm(
    playlist: UserPlaylist,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(playlist.title) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val fonts = orbitFonts()
    val look = orbitLook()

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        if (name.isNotBlank()) {
            focusManager.clearFocus()
            onRename(name)
        }
    }

    // The form opens with the keyboard already up, which on a bottom sheet
    // would otherwise sit over the button the form exists to reach.
    Column(
        modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        OrbitSheetTitle(
            title = stringResource(R.string.rename_playlist),
            leading = { OrbitRoundButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), look.ink, onBack) },
        )

        OrbitTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.playlist_name),
            fonts = fonts,
            look = look,
            onDone = submit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.height(12.dp))
        OrbitSheetButton(
            label = stringResource(R.string.save_name),
            onClick = submit,
            enabled = name.isNotBlank() && name != playlist.title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * The glyph that says what a visibility actually means — a padlock, a shared
 * link, a globe. Three words that all sound like degrees of the same thing
 * read much faster as three different shapes.
 */
private val PlaylistPrivacy.icon: ImageVector
    get() = when (this) {
        PlaylistPrivacy.PRIVATE -> Icons.Rounded.Lock
        PlaylistPrivacy.UNLISTED -> Icons.Rounded.Link
        PlaylistPrivacy.PUBLIC -> Icons.Rounded.Public
    }

/** The search filters' chip, carrying an icon ahead of its label. */
@Composable
private fun PrivacyPill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OrbitChip(
        title = label,
        fonts = orbitFonts(),
        look = orbitLook(),
        onClick = onClick,
        selected = selected,
        leading = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}
