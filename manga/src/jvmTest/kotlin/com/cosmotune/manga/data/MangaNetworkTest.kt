package com.cosmotune.manga.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MangaNetworkTest {
    @AfterTest fun reset() { MangaNetwork.metered = { false } }

    @Test fun aheadWaitsOnMobileDataOnlyWhenAsked() {
        MangaNetwork.metered = { true }
        assertTrue(MangaNetwork.aheadAllowed(MangaLibraryState()))
        assertFalse(MangaNetwork.aheadAllowed(MangaLibraryState(aheadOnWifiOnly = true)))
        MangaNetwork.metered = { false }
        assertTrue(MangaNetwork.aheadAllowed(MangaLibraryState(aheadOnWifiOnly = true)))
        MangaNetwork.metered = { error("no network service") }
        assertTrue(MangaNetwork.aheadAllowed(MangaLibraryState(aheadOnWifiOnly = true)))
    }
}
