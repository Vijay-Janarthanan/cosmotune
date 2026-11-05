package com.cosmotune.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A Firestore that lives in memory: the same preconditions and the same server-stamped `syncedAt`, so the sync can be tested without a network. */
internal class FakeCloudDocs : CloudDocs {
    private class Stored(val fields: JsonObject, val version: Int, val syncedAtMs: Long)

    private val store = mutableMapOf<String, Stored>()
    private var serverClock = 1_000_000L
    var commits = 0
    var writes = 0
    /** Runs once, just before the next commit is checked: another device getting in between a read and a write. */
    var beforeNextCommit: (() -> Unit)? = null

    private fun doc(path: String, s: Stored) = CloudDoc(path, s.fields, "v${s.version}", s.syncedAtMs)

    override suspend fun get(path: String): CloudDoc? = store[path]?.let { doc(path, it) }

    override suspend fun commit(writes: List<DocWrite>) {
        beforeNextCommit?.also { beforeNextCommit = null }?.invoke()
        for (w in writes) {
            val current = store[w.path]
            if (w.mustNotExist && current != null) throw CloudConflict("exists")
            if (w.expectUpdateTime != null && (current == null || "v${current.version}" != w.expectUpdateTime)) throw CloudConflict("changed")
        }
        commits++
        serverClock += 1_000
        for (w in writes) {
            this.writes++
            if (w.fields == null) store.remove(w.path)
            else store[w.path] = Stored(w.fields, (store[w.path]?.version ?: 0) + 1, serverClock)
        }
    }

    override suspend fun changedSince(parentPath: String, collectionId: String, sinceMs: Long): List<CloudDoc> =
        store.filterKeys { it.startsWith("$parentPath/$collectionId/") }.filterValues { it.syncedAtMs > sinceMs }
            .map { (path, s) -> doc(path, s) }.sortedBy { it.syncedAtMs }

    override suspend fun listIds(collectionPath: String): List<String> =
        store.keys.filter { it.startsWith("$collectionPath/") && it.removePrefix("$collectionPath/").indexOf('/') < 0 }.map { it.substringAfterLast('/') }

    /** Another device's write, done directly. */
    fun put(path: String, fields: JsonObject) {
        serverClock += 1_000
        store[path] = Stored(fields, (store[path]?.version ?: 0) + 1, serverClock)
    }
}

internal class FakeLibrary(initial: Map<String, List<SyncEntry>> = emptyMap()) : SyncSource {
    val kinds = initial.mapValues { (_, list) -> list.associateBy { it.id }.toMutableMap() }.toMutableMap()
    override suspend fun entries(kind: String) = kinds[kind]?.values?.toList().orEmpty()
    override suspend fun apply(kind: String, entries: List<SyncEntry>) {
        entries.forEach { kinds.getOrPut(kind) { mutableMapOf() }[it.id] = it }
    }
    fun edit(kind: String, entry: SyncEntry) { kinds.getOrPut(kind) { mutableMapOf() }[entry.id] = entry }
    fun ids(kind: String) = kinds[kind].orEmpty().filterValues { !it.deleted }.keys
}

class CloudSyncTest {
    private val docs = FakeCloudDocs()
    private fun v(n: Int): JsonObject = buildJsonObject { put("v", n) }
    private fun entry(id: String, at: Long, n: Int = 1, deleted: Boolean = false) = SyncEntry(id, at, deleted, v(n))

    private fun device(library: FakeLibrary, clock: () -> Long, kinds: List<String> = listOf("likes")) =
        CloudSync(docs, { "user-1" }, library, InMemorySyncMarks(), kinds, clock)

    @Test fun twoDevicesEndUpWithTheSameLibrary() = runBlocking<Unit> {
        val a = FakeLibrary(mapOf("likes" to listOf(entry("a", 1000), entry("b", 1000))))
        val b = FakeLibrary(mapOf("likes" to listOf(entry("c", 2000))))
        val syncA = device(a, { 5000 })
        val syncB = device(b, { 5000 })
        syncA.run(); syncB.run(); syncA.run()
        assertEquals(setOf("a", "b", "c"), a.ids("likes"))
        assertEquals(setOf("a", "b", "c"), b.ids("likes"))
    }

