package com.cosmotune.music.data.lyrics

import java.io.File

/**
 * The common "Song.flac" + "Song.lrc" convention for lyrics kept beside a local file. Read only;
 * bounded so a damaged or enormous file cannot be loaded into memory.
 */
object LrcSidecar {
    private const val MAX_BYTES = 2L * 1024 * 1024

    fun fileFor(audio: File): File? {
        val parent = audio.absoluteFile.parentFile ?: return null
        val stem = audio.name.substringBeforeLast('.', audio.name)
        if (stem.isBlank() || audio.extension.equals("lrc", true) || audio.extension.equals("m3u8", true)) return null
        val exact = File(parent, "$stem.lrc")
        if (exact.isFile) return exact
        // Case-insensitive file systems already matched above; elsewhere accept ".LRC" and similar.
        return parent.listFiles { f -> f.isFile && f.name.length == stem.length + 4 && f.name.equals("$stem.lrc", ignoreCase = true) }?.firstOrNull()
    }

    /** The sidecar's text when it holds at least one timed or plain lyric line. */
    fun read(audio: File): String? {
        val file = fileFor(audio) ?: return null
        if (file.length() !in 1..MAX_BYTES) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.removePrefix("\uFEFF") ?: return null
        return text.takeIf { raw -> raw.lineSequence().any { it.isNotBlank() && !it.trimStart().startsWith("[") || Regex("""^\s*\[\d+:\d""").containsMatchIn(it) } }
    }
}
