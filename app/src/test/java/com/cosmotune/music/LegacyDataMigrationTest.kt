package com.cosmotune.music

import com.cosmotune.music.LegacyDataMigration.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time copy from the names before the rename, with in-memory stores. Names the old names on purpose. */
class LegacyDataMigrationTest {

    private class MemoryStore(initial: Map<String, Any?> = emptyMap(), var accepts: Boolean = true) : LegacyDataMigration.Store {
        val values = LinkedHashMap<String, Any?>(initial)
        var writes = 0
        override fun read(): Map<String, Any?> = LinkedHashMap(values)
        override fun write(values: Map<String, Any?>): Boolean {
            writes++
            if (!accepts) return false
            this.values += values
            return true
        }
    }

    private class MemoryFlag(var set: Boolean = false) : LegacyDataMigration.Flag {
        override fun isSet() = set
        override fun set() { set = true }
    }

    private val logged = mutableListOf<String>()
    private val log: (String, Throwable?) -> Unit = { message, _ -> logged += message }

    private fun job(old: MemoryStore?, new: MemoryStore, label: String = "bitchord_settings") =
        LegacyDataMigration.Job(label, source = { old }, target = { new })

    private val everyType: Map<String, Any?> = mapOf(
        "theme" to "dark",
        "crossfade" to true,
        "volume_steps" to 12,
        "last_sync" to 1_789_836_644_489L,
        "speed" to 1.25f,
        "hidden_artists" to setOf("UC1", "UC2"),
    )

    @Test
    fun `copies entries of every type and keeps the old store`() {
        val old = MemoryStore(everyType)
        val new = MemoryStore()
        val flag = MemoryFlag()

        assertEquals(Outcome.COPIED, LegacyDataMigration.copy(flag, listOf(job(old, new)), log))

        assertEquals(everyType, new.values)
        assertEquals("the old store is left as it was", everyType, old.values)
        assertTrue(flag.set)
    }

    @Test
    fun `a second run does nothing`() {
        val old = MemoryStore(everyType)
        val new = MemoryStore()
        val flag = MemoryFlag()
        LegacyDataMigration.copy(flag, listOf(job(old, new)), log)
        new.values["theme"] = "light" // the app writes after the copy
        old.values["theme"] = "amoled" // nothing should read the old store again

        var opened = false
        val watching = LegacyDataMigration.Job("bitchord_settings", source = { opened = true; old }, target = { new })
        assertEquals(Outcome.ALREADY_DONE, LegacyDataMigration.copy(flag, listOf(watching), log))

        assertFalse("the old store is not even opened", opened)
        assertEquals("light", new.values["theme"])
        assertEquals(1, new.writes)
    }

    @Test
    fun `values already under the new name win`() {
        val old = MemoryStore(mapOf("theme" to "dark", "speed" to 1.0f))
        val new = MemoryStore(mapOf("theme" to "light"))

        LegacyDataMigration.copy(MemoryFlag(), listOf(job(old, new)), log)

        assertEquals(mapOf("theme" to "light", "speed" to 1.0f), new.values)
    }

    @Test
    fun `is idempotent when run again before the flag was stored`() {
        val old = MemoryStore(everyType)
        val new = MemoryStore()
        LegacyDataMigration.copy(MemoryFlag(), listOf(job(old, new)), log)
        val afterFirst = LinkedHashMap(new.values)

        LegacyDataMigration.copy(MemoryFlag(), listOf(job(old, new)), log)

        assertEquals(afterFirst, new.values)
        assertEquals("nothing left to write the second time", 1, new.writes)
    }

    @Test
    fun `nothing stored under the old names still sets the flag`() {
        val flag = MemoryFlag()
        val new = MemoryStore()

        assertEquals(Outcome.COPIED, LegacyDataMigration.copy(flag, listOf(job(null, new), job(MemoryStore(), new)), log))

        assertTrue(flag.set)
        assertEquals(0, new.writes)
    }

