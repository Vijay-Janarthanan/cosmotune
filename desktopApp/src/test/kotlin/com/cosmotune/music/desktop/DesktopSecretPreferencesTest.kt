package com.cosmotune.music.desktop

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** LK-20: desktop credentials never stay in a plain preference value. In-memory store and temporary folders only. */
class DesktopSecretPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    private class MemoryStore : DesktopSecretPreferences.Store {
        val values = linkedMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    /** Stands in for DPAPI: reversible, and visibly not the plain bytes. */
    private object Reversing : DesktopSecretPreferences.Sealer {
        override fun protect(plain: ByteArray) = plain.reversedArray().map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        override fun unprotect(sealed: ByteArray) = sealed.map { (it.toInt() xor 0x5a).toByte() }.toByteArray().reversedArray()
    }

    private val token = "fixture-token-value"

    @Test fun sealedHostsKeepOnlyTheSealedValueAndMigrateTheOldPlainKey() {
        val store = MemoryStore().apply { values["listenbrainz_token"] = token; values["lastfm_enabled"] = "true" }
        val secrets = DesktopSecretPreferences(store, Reversing, directory = null)
        assertEquals(token, secrets.read("listenbrainz_token"))
        assertNull(store.values["listenbrainz_token"], "the plain value is removed on first read")
        assertTrue(store.values.values.none { token in it }, "no stored preference contains the credential")
        assertEquals("true", store.values["lastfm_enabled"], "ordinary settings are left alone")
        assertEquals(token, DesktopSecretPreferences(store, Reversing, null).read("listenbrainz_token"), "survives a restart")
        secrets.write("listenbrainz_token", "")
        assertEquals("", secrets.read("listenbrainz_token"))
        assertTrue(store.values.keys.none { it.startsWith("listenbrainz_token") })
    }

    @Test fun otherDesktopsUseOwnerOnlyFilesAndNoPreference() {
        val store = MemoryStore().apply { values["lastfm_session_key"] = token }
        val folder = temporary.newFolder("credentials")
        val secrets = DesktopSecretPreferences(store, sealer = null, directory = folder)
        assertEquals(token, secrets.read("lastfm_session_key"))
        assertTrue(store.values.isEmpty())
        val file = java.io.File(folder, "lastfm_session_key")
        assertEquals(token, file.readText())
        runCatching { java.nio.file.Files.getPosixFilePermissions(file.toPath()) }.getOrNull()?.let { permissions ->
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), permissions)
        }
        secrets.write("google_cookie_AbC123", "cookie=fixture")
        assertEquals("cookie=fixture", secrets.read("google_cookie_AbC123"))
        secrets.write("lastfm_session_key", "")
        assertFalse(file.exists())
        assertTrue(store.values.isEmpty())
    }

    @Test fun aSealerThatFailsNeverFallsBackToPlainText() {
        val store = MemoryStore()
        val failing = object : DesktopSecretPreferences.Sealer {
            override fun protect(plain: ByteArray): ByteArray? = null
            override fun unprotect(sealed: ByteArray): ByteArray? = null
        }
        assertFailsWith<IllegalStateException> { DesktopSecretPreferences(store, failing, null).write("lastfm_api_secret", token) }
        assertTrue(store.values.isEmpty())
    }
}
