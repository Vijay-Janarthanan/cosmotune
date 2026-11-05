package com.cosmotune.music.data.innertube

import kotlinx.serialization.json.*
import kotlin.test.*

class UploadedTracksTest {
    @AfterTest fun cleanup() = UploadedTracks.clear()

    private fun response(id: String, menu: String) = Json.parseToJsonElement("""{
      "contents":[{"musicResponsiveListItemRenderer":{
        "playlistItemData":{"videoId":"$id"},
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"My recording"}]}}},
        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Singer • 3:00"}]}}}],"menu":$menu
      }}]}
    """).jsonObject

    @Test fun privatelyOwnedCatalogueRowsAreRememberedButOrdinaryMenusAreNot() {
        InnertubeParser.parseSearchSongs(response("UPLOAD00001", """{"musicDeletePrivatelyOwnedEntityCommand":{"entityId":"fixture"}}"""))
        InnertubeParser.parseSearchSongs(response("PUBLIC00001", """{"menuServiceItemRenderer":{"text":"Delete from playlist"}}"""))
        assertTrue(UploadedTracks.contains("UPLOAD00001"))
        assertFalse(UploadedTracks.contains("PUBLIC00001"))
    }

    @Test fun anAccountChangeDiscardsOwnershipHints() {
        UploadedTracks.observe("UPLOAD00001", buildJsonObject { put("musicVideoType", "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK") })
        assertTrue(UploadedTracks.contains("UPLOAD00001"))
        StreamResolver.onSessionChanged()
        assertFalse(UploadedTracks.contains("UPLOAD00001"))
    }

    @Test fun textThatMentionsACommandDoesNotDeclareOwnership() {
        UploadedTracks.observe("PUBLIC00001", buildJsonObject { put("description", "musicDeletePrivatelyOwnedEntityCommand") })
        assertFalse(UploadedTracks.contains("PUBLIC00001"))
    }
}