    @Test
    fun `an unreadable old store is skipped for good and the others are still copied`() {
        val flag = MemoryFlag()
        val broken = LegacyDataMigration.Job("bitchord_auth", source = { throw IllegalStateException("keyset") }, target = { MemoryStore() })
        val alsoBroken = LegacyDataMigration.Job(
            "bitchord_sources",
            source = { object : LegacyDataMigration.Store {
                override fun read(): Map<String, Any?> = throw SecurityException("keystore")
                override fun write(values: Map<String, Any?>) = false
            } },
            target = { MemoryStore() },
        )
        val new = MemoryStore()

        val outcome = LegacyDataMigration.copy(flag, listOf(broken, alsoBroken, job(MemoryStore(everyType), new)), log)

        assertEquals(Outcome.COPIED, outcome)
        assertEquals(everyType, new.values)
        assertTrue("no later start could read it either", flag.set)
    }

    @Test
    fun `a failing new store leaves the flag unset so the next start tries again`() {
        val flag = MemoryFlag()
        val refusing = MemoryStore(accepts = false)
        val throwing = LegacyDataMigration.Job("bitchord_widget", source = { MemoryStore(everyType) }, target = { throw IllegalStateException("disk full") })
        val fine = MemoryStore()

        val outcome = LegacyDataMigration.copy(
            flag,
            listOf(job(MemoryStore(everyType), refusing), throwing, job(MemoryStore(mapOf("a" to "b")), fine, "bitchord_last_played")),
            log,
        )

        assertEquals(Outcome.RETRY_LATER, outcome)
        assertFalse(flag.set)
        assertEquals("the stores that work are copied anyway", mapOf("a" to "b"), fine.values)

        refusing.accepts = true
        assertEquals(Outcome.RETRY_LATER, LegacyDataMigration.copy(flag, listOf(job(MemoryStore(everyType), refusing), throwing), log))
        assertEquals(everyType, refusing.values)
        assertEquals(Outcome.COPIED, LegacyDataMigration.copy(flag, listOf(job(MemoryStore(everyType), refusing)), log))
        assertTrue(flag.set)
    }

    @Test
    fun `a throwing flag never escapes`() {
        val flag = object : LegacyDataMigration.Flag {
            override fun isSet(): Boolean = throw IllegalStateException("prefs")
            override fun set() = throw IllegalStateException("prefs")
        }
        assertEquals(Outcome.RETRY_LATER, LegacyDataMigration.copy(flag, listOf(job(MemoryStore(everyType), MemoryStore())), log))

        val unstorable = object : LegacyDataMigration.Flag {
            override fun isSet() = false
            override fun set() = throw IllegalStateException("prefs")
        }
        assertEquals(Outcome.RETRY_LATER, LegacyDataMigration.copy(unstorable, listOf(job(MemoryStore(everyType), MemoryStore())), log))
    }

    @Test
    fun `values SharedPreferences cannot hold are left out`() {
        val new = MemoryStore()
        LegacyDataMigration.copy(MemoryFlag(), listOf(job(MemoryStore(mapOf("ok" to "yes", "null" to null, "odd" to listOf(1), "mixed" to setOf("a", 1))), new)), log)
        assertEquals(mapOf("ok" to "yes"), new.values)
    }

    @Test
    fun `every store of the old names has a new name of its own`() {
        val plain = LegacyDataMigration.PLAIN_STORES
        val encrypted = LegacyDataMigration.ENCRYPTED_STORES
        val olds = plain.map { it.first } + encrypted.flatMap { listOf(it.old, it.oldPlain) }
        val news = plain.map { it.second } + encrypted.flatMap { listOf(it.new, it.newPlain) }
        assertTrue(olds.all { it.startsWith("bitchord_") })
        assertTrue(news.all { it.startsWith("cosmotune_") })
        assertEquals(olds.map { it.removePrefix("bitchord_") }, news.map { it.removePrefix("cosmotune_") })
        assertEquals(news.size, news.toSet().size)
        assertFalse(LegacyDataMigration.FLAG_STORE in news)
    }
}
