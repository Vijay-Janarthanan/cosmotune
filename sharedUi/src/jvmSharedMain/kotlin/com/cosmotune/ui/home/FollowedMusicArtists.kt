package com.cosmotune.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import com.cosmotune.listening.ListeningStore
import com.cosmotune.listening.WatchedArtist
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The explicit watch list, read without blocking UI. Closing its editor refreshes Home's context. */
@Composable
fun rememberFollowedMusicArtists(file: File, refreshKey: Any?): List<WatchedArtist> =
    produceState(emptyList<WatchedArtist>(), file.absolutePath, refreshKey) {
        value = withContext(Dispatchers.IO) { runCatching { ListeningStore(file).state.value.artists.take(200) }.getOrDefault(emptyList()) }
    }.value
