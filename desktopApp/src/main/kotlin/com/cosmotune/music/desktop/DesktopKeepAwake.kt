package com.cosmotune.music.desktop

import java.util.concurrent.Executors

/**
 * Keeps the computer awake for two reasons, held together:
 * - [playing]: while something plays the computer does not go to sleep (Kasts' "prevent suspend"); the screen may still turn off.
 * - [hold]: Settings, Player, "Keep the screen on" asks for more than lyrics or a video, so the display stays on too.
 *
 * Windows is asked through SetThreadExecutionState on one thread that lives for the run (the state belongs to the thread that set
 * it); macOS through `caffeinate` (`-i`, plus `-d` for the display); Linux through `systemd-inhibit` (sleep, plus idle for the
 * display). Where none answers, nothing happens and nothing fails. Nothing is held while neither asks.
 */
internal object DesktopKeepAwake {
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val windowsThread by lazy { Executors.newSingleThreadExecutor { Thread(it, "cosmotune-keep-awake").apply { isDaemon = true } } }
    private var process: Process? = null
    private var sleepHeld = false
    private var displayHeld = false
    private var applied: Pair<Boolean, Boolean> = false to false

    init {
        Runtime.getRuntime().addShutdownHook(Thread { process?.destroy() })
    }

    /** Something plays (true) or nothing does: the computer is kept from sleeping while it plays. */
    @Synchronized fun playing(on: Boolean) { sleepHeld = on; apply() }

    /** The listener's "Keep the screen on" asks for the display to stay on while music plays. */
    @Synchronized fun hold(on: Boolean) { displayHeld = on; apply() }

    private fun apply() {
        val wanted = (sleepHeld || displayHeld) to displayHeld
        if (wanted == applied) return
        applied = wanted
        val (system, display) = wanted
        runCatching {
            if (os.contains("win")) windowsThread.execute {
                runCatching {
                    com.sun.jna.platform.win32.Kernel32.INSTANCE.SetThreadExecutionState(
                        ES_CONTINUOUS or (if (system) ES_SYSTEM_REQUIRED else 0) or (if (display) ES_DISPLAY_REQUIRED else 0),
                    )
                }
            } else {
                process?.destroy(); process = null
                if (system) process = command(display)?.let {
                    ProcessBuilder(it).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                }
            }
        }.onFailure { DesktopTrackLog.log("keep awake: ${it.message}") }
    }

    private fun command(display: Boolean): List<String>? {
        val pid = ProcessHandle.current().pid().toString()
        return when {
            os.contains("mac") -> listOf("caffeinate") + (if (display) listOf("-d") else emptyList()) + listOf("-i", "-w", pid)
            os.contains("linux") -> listOf("systemd-inhibit", "--what=" + if (display) "sleep:idle" else "sleep", "--who=Cosmotune", "--why=Playing",
                "--mode=block", "tail", "--pid=$pid", "-f", "/dev/null")
            else -> null
        }
    }
}
