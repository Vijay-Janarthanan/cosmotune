package com.cosmotune.watch

import com.cosmotune.watch.plugin.Capability
import com.cosmotune.watch.plugin.PluginInfo
import com.cosmotune.watch.plugin.PluginKind
import com.cosmotune.watch.store.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import okhttp3.OkHttpClient

class WatchSetupGuideTest {
    private fun plugin(id: String, builtIn: Boolean, enabled: Boolean = true, streams: Boolean = true) = PluginInfo(id, PluginKind.STREMIO_ADDON, id, "", "ADD",
        builtIn = builtIn, enabled = enabled, defaultEnabled = true, capabilities = if (streams) setOf(Capability.STREAMS) else setOf(Capability.CATALOG))

    @Test fun aFirstOpenOffersMoreSourcesAndTheOptionalExtrasWithoutCallingThemRequired() {
        val steps = WatchSetupGuide.steps(listOf(plugin("ia", true), plugin("tmdb", true, streams = false)), profiles = 1,
            tracking = listOf(WatchTrackingStatus(WatchTracker.TRAKT), WatchTrackingStatus(WatchTracker.SIMKL)))
        assertEquals(listOf("sources", "viewers", "tracking"), steps.map { it.id })
        assertTrue(steps.none { it.done })
        assertTrue(steps[0].detail.startsWith("1 free source is on"), steps[0].detail)
        assertTrue(steps[1].detail.startsWith("Optional"))
        assertTrue(steps[2].detail.startsWith("Optional"))
        assertTrue(WatchSetupGuide.shouldShow(dismissed = false, steps))
        assertFalse(WatchSetupGuide.shouldShow(dismissed = true, steps))
    }

    @Test fun whatIsAlreadySetUpIsMarkedDoneAndAFullySetUpDeviceShowsNothing() {
        val steps = WatchSetupGuide.steps(listOf(plugin("ia", true), plugin("mine", false)), profiles = 2,
            tracking = listOf(WatchTrackingStatus(WatchTracker.TRAKT, connected = true), WatchTrackingStatus(WatchTracker.SIMKL)))
        assertTrue(steps.all { it.done })
        assertEquals("Connected to Trakt.", steps.last().detail)
        assertFalse(WatchSetupGuide.shouldShow(dismissed = false, steps))
    }

    @Test fun everySourceOffSaysSoAndTrackingIsLeftOutWhereThereIsNone() {
        val steps = WatchSetupGuide.steps(listOf(plugin("ia", true, enabled = false)), profiles = 1, tracking = null)
        assertEquals(listOf("sources", "viewers"), steps.map { it.id })
        assertTrue(steps[0].detail.startsWith("Every source is off"))
        assertEquals(WatchSetupTarget.PLUGINS, steps[0].target)
    }

    @Test fun hidingTheGuideIsRememberedOnThisDeviceAndNotSynced() = runBlocking {
        val root = Files.createTempDirectory("watch-guide").toFile()
        try {
            val store = WatchStore(root.resolve("watch.json"), UnsealedWatchFiles)
            val repository = DefaultWatchRepository(WatchConfig(OkHttpClient(), root, root.resolve("cache")), store, builtIns = { _, _ -> emptyList() })
            assertFalse(repository.setupGuideDismissed.value)
            repository.dismissSetupGuide()
            assertTrue(repository.setupGuideDismissed.value)
            val reopened = DefaultWatchRepository(WatchConfig(OkHttpClient(), root, root.resolve("cache")),
                WatchStore(root.resolve("watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> emptyList() })
            assertTrue(reopened.setupGuideDismissed.value)
            assertFalse(SyncWatchLibrary.snapshot(store.state.value).toString().contains("guide"))
            assertFalse(SyncWatchPlugins.snapshot(store.state.value).toString().contains("guide"))
        } finally { root.deleteRecursively() }
    }
}
