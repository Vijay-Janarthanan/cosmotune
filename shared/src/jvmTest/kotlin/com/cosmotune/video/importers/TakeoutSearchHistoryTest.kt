package com.cosmotune.video.importers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TakeoutSearchHistoryTest {
    @Test fun theSearchHistoryBecomesRecentSearchesNewestFirst() {
        val json = """[
          {"header":"YouTube","title":"Searched for old song","titleUrl":"https://www.youtube.com/results?search_query=old+song","time":"2026-11-01T10:00:00.000Z","products":["YouTube"]},
          {"header":"YouTube","title":"Searched for café live","titleUrl":"https://www.youtube.com/results?search_query=caf%C3%A9+live","time":"2026-11-02T10:00:00.000Z","products":["YouTube"]},
          {"header":"YouTube","title":"Searched for Old Song","titleUrl":"https://www.youtube.com/results?search_query=Old+Song","time":"2026-10-01T10:00:00.000Z","products":["YouTube"]}
        ]"""
        val preview = TakeoutHistoryImporter.parse(json)
        assertEquals(listOf("café live", "old song"), preview.searches)
        assertEquals(0, preview.history.size)
    }

    @Test fun onlySearchAddressesAreSearches() {
        assertNull(TakeoutHistoryImporter.searchOf("https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("a b", TakeoutHistoryImporter.searchOf("https://www.youtube.com/results?search_query=a+b&sp=x"))
    }
}
