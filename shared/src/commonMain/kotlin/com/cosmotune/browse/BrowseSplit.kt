package com.cosmotune.browse

import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection

/**
 * Explore's buttons, split for the two new screens: the moods go to Home's chip row and Search's idle grid, the genres to
 * Search's idle grid. YouTube Music lists "Moods & moments" first and every genre group after it, so the split goes by position
 * and not by a section's title (the titles are translated). An item is the same item when its `browseId` and `params` match.
 */
object BrowseSplit {

    /** The items of the first section, each once. */
    fun moods(sections: List<MoodGenreSection>): List<MoodGenre> =
        sections.firstOrNull()?.items.orEmpty().distinctBy { key(it) }

    /** The items of every other section, each once, and none that [moods] already has. */
    fun genres(sections: List<MoodGenreSection>): List<MoodGenre> {
        val taken = moods(sections).mapTo(HashSet()) { key(it) }
        return sections.drop(1).flatMap { it.items }.distinctBy { key(it) }.filter { key(it) !in taken }
    }

    private fun key(item: MoodGenre): Pair<String, String?> = item.browseId to item.params
}
