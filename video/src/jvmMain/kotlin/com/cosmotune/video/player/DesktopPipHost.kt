package com.cosmotune.video.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.cosmotune.ui.pip.DesktopPipBoard
import com.cosmotune.ui.pip.PipOwner
import com.cosmotune.ui.pip.PipWindowHost
import com.cosmotune.ui.pip.PipWish
import com.cosmotune.ui.theme.LocalCosmoType

/**
 * The host's window for Watch and the song's music video ([PipWindowHost], installed by [InstallDesktopPip]): the same window Video's
 * own button opens.
 */
private class DesktopPipWindows(private val provide: @Composable (@Composable () -> Unit) -> Unit) : PipWindowHost {
    override fun open(owner: PipOwner, wish: () -> PipWish?): Boolean {
        val first = wish() ?: return false
        if (first.aspectHeight > 0) DesktopPip.shape(first.aspectWidth.toFloat() / first.aspectHeight)
        return DesktopPip.openFor(owner, wishPipSource(wish), first.title, provide)
    }

    override fun close(returnToApp: Boolean) = DesktopPip.close(returnToApp)
}

/**
 * Makes this window the desktop's picture-in-picture for every mode ([DesktopPipBoard.host]), drawn in the type of the window that
 * composes this. The desktop app's mode host calls it once, in the root, so Watch and Music reach the window without naming Video.
 */
@Composable
fun InstallDesktopPip() {
    val type = LocalCosmoType.current
    DisposableEffect(type) {
        DesktopPipBoard.host = DesktopPipWindows { content -> CompositionLocalProvider(LocalCosmoType provides type) { content() } }
        onDispose { }
    }
}
