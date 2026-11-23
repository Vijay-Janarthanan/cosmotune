package com.cosmotune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.cloud.LostLikes
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

const val RESTORE_LIKES_TITLE = "Restore lost likes"
const val RESTORE_LIKES_LINE = "Finds songs you liked in Cosmotune that YouTube's Liked music no longer lists, and likes them again"

/**
 * Settings, Your data: the songs liked in Cosmotune that YouTube's Liked music has lost ([LostLikes]). Check reads the
 * account's whole liked list ([loadLiked]) and compares it with the likes kept here ([kept]); Like them again sends each
 * one back ([like]), with a count as it goes. Nothing is sent until the listener asks.
 */
@Composable
fun RestoreLikesRows(
    signedIn: Boolean,
    kept: () -> Map<String, JsonObject>,
    loadLiked: suspend () -> Result<Set<String>>,
    like: suspend (LostLikes.Lost) -> Boolean,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var lost by remember { mutableStateOf<List<LostLikes.Lost>?>(null) }
    var line by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(RESTORE_LIKES_TITLE, style = fonts.title, color = look.ink)
        Text(if (signedIn) RESTORE_LIKES_LINE else "Sign in to YouTube Music to check your likes", style = fonts.body, color = look.dim)
        val found = lost
        (busy ?: line)?.let { Text(it, style = fonts.body, color = look.ink, modifier = Modifier.padding(top = 4.dp)) }
        if (busy == null && !found.isNullOrEmpty()) {
            found.take(5).forEach { song ->
                Text(listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "), style = fonts.body, color = look.dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (found.size > 5) Text("and ${found.size - 5} more", style = fonts.body, color = look.dim)
        }
        if (signedIn && busy == null) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!found.isNullOrEmpty()) {
                OrbitActionPill("Like them again", {
                    scope.launch {
                        busy = "Liking again, 0 of ${found.size}"
                        val accepted = LostLikes.restore(found, like) { busy = "Liking again, $it of ${found.size}" }
                        busy = null; lost = null
                        line = if (accepted == found.size) "Liked again: ${found.size} songs are back in Liked music"
                        else "Liked again: $accepted of ${found.size}. Check again later for the rest."
                    }
                }, fonts, look)
            }
            OrbitActionPill(if (found == null) "Check my likes" else "Check again", {
                scope.launch {
                    busy = "Reading your Liked music…"
                    val keptNow = kept()
                    loadLiked().onSuccess { onYouTube ->
                        val missing = LostLikes.missing(keptNow, onYouTube)
                        lost = missing
                        line = LostLikes.summary(missing.size, keptNow.size)
                    }.onFailure { line = "Couldn't read your Liked music. Check the connection and try again." }
                    busy = null
                }
            }, fonts, look)
        }
    }
}
