package com.cosmotune.music.data.credits

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Song info's credits, read from the shapes YouTube Music sends (adapted from the upstream test, GPL-3.0). */
class SongCreditsTest {
    @Test fun creditsFromTheDialogSections() {
        val page = Json.parseToJsonElement(
            """{"sections":[
              {"dismissableDialogContentSectionRenderer":{"title":{"runs":[{"text":"Performed by"}]},"subtitle":{"runs":[{"text":"Eminem"}]}}},
              {"dismissableDialogContentSectionRenderer":{"title":{"runs":[{"text":"Written by"}]},"subtitle":{"runs":[{"text":"Jeff Bass"},{"text":"\n"},{"text":"Luis Resto"},{"text":"\n"},{"text":"Marshall Mathers"}]}}},
              {"dismissableDialogContentSectionRenderer":{"title":{"runs":[{"text":"Produced by"}]},"subtitle":{"runs":[{"text":"Eminem"}]}}}
            ]}""",
        )
        val credits = SongCredits.parseCreditsPage(page)
        assertEquals(listOf("Performed by", "Written by", "Produced by"), credits.map { it.role })
        assertEquals(listOf("Jeff Bass", "Luis Resto", "Marshall Mathers"), credits[1].names)
    }

    @Test fun creditsFromListRows() {
        val page = Json.parseToJsonElement(
            """{"contents":{"musicShelfRenderer":{"contents":[
              {"musicResponsiveListItemRenderer":{"flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Written by"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Jeff Bass, Luis Resto & Marshall Mathers"}]}}}]}},
              {"musicResponsiveListItemRenderer":{"flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Written by"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Jeff Bass, Luis Resto & Marshall Mathers"}]}}}]}}
            ]}}}""",
        )
        val credits = SongCredits.parseCreditsPage(page)
        assertEquals(1, credits.size, "a repeated line is shown once")
        assertEquals(listOf("Jeff Bass", "Luis Resto & Marshall Mathers"), credits.single().names)
    }

    @Test fun theCreditsEntryOfTheSongsMenuIsFound() {
        val next = Json.parseToJsonElement(
            """{"contents":{"playlistPanelRenderer":{"contents":[{"playlistPanelVideoRenderer":{"videoId":"abcdefghijk","menu":{"menuRenderer":{"items":[
              {"menuNavigationItemRenderer":{"text":{"runs":[{"text":"Go to album"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_x"}}}},
              {"menuNavigationItemRenderer":{"text":{"runs":[{"text":"View song credits"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"MPTC_x",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_TRACK_CREDITS"}}}}}}
            ]}}}}]}}}""",
        )
        assertEquals("MPTC_x", (SongCredits.findCreditsEndpoint(next, "abcdefghijk")?.get("browseId") as? JsonPrimitive)?.content)
    }

    @Test fun aPageWithoutCreditsHasNone() = assertEquals(emptyList(), SongCredits.parseCreditsPage(Json.parseToJsonElement("""{"contents":{}}""")))
}
