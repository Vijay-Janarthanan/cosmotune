package com.cosmotune.providers

import kotlinx.serialization.json.*
import kotlin.test.*

class ProviderTrackModesTest {
    private fun track(extra: String) = requireNotNull(ProviderCatalogue.track("fixture", JsonObject(
        mapOf("id" to JsonPrimitive("track"), "name" to JsonPrimitive("A recording")) + Json.parseToJsonElement(extra).jsonObject)))

    @Test fun trackModesSupportScalarArrayAndExactTrackMetadataShapes() {
        for (field in listOf("""{"audio_modes":"DOLBY_ATMOS"}""", """{"audioModes":["STEREO","DOLBY ATMOS"]}""",
            """{"attributes":{"audioQualities":["HD","ATMOS"]}}""", """{"metadata":{"audio_modes":"DOLBY_ATMOS"}}"""))
            assertEquals("DOLBY", track(field).quality, field)
    }

    @Test fun albumsCodecsAndNegativeLabelsCannotCreateTrackAtmos() {
        for (field in listOf("""{"album":{"audio_modes":"DOLBY_ATMOS"}}""", """{"format":"AC-4"}""",
            """{"audio_modes":"NOT_ATMOS"}""", """{"audio_modes":[["DOLBY_ATMOS"]]}""", """{"audio_modes":"Atmos unavailable"}"""))
            assertNotEquals("DOLBY", track(field).quality, field)
        assertEquals("LOSSLESS", track("""{"audio_modes":"STEREO","quality":"FLAC"}""").quality)
    }
}
