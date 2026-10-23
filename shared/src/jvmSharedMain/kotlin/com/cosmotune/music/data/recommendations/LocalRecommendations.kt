package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Optional local discovery; playback always receives provider-verified Song IDs. */
object LocalRecommendations {
    @Volatile private var directory: File? = null
    @Volatile private var model: TinyRecommendationModel? = null
    @Volatile private var attempted = false
    private val loadLock = Any()
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val resolved = object : LinkedHashMap<String, Song?>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song?>) = size > 128
    }

    /** Call once at app startup. Loading and decompression happen on the first radio request's IO dispatcher. */
    fun configure(cacheDirectory: File) {
        directory = cacheDirectory
        prefetchScope.launch { load() }
    }

    suspend fun enhance(seed: Song, remote: List<Song>, search: suspend (String) -> List<Song>): List<Song> = withContext(Dispatchers.IO) {
        val graph = load() ?: return@withContext remote
        val candidates = graph.recommend(seed)
        val ordered = remote.mapIndexed { index, song ->
            // Quantised hash embeddings can collide. Weak similarities abstain
            // rather than overturning the provider's order for unfamiliar songs.
            val local = ((graph.score(seed, song) - .65f) / .35f).coerceIn(0f, 1f)
            song to (.5f / (index + 1) + .5f * local)
        }.sortedByDescending { it.second }.map { it.first }
        val additions = ArrayList<Song>(3)
        for (candidate in candidates.take(3)) {
                if (remote.any { TrackMatcher.best(listOf(it), candidate.target()) != null }) continue
                val key = candidate.key
                val held = synchronized(resolved) { resolved.containsKey(key) to resolved[key] }
                val song = held.second
                if (!held.first && inFlight.add(key)) {
                    // Live provider resolution never delays the current radio batch. A later
                    // request can use the verified match; misses remain ordinary provider radio.
                    prefetchScope.launch {
                        try {
                            val found = withTimeoutOrNull(2_000) {
                                val query = TrackMatcher.queries(candidate.target()).firstOrNull() ?: return@withTimeoutOrNull null
                                TrackMatcher.best(search(query), candidate.target())
                            }
                            if (found != null) synchronized(resolved) { resolved[key] = found }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // The provider remains authoritative and can recover next time.
                        } finally { inFlight.remove(key) }
                    }
                }
                if (song != null && song.videoId != seed.videoId && additions.none { it.videoId == song.videoId }) additions += song
        }
        if (additions.isEmpty()) return@withContext ordered
        // Retain provider diversity and its familiar first choices, interspersing bounded discoveries.
        val result = ordered.toMutableList()
        additions.forEachIndexed { index, song -> result.add(minOf(2 + index * 5, result.size), song) }
        result.distinctBy { it.videoId }
    }

    private fun load(): TinyRecommendationModel? {
        model?.let { return it }
        if (attempted || directory == null) return null
        synchronized(loadLock) {
            model?.let { return it }
            if (attempted) return null
            try {
                val root = directory ?: return null
                val manifest = javaClass.getResourceAsStream("/recommendations/manifest.json")?.bufferedReader()?.use { Json.parseToJsonElement(it.readText()).jsonObject }
                    ?: return null
                val sha = manifest.getValue("sha256").jsonPrimitive.content
                require(sha.matches(Regex("[0-9a-f]{64}")))
                val expected = manifest.getValue("unpacked_bytes").jsonPrimitive.content.toLong()
                require(expected in 40..TinyRecommendationModel.MAX_BYTES)
                check(root.isDirectory || root.mkdirs())
                val target = File(root, "recommendations-${sha.take(16)}.ctrec")
                if (!valid(target, sha, expected)) {
                    require(root.usableSpace >= expected + 32L * 1024 * 1024) { "Insufficient cache space" }
                    val temporary = File.createTempFile("recommendations-", ".part", root)
                    try {
                        val packed = javaClass.getResourceAsStream("/recommendations/recommendations.ctrec.gz") ?: return null
                        GZIPInputStream(packed).use { input ->
                            FileOutputStream(temporary).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var size = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    size += n
                                    require(size <= expected) { "Oversized model" }
                                    output.write(buffer, 0, n)
                                }
                                output.fd.sync()
                            }
                        }
                        require(valid(temporary, sha, expected)) { "Model checksum mismatch" }
                        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { temporary.delete() }
                }
                model = TinyRecommendationModel.open(target)
                // Only obsolete files created by this loader, inside its dedicated cache.
                root.listFiles()?.filter { it != target && it.name.matches(Regex("recommendations-[0-9a-f]{16}\\.ctrec")) }?.forEach { it.delete() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Discovery must never prevent the provider's radio from playing.
            } finally { attempted = true }
            return model
        }
    }

    private fun valid(file: File, sha: String, expected: Long): Boolean {
        if (!file.isFile || file.length() != expected) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha
    }
}
