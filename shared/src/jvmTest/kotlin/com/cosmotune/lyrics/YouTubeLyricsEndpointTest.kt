package com.cosmotune.lyrics

import com.cosmotune.music.data.lyrics.YouTubeMusicLyrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class YouTubeLyricsEndpointTest {
    private fun response(tabs: String) = Json.parseToJsonElement("""{"tabs":[$tabs]}""")
    private fun tab(title: String, id: String, extra: String = "") =
        """{"tabRenderer":{"title":"$title",$extra"endpoint":{"browseEndpoint":{"browseId":"$id"}}}}"""

    @Test fun unrelatedSecondTabIsNeverTreatedAsLyrics() {
        val next = response(tab("Up next", "MUSIC_QUEUE") + "," + tab("Related", "MPTRrelated"))
        assertNull(YouTubeMusicLyrics.lyricsEndpoint(next))
    }

    @Test fun disabledLyricsTabDoesNotFallBackToRelated() {
        val next = response(tab("Lyrics", "MPLYtmissing", "\"unselectable\":true,") + "," + tab("Related", "MPTRrelated"))
        assertNull(YouTubeMusicLyrics.lyricsEndpoint(next))
    }

    @Test fun localizedLyricsLabelIsIdentifiedByItsBrowseId() {
        val next = response(tab("தொடர்புடையவை", "MPTRrelated") + "," + tab("பாடல்வரிகள்", "MPLYtwords"))
        assertEquals("MPLYtwords", YouTubeMusicLyrics.lyricsEndpoint(next)?.get("browseId")?.jsonPrimitive?.content)
    }

    @Test fun pageTypeIdentifiesLyricsWithoutEnglishOrAnIdPrefix() {
        val next = Json.parseToJsonElement("""{"tabRenderer":{"title":"పాట సాహిత్యం","endpoint":{"browseEndpoint":{
            "browseId":"new-format","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
                "pageType":"MUSIC_PAGE_TYPE_TRACK_LYRICS"}}}}}}""")
        assertEquals("new-format", YouTubeMusicLyrics.lyricsEndpoint(next)?.get("browseId")?.jsonPrimitive?.content)
    }

    @Test fun lyricExtractionKeepsOriginalScriptAndOnlyDescriptionText() {
        val page = Json.parseToJsonElement("""{"musicDescriptionShelfRenderer":{
            "description":{"runs":[{"text":"தமிழ் வரி\n"},{"text":"రెండో పంక్తి"}]},
            "footer":{"runs":[{"text":"Source attribution"}]}}}""")
        assertEquals(listOf("தமிழ் வரி", "రెండో పంక్తి"), YouTubeMusicLyrics.parsePage(page)?.map { it.text })
    }
}
