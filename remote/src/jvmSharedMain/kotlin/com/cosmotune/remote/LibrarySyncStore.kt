package com.cosmotune.remote

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class SyncTrack(val id: String, val title: String, val artist: String,
    val album: String? = null, val duration: String? = null, val fileHash: String? = null)
@Serializable data class SyncPlaylist(val id: String, val title: String, val tracks: List<SyncTrack>)
@Serializable data class SyncFile(val hash: String, val name: String, val size: Long)
@Serializable data class SyncStamp(val clock: Long, val device: String) : Comparable<SyncStamp> {
    override fun compareTo(other: SyncStamp): Int = compareValuesBy(this, other, SyncStamp::clock, SyncStamp::device)
}
/** Sequence is the receiving store's cursor; stamp is the stable cross-device conflict winner. */
@Serializable data class SyncRecord(val id: String, val stamp: SyncStamp, val sequence: Long = 0,
    val deleted: Boolean = false, val playlist: SyncPlaylist? = null, val file: SyncFile? = null)
@Serializable data class SyncPage(val device: String, val through: Long, val next: Long?, val records: List<SyncRecord>)
@Serializable data class SyncPush(val records: List<SyncRecord>)
@Serializable data class SyncAck(val peer: String, val through: Long)
@Serializable data class SyncChunk(val hash: String, val offset: Long, val bytes: String)
data class SyncStorageLimits(val fileBytes: Long = 2L * 1024 * 1024 * 1024,
    val totalBytes: Long = 8L * 1024 * 1024 * 1024, val reserveBytes: Long = 64L * 1024 * 1024)
@Serializable private data class SyncState(val version: Int = 1, val device: String = UUID.randomUUID().toString(),
    val clock: Long = 0, val sequence: Long = 0, val records: List<SyncRecord> = emptyList(), val cursors: Map<String, Long> = emptyMap(),
    val published: Map<String, SyncPlaylist> = emptyMap())

