package com.cosmotune.music.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How the window is sitting on the screen: floating, maximized, or filling it. */
internal object DesktopWindowMode {

    private val _placement = MutableStateFlow(WindowPlacement.Floating)
    private val _fullScreen = MutableStateFlow(false)
    private val _maximized = MutableStateFlow(false)

    /** What the window's own `placement` is driven from. */
    val placement: StateFlow<WindowPlacement> = _placement

    /** Whether the player is filling the screen, for the controls that say so. */
    val fullScreen: StateFlow<Boolean> = _fullScreen

    /** Whether the window is maximized, for the caption button's own glyph. */
    val maximized: StateFlow<Boolean> = _maximized

    /**
     * The placement the player's full-screen button asks for.
     *
     * Maximized on Windows, not Fullscreen: Compose routes Fullscreen through Skiko's layer, which
     * swaps the rendering surface and there leaves the whole screen white.
     */
    private val fillsScreen: WindowPlacement =
        if (DesktopPlatform.drawsOwnWindowFrame) WindowPlacement.Maximized else WindowPlacement.Fullscreen

    /**
     * The native borderless fullscreen (BC-35): on Windows the only thing that covers a taskbar told to stay on top is a
     * frameless window raised above it, which no Compose placement is. True when the native frame took the request; then the
     * window's placement is left as it was (the Win32 style changes, not the placement). Replaceable for tests.
     */
    internal var nativeFullscreen: (Boolean) -> Boolean = { on -> DesktopPlatform.isWindows && DesktopWindowsFrame.setFullscreen(on) }

    /** Puts the chosen window material back over the frame margin the native window resets on its way out. */
    internal var restoreBackdrop: () -> Unit = { DesktopWindowBackdrop.apply() }

    /** Whether the native borderless fullscreen is what is filling the screen now. */
    private var nativeActive = false

    fun toggle() = setFullScreen(!_fullScreen.value)

    /**
     * Maximize or restore, which is what the caption button in the corner does and what
     * double-clicking the title bar has always done.
     */
    fun toggleMaximized() {
        leaveNativeFullscreen()
        _fullScreen.value = false
        set(if (_placement.value == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized)
    }

    /**
     * Leaves full screen, for the paths that can only mean "get me out" — closing the player, or
     * the window being put away to the tray.
     */
    fun exit() {
        if (_fullScreen.value) setFullScreen(false)
    }

    /** Takes the window's word for how it is sitting, rather than this object's. */
    fun adopt(placement: WindowPlacement) {
        if (placement == _placement.value) return
        if (placement == WindowPlacement.Floating) _fullScreen.value = false
        set(placement)
    }

    private fun setFullScreen(on: Boolean) {
        _fullScreen.value = on
        if (on) {
            if (nativeFullscreen(true)) {
                nativeActive = true
                return
            }
        } else if (leaveNativeFullscreen()) {
            return
        }
        set(if (on) fillsScreen else WindowPlacement.Floating)
    }

    /** Takes the native fullscreen down if it is up; true when it was (the placement was never changed for it). */
    private fun leaveNativeFullscreen(): Boolean {
        if (!nativeActive) return false
        nativeActive = false
        if (nativeFullscreen(false)) restoreBackdrop()
        return true
    }

    /** Back to a floating, windowed start, for tests. */
    internal fun resetForTest() {
        nativeActive = false
        _fullScreen.value = false
        set(WindowPlacement.Floating)
    }

    private fun set(placement: WindowPlacement) {
        _placement.value = placement
        _maximized.value = placement == WindowPlacement.Maximized
    }
}
