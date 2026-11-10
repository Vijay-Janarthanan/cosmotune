package com.cosmotune.music.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.R
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.spotify.SharedPlaylistLink
import com.cosmotune.music.data.spotify.SpotifyImportTrack
import com.cosmotune.ui.theme.CosmoRadii
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Imports a pasted link or starts immediately when opened from Android's share sheet. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SpotifyImportAlert(
    hazeState: HazeState,
    initialLink: String?,
    onImported: (title: String, songs: List<Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf(initialLink.orEmpty()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var unmatched by remember { mutableStateOf<List<SpotifyImportTrack>?>(null) }
    var completed by rememberSaveable { mutableStateOf(false) }
    var unavailable by rememberSaveable { mutableStateOf(0) }

    fun start() {
        if (working || completed) return
        if (SharedPlaylistLink.fromText(link) == null) {
            status = context.getString(R.string.spotify_import_invalid)
            failed = true
            return
        }
        working = true
        failed = false
        status = context.getString(R.string.spotify_import_fetching)
        scope.launch {
            try {
                val result = SharedPlaylistLink.import(link) { done, total ->
                    status = context.getString(R.string.spotify_import_matching, done, total)
                }
                if (result.songs.isEmpty()) error(context.getString(R.string.spotify_import_none))
                onImported(result.name, result.songs)
                unavailable = result.unavailable
                unmatched = result.unmatched
                completed = true
                if (result.unmatched.isEmpty() && unavailable == 0) onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = context.getString(R.string.spotify_import_failed, e.message.orEmpty())
                failed = true
            } finally { working = false }
        }
    }

    LaunchedEffect(initialLink) { if (initialLink != null && !completed) start() }
    BackHandler { if (!working) onDismiss() }

    AlertScaffold(hazeState = hazeState, onDismiss = { if (!working) onDismiss() }) {
        com.cosmotune.ui.sheets.CosmoDialogBody(
            overline = null,
            title = stringResource(R.string.spotify_import_title),
            message = null,
            confirmLabel = if (completed) null else stringResource(R.string.spotify_import_button),
            onConfirm = ::start,
            confirmEnabled = link.isNotBlank() && !working,
            dismissLabel = stringResource(if (completed) R.string.done else R.string.cancel),
            onDismiss = onDismiss,
            dismissEnabled = !working,
        ) {
            val missed = unmatched.orEmpty()
            if (completed) {
                Text(
                    text = if (missed.isEmpty()) stringResource(R.string.playlist_import_saved)
                        else stringResource(R.string.spotify_import_unmatched, missed.size),
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (unavailable > 0) Text(stringResource(R.string.playlist_import_unavailable, unavailable))
                if (missed.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(CosmoRadii.rSm))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        missed.forEach { track ->
                            Text(
                                text = if (track.artist.isBlank()) track.title else "${track.title} · ${track.artist}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = status ?: stringResource(R.string.spotify_import_description),
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = when {
                        status == null -> MaterialTheme.colorScheme.onSurface
                        failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                PillTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        if (failed) {
                            status = null
                            failed = false
                        }
                    },
                    placeholder = stringResource(R.string.spotify_import_hint),
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank() && !working) start() }),
                )
            }
        }
    }
}
