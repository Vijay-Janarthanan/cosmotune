package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.ui.PodioApp
import com.cosmotune.podio.ui.PodioPlayback
import com.cosmotune.reading.data.ReadingRepository
import com.cosmotune.reading.ui.ReadApp
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.SwingUtilities
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Read's Browse and Podio's show page set their state on the UI thread once the network answers. Compose's desktop harness runs
 * effects on an unconfined dispatcher, so without [rememberUiCoroutineScope] the rest of a coroutine (the list, the error, the busy
 * flag) ran on the OkHttp or IO worker that answered while the AWT thread laid the page out; under load the page then missed the
 * change for good (BooksVisualReviewTest and PodioInteractionTest timed out waiting for it). Every state write is recorded here;
 * only the AWT thread and the test's own thread (its clicks) may write.
 */
@OptIn(ExperimentalTestApi::class)
class UiThreadStateWritesTest {
    @get:Rule(order = 0) val writes = WriteThreads()
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun browseSetsItsResultsOnTheUiThread() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = """{"numFound":1,"docs":[{"key":"/works/OL100W","title":"The illustrated atlas","author_name":["Mira Sen"],"ebook_access":"no_ebook"}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = ReadingRepository(temporary.newFolder(), client = client)
        compose.setContent { PodioTestTheme { Box(Modifier.size(400.dp, 800.dp)) { ReadApp(repository = repository) } } }
        compose.onNode(hasText("Browse") and isSelectable()).performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText("The illustrated atlas") and hasAnyAncestor(hasTestTag("books:discover"))).fetchSemanticsNodes().isNotEmpty()
        }
        writes.assertOnlyUiThreads()
    }

    @Test fun aFailedFeedSetsItsErrorOnTheUiThread() {
        val directory = MockWebServer()
        directory.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/feed") -> MockResponse().setResponseCode(503)
                request.path.orEmpty().startsWith("/search") -> MockResponse().setBody("""{"results":[{"collectionName":"Test podcast","artistName":"A host","feedUrl":"${directory.url("/feed")}"}]}""")
                request.path.orEmpty().startsWith("/0.2") -> MockResponse().setBody("""{"data":[]}""")
                request.path.orEmpty().startsWith("/json") -> MockResponse().setBody("[]")
                else -> MockResponse().setBody("{}")
            }
        }
        directory.use {
            val base = directory.url("/").toString().trimEnd('/')
            val catalogue = PodioCatalogue(radioMirrors = listOf("$base/"), appleBase = base, fyydBase = base)
            val file = temporary.newFile("podio.json")
            compose.setContent { PodioTestTheme { Box(Modifier.size(1200.dp, 820.dp)) {
                PodioApp(file, PodioPlayback(), { _, _ -> }, {}, {}, {}, {}, directory = catalogue)
            } } }
            compose.onNode(podioTab("Podcasts") and hasAnyAncestor(hasTestTag("podio:root"))).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Test podcast").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Test podcast").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Couldn't load this podcast feed. Please retry.").fetchSemanticsNodes().isNotEmpty() }
            writes.assertOnlyUiThreads()
        }
    }
}

/** Records the thread and the first app frame of every snapshot state write made while a test runs. */
class WriteThreads : org.junit.rules.TestRule {
    private val offThread = ConcurrentLinkedQueue<String>()
    @Volatile private var testThread: Thread? = null

    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) = object : org.junit.runners.model.Statement() {
        override fun evaluate() {
            testThread = Thread.currentThread()
            val handle = Snapshot.registerGlobalWriteObserver {
                val thread = Thread.currentThread()
                if (!SwingUtilities.isEventDispatchThread() && thread !== testThread) {
                    val frame = thread.stackTrace.firstOrNull { it.className.startsWith("com.cosmotune.") && !it.className.startsWith("com.cosmotune.ui.WriteThreads") }
                    offThread += "${thread.name} at $frame"
                }
            }
            try { base.evaluate() } finally { handle.dispose() }
        }
    }

    fun assertOnlyUiThreads() {
        val seen = offThread.toList().distinct()
        assertTrue(seen.isEmpty(), "Page state was written off the UI thread:\n" + seen.joinToString("\n"))
    }
}
