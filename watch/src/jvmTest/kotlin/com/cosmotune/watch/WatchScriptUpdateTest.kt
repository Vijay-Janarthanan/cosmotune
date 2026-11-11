package com.cosmotune.watch

import com.cosmotune.watch.plugin.AddSourceResult
import com.cosmotune.watch.plugin.UserSourceSpec
import com.cosmotune.watch.plugins.nuvio.NuvioPackages
import com.cosmotune.watch.store.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import okhttp3.OkHttpClient

/** Installing, checking and updating a Watch script, with the network read answered by the test. */
class WatchScriptUpdateTest {
    private val address = "https://scripts.example.org/provider.js"
    private val first = "module.exports.getStreams = async () => [{ url: 'https://cdn.example.org/a.mp4', quality: '720p' }]"
    private val second = "module.exports.getStreams = async () => [{ url: 'https://cdn.example.org/b.mp4', quality: '1080p' }]"

    private class Upstream(var code: String) {
        val reads = CopyOnWriteArrayList<String>()
        val fetch: suspend (String) -> String = { url -> reads += url; code }
    }

    private fun <T> repository(upstream: Upstream, body: suspend (DefaultWatchRepository, WatchStore, java.io.File) -> T): T = runBlocking {
        val root = Files.createTempDirectory("watch-scripts").toFile()
        try {
            val store = WatchStore(root.resolve("watch.json"), UnsealedWatchFiles)
            val repository = DefaultWatchRepository(WatchConfig(OkHttpClient(), root, root.resolve("cache")), store,
                builtIns = { _, _ -> emptyList() }, scriptFetch = upstream.fetch)
            body(repository, store, root)
        } finally { root.deleteRecursively() }
    }

    private fun spec(store: WatchStore) = store.state.value.sources.single().spec as UserSourceSpec.NuvioScript

    @Test fun anUpdateIsOfferedOnlyWhenTheSourceChangedAndInstallsExactlyTheReviewedBytes() {
        val upstream = Upstream(first)
        repository(upstream) { repository, store, root ->
            assertIs<AddSourceResult.Added>(repository.addUserSource(UserSourceSpec.NuvioScript(address, emptyList(), "Provider")))
            val id = store.state.value.sources.single().id
            assertEquals(NuvioPackages.sha256(first), spec(store).sha256)

            val unchanged = assertNotNull(repository.checkScriptUpdate(id))
            assertFalse(unchanged.available)
            assertFalse(repository.applyScriptUpdate(id, unchanged.availableSha256), "nothing was offered")

            upstream.code = second
            val offered = assertNotNull(repository.checkScriptUpdate(id))
            assertTrue(offered.available)
            assertEquals(NuvioPackages.sha256(first), offered.installedSha256)
            assertEquals(NuvioPackages.sha256(second), offered.availableSha256)
            // Checking changes nothing.
            assertEquals(NuvioPackages.sha256(first), spec(store).sha256)
            assertEquals(first, root.resolve("scripts/$id.js").readText())

            // Upstream changes again between the check and the tap: the reviewed bytes are still what lands.
            upstream.code = "module.exports.getStreams = async () => { throw new Error('later') }"
            assertFalse(repository.applyScriptUpdate(id, "0".repeat(64)), "a fingerprint that was not offered")
            assertTrue(repository.applyScriptUpdate(id, offered.availableSha256))
            assertEquals(NuvioPackages.sha256(second), spec(store).sha256)
            assertEquals(second, root.resolve("scripts/$id.js").readText())
            assertFalse(repository.applyScriptUpdate(id, offered.availableSha256), "an offer is used once")
            // The descriptor that syncs carries the new pin.
            assertTrue(SyncWatchPlugins.snapshot(store.state.value).getValue(id).toString().contains(NuvioPackages.sha256(second)))
        }
    }

    @Test fun installRejectsQueriesCredentialsAndPlainHttpBeforeAnyDownload() {
        val upstream = Upstream(first)
        repository(upstream) { repository, store, _ ->
            for (bad in listOf("$address?token=secret", "$address?", "$address#frag", "http://scripts.example.org/provider.js",
                "https://user:pass@scripts.example.org/provider.js", "https:///provider.js", "not a url")) {
                val result = repository.addUserSource(UserSourceSpec.NuvioScript(bad, emptyList()))
                assertIs<AddSourceResult.Failed>(result, bad)
            }
            assertTrue(upstream.reads.isEmpty(), "nothing was downloaded: ${upstream.reads}")
            assertTrue(store.state.value.sources.isEmpty())
        }
    }

    @Test fun aSourceThatIsNotAWatchScriptIsNotInstalled() {
        repository(Upstream("console.log('hello')")) { repository, store, _ ->
            assertIs<AddSourceResult.Failed>(repository.addUserSource(UserSourceSpec.NuvioScript(address, emptyList())))
            assertTrue(store.state.value.sources.isEmpty())
        }
    }

    @Test fun cloudRestoreRefusesAQueryAndMovesAnExistingPinToTheApprovedUpdate() {
        val id = "js-0123456789"
        val pinned = StoredSource(id, UserSourceSpec.NuvioScript(address, emptyList(), "Provider", "a".repeat(64)), addedAtMs = 1)
        val updatedElsewhere = SyncWatchPlugins.snapshot(WatchState(sources = listOf(pinned.copy(spec = (pinned.spec as UserSourceSpec.NuvioScript).copy(sha256 = "b".repeat(64))))))
        val moved = SyncWatchPlugins.apply(WatchState(sources = listOf(pinned)), updatedElsewhere, emptySet())
        assertEquals("b".repeat(64), (moved.sources.single().spec as UserSourceSpec.NuvioScript).sha256)

        val withQuery = SyncWatchPlugins.snapshot(WatchState(sources = listOf(pinned.copy(spec = (pinned.spec as UserSourceSpec.NuvioScript).copy(scriptUrl = "$address?token=x")))))
        assertTrue(SyncWatchPlugins.apply(WatchState(), withQuery, emptySet()).sources.isEmpty())
    }

    @Test fun theAddressRuleIsTheSameEverywhere() {
        assertEquals("scripts.example.org", NuvioPackages.scriptUri(" $address ").host)
        assertFailsWith<IllegalArgumentException> { NuvioPackages.scriptUri("$address?a=1") }
        assertFailsWith<IllegalArgumentException> { runBlocking { NuvioPackages.fetch("$address?a=1") } }
    }
}
