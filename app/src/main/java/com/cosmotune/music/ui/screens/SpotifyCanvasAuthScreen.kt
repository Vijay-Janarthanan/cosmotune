package com.cosmotune.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.cosmotune.ui.orbit.OrbitBackHeader
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.OrbitSwitch
import com.cosmotune.ui.orbit.OrbitTextField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

@Composable
fun SpotifyCanvasAuthScreen(
    onNavigateUp: () -> Unit
) {
    val currentToken by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val autoHidePlayer by AppSettings.spotifyCanvasAutoHide.collectAsStateWithLifecycle()
    val prioritizeSpotify by AppSettings.prioritizeSpotifyCanvas.collectAsStateWithLifecycle()
    var tokenInput by remember(currentToken) { mutableStateOf(currentToken) }
    val fonts = orbitFonts()
    val look = orbitLook()

    Column(Modifier.fillMaxSize().background(look.ground).statusBarsPadding()) {
        OrbitBackHeader(
            kind = stringResource(R.string.spotify),
            onBack = onNavigateUp,
            backLabel = stringResource(R.string.back),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.spotify_canvas_setup),
                style = fonts.heroTitle,
                color = look.ink,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.spotify_canvas_setup_description),
                style = fonts.body,
                color = look.dim,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SpotifyCanvasSettingToggle(
                title = stringResource(R.string.spotify_canvas_auto_hide),
                subtitle = stringResource(R.string.spotify_canvas_auto_hide_subtitle),
                checked = autoHidePlayer,
                onCheckedChange = AppSettings::setSpotifyCanvasAutoHide,
            )

            SpotifyCanvasSettingToggle(
                title = stringResource(R.string.prioritize_spotify_canvas),
                subtitle = stringResource(R.string.prioritize_spotify_canvas_subtitle),
                checked = prioritizeSpotify,
                onCheckedChange = AppSettings::setPrioritizeSpotifyCanvas,
            )

            Text(
                text = stringResource(R.string.spotify_canvas_setup_steps),
                style = fonts.body,
                color = look.dim,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
            )

            OrbitTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                placeholder = stringResource(R.string.spdc_token),
                fonts = fonts,
                look = look,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            )

            OrbitSheetButton(
                label = stringResource(R.string.save),
                onClick = {
                    AppSettings.setSpotifySpdcToken(tokenInput.trim())
                    onNavigateUp()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SpotifyCanvasSettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) {
            Text(text = title, style = fonts.title, color = look.ink)
            Text(text = subtitle, style = fonts.body, color = look.dim)
        }
        OrbitSwitch(checked = checked, onChange = onCheckedChange, look = look, label = title)
    }
}
