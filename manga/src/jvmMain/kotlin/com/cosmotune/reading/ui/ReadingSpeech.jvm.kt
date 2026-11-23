package com.cosmotune.reading.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.io.BufferedReader
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * The desktop's own voices: Windows speech through one PowerShell kept open, macOS `say`, Linux `espeak-ng` / `espeak` / `spd-say`.
 * Each sentence is handed over as data (base64, or an argument after "--"), never as a command to run.
 */
private class DesktopReadingSpeech : ReadingSpeech {
    private val os = System.getProperty("os.name").lowercase()
    @Volatile private var shell: Process? = null
    @Volatile private var current: Process? = null
    @Volatile private var reader: BufferedReader? = null
    private val lock = Any()

    private fun linuxTool(): String? = listOf("espeak-ng", "espeak", "spd-say").firstOrNull { tool ->
        System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, tool).canExecute() }
    }

    private fun windows(): Process {
        shell?.takeIf { it.isAlive }?.let { return it }
        val d = "$"
        val script = listOf(
            "Add-Type -AssemblyName System.Speech",
            "${d}s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
            "while (${d}true) {",
            "  ${d}line = [Console]::In.ReadLine()",
            "  if (${d}line -eq ${d}null) { break }",
            "  ${d}p = ${d}line.Split('|')",
            "  if (${d}p[0] -eq 'VOICES') { foreach (${d}v in ${d}s.GetInstalledVoices()) { if (${d}v.Enabled) { [Console]::Out.WriteLine(${d}v.VoiceInfo.Name + '|' + ${d}v.VoiceInfo.Culture.Name) } }; [Console]::Out.WriteLine('END'); [Console]::Out.Flush(); continue }",
            "  if (${d}p[1] -ne '') { try { ${d}s.SelectVoice([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${d}p[1]))) } catch {} }",
            "  ${d}s.Rate = [int]${d}p[2]",
            "  ${d}s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${d}p[3])))",
            "  [Console]::Out.WriteLine('DONE'); [Console]::Out.Flush()",
            "}",
        ).joinToString("\n")
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).start()
        process.errorStream.close()
        shell = process
        reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        return process
    }

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    override suspend fun voices(): List<ReadingVoiceInfo> = withContext(Dispatchers.IO) {
        runCatching {
            when {
                "win" in os -> synchronized(lock) {
                    val process = windows()
                    process.outputStream.write("VOICES\n".toByteArray()); process.outputStream.flush()
                    generateSequence { reader?.readLine() }.takeWhile { it != "END" }.map { line ->
                        ReadingVoiceInfo(line.substringBefore('|'), line.substringBefore('|'), line.substringAfter('|', ""))
                    }.toList()
                }
                "mac" in os -> ProcessBuilder("say", "-v", "?").start().inputStream.bufferedReader().readLines().mapNotNull { line ->
                    val match = Regex("^(.+?)\\s{2,}([a-z]{2}[_-][A-Za-z0-9]+)").find(line) ?: return@mapNotNull null
                    ReadingVoiceInfo(match.groupValues[1].trim(), match.groupValues[1].trim(), match.groupValues[2].replace('_', '-'))
                }
                else -> when (val tool = linuxTool()) {
                    "espeak-ng", "espeak" -> ProcessBuilder(tool, "--voices").start().inputStream.bufferedReader().readLines().drop(1).mapNotNull { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size < 4) null else ReadingVoiceInfo(parts[3], parts[3], parts[1])
                    }
                    else -> emptyList()
                }
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun speak(text: String, voice: String, rate: Float) {
        val clean = text.replace('\n', ' ').trim()
        if (clean.isEmpty()) return
        if ("win" in os) {
            withContext(Dispatchers.IO) {
                try {
                    runInterruptible {
                        synchronized(lock) {
                            val process = windows()
                            val speed = ((rate - 1f) * 10f).toInt().coerceIn(-10, 10)
                            process.outputStream.write("SAY|${b64(voice)}|$speed|${b64(clean)}\n".toByteArray()); process.outputStream.flush()
                            while (true) { val line = reader?.readLine() ?: break; if (line == "DONE") break }
                        }
                    }
                } catch (e: CancellationException) { stop(); throw e }
            }
            return
        }
        val words = (175 * rate).toInt().coerceIn(80, 500)
        val command = if ("mac" in os) listOf("say", "-r", words.toString()) + (if (voice.isNotBlank()) listOf("-v", voice) else emptyList()) + listOf("--", clean)
        else when (val tool = linuxTool() ?: error("Install espeak-ng to have books read aloud.")) {
            "spd-say" -> listOf("spd-say", "-w", "-r", ((rate - 1f) * 100).toInt().coerceIn(-100, 100).toString(), "--", clean)
            else -> listOf(tool, "-s", words.toString()) + (if (voice.isNotBlank()) listOf("-v", voice) else emptyList()) + listOf("--", clean)
        }
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            current = process
            try { runInterruptible { while (!process.waitFor(200, TimeUnit.MILLISECONDS)) Unit } }
            finally { if (process.isAlive) process.destroy(); current = null }
        }
    }

    override fun stop() {
        current?.destroy()
        // Windows' synthesizer cannot be cut off from outside mid-sentence; ending its shell stops it at once (the next sentence starts a new one).
        shell?.let { it.destroy(); shell = null; reader = null }
    }
    override fun close() = stop()
}

@Composable internal actual fun rememberReadingSpeech(): ReadingSpeech? {
    val speech = remember { DesktopReadingSpeech() }
    DisposableEffect(speech) { onDispose { speech.close() } }
    return speech
}
