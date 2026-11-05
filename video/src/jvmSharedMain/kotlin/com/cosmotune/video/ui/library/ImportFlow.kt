package com.cosmotune.video.ui.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cosmotune.video.importers.ImportDetector
import com.cosmotune.video.importers.ImportFormat
import com.cosmotune.video.importers.ImportPreview
import com.cosmotune.video.importers.NewPipeDatabaseImporter
import com.cosmotune.video.importers.NewPipeSubscriptionsImporter
import com.cosmotune.video.importers.OpmlImporter
import com.cosmotune.video.importers.TakeoutArchiveImporter
import com.cosmotune.video.importers.TakeoutHistoryImporter
import com.cosmotune.video.importers.TakeoutPlaylistsImporter
import com.cosmotune.video.importers.TakeoutSubscriptionsImporter
import com.cosmotune.video.importers.TempFileSqliteOpener
import com.cosmotune.video.importers.ZipEntries
import com.cosmotune.video.importers.ZipInput
import com.cosmotune.video.library.ImportReport
import com.cosmotune.video.library.LibraryExport
import com.cosmotune.video.library.LibraryImport
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.source.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * The rows of the import page (spec 8.2): what each one asks the picker for. The picked file is then read for what it is
 * ([ImportDetector] decides on its content), so a file picked from another row still imports: the row only narrows the picker.
 */
internal enum class ImportKind(val mimeTypes: List<String>) {
    NEWPIPE_SUBSCRIPTIONS(listOf("application/json", "text/plain", "application/octet-stream")),
    NEWPIPE_BACKUP(listOf("application/zip", "application/x-zip-compressed", "application/octet-stream")),
    TAKEOUT(
        listOf(
            "application/zip", "application/x-zip-compressed", "text/csv", "text/comma-separated-values", "application/json", "text/plain",
            "application/octet-stream",
        ),
    ),
    OPML(listOf("text/x-opml", "text/xml", "application/xml", "text/plain", "application/octet-stream")),
    COSMOTUNE(listOf("application/json", "text/plain", "application/octet-stream")),
}

/**
 * A file read and waiting to be imported (spec 8.2: "every import shows a summary before it writes"): what it holds as the
 * document `VideoLibrary.importDocument` merges, the channel addresses still to look up, and how many of its entries were skipped.
 */
@Immutable
internal data class PendingImport(
    val fileName: String,
    val format: ImportFormat,
    val doc: LibraryImport,
    val lookups: List<String>,
    val skipped: Int,
) {
    /** The summary's channels. */
    val channels: Int get() = doc.subscriptions.size

    /** The summary's history entries. */
    val historyEntries: Int get() = doc.history.size

    /** The summary's playlists: your playlists and the saved ones. */
    val playlists: Int get() = doc.playlists.size + doc.savedPlaylists.size

    /** This import with [resolved]'s channels added, nothing left to look up, and the lookups that failed or repeated counted as skipped. */
    fun withResolved(resolved: Resolution): PendingImport = copy(
        doc = doc.copy(subscriptions = doc.subscriptions + resolved.subscriptions),
        lookups = emptyList(),
        skipped = skipped + resolved.failed + resolved.repeats,
    )
}

/**
 * Reads a picked file into a [PendingImport] (spec 8.2), on the caller's thread (an I/O thread): the first
 * [ImportDetector.HEAD_BYTES] bytes decide the format, then the one importer for it reads the file.
 *
 * - Text files (NewPipe's subscriptions, Takeout's CSV and JSON, OPML, Cosmotune's own export) are read whole as UTF-8.
 * - Archives (the NewPipe backup, a Takeout ZIP) are read by entry through [ZipInput] from a file: the picked file itself when it is
 *   one (the desktop), else a copy in the cache folder (deleted afterwards), else, for an archive too large to copy, the stream (one
 *   pass per entry read).
 * - The NewPipe backup: only its database is read, extracted to the cache folder, read through the platform's SQLite reader and deleted
 *   afterwards ([TempFileSqliteOpener], [NewPipeDatabaseImporter.parseBackup]).
 *
 * Null when the file is of no format the page reads, cannot be read, or holds nothing to import (the page says `video_import_failed`).
 */
internal object ImportReader {
    /** An archive larger than this is read from its stream rather than copied to the cache folder. */
    const val COPY_MAX_BYTES = 2L * 1024 * 1024 * 1024

    /** A text file larger than this is not read (the archives' own entry cap, `ZipInput.MAX_ENTRY_BYTES`). */
    const val TEXT_MAX_BYTES = ZipInput.MAX_ENTRY_BYTES

