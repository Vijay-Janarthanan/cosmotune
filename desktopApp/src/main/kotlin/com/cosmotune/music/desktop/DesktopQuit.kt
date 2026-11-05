package com.cosmotune.music.desktop

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The one way the application ends, for the tray's Quit and for Windows asking it to close (an installer replacing the
 * files, a shutdown).
 *
 * Quitting runs native work first (the audio thread, the media controls) and then ends the process, and either step
 * can fail to return: a shutdown hook that waits, a native library that does not give a thread back. A Quit that
 * left the process running kept the installer's files locked, so the process is ended by force when the orderly way
 * has not finished in [deadlineMs].
 */
internal object DesktopQuit {
    @Volatile private var orderly: (() -> Unit)? = null
    private val started = AtomicBoolean(false)

    /** How long the orderly way gets. Replaceable by tests, which must not end their own process. */
    internal var deadlineMs = 4_000L
    internal var force: () -> Unit = { Runtime.getRuntime().halt(0) }
    internal var end: () -> Unit = { exitProcess(0) }

    /** What quitting does before the process ends: stop the tray, the audio engine and the media controls. */
    fun bind(action: () -> Unit) { orderly = action }

    /** Starts quitting. Only the first call does anything, so a Quit and a close request together cannot overlap. */
    fun now(reason: String) {
        if (!started.compareAndSet(false, true)) return
        DesktopTrackLog.log("quitting: $reason")
        val guard = Thread({
            try {
                Thread.sleep(deadlineMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            DesktopTrackLog.log("quitting did not finish within $deadlineMs ms; ending the process")
            force()
        }, "cosmotune-quit-deadline").apply { isDaemon = true; start() }
        runCatching { orderly?.invoke() }
            .onFailure { DesktopTrackLog.log("quitting: ${it.javaClass.simpleName}: ${it.message}") }
        end()
        // Only reached when ending the process returned (a test's stand-in): the deadline has nothing left to enforce.
        guard.interrupt()
    }

    /** For tests: allows another [now]. */
    internal fun reset() { started.set(false); orderly = null }
}
