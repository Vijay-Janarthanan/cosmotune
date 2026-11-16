package com.cosmotune.providers
import kotlin.test.*
class ProviderRecoveryTest {
    @Test fun transientFailuresBackOffAndManualRecoveryClearsOnlyTheirOwnCircuit() {
        var now=100L
        val recovery=ProviderRecovery { now }
        recovery.failed("one"); assertEquals(0L,recovery.remainingMs("one"))
        recovery.failed("one"); assertEquals(15_000L,recovery.remainingMs("one"))
        assertTrue(recovery.due().isEmpty())
        now+=15_000; assertEquals(listOf("one"),recovery.due())
        repeat(20) { recovery.failed("one") }
        assertEquals(120_000L,recovery.remainingMs("one"))
        recovery.failed("two"); recovery.clear("one")
        assertEquals(listOf("two"),recovery.names())
    }
}
