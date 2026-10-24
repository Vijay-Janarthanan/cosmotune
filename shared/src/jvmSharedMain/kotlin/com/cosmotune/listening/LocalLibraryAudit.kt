package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class LibraryFileState { READABLE, EMPTY, MISSING, ACCESS_REQUIRED, UNSUPPORTED, CHANGED, FAILED }
enum class LibraryDuplicateKind { SAME_FILE, IDENTICAL_BYTES, METADATA_CANDIDATES }

/** A native platform may supply content-provider access; HTTP streams are never scanned. */
interface LocalLibraryAccess {
    fun inspect(song: Song): LocalLibraryFile
}

data class LocalLibraryFile(
    val identity: String,
    val bytes: Long?,
    val modified: Long?,
    val open: () -> InputStream,
)

object FileLibraryAccess : LocalLibraryAccess {
    override fun inspect(song: Song): LocalLibraryFile {
        val candidates = buildList {
            song.localPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf(File::isAbsolute)?.let(::add)
            song.localUri?.let { raw -> runCatching {
                val uri = URI(raw)
                require(uri.scheme.equals("file", true) && uri.query == null && uri.fragment == null)
                File(uri).takeIf(File::isAbsolute)
            }.getOrNull() }?.let(::add)
        }
        val file = candidates.firstOrNull { it.isFile } ?: candidates.firstOrNull()
            ?: throw UnsupportedOperationException("This location needs platform access")
        val path = file.toPath().toRealPath()
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        require(attrs.isRegularFile) { "This location is not an audio file" }
        if (!Files.isReadable(path)) throw SecurityException("Audio permission is required")
        return LocalLibraryFile(path.toString(), attrs.size(), attrs.lastModifiedTime().toMillis()) {
            Files.newInputStream(path)
        }
    }
}

data class LibraryAuditEntry(
    val song: Song,
    val state: LibraryFileState,
    val identity: String? = null,
    val bytes: Long? = null,
    val format: String? = null,
    val sha256: String? = null,
)

data class LibraryDuplicateGroup(val kind: LibraryDuplicateKind, val indices: List<Int>)
data class LibraryAuditProgress(val checked: Int, val total: Int, val hashedBytes: Long)

/** Keep an actionable missing location without exporting a remote URL or URI query credentials. */
private fun reportedLocalLocation(song: Song): String? {
    song.localPath?.takeIf { it.length <= 16_384 && File(it).isAbsolute }?.let { return it }
    return song.localUri?.takeIf { it.length <= 16_384 }?.let { value -> runCatching {
        val uri = URI(value)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("file", "content") && !uri.isOpaque && uri.userInfo == null)
        URI(uri.scheme, uri.authority, uri.path, null, null).toASCIIString()
    }.getOrNull() }
}
data class LibraryAuditReport(
    val entries: List<LibraryAuditEntry>,
    val duplicates: List<LibraryDuplicateGroup>,
    val hashedBytes: Long,
    val hashBudgetReached: Boolean,
) {
    /** Explicit export includes local paths, but never account tokens or remote stream URLs. */
    fun export(): String {
        // Bound escaped JSON before allocating the complete document.
        var estimatedBytes = 1024L + duplicates.sumOf { 128L + it.indices.size * 12L }
        entries.forEach { entry ->
            estimatedBytes += 512 + listOf(entry.song.videoId.take(4096), entry.song.title.take(1000),
                entry.song.artist.take(1000), entry.identity.orEmpty().take(16_384)).sumOf {
                JsonPrimitive(it).toString().toByteArray(Charsets.UTF_8).size.toLong()
            }
            require(estimatedBytes <= 16 * 1024 * 1024) { "The review report exceeds 16 MB. Export a smaller library selection." }
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("version", 1); put("hashedBytes", hashedBytes); put("hashBudgetReached", hashBudgetReached)
        putJsonArray("files") { entries.forEach { entry -> add(buildJsonObject {
            put("id", entry.song.videoId.take(4096)); put("title", entry.song.title.take(1000))
            put("artist", entry.song.artist.take(1000)); put("state", entry.state.name)
            entry.identity?.let { put("localLocation", it.take(16_384)) }
            entry.bytes?.let { put("bytes", it) }; entry.format?.let { put("detectedFormat", it) }
            entry.sha256?.let { put("sha256", it) }
        }) } }
        putJsonArray("duplicates") { duplicates.forEach { group -> add(buildJsonObject {
            put("kind", group.kind.name); putJsonArray("fileIndices") { group.indices.forEach { add(it) } }
        }) } }
        }).also { require(it.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) { "The review report exceeds 16 MB" } }
    }
}

/**
 * Bounded read-only review. Hashes establish identical files, not recording identity
 * across different encodings. Title matches are kept explicitly separate.
 */
