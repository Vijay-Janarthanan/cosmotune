package com.cosmotune.reading.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@Composable internal actual fun rememberReadingBattery(enabled: Boolean): Int? {
    var level by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(enabled) {
        if (!enabled) { level = null; return@LaunchedEffect }
        while (true) {
            level = withContext(Dispatchers.IO) { runCatching { desktopBattery() }.getOrNull() }
            // A desktop without a battery says so once; the line then leaves it out.
            if (level == null) break
            delay(120_000)
        }
    }
    return level
}

private fun desktopBattery(): Int? {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "linux" in os -> File("/sys/class/power_supply").listFiles()?.filter { it.name.startsWith("BAT") }?.sortedBy { it.name }
            ?.firstNotNullOfOrNull { readingBatteryFrom(File(it, "capacity").takeIf(File::isFile)?.readText()) }
        "mac" in os -> readingBatteryFrom(run("pmset", "-g", "batt"))
        "windows" in os -> readingBatteryFrom(run("powershell", "-NoProfile", "-NonInteractive", "-Command",
            "(Get-CimInstance -ClassName Win32_Battery | Select-Object -First 1).EstimatedChargeRemaining"))
        else -> null
    }
}

private fun run(vararg command: String): String? {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val text = process.inputStream.bufferedReader().use { it.readText() }
    return if (process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0) text else { process.destroy(); null }
}
