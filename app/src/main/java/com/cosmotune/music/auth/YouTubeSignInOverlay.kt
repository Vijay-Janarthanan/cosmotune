package com.cosmotune.music.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cosmotune.music.R
import com.cosmotune.music.ui.MainViewModel
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSheetAction
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * The full-screen Google sign-in (or channel picker) over whichever mode asked for it: the bar with Close, the title and
 * "Use this profile", and [YtMusicLoginScreen] under it. The one login of the app: Music opens it from its profile button and
 * Library, Video opens it in place from its account sheet (it no longer switches the app to Music to do so).
 *
 * A capture goes through [MainViewModel.onWebSession], which validates it before anything is stored; [onClose] is called when the
 * screen is closed by hand, [onSignedIn] after a capture was accepted.
 */
@Composable
fun YouTubeSignInOverlay(
    mode: WebSessionMode,
    viewModel: MainViewModel,
    initialCookie: String?,
    initialGoogleCookie: String? = null,
    onClose: () -> Unit,
    onSignedIn: () -> Unit,
) {
    BackHandler { onClose() }
    // Raised by "Use this channel", read by the browser as "take the
    // session from the page as it now stands". A counter rather than a
    // flag so a second tap, after a failed first one, is still a new
    // request rather than a value that was already true.
    var captureRequest by remember(mode) { mutableIntStateOf(0) }
    var captureFailed by remember(mode) { mutableStateOf(false) }
    var pageReady by remember(mode) { mutableStateOf(false) }
    var confirmingProfile by remember(mode) { mutableStateOf(false) }
    val fonts = orbitFonts()
    val look = orbitLook()
    Box(Modifier.fillMaxSize().background(look.ground)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OrbitRoundButton(Icons.Rounded.Close, stringResource(R.string.close), look.ink, onClose)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (mode) {
                            WebSessionMode.SIGN_IN -> stringResource(R.string.sign_in_youtube_music)
                            WebSessionMode.SWITCH_CHANNEL -> stringResource(R.string.choose_profile)
                        },
                        style = fonts.title,
                        color = look.ink,
                    )
                    if (pageReady) {
                        Text(
                            text = if (captureFailed) {
                                stringResource(R.string.profile_unavailable)
                            } else {
                                stringResource(R.string.switch_profile_hint)
                            },
                            style = fonts.body,
                            color = look.dim,
                            maxLines = 2,
                        )
                    }
                }
                if (pageReady) {
                    OrbitSheetAction(
                        label = if (confirmingProfile) stringResource(R.string.checking)
                        else stringResource(R.string.use_this_profile),
                        onClick = {
                            captureFailed = false
                            confirmingProfile = true
                            captureRequest++
                        },
                        primary = true,
                        enabled = !confirmingProfile,
                    )
                }
            }
            YtMusicLoginScreen(
                mode = mode,
                initialCookie = initialCookie,
                initialGoogleCookie = initialGoogleCookie,
                captureRequest = captureRequest,
                onPageReady = { pageReady = it },
                onCaptureUnavailable = {
                    confirmingProfile = false
                    captureFailed = true
                },
                onCaptured = { session ->
                    viewModel.onWebSession(session, mode) { accepted ->
                        confirmingProfile = false
                        if (accepted) onSignedIn() else captureFailed = true
                    }
                },
            )
        }
    }
}
