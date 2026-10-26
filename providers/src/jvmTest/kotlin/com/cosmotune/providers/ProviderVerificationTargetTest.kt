package com.cosmotune.providers

import org.junit.Test
import kotlin.test.*

class ProviderVerificationTargetTest {
    private val target = ProviderVerificationTarget(
        "https://operator.example/challenge?id=owned&cb=cosmotune%3A%2F%2Fprovider-auth%2Fgrant%3Fstate%3Dfixture-state-long-enough",
        "cosmotune://provider-auth/grant", "fixture-state-long-enough", "Fixture", listOf("operator.example"), Long.MAX_VALUE, true)
    private val callback = "cosmotune://provider-auth/grant?grant=fixture-grant&state=fixture-state-long-enough"

    @Test fun callbacksRequireTheirExactAddressProofAndOneUnambiguousResult() {
        assertEquals(callback, target.completedCallback(callback))
        listOf(callback.replace("state=", "state=wrong"), callback.replace("/grant?", "/other?"),
            "$callback&code=other", "$callback&state=duplicate", "file:///secret", "javascript:alert(1)").forEach { assertNull(target.completedCallback(it)) }
        assertNull(target.copy(expiresAt = 1).completedCallback(callback))
        assertNull(target.completedCallback(target.url), "An incomplete challenge callback is not completion")
    }

    @Test fun popupWrappersRequireTheOperatorsDomainAndTheCompleteNestedCallback() {
        val wrapped = "https://operator.example/return?cb=" + java.net.URLEncoder.encode(callback, "UTF-8")
        assertEquals(callback, target.completedCallback(wrapped))
        assertNull(target.completedCallback(wrapped.replace("operator.example", "elsewhere.example")))
    }

    @Test fun grantOnlyFallbackIsReadOnlyFromTheActiveSignedChallenge() {
        assertEquals("grant=fixture-grant", target.verifiedPageGrant(target.url, "Already verified.\ngrant=fixture-grant\nCopy link"))
        assertNull(target.verifiedPageGrant(target.url.replace("id=owned", "id=another"), "grant=fixture-grant"))
        assertNull(target.verifiedPageGrant(target.url.replace("operator.example", "elsewhere.example"), "grant=fixture-grant"))
        assertNull(target.copy(signed = false).verifiedPageGrant(target.url, "grant=fixture-grant"))
        assertNull(target.copy(expiresAt = 1).verifiedPageGrant(target.url, "grant=fixture-grant"))
        assertNull(target.verifiedPageGrant(target.url, "x".repeat(16_385)))
        assertFalse(target.toString().contains("fixture-state"))
    }

    @Test fun ordinaryNavigationStaysWithinDeclaredHttpsDomains() {
        assertTrue(target.allowsNavigation(target.url))
        assertFalse(target.allowsNavigation("http://operator.example/"))
        assertFalse(target.allowsNavigation("https://operator.example@elsewhere.example/"))
        assertFalse(target.allowsNavigation("https://operator.example.evil.example/"))
    }
}
