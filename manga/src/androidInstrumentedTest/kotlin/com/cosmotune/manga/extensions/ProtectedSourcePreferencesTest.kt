package com.cosmotune.manga.extensions

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cosmotune.manga.data.platformMangaFileProtection
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtectedSourcePreferencesTest {
    @Test fun typedPreferencesAreEncryptedReopenableAndOutsideSystemBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "source-prefs-test-${UUID.randomUUID()}.bin")
        val protection = platformMangaFileProtection()
        val prefs = ProtectedSourcePreferences(file, protection)
        val changed = CountDownLatch(1)
        val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "token") changed.countDown() }
        prefs.registerOnSharedPreferenceChangeListener(observer)
        assertTrue(prefs.edit().putString("token", "private-source-token-தமிழ்").putBoolean("enabled", true).putInt("count", 7)
            .putLong("timestamp", 99L).putFloat("scale", 1.5f).putStringSet("tags", setOf("one", "two")).commit())
        assertTrue(changed.await(5, TimeUnit.SECONDS))
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("private-source-token"))
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        val reopened = ProtectedSourcePreferences(file, protection)
        assertEquals("private-source-token-தமிழ்", reopened.getString("token", null))
        assertTrue(reopened.getBoolean("enabled", false)); assertEquals(7, reopened.getInt("count", 0))
        assertEquals(99L, reopened.getLong("timestamp", 0L)); assertEquals(1.5f, reopened.getFloat("scale", 0f), 0f)
        assertEquals(setOf("one", "two"), reopened.getStringSet("tags", null))
        @Suppress("UNCHECKED_CAST") val copy = reopened.getStringSet("tags", null) as MutableSet<String>
        copy.clear(); assertEquals(2, reopened.getStringSet("tags", null)?.size)
        val dataStore = SourcePreferenceDataStore(reopened)
        dataStore.putString("setting", "updated")
        assertEquals("updated", dataStore.getString("setting", null))
        // A synchronous commit also waits for the preceding apply to reach durable storage.
        assertTrue(reopened.edit().remove("token").commit())
        assertFalse(ProtectedSourcePreferences(file, protection).contains("token"))
        val original = file.readBytes()
        assertFalse(reopened.edit().putString("oversized", "x".repeat(70_000)).commit())
        assertArrayEquals(original, file.readBytes())
        prefs.unregisterOnSharedPreferenceChangeListener(observer)
    }

    @Test fun legacyValuesMigrateOnlyAfterProtectedReadbackAndCorruptionStaysUntouched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "source_fixture_${UUID.randomUUID()}"
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("token", "private-value").commit())
        val facade = SourcePreferenceApplication(context.applicationContext as Application)
        val prefs = facade.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertEquals("private-value", prefs.getString("token", null))
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
        assertSame(facade, facade.applicationContext)
        assertSame(prefs, facade.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))
        val file = File(context.noBackupFilesDir, "unreadable-source-${UUID.randomUUID()}.bin").apply { writeBytes(ByteArray(40) { 17 }) }
        val original = file.readBytes()
        val broken = ProtectedSourcePreferences(file, platformMangaFileProtection())
        assertFalse(broken.edit().putString("token", "replacement").commit())
        assertArrayEquals(original, file.readBytes())
    }
}
