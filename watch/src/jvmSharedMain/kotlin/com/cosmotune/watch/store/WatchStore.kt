package com.cosmotune.watch.store

import com.cosmotune.watch.model.MyListEntry
import com.cosmotune.watch.model.WatchProgress
import com.cosmotune.watch.plugin.UserSourceSpec
import com.cosmotune.watch.plugins.servers.ServerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A user source as kept: its spec (Jellyfin and Emby specs have the password removed once signed in; the [session] token
 * is kept instead), when it was added and changed, and its switch.
 */
@Serializable
data class StoredSource(
    val id: String,
    val spec: UserSourceSpec,
    val session: ServerSession? = null,
    val enabled: Boolean = true,
    val addedAtMs: Long,
    val updatedAtMs: Long = addedAtMs,
    /** The add-on's manifest name, shown on the Plugins page and in packs. */
    val name: String? = null,
)

@Serializable
data class WatchState(
    val version: Int = 1,
    /** Built-in plugin id to its switch, when changed from the default. */
    val builtIn: Map<String, Boolean> = emptyMap(),
    val sources: List<StoredSource> = emptyList(),
    val progress: Map<String, WatchProgress> = emptyMap(),
    val myList: List<MyListEntry> = emptyList(),
    val favourites: Set<String> = emptySet(),
    /** Plugin settings: `iptv.guides` (XMLTV addresses, one per line), `regions` ("in,us"). */
    val settings: Map<String, String> = emptyMap(),
    /** A stable random id for sign-ins and the free services' sessions. */
    val deviceId: String = "",
    /** Sources removed here, with when, so a sync does not bring them back. */
    val removed: Map<String, Long> = emptyMap(),
    val activeProfile: String = "default",
    val profiles: Map<String, com.cosmotune.watch.model.WatchProfile> = mapOf("default" to com.cosmotune.watch.model.WatchProfile("default", "Everyone")),
)

/** Legacy progress belongs to Everyone. The active lists stay in their old fields for backward compatibility. */
fun WatchState.allProfiles(): Map<String, com.cosmotune.watch.model.WatchProfile> = profiles +
    (activeProfile to (profiles[activeProfile] ?: com.cosmotune.watch.model.WatchProfile(activeProfile, "Everyone"))
        .copy(progress = progress, myList = myList))

fun WatchState.selectProfile(id: String): WatchState {
    val all = allProfiles()
    val selected = all[id] ?: return this
    return copy(activeProfile = id, profiles = all, progress = selected.progress, myList = selected.myList)
}

/**
 * Everything Watch keeps on the device, in one file sealed at rest (DPAPI on Windows, the Android Keystore on Android,
 * owner-only elsewhere), the way the provider and source-library stores keep theirs. Writes go to a temporary file and are
 * moved into place. A file that cannot be opened is left untouched and the store starts empty in memory.
 */
class WatchStore(private val file: File, private val protection: WatchProtection = watchProtection()) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    var recoveryMessage: String? = null
        private set
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<WatchState> = mutable.asStateFlow()

    private fun read(): WatchState {
        if (!file.exists()) return WatchState(deviceId = java.util.UUID.randomUUID().toString())
        return try {
            require(file.length() <= MAX_BYTES + 64 * 1024)
            json.decodeFromString<WatchState>(protection.open(file.readBytes()).toString(Charsets.UTF_8)).let {
                if (it.deviceId.isBlank()) it.copy(deviceId = java.util.UUID.randomUUID().toString()) else it
            }
        } catch (_: Exception) {
            recoveryMessage = "Watch's saved data could not be opened. Its file was left untouched."
            WatchState(deviceId = java.util.UUID.randomUUID().toString())
        }
    }

    suspend fun update(change: (WatchState) -> WatchState): WatchState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = mutable.value
            val next = trim(change(current))
            if (next == current) return@withContext current
            if (recoveryMessage == null) write(next)
            mutable.value = next
            next
        }
    }

    private fun trim(s: WatchState): WatchState = s.copy(
        progress = if (s.progress.size <= MAX_PROGRESS) s.progress else s.progress.values.sortedByDescending { it.updatedAtMs }.take(MAX_PROGRESS).associateBy { it.key.value },
        myList = s.myList.take(MAX_LIST),
        removed = s.removed.filterValues { System.currentTimeMillis() - it < 60L * 24 * 3_600_000 },
    )

    private fun write(state: WatchState) {
        val bytes = json.encodeToString(WatchState.serializer(), state).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Watch's saved data is full" }
        val parent = requireNotNull(file.absoluteFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()); runCatching { parent.ownerOnly() }
        val temporary = File.createTempFile("watch-", ".part", parent)
        try {
            runCatching { temporary.ownerOnly() }
            temporary.outputStream().use { it.write(protection.seal(bytes)); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }

    companion object {
        const val MAX_BYTES = 8 shl 20
        const val MAX_PROGRESS = 2_000
        const val MAX_LIST = 1_000
    }
}
