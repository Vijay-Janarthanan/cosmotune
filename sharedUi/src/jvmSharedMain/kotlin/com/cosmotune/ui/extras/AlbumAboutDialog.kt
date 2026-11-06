package com.cosmotune.ui.extras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.cosmotune.music.data.NetworkPolicy
import com.cosmotune.music.data.about.WikipediaAbout

/**
 * "About this album": Wikipedia's summary of [album] by [artist], with a link to the article. Opened from a song's menu on
 * both apps. Says plainly when there is no article, and does not ask while offline mode is on.
 */
@Composable
fun AlbumAboutDialog(album: String, artist: String?, onDismiss: () -> Unit) {
    var state by remember(album, artist) { mutableStateOf<AboutState>(AboutState.Loading) }
    LaunchedEffect(album, artist) {
        state = if (NetworkPolicy.offline) {
            AboutState.Offline
        } else {
            WikipediaAbout.album(album, artist)?.let(AboutState::Found) ?: AboutState.Missing
        }
    }
    val uri = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(16.dp).widthIn(max = 520.dp).heightIn(max = 640.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("About this album", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                when (val shown = state) {
                    AboutState.Loading -> {
                        Text(album, style = MaterialTheme.typography.headlineSmall)
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                    AboutState.Offline -> {
                        Text(album, style = MaterialTheme.typography.headlineSmall)
                        Text("Offline mode is on, so Wikipedia was not asked.", style = MaterialTheme.typography.bodyMedium)
                    }
                    AboutState.Missing -> {
                        Text(album, style = MaterialTheme.typography.headlineSmall)
                        Text("Wikipedia has no article for this album.", style = MaterialTheme.typography.bodyMedium)
                    }
                    is AboutState.Found -> {
                        val summary = shown.summary
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            summary.thumbnailUrl?.let {
                                AsyncImage(
                                    model = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(summary.title, style = MaterialTheme.typography.headlineSmall)
                                summary.description?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(summary.extract, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "From Wikipedia, under CC BY-SA 4.0.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    (state as? AboutState.Found)?.let { found ->
                        TextButton(onClick = { runCatching { uri.openUri(found.summary.pageUrl) } }) { Text("Open article") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

private sealed interface AboutState {
    data object Loading : AboutState
    data object Offline : AboutState
    data object Missing : AboutState
    data class Found(val summary: WikipediaAbout.Summary) : AboutState
}
