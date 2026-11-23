package com.cosmotune.watch

import com.cosmotune.watch.plugins.stremio.StremioManifest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonActivityNoticeTest {
    private fun manifest(body: String) = StremioManifest.parse(Json.parseToJsonElement("""{"id":"x","name":"X","version":"1","types":["movie"],$body}"""))

    @Test fun anAddonThatFindsCopiesIsToldWhatYouOpen() {
        assertTrue(manifest(""""resources":["stream"],"catalogs":[]""").activityNotice!!.contains("every film or episode you open"))
        assertTrue(manifest(""""resources":[{"name":"subtitles","types":["movie"]}],"catalogs":[]""").activityNotice!!.contains("can see what you watch"))
    }

    @Test fun aCatalogueOnlyAddonSeesItsRowsAndNothingAtAllSeesNothing() {
        assertTrue(manifest(""""resources":["catalog"],"catalogs":[{"type":"movie","id":"top"}]""").activityNotice!!.contains("rows you browse"))
        assertNull(manifest(""""resources":[],"catalogs":[]""").activityNotice)
    }
}
