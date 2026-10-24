package com.cosmotune.providers

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

data class ArtworkAccess(val download: Boolean, val declaredDomains: List<String>, val allowLocalNetwork: Boolean = false,
    val localOrigin: HttpUrl? = null, val revision: String = "1") {
    companion object {
        /** Explicit server configuration permits its own origin, including a private LAN server. */
        fun httpSource(address: String, enabled: Boolean): ArtworkAccess? {
            val url = address.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: return null
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
            val revision = MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return ArtworkAccess(enabled, listOf(url.host), allowLocalNetwork = true, localOrigin = origin, revision = revision)
        }
    }
}

/** Opaque references keep signed artwork addresses out of song metadata and portable library exports. */
class CatalogueArtwork(private val root: File, private val access: (String) -> ArtworkAccess?,
    private val protection: ProviderFileProtection = providerFileProtection(),
    private val client: OkHttpClient = com.cosmotune.music.data.Http.client) : AutoCloseable {
    @Serializable private data class Entry(val owner: String, val url: String)
    private class Held { val mutex = Mutex(); var readers = 0 }
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(128, .75f, true)
    private val held = HashMap<String, Held>()
    private val active = ConcurrentHashMap<ProviderNetwork, String>()
    private val workers = Semaphore(4)
    private val disk = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val index = File(root, "references.bin")
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var closed = false

    init {
        check(root.isDirectory || root.mkdirs()); root.ownerOnly()
        if (index.isFile && index.length() <= MAX_INDEX_BYTES + 64 * 1024) runCatching {
            val bytes = protection.open(index.readBytes())
            require(bytes.size <= MAX_INDEX_BYTES)
            val restored = json.decodeFromString<Map<String, Entry>>(ProviderPackages.utf8(bytes))
            require(restored.size <= MAX_ENTRIES)
            restored.forEach { (key, entry) ->
                require(key.matches(HASH) && ownerValid(entry.owner) && validUrl(entry.url) != null && key == hash(entry.owner + "|" + entry.url))
            }
            entries.putAll(restored)
        }
        scope.launch { try { for (ignored in writes) {
            delay(250)
            try { saveIndex() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A full or removed cache directory never interrupts playback. */ }
        } } finally { scope.cancel() } }
    }

    fun reference(owner: String, rawUrl: String?): String? {
        if (closed || !ownerValid(owner)) return null
        val url = rawUrl?.let(::validUrl) ?: return null
        val key = hash("$owner|$url")
        synchronized(lock) {
            entries[key] = Entry(owner, url)
            while (entries.size > MAX_ENTRIES || entries.values.sumOf { it.url.length + it.owner.length + 100 } > MAX_INDEX_BYTES / 4) entries.remove(entries.keys.first())
        }
        writes.trySend(Unit)
        val fields = owner.split(':', limit = 2)
        return "cosmotune-artwork://${fields[0]}/${fields[1]}/$key?v=${hash(access(owner)?.revision.orEmpty()).take(12)}"
    }

    fun decorate(owner: String, track: ProviderTrack): ProviderTrack = track.copy(artworkReference = reference(owner, track.artwork))
    fun decorate(owner: String, page: ProviderCollection): ProviderCollection = page.copy(
        artwork = reference(owner, page.artwork), tracks = page.tracks.map { decorate(owner, it) },
        collections = page.collections.map { decorate(owner, it) })

    suspend fun resolve(reference: String): File = withContext(Dispatchers.IO) {
        val (owner, key) = parse(reference) ?: error("Invalid artwork reference")
        check(!closed) { "Artwork cache is closed" }
        val gate = synchronized(lock) { held.getOrPut(key) { Held() }.also { it.readers++ } }
        try { gate.mutex.withLock {
            val file = File(root, "$key.img")
            require(!java.nio.file.Files.isSymbolicLink(file.toPath()))
            val cached = file.takeIf { it.isFile && it.length() in 1..MAX_IMAGE_BYTES.toLong() }
            if (cached != null && System.currentTimeMillis() - cached.lastModified() in 0..FRESH_MS) return@withLock cached
            val entry = synchronized(lock) { entries[key]?.takeIf { it.owner == owner } }
            val policy = access(owner)
            if (entry == null || policy?.download != true) return@withLock cached ?: error("Artwork is unavailable")
            try { workers.withPermit {
                currentCoroutineContext().ensureActive()
                val network = ProviderNetwork(ProviderPermissions(network = policy.declaredDomains), policy.allowLocalNetwork,
                    client, allowPublicArtwork = true, localArtworkOrigin = policy.localOrigin)
                active[network] = owner
                try {
                    val bytes = providerRuntimeWork(network::cancel) { network.withResponse(entry.url, JsonObject(emptyMap())) { response ->
                        require(response.isSuccessful && response.body.contentLength() <= MAX_IMAGE_BYTES)
                        response.body.byteStream().use { input ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                check(!network.isCancelled)
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(out.size() <= MAX_IMAGE_BYTES - count)
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                    } }
                    require(ArtworkDimensions.read(bytes) != null) { "Unsupported or oversized artwork" }
                    currentCoroutineContext().ensureActive()
                    check(access(owner)?.download == true && !closed && !network.isCancelled)
                    val temporary = File.createTempFile("art-", ".part", root)
                    try {
                        temporary.ownerOnly()
                        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
                        ProviderStore.move(temporary, file)
                    } finally { temporary.delete() }
                    trim(file)
                    file
                } finally { active.remove(network); network.cancel() }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { cached ?: throw IllegalStateException("Artwork could not be loaded", error) }
        } } finally { synchronized(lock) { if (--gate.readers == 0) held.remove(key, gate) } }
    }

    fun cancel(owner: String) { active.filterValues { it == owner }.keys.forEach(ProviderNetwork::cancel) }

    private suspend fun trim(keep: File) = disk.withLock {
        val files = root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.img")) && it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .sortedByDescending { it.lastModified() }
        var bytes = 0L
        files.forEachIndexed { at, file ->
            bytes += file.length()
            if (file != keep && (at >= 512 || bytes > 128L * 1024 * 1024)) {
                require(file.canonicalFile.parentFile == root.canonicalFile)
                file.delete()
            }
        }
    }

    private suspend fun saveIndex() = disk.withLock {
        val snapshot = synchronized(lock) { entries.toMap() }
        val bytes = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_INDEX_BYTES) return@withLock
        val temporary = File.createTempFile("index-", ".part", root)
        try {
            temporary.ownerOnly()
            temporary.outputStream().use { it.write(protection.seal(bytes)); it.fd.sync() }
            ProviderStore.move(temporary, index)
        } catch (_: Exception) {
            // Disposable cache metadata never prevents playback or changes provider credentials.
        } finally { temporary.delete() }
    }

    override fun close() { closed = true; active.keys.forEach(ProviderNetwork::cancel); writes.close() }

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        private const val MAX_ENTRIES = 1_024
        private const val FRESH_MS = 24 * 60 * 60 * 1000L
        private val HASH = Regex("[a-f0-9]{64}")
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun ownerValid(owner: String) = owner.matches(Regex("(?:provider|addon):[a-z0-9][a-z0-9_-]{0,99}"))
        private fun validUrl(raw: String): String? = raw.takeIf { it.length <= 8_192 }?.toHttpUrlOrNull()?.takeIf {
            it.scheme in setOf("http", "https") && it.username.isEmpty() && it.password.isEmpty()
        }?.toString()
        private fun parse(raw: String): Pair<String, String>? = runCatching {
            require(raw.length <= 500)
            val uri = URI(raw)
            require(uri.scheme == "cosmotune-artwork" && uri.host in setOf("provider", "addon") && uri.userInfo == null && uri.port == -1)
            require(uri.fragment == null && (uri.query == null || uri.query.matches(Regex("v=[a-f0-9]{12}"))))
            val path = uri.path.removePrefix("/").split('/')
            require(path.size == 2 && HASH.matches(path[1]))
            val owner = "${uri.host}:${path[0]}"
            require(ownerValid(owner))
            owner to path[1]
        }.getOrNull()
        fun handles(raw: String) = raw.startsWith("cosmotune-artwork:")
        fun isReference(raw: String) = parse(raw) != null
    }
}
