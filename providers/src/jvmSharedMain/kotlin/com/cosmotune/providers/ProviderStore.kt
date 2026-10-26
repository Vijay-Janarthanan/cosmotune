package com.cosmotune.providers

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable data class ProviderInstall(val manifest: ProviderManifest, val sha256: String, val enabled: Boolean = true,
    val repositoryUrl: String? = null, val installedAtMs: Long = System.currentTimeMillis(), val allowLocalNetwork: Boolean = false,
    val allowArtwork: Boolean = false)
@Serializable data class ProviderRepositoryConfig(val address: String, val title: String, val index: ProviderRepositoryIndex? = null,
    val refreshedAtMs: Long = 0)
@Serializable data class ProviderState(val version: Int = 1, val installed: List<ProviderInstall> = emptyList(),
    val repositories: List<ProviderRepositoryConfig> = emptyList(), val settings: Map<String, JsonObject> = emptyMap(),
    val storage: Map<String, JsonObject> = emptyMap(), val credentials: Map<String, JsonObject> = emptyMap(),
    val auth: Map<String, JsonObject> = emptyMap(), val cookies: Map<String, List<StoredProviderCookie>> = emptyMap())

/** Provider tokens and settings stay in protected app data and outside portable configuration exports. */
class ProviderStore(val root: File, internal val protection: ProviderFileProtection = providerFileProtection()) {
    internal val cookieJars = java.util.concurrent.ConcurrentHashMap<String, ProviderCookieJar>()
    internal val signedSessionLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val platformSessionCallback by lazy { providerSessionCallback() }
    @Volatile private var configuredSessionCallback: String? = null
    internal var defaultSessionCallback: String
        get() = configuredSessionCallback ?: platformSessionCallback
        set(value) { configuredSessionCallback = value }
    private val file = File(root.apply { check(isDirectory || mkdirs()); ownerOnly() }, "providers.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val diskLock = diskLocks.computeIfAbsent(file.canonicalPath) { Any() }
    var recoveryMessage: String? = null
        private set
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    private fun load(): ProviderState = synchronized(diskLock) {
        if (!file.exists()) return@synchronized ProviderState()
        try {
            require(file.length() <= MAX_BYTES + 64 * 1024)
            val bytes = protection.open(file.readBytes())
            require(bytes.size <= MAX_BYTES)
            json.decodeFromString<ProviderState>(ProviderPackages.utf8(bytes)).also { validate(it) }
        } catch (_: Exception) {
            recoveryMessage = "Provider settings could not be opened. Their original file was left untouched."
            ProviderState()
        }
    }

    suspend fun update(change: (ProviderState) -> ProviderState) = lock.withLock { withContext(Dispatchers.IO) {
        check(recoveryMessage == null) { recoveryMessage.orEmpty() }
        val next = change(mutable.value).also { validate(it) }
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Provider settings exceed the 4 MB storage limit" }
        val temporary = File.createTempFile("providers-", ".part", root)
        try {
            temporary.ownerOnly()
            temporary.outputStream().use { it.write(protection.seal(bytes)); it.fd.sync() }
            synchronized(diskLock) { move(temporary, file); mutable.value = next }
        } finally { temporary.delete() }
    } }

    private fun validate(state: ProviderState) {
        require(state.version == 1 && state.installed.size <= 100 && state.repositories.size <= 30)
        require(state.installed.map { it.manifest.name }.distinct().size == state.installed.size)
        require(state.cookies.size <= 100 && state.cookies.values.all { values -> values.size <= 512 &&
            values.sumOf { it.origin.length.toLong() + it.value.length } <= 512 * 1024 && values.all { it.origin.length <= 512 && it.value.length <= 8_192 } })
        state.installed.forEach { item ->
            item.manifest.validate()
            require(item.sha256.matches(Regex("[a-f0-9]{64}")))
        }
    }

    internal companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        private val diskLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun move(source: File, target: File) {
            try { Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}
