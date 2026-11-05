package com.cosmotune.reading.data

import com.cosmotune.manga.data.MangaFileProtection
import com.cosmotune.manga.data.UnsealedMangaFiles
import com.cosmotune.manga.data.ownerOnly
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Private reading metadata and OPDS credentials; callers on Android pass the host's protected-file implementation. */
class ReadingStore(val root: File, private val protection: MangaFileProtection = UnsealedMangaFiles) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(root.apply { mkdirs(); ownerOnly() }, "reading.json")
    private val mutex = Mutex()
    var recoveryMessage: String? = null
        private set
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    private fun load(): ReadingState {
        if (!file.exists()) return ReadingState()
        return try {
            require(file.length() <= MAX_BYTES + 64 * 1024)
            val bytes = protection.open(file.readBytes())
            require(bytes.size <= MAX_BYTES)
            json.decodeFromString<ReadingState>(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            recoveryMessage = "Saved reading data could not be opened. Its original file is preserved; check storage access and retry."
            ReadingState()
        }
    }

    suspend fun update(change: (ReadingState) -> ReadingState) = mutex.withLock { withContext(Dispatchers.IO) {
        check(recoveryMessage == null) { recoveryMessage.orEmpty() }
        val next = change(mutable.value)
        val bytes = json.encodeToString(next).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "The reading library exceeds the metadata limit." }
        val temporary = File.createTempFile("reading-", ".part", root)
        try {
            temporary.ownerOnly()
            temporary.outputStream().use { output -> output.write(protection.seal(bytes)); output.fd.sync() }
            move(temporary, file)
            mutable.value = next
        } finally { temporary.delete() }
    } }

    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
        internal fun move(from: File, to: File) {
            try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }
}
