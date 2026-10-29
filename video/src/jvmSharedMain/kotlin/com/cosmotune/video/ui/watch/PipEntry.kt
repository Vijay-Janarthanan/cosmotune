package com.cosmotune.video.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.video.player.PictureInPictureState
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_pip_playing
import com.cosmotune.video.resources.video_pip_return
import org.jetbrains.compose.resources.stringResource

/**
 * What the picture-in-picture button does on this device, or null where there is no such button: on the desktop it opens the window
 * of its own ([PictureInPictureState.enter]); on the phone it is the system's `enterPictureInPictureMode` (null on a device without
 * the feature). Both pages of the player ask for it, so their buttons agree.
 */
@Composable
internal fun rememberPipEntry(pip: PictureInPictureState): (() -> Unit)? {
    val system = rememberEnterPictureInPicture()
    return remember(pip, system, pip.supported) {
        when {
            !pip.supported -> null
            pip.manual -> ({ pip.enter() })
            else -> system
        }
    }
}

/**
 * What the page shows in place of the player while the desktop's picture-in-picture window holds the picture: the video is on, in the
 * small window, and "Return" brings it back here ([onReturn]). White on black: the player's own black, the documented exception.
 */
@Composable
internal fun PipPlaceholder(onReturn: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(Res.string.video_pip_playing), color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
        CosmoButton(stringResource(Res.string.video_pip_return), onReturn, kind = CosmoButtonKind.QUIET, size = CosmoButtonSize.SMALL, floating = true)
    }
}