    fun read(file: PickedFile, platform: ImportPlatform, nowMs: Long): PendingImport? = try {
        val format = ImportDetector.detect(file.name, head(file))
        when (format) {
            ImportFormat.UNKNOWN -> null
            ImportFormat.NEWPIPE_DATABASE -> withZip(file, platform) { zip ->
                NewPipeDatabaseImporter.parseBackup(zip, TempFileSqliteOpener(platform.cacheDir, platform.sqlite), nowMs)
            }.pending(file.name, format)
            ImportFormat.TAKEOUT_ZIP -> withZip(file, platform) { zip -> TakeoutArchiveImporter.parse(zip, nowMs) }.pending(file.name, format)
            ImportFormat.COSMOTUNE_EXPORT -> text(file)?.let(LibraryExport::parseOrNull)
                ?.let { PendingImport(file.name, format, it, emptyList(), 0) }
                ?.takeUnless { it.channels == 0 && it.historyEntries == 0 && it.playlists == 0 && it.doc.watchLater.isEmpty() && it.doc.liked.isEmpty() && it.doc.searches.isEmpty() }
            else -> text(file)?.let { textPreview(format, file.name, it, nowMs) }.pending(file.name, format)
        }
    } catch (e: Exception) {
        null
    }

    /** The importer of a text [format]. */
    private fun textPreview(format: ImportFormat, name: String, text: String, nowMs: Long): ImportPreview? = when (format) {
        ImportFormat.NEWPIPE_SUBSCRIPTIONS -> NewPipeSubscriptionsImporter.parse(text, nowMs)
        ImportFormat.TAKEOUT_SUBSCRIPTIONS_CSV -> TakeoutSubscriptionsImporter.parseCsv(text, nowMs)
        ImportFormat.TAKEOUT_SUBSCRIPTIONS_JSON -> TakeoutSubscriptionsImporter.parseJson(text, nowMs)
        ImportFormat.TAKEOUT_HISTORY_JSON -> TakeoutHistoryImporter.parse(text)
        ImportFormat.TAKEOUT_PLAYLIST_CSV -> TakeoutPlaylistsImporter.parse(name, text, nowMs)
        ImportFormat.OPML -> OpmlImporter.parse(text, nowMs)
        else -> null
    }

    private fun ImportPreview?.pending(name: String, format: ImportFormat): PendingImport? {
        if (this == null || isEmpty) return null
        return PendingImport(name, format, toLibraryImport(), unresolvedChannels, skipped)
    }

    /** The first [ImportDetector.HEAD_BYTES] bytes of [file] (fewer when it is shorter). */
    internal fun head(file: PickedFile): ByteArray = file.open().use { input ->
        val buffer = ByteArray(ImportDetector.HEAD_BYTES)
        var filled = 0
        while (filled < buffer.size) {
            val count = input.read(buffer, filled, buffer.size - filled)
            if (count < 0) break
            filled += count
        }
        buffer.copyOf(filled)
    }

    /** The whole of [file] as UTF-8 text, or null when it is larger than [TEXT_MAX_BYTES]. */
    private fun text(file: PickedFile): String? {
        if ((file.size ?: 0L) > TEXT_MAX_BYTES) return null
        return file.open().use { input -> readCapped(input, TEXT_MAX_BYTES) }?.decodeToString()
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > cap) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /**
     * Runs [read] on the archive [file]: through the file itself, a copy in the cache folder (deleted afterwards, even on failure), or
     * the stream when the archive's size is unknown to be small enough or the cache folder has no room for it.
     */
    private fun <T> withZip(file: PickedFile, platform: ImportPlatform, read: (ZipEntries) -> T): T {
        file.file?.let { return read(ZipInput(it)) }
        val size = file.size
        val dir = platform.cacheDir
        dir.mkdirs()
        val copyable = size != null && size <= COPY_MAX_BYTES && dir.usableSpace > size + COPY_HEADROOM_BYTES
        if (!copyable) return read(ZipInput { file.open() })
        val temp = File.createTempFile("import-", ".zip", dir)
        try {
            file.open().use { input -> temp.outputStream().use { input.copyTo(it, BUFFER) } }
            return read(ZipInput(temp))
        } finally {
            temp.delete()
        }
    }

    private const val BUFFER = 64 * 1024

    /** Room the cache folder keeps beyond a copied archive. */
    private const val COPY_HEADROOM_BYTES = 64L * 1024 * 1024
}

/** What a round of lookups found (spec 8.2): the channels, as new subscriptions; the lookups that failed; the ones that named a channel already in the file. */
internal class Resolution(val subscriptions: List<Subscription>, val failed: Int, val repeats: Int)

/**
 * Looks up the channels a file names only by address (`/user/<name>`, `/c/<name>`, `/@handle`; spec 8.2: "resolved through
 * `VideoSource.channel`, one request each, reported as resolved or skipped"), at most [PARALLEL] at a time.
 */
internal object ChannelResolver {
    /** Lookups in flight at once (plan task Y-22: "at most 4 at a time, with progress"). */
    const val PARALLEL = 4

