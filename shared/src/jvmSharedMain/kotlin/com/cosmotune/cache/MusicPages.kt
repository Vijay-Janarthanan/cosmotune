package com.cosmotune.cache

import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.LibraryPage
import com.cosmotune.music.data.model.MoodGenreSection
import java.util.Locale
import kotlinx.serialization.builtins.ListSerializer

/**
 * Music's main pages as the phone, the TV and the desktop keep them on the disk between runs ([PageCache.shared]'s folder):
 * Home and the library per listener (an account and its channel, or signed out), Explore's moods per language. The app shows
 * the copy at once on the next start while it asks again; the copy never replaces what was fetched in this run.
 */
object MusicPages {
    private const val HOME_SHELVES = 40
    private val HOME = CacheCodec.json(ListSerializer(HomeShelf.serializer()))
    private val EXPLORE = CacheCodec.json(ListSerializer(MoodGenreSection.serializer()))
    private val LIBRARY = CacheCodec.json(LibraryPage.serializer())

    private fun who(identity: String?) = identity ?: "signed-out"
    private fun homeKey(identity: String?) = "music:home:" + who(identity)
    private fun libraryKey(identity: String?) = "music:library:" + who(identity)
    private fun exploreKey() = "music:explore:" + Locale.getDefault().toLanguageTag()

    suspend fun savedHome(identity: String?): List<HomeShelf>? = PageCache.shared.readSaved(homeKey(identity), HOME)?.second

    fun saveHome(identity: String?, shelves: List<HomeShelf>) = PageCache.shared.writeSaved(homeKey(identity), shelves.take(HOME_SHELVES), HOME)

    suspend fun savedExplore(): List<MoodGenreSection>? = PageCache.shared.readSaved(exploreKey(), EXPLORE)?.second

    fun saveExplore(sections: List<MoodGenreSection>) = PageCache.shared.writeSaved(exploreKey(), sections, EXPLORE)

    suspend fun savedLibrary(identity: String?): LibraryPage? = PageCache.shared.readSaved(libraryKey(identity), LIBRARY)?.second

    fun saveLibrary(identity: String?, page: LibraryPage) = PageCache.shared.writeSaved(libraryKey(identity), page.copy(likedContinuation = null), LIBRARY)

    /** Forgets a listener's saved Home and library (they signed out). */
    fun forget(identity: String?) {
        PageCache.shared.removeSaved(homeKey(identity))
        PageCache.shared.removeSaved(libraryKey(identity))
    }
}

/**
 * Holds what a page publishes while it shows a saved copy and waits for its main answer: [hold] keeps each publication, [stop]
 * hands them back in order (and publishes go straight through again). Used from the UI thread only.
 */
class StagedPublish {
    private var held: MutableList<() -> Unit>? = null

    /** Whether publications are being held. */
    val holding: Boolean get() = held != null

    /** Holds every publication from now on. */
    fun start() { held = mutableListOf() }

    /** Keeps [publish] for later and returns true while holding; false (publish now) otherwise. */
    fun hold(publish: () -> Unit): Boolean {
        val list = held ?: return false
        list += publish
        return true
    }

    /** Stops holding; returns what was held, or null when nothing was being held. */
    fun stop(): List<() -> Unit>? = held.also { held = null }
}
