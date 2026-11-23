package com.cosmotune.music.desktop

import com.cosmotune.listening.GlobalShortcuts
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import kotlin.concurrent.thread

/**
 * Settings, Player, Shortcuts in any app: the [GlobalShortcuts] registered with Windows ([User32.RegisterHotKey]) on a
 * thread of their own that waits for them, so they reach Cosmotune whichever app is in front. Elsewhere the system's
 * media keys already do (MPRIS on Linux, the Now Playing keys on macOS), so this does nothing there.
 */
internal object DesktopGlobalHotkeys {
    private const val MOD_ALT = 0x0001
    private const val MOD_CONTROL = 0x0002
    private const val MOD_NOREPEAT = 0x4000
    private const val WM_HOTKEY = 0x0312
    private const val WM_QUIT = 0x0012

    @Volatile private var threadId = 0
    @Volatile private var worker: Thread? = null

    /** Whether this system takes them. */
    val supported: Boolean get() = DesktopPlatform.isWindows

    /** Registers the shortcuts; each press calls [onAction] on the waiting thread. A second call replaces the first. */
    @Synchronized
    fun start(onAction: (GlobalShortcuts.Action) -> Unit) {
        stop()
        if (!supported) return
        worker = thread(name = "cosmotune-global-shortcuts", isDaemon = true) {
            val user32 = runCatching { User32.INSTANCE }.getOrNull() ?: return@thread
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val taken = GlobalShortcuts.BINDINGS.mapIndexedNotNull { index, binding ->
                val ok = user32.RegisterHotKey(null, index + 1, MOD_CONTROL or MOD_ALT or MOD_NOREPEAT, binding.windowsKey)
                if (!ok) DesktopTrackLog.log("shortcuts: ${binding.label} is taken by another app")
                (index + 1).takeIf { ok }
            }
            try {
                val message = WinUser.MSG()
                while (user32.GetMessage(message, null, 0, 0) > 0) {
                    if (message.message == WM_HOTKEY) {
                        GlobalShortcuts.BINDINGS.getOrNull(message.wParam.toInt() - 1)?.let { binding ->
                            runCatching { onAction(binding.action) }
                        }
                    }
                }
            } finally {
                taken.forEach { user32.UnregisterHotKey(null, it) }
                threadId = 0
            }
        }
    }

    /** Lets the shortcuts go. */
    @Synchronized
    fun stop() {
        // The waiting thread names itself as it starts; give it a moment if it has only just been started.
        var waited = 0
        while (worker?.isAlive == true && threadId == 0 && waited < 20) { Thread.sleep(10); waited++ }
        val id = threadId
        if (id != 0) runCatching { User32.INSTANCE.PostThreadMessage(id, WM_QUIT, com.sun.jna.platform.win32.WinDef.WPARAM(0), com.sun.jna.platform.win32.WinDef.LPARAM(0)) }
        worker?.join(1_000)
        worker = null
    }
}
