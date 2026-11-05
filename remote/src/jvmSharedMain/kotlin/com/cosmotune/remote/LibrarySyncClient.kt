package com.cosmotune.remote

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Ephemeral pairing credentials stay in memory and never enter the journal or diagnostics. */
class LibrarySyncClient(pairingLink: String) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val origin: String
    private val key: String
    init {
        val uri = URI(pairingLink.trim())
        require(uri.scheme == "http" && uri.userInfo == null && uri.path in listOf("", "/") && uri.query == null && uri.port in 1..65535)
        // Literal IPv4 only: no DNS rebinding, redirect following or public endpoints.
        require(uri.host?.matches(Regex("[0-9.]{7,15}")) == true)
        val address = InetAddress.getByName(uri.host)
        require(address is Inet4Address && (address.isLoopbackAddress || address.isSiteLocalAddress) && !address.isAnyLocalAddress)
        key = uri.rawFragment.orEmpty().removePrefix("key=")
        require(key.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Paste the current private pairing link from the other device." }
        origin = "http://${uri.host}:${uri.port}"
    }
    data class Preview(val peer: String, val through: Long, val incoming: List<SyncRecord>, val outgoing: List<SyncRecord>,
        val localThrough: Long, val remoteAck: Long)
    suspend fun preview(store: LibrarySyncStore): Preview = withContext(Dispatchers.IO) {
        // Discover stable peer identity before using this device's saved cursor.
        val identity = json.decodeFromString<SyncPage>(request("/api/sync/catalogue?after=0&limit=1"))
        require(identity.device != store.device) { "This pairing link belongs to this device." }
        var after = store.cursor(identity.device)
        val incoming = ArrayList<SyncRecord>()
        var pages = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            require(++pages <= 20_001) { "The peer catalogue did not finish." }
            val page = json.decodeFromString<SyncPage>(request("/api/sync/catalogue?after=$after&limit=16"))
            require(page.device == identity.device && page.through >= after && page.records.size <= 64)
            page.records.forEach(LibrarySyncStore::validate)
            require(page.records.all { it.sequence > after && it.sequence <= page.through } &&
                page.records.zipWithNext().all { (left, right) -> left.sequence < right.sequence }) { "The peer returned an inconsistent journal page." }
            incoming += page.records
            require(incoming.size <= 20_000)
            if (page.next == null) { after = page.through; break }
            require(page.next > after && page.next == page.through)
            after = page.next
        }
        val ack = json.parseToJsonElement(request("/api/sync/ack?peer=${store.device}")).jsonObject.getValue("through").jsonPrimitive.long
        require(ack in 0..store.sequence()) { "The peer's journal is ahead of this copy. Restore the current journal before syncing." }
        Preview(identity.device, after, incoming, store.records().filter { it.sequence > ack }, store.sequence(), ack)
    }
    suspend fun apply(store: LibrarySyncStore, preview: Preview, files: Boolean): Int = withContext(Dispatchers.IO) {
        val transferContext = currentCoroutineContext()
        require(preview.localThrough == store.sequence()) { "Your library changed. Preview synchronization again." }
        var changed = 0
        // Merge before sending so a higher-stamped deletion wins in both directions.
        preview.incoming.chunked(32).forEach { changed += store.merge(it) }
        val outgoing = store.records()
        outgoing.filter { it.sequence > preview.remoteAck }.chunked(16).forEach { rows ->
            currentCoroutineContext().ensureActive()
            request("/api/sync/merge", json.encodeToString(SyncPush(rows)))
        }
        if (files) {
            for (record in outgoing.filterNot { it.deleted }) {
                currentCoroutineContext().ensureActive()
                val descriptor = record.file ?: continue
                if (store.file(descriptor.hash) == null) {
                    store.receive(descriptor) { offset -> transferContext.ensureActive(); json.decodeFromString(request("/api/sync/file?hash=${descriptor.hash}&offset=$offset")) }
                } else {
                    var offset = json.parseToJsonElement(request("/api/sync/file-offset?hash=${descriptor.hash}")).jsonObject.getValue("offset").jsonPrimitive.long
                    require(offset in 0..descriptor.size)
                    while (offset < descriptor.size) {
                        currentCoroutineContext().ensureActive()
                        val chunk = store.chunk(descriptor.hash, offset)
                        val next = json.parseToJsonElement(request("/api/sync/file", json.encodeToString(chunk))).jsonObject.getValue("offset").jsonPrimitive.long
                        require(next > offset && next <= descriptor.size); offset = next
                    }
                }
            }
        }
        request("/api/sync/ack", json.encodeToString(SyncAck(store.device, store.sequence())))
        store.acknowledge(preview.peer, preview.through)
        changed
    }
    private fun request(path: String, body: String? = null): String {
        val connection = URI(origin + path).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5_000; connection.readTimeout = 10_000
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Cache-Control", "no-store")
        try {
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 8 * 1024 * 1024) { "This sync batch is too large." }
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            require(connection.responseCode == 200) { "The paired device could not finish synchronization (HTTP ${connection.responseCode}). Check its current link and sharing switch." }
            return connection.inputStream.use { source ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = source.read(buffer); if (n < 0) break; require(output.size() + n <= 8 * 1024 * 1024); output.write(buffer, 0, n) }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }
    override fun toString() = "LibrarySyncClient(paired device, credentials hidden)"
}
