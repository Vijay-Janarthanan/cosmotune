package com.cosmotune.video.library

/**
 * Where the library's JSON files live (spec 8.1, decision V8): a flat folder of small text files, addressed by name
 * (`history.json`). The real one is [FileLibraryFiles] in the app's own folder (Android `filesDir/video/`, desktop
 * `~/.local/share/cosmotune/video/`); tests use one in memory. The stores call it from one thread at a time.
 */
interface LibraryFiles {
    /** The text of [name], or null when there is no such file. */
    fun read(name: String): String?

    /** Replaces [name] with [text]. An implementation writes so that a crash leaves the old text or the new, never half of it. */
    fun write(name: String, text: String)

    /**
     * Sets [name] aside as [newName] (spec 8.1: a file that cannot be read is kept as `<name>.broken-<time>`). Does nothing when
     * [name] does not exist. This default copies the text, so a file system should override it with a real move: the original
     * is then gone and the store starts again from an empty document.
     */
    fun rename(name: String, newName: String) {
        val text = read(name) ?: return
        write(newName, text)
    }
}
