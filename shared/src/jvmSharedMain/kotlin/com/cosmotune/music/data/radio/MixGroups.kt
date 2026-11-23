package com.cosmotune.music.data.radio

import com.cosmotune.music.data.model.ShelfItem

/** One mood's mixes, its Supermix first and then the numbered ones in order. */
data class MoodMixes(val name: String, val items: List<ShelfItem>)

/** The "Mixed for you" cards sorted into Radio's three kinds; see [MixGroups.of]. */
data class MixGroups(val stations: List<ShelfItem>, val yours: List<ShelfItem>, val moods: List<MoodMixes>) {
    val isEmpty: Boolean get() = stations.isEmpty() && yours.isEmpty() && moods.isEmpty()

    companion object {
        /** The named mixes everyone gets, lower case, in the order Radio shows them. */
        private val STATIONS = listOf("my supermix", "discover mix", "new release mix", "replay mix", "archive mix")

        /** "Feel Good Supermix" names the mood "Feel Good". "My Supermix" is a station, matched before this. */
        private val MOOD_SUPERMIX = Regex("""^(.+?)\s+supermix$""", RegexOption.IGNORE_CASE)

        /** "Feel Good Mix 2": the mood "Feel Good", second. */
        private val NUMBERED_MIX = Regex("""^(.+?)\s+mix\s*(\d+)$""", RegexOption.IGNORE_CASE)

        /**
         * YouTube Music lists every mix in one run; Radio (from upstream 1.10, GPL-3.0) sorts them by name: the named mixes become
         * the stations, a mood's mixes ("Feel Good Supermix", "Feel Good Mix 1") gather under the mood in the order the page first
         * names it, and the rest are the listener's own ("Modern Punjabi Beats", "My Mix 1"). The names are read as YouTube sends
         * them; in another language every card that is not recognised falls to the listener's own, so none is ever lost.
         */
        fun of(mixes: List<ShelfItem>): MixGroups {
            val stations = mutableListOf<Pair<Int, ShelfItem>>()
            val yours = mutableListOf<ShelfItem>()
            val moods = LinkedHashMap<String, Pair<String, MutableList<Pair<Int, ShelfItem>>>>()
            fun addMood(name: String, order: Int, item: ShelfItem) {
                moods.getOrPut(name.lowercase()) { name to mutableListOf() }.second += order to item
            }
            mixes.distinctBy { it.browseId ?: it.title }.forEach { item ->
                val key = item.title.trim()
                val rank = STATIONS.indexOf(key.lowercase())
                val supermix = MOOD_SUPERMIX.matchEntire(key)
                val numbered = NUMBERED_MIX.matchEntire(key)
                when {
                    rank >= 0 -> stations += rank to item
                    supermix != null -> addMood(supermix.groupValues[1], 0, item)
                    numbered != null && !numbered.groupValues[1].equals("My", ignoreCase = true) ->
                        addMood(numbered.groupValues[1], numbered.groupValues[2].toInt(), item)
                    else -> yours += item
                }
            }
            return MixGroups(
                stations = stations.sortedBy { it.first }.map { it.second },
                yours = yours,
                moods = moods.values.map { (name, members) -> MoodMixes(name, members.sortedBy { it.first }.map { it.second }) },
            )
        }
    }
}
