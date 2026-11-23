package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable

/** Opens the device's file picker for a .torrent file and hands its bytes (at most 4 MB) to [onPicked]. */
@Composable internal expect fun rememberTorrentFilePicker(onPicked: (ByteArray) -> Unit): () -> Unit
