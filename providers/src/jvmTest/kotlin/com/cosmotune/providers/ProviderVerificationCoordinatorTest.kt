package com.cosmotune.providers

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The automatic verification rules: once per provider per session, in front only, one at a time, closes itself on success. */
class ProviderVerificationCoordinatorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun stop() = scope.cancel()

    private fun target(name: String) = ProviderVerificationTarget("https://verify.example/challenge?id=$name", "cosmotune://provider-auth/session-grant",
        "state-${name}-0123456789abcdef", "Fixture $name", listOf("verify.example"), System.currentTimeMillis() + 180_000, signed = true)

    /** A provider store stand-in: each provider's auth record and whether it counts as connected. */
    private inner class World {
        val auth = MutableStateFlow<Map<String, String>>(emptyMap())
        val prepared = AtomicInteger()
        var preparation: (String) -> ProviderVerificationPreparation = { ProviderVerificationPreparation.Ready(target(it)) }
        val coordinator = ProviderVerificationCoordinator(scope,
            prepare = { prepared.incrementAndGet(); preparation(it) },
            isVerified = { auth.value[it] == "connected" },
            authSnapshot = { auth.value[it] },
            changes = { auth })
        fun connect(name: String) { auth.value = auth.value + (name to "connected") }
        suspend fun request(): ProviderVerificationRequest = withTimeout(5_000) { coordinator.request.first { it != null }!! }
        suspend fun closedWindow() = withTimeout(5_000) { coordinator.request.first { it == null } }
        suspend fun status(name: String, status: ProviderVerificationStatus) = withTimeout(5_000) { coordinator.statuses.first { it[name] == status } }
    }

    @Test fun aFailedSearchOpensTheProviderOnceAndASuccessfulGrantClosesItAndAsksAgain() = runBlocking {
        val world = World()
        world.coordinator.setForeground(true)
        val again = async { withTimeout(5_000) { world.coordinator.verified.first() } }
        delay(50)
        assertTrue(world.coordinator.accessRequired("qobuz"))
        val request = world.request()
        assertEquals("qobuz", request.provider)
        assertTrue(request.automatic)
        // The window delivered the grant and the manager saved the session: everything closes by itself.
        world.connect("qobuz")
        world.closedWindow()
        assertEquals("qobuz", again.await(), "the search that needed it is asked again")
        assertEquals(ProviderVerificationStatus.VERIFIED, world.coordinator.status("qobuz"))
        // A later failure in the same session does not reopen it automatically.
        assertFalse(world.coordinator.accessRequired("qobuz"))
        assertEquals(1, world.prepared.get())
    }

    @Test fun closingTheWindowAsksTheListenerAndIsNotReopenedInALoop() = runBlocking {
        val world = World()
        world.coordinator.setForeground(true)
        world.coordinator.accessRequired("tidal")
        val request = world.request()
        world.coordinator.finish(request.id, ProviderVerificationOutcome.CLOSED)
        world.status("tidal", ProviderVerificationStatus.NEEDS_USER)
        assertFalse(world.coordinator.accessRequired("tidal"), "the next failed search shows Verify to search instead")
        assertNull(world.coordinator.request.value)
        // A tap on Verify to search opens it on purpose.
        assertTrue(world.coordinator.retry("tidal"))
        assertEquals("tidal", world.request().provider)
        assertFalse(world.coordinator.request.value!!.automatic)
    }

    @Test fun oneVerificationAtATimeAndClosingDropsTheOnesWaiting() = runBlocking {
        val world = World()
        world.coordinator.setForeground(true)
        world.coordinator.accessRequired("a")
        val first = world.request()
        assertTrue(world.coordinator.accessRequired("a"), "the same provider is not opened twice")
        world.coordinator.accessRequired("b")
        world.coordinator.accessRequired("c")
        delay(100)
        assertEquals(first, world.coordinator.request.value, "nothing opens over the current window")
        // Completing the first lets the next one open by itself.
        world.connect("a")
        val second = withTimeout(5_000) { world.coordinator.request.first { it != null && it.id != first.id }!! }
        assertEquals("b", second.provider)
        // Closing this one is a "not now" for the rest of the queue as well.
        world.coordinator.finish(second.id, ProviderVerificationOutcome.CLOSED)
        world.status("c", ProviderVerificationStatus.NEEDS_USER)
        delay(100)
        assertNull(world.coordinator.request.value)
        assertEquals(2, world.prepared.get())
    }

    @Test fun nothingOpensInTheBackgroundUntilTheAppIsInFrontAgain() = runBlocking {
        val world = World()
        world.coordinator.setForeground(false)
        assertTrue(world.coordinator.accessRequired("deezer"), "it waits rather than being lost")
        delay(100)
        assertNull(world.coordinator.request.value)
        assertEquals(0, world.prepared.get())
        world.coordinator.setForeground(true)
        assertEquals("deezer", world.request().provider)
    }

    @Test fun theSettingTurnsAutomaticOpeningOffButNotTheButton() = runBlocking {
        val world = World()
        var saved: Boolean? = null
        world.coordinator.restoreAutoOpen(false) { saved = it }
        world.coordinator.setForeground(true)
        assertFalse(world.coordinator.accessRequired("amazon"))
        world.status("amazon", ProviderVerificationStatus.NEEDS_USER)
        assertTrue(world.coordinator.retry("amazon"))
        world.request()
        world.coordinator.setAutoOpen(true)
        assertEquals(true, saved)
    }

    @Test fun aSilentSessionNeedsNoWindowAndAPackageWithoutAChallengeGoesToSources() = runBlocking {
        val world = World()
        world.coordinator.setForeground(true)
        world.preparation = { ProviderVerificationPreparation.AlreadyVerified }
        val again = async { withTimeout(5_000) { world.coordinator.verified.first() } }
        delay(50)
        world.coordinator.accessRequired("silent")
        assertEquals("silent", again.await())
        assertNull(world.coordinator.request.value, "bootstrap returned a session: no browser was opened")
        world.preparation = { ProviderVerificationPreparation.Unavailable }
        world.coordinator.accessRequired("oauth")
        world.status("oauth", ProviderVerificationStatus.NEEDS_USER)
        world.preparation = { error("operator unavailable") }
        world.coordinator.accessRequired("down")
        world.status("down", ProviderVerificationStatus.FAILED)
        assertNull(world.coordinator.request.value)
    }

    @Test fun aWindowClosedAfterTheSessionWasSavedCountsAsVerified() = runBlocking {
        val world = World()
        val noWatch = ProviderVerificationCoordinator(scope, prepare = { ProviderVerificationPreparation.Ready(target(it)) },
            isVerified = { world.auth.value[it] == "connected" })
        noWatch.setForeground(true)
        noWatch.accessRequired("p")
        val request = withTimeout(5_000) { noWatch.request.first { it != null }!! }
        world.connect("p")
        noWatch.finish(request.id, ProviderVerificationOutcome.CLOSED)
        assertEquals(ProviderVerificationStatus.VERIFIED, noWatch.status("p"))
        noWatch.finish(request.id, ProviderVerificationOutcome.FAILED)
        assertEquals(ProviderVerificationStatus.VERIFIED, noWatch.status("p"), "a stale report is ignored")
    }

    @Test fun anOperationWaitsForTheVerificationAndRetriesOnlyAfterSuccess() = runBlocking {
        val world = World()
        world.coordinator.setForeground(true)
        world.coordinator.accessRequired("x")
        val waiting = async { world.coordinator.awaitVerification("x") }
        world.request()
        world.connect("x")
        assertTrue(waiting.await())
        world.coordinator.retry("y")
        val closed = async { world.coordinator.awaitVerification("y") }
        world.coordinator.finish(world.request().id, ProviderVerificationOutcome.CLOSED)
        assertFalse(closed.await())
    }
}
