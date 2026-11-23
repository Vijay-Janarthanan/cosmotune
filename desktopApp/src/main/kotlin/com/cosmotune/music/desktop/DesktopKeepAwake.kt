package com.cosmotune.music.desktop

import java.util.concurrent.Executors

/**
 * Keeps the computer's display awake while music plays, when Settings, Player, "Keep the screen on" asks for more than lyrics or a
 * video (a desktop's display is otherwise left to its own idle timeout). Windows is asked through SetThreadExecutionState on one
 * thread that lives for the run; macOS through `caffeinate -d`; Linux through `systemd-inhibit --what=idle`. Where none answers,
 * nothing happens and nothing fails.
 */
internal object DesktopKeepAwake {
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    // SetThreadExecutionState holds for the thread that called it, so every call is made on this one.
    private val windowsThread by lazy { Executors.newSingleThreadExecutor { Thread(it, "cosmotune-keep-awake").apply { isDaemon = true } } }
    private var held = false
    private var process: Process? = null

    init {
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroy() })
    }

    @Synchronized
    fun hold(on: Boolean) {
        if (on == held) return
        held = on
        runCatching {
            when {
                os.contains("win") -> windowsThread.execute {
                    runCatching {
                        com.sun.jna.platform.win32.Kernel32.INSTANCE.SetThreadExecutionState(
                            if (on) ES_CONTINUOUS or ES_DISPLAY_REQUIRED or ES_SYSTEM_REQUIRED else ES_CONTINUOUS,
                        )
                    }
                }
                on -> process = ProcessBuilder(command() ?: return).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                else -> { process?.destroy(); process = null }
            }
        }.onFailure { DesktopTrackLog.log("keep awake: ${it.message}") }
    }

    private fun command(): List<String>? = when {
        os.contains("mac") -> listOf("caffeinate", "-d", "-i", "-w", ProcessHandle.current().pid().toString())
        os.contains("linux") -> listOf("systemd-inhibit", "--what=idle", "--who=Cosmotune", "--why=Music is playing", "sleep", "infinity")
        else -> null
    }
}
