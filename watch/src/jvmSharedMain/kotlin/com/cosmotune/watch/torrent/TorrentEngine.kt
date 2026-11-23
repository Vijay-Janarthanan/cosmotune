package com.cosmotune.watch.torrent

import com.cosmotune.watch.http.bool
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.int
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.long
import com.cosmotune.watch.http.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Cosmotune's bundled torrent engine (the Go helper) beyond streaming one file: reading what a torrent holds, downloading
 * chosen files with progress (resuming what is already in the folder), sharing a finished download, and making a torrent of the
 * listener's own folder. Torrents only ever come from links and files the listener opens or add-ons they installed: nothing here
 * searches for any. Each call runs its own helper process, which ends when the flow is no longer collected.
 */
class TorrentEngine(private val executable: () -> File?, private val settings: () -> TorrentSettings = { TorrentSettings() }) {
    /** .torrent files opened in this run, by info hash: the player and Downloads hand them to the helper again. */
    private val torrentFiles = ConcurrentHashMap<String, ByteArray>()

    fun remember(hash: String, torrentFile: ByteArray) { torrentFiles[hash] = torrentFile }
    fun torrentFileOf(hash: String): ByteArray? = torrentFiles[hash]

    /** The files of [link], or of the .torrent [file] (whose hash the engine reports). */
    suspend fun files(link: TorrentLink?, file: ByteArray? = null): TorrentListing {
        val first = withTimeoutOrNull(45_000) { run(request("files", link, file)).first { it.listing != null || it.error != null } }
            ?: error("No peers supplied the torrent's file list. Try again later.")
        first.error?.let { error(it) }
        val listing = checkNotNull(first.listing)
        if (file != null) remember(listing.hash, file)
        return listing
    }

    /** Downloads [indexes] of [hash] into [dir] (made if missing), seeding afterwards when [seed]; ends when done (or seeded). */
    fun download(hash: String, trackers: List<String>, dir: File, indexes: List<Int>, seed: Boolean, seedMinutes: Int): Flow<TorrentEvent> {
        dir.mkdirs()
        return run(request("download", TorrentLink(hash, trackers = trackers), torrentFiles[hash]) {
            put("Dir", dir.absolutePath)
            put("Indexes", buildJsonArray { indexes.forEach { add(it) } })
            put("Seed", seed)
            put("SeedMinutes", seedMinutes.coerceIn(0, 7 * 24 * 60))
        })
    }

    /** Makes a torrent of [path] (a file or a folder), gives its magnet link in the first event, and shares it for [seedMinutes]. */
    fun share(path: File, seedMinutes: Int): Flow<TorrentEvent> = run(request("share", null, null) {
        put("Path", path.absolutePath)
        put("SeedMinutes", seedMinutes.coerceIn(0, 7 * 24 * 60))
    })

    /** The helper's request line for streaming [hash] (with the .torrent opened for it and the chosen connection). */
    fun streamRequest(base: JsonObject, hash: String): JsonObject = JsonObject(base + buildJsonObject {
        torrentFiles[hash]?.let { put("Torrent", Base64.getEncoder().encodeToString(it)) }
        settings().bindInterface?.let { put("Bind", it) }
    })

    private fun request(mode: String, link: TorrentLink?, file: ByteArray?, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("Mode", mode)
        link?.let { put("Hash", it.hash); put("Trackers", buildJsonArray { it.trackers.take(8).forEach { t -> add(t.take(256)) } }) }
        file?.let { put("Torrent", Base64.getEncoder().encodeToString(it)) }
        settings().bindInterface?.let { put("Bind", it) }
        more()
    }

    /** Runs the helper with [request] and gives each line it prints; the helper is stopped when collection stops. */
    private fun run(request: JsonObject): Flow<TorrentEvent> = callbackFlow {
        val binary = executable()?.takeIf(File::isFile) ?: error("The torrent engine is missing from this Cosmotune build.")
        val process = ProcessBuilder(binary.absolutePath).redirectErrorStream(true).start()
        val reader = thread(name = "Cosmotune torrent engine", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) if (line.startsWith("{")) runCatching { parse(Json.parseToJsonElement(line)) }.getOrNull()?.let { trySend(it) }
                }
            }
            channel.close()
        }
        process.outputStream.write("$request\n".toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
        awaitClose { process.destroy(); reader.interrupt() }
    }.flowOn(Dispatchers.IO)

    companion object {
        fun parse(line: JsonElement): TorrentEvent {
            val files = line.list("files").mapNotNull { f ->
                val index = f.int("index") ?: return@mapNotNull null
                TorrentFile(index, f.str("path") ?: "File ${index + 1}", f.long("length") ?: 0, f.bool("video") == true)
            }
            val hash = line.str("hash")?.lowercase()
            val listing = if (hash != null && files.isNotEmpty()) TorrentListing(hash, line.str("name") ?: hash.take(8), files) else null
            val p = line.child("progress")
            return TorrentEvent(
                done = p.long("done") ?: 0, total = p.long("total") ?: 0, rateBytesPerSecond = p.long("rate") ?: 0, peers = p.int("peers") ?: 0,
                uploaded = p.long("uploaded") ?: 0, seeding = p.bool("seeding") == true, complete = line.bool("complete") == true,
                listing = listing, magnet = line.str("magnet"), error = line.str("error"),
            )
        }
    }
}

