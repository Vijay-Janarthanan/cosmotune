package com.cosmotune.ui.pip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

/**
 * The desktop's picture-in-picture: one small always-on-top window for the app, which the host provides ([host]; the desktop app's
 * mode host installs Video's window there), and one [board] that says who is in it. Video's own window, the same one, tells the
 * board too ([opened], [closed]), so a film, a video and a song's video never open two windows or take one from each other.
 */
object DesktopPipBoard {
    val board = PipCoordinator()

    /** The window the host installed; null until it has (and in tests): the players' buttons then stay hidden. */
    var host: PipWindowHost? by mutableStateOf(null)

    /** Whether there is a screen to put a window on. */
    val headless: Boolean get() = GraphicsEnvironment.isHeadless()

    /** The window opened for [owner] (by the host, or Video's own). */
    fun opened(owner: PipOwner) {
        board.ask(owner)
        board.entered()
    }

    /**
     * The window closed: [returnToApp] when it was "Back to the app" (the player's [PipWish.onExpanded]), otherwise its close
     * button ([PipWish.onClosed]: Watch pauses, the song plays on without its picture).
     */
    fun closed(returnToApp: Boolean) {
        val was = board.left() ?: return
        val wish = board.wish(was)
        runCatching { if (returnToApp) wish?.onExpanded?.invoke() else wish?.onClosed?.invoke() }
        // A session kept after its page left goes with the window ("Back to app" brought its page back, which speaks again).
        board.dropDetached(was)
    }
}

/** The desktop: [owner]'s handle on the one window. */
@Composable
actual fun rememberPip(owner: PipOwner): PipHandle {
    val handle = remember(owner) { DesktopPipHandle(owner) }
    DisposableEffect(handle) { onDispose { handle.leave() } }
    return handle
}

@Stable
private class DesktopPipHandle(private val owner: PipOwner) : PipHandle {
    override val supported: Boolean get() = DesktopPipBoard.host != null && !DesktopPipBoard.headless
    override val active: Boolean get() = DesktopPipBoard.board.showing == owner
    override val manual: Boolean get() = true

    override fun update(wish: PipWish) = DesktopPipBoard.board.update(this, owner, wish)

    override fun enter(): Boolean {
        val host = DesktopPipBoard.host ?: return false
        val board = DesktopPipBoard.board
        if (board.showing == owner) return true
        if (board.wish(owner)?.picture == null || !board.ask(owner)) return false
        val other = board.showing
        if (other != null && other != owner) {
            // Another player's session, its page gone, is in the window: it gives way. The window is taken apart a moment
            // after it closes, so this player's opens after that.
            host.close(returnToApp = false)
            SwingUtilities.invokeLater { SwingUtilities.invokeLater { open(host) } }
            return true
        }
        return open(host)
    }

    private fun open(host: PipWindowHost): Boolean {
        val board = DesktopPipBoard.board
        if (board.wish(owner) == null) return false
        board.ask(owner)
        val opened = host.open(owner) { board.wish(owner) }
        if (opened) board.entered() else board.cancelAsk()
        return opened
    }

    /** The page's Return: the picture comes back to the page and plays on ([PipWish.onExpanded], not the close button's pause). */
    override fun exit() {
        if (active) DesktopPipBoard.host?.close(returnToApp = true)
    }

    /**
     * The page left: its wish goes, unless the window is showing it and its player keeps a session there ([PipWish.keep]); a
     * window left with nothing to draw closes.
     */
    fun leave() {
        val showing = DesktopPipBoard.board.showing == owner
        DesktopPipBoard.board.withdraw(this, owner)
        if (showing && !DesktopPipBoard.board.claims(owner)) DesktopPipBoard.host?.close(returnToApp = false)
    }
}

/** The desktop: its small window draws the kept session itself. */
@Composable
actual fun PipKeptPicture() = Unit
