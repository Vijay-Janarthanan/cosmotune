package com.cosmotune.watch

import com.cosmotune.watch.model.Subtitle
import java.io.File

/** Where a download keeps its poster and subtitle files: beside the video, named after it ("Film.poster.jpg", "Film.en.srt"). */
object DownloadExtras {
    const val MAX_SUBTITLES = 6
    private val formats = setOf("srt", "vtt", "ass", "ssa")

    fun posterFile(video: File): File = File(video.parentFile, "${video.nameWithoutExtension}.poster.jpg")

    private fun languageOf(sub: Subtitle) = sub.language.lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(12).ifBlank { "und" }

    private fun formatOf(sub: Subtitle) = (sub.format ?: sub.url?.substringBefore('?')?.substringAfterLast('.'))?.lowercase()?.takeIf { it in formats } ?: "srt"

    fun subtitleFile(video: File, sub: Subtitle): File = File(video.parentFile, "${video.nameWithoutExtension}.${languageOf(sub)}.${formatOf(sub)}")

    /** The subtitles worth keeping: web files only, one per language (the plain one before hearing-impaired), at most [MAX_SUBTITLES]. */
    fun pick(subtitles: List<Subtitle>): List<Subtitle> = subtitles
        .filter { it.url?.startsWith("http") == true && it.trackId == null }
        .sortedBy { it.hearingImpaired }
        .distinctBy { languageOf(it) }
        .take(MAX_SUBTITLES)

    /** The subtitle files kept beside [video], with each one's language. */
    fun subtitleFiles(video: File): List<Pair<File, String>> {
        val base = video.nameWithoutExtension + "."
        return video.parentFile?.listFiles().orEmpty().filter { f ->
            f.isFile && f.name.startsWith(base) && f.extension.lowercase() in formats && f.length() > 0
        }.sortedBy { it.name }.map { it to it.name.removePrefix(base).substringBeforeLast('.').ifBlank { "und" } }
    }
}
