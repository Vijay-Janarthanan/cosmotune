package com.cosmotune.ui.library

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.listening.LibraryViewOptions
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.settings.LocalMusicSort
import com.cosmotune.music.download.DownloadedCollection
import com.cosmotune.music.ui.screens.localizedLabel
import com.cosmotune.music.ui.screens.sortedForLibrary

/**
 * The one entry point of the Downloads and Local Music pages: Orbit's page ([CosmoDeviceMusic]). It is in `app` because the sort
 * settings and the columns' store are. The parameter list is upstream's `LocalMusicScreen`'s, name for name and in the same order.
 *
 * The page keeps the folder's own sort ([AppSettings.downloadedMusicSort] or [AppSettings.localMusicSort]) and its columns (the
 * `cosmotune_library_view` preferences, one key per folder), as the old page did.
 */
@Composable
fun LocalMusicSwitch(
    songs: List<Song>,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    contentPadding: PaddingValues,
    emptyMessage: String? = null,
    onFindMusic: (() -> Unit)? = null,
    onCollectionLongPress: ((String, List<Song>) -> Unit)? = null,
    collections: List<DownloadedCollection> = emptyList(),
    isDownloads: Boolean = false,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    onDeleteDownloads: ((List<Song>) -> Unit)? = null,
    onUploadToWebDav: ((List<Song>) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val sort by if (isDownloads) AppSettings.downloadedMusicSort.collectAsStateWithLifecycle() else AppSettings.localMusicSort.collectAsStateWithLifecycle()
    val sorted = remember(songs, sort) { songs.sortedForLibrary(sort) }
    val prefs = LocalContext.current.getSharedPreferences("cosmotune_library_view", Context.MODE_PRIVATE)
    val viewKey = if (isDownloads) "downloads" else "local"
    var columns by remember(viewKey) { mutableStateOf(LibraryViewOptions.decode(prefs.getString(viewKey, null))) }
    CosmoDeviceMusic(
        title = if (isDownloads) "Downloads" else "Local Music",
        songs = sorted,
        onSongClick = onSongClick,
        onSongLongPress = onSongLongPress,
        contentPadding = contentPadding,
        modifier = modifier,
        isDownloads = isDownloads,
        onSongSwipe = onSongSwipe,
        onShuffle = onShuffle,
        collections = collections.map { DeviceCollection(it.id, it.title, it.subtitle, it.thumbnailUrl, it.songs, it.playlist) },
        sortLabels = LocalMusicSort.entries.map { it.localizedLabel() },
        sortIndex = sort.ordinal,
        onSort = { index ->
            val next = LocalMusicSort.entries[index]
            if (isDownloads) AppSettings.setDownloadedMusicSort(next) else AppSettings.setLocalMusicSort(next)
        },
        columns = columns,
        onColumnsChange = { next ->
            columns = next
            prefs.edit().putString(viewKey, next.encode()).apply()
        },
        emptyMessage = emptyMessage,
        onFindMusic = onFindMusic,
        onCollectionLongPress = onCollectionLongPress,
        onDeleteSelected = onDeleteDownloads,
        onUploadSelected = onUploadToWebDav,
        currentSong = currentSong,
        isPlaying = isPlaying,
    )
}
