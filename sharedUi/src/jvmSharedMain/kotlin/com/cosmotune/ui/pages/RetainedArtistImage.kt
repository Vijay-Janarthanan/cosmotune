package com.cosmotune.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** Keeps the last decoded portrait on screen until its replacement succeeds, within one exact identity. */
@Composable
internal fun RetainedArtistImage(url: String?, identity: String?, modifier: Modifier = Modifier) {
    var decoded by remember(identity) { mutableStateOf<Painter?>(null) }
    val latest by rememberUpdatedState(url)
    Box(modifier) {
        decoded?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        AsyncImage(url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { state -> if (latest == url) decoded = state.painter })
    }
}
