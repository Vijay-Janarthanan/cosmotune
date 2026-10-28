package com.cosmotune.music.playback

import android.bluetooth.BluetoothClass
import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class AudioRoutingFormTest {
    @Test fun knownProductShapesKeepWornDevicesAheadOfSpeakerBrandNames() {
        val examples = mapOf(
            "AirPods Max" to AudioRouting.Form.HEADPHONES,
            "AirPods Pro" to AudioRouting.Form.BUDS,
            "Echo Buds" to AudioRouting.Form.BUDS,
            "SoundLink Flex headphones" to AudioRouting.Form.HEADPHONES,
            "SoundLink Flex" to AudioRouting.Form.SPEAKER,
            "EMBERTON III" to AudioRouting.Form.SPEAKER,
            "WH-1000XM5" to AudioRouting.Form.HEADPHONES,
            "WF-1000XM5" to AudioRouting.Form.BUDS,
            "HomePods" to AudioRouting.Form.SPEAKER,
        )
        examples.forEach { (name, form) -> assertEquals(name, form, AudioRouting.formFromName(name)) }
        assertNull(AudioRouting.formFromName("My Bluetooth device"))
        assertNull(AudioRouting.formFromName("Solo"))
        assertNull(AudioRouting.formFromName(""))
    }

    @Test fun declaredLeSpeakerTypeWinsAndStaleClassesDoNotOverrideRecognizableNames() {
        assertEquals(AudioRouting.Form.SPEAKER,
            AudioRouting.bluetoothForm(AudioDeviceInfo.TYPE_BLE_SPEAKER, "AirPods") { error("type is enough") })
        assertEquals(AudioRouting.Form.SPEAKER,
            AudioRouting.bluetoothForm(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Emberton III") {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES
            })
    }

    @Test fun optionalClassInformationIsUsedWithoutRequiringBluetoothPermission() {
        assertEquals(AudioRouting.Form.CAR,
            AudioRouting.bluetoothForm(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Unknown") { BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO })
        assertEquals(AudioRouting.Form.UNKNOWN,
            AudioRouting.bluetoothForm(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Unknown") { throw SecurityException("permission denied") })
        assertEquals(AudioRouting.Form.BUDS,
            AudioRouting.bluetoothForm(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Galaxy Buds") { error("must not request paired metadata") })
        assertEquals(AudioRouting.Form.UNKNOWN, AudioRouting.bluetoothForm(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Unknown"))
    }

    @Test fun classMappingDoesNotChangeTheMediaRouteFilter() {
        assertEquals(AudioRouting.Form.HEADPHONES, AudioRouting.formFromClass(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertEquals(AudioRouting.Form.SPEAKER, AudioRouting.formFromClass(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertNull(AudioRouting.formFromClass(-1))
        assertEquals(AudioRouting.Kind.BLUETOOTH, AudioRouting.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertNull(AudioRouting.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }
}
