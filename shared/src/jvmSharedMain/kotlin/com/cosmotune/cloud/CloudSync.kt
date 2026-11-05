package com.cosmotune.cloud

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One item of the library (a like, a playlist, a saved artist) with when it last changed. A delete is kept as [deleted] so it can travel. */
data class SyncEntry(val id: String, val updatedAt: Long, val deleted: Boolean = false, val data: JsonObject = JsonObject(emptyMap()))

/** What the app's own library offers the sync. [kind] names a group of entries ("likes", "playlists", "artists"). */
interface SyncSource {
    /** Every entry of a kind, deletes included. */
    suspend fun entries(kind: String): List<SyncEntry>

    /** Takes the entries that came from the cloud and are newer than what the device had. */
    suspend fun apply(kind: String, entries: List<SyncEntry>)
}

/** Where the sync remembers how far it got. */
interface SyncMarks {
    var pulledMs: Long
    var pushedMs: Long
}

class InMemorySyncMarks : SyncMarks {
    override var pulledMs: Long = 0
    override var pushedMs: Long = 0
}

/** What one run did, for the settings page ("last synced, 3 sent, 5 received"). */
data class SyncReport(val sent: Int, val received: Int, val documentsRead: Int, val documentsWritten: Int)

/**
 * Keeps the library the same on every device. The device is the truth: nothing here is needed to use the app.
 *
 * Entries are grouped into a few documents ("buckets") per kind instead of one document each, because the free Firestore plan counts
 * writes per document, per project, per day: sending a thousand likes costs a handful of writes, not a thousand.
 * Two devices that change the same entry settle by the newer time; a delete is an entry marked deleted and is forgotten after 30 days.
 * A bucket is written only if it is still the version that was read, so two devices syncing at once cannot overwrite each other.
 */
