package com.cosmotune.music.data.room.voice

import android.content.pm.ServiceInfo
import com.cosmotune.music.data.room.voice.MicPermission.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The microphone permission's decisions and the foreground type, without Android. */
class VoicePermissionTest {
    @Test
    fun `granted wins over everything remembered`() {
        assertEquals(Status.GRANTED, MicPermission.status(granted = true, blockedBefore = true, rationale = false))
    }

    @Test
    fun `never asked can be asked`() {
        assertEquals(Status.CAN_ASK, MicPermission.status(granted = false, blockedBefore = false, rationale = false))
    }

    @Test
    fun `a final refusal stays blocked until the system would ask again`() {
        assertEquals(Status.BLOCKED, MicPermission.status(granted = false, blockedBefore = true, rationale = false))
        // permissions reset from the system settings: the rationale is due again, so the request will be shown
        assertEquals(Status.CAN_ASK, MicPermission.status(granted = false, blockedBefore = true, rationale = true))
    }

    @Test
    fun `a first refusal is not final`() {
        assertFalse(MicPermission.blockedAfterDenial(rationaleBefore = false, rationaleAfter = true, answeredInMs = 2_000))
    }

    @Test
    fun `a second refusal on Android 11 and later is final`() {
        assertTrue(MicPermission.blockedAfterDenial(rationaleBefore = true, rationaleAfter = false, answeredInMs = 2_000))
    }

    @Test
    fun `an instant no means the system did not ask`() {
        assertTrue(MicPermission.blockedAfterDenial(rationaleBefore = false, rationaleAfter = false, answeredInMs = 40))
    }

    @Test
    fun `a first request dismissed by tapping outside is not final`() {
        assertFalse(MicPermission.blockedAfterDenial(rationaleBefore = false, rationaleAfter = false, answeredInMs = 3_000))
    }

    @Test
    fun `the microphone foreground type exists from Android 11`() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, VoiceForeground.serviceType(34))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, VoiceForeground.serviceType(30))
        assertEquals(0, VoiceForeground.serviceType(29))
    }
}
