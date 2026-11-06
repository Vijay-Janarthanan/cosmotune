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
    val found = (state as? AboutState.Found)?.summary
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = onDismiss,
        overline = "About this album",
        title = found?.title ?: album,
        message = when (state) {
            AboutState.Offline -> "Offline mode is on, so Wikipedia was not asked."
            AboutState.Missing -> "Wikipedia has no article for this album."
            else -> found?.description
        },
        confirmLabel = if (found != null) "Open article" else null,
        onConfirm = { found?.let { runCatching { uri.openUri(it.pageUrl) } } },
        dismissLabel = "Close",
        onDismiss = onDismiss,
        maxWidth = 520.dp,
        content = when {
            state == AboutState.Loading -> ({ CircularProgressIndicator(Modifier.size(28.dp)) })
            found != null -> ({
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    found.thumbnailUrl?.let {
                        AsyncImage(
                            model = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
                        )
                    }
                    Text(found.extract)
                    Text("From Wikipedia, under CC BY-SA 4.0.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
            else -> null
        },
    )
}

private sealed interface AboutState {
    data object Loading : AboutState
    data object Offline : AboutState
    data object Missing : AboutState
    data class Found(val summary: WikipediaAbout.Summary) : AboutState
}
