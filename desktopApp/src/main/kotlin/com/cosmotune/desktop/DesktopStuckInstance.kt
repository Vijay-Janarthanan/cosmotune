package com.cosmotune.desktop

import java.util.concurrent.TimeUnit
import javax.swing.JOptionPane
import javax.swing.UIManager

/**
 * What a launch says when Cosmotune is already open but does not answer (its window stuck, or its files replaced by an update
 * under it): a plain question instead of the Windows launcher's "Failed to launch JVM". "Close it and open again" ends that
 * process (it is the one that holds Cosmotune's lock and wrote its id beside it) and lets this launch start; "Cancel" leaves it.
 */
internal object DesktopStuckInstance {
    /** True when the stuck process [pid] was closed, so this launch may go on to start. */
    fun offerToClose(pid: Long?): Boolean {
        val process = pid?.let { ProcessHandle.of(it).orElse(null) }?.takeIf { it.isAlive }
        val choice = runCatching {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val options = if (process != null) arrayOf("Close it and open again", "Cancel") else arrayOf("OK")
            JOptionPane.showOptionDialog(
                null,
                if (process != null) "Cosmotune is already open but is not responding." else
                    "Cosmotune is already open but is not responding. Close it from the tray or Task Manager, then open it again.",
                "Cosmotune",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[0],
            )
        }.getOrDefault(-1)
        if (process == null || choice != 0) return false
        process.destroy()
        val ended = runCatching { process.onExit().get(5, TimeUnit.SECONDS); true }.getOrDefault(false)
        if (!ended) {
            process.destroyForcibly()
            runCatching { process.onExit().get(5, TimeUnit.SECONDS) }
        }
        return !process.isAlive
    }
}
