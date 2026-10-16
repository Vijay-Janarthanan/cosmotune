package com.cosmotune.video.library

/**
 * The folder the library keeps its documents in (spec 8.1): one text per file name. This declaration is plan task Y-7's
 * (`LibraryFiles.kt`); the subscription feed (Y-9) keeps `feed-cache.json` through it and was built before that branch
 * merged, so the declaration is repeated here with the signature the plan gives. When Y-7 merges, its file replaces this one.
 */
interface LibraryFiles {
    /** The text of [name], or null when there is no such file. */
    fun read(name: String): String?

    /** Replaces the text of [name] (the real one writes `<name>.tmp` and renames it). */
    fun write(name: String, text: String)
}