/** Typed, credential-free metadata journal with retained tombstones and private, content-addressed file copies. */
class LibrarySyncStore(val directory: File, private val limits: SyncStorageLimits = SyncStorageLimits()) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val index = File(directory, "journal.json")
    private val blobs = File(directory, "files")
    private val verified = HashMap<String, Pair<Long, Long>>()
    private var cachedUsage: Long? = null
    init { require(limits.fileBytes in 1..MAX_FILE_BYTES && limits.totalBytes >= limits.fileBytes && limits.reserveBytes >= 0) }
    private var state = if (index.exists()) {
        require(index.length() <= MAX_STATE_BYTES) { "Device sync journal exceeds its limit; original retained." }
        json.decodeFromString<SyncState>(index.readText(Charsets.UTF_8)).also {
            require(it.version == 1 && validId(it.device) && it.records.size <= MAX_RECORDS)
            it.records.forEach(::validate)
        }
    } else SyncState()
    val device: String get() = synchronized(this) { state.device }
    @Synchronized fun records(): List<SyncRecord> = state.records.toList()
    @Synchronized fun sequence(): Long = state.sequence
    @Synchronized fun cursor(peer: String): Long = state.cursors[peer] ?: 0
    @Synchronized fun acknowledge(peer: String, through: Long) {
        require(validId(peer) && through >= 0)
        require(peer in state.cursors || state.cursors.size < 256) { "The paired-device journal has reached its peer limit." }
        save(state.copy(cursors = state.cursors + (peer to maxOf(through, cursor(peer)))))
    }
    @Synchronized fun page(after: Long, limit: Int = 32): SyncPage {
        require(after in 0..state.sequence && limit in 1..64)
        val rows = state.records.filter { it.sequence > after }.sortedBy { it.sequence }.take(limit)
        val last = rows.lastOrNull()?.sequence ?: state.sequence
        return SyncPage(state.device, last, last.takeIf { end -> state.records.any { it.sequence > end } }, rows)
    }
    @Synchronized fun publish(playlist: SyncPlaylist) {
        val id = "playlist:${playlist.id}"
        val old = state.records.firstOrNull { it.id == id }
        if (old?.deleted != false || old.playlist != playlist) local(SyncRecord(id, SyncStamp(state.clock + 1, state.device), playlist = playlist))
        val snapshot = withoutFiles(playlist)
        if (state.published[playlist.id] != snapshot) save(state.copy(published = state.published + (playlist.id to snapshot)))
    }
    /** Only previously selected playlists are watched. Native removals become tombstones, not file deletions. */
    @Synchronized fun reconcilePublished(native: List<SyncPlaylist>) {
        val available = native.associateBy { it.id }
        for ((id, previous) in state.published.toMap()) {
            val current = available[id]
            if (current == null) remove("playlist:$id")
            else if (withoutFiles(current) != previous) {
                val shared = state.records.firstOrNull { it.id == "playlist:$id" }?.playlist
                publish(current.copy(tracks = current.tracks.map { track ->
                    track.copy(fileHash = track.fileHash ?: shared?.tracks?.firstOrNull { it.id == track.id }?.fileHash)
                }))
            }
        }
    }
    @Synchronized fun bindReceived(records: List<SyncRecord>) {
        val snapshots = records.filterNot { it.deleted }.mapNotNull { it.playlist }.associate { it.id to withoutFiles(it) }
        val removed = records.filter { it.deleted && it.id.startsWith("playlist:") }.map { it.id.removePrefix("playlist:") }.toSet()
        save(state.copy(published = (state.published - removed) + snapshots))
    }
    @Synchronized fun remove(id: String) {
        val old = state.records.firstOrNull { it.id == id } ?: return
        if (!old.deleted) local(SyncRecord(id, SyncStamp(state.clock + 1, state.device), deleted = true))
        if (id.startsWith("playlist:") && id.removePrefix("playlist:") in state.published)
            save(state.copy(published = state.published - id.removePrefix("playlist:")))
    }
    @Synchronized private fun local(record: SyncRecord) { merge(listOf(record)) }
    /** Conflict resolution is deterministic and idempotent. Deletions propagate as records, never file deletes. */
    @Synchronized fun merge(incoming: List<SyncRecord>): Int {
        require(incoming.size <= 64)
        incoming.forEach(::validate)
        val rows = state.records.associateBy { it.id }.toMutableMap()
        var sequence = state.sequence
        var changed = 0
        for (record in incoming) {
            val old = rows[record.id]
            if (old == null || record.stamp > old.stamp) {
                rows[record.id] = record.copy(sequence = ++sequence)
                changed++
            } else if (old.stamp == record.stamp) require(old.copy(sequence = 0) == record.copy(sequence = 0)) { "Conflicting sync record identity." }
        }
        require(rows.size <= MAX_RECORDS) { "Device sync journal is full; tombstones were kept." }
        if (changed > 0) save(state.copy(clock = maxOf(state.clock, incoming.maxOfOrNull { it.stamp.clock } ?: 0), sequence = sequence, records = rows.values.toList()))
        return changed
    }

    /** Copies only an explicitly selected input into private sync storage; no host filesystem path is shared. */
    fun offerFile(name: String, input: () -> InputStream, checkCancelled: () -> Unit = {}): SyncFile {
        check(blobs.isDirectory || blobs.mkdirs())
        val temporary = File.createTempFile("offer-", ".part", blobs)
        var createdCopy: File? = null
        var committedHash: String? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            input().use { source -> temporary.outputStream().use { output ->
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    checkCancelled()
                    val read = source.read(buffer); if (read < 0) break
                    size += read; require(size <= limits.fileBytes) { "Selected file exceeds this device's sync file limit." }
                    checkSpace(read.toLong())
                    digest.update(buffer, 0, read); output.write(buffer, 0, read)
                }
                output.fd.sync()
            } }
            require(size > 0)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val descriptor = SyncFile(hash, name.substringAfterLast('/').substringAfterLast('\\').take(180).ifBlank { "Audio" }, size)
            checkCancelled()
            synchronized(this) {
                val target = File(blobs, hash)
                if (!target.exists()) createdCopy = target
                if (!target.exists() || digest(target) != hash) move(temporary, target)
                verified[hash] = target.length() to target.lastModified()
                val old = state.records.firstOrNull { it.id == "file:$hash" }
                if (old == null || old.file != descriptor || old.deleted) local(SyncRecord("file:$hash", SyncStamp(state.clock + 1, state.device), file = descriptor))
                committedHash = hash
            }
            return descriptor
        } finally {
            temporary.delete()
            synchronized(this) { if (committedHash == null) createdCopy?.delete(); cachedUsage = null }
        }
    }
    @Synchronized fun file(hash: String): File? {
        require(hash.matches(HASH))
        val descriptor = state.records.firstOrNull { it.id == "file:$hash" && !it.deleted }?.file ?: return null
        val file = File(blobs, hash).takeIf { it.isFile && it.length() == descriptor.size } ?: return null
        val signature = file.length() to file.lastModified()
        if (verified[hash] != signature) {
            if (digest(file) != hash) return null
            verified[hash] = signature
        }
        return file
    }
    @Synchronized fun chunk(hash: String, offset: Long): SyncChunk {
        val file = requireNotNull(file(hash)) { "This shared file is unavailable." }
        require(offset in 0..file.length())
        val data = ByteArray(minOf(CHUNK_BYTES.toLong(), file.length() - offset).toInt())
        RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(data) }
        return SyncChunk(hash, offset, Base64.getEncoder().encodeToString(data))
    }
    @Synchronized fun uploadOffset(hash: String): Long {
        require(hash.matches(HASH) && state.records.any { it.file?.hash == hash && !it.deleted })
        return file(hash)?.length() ?: File(blobs, "$hash.part").takeIf { it.isFile }?.length() ?: 0L
    }
    @Synchronized fun deleteLocalCopy(hash: String) {
        require(hash.matches(HASH))
        for (name in listOf(hash, "$hash.part")) {
            val target = File(blobs, name)
            check(!target.exists() || target.delete()) { "The shared copy could not be removed." }
        }
        verified.remove(hash); cachedUsage = null
    }
    @Synchronized fun acceptChunk(chunk: SyncChunk): Long {
        val descriptor = state.records.firstOrNull { it.file?.hash == chunk.hash && !it.deleted }?.file
            ?: error("This file was not offered in the paired catalogue.")
        require(chunk.hash.matches(HASH))
        require(descriptor.size <= limits.fileBytes) { "This file exceeds the receiving device's sync limit." }
        file(chunk.hash)?.let { return it.length() }
        check(blobs.isDirectory || blobs.mkdirs())
        val partial = File(blobs, "${chunk.hash}.part")
        require(chunk.offset == (if (partial.exists()) partial.length() else 0L)) { "Resume from the current file offset." }
        val bytes = Base64.getDecoder().decode(chunk.bytes)
        require(bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES && chunk.offset + bytes.size <= descriptor.size)
        checkSpace(bytes.size.toLong())
        try { RandomAccessFile(partial, "rw").use { output -> output.seek(chunk.offset); output.write(bytes); output.fd.sync() } }
        catch (failure: Exception) { cachedUsage = null; throw failure }
        if (partial.length() == descriptor.size) {
            if (digest(partial) != descriptor.hash) { partial.delete(); cachedUsage = null; error("File checksum did not match.") }
            move(partial, File(blobs, descriptor.hash))
            File(blobs, descriptor.hash).let { verified[descriptor.hash] = it.length() to it.lastModified() }
        }
        return chunk.offset + bytes.size
    }
    fun receive(file: SyncFile, fetch: (Long) -> SyncChunk): File {
        require(file.hash.matches(HASH) && file.size in 1..limits.fileBytes)
        check(blobs.isDirectory || blobs.mkdirs())
        val target = File(blobs, file.hash)
        if (target.isFile && target.length() == file.size && digest(target) == file.hash) return target
        val partial = File(blobs, "${file.hash}.part")
        require(!partial.exists() || partial.length() <= file.size)
        checkSpace(file.size - if (partial.exists()) partial.length() else 0L, reserve = false)
        try { RandomAccessFile(partial, "rw").use { output ->
            var offset = output.length(); output.seek(offset)
            while (offset < file.size) {
                val chunk = fetch(offset)
                require(chunk.hash == file.hash && chunk.offset == offset)
                val bytes = Base64.getDecoder().decode(chunk.bytes)
                require(bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES && offset + bytes.size <= file.size)
                checkSpace(bytes.size.toLong())
                output.write(bytes); offset += bytes.size
            }
            output.fd.sync()
        } } finally { synchronized(this) { cachedUsage = null } }
        if (digest(partial) != file.hash) { partial.delete(); error("Transferred file checksum did not match. Retry from the start.") }
        move(partial, target)
        synchronized(this) { verified[file.hash] = target.length() to target.lastModified() }
        return target
    }
    @Synchronized private fun checkSpace(additional: Long, reserve: Boolean = true) {
        val used = cachedUsage ?: blobs.listFiles().orEmpty().filter(File::isFile).sumOf { it.length() }.also { cachedUsage = it }
        require(additional >= 0 && used <= limits.totalBytes - additional) { "Sync storage quota reached. Remove unneeded shared copies before retrying." }
        check(directory.usableSpace >= additional && directory.usableSpace - additional >= limits.reserveBytes) { "Not enough free storage for this transfer." }
        if (reserve) cachedUsage = used + additional
    }
    private fun save(next: SyncState) {
        val encoded = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_STATE_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File.createTempFile("journal-", ".part", directory)
        try { temporary.outputStream().use { it.write(encoded); it.fd.sync() }; move(temporary, index); state = next }
        finally { temporary.delete() }
    }
    companion object {
        const val CHUNK_BYTES = 32 * 1024
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_STATE_BYTES = 64L * 1024 * 1024
        private const val MAX_RECORDS = 20_000
        private val HASH = Regex("[a-f0-9]{64}")
        private fun validId(value: String) = value.length in 1..256 && value.none(Char::isISOControl)
        private fun withoutFiles(playlist: SyncPlaylist) = playlist.copy(tracks = playlist.tracks.map { it.copy(fileHash = null) })
        fun validate(record: SyncRecord) {
            require(validId(record.id) && validId(record.stamp.device) && record.stamp.clock in 1..Long.MAX_VALUE - 1)
            require(record.sequence >= 0)
            if (record.deleted) require(record.playlist == null && record.file == null)
            else require((record.playlist == null) != (record.file == null))
            record.playlist?.let { playlist ->
                require(validId(playlist.id) && record.id == "playlist:${playlist.id}" && playlist.title.length in 1..500 && playlist.tracks.size <= 10_000)
                playlist.tracks.forEach { track ->
                    require(validId(track.id) && track.title.length <= 500 && track.artist.length <= 500 && (track.album?.length ?: 0) <= 500)
                    require(track.fileHash == null || track.fileHash.matches(HASH))
                    require(!track.id.contains("://") && !track.id.startsWith("/")) { "Device-local paths and URLs cannot be playlist identities." }
                }
            }
            record.file?.let { require(it.hash.matches(HASH) && record.id == "file:${it.hash}" && it.size in 1..MAX_FILE_BYTES && it.name.length in 1..180 && it.name.none(Char::isISOControl) && '/' !in it.name && '\\' !in it.name) }
        }
        private fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(CHUNK_BYTES); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        private fun move(from: File, to: File) {
            try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}