suspend fun auditLocalLibrary(
    songs: List<Song>,
    access: LocalLibraryAccess = FileLibraryAccess,
    verifyBytes: Boolean = false,
    hashBudget: Long = 2L * 1024 * 1024 * 1024,
    progress: (LibraryAuditProgress) -> Unit = {},
): LibraryAuditReport = withContext(Dispatchers.IO) {
    require(songs.size <= 10_000) { "Review at most 10,000 library entries at a time" }
    require(hashBudget in 0..(16L * 1024 * 1024 * 1024)) { "Invalid comparison budget" }
    val selected = songs.filter { it.localPath != null || it.localUri != null }
        .distinctBy { Triple(it.videoId, it.localPath, it.localUri) }
    val handles = HashMap<Int, LocalLibraryFile>()
    val entries = selected.mapIndexed { index, song ->
        currentCoroutineContext().ensureActive()
        val entry = try {
            val file = access.inspect(song)
            handles[index] = file
            val header = file.open().use { input ->
                val bytes = ByteArray(64); var count = 0
                while (count < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    if (read == 0) throw java.io.IOException("Audio read did not advance")
                    count += read
                }
                bytes.copyOf(count)
            }
            LibraryAuditEntry(song, if (header.isEmpty()) LibraryFileState.EMPTY else LibraryFileState.READABLE,
                file.identity, file.bytes, detectLocalAudioFormat(header))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: java.nio.file.NoSuchFileException) { LibraryAuditEntry(song, LibraryFileState.MISSING) }
        catch (_: FileNotFoundException) { LibraryAuditEntry(song, LibraryFileState.MISSING) }
        catch (_: java.nio.file.AccessDeniedException) { LibraryAuditEntry(song, LibraryFileState.ACCESS_REQUIRED) }
        catch (_: SecurityException) { LibraryAuditEntry(song, LibraryFileState.ACCESS_REQUIRED) }
        catch (_: UnsupportedOperationException) { LibraryAuditEntry(song, LibraryFileState.UNSUPPORTED) }
        catch (_: Exception) { LibraryAuditEntry(song, LibraryFileState.FAILED) }
        progress(LibraryAuditProgress(index + 1, selected.size, 0))
        if (entry.identity == null) entry.copy(identity = reportedLocalLocation(song)) else entry
    }.toMutableList()
    val readable = entries.indices.filter { entries[it].state == LibraryFileState.READABLE }
    val sameFile = readable.groupBy { entries[it].identity }.values.filter { it.size > 1 }
    val uniqueFiles = readable.distinctBy { entries[it].identity }
    val candidates = uniqueFiles.groupBy { entries[it].bytes }.filterKeys { it != null && it > 0 }
        .values.filter { it.size > 1 }.flatten()
    var consumed = 0L
    var budgetReached = false
    if (verifyBytes) for (index in candidates) {
        currentCoroutineContext().ensureActive()
        val original = handles.getValue(index)
        val size = original.bytes ?: continue
        if (size > hashBudget - consumed) { budgetReached = true; continue }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var readTotal = 0L
            original.open().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size - readTotal + 1).toInt())
                    if (read < 0) break
                    if (read == 0) throw java.io.IOException("Audio read did not advance")
                    readTotal += read; consumed += read
                    if (readTotal > size) throw java.io.IOException("Audio changed while comparing")
                    digest.update(buffer, 0, read)
                }
            }
            val after = access.inspect(entries[index].song)
            if (readTotal != size || original.identity != after.identity || original.bytes != after.bytes || original.modified != after.modified) {
                entries[index] = entries[index].copy(state = LibraryFileState.CHANGED)
            } else entries[index] = entries[index].copy(sha256 = digest.digest().joinToString("") { "%02x".format(it) })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { entries[index] = entries[index].copy(state = LibraryFileState.CHANGED) }
        progress(LibraryAuditProgress(selected.size, selected.size, consumed))
    }
    val byteGroups = uniqueFiles.filter { entries[it].sha256 != null }
        .groupBy { entries[it].sha256 }.values.filter { it.size > 1 }
    val metadataGroups = uniqueFiles.filter { entries[it].state == LibraryFileState.READABLE }
        .filter { entries[it].song.title.isNotBlank() && entries[it].song.artist.isNotBlank() }
        .groupBy { index -> entries[index].song.let { listOf(it.title, it.artist, it.albumName.orEmpty(), it.durationText.orEmpty()).map(::auditKey) } }
        .values.filter { it.size > 1 }
    LibraryAuditReport(entries, sameFile.map { LibraryDuplicateGroup(LibraryDuplicateKind.SAME_FILE, it) } +
        byteGroups.map { LibraryDuplicateGroup(LibraryDuplicateKind.IDENTICAL_BYTES, it) } +
        metadataGroups.map { LibraryDuplicateGroup(LibraryDuplicateKind.METADATA_CANDIDATES, it) }, consumed, budgetReached)
}

