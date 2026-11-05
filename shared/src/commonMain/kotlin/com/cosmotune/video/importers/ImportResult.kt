package com.cosmotune.video.importers

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.LibraryImport
import com.cosmotune.video.library.LocalPlaylist
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.model.PlaylistItem

/**
 * What an importer read, before anything is written (spec 8.2: "every import shows a summary before it writes"):
 * "123 channels, 4,210 history entries, 6 playlists; 3 skipped". [toLibraryImport] gives the document that
 * `VideoLibrary.importDocument` merges (it never deletes what is there).
 *
 * - [unresolvedChannels]: addresses of channels the file names without an id (`/user/<name>`, `/c/<name>`, `/@handle`); the
 *   import screen resolves each through `VideoSource.channel(addressOrId)` (one request each, spec 8.2) and subscribes to the
 *   channel it finds. They are full addresses, so the source can take them as they are.
 * - [skipped]: entries of the file that did not become a new entry: another service's, an ad or a removed video, an unusable
 *   address, a row that is not a channel or video, and a duplicate within the file.
 * - [reason]: set when the preview is empty and the importer knows why; null otherwise.
 */
data class ImportPreview(
    val subscriptions: List<Subscription> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val savedPlaylists: List<PlaylistItem> = emptyList(),
    val searches: List<String> = emptyList(),
    val unresolvedChannels: List<String> = emptyList(),
    val skipped: Int = 0,
    val reason: ImportReason? = null,
) {
    /** Whether the file gave nothing to import (not even channels to look up). */
    val isEmpty: Boolean
        get() = subscriptions.isEmpty() && history.isEmpty() && playlists.isEmpty() && savedPlaylists.isEmpty() &&
            searches.isEmpty() && unresolvedChannels.isEmpty()

    /** The document `VideoLibrary.importDocument` takes. An import never fills watch later or liked videos. */
    fun toLibraryImport(): LibraryImport = LibraryImport(
        subscriptions = subscriptions,
        history = history,
        playlists = playlists,
        savedPlaylists = savedPlaylists,
        watchLater = emptyList(),
        liked = emptyList(),
        searches = searches,
    )

    /** This preview and [other] as one (a Takeout archive gives several): lists joined, [skipped] added, the first [reason] kept. */
    operator fun plus(other: ImportPreview): ImportPreview = ImportPreview(
        subscriptions = subscriptions + other.subscriptions,
        history = history + other.history,
        playlists = playlists + other.playlists,
        savedPlaylists = savedPlaylists + other.savedPlaylists,
        searches = searches + other.searches,
        unresolvedChannels = unresolvedChannels + other.unresolvedChannels,
        skipped = skipped + other.skipped,
        reason = reason ?: other.reason,
    )

    companion object {
        /** An empty preview that says why. */
        fun failed(reason: ImportReason, skipped: Int = 0): ImportPreview = ImportPreview(skipped = skipped, reason = reason)
    }
}

/** Why a preview is empty (the import screen shows `video_import_failed` for all of them; the reason is for the log and tests). */
enum class ImportReason {
    /** The text is not what the importer reads at all (broken JSON, not a CSV of the expected shape). */
    UNREADABLE,

    /** The file was read, but nothing in it can be imported. */
    NOTHING_FOUND,

    /** A NewPipe backup without `newpipe.db` (spec 8.2: only the database is read). */
    NO_DATABASE,

    /** `newpipe.db` could not be opened or none of its tables could be read. */
    DATABASE_UNREADABLE,
}

/**
 * The files of an archive by name (spec 8.2: the Takeout ZIP and the NewPipe backup). The importers read only the entries
 * they need, one at a time. The JVM implementation is `ZipInput`.
 */
interface ZipEntries {
    /** The names of the file entries (folders left out), in archive order, with `/` as the separator. */
    fun names(): List<String>

    /** The content of entry [name], or null when the archive has no such file or it is too large to hold in memory. */
    fun read(name: String): ByteArray?
}

/** [preview] with [ImportReason.NOTHING_FOUND] when it holds nothing and has no reason yet. */
internal fun ImportPreview.explainedIfEmpty(): ImportPreview =
    if (isEmpty && reason == null) copy(reason = ImportReason.NOTHING_FOUND) else this
