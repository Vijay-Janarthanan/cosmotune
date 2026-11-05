package com.cosmotune.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

/**
 * Finds out what changed in a store that keeps no change times, by comparing it with how it looked at the last sync.
 * A playlist whose content differs from the remembered one was edited now; an id that has disappeared was deleted now.
 * That lets the existing stores (which have no `updatedAt` and no deletes-as-data) take part in sync without being changed.
 */
class SnapshotJournal(private val file: File?, private val now: () -> Long = System::currentTimeMillis) {
    @Serializable
    private data class Record(val hash: String, val updatedAt: Long, val deleted: Boolean)

    private val json = Json { ignoreUnknownKeys = true }
    private val records: MutableMap<String, MutableMap<String, Record>> = load()

    private fun load(): MutableMap<String, MutableMap<String, Record>> = runCatching {
        json.decodeFromString<Map<String, Map<String, Record>>>(file!!.readText()).mapValues { it.value.toMutableMap() }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun save() {
        val target = file ?: return
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(json.encodeToString(records))
        if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
    }

    /** The entries of one kind as they stand now: edits stamped with this moment, deletes kept as deleted entries. */
    @Synchronized
    fun reconcile(kind: String, current: Map<String, JsonObject>): List<SyncEntry> {
        val known = records.getOrPut(kind) { mutableMapOf() }
        val stamp = now()
        val out = mutableListOf<SyncEntry>()
        for ((id, data) in current) {
            val hash = hash(data)
            val record = known[id]?.takeIf { it.hash == hash && !it.deleted } ?: Record(hash, stamp, false).also { known[id] = it }
            out += SyncEntry(id, record.updatedAt, false, data)
        }
        for ((id, record) in known.toMap()) {
            if (id in current) continue
            val gone = if (record.deleted) record else Record("", stamp, true).also { known[id] = it }
            out += SyncEntry(id, gone.updatedAt, true)
        }
        save()
        return out
    }

    /** Remembers what the store holds after cloud entries were written into it, with the cloud's times, so they are not mistaken for local edits. */
    @Synchronized
    fun accept(kind: String, applied: List<SyncEntry>, after: Map<String, JsonObject>) {
        val known = records.getOrPut(kind) { mutableMapOf() }
        for (entry in applied) {
            known[entry.id] = if (entry.deleted) Record("", entry.updatedAt, true) else Record(hash(after[entry.id] ?: entry.data), entry.updatedAt, false)
        }
        save()
    }

    private fun hash(data: JsonObject): String =
        MessageDigest.getInstance("SHA-256").digest(data.toString().toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * A [SyncSource] over a store described by two small functions. The platform says how to read the store as `id -> data` and how to write
 * changes back; the journal supplies the change times.
 */
abstract class JournalSyncSource(private val journal: SnapshotJournal) : SyncSource {
    /** The store's content for a kind as device-independent JSON (no file paths or device ids). */
    protected abstract suspend fun snapshot(kind: String): Map<String, JsonObject>

    /** Writes cloud changes into the store. [deletes] are ids to remove; [upserts] are ids to create or replace. */
    protected abstract suspend fun write(kind: String, upserts: Map<String, JsonObject>, deletes: Set<String>)

    override suspend fun entries(kind: String): List<SyncEntry> = journal.reconcile(kind, snapshot(kind))

    override suspend fun apply(kind: String, entries: List<SyncEntry>) {
        write(kind, entries.filter { !it.deleted }.associate { it.id to it.data }, entries.filter { it.deleted }.map { it.id }.toSet())
        val after = snapshot(kind)
        // Only what the store really took is recorded. A cloud entry the store refused (a full list, a store not open yet) recorded as
        // applied would read as removed on this device at the next sync, and that delete would travel to every other device.
        journal.accept(kind, entries.filter { if (it.deleted) it.id !in after else it.id in after }, after)
    }
}
