package com.cosmotune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.settings.CosmoLookSettings
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Watch's one-tap install links (`stremio://…`, `cosmotune://watch/…`) on Windows and Linux: Cosmotune is made the app that opens
 * them only when the person presses "Set as handler" on the Watch Plugins page, never on its own. On Windows that writes the two
 * schemes under the user's own `HKCU\Software\Classes` (no administrator rights, nothing machine-wide); on Linux it writes a
 * `cosmotune-links.desktop` entry under `~/.local/share/applications` and makes it the default for the two schemes through
 * `xdg-mime`. The AppImage's own desktop entry declares the schemes too. macOS reads them from the bundle's URL types. The app's
 * launcher is known only in the installed app (jpackage's `jpackage.app-path`, the AppImage's `APPIMAGE`), so a development run
 * offers nothing.
 */
object DesktopWatchLinks {
    private val schemes = listOf("stremio" to "URL:Stremio add-on link", "cosmotune" to "URL:Cosmotune link")
    private const val LINUX_ENTRY = "cosmotune-links.desktop"

    private val os = System.getProperty("os.name").orEmpty().lowercase()
    private val windows = os.startsWith("windows")
    private val linux = os.contains("linux")

    /** The installed app's launcher, or null in a development run. */
    private fun launcher(): String? =
        (System.getenv("APPIMAGE")?.takeIf { linux } ?: System.getProperty("jpackage.app-path"))?.takeIf { File(it).isFile }

    /** Whether this desktop can be told to open the links with Cosmotune (Windows or Linux, installed). */
    val available: Boolean get() = (windows || linux) && launcher() != null

    private fun windowsCommand(app: String) = "\"$app\" \"%1\""

    /** Whether both schemes already open this app. Reads only. */
    fun isSet(): Boolean = runCatching {
        val app = launcher() ?: return false
        when {
            windows -> schemes.all { (scheme, _) ->
                val key = "Software\\Classes\\$scheme\\shell\\open\\command"
                Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, key) &&
                    Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, key, "") == windowsCommand(app)
            }
            linux -> schemes.all { (scheme, _) -> run("xdg-mime", "query", "default", "x-scheme-handler/$scheme")?.trim() == LINUX_ENTRY }
            else -> false
        }
    }.getOrDefault(false)

    /** Makes Cosmotune open both schemes for this user; the message to show when it could not. Called only from the button. */
    fun set(): String? = runCatching {
        val app = launcher() ?: return "Only the installed app can open links."
        when {
            windows -> {
                for ((scheme, name) in schemes) {
                    val root = "Software\\Classes\\$scheme"
                    Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "$root\\shell\\open\\command")
                    Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, root, "", name)
                    Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, root, "URL Protocol", "")
                    Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, "$root\\shell\\open\\command", "", windowsCommand(app))
                }
                null
            }
            linux -> {
                val dir = File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"), "applications")
                dir.mkdirs()
                File(dir, LINUX_ENTRY).writeText(
                    """
                    [Desktop Entry]
                    Type=Application
                    Name=Cosmotune
                    Comment=Opens Watch add-on links in Cosmotune
                    Exec="${app.replace("\"", "\\\"")}" %u
                    Terminal=false
                    NoDisplay=true
                    MimeType=${schemes.joinToString("") { "x-scheme-handler/${it.first};" }}
                    """.trimIndent() + "\n",
                )
                run("update-desktop-database", dir.path)
                val failed = schemes.filter { (scheme, _) -> run("xdg-mime", "default", LINUX_ENTRY, "x-scheme-handler/$scheme") == null }
                if (failed.isEmpty()) null else "xdg-mime is missing, so the desktop was not told."
            }
            else -> "This desktop opens links through the app bundle."
        }
    }.getOrElse { it.message ?: "Could not set Cosmotune as the handler." }

    /** Runs a short command; its output, or null when it failed or is missing. */
    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); return null }
        out.takeIf { process.exitValue() == 0 }
    }.getOrNull()
}

/**
 * A Watch install or pack link that reached the app (the command line, the system's URL handler, a second launch) makes Watch the
 * mode, where its sheet opens. Called once by the window.
 */
@Composable
fun FollowWatchLinks(settings: CosmoLookSettings?) {
    val link by com.cosmotune.watch.WatchLinks.pending.collectAsState()
    LaunchedEffect(link != null, settings) {
        if (link != null && settings != null && AppModes.available(AppMode.WATCH) && settings.mode.value != AppMode.WATCH) settings.setMode(AppMode.WATCH)
    }
}
