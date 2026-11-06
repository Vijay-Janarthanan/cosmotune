package com.cosmotune.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.R
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.spotify.SpotifyLibrary
import com.cosmotune.music.data.spotify.SpotifyPlaylist
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.spotify_logo
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import org.jetbrains.compose.resources.painterResource

/**
 * The signed-in Spotify account's playlists. A tap opens one as an ordinary
 * playlist page ([onOpenPlaylist]) — this screen is only the way in.
 */
@Composable
fun SpotifyLibraryScreen(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenTokenSetup: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fonts = orbitFonts()
    val look = orbitLook()

    LaunchedEffect(cookie) {
        if (cookie.isBlank()) {
            playlists = emptyList()
            error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { SpotifyLibrary.playlists() }
            .onSuccess { playlists = it }
            .onFailure { error = it.message }
        loading = false
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.spotify_logo),
                    contentDescription = null,
                    tint = look.ink,
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.spotify),
                    style = fonts.greeting,
                    color = look.ink,
                )
            }
        }
        if (cookie.isBlank()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(R.string.spotify_connect_subtitle),
                        style = fonts.body,
                        color = look.dim,
                    )
                    Spacer(Modifier.height(16.dp))
                    OrbitSheetButton(label = stringResource(R.string.spotify_sign_in), onClick = onOpenLogin)
                    Spacer(Modifier.height(8.dp))
                    OrbitSheetButton(label = stringResource(R.string.spotify_use_spdc_token), onClick = onOpenTokenSetup, primary = false)
                }
            }
        } else {
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        OrbitRing(40.dp, look.ink, progress = null, spinning = true)
                    }
                }
            }
            error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = fonts.body,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { item ->
                OrbitSongRow(
                    title = item.name,
                    subtitle = item.owner.orEmpty(),
                    cover = item.imageUrl,
                    fonts = fonts,
                    look = look,
                    onClick = { onOpenPlaylist(item) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
