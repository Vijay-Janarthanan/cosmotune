package com.cosmotune.audio

import kotlin.test.*

class ListeningDevicesTest {
    private val route = listeningRouteKey("android", "BLUETOOTH", "confirmed-device")
    private fun profile(eq: ListeningEq = ListeningEq(enabled = true)) = ListeningDeviceProfile(route, "My headphones", ListeningDeviceModel.SONY_WH1000XM5.name, eq)
    @Test fun reconnectNeverTunesAnUnmappedOrNonOptedInDeviceAndFlatBypassPreservesTheCurve() {
        var disk = ""
        val store = ListeningDeviceProfiles({ disk }, { disk = it })
        val reconnect = ListeningReconnect()
        assertNull(reconnect.changed(route, store))
        val curve = ListeningEq(enabled = true, manual = true, bands = listOf(2f, 1f, 0f, -1f, 0f, 1f, 2f), balance = .2f)
        store.save(profile(curve))
        assertNull(reconnect.changed(null, store)); assertNull(reconnect.changed(route, store))
        store.save(profile(curve).copy(restoreOnConnect = true, bypass = true))
        assertNull(reconnect.changed(route, store)) // Changing saved settings is not a reconnect.
        reconnect.changed(null, store)
        val applied = assertNotNull(reconnect.changed(route, store))
        assertFalse(applied.enabled); assertEquals(curve.bands, applied.bands)
        assertEquals(curve, ListeningDeviceProfiles({ disk }, {}).forRoute(route)?.eq)
        assertNull(reconnect.changed(route, store))
    }
    @Test fun malformedOrUnpersistableProfilesCannotBecomeAppliedSettings() {
        var disk = ""
        val store = ListeningDeviceProfiles({ disk }, { disk = it })
        assertFailsWith<IllegalArgumentException> { store.save(profile(ListeningEq(balance = Float.NaN))) }
        assertFailsWith<IllegalArgumentException> { store.save(profile().copy(routeKey = "ephemeral-id-12")) }
        assertTrue(store.profiles.value.isEmpty())
        assertTrue(ListeningDeviceProfiles({ "{\"version\":99,\"profiles\":[]}" }, {}).profiles.value.isEmpty())
        val unwritable = ListeningDeviceProfiles({ "" }, { error("Disk unavailable") })
        assertFailsWith<IllegalStateException> { unwritable.save(profile()) }
        assertTrue(unwritable.profiles.value.isEmpty())
    }
    @Test fun autoEqRequiresExactModelSourceAndApproximatesOnlyBoundedMonotonicGraphicData() {
        val model = ListeningDeviceModel.SONY_WH1000XM5
        val source = "https://github.com/jaakkopasanen/AutoEq/blob/master/results/test/Sony%20WH-1000XM5/Sony%20WH-1000XM5%20GraphicEQ.txt"
        val (eq, provenance) = importAutoEqGraphic(model, source, "User supplied measurement rig", "GraphicEQ: 20 -4; 1000 0; 20000 -4")
        assertEquals(7, eq.bands.size); assertTrue(eq.manual && eq.enabled && eq.fullHeadroom)
        assertTrue(eq.bands.all { it in -4f..0f }); assertEquals(0f, eq.bands[3], .001f)
        assertTrue(provenance.approximation)
        assertFailsWith<IllegalArgumentException> { importAutoEqGraphic(ListeningDeviceModel.SAMSUNG_BUDS4_PRO, source, "Rig", "GraphicEQ: 20 0; 20000 0") }
        assertFailsWith<IllegalArgumentException> { importAutoEqGraphic(model, source.replace("github.com", "github.com.attacker.test"), "Rig", "GraphicEQ: 20 0; 20000 0") }
        assertFailsWith<IllegalArgumentException> { importAutoEqGraphic(model, source, "Rig", "GraphicEQ: 20 NaN; 20000 0") }
        assertFailsWith<IllegalArgumentException> { importAutoEqGraphic(model, source, "Rig", "GraphicEQ: 1000 0; 20 0; 20000 0") }
        assertFailsWith<IllegalArgumentException> { importAutoEqGraphic(model, source, "Rig", "GraphicEQ: 20 20; 20000 20") }
        assertFailsWith<IllegalArgumentException> { importAutoEqGraphic(model, source, "Rig", "Filter 1: ON PK Fc 1000 Hz Gain -2 dB Q 3") }
    }
    @Test fun eligibleOrUnknownActiveSystemSpatializationBlocksExtraWidening() {
        assertTrue(PlatformSpatialState(true, true, true, true).blocksWidening)
        assertTrue(PlatformSpatialState(true, true, true, null).blocksWidening)
        assertFalse(PlatformSpatialState(true, true, true, false).blocksWidening)
        assertFalse(PlatformSpatialState(true, false, true, true).blocksWidening)
        assertFalse(PlatformSpatialState(true, true, false, true).blocksWidening)
    }
}
