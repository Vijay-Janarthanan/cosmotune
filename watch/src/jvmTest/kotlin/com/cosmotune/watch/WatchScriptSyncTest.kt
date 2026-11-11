package com.cosmotune.watch

import com.cosmotune.watch.plugin.UserSourceSpec
import com.cosmotune.watch.store.*
import kotlinx.serialization.json.*
import kotlin.test.*

class WatchScriptSyncTest {
    @Test fun cloudRestorePinsCodeAndLeavesScriptCredentialsOnTheirOwnDevice() {
        val id = "js-0123456789"
        val source = StoredSource(id, UserSourceSpec.NuvioScript("https://example.com/provider.js", emptyList(), "A script", "a".repeat(64),
            buildJsonObject { put("token", "device-only-secret") }), addedAtMs = 1)
        val snapshot = SyncWatchPlugins.snapshot(WatchState(sources = listOf(source)))
        assertFalse(snapshot.toString().contains("device-only-secret"))
        val restored = SyncWatchPlugins.apply(WatchState(), snapshot, emptySet())
        val spec = restored.sources.single().spec as UserSourceSpec.NuvioScript
        assertEquals("a".repeat(64), spec.sha256)
        assertTrue(spec.settings.isEmpty())
        assertTrue(SyncWatchPlugins.apply(restored, emptyMap(), setOf(id)).sources.isEmpty())
        assertTrue(SyncWatchPlugins.apply(WatchState(), mapOf("js-../../outside" to snapshot.getValue(id)), emptySet()).sources.isEmpty())
    }
}
