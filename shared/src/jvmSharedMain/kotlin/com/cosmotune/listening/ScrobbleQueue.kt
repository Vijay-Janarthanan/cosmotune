package com.cosmotune.listening

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A finished listen waiting to be submitted. Holds track metadata only; never a session key or token. */
@Serializable
data class PendingScrobble(
    val service: String, val artist: String, val track: String, val timestampSeconds: Long,
    val album: String? = null, val durationSeconds: Int? = null, val attempts: Int = 0,
)

enum class ScrobbleOutcome { SENT, RETRY_LATER, REJECTED }
data class ScrobbleFlush(val sent: Int, val rejected: Int, val expired: Int, val remaining: Int)

@Serializable
private data class ScrobbleQueueFile(val version: Int = 1, val pending: List<PendingScrobble> = emptyList())

/**
 * Offline scrobble queue shared by both hosts. Listens that fail for a network reason are kept,
 * in order, and retried later; services refuse listens older than about two weeks, so those are
 * dropped rather than retried forever. Writes are atomic and bounded.
 */
class ScrobbleQueue(private val file: File, private val maxItems: Int = 2_000) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()
    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun pending(): List<PendingScrobble> = synchronized(lock) { read() }

    fun add(item: PendingScrobble) = synchronized(lock) {
        require(item.service.matches(Regex("[a-z]{2,20}")) && item.artist.isNotBlank() && item.track.isNotBlank()) { "Invalid scrobble" }
        val current = read()
        if (current.any { it.service == item.service && it.timestampSeconds == item.timestampSeconds && it.track == item.track }) return@synchronized
        // Oldest listens go first when the queue is full: newer ones are the likelier to be accepted.
        write((current + item.copy(artist = item.artist.take(512), track = item.track.take(512), album = item.album?.take(512))).takeLast(maxItems))
    }

    /**
     * Submits in listening order; stops at the first retryable failure so order is preserved. Concurrent flushes are skipped.
     * With [service], only that service's listens are submitted; the others stay queued untouched.
     */
    suspend fun flush(nowSeconds: Long = System.currentTimeMillis() / 1000, service: String? = null,
        submit: suspend (PendingScrobble) -> ScrobbleOutcome): ScrobbleFlush {
        if (!flushing.compareAndSet(false, true)) return ScrobbleFlush(0, 0, 0, pending().size)
        try {
            var sent = 0; var rejected = 0; var expired = 0
            val snapshot = pending().filter { service == null || it.service == service }
            val done = HashSet<PendingScrobble>()
            val retried = HashMap<PendingScrobble, PendingScrobble>()
            for (item in snapshot) {
                if (item.timestampSeconds < nowSeconds - maxAgeSeconds(item.service)) { expired++; done += item; continue }
                val outcome = try { submit(item) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { ScrobbleOutcome.RETRY_LATER }
                when (outcome) {
                    ScrobbleOutcome.SENT -> { sent++; done += item }
                    ScrobbleOutcome.REJECTED -> { rejected++; done += item }
                    ScrobbleOutcome.RETRY_LATER -> {
                        if (item.attempts + 1 >= MAX_ATTEMPTS) { rejected++; done += item } else retried[item] = item.copy(attempts = item.attempts + 1)
                        break
                    }
                }
            }
            val remaining = synchronized(lock) {
                // Items added while flushing are kept after the ones that were already waiting.
                val next = read().filterNot { it in done }.map { retried[it] ?: it }
                write(next); next.size
            }
            return ScrobbleFlush(sent, rejected, expired, remaining)
        } finally { flushing.set(false) }
    }

    private fun read(): List<PendingScrobble> {
        if (!file.isFile) return emptyList()
        if (file.length() > MAX_BYTES) return emptyList()
        return runCatching { json.decodeFromString<ScrobbleQueueFile>(file.readText(Charsets.UTF_8)).pending }.getOrDefault(emptyList())
    }

    private fun write(items: List<PendingScrobble>) {
        val raw = json.encodeToString(ScrobbleQueueFile(pending = items)).toByteArray(Charsets.UTF_8)
        file.absoluteFile.parentFile.mkdirs()
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".cosmotune-scrobbles-", ".tmp")
        try {
            java.io.FileOutputStream(temp.toFile()).use { it.write(raw); it.fd.sync() }
            try { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }

    companion object {
        /** Last.fm accepts scrobbles up to 14 days old. */
        const val MAX_AGE_SECONDS = 14L * 24 * 60 * 60
        /** ListenBrainz accepts older listens; a queued one is still dropped after half a year. */
        const val LISTENBRAINZ_MAX_AGE_SECONDS = 183L * 24 * 60 * 60

        fun maxAgeSeconds(service: String): Long = if (service == ListenBrainzSubmitter.SERVICE) LISTENBRAINZ_MAX_AGE_SECONDS else MAX_AGE_SECONDS
        const val MAX_ATTEMPTS = 50
        private const val MAX_BYTES = 4L * 1024 * 1024

        /** Network failures are worth retrying; a service's explicit refusal is not. */
        fun outcomeOf(error: Throwable?): ScrobbleOutcome = when {
            error == null -> ScrobbleOutcome.SENT
            generateSequence(error) { it.cause }.take(8).any { it is java.io.IOException } -> ScrobbleOutcome.RETRY_LATER
            else -> ScrobbleOutcome.REJECTED
        }
    }
}