private fun auditKey(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
    .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

/** Header evidence only. A FLAC signature is never proof of a lossless original master. */
internal fun detectLocalAudioFormat(bytes: ByteArray): String? {
    fun at(offset: Int, text: String) = offset + text.length <= bytes.size &&
        text.indices.all { (bytes[offset + it].toInt() and 255) == text[it].code }
    return when {
        at(0, "fLaC") -> "FLAC"
        at(0, "RIFF") && at(8, "WAVE") -> "WAV"
        at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC")) -> "AIFF"
        at(0, "DSD ") -> "DSF"
        at(0, "FRM8") && at(12, "DSD ") -> "DFF"
        at(0, "OggS") && at(28, "OpusHead") -> "OPUS"
        at(0, "OggS") -> "OGG"
        at(4, "ftyp") -> "MP4 (codec not yet measured)"
        at(0, "ID3") -> "ID3 tagged (codec not yet measured)"
        else -> null
    }
}

data class SessionFileRepair(val sessionId: String, val trackIndex: Int, val before: ListeningTrack, val after: ListeningTrack)

/** Only a readable local copy with the exact source-qualified ID may repair a saved location. */
fun previewSessionFileRepairs(state: ListeningState, library: List<Song>, available: (ListeningTrack) -> Boolean): List<SessionFileRepair> {
    val copies = library.filter { it.localPath != null || it.localUri != null }.groupBy { it.videoId }
    return state.sessions.flatMap { session -> session.tracks.mapIndexedNotNull { index, track ->
        if ((track.path == null && track.uri == null) || available(track)) return@mapIndexedNotNull null
        val copy = copies[track.id]?.firstOrNull { available(ListeningTrack.of(it)) } ?: return@mapIndexedNotNull null
        SessionFileRepair(session.id, index, track, track.copy(path = copy.localPath, uri = copy.localUri,
            format = copy.downloadFormat, quality = copy.sourceQuality, modified = copy.localDateModifiedSeconds))
    } }
}

/** Optimistic check keeps edits made after the preview and preserves entry/order/history metadata. */
fun applySessionFileRepairs(state: ListeningState, repairs: List<SessionFileRepair>, available: (ListeningTrack) -> Boolean): ListeningState {
    require(repairs.size <= 10_000) { "Too many repairs" }
    require(repairs.map { it.sessionId to it.trackIndex }.distinct().size == repairs.size) { "Duplicate repair entries" }
    repairs.forEach { repair ->
        val actual = state.sessions.firstOrNull { it.id == repair.sessionId }?.tracks?.getOrNull(repair.trackIndex)
        require(actual == repair.before) { "The saved session changed. Review the repairs again." }
        require(repair.before.id == repair.after.id && available(repair.after)) { "The replacement file is no longer available." }
        require(repair.after.copy(path = repair.before.path, uri = repair.before.uri, format = repair.before.format,
            quality = repair.before.quality, modified = repair.before.modified) == repair.before) { "A repair may only update local file details" }
    }
    val bySession = repairs.groupBy { it.sessionId }
    return state.copy(sessions = state.sessions.map { session ->
        val replacements = bySession[session.id]?.associateBy { it.trackIndex } ?: return@map session
        session.copy(tracks = session.tracks.mapIndexed { index, track -> replacements[index]?.after ?: track })
    })
}

/** Keeping one byte-identical copy: saved references move to it; the other copies may then be trashed by the host. */
data class DuplicateMergePreview(val keep: LibraryAuditEntry, val remove: List<LibraryAuditEntry>, val repairs: List<SessionFileRepair>)

/**
 * Only groups whose bytes were verified identical can be merged; similar metadata is never merged.
 * Each saved entry keeps its own recording ID, queue entry and history; only its local location changes.
 */
fun previewDuplicateMerge(state: ListeningState, report: LibraryAuditReport, group: LibraryDuplicateGroup, keepIndex: Int): DuplicateMergePreview {
    require(group.kind == LibraryDuplicateKind.IDENTICAL_BYTES) { "Only copies with identical bytes can be merged." }
    require(keepIndex in group.indices) { "Choose a copy from this group." }
    val keep = report.entries[keepIndex]
    val remove = group.indices.filter { it != keepIndex }.map { report.entries[it] }
    require(keep.sha256 != null && remove.all { it.sha256 == keep.sha256 && it.identity != keep.identity }) { "Compare the file bytes again before merging." }
    val removed = remove.flatMap { listOfNotNull(it.identity, it.song.localPath, it.song.localUri) }.toSet()
    val repairs = state.sessions.flatMap { session -> session.tracks.mapIndexedNotNull { index, track ->
        if (track.path !in removed && track.uri !in removed) return@mapIndexedNotNull null
        SessionFileRepair(session.id, index, track, track.copy(path = keep.song.localPath, uri = keep.song.localUri))
    } }
    return DuplicateMergePreview(keep, remove, repairs)
}

/**
 * Moved-file reconciliation. A saved location that is now missing is matched to a readable file
 * whose earlier analysis fingerprint (exact byte size, decoded length and integrated loudness)
 * agrees, and only when exactly one file does. Needs an analysis made before the move.
 */
fun previewMovedFileRepairs(
    state: ListeningState,
    analysis: Map<String, com.cosmotune.audio.LibraryAudioAnalysis>,
    available: (ListeningTrack) -> Boolean,
): List<SessionFileRepair> {
    fun same(a: com.cosmotune.audio.LibraryAudioAnalysis, b: com.cosmotune.audio.LibraryAudioAnalysis) =
        a.bytes != null && a.bytes == b.bytes && kotlin.math.abs(a.durationSeconds - b.durationSeconds) < 0.05 &&
            a.integratedLufs != null && b.integratedLufs != null && kotlin.math.abs(a.integratedLufs - b.integratedLufs) < 0.05
    val present = analysis.values.filter { candidate -> available(ListeningTrack(id = candidate.id, title = "", artist = "", path = candidate.location.takeIf { File(it).isAbsolute }, uri = candidate.location.takeUnless { File(it).isAbsolute })) }
    return state.sessions.flatMap { session -> session.tracks.mapIndexedNotNull { index, track ->
        val old = (track.path ?: track.uri)?.let(analysis::get) ?: return@mapIndexedNotNull null
        if (available(track)) return@mapIndexedNotNull null
        val match = present.filter { it.location != old.location && same(it, old) }.singleOrNull() ?: return@mapIndexedNotNull null
        val absolute = File(match.location).isAbsolute
        SessionFileRepair(session.id, index, track, track.copy(path = if (absolute) match.location else null, uri = if (absolute) null else match.location))
    } }
}

/**
 * Lossless upgrade preview. A saved entry whose analysed local file decodes lossy is pointed at a
 * readable copy of the same recording ID whose analysis decodes lossless without a lossy-looking
 * spectrum. Only saved references change (atomically, through [applySessionFileRepairs]); audio
 * files are never overwritten or deleted by an upgrade.
 */
/**
 * LK-08 provider-based replacement, first half: saved entries whose measured file is lossy and that
 * have no analysed lossless copy of the same recording yet, and whose recording a source can be asked
 * for (catalogue or source-qualified IDs, never a device-only file). The host queues these as LOSSLESS
 * downloads; each download is labelled from its own bytes (MN-03), and only a copy that the analysis
 * then measures as lossless, without a lossy-source spectrum, is offered by [previewLosslessUpgrades].
 */
fun losslessCopyRequests(state: ListeningState, analysis: Map<String, com.cosmotune.audio.LibraryAudioAnalysis>): List<ListeningTrack> {
    val verified = analysis.values.filter { it.lossless && !it.possibleLossySource }.map { it.id }.toSet()
    return state.sessions.asSequence().flatMap { it.tracks.asSequence() }
        .filter { track -> (track.path ?: track.uri)?.let(analysis::get)?.let { !it.lossless && it.id == track.id } == true }
        .filter { it.id !in verified && it.id.isNotBlank() && listOf("file:", "content:", "local:").none(it.id::startsWith) }
        .distinctBy { it.id }.take(500).toList()
}

fun previewLosslessUpgrades(
    state: ListeningState,
    analysis: Map<String, com.cosmotune.audio.LibraryAudioAnalysis>,
    available: (ListeningTrack) -> Boolean,
): List<SessionFileRepair> {
    val better = analysis.values.filter { it.lossless && !it.possibleLossySource }.groupBy { it.id }
    return state.sessions.flatMap { session -> session.tracks.mapIndexedNotNull { index, track ->
        val current = (track.path ?: track.uri)?.let(analysis::get)?.takeIf { !it.lossless } ?: return@mapIndexedNotNull null
        val candidate = better[track.id].orEmpty().filter { it.location != current.location }
            .map { it to track.copy(path = it.location.takeIf { l -> File(l).isAbsolute }, uri = it.location.takeUnless { l -> File(l).isAbsolute }) }
            .filter { available(it.second) }
            .maxByOrNull { (a, _) -> a.sampleRate.toLong() * (a.bitsPerSample ?: 16) }
            ?: return@mapIndexedNotNull null
        SessionFileRepair(session.id, index, track, candidate.second.copy(format = candidate.first.codec, quality = candidate.first.codec?.uppercase()))
    } }
}