    @Test fun theNewerChangeWins() = runBlocking<Unit> {
        val a = FakeLibrary(mapOf("likes" to listOf(entry("x", 1000, n = 1))))
        val b = FakeLibrary(mapOf("likes" to listOf(entry("x", 2000, n = 2))))
        val syncA = device(a, { 5000 })
        syncA.run()
        device(b, { 5000 }).run()
        syncA.run()
        assertEquals(v(2), a.kinds["likes"]!!["x"]!!.data)
    }

    @Test fun aDeleteTravelsToTheOtherDevice() = runBlocking<Unit> {
        val a = FakeLibrary(mapOf("likes" to listOf(entry("x", 1000))))
        val b = FakeLibrary()
        val syncA = device(a, { 5000 })
        val syncB = device(b, { 5000 })
        syncA.run(); syncB.run()
        assertEquals(setOf("x"), b.ids("likes"))
        a.edit("likes", entry("x", 6000, deleted = true))
        val laterA = device(a, { 7000 })
        laterA.run(); syncB.run()
        assertTrue(b.ids("likes").isEmpty())
    }

    @Test fun aThousandLikesCostAHandfulOfWrites() = runBlocking<Unit> {
        val a = FakeLibrary(mapOf("likes" to List(1000) { entry("track-$it", 1000L + it) }))
        val report = device(a, { 5000 }).run()
        assertTrue(docs.writes <= CloudSync.BUCKETS, "writes=${docs.writes}")
        assertEquals(1000, report.sent)
    }

    @Test fun anUnchangedLibraryCostsNoWritesTheSecondTime() = runBlocking<Unit> {
        val a = FakeLibrary(mapOf("likes" to listOf(entry("a", 1000)), "playlists" to listOf(entry("p", 1000))))
        val sync = device(a, { 5000 }, listOf("likes", "playlists"))
        sync.run()
        val before = docs.writes
        val second = sync.run()
        assertEquals(before, docs.writes)
        assertEquals(0, second.sent)
    }

    @Test fun twoDevicesWritingTheSameBucketDoNotLoseEachOthersChanges() = runBlocking<Unit> {
        val bucket = "users/user-1/library/" + CloudSync.bucketName("likes", "a")
        // the same bucket for both ids, so the clash is real
        val id2 = (0 until 200).map { "id$it" }.first { CloudSync.bucketName("likes", it) == CloudSync.bucketName("likes", "a") }
        val a = FakeLibrary(mapOf("likes" to listOf(entry("a", 1000))))
        // another device writes this bucket after this one has read it and before it commits
        docs.beforeNextCommit = {
            docs.put(bucket, CloudSync.bucketFields("likes", mapOf(id2 to entry(id2, 3000))))
        }
        device(a, { 5000 }).run()
        val stored = CloudSync.entriesOf(docs.get(bucket)!!)
        assertEquals(setOf("a", id2), stored.keys)
        assertTrue(id2 in a.ids("likes"), "the other device's entry reached this device")
    }

    @Test fun deletesOlderThanThirtyDaysAreForgotten() {
        val old = entry("gone", 1000, deleted = true)
        val merged = CloudSync.merge(mapOf("gone" to old), mapOf("gone" to old), nowMs = 1000 + CloudSync.TOMBSTONE_MS + 1)
        assertTrue(merged.entries.isEmpty())
        assertTrue(merged.remoteOutOfDate, "the cloud copy still holds the forgotten delete, so it is rewritten")
    }

    @Test fun aTieIsSettledTheSameOnEveryDevice() {
        val alive = entry("t", 1000, n = 1)
        val deleted = entry("t", 1000, deleted = true)
        assertTrue(CloudSync.merge(mapOf("t" to alive), mapOf("t" to deleted), 2000).entries["t"]!!.deleted)
        assertTrue(CloudSync.merge(mapOf("t" to deleted), mapOf("t" to alive), 2000).entries["t"]!!.deleted)
    }

    @Test fun deletingAnAccountRemovesEveryDocument() = runBlocking<Unit> {
        val a = FakeLibrary(mapOf("likes" to listOf(entry("a", 1000))))
        val sync = device(a, { 5000 })
        sync.run()
        docs.put("users/user-1/settings/appearance", JsonObject(mapOf("x" to JsonPrimitive(1))))
        sync.deleteAllCloudData()
        assertTrue(docs.listIds("users/user-1/library").isEmpty())
        assertTrue(docs.listIds("users/user-1/settings").isEmpty())
    }
}