    /**
     * Looks up each of [addresses] with [lookup], at most [parallel] at a time, calling [onProgress] with the number done after each
     * one. A lookup that fails counts as failed; a channel whose id is in [known] or was found already counts as a repeat. The
     * channels come back in the order of [addresses], followed at [nowMs].
     */
    suspend fun resolve(
        addresses: List<String>,
        known: Set<String>,
        nowMs: Long,
        lookup: suspend (String) -> ChannelItem,
        onProgress: (done: Int) -> Unit = {},
        parallel: Int = PARALLEL,
    ): Resolution {
        if (addresses.isEmpty()) return Resolution(emptyList(), 0, 0)
        val permits = Semaphore(parallel.coerceAtLeast(1))
        val done = AtomicInteger(0)
        val found: List<ChannelItem?> = coroutineScope {
            addresses.map { address ->
                async {
                    val channel = permits.withPermit {
                        try {
                            lookup(address)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    onProgress(done.incrementAndGet())
                    channel
                }
            }.awaitAll()
        }
        val seen = HashSet(known)
        val subscriptions = ArrayList<Subscription>()
        var failed = 0
        var repeats = 0
        for (channel in found) {
            when {
                channel == null -> failed += 1
                !seen.add(channel.id) -> repeats += 1
                else -> subscriptions += Subscription(channel.id, channel.name, channel.avatarUrl, nowMs, null)
            }
        }
        return Resolution(subscriptions, failed, repeats)
    }
}

/** Where the import page is (spec 8.2): nothing picked, reading, looking channels up, the summary waiting, importing, done, or failed. */
internal sealed interface ImportPhase {
    data object Idle : ImportPhase

    data class Reading(val fileName: String) : ImportPhase

    data class Resolving(val pending: PendingImport, val done: Int, val total: Int) : ImportPhase

    data class Ready(val pending: PendingImport) : ImportPhase

    data class Importing(val pending: PendingImport) : ImportPhase

    data class Done(val pending: PendingImport, val report: ImportReport) : ImportPhase

    data class Failed(val fileName: String) : ImportPhase
}

/**
 * The import page's state (spec 8.1, 8.2): [read] a picked file (off the main thread, then the channel lookups with their progress),
 * show its summary ([phase]), [confirm] to merge it into the library, [dismiss] to drop it; [export] writes the library to a created
 * file. Nothing is written to the library before [confirm].
 */
@Stable
internal class ImportState(
    private val scope: CoroutineScope,
    private val library: VideoLibrary,
    private val source: VideoSource,
    private val platform: ImportPlatform,
    private val io: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var phase: ImportPhase by mutableStateOf(ImportPhase.Idle)
        private set

    private var job: Job? = null

    /** Reads [file] and shows its summary once its channels are looked up; a file that cannot be read shows the failure. */
    fun read(file: PickedFile) {
        job?.cancel()
        phase = ImportPhase.Reading(file.name)
        job = scope.launch {
            val now = clock()
            val read = withContext(io) { ImportReader.read(file, platform, now) }
            if (read == null) {
                phase = ImportPhase.Failed(file.name)
                return@launch
            }
            if (read.lookups.isEmpty()) {
                phase = ImportPhase.Ready(read)
                return@launch
            }
            val total = read.lookups.size
            phase = ImportPhase.Resolving(read, 0, total)
            val known = read.doc.subscriptions.mapTo(HashSet()) { it.channelId }
            val resolved = ChannelResolver.resolve(
                addresses = read.lookups,
                known = known,
                nowMs = now,
                lookup = { address -> source.channel(address).channel },
                onProgress = { done -> phase = ImportPhase.Resolving(read, done, total) },
            )
            phase = ImportPhase.Ready(read.withResolved(resolved))
        }
    }

    /** Merges the summary's file into the library (spec 8.2: never deletes what is there) and shows what it added. */
    fun confirm() {
        val ready = phase as? ImportPhase.Ready ?: return
        phase = ImportPhase.Importing(ready.pending)
        job = scope.launch {
            phase = try {
                val report = withContext(io) { library.importDocument(ready.pending.doc) }
                ImportPhase.Done(ready.pending, report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportPhase.Failed(ready.pending.fileName)
            }
        }
    }

    /** Drops the summary (or the outcome) and stops a read or a lookup that is running. Nothing is written. */
    fun dismiss() {
        if (phase is ImportPhase.Importing) return
        job?.cancel()
        job = null
        phase = ImportPhase.Idle
    }

    /** Writes the whole library (`exportJson`, spec 8.1) to [file] off the main thread, then tells [onDone] whether it was written. */
    fun export(file: CreatedFile, onDone: (written: Boolean) -> Unit) {
        scope.launch {
            val written = withContext(io) {
                try {
                    val json = library.exportJson()
                    file.open().use { it.write(json.encodeToByteArray()) }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            onDone(written)
        }
    }
}
