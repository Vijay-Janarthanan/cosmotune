package com.cosmotune.ui.sheets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import java.text.Normalizer
import java.util.Locale

/** Lower case with Latin accents dropped, so "Beyoncé" is found by "beyonce". Other scripts keep their marks (they are vowels there). */
private fun folded(text: String): String = buildString(text.length) {
    text.lowercase(Locale.ROOT).forEach { c ->
        if (c.code < 0x80 || Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN) append(c)
        else Normalizer.normalize(c.toString(), Normalizer.Form.NFD).forEach { if (Character.getType(it) != Character.NON_SPACING_MARK.toInt()) append(it) }
    }
}

/**
 * Add to playlist's "Find a playlist" (from upstream 1.10, GPL-3.0): the playlists whose name holds every word of [query], in any
 * order, ignoring case and accents; all of them for a blank query, in the order they came (the library's).
 */
fun <T> findPlaylists(playlists: List<T>, query: String, name: (T) -> String): List<T> {
    val words = folded(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return playlists
    return playlists.filter { playlist -> folded(name(playlist)).let { title -> words.all { it in title } } }
}

/** The "Find a playlist" field over a picker's list, the phone's sheet and the desktop's dialog alike; a clear button once typed in. */
@Composable
fun PlaylistFindField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val look = orbitLook()
    OrbitTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Find a playlist",
        fonts = orbitFonts(),
        look = look,
        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
        trailing = if (query.isNotEmpty()) {
            { OrbitRoundButton(Icons.Rounded.Close, "Clear", look.ink, { onQueryChange("") }, size = 32.dp) }
        } else null,
        modifier = modifier,
    )
}

/** What the picker says when the search hides every playlist. */
fun noPlaylistMatches(query: String): String = "No playlists match “${query.trim()}”"
