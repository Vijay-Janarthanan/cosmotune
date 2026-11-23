package com.cosmotune.ui

import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.search.CosmoRecentSearches
import kotlin.test.Test
import kotlin.test.assertEquals

/** The per-mode recent searches every search page shows before you type: newest first, once each, at most ten, kept in the store. */
class RecentSearchesTest {
    private class Store : KeyValueStore {
        val values = HashMap<String, String>()
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, default: String) = values[key] ?: default
        override fun putString(key: String, value: String) { values[key] = value }
    }

    @Test fun aTermGoesToTheFrontOnceWhateverItsCase() {
        assertEquals(listOf("Ilaiyaraaja", "news"), CosmoRecentSearches.added(listOf("news", "ilaiyaraaja"), "  Ilaiyaraaja "))
    }

    @Test fun blankTermsAreNotKeptAndSpacesFold() {
        assertEquals(listOf("a"), CosmoRecentSearches.added(listOf("a"), "   "))
        assertEquals(listOf("one piece"), CosmoRecentSearches.added(emptyList(), "one   piece"))
    }

    @Test fun theOldestFallsOffPastTen() {
        val full = (1..10).map { "t$it" }
        val next = CosmoRecentSearches.added(full, "new")
        assertEquals(CosmoRecentSearches.MAX, next.size)
        assertEquals("new", next.first())
        assertEquals("t9", next.last())
    }

    @Test fun eachModeKeepsItsOwnListInTheStoreAndUndoPutsAClearBack() {
        val store = Store()
        CosmoRecentSearches.install(store)
        CosmoRecentSearches.clear("test-a"); CosmoRecentSearches.clear("test-b")
        CosmoRecentSearches.add("test-a", "radio mirchi")
        CosmoRecentSearches.add("test-a", "bbc")
        CosmoRecentSearches.add("test-b", "berserk")
        assertEquals(listOf("bbc", "radio mirchi"), CosmoRecentSearches.of("test-a").value)
        assertEquals(listOf("berserk"), CosmoRecentSearches.of("test-b").value)
        val before = CosmoRecentSearches.of("test-a").value
        CosmoRecentSearches.clear("test-a")
        assertEquals(emptyList(), CosmoRecentSearches.of("test-a").value)
        CosmoRecentSearches.restore("test-a", before)
        assertEquals(before, CosmoRecentSearches.of("test-a").value)
        CosmoRecentSearches.remove("test-a", "BBC")
        assertEquals(listOf("radio mirchi"), CosmoRecentSearches.of("test-a").value)
        // Read again from the store, as the next start would.
        CosmoRecentSearches.install(store)
        assertEquals(listOf("radio mirchi"), CosmoRecentSearches.of("test-a").value)
    }

    @Test fun theStoredTextRoundTrips() {
        val list = listOf("a b", "ç", "日本")
        assertEquals(list, CosmoRecentSearches.decode(CosmoRecentSearches.encode(list)))
        assertEquals(emptyList(), CosmoRecentSearches.decode(""))
    }
}
