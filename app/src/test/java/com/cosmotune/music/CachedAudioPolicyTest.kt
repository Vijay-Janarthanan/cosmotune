package com.cosmotune.music

import androidx.media3.common.PlaybackException
import com.cosmotune.music.playback.CachedAudioPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CachedAudioPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `matcher schema invalidation covers both Atmos settings without deleting original or downloaded files`() {
        for (suffix in listOf("#alt", "#alt-noatmos", "#alt#watch", "#alt-noatmos#watch")) {
            assertTrue(CachedAudioPolicy.isSubstitutionKey("abcdefghijk$suffix"))
        }
        for (key in listOf("abcdefghijk", "abcdefghijk#watch", "abcdefghijk#hifi-saved",
            "provider|abcdefghijk#alt", "file:///music/song#alt", "/music/abcdefghijk#alt")) {
            assertFalse(key, CachedAudioPolicy.isSubstitutionKey(key))
        }
    }

    @Test fun `moving the cache keeps all spans and its UID together`() {
        val legacy = temporary.newFolder("legacy")
        File(legacy, "123.uid").writeText("uid")
        File(legacy, "span.v3.exo").writeText("audio")
        val persistent = File(temporary.root, "no-backup/audio")
        assertEquals(persistent, CachedAudioPolicy.directory(legacy, persistent))
        assertEquals("uid", File(persistent, "123.uid").readText())
        assertEquals("audio", File(persistent, "span.v3.exo").readText())
        assertFalse(legacy.exists())
    }

    @Test fun `a failed migration never deletes the offline songs`() {
        val legacy = temporary.newFolder("legacy")
        File(legacy, "span.v3.exo").writeText("audio")
        val persistent = File(temporary.root, "no-backup/audio")
        assertEquals(legacy, CachedAudioPolicy.directory(legacy, persistent) { _, _ -> false })
        assertEquals("audio", File(legacy, "span.v3.exo").readText())
        assertFalse(persistent.exists())
    }

    @Test fun `an established persistent cache is not overwritten by an old directory`() {
        val legacy = temporary.newFolder("legacy")
        val persistent = temporary.newFolder("persistent")
        File(legacy, "old.exo").writeText("old")
        File(persistent, "current.exo").writeText("current")
        assertEquals(persistent, CachedAudioPolicy.directory(legacy, persistent))
        assertEquals("current", File(persistent, "current.exo").readText())
        assertEquals("old", File(legacy, "old.exo").readText())
    }

    @Test fun `offline source changes can reuse a complete retained copy`() {
        val copies = listOf(CachedAudioPolicy.Copy("song", complete = true, keptOrigin = true))
        assertEquals("song", CachedAudioPolicy.sibling("song#alt-noatmos", false, true, copies))
        assertNull(CachedAudioPolicy.sibling("song#alt-noatmos", false, false, copies))
        assertNull(CachedAudioPolicy.sibling("song#alt-noatmos", true, true, copies))
    }

    @Test fun `a plain online replay avoids a duplicate retained download`() {
        val copies = listOf(CachedAudioPolicy.Copy("song#alt", complete = true, keptOrigin = true))
        assertEquals("song#alt", CachedAudioPolicy.sibling("song", false, false, copies))
        assertNull(CachedAudioPolicy.sibling("song", true, false, copies))
    }

    @Test fun `partial files and unverified alternate mixes never become sibling fallbacks`() {
        assertNull(CachedAudioPolicy.sibling("song", false, true, listOf(
            CachedAudioPolicy.Copy("song#alt", complete = false, keptOrigin = true),
            CachedAudioPolicy.Copy("song#alt-noatmos", complete = true, keptOrigin = false),
        )))
    }

    @Test fun `network faults keep complete files but actual container faults still invalidate them`() {
        assertTrue(CachedAudioPolicy.keepComplete(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, false))
        assertTrue(CachedAudioPolicy.keepComplete(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, false))
        assertTrue(CachedAudioPolicy.keepComplete(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, false))
        assertTrue(CachedAudioPolicy.keepComplete(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, true))
        assertFalse(CachedAudioPolicy.keepComplete(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, false))
        assertFalse(CachedAudioPolicy.keepComplete(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, true))
    }
}
