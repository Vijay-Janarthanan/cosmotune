package com.cosmotune.video.importers

/** Compatibility entry point for the removable Video-mode importers. */
object Csv {
    fun parse(text: String): List<List<String>> = com.cosmotune.data.Csv.parse(text)
}

internal fun List<String>.isBlankRecord(): Boolean = all { it.isBlank() }
