package com.cosmotune.music.desktop

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors

/**
 * Keeps the computer from going to sleep while something plays (Kasts' "prevent suspend"): on Windows the playing thread's
 * execution state asks for the system to stay up, on macOS `caffeinate` holds an assertion for as long as it runs, and on Linux
 * `systemd-inhibit` does the same. The screen may still turn off; only sleep is held off. Nothing is held while paused.
 */
internal object DesktopKeepAwake {
    private val os = System.getProperty("os.name").lowercase()
    // SetThreadExecutionState belongs to the thread that called it, so one thread makes every call.
    private val windowsThread by lazy { Executors.newSingleThreadExecutor { Thread(it, "Cosmotune-KeepAwake").apply { isDaemon = true } } }
    private var process: Process? = null
    private var held = false

    @Synchronized fun playing(on: Boolean) {
        if (on == held) return
        held = on
        runCatching {
            when {
                os.contains("win") -> windowsThread.execute {
                    runCatching { Kernel.INSTANCE.SetThreadExecutionState(if (on) ES_CONTINUOUS or ES_SYSTEM_REQUIRED else ES_CONTINUOUS) }
                }
                on -> process = startHolder()
                else -> { process?.destroy(); process = null }
            }
        }
    }

    private fun startHolder(): Process? {
        val pid = ProcessHandle.current().pid().toString()
        val command = when {
            os.contains("mac") -> listOf("caffeinate", "-i", "-w", pid)
            else -> listOf("systemd-inhibit", "--what=sleep", "--who=Cosmotune", "--why=Playing", "--mode=block", "tail", "--pid=$pid", "-f", "/dev/null")
        }
        return runCatching { ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start() }.getOrNull()
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    @Suppress("FunctionName")
    private interface Kernel : StdCallLibrary {
        fun SetThreadExecutionState(flags: Int): Int
        companion object { val INSTANCE: Kernel by lazy { Native.load("kernel32", Kernel::class.java) } }
    }
}