class CloudSync(
    private val docs: CloudDocs,
    private val uid: () -> String,
    private val source: SyncSource,
    private val marks: SyncMarks,
    private val kinds: List<String>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): SyncReport {
        val root = "users/${uid()}"
        val startedAt = now()
        var read = 0
        var written = 0
        var sent = 0
        var received = 0

        val changed = docs.changedSince(root, COLLECTION, marks.pulledMs).filter { kindOf(bucketOf(it.path)) in kinds }
        read += changed.size
        val remoteDocs = changed.associateBy { bucketOf(it.path) }.toMutableMap()

        val local = kinds.associateWith { kind -> source.entries(kind).associateBy(SyncEntry::id) }
        val dirty = buildSet {
            for ((kind, entries) in local) for (entry in entries.values) if (entry.updatedAt > marks.pushedMs) add(bucketName(kind, entry.id))
        }

        val toApply = mutableMapOf<String, MutableList<SyncEntry>>()
        for (bucket in remoteDocs.keys + dirty) {
            val kind = kindOf(bucket)
            val mine = local[kind].orEmpty().filterValues { bucketName(kind, it.id) == bucket }
            var attempt = 0
            var fromRemote = emptyList<SyncEntry>()
            while (true) {
                val remoteDoc = remoteDocs[bucket] ?: docs.get("$root/$COLLECTION/$bucket").also { if (it != null) read++ }
                val remote = remoteDoc?.let(::entriesOf).orEmpty()
                val merged = merge(mine, remote, startedAt)
                // only the last merge counts: after a conflict the bucket was read again and may hold more than the first read did
                fromRemote = merged.fromRemote
                if (!merged.remoteOutOfDate) break
                val write = DocWrite(
                    path = "$root/$COLLECTION/$bucket",
                    fields = bucketFields(kind, merged.entries),
                    expectUpdateTime = remoteDoc?.updateTime,
                    mustNotExist = remoteDoc == null,
                )
                try {
                    docs.commit(listOf(write))
                    written++
                    sent += merged.entries.values.count { remote[it.id] != it }
                    break
                } catch (conflict: CloudConflict) {
                    // another device wrote this bucket after it was read: read it again and merge again
                    if (++attempt >= MAX_ATTEMPTS) throw conflict
                    remoteDocs.remove(bucket)
                }
            }
            toApply.getOrPut(kind) { mutableListOf() } += fromRemote
            received += fromRemote.size
        }

        for ((kind, entries) in toApply) if (entries.isNotEmpty()) source.apply(kind, entries)
        marks.pulledMs = maxOf(marks.pulledMs, changed.maxOfOrNull { it.syncedAtMs } ?: 0L)
        marks.pushedMs = startedAt
        return SyncReport(sent, received, read, written)
    }

    /** Deletes everything this account stored, for "delete my account". The account itself is deleted by the caller afterwards. */
    suspend fun deleteAllCloudData() {
        val root = "users/${uid()}"
        for (collection in listOf(COLLECTION, "playlists", "settings", "log", "devices")) {
            val ids = docs.listIds("$root/$collection")
            ids.chunked(400).forEach { chunk -> docs.commit(chunk.map { DocWrite("$root/$collection/$it", null) }) }
        }
        docs.get(root)?.let { docs.commit(listOf(DocWrite(root, null))) }
    }

    internal class Merged(val entries: Map<String, SyncEntry>, val fromRemote: List<SyncEntry>, val remoteOutOfDate: Boolean)

    internal companion object {
        const val COLLECTION = "library"
        const val BUCKETS = 8
        const val MAX_ATTEMPTS = 3
        const val TOMBSTONE_MS = 30L * 24 * 60 * 60 * 1000

        fun bucketName(kind: String, id: String): String = "$kind-${(id.hashCode() and Int.MAX_VALUE) % BUCKETS}"
        fun bucketOf(path: String): String = path.substringAfterLast('/')
        fun kindOf(bucket: String): String = bucket.substringBeforeLast('-')

        /** The newer entry wins; at the same time a delete wins, and otherwise this device's own entry stays. Old deletes are dropped. */
        fun merge(local: Map<String, SyncEntry>, remote: Map<String, SyncEntry>, nowMs: Long): Merged {
            val result = mutableMapOf<String, SyncEntry>()
            val fromRemote = mutableListOf<SyncEntry>()
            for (id in local.keys + remote.keys) {
                val mine = local[id]
                val theirs = remote[id]
                val winner = when {
                    mine == null -> theirs!!
                    theirs == null -> mine
                    theirs.updatedAt > mine.updatedAt -> theirs
                    theirs.updatedAt < mine.updatedAt -> mine
                    theirs.deleted && !mine.deleted -> theirs
                    else -> mine
                }
                if (winner === theirs && winner != mine) fromRemote += winner
                if (winner.deleted && nowMs - winner.updatedAt > TOMBSTONE_MS) continue
                result[id] = winner
            }
            // the cloud copy is stale if it lacks an entry or holds an older version of one, or still holds a forgotten delete
            val stale = result.any { (id, entry) -> remote[id] != entry } || remote.keys.any { it !in result }
            return Merged(result, fromRemote, stale)
        }

        fun bucketFields(kind: String, entries: Map<String, SyncEntry>): JsonObject = buildJsonObject {
            put("kind", kind)
            put("entries", JsonObject(entries.mapValues { (_, e) ->
                buildJsonObject {
                    put("updatedAt", e.updatedAt)
                    put("deleted", e.deleted)
                    put("data", e.data)
                }
            }))
        }

        fun entriesOf(doc: CloudDoc): Map<String, SyncEntry> {
            val entries = doc.fields["entries"] as? JsonObject ?: return emptyMap()
            return entries.mapNotNull { (id, raw) ->
                val e = (raw as? JsonObject) ?: return@mapNotNull null
                val updatedAt = (e["updatedAt"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                id to SyncEntry(id, updatedAt, (e["deleted"] as? JsonPrimitive)?.booleanOrNull ?: false, (e["data"] as? JsonElement)?.let { it as? JsonObject } ?: JsonObject(emptyMap()))
            }.toMap()
        }
    }
}
