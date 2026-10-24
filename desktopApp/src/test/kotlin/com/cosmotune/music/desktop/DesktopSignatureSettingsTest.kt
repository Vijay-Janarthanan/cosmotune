package com.cosmotune.music.desktop

import com.cosmotune.music.data.canvas.ArtistSignatureRepository.SaveCheck
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The desktop signature settings against an in-memory store: never the real preferences. */
class DesktopSignatureSettingsTest {
    private val stored = HashMap<String, Any>()
    private var cleared = 0
    private val checked = ArrayList<String>()

    private fun model(answer: (String) -> SaveCheck) = DesktopSignatureSettingsModel(
        readBoolean = { key, default -> stored[key] as? Boolean ?: default },
        readString = { key, default -> stored[key] as? String ?: default },
        saveBoolean = { key, value -> stored[key] = value },
        saveString = { key, value -> stored[key] = value },
        forgetSignatures = { cleared++ },
        check = { draft -> checked += draft; answer(draft) },
    )

    @Test fun aHealthyAddressIsStoredNormalizedTurnedOnAndTheOldSignaturesForgotten() = runBlocking {
        val settings = model { SaveCheck.Ready("https://signatures.example.org") }
        assertFalse(settings.enabled.value)
        settings.submit("https://signatures.example.org/ ")
        assertEquals("https://signatures.example.org", settings.url.value)
        assertTrue(settings.enabled.value)
        assertEquals("https://signatures.example.org", stored[DesktopSignatureSettingsModel.KEY_URL])
        assertEquals(true, stored[DesktopSignatureSettingsModel.KEY_ENABLED])
        assertEquals(1, cleared)
        // A reopened app reads the same values back.
        val reopened = model { error("not asked") }
        assertEquals("https://signatures.example.org", reopened.url.value)
        assertTrue(reopened.enabled.value)
    }

    @Test fun anUnreachableOrMalformedAddressChangesNothing() = runBlocking {
        stored[DesktopSignatureSettingsModel.KEY_URL] = "https://old.example.org"
        stored[DesktopSignatureSettingsModel.KEY_ENABLED] = true
        for (answer in listOf(SaveCheck.Unreachable, SaveCheck.Invalid)) {
            val settings = model { answer }
            assertEquals(answer, settings.submit("https://new.example.org"))
            assertEquals("https://old.example.org", settings.url.value)
            assertTrue(settings.enabled.value)
        }
        assertEquals("https://old.example.org", stored[DesktopSignatureSettingsModel.KEY_URL])
        assertEquals(0, cleared)
    }

    @Test fun aBlankAddressForgetsTheProviderAndTheToggleIsKeptOnItsOwn() = runBlocking {
        stored[DesktopSignatureSettingsModel.KEY_URL] = "https://old.example.org"
        stored[DesktopSignatureSettingsModel.KEY_ENABLED] = true
        val settings = model { SaveCheck.Remove }
        settings.submit("")
        assertEquals("", settings.url.value)
        assertFalse(settings.enabled.value)
        assertEquals(1, cleared)
        settings.enable(true)
        assertEquals(true, stored[DesktopSignatureSettingsModel.KEY_ENABLED])
        assertEquals(listOf(""), checked)
    }
}
