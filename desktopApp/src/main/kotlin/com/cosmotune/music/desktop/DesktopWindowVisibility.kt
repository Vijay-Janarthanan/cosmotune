@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.cosmotune.music.desktop

import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.awt.desktop.AppReopenedListener
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the window is on screen, and what closing it means. */
internal object DesktopWindowVisibility {

    private val _visible = MutableStateFlow(true)

    /** What the window's own `visible` is driven from. */
    val visible: StateFlow<Boolean> = _visible

    /** Whether a close should hide rather than quit. */
    @Volatile
    var keepRunningWhenClosed: Boolean = false

    private var windowRef: WeakReference<Window>? = null

    private val _generation = MutableStateFlow(0)

    /** Bumped to put up a fresh main window in place of one that is gone. */
    val generation: StateFlow<Int> = _generation

    private val _crashed = MutableStateFlow(false)

    /** True while there is no main window because its page failed twice in a row; [show] puts one back. */
    val crashed: StateFlow<Boolean> = _crashed

    @Volatile private var lastCrashAtMs = 0L

    /**
     * The main window's page threw. Compose's own answer (an error box, then the window closed, which with the tray on only hid
     * it, its page dead) left a process with no usable window that still held the single-instance lock. Logged; the failed
     * window goes and a fresh one comes up at once, unless the last one failed under a minute ago: then none until the next
     * launch or the tray asks for it ([show]), so a page that fails at once is not reopened in a loop.
     */
    val crashHandler = androidx.compose.ui.window.WindowExceptionHandlerFactory { _ ->
        androidx.compose.ui.window.WindowExceptionHandler { failure ->
            com.cosmotune.diagnostics.CrashNotes.record(failure)
            DesktopTrackLog.log("the window's page failed: ${failure.javaClass.name}: ${failure.message}\n" +
                failure.stackTrace.take(20).joinToString("\n") { "    at $it" })
            EventQueue.invokeLater { windowFailed() }
        }
    }

    private fun windowFailed() {
        windowRef = null
        val now = System.currentTimeMillis()
        val again = now - lastCrashAtMs < CRASH_LOOP_MS
        lastCrashAtMs = now
        if (again) _crashed.value = true else _generation.value++
    }

    private const val CRASH_LOOP_MS = 60_000L
    private var isListenerInstalled = false
    private var activationPending = false // Accessed only on AWT's event thread.

    /** Attaches the active Compose window so show() can bring it to the foreground. */
    fun attachWindow(window: Window) {
        windowRef = WeakReference(window)
        if (activationPending) show()
    }

    /**
     * Installs system-level reopen hooks (e.g. macOS Dock click when window is closed).
     */
    fun install() {
        if (isListenerInstalled) return
        isListenerInstalled = true

        if (DesktopPlatform.isMac && Desktop.isDesktopSupported()) {
            runCatching {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                    desktop.addAppEventListener(AppReopenedListener {
                        EventQueue.invokeLater {
                            DesktopTrackLog.log("AppReopenedEvent received from macOS Dock")
                            show()
                        }
                    })
                    DesktopTrackLog.log("DesktopWindowVisibility: AppReopenedListener registered")
                }
            }.onFailure {
                DesktopTrackLog.log("DesktopWindowVisibility: failed to register AppReopenedListener: ${it.message}")
            }
        }
    }

    /** Brings the window back — from the Dock, tray menu, or the icon itself. */
    fun show() {
        _visible.value = true
        EventQueue.invokeLater {
            // No window to show (its page failed, or it was disposed): put up a fresh one, which shows itself when attached.
            val gone = windowRef?.get()?.isDisplayable == false
            if (_crashed.value || gone) {
                windowRef = null
                _crashed.value = false
                _generation.value++
            }
            val win = windowRef?.get()
            activationPending = win == null
            if (win != null) {
                if (win is Frame && (win.extendedState and Frame.ICONIFIED) != 0) {
                    win.extendedState = win.extendedState and Frame.ICONIFIED.inv()
                }
                win.isVisible = true
                win.toFront()
                win.requestFocus()
            }
            if (DesktopPlatform.isMac) {
                runCatching {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                        Desktop.getDesktop().requestForeground(true)
                    }
                }
                DesktopMacFrame.activateApp()
            }
        }
    }

    /**
     * Handles the window's close request.
     * @return true when the caller should end the application; false when the
     */
    fun onCloseRequest(): Boolean {
        if (!keepRunningWhenClosed) return true
        // A window put away full-screen comes back full-screen with nothing on it, which reads as
        // the app having broken rather than as it having been hidden.
        DesktopWindowMode.exit()
        _visible.value = false
        DesktopTrackLog.log("window closed to the tray; playback continues")
        return false
    }
}
