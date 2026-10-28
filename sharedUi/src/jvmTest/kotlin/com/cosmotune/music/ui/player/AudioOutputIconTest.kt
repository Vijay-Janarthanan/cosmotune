package com.cosmotune.music.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Earbuds
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioOutputIconTest {
    @Test fun bluetoothIconsFollowOnlyTheOptionalFormHint() {
        val device = AudioOutputDevice(1, "Connected device", AudioOutputKind.BLUETOOTH)
        assertEquals(Icons.Rounded.Bluetooth, audioOutputIcon(device))
        assertEquals(Icons.Rounded.Earbuds, audioOutputIcon(device.copy(form = AudioOutputForm.BUDS)))
        assertEquals(Icons.Rounded.Headphones, audioOutputIcon(device.copy(form = AudioOutputForm.HEADPHONES)))
        assertEquals(Icons.Rounded.Speaker, audioOutputIcon(device.copy(form = AudioOutputForm.SPEAKER)))
        assertEquals(Icons.Rounded.DirectionsCar, audioOutputIcon(device.copy(form = AudioOutputForm.CAR)))
        assertEquals(Icons.Rounded.Headphones, audioOutputIcon(device.copy(kind = AudioOutputKind.WIRED, form = AudioOutputForm.CAR)))
    }
}
