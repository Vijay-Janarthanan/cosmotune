package com.cosmotune.ui.chrome

import com.cosmotune.design.AppMode
import org.junit.After
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ModeNavigationRequestTest {
    @After fun clear() { ModeNavigationRequest.pending.value?.let(ModeNavigationRequest::handled) }

    @Test fun aModeDestinationCarriesItsReturnRouteAndIsConsumedOnce() {
        ModeNavigationRequest.open(ModeNavigationRequest.Destination.SOURCES, AppMode.PODIO)
        val request = requireNotNull(ModeNavigationRequest.pending.value)
        assertEquals(AppMode.PODIO, request.returnMode)
        assertEquals(ModeNavigationRequest.Destination.SOURCES, request.destination)
        ModeNavigationRequest.handled(request)
        assertNull(ModeNavigationRequest.pending.value)
    }

    @Test fun finishingAnOlderHandoffDoesNotDiscardANewerTapFromTheSameMode() {
        ModeNavigationRequest.open(ModeNavigationRequest.Destination.SETTINGS, AppMode.MANGA)
        val old = requireNotNull(ModeNavigationRequest.pending.value)
        ModeNavigationRequest.open(ModeNavigationRequest.Destination.SETTINGS, AppMode.MANGA)
        val newest = requireNotNull(ModeNavigationRequest.pending.value)
        ModeNavigationRequest.handled(old)
        assertEquals(newest, ModeNavigationRequest.pending.value)
        ModeNavigationRequest.handled(newest)
        assertNull(ModeNavigationRequest.pending.value)
    }
}
